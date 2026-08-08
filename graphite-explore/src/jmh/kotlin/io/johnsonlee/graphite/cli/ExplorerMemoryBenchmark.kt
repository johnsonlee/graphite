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
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
                request("/api/methods?limit=200")
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
        issue("/api/methods?limit=200")

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

    private fun cypherPath(query: String): String =
        "/api/cypher?limit=10&query=${URLEncoder.encode(query, StandardCharsets.UTF_8)}"

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
        ExploreRoutes().register(app, registry)
        port = app.port()
    }

    @TearDown
    fun tearDown() {
        runCatching { app.stop() }
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
 * Acceptance benchmark for a real multi-service corpus. The configured root
 * must contain exactly 50 distinct persisted graph directories totalling at
 * least 100M nodes. Unlike [MultiGraphExplorerBenchmark], this never repeats a
 * single fixture to simulate services.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xms512m", "-Xmx8g"])
open class RealMultiGraphMemoryBenchmark {

    @Param("50")
    var expectedGraphCount: Int = 0

    @Param("100000000")
    var minimumNodeCount: Long = 0

    @Param("8589934592")
    var memoryLimitBytes: Long = 0

    private lateinit var registry: GraphRegistry
    private lateinit var app: Javalin
    private var port: Int = 0
    private var setupMaxUsedHeapBytes: Long = 0
    private var totalNodes: Long = 0
    private var totalEdges: Long = 0

    @Setup
    fun setup() {
        totalNodes = 0
        totalEdges = 0
        val root = configuredGraphRoot()
        val graphPaths = discoverPersistedGraphs(root)
        require(graphPaths.size == expectedGraphCount) {
            "Real corpus must contain exactly $expectedGraphCount graphs, found ${graphPaths.size} under $root"
        }
        require(graphPaths.map { it.toRealPath() }.distinct().size == expectedGraphCount) {
            "Real corpus contains duplicate graph directories"
        }
        requireDistinctGraphData(graphPaths)

        registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        val loadMeasurement = measurePeakHeap {
            graphPaths.forEachIndexed { index, path ->
                val descriptor = registry.load("service-${index.toString().padStart(2, '0')}", path)
                totalNodes += descriptor.stats.nodes
                totalEdges += descriptor.stats.edges
            }
        }
        setupMaxUsedHeapBytes = loadMeasurement.maxUsedHeapBytes
        require(totalNodes >= minimumNodeCount) {
            "Real corpus has $totalNodes nodes; at least $minimumNodeCount are required"
        }
        check(setupMaxUsedHeapBytes <= memoryLimitBytes) {
            "50-graph load exceeded heap gate: peak=$setupMaxUsedHeapBytes limit=$memoryLimitBytes"
        }

        app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)
        ExploreRoutes().register(app, registry)
        port = app.port()
    }

    @TearDown
    fun tearDown() {
        runCatching { app.stop() }
        runCatching { registry.close() }
    }

    @Benchmark
    fun real50GraphFullCallSiteSearch(counters: ExplorerMemoryCounters): Long {
        return measureRealCorpusRequest(counters, "/api/call-sites?class=__graphite_absent__&limit=50")
    }

    @Benchmark
    fun real50GraphTopology(counters: ExplorerMemoryCounters): Long =
        measureRealCorpusRequest(counters, "/api/graph-overview")

    private fun measureRealCorpusRequest(counters: ExplorerMemoryCounters, path: String): Long {
        forceGcForRealCorpus()
        val before = realCorpusMemorySample()
        val measurement = measurePeakHeap {
            requestRealCorpus(path)
        }
        forceGcForRealCorpus()
        val after = realCorpusMemorySample()

        counters.usedHeapBeforeBytes = before.usedHeapBytes
        counters.usedHeapAfterBytes = after.usedHeapBytes
        counters.retainedHeapBytes = after.usedHeapBytes - before.usedHeapBytes
        counters.maxUsedHeapBytes = maxOf(setupMaxUsedHeapBytes, measurement.maxUsedHeapBytes)
        counters.committedHeapBeforeBytes = before.committedHeapBytes
        counters.committedHeapAfterBytes = after.committedHeapBytes
        counters.maxCommittedHeapBytes = maxOf(before.committedHeapBytes, after.committedHeapBytes)
        counters.residentSetBeforeBytes = before.residentSetBytes
        counters.residentSetAfterBytes = after.residentSetBytes
        counters.maxResidentSetBytes = maxOf(before.residentSetBytes, after.residentSetBytes)
        counters.memoryLimitBytes = memoryLimitBytes
        counters.graphCount = expectedGraphCount.toLong()
        counters.totalNodes = totalNodes
        counters.totalEdges = totalEdges
        counters.setupMaxUsedHeapBytes = setupMaxUsedHeapBytes

        check(counters.maxUsedHeapBytes <= memoryLimitBytes) {
            "50-graph request exceeded heap gate for $path: max=${counters.maxUsedHeapBytes} limit=$memoryLimitBytes"
        }
        return measurement.result
    }

    private fun requestRealCorpus(path: String): Long {
        val connection = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = REAL_CORPUS_HTTP_TIMEOUT_MS
        connection.readTimeout = REAL_CORPUS_HTTP_TIMEOUT_MS
        val code = connection.responseCode
        val body = if (code in REAL_CORPUS_HTTP_SUCCESS_RANGE) {
            connection.inputStream.use { it.readBytes() }
        } else {
            connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        }
        connection.disconnect()
        check(code in REAL_CORPUS_HTTP_SUCCESS_RANGE) { "GET $path returned $code: ${body.decodeToString()}" }
        return body.size.toLong()
    }

    private fun configuredGraphRoot(): Path {
        val configured = System.getProperty(REAL_CORPUS_ROOT_PROPERTY)
            ?: System.getenv(REAL_CORPUS_ROOT_ENV)
        require(!configured.isNullOrBlank()) {
            "Set -D$REAL_CORPUS_ROOT_PROPERTY=<root> or $REAL_CORPUS_ROOT_ENV for the real 50-graph corpus"
        }
        return Path.of(configured).toAbsolutePath().normalize().also { root ->
            require(root.isDirectory()) { "Real multi-graph root is not a directory: $root" }
        }
    }

    private fun discoverPersistedGraphs(root: Path): List<Path> =
        Files.walk(root, REAL_CORPUS_MAX_DEPTH).use { paths ->
            paths.filter { path -> path.fileName.toString() == REAL_CORPUS_NODE_DATA_FILE && path.isRegularFile() }
                .map { it.parent }
                .sorted()
                .toList()
        }

    private fun requireDistinctGraphData(graphPaths: List<Path>) {
        val nodeDataFiles = graphPaths.map { it.resolve(REAL_CORPUS_NODE_DATA_FILE) }
        nodeDataFiles.forEachIndexed { index, current ->
            for (otherIndex in 0 until index) {
                require(!Files.isSameFile(current, nodeDataFiles[otherIndex])) {
                    "Real corpus reuses graph data: ${graphPaths[otherIndex]} and ${graphPaths[index]}"
                }
            }
        }
    }

    private companion object {
        private const val REAL_CORPUS_ROOT_PROPERTY = "graphite.multigraph.root"
        private const val REAL_CORPUS_ROOT_ENV = "GRAPHITE_MULTIGRAPH_ROOT"
        private const val REAL_CORPUS_NODE_DATA_FILE = "graph.nodedata"
        private const val REAL_CORPUS_MAX_DEPTH = 6
        private const val REAL_CORPUS_HTTP_TIMEOUT_MS = 30 * 60 * 1_000
        private val REAL_CORPUS_HTTP_SUCCESS_RANGE = 200..299
    }
}

private data class PeakHeapMeasurement<T>(val result: T, val maxUsedHeapBytes: Long)

private fun <T> measurePeakHeap(block: () -> T): PeakHeapMeasurement<T> {
    val running = AtomicBoolean(true)
    val maximum = AtomicLong(realCorpusMemorySample().usedHeapBytes)
    val sampler = thread(start = true, isDaemon = true, name = "graphite-heap-peak-sampler") {
        while (running.get()) {
            maximum.accumulateAndGet(realCorpusMemorySample().usedHeapBytes, ::maxOf)
            LockSupport.parkNanos(REAL_CORPUS_SAMPLE_NANOS)
        }
    }
    return try {
        val result = block()
        maximum.accumulateAndGet(realCorpusMemorySample().usedHeapBytes, ::maxOf)
        PeakHeapMeasurement(result, maximum.get())
    } finally {
        running.set(false)
        sampler.join()
    }
}

private fun realCorpusMemorySample(): MemorySample {
    val runtime = Runtime.getRuntime()
    val committed = runtime.totalMemory()
    return MemorySample(committed - runtime.freeMemory(), committed, committed)
}

private fun forceGcForRealCorpus() {
    repeat(REAL_CORPUS_GC_ATTEMPTS) {
        System.gc()
        System.runFinalization()
        Thread.sleep(REAL_CORPUS_GC_PAUSE_MS)
    }
}

private const val REAL_CORPUS_SAMPLE_NANOS = 5_000_000L
private const val REAL_CORPUS_GC_ATTEMPTS = 3
private const val REAL_CORPUS_GC_PAUSE_MS = 100L

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

    @JvmField
    var graphCount: Long = 0

    @JvmField
    var totalNodes: Long = 0

    @JvmField
    var totalEdges: Long = 0

    @JvmField
    var setupMaxUsedHeapBytes: Long = 0
}

private object ExplorerBenchmarkCorpus {
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
