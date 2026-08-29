package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import io.johnsonlee.graphite.webgraph.GraphStore
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx4g"])
open class ExplorerMemoryBenchmark {

    @Param("3")
    var repeats: Int = 0

    @Param("MAPPED")
    lateinit var loadMode: String

    @Param("512")
    var sampledNodeCount: Int = 0

    @Param("32")
    var waterlineWarmupCycles: Int = 0

    @Param("256")
    var waterlineMeasuredCycles: Int = 0

    @Param("4294967296")
    var memoryLimitBytes: Long = 0

    @Param("16777216")
    var stableHeapGrowthLimitBytes: Long = 0

    @Param("67108864")
    var stableResidentGrowthLimitBytes: Long = 0

    private lateinit var graph: Graph
    private lateinit var app: Javalin
    private lateinit var sampledNodeIds: IntArray
    private var port: Int = 0
    private var centerNodeId: Int = 0

    @Setup
    fun setup() {
        graph = GraphStore.load(
            ExplorerBenchmarkCorpus.persistedAndroidGraph(),
            GraphStore.LoadMode.valueOf(loadMode)
        )
        app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)
        ExploreRoutes().register(app, graph)
        port = app.port()
        centerNodeId = graph.nodes(CallSiteNode::class.java).firstOrNull()?.id?.value
            ?: graph.nodes(Node::class.java).first().id.value
        sampledNodeIds = selectNodeSamples()
    }

    @TearDown
    fun tearDown() {
        runCatching { app.stop() }
        runCatching { (graph as? Closeable)?.close() }
    }

    @Benchmark
    fun android_initialExplorerSession(counters: ExplorerMemoryCounters): Long =
        measureRetainedHeap(counters) {
            request("/api/graphs") +
                request("/api/overview?limit=200") +
                request(cypherPath("MATCH (n:ReturnNode) RETURN n LIMIT 200", 200))
        }

    @Benchmark
    fun android_browserForwardExploration(counters: ExplorerMemoryCounters): Long =
        measureRetainedHeap(counters) {
            var bytes = 0L
            repeat(repeats) {
                bytes += request("/api/graphs/standalone/node/$centerNodeId")
                bytes += request("/api/graphs/standalone/node/$centerNodeId/outgoing?limit=200")
                bytes += request("/api/graphs/standalone/subgraph?center=$centerNodeId&depth=2&direction=outgoing")
            }
            bytes
        }

    @Benchmark
    fun android_longRunningExplorerWaterline(counters: ExplorerMemoryCounters): Long =
        measureWaterline(counters) { cycle, issue ->
            val nodeId = sampledNodeIds[cycle % sampledNodeIds.size]
            issue("/api/graphs/standalone/node/$nodeId")
            issue("/api/graphs/standalone/node/$nodeId/outgoing?limit=200")
            if (cycle % SUBGRAPH_SAMPLE_INTERVAL == 0) {
                issue("/api/graphs/standalone/subgraph?center=$centerNodeId&depth=2&direction=outgoing")
            }
            if (cycle % CYPHER_SAMPLE_INTERVAL == 0) {
                issue(cypherPath("MATCH (n:CallSiteNode) RETURN n LIMIT 10"))
            }
        }

    @Benchmark
    fun android_incomingExplorerWaterline(counters: ExplorerMemoryCounters): Long =
        measureWaterline(counters) { cycle, issue ->
            val nodeId = sampledNodeIds[cycle % sampledNodeIds.size]
            issue("/api/graphs/standalone/node/$nodeId")
            issue("/api/graphs/standalone/node/$nodeId/incoming?limit=200")
            if (cycle % SUBGRAPH_SAMPLE_INTERVAL == 0) {
                issue("/api/graphs/standalone/subgraph?center=$centerNodeId&depth=2&direction=incoming")
            }
            if (cycle % CYPHER_SAMPLE_INTERVAL == 0) {
                issue(cypherPath("MATCH (n:CallSiteNode) RETURN n LIMIT 10"))
            }
        }

    private fun measureWaterline(
        counters: ExplorerMemoryCounters,
        issueCycle: (cycle: Int, issue: (String) -> Unit) -> Unit
    ): Long {
        var bytes = 0L
        var maxUsedHeapBytes = 0L
        var maxCommittedHeapBytes = 0L
        var maxResidentSetBytes = 0L

        fun record(): MemorySample {
            val sample = currentMemorySample()
            maxUsedHeapBytes = maxOf(maxUsedHeapBytes, sample.usedHeapBytes)
            maxCommittedHeapBytes = maxOf(maxCommittedHeapBytes, sample.committedHeapBytes)
            maxResidentSetBytes = maxOf(maxResidentSetBytes, sample.residentSetBytes)
            return sample
        }

        fun issue(path: String) {
            bytes += request(path)
            record()
        }

        forceGc()
        val before = record()
        issue("/api/graphs")
        issue("/api/overview?limit=200")
        issue(cypherPath("MATCH (n:ReturnNode) RETURN n LIMIT 200", 200))

        repeat(waterlineWarmupCycles) { cycle -> issueCycle(cycle, ::issue) }
        forceGc()
        val steadyBefore = record()

        repeat(waterlineMeasuredCycles) { cycle ->
            issueCycle(cycle + waterlineWarmupCycles, ::issue)
        }

        forceGc()
        val after = record()
        val postWarmupHeapGrowthBytes = maxOf(0L, after.usedHeapBytes - steadyBefore.usedHeapBytes)
        val postWarmupResidentGrowthBytes = maxOf(0L, after.residentSetBytes - steadyBefore.residentSetBytes)

        counters.usedHeapBeforeBytes = before.usedHeapBytes
        counters.usedHeapAfterBytes = after.usedHeapBytes
        counters.retainedHeapBytes = after.usedHeapBytes - before.usedHeapBytes
        counters.committedHeapBeforeBytes = before.committedHeapBytes
        counters.committedHeapAfterBytes = after.committedHeapBytes
        counters.maxUsedHeapBytes = maxUsedHeapBytes
        counters.maxCommittedHeapBytes = maxCommittedHeapBytes
        counters.residentSetBeforeBytes = before.residentSetBytes
        counters.residentSetAfterBytes = after.residentSetBytes
        counters.steadyResidentSetBeforeBytes = steadyBefore.residentSetBytes
        counters.maxResidentSetBytes = maxResidentSetBytes
        counters.steadyUsedHeapBeforeBytes = steadyBefore.usedHeapBytes
        counters.postWarmupHeapGrowthBytes = postWarmupHeapGrowthBytes
        counters.postWarmupResidentGrowthBytes = postWarmupResidentGrowthBytes
        counters.memoryLimitBytes = memoryLimitBytes
        counters.stableHeapGrowthLimitBytes = stableHeapGrowthLimitBytes
        counters.stableResidentGrowthLimitBytes = stableResidentGrowthLimitBytes
        counters.residentSetMeasured = if (residentSetBytes() != null) 1 else 0

        check(maxUsedHeapBytes <= memoryLimitBytes) {
            "Explorer heap waterline exceeded limit: max=$maxUsedHeapBytes limit=$memoryLimitBytes"
        }
        check(postWarmupHeapGrowthBytes <= stableHeapGrowthLimitBytes) {
            "Explorer heap kept growing after warmup: growth=$postWarmupHeapGrowthBytes " +
                "limit=$stableHeapGrowthLimitBytes"
        }
        if (counters.residentSetMeasured == 1L) {
            check(postWarmupResidentGrowthBytes <= stableResidentGrowthLimitBytes) {
                "Explorer RSS kept growing after warmup: growth=$postWarmupResidentGrowthBytes " +
                    "limit=$stableResidentGrowthLimitBytes"
            }
        }

        return bytes
    }

    private fun selectNodeSamples(): IntArray {
        val target = sampledNodeCount.coerceAtLeast(1)
        val nodeCount = graph.nodeCount(Node::class.java) ?: 0L
        val sampled = linkedSetOf<Int>()
        if (nodeCount > 0L) {
            val stride = maxOf(1L, nodeCount / target)
            repeat(target * CANDIDATE_MULTIPLIER) { index ->
                val candidate = 1L + (index.toLong() * stride % nodeCount)
                if (candidate <= Int.MAX_VALUE) {
                    graph.node(io.johnsonlee.graphite.core.NodeId(candidate.toInt()))?.let { sampled += it.id.value }
                }
            }
        }
        if (sampled.isEmpty()) {
            graph.nodes(Node::class.java).take(target).forEach { sampled += it.id.value }
        }
        return sampled.toIntArray().takeIf { it.isNotEmpty() } ?: intArrayOf(centerNodeId)
    }

    private fun cypherPath(query: String, limit: Int = 10): String =
        "/api/cypher?limit=$limit&query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"

    private fun request(path: String): Long {
        val connection = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        val code = connection.responseCode
        val body = if (code in HTTP_SUCCESS_RANGE) {
            connection.inputStream.use { it.readBytes() }
        } else {
            connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        }
        connection.disconnect()
        check(code in HTTP_SUCCESS_RANGE) { "GET $path returned $code: ${body.decodeToString()}" }
        return body.size.toLong()
    }

    private fun measureRetainedHeap(counters: ExplorerMemoryCounters, action: () -> Long): Long {
        forceGc()
        val before = currentMemorySample()
        val bytes = action()
        forceGc()
        val after = currentMemorySample()
        counters.usedHeapBeforeBytes = before.usedHeapBytes
        counters.usedHeapAfterBytes = after.usedHeapBytes
        counters.retainedHeapBytes = after.usedHeapBytes - before.usedHeapBytes
        counters.committedHeapBeforeBytes = before.committedHeapBytes
        counters.committedHeapAfterBytes = after.committedHeapBytes
        counters.maxUsedHeapBytes = maxOf(before.usedHeapBytes, after.usedHeapBytes)
        counters.maxCommittedHeapBytes = maxOf(before.committedHeapBytes, after.committedHeapBytes)
        counters.residentSetBeforeBytes = before.residentSetBytes
        counters.residentSetAfterBytes = after.residentSetBytes
        counters.maxResidentSetBytes = maxOf(before.residentSetBytes, after.residentSetBytes)
        counters.residentSetMeasured = if (residentSetBytes() != null) 1 else 0
        return bytes
    }

    private fun currentMemorySample(): MemorySample {
        val runtime = Runtime.getRuntime()
        val committedHeapBytes = runtime.totalMemory()
        val usedHeapBytes = committedHeapBytes - runtime.freeMemory()
        return MemorySample(
            usedHeapBytes = usedHeapBytes,
            committedHeapBytes = committedHeapBytes,
            residentSetBytes = residentSetBytes() ?: committedHeapBytes
        )
    }

    private fun forceGc() {
        repeat(GC_ATTEMPTS) {
            System.gc()
            System.runFinalization()
            Thread.sleep(GC_PAUSE_MS)
        }
    }

    private fun residentSetBytes(): Long? =
        residentSetBytesFromProcStatus() ?: residentSetBytesFromPs()

    private fun residentSetBytesFromProcStatus(): Long? {
        val status = Path.of("/proc/self/status")
        if (!Files.isRegularFile(status)) return null
        val line = Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val kilobytes = line.split(WHITESPACE).firstNotNullOfOrNull { it.toLongOrNull() } ?: return null
        return kilobytes * BYTES_PER_KIB
    }

    private fun residentSetBytesFromPs(): Long? {
        val process = ProcessBuilder(
            "ps",
            "-o",
            "rss=",
            "-p",
            ProcessHandle.current().pid().toString()
        ).redirectErrorStream(true).start()
        if (!process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().use { it.readText() }
            .lineSequence()
            .mapNotNull { it.trim().toLongOrNull() }
            .firstOrNull()
            ?.times(BYTES_PER_KIB)
    }

    private companion object {
        private const val HTTP_TIMEOUT_MS = 120_000
        private const val GC_ATTEMPTS = 3
        private const val GC_PAUSE_MS = 100L
        private const val SUBGRAPH_SAMPLE_INTERVAL = 8
        private const val CYPHER_SAMPLE_INTERVAL = 16
        private const val CANDIDATE_MULTIPLIER = 2
        private const val BYTES_PER_KIB = 1024L
        private const val PS_TIMEOUT_SECONDS = 2L
        private val WHITESPACE = Regex("\\s+")
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class MultiGraphExplorerBenchmark {

    @Param("100")
    var graphCount: Int = 0

    @Param("32")
    var concurrency: Int = 0

    @Param("1")
    var queriesPerGraph: Int = 0

    @Param("8589934592")
    var memoryLimitBytes: Long = 0

    private lateinit var root: Path
    private lateinit var registry: GraphRegistry
    private lateinit var topology: TopologyService
    private lateinit var app: Javalin
    private var port: Int = 0

    @Setup
    fun setup() {
        root = Files.createTempDirectory("graphite-explorer-multigraph")
        registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        val graphPath = ExplorerBenchmarkCorpus.persistedAndroidGraph()
        repeat(graphCount) { index ->
            registry.load("service-$index", graphPath, GraphStore.LoadMode.MAPPED)
        }
        app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)
        topology = TopologyService(registry, emptyList(), root)
        topology.rebuild()
        ExploreRoutes().register(app, registry, topology)
        port = app.port()
    }

    @TearDown
    fun tearDown() {
        runCatching { app.stop() }
        runCatching { topology.close() }
        runCatching { registry.close() }
        runCatching { root.toFile().deleteRecursively() }
    }

    @Benchmark
    fun android_multiGraphScopedCypher(counters: ExplorerMemoryCounters): Long {
        forceGc()
        val before = currentMemorySample()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency)
        val bytes = try {
            val tasks = (0 until graphCount).flatMap { index ->
                List(queriesPerGraph) {
                    java.util.concurrent.Callable {
                        request(cypherPath(index, "MATCH (n:CallSiteNode) RETURN n LIMIT 10"))
                    }
                }
            }
            executor.invokeAll(tasks).sumOf { it.get() }
        } finally {
            executor.shutdownNow()
        }
        forceGc()
        val after = currentMemorySample()

        counters.usedHeapBeforeBytes = before.usedHeapBytes
        counters.usedHeapAfterBytes = after.usedHeapBytes
        counters.retainedHeapBytes = after.usedHeapBytes - before.usedHeapBytes
        counters.committedHeapBeforeBytes = before.committedHeapBytes
        counters.committedHeapAfterBytes = after.committedHeapBytes
        counters.maxUsedHeapBytes = maxOf(before.usedHeapBytes, after.usedHeapBytes)
        counters.maxCommittedHeapBytes = maxOf(before.committedHeapBytes, after.committedHeapBytes)
        counters.residentSetBeforeBytes = before.residentSetBytes
        counters.residentSetAfterBytes = after.residentSetBytes
        counters.maxResidentSetBytes = maxOf(before.residentSetBytes, after.residentSetBytes)
        counters.memoryLimitBytes = memoryLimitBytes
        counters.residentSetMeasured = if (residentSetBytes() != null) 1 else 0

        check(counters.maxUsedHeapBytes <= memoryLimitBytes) {
            "Multi-graph explorer heap exceeded limit: max=${counters.maxUsedHeapBytes} limit=$memoryLimitBytes"
        }
        return bytes
    }

    private fun cypherPath(graphIndex: Int, query: String): String =
        "/api/graphs/service-$graphIndex/cypher?limit=10&query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"

    private fun request(path: String): Long {
        val connection = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        val code = connection.responseCode
        val body = if (code in HTTP_SUCCESS_RANGE) {
            connection.inputStream.use { it.readBytes() }
        } else {
            connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        }
        connection.disconnect()
        check(code in HTTP_SUCCESS_RANGE) { "GET $path returned $code: ${body.decodeToString()}" }
        return body.size.toLong()
    }

    private fun currentMemorySample(): MemorySample {
        val runtime = Runtime.getRuntime()
        val committedHeapBytes = runtime.totalMemory()
        val usedHeapBytes = committedHeapBytes - runtime.freeMemory()
        return MemorySample(
            usedHeapBytes = usedHeapBytes,
            committedHeapBytes = committedHeapBytes,
            residentSetBytes = residentSetBytes() ?: committedHeapBytes
        )
    }

    private fun forceGc() {
        repeat(GC_ATTEMPTS) {
            System.gc()
            System.runFinalization()
            Thread.sleep(GC_PAUSE_MS)
        }
    }

    private fun residentSetBytes(): Long? =
        residentSetBytesFromProcStatus() ?: residentSetBytesFromPs()

    private fun residentSetBytesFromProcStatus(): Long? {
        val status = Path.of("/proc/self/status")
        if (!Files.isRegularFile(status)) return null
        val line = Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") } ?: return null
        val kilobytes = line.split(WHITESPACE).firstNotNullOfOrNull { it.toLongOrNull() } ?: return null
        return kilobytes * BYTES_PER_KIB
    }

    private fun residentSetBytesFromPs(): Long? {
        val process = ProcessBuilder(
            "ps",
            "-o",
            "rss=",
            "-p",
            ProcessHandle.current().pid().toString()
        ).redirectErrorStream(true).start()
        if (!process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        return process.inputStream.bufferedReader().use { it.readText() }
            .lineSequence()
            .mapNotNull { it.trim().toLongOrNull() }
            .firstOrNull()
            ?.times(BYTES_PER_KIB)
    }

    private companion object {
        private const val HTTP_TIMEOUT_MS = 120_000
        private const val GC_ATTEMPTS = 3
        private const val GC_PAUSE_MS = 100L
        private const val BYTES_PER_KIB = 1024L
        private const val PS_TIMEOUT_SECONDS = 2L
        private val WHITESPACE = Regex("\\s+")
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}

/**
 * Measures the real multi-graph startup path with and without topology materialization.
 * Both variants load mapped instances of the same Android-scale graph through GraphRegistry.
 * This stresses per-graph heap and logical query cardinality, but mapped file pages may be shared.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class TopologyStartupBenchmark {

    @Param("3")
    var graphCount: Int = 0

    @Param("bounded")
    lateinit var topologyShape: String

    private lateinit var graphPath: Path

    @Setup(Level.Trial)
    fun setup() {
        graphPath = ExplorerBenchmarkCorpus.persistedAndroidGraph()
    }

    @Benchmark
    fun android_loadServiceGraphs(): Long = runStartup(buildTopology = false)

    @Benchmark
    fun android_loadAndBuildTopology(): Long = runStartup(buildTopology = true)

    @Benchmark
    fun android_rejectOversizedUnionTopology(): Long = runStartup(
        buildTopology = true,
        queryOverride = topologyStartupQuery("union-over-budget"),
        expectRowLimit = true
    )

    private fun runStartup(
        buildTopology: Boolean,
        queryOverride: TopologyQuery? = null,
        expectRowLimit: Boolean = false
    ): Long {
        val root = Files.createTempDirectory("graphite-topology-startup")
        val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        var topology: TopologyService? = null
        return try {
            repeat(graphCount) { index ->
                registry.load("service-$index", graphPath, GraphStore.LoadMode.MAPPED)
            }
            topology = TopologyService(
                registry,
                if (buildTopology) listOf(queryOverride ?: topologyStartupQuery(topologyShape)) else emptyList(),
                root
            )
            try {
                val summary = topology.rebuild()
                check(!expectRowLimit) { "Expected the oversized topology query to be rejected" }
                summary.graphCount.toLong() + summary.relationCount + summary.matchedRows
            } catch (error: IllegalArgumentException) {
                check(expectRowLimit && error.message.orEmpty().contains("100000 row limit")) { throw error }
                graphCount.toLong()
            }
        } finally {
            topology?.close()
            registry.close()
            root.toFile().deleteRecursively()
        }
    }
}

/** End-to-end HTTP latency for querying an already materialized topology snapshot. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class TopologyApiBenchmark {

    private lateinit var root: Path
    private lateinit var registry: GraphRegistry
    private lateinit var topology: TopologyService
    private lateinit var app: Javalin
    private var port: Int = 0

    @Setup(Level.Trial)
    fun setup() {
        root = Files.createTempDirectory("graphite-topology-api")
        registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        val graphPath = ExplorerBenchmarkCorpus.persistedAndroidGraph()
        repeat(3) { index -> registry.load("service-$index", graphPath, GraphStore.LoadMode.MAPPED) }
        topology = TopologyService(registry, listOf(topologyStartupQuery("bounded")), root).also { it.rebuild() }
        app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)
        ExploreRoutes().register(app, registry, topology)
        port = app.port()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        runCatching { app.stop() }
        runCatching { topology.close() }
        runCatching { registry.close() }
        runCatching { root.toFile().deleteRecursively() }
    }

    @Benchmark
    fun android_topologyHttpQuery(): Long {
        val connection = URI("http://localhost:$port/api/topology").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 120_000
        connection.readTimeout = 120_000
        val code = connection.responseCode
        val body = connection.inputStream.use { it.readBytes() }
        connection.disconnect()
        check(code == 200)
        return body.size.toLong()
    }
}

/**
 * Measures topology memory and mapped-file growth on top of three already-loaded Android-scale graphs.
 *
 * Forced GC runs in invocation fixtures, outside the timed benchmark method. The reported
 * SingleShotTime score therefore measures topology construction, while the auxiliary counters
 * distinguish the loaded-service-graph baseline, sampled build peak, post-GC retained heap and
 * process RSS, and the exact internal topology file size. Retained measurements come from the
 * preceding identical invocation; one warmup invocation makes them available for every measured
 * invocation without adding GC or RSS probing to the JMH score.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class TopologyHeapBenchmark {

    @Param("details-heavy", "relation-heavy", "long-strings")
    lateinit var scale: String

    private lateinit var root: Path
    private lateinit var registry: GraphRegistry
    private lateinit var query: TopologyQuery
    private lateinit var sampler: TopologyHeapBenchmarkSampler
    private var retainedService: TopologyService? = null
    private var buildBaselineBytes: Long = 0
    private var retainedBaselineBytes: Long = 0
    private var peakUsedHeapBytes: Long = 0
    private var retainedUsedHeapBytes: Long = 0
    private var retainedDeltaBytes: Long = 0
    private var buildBaselineResidentBytes: Long = 0
    private var retainedBaselineResidentBytes: Long = 0
    private var retainedResidentBytes: Long = 0
    private var retainedResidentDeltaBytes: Long = 0

    @Setup(Level.Trial)
    fun setupTrial() {
        root = Files.createTempDirectory("graphite-topology-heap")
        registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        val graphPath = ExplorerBenchmarkCorpus.persistedAndroidGraph()
        repeat(TOPOLOGY_HEAP_GRAPH_COUNT) { index ->
            registry.load("service-$index", graphPath, GraphStore.LoadMode.MAPPED)
        }
        query = topologyHeapQuery(TopologyHeapBenchmarkScale.parse(scale))
        sampler = TopologyHeapBenchmarkSampler()
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        runCatching { retainedService?.close() }
        retainedService = null
        runCatching { sampler.close() }
        runCatching { registry.close() }
        runCatching { root.toFile().deleteRecursively() }
    }

    @Setup(Level.Invocation)
    fun setupInvocation() {
        retainedService?.let { service ->
            sampler.stop()
            forceTopologyHeapGc()
            retainedUsedHeapBytes = topologyUsedHeapBytes()
            retainedBaselineBytes = buildBaselineBytes
            retainedDeltaBytes = retainedUsedHeapBytes - retainedBaselineBytes
            retainedResidentBytes = topologyResidentSetBytes()
            retainedBaselineResidentBytes = buildBaselineResidentBytes
            retainedResidentDeltaBytes = maxOf(0, retainedResidentBytes - retainedBaselineResidentBytes)
            check(service.summary().matchedRows > 0)
            service.close()
        }
        retainedService = null
        forceTopologyHeapGc()
        buildBaselineBytes = topologyUsedHeapBytes()
        buildBaselineResidentBytes = topologyResidentSetBytes()
        sampler.start(buildBaselineBytes)
    }

    @Benchmark
    fun android_buildTopologyHeap(counters: TopologyHeapBenchmarkCounters): Long {
        val service = TopologyService(registry, listOf(query), root)
        val topology = service.rebuild()
        val mappedBytes = requireNotNull(service.openApiStream()).input.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
            }
            total
        }
        retainedService = service
        peakUsedHeapBytes = sampler.stop()

        counters.graphCount = topology.graphCount.toLong()
        counters.relationCount = topology.relationCount.toLong()
        counters.matchedRows = topology.matchedRows.toLong()
        counters.buildBaselineBytes = buildBaselineBytes
        counters.peakUsedHeapBytes = peakUsedHeapBytes
        counters.peakDeltaBytes = peakUsedHeapBytes - buildBaselineBytes
        counters.retainedBaselineBytes = retainedBaselineBytes
        counters.retainedUsedHeapBytes = retainedUsedHeapBytes
        counters.retainedDeltaBytes = retainedDeltaBytes
        counters.buildBaselineResidentBytes = buildBaselineResidentBytes
        counters.retainedBaselineResidentBytes = retainedBaselineResidentBytes
        counters.retainedResidentBytes = retainedResidentBytes
        counters.retainedResidentDeltaBytes = retainedResidentDeltaBytes
        counters.topologyFileBytes = Files.walk(service.currentBuildDir()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
        counters.mappedJsonBytes = mappedBytes
        return topology.graphCount.toLong() + topology.relationCount + topology.matchedRows
    }
}

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
open class TopologyHeapBenchmarkCounters {
    @JvmField
    var buildBaselineBytes: Long = 0

    @JvmField
    var retainedBaselineBytes: Long = 0

    @JvmField
    var peakUsedHeapBytes: Long = 0

    @JvmField
    var peakDeltaBytes: Long = 0

    @JvmField
    var retainedUsedHeapBytes: Long = 0

    @JvmField
    var retainedDeltaBytes: Long = 0

    @JvmField
    var buildBaselineResidentBytes: Long = 0

    @JvmField
    var retainedBaselineResidentBytes: Long = 0

    @JvmField
    var retainedResidentBytes: Long = 0

    @JvmField
    var retainedResidentDeltaBytes: Long = 0

    @JvmField
    var topologyFileBytes: Long = 0

    @JvmField
    var mappedJsonBytes: Long = 0

    @JvmField
    var graphCount: Long = 0

    @JvmField
    var relationCount: Long = 0

    @JvmField
    var matchedRows: Long = 0
}

private enum class TopologyHeapBenchmarkScale(
    val rows: Int,
    val relations: Int,
    val operationPadding: Int,
    val evidencePadding: Int
) {
    DETAILS_HEAVY(rows = 100_000, relations = 1_000, operationPadding = 64, evidencePadding = 256),
    RELATION_HEAVY(rows = 100_000, relations = 100_000, operationPadding = 0, evidencePadding = 0),
    LONG_STRINGS(rows = 100_000, relations = 1_000, operationPadding = 128, evidencePadding = 1_024);

    companion object {
        fun parse(value: String): TopologyHeapBenchmarkScale = when (value) {
            "details-heavy" -> DETAILS_HEAVY
            "relation-heavy" -> RELATION_HEAVY
            "long-strings" -> LONG_STRINGS
            else -> error("Unknown topology heap scale: $value")
        }
    }
}

private class TopologyHeapBenchmarkSampler : Closeable {
    private val running = AtomicBoolean(true)
    private val sampling = AtomicBoolean(false)
    private val maximum = AtomicLong(0)
    private val thread = Thread({ sampleLoop() }, "topology-heap-sampler").apply {
        isDaemon = true
        start()
    }

    fun start(baselineBytes: Long) {
        maximum.set(baselineBytes)
        sampling.set(true)
    }

    fun stop(): Long {
        sampling.set(false)
        maximum.accumulateAndGet(topologyUsedHeapBytes(), ::maxOf)
        return maximum.get()
    }

    override fun close() {
        sampling.set(false)
        running.set(false)
        thread.join(TOPOLOGY_HEAP_SAMPLER_JOIN_MS)
    }

    private fun sampleLoop() {
        while (running.get()) {
            if (sampling.get()) {
                maximum.accumulateAndGet(topologyUsedHeapBytes(), ::maxOf)
            }
            LockSupport.parkNanos(TOPOLOGY_HEAP_SAMPLE_INTERVAL_NS)
        }
    }
}

private fun topologyHeapQuery(scale: TopologyHeapBenchmarkScale): TopologyQuery = TopologyQuery(
    "heap-${scale.name.lowercase()}.cypher",
    """
    UNWIND range(0, ${scale.rows - 1}) AS row
    RETURN 'service-0' AS source,
           CASE row % 2 WHEN 0 THEN 'service-1' ELSE 'service-2' END AS target,
           'rpc-' + toString(row % ${scale.relations}) AS protocol,
           '${"o".repeat(scale.operationPadding)}operation-' + toString(row) AS operation,
           '${"e".repeat(scale.evidencePadding)}evidence-' + toString(row) AS evidence
    """.trimIndent()
)

private fun topologyUsedHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun topologyResidentSetBytes(): Long {
    val procStatus = Path.of("/proc/self/status")
    if (Files.isRegularFile(procStatus)) {
        val kilobytes = Files.readAllLines(procStatus)
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.firstNotNullOfOrNull { it.toLongOrNull() }
        if (kilobytes != null) return kilobytes * 1_024L
    }
    val process = ProcessBuilder(
        "ps",
        "-o",
        "rss=",
        "-p",
        ProcessHandle.current().pid().toString()
    ).redirectErrorStream(true).start()
    check(process.waitFor(2L, TimeUnit.SECONDS)) { "Timed out while measuring topology benchmark RSS" }
    check(process.exitValue() == 0) { "Failed to measure topology benchmark RSS" }
    val kilobytes = process.inputStream.bufferedReader().use { it.readText() }
        .lineSequence()
        .mapNotNull { it.trim().toLongOrNull() }
        .firstOrNull()
    return checkNotNull(kilobytes) { "Topology benchmark RSS was unavailable" } * 1_024L
}

private fun forceTopologyHeapGc() {
    repeat(TOPOLOGY_HEAP_GC_ATTEMPTS) {
        System.gc()
        System.runFinalization()
        Thread.sleep(TOPOLOGY_HEAP_GC_PAUSE_MS)
    }
}

private const val TOPOLOGY_HEAP_GRAPH_COUNT = 3
private const val TOPOLOGY_HEAP_GC_ATTEMPTS = 3
private const val TOPOLOGY_HEAP_GC_PAUSE_MS = 100L
private const val TOPOLOGY_HEAP_SAMPLE_INTERVAL_NS = 1_000_000L
private const val TOPOLOGY_HEAP_SAMPLER_JOIN_MS = 5_000L

private fun topologyStartupQuery(shape: String): TopologyQuery = when (shape) {
    "bounded" -> TopologyQuery(
        "android-topology.cypher",
        """
        MATCH (call:CallSiteNode)
        WHERE graphId(call) = 'service-0'
        RETURN 'service-0' AS source,
               'service-1' AS target,
               'benchmark-rpc' AS protocol,
               call.callee_name AS operation
        LIMIT 100
        """.trimIndent()
    )
    "union-broad" -> TopologyQuery(
        "android-topology-union.cypher",
        topologyUnionQuery(MAX_TOPOLOGY_BENCHMARK_ROWS / TOPOLOGY_UNION_BRANCHES)
    )
    "union-over-budget" -> TopologyQuery(
        "android-topology-union-over-budget.cypher",
        topologyUnionQuery(MAX_TOPOLOGY_BENCHMARK_ROWS)
    )
    else -> error("Unknown topology startup shape: $shape")
}

private fun topologyUnionQuery(rowsPerBranch: Int): String =
    List(TOPOLOGY_UNION_BRANCHES) { branch ->
        """
        MATCH (call:CallSiteNode)
        RETURN graphId(call) AS source,
               CASE graphId(call) WHEN 'service-0' THEN 'service-1' ELSE 'service-0' END AS target,
               'benchmark-rpc-$branch' AS protocol,
               call.callee_name AS operation,
               call.callee_class AS evidence
        LIMIT $rowsPerBranch
        """.trimIndent()
    }.joinToString("\nUNION ALL\n")

private const val TOPOLOGY_UNION_BRANCHES = 8
private const val MAX_TOPOLOGY_BENCHMARK_ROWS = 100_000

private data class MemorySample(
    val usedHeapBytes: Long,
    val committedHeapBytes: Long,
    val residentSetBytes: Long
)

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
open class ExplorerMemoryCounters {
    @JvmField
    var usedHeapBeforeBytes: Long = 0

    @JvmField
    var usedHeapAfterBytes: Long = 0

    @JvmField
    var retainedHeapBytes: Long = 0

    @JvmField
    var committedHeapBeforeBytes: Long = 0

    @JvmField
    var committedHeapAfterBytes: Long = 0

    @JvmField
    var maxUsedHeapBytes: Long = 0

    @JvmField
    var maxCommittedHeapBytes: Long = 0

    @JvmField
    var residentSetBeforeBytes: Long = 0

    @JvmField
    var residentSetAfterBytes: Long = 0

    @JvmField
    var steadyResidentSetBeforeBytes: Long = 0

    @JvmField
    var maxResidentSetBytes: Long = 0

    @JvmField
    var steadyUsedHeapBeforeBytes: Long = 0

    @JvmField
    var postWarmupHeapGrowthBytes: Long = 0

    @JvmField
    var postWarmupResidentGrowthBytes: Long = 0

    @JvmField
    var memoryLimitBytes: Long = 0

    @JvmField
    var stableHeapGrowthLimitBytes: Long = 0

    @JvmField
    var stableResidentGrowthLimitBytes: Long = 0

    @JvmField
    var residentSetMeasured: Long = 0
}

internal object ExplorerBenchmarkCorpus {
    @Volatile
    private var cachedAndroidGraph: Path? = null

    fun persistedAndroidGraph(): Path = cachedAndroidGraph ?: synchronized(this) {
        cachedAndroidGraph ?: preparePersistedAndroidGraph().also { cachedAndroidGraph = it }
    }

    private fun preparePersistedAndroidGraph(): Path {
        System.getProperty(ANDROID_GRAPH_PATH_PROPERTY)?.let { configured ->
            val graphPath = Path.of(configured)
            require(graphPath.isDirectory() && hasPersistedGraph(graphPath)) {
                "Persisted Android graph not found at $graphPath"
            }
            return graphPath
        }

        val tempDir = Files.createTempDirectory("graphite-explorer-android-graph")
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { tempDir.toFile().deleteRecursively() }
        })
        buildPersistedGraph(resolveAndroidJar(), tempDir)
        return tempDir
    }

    private fun buildPersistedGraph(jarPath: Path, outputDir: Path) {
        if (hasPersistedGraph(outputDir)) return
        outputDir.toFile().deleteRecursively()
        Files.createDirectories(outputDir)

        val graph = JavaProjectLoader(
            LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )
        ).load(jarPath)
        try {
            GraphStore.save(graph, outputDir)
        } finally {
            runCatching { (graph as? Closeable)?.close() }
        }
    }

    private fun resolveAndroidJar(): Path {
        System.getProperty(ANDROID_JAR_PATH_PROPERTY)?.let { configured ->
            val jarPath = Path.of(configured)
            require(jarPath.isRegularFile()) { "Android fixture JAR not found at $jarPath" }
            return jarPath
        }

        findAndroidJarOnClasspath()?.let { return it }

        val cacheDir = Path.of(System.getProperty("user.home"), ".gradle", "caches")
        require(cacheDir.isDirectory()) { "Gradle cache not found at $cacheDir" }
        Files.walk(cacheDir, MAX_CACHE_WALK_DEPTH).use { matches ->
            return matches
                .filter { Files.isRegularFile(it) && isAndroidJar(it.fileName.toString()) }
                .sorted(Comparator.comparing<Path, String> { it.toString() }.reversed())
                .findFirst()
                .orElseThrow {
                    IllegalStateException(
                        "Unable to locate Android fixture JAR. " +
                            "Set -D$ANDROID_JAR_PATH_PROPERTY=<path> or resolve integration fixtures first."
                    )
                }
        }
    }

    private fun findAndroidJarOnClasspath(): Path? =
        System.getProperty("java.class.path")
            .split(System.getProperty("path.separator").toRegex())
            .asSequence()
            .mapNotNull { entry -> entry.takeIf { it.isNotBlank() }?.let(Path::of) }
            .filter { it.isRegularFile() && isAndroidJar(it.fileName.toString()) }
            .firstOrNull()

    private fun hasPersistedGraph(dir: Path): Boolean =
        dir.resolve("graph.nodedata").exists() &&
            dir.resolve("graph.metadata").exists() &&
            dir.resolve("graph.nodeindex").exists()

    private fun isAndroidJar(fileName: String): Boolean =
        fileName.startsWith("android-all-") && fileName.endsWith(".jar")

    private const val ANDROID_JAR_PATH_PROPERTY = "android.jar.path"
    private const val ANDROID_GRAPH_PATH_PROPERTY = "android.graph.path"
    private const val MAX_CACHE_WALK_DEPTH = 12
}
