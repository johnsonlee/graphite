package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
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
import java.security.MessageDigest
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
                requestMethodDiscovery()
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
        bytes += requestMethodDiscovery()
        record()

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

    private fun requestMethodDiscovery(): Long = request(
        cypherPath("MATCH (n:ReturnNode) RETURN n.method AS signature LIMIT 200", 200)
    ) { body ->
        val result = JsonParser.parseString(body.decodeToString()).asJsonObject
        val columns = result.getAsJsonArray("columns")
        val rows = result.getAsJsonArray("rows")
        check(columns.size() == 1 && columns[0].asString == "signature") {
            "Method discovery returned unexpected columns: $columns"
        }
        check(rows.size() == 200 && rows.all { row ->
            row.asJsonObject.get("signature")?.isJsonPrimitive == true
        }) { "Method discovery did not return 200 signatures" }
    }

    private fun request(path: String, validate: ((ByteArray) -> Unit)? = null): Long {
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
        validate?.invoke(body)
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

/**
 * Paired migration gate for the method-discovery HTTP surface.
 *
 * The candidate copy of this source is compiled into both revisions. A base server is therefore
 * exercised through its scoped `/methods` route, while a candidate server is exercised through
 * `MATCH (m:Method)`. Both paths are normalized to the same schema and checked against the same
 * heterogeneous mapped method indexes before their latency, CPU, and RSS measurements are compared.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class MethodDiscoveryCompatibilityBenchmark {

    @Param("4", "17", "36")
    var graphCount: Int = 0

    @Param("zero", "early", "middle", "late", "prefix", "suffix", "contains", "regex", "or", "count", "order")
    lateinit var scenario: String

    private lateinit var root: Path
    private lateinit var registry: GraphRegistry
    private lateinit var topology: TopologyService
    private lateinit var app: Javalin
    private lateinit var cypherGuard: CypherQueryGuard
    private lateinit var services: List<MethodBenchmarkService>
    private var port: Int = 0
    private var legacyMethodsRoute: Boolean = false

    @Setup
    fun setup() {
        root = Files.createTempDirectory("graphite-method-compatibility")
        registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        val fixtures = METHOD_CORPORA.associateWith { corpus ->
            val graphPath = ExplorerBenchmarkCorpus.persistedGraph(corpus)
            val graph = GraphStore.load(graphPath, GraphStore.LoadMode.MAPPED)
            try {
                MethodCompatibilityFixture.from(corpus, graph.methods(MethodPattern()).toList())
            } finally {
                runCatching { (graph as? Closeable)?.close() }
            }
        }
        services = List(graphCount) { index ->
            val corpus = METHOD_CORPORA[index % METHOD_CORPORA.size]
            val graphId = "service-$index"
            val fixture = fixtures.getValue(corpus)
            registry.load(graphId, ExplorerBenchmarkCorpus.persistedGraph(corpus), GraphStore.LoadMode.MAPPED)
            MethodBenchmarkService(graphId, corpus, fixture)
        }
        check(services.map(MethodBenchmarkService::corpus).withIndex().all { (index, corpus) ->
            corpus == METHOD_CORPORA[index % METHOD_CORPORA.size]
        }) { "Method benchmark registry did not preserve the heterogeneous corpus cycle" }
        check(services.map(MethodBenchmarkService::corpus).toSet() == METHOD_CORPORA.toSet()) {
            "Method benchmark registry did not include every real corpus"
        }
        topology = TopologyService(registry, emptyList(), root).also { it.rebuild() }
        app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)
        cypherGuard = CypherQueryGuard(TARGET_CYPHER_CONCURRENCY, TARGET_CYPHER_WORK_BUDGET)
        ExploreRoutes(cypherGuard).register(app, registry, topology)
        port = app.port()
        legacyMethodsRoute = rawRequest(legacyPath(services.first().fixture.className, 0)).code == HTTP_OK
        writeCompatibilityManifest()
    }

    @TearDown
    fun tearDown() {
        runCatching { app.stop() }
        runCatching { cypherGuard.close() }
        runCatching { topology.close() }
        runCatching { registry.close() }
        runCatching { root.toFile().deleteRecursively() }
    }

    @Benchmark
    fun methodScenarioGate(counters: MethodCompatibilityCounters): Long =
        measure(counters) {
            val scopedBytes = services.mapIndexed { graphIndex, service ->
                executeAndValidate(caseFor(scenario, service.fixture), service, graphIndex)
            }.sumOf { it.bytes }
            val rootBytes = services.distinctBy(MethodBenchmarkService::corpus).sumOf { source ->
                executeRootAndValidate(caseFor(scenario, source.fixture), source.corpus).bytes
            }
            scopedBytes + rootBytes
        }

    private fun caseFor(name: String, fixture: MethodCompatibilityFixture): MethodCompatibilityCase = when (name) {
        "zero" -> MethodCompatibilityCase(
            "zero",
            "m.class = ${cypherString(fixture.className)} AND m.name = '__graphite_never_present__'",
            fixture.className
        ) { false }
        "early" -> exactCase("early", fixture.early.record)
        "middle" -> exactCase("middle", fixture.middle.record)
        "late" -> exactCase("late", fixture.late.record)
        "prefix" -> MethodCompatibilityCase(
            "prefix",
            "m.class = ${cypherString(fixture.className)} AND m.name STARTS WITH ${cypherString(fixture.prefix)}",
            fixture.className
        ) { it.className == fixture.className && it.name.startsWith(fixture.prefix) }
        "suffix" -> MethodCompatibilityCase(
            "suffix",
            "m.class = ${cypherString(fixture.className)} AND m.name ENDS WITH ${cypherString(fixture.suffix)}",
            fixture.className
        ) { it.className == fixture.className && it.name.endsWith(fixture.suffix) }
        "contains" -> MethodCompatibilityCase(
            "contains",
            "m.class = ${cypherString(fixture.className)} AND m.name CONTAINS ${cypherString(fixture.contains)}",
            fixture.className
        ) { it.className == fixture.className && it.name.contains(fixture.contains) }
        "regex" -> MethodCompatibilityCase(
            "regex",
            "m.class = ${cypherString(fixture.className)} AND m.name =~ ${cypherString(fixture.regex)}",
            fixture.className
        ) { it.className == fixture.className && fixture.regex.toRegex().matches(it.name) }
        "or" -> MethodCompatibilityCase(
            "or",
            "m.class = ${cypherString(fixture.className)} AND " +
                "(m.name = ${cypherString(fixture.orNames.first)} OR " +
                "m.name = ${cypherString(fixture.orNames.second)})",
            fixture.className
        ) {
            it.className == fixture.className &&
                (it.name == fixture.orNames.first || it.name == fixture.orNames.second)
        }
        "count" -> MethodCompatibilityCase(
            "count",
            "m.class = ${cypherString(fixture.className)}",
            fixture.className,
            aggregate = MethodCompatibilityAggregate.COUNT
        ) { it.className == fixture.className }
        "order" -> MethodCompatibilityCase(
            "order",
            "m.class = ${cypherString(fixture.className)}",
            fixture.className,
            aggregate = MethodCompatibilityAggregate.ORDERED_TOP
        ) { it.className == fixture.className }
        else -> error("Unknown Method benchmark scenario: $name")
    }

    private fun exactCase(name: String, target: MethodCompatibilityRecord) = MethodCompatibilityCase(
        name,
        "m.signature = ${cypherString(target.signature)}",
        target.className
    ) { it.signature == target.signature }

    private fun executeAndValidate(
        case: MethodCompatibilityCase,
        service: MethodBenchmarkService,
        graphIndex: Int
    ): MethodCompatibilityResult {
        val expected = expected(case, service.fixture)
        val response = if (legacyMethodsRoute) {
            executeLegacy(case, graphIndex)
        } else {
            executeCypher(case, graphIndex)
        }
        check(response.schema == expected.schema) {
            "${service.graphId}/${service.corpus}/${case.name} returned schema ${response.schema}, " +
                "expected ${expected.schema}"
        }
        check(response.digest == expected.digest) {
            "${service.graphId}/${service.corpus}/${case.name} digest mismatch: " +
                "actual=${response.digest} expected=${expected.digest}"
        }
        return response
    }

    private fun executeLegacy(
        case: MethodCompatibilityCase,
        graphIndex: Int
    ): MethodCompatibilityResult {
        val response = successfulRequest(
            legacyPath(case.legacyClassName, graphIndex),
            "legacy methods ${case.name}"
        )
        val methods = JsonParser.parseString(response.body.decodeToString()).asJsonArray.map { element ->
            val value = element.asJsonObject
            MethodCompatibilityRecord(
                className = value.get("class").asString,
                name = value.get("name").asString,
                parameterTypes = value.getAsJsonArray("parameterTypes").map { it.asString },
                returnType = value.get("returnType").asString,
                signature = value.get("signature").asString
            )
        }
        val normalized = normalize(case, methods.filter(case.predicate))
        return normalized.copy(bytes = response.body.size.toLong())
    }

    private fun executeRootAndValidate(
        case: MethodCompatibilityCase,
        sourceCorpus: String
    ): MethodCompatibilityResult {
        val actual = if (legacyMethodsRoute) executeLegacyRoot(case) else executeCypherRoot(case)
        val expectedByGraph = expectedByGraph(case)
        check(actual.byGraph.keys == expectedByGraph.keys) {
            "root $sourceCorpus/${case.name} returned graph ids ${actual.byGraph.keys}, " +
                "expected ${expectedByGraph.keys}"
        }
        services.forEach { service ->
            val expected = expectedByGraph.getValue(service.graphId)
            val result = actual.byGraph.getValue(service.graphId)
            check(result.schema == expected.schema && result.digest == expected.digest) {
                "root $sourceCorpus/${case.name}/${service.graphId}/${service.corpus} digest mismatch: " +
                    "actual=${result.schema}:${result.digest} expected=${expected.schema}:${expected.digest}"
            }
        }
        return rootResult(case, sourceCorpus, actual.byGraph, actual.bytes)
    }

    private fun executeLegacyRoot(case: MethodCompatibilityCase): MethodBenchmarkRootResponse {
        val response = successfulRequest(legacyPath(case.legacyClassName, null), "root legacy methods ${case.name}")
        val body = JsonParser.parseString(response.body.decodeToString()).asJsonObject
        check(body.get("graphCount").asInt == graphCount) {
            "root legacy ${case.name} reported the wrong graph count: ${body.get("graphCount")}"
        }
        val byGraph = body.getAsJsonArray("results").associate { result ->
            val grouped = result.asJsonObject
            val graphId = grouped.get("graphId").asString
            val methods = grouped.getAsJsonArray("data").map { element ->
                val value = element.asJsonObject
                MethodCompatibilityRecord(
                    className = value.get("class").asString,
                    name = value.get("name").asString,
                    parameterTypes = value.getAsJsonArray("parameterTypes").map { it.asString },
                    returnType = value.get("returnType").asString,
                    signature = value.get("signature").asString
                )
            }
            graphId to normalize(case, methods.filter(case.predicate))
        }
        return MethodBenchmarkRootResponse(byGraph.withEmptyGraphResults(case), response.body.size.toLong())
    }

    private fun executeCypherRoot(case: MethodCompatibilityCase): MethodBenchmarkRootResponse {
        val query = when (case.aggregate) {
            MethodCompatibilityAggregate.NONE ->
                "MATCH (m:Method) WHERE ${case.where} " +
                    "RETURN graphId(m) AS graph_id, m.class AS class, m.name AS name, " +
                    "m.parameter_types AS parameter_types, m.return_type AS return_type, " +
                    "m.signature AS signature LIMIT $METHOD_RESULT_LIMIT"
            MethodCompatibilityAggregate.COUNT ->
                "MATCH (m:Method) WHERE ${case.where} RETURN graphId(m) AS graph_id, count(m) AS total"
            MethodCompatibilityAggregate.ORDERED_TOP ->
                "MATCH (m:Method) WHERE ${case.where} RETURN graphId(m) AS graph_id, " +
                    "m.signature AS signature ORDER BY graph_id, signature DESC LIMIT $METHOD_RESULT_LIMIT"
        }
        val response = successfulRequest(cypherPath(query, null), "root Method Cypher ${case.name}")
        val body = JsonParser.parseString(response.body.decodeToString()).asJsonObject
        check(body.get("graphCount").asInt == graphCount) {
            "root Method Cypher ${case.name} reported the wrong graph count: ${body.get("graphCount")}"
        }
        val columns = body.getAsJsonArray("columns").map { it.asString }
        val rows = body.getAsJsonArray("rows").map { it.asJsonObject }
        val byGraph = when (case.aggregate) {
            MethodCompatibilityAggregate.NONE -> {
                check(columns == listOf("graph_id") + METHOD_COLUMNS) {
                    "root ${case.name} returned unexpected columns: $columns"
                }
                rows.groupBy { it.get("graph_id").asString }
                    .mapValues { (_, graphRows) -> normalize(case, graphRows.map(::parseCypherRecord)) }
            }
            MethodCompatibilityAggregate.COUNT -> {
                check(columns == listOf("graph_id", "total"))
                rows.associate { row ->
                    row.get("graph_id").asString to
                        scalarResult("count:total", row.get("total").asLong.toString())
                }
            }
            MethodCompatibilityAggregate.ORDERED_TOP -> {
                check(columns == listOf("graph_id", "signature"))
                rows.groupBy { it.get("graph_id").asString }.mapValues { (_, graphRows) ->
                    scalarResult(
                        "order:signature",
                        graphRows.map { it.get("signature").asString }.sortedDescending().take(METHOD_ORDER_LIMIT)
                    )
                }
            }
        }
        return MethodBenchmarkRootResponse(byGraph.withEmptyGraphResults(case), response.body.size.toLong())
    }

    private fun Map<String, MethodCompatibilityResult>.withEmptyGraphResults(
        case: MethodCompatibilityCase
    ): Map<String, MethodCompatibilityResult> = services.associate { service ->
        service.graphId to (this[service.graphId] ?: normalize(case, emptyList()))
    }

    private fun expectedByGraph(case: MethodCompatibilityCase): Map<String, MethodCompatibilityResult> =
        services.associate { service -> service.graphId to expected(case, service.fixture) }

    private fun rootResult(
        case: MethodCompatibilityCase,
        sourceCorpus: String,
        byGraph: Map<String, MethodCompatibilityResult>,
        bytes: Long
    ): MethodCompatibilityResult = scalarResult(
        "root:$sourceCorpus:${case.name}",
        services.map { service -> "${service.graphId}=${byGraph.getValue(service.graphId).digest}" }
    ).copy(bytes = bytes)

    private fun executeCypher(case: MethodCompatibilityCase, graphIndex: Int): MethodCompatibilityResult {
        val query = when (case.aggregate) {
            MethodCompatibilityAggregate.NONE -> methodProjectionQuery(case)
            MethodCompatibilityAggregate.COUNT ->
                "MATCH (m:Method) WHERE ${case.where} RETURN count(m) AS total"
            MethodCompatibilityAggregate.ORDERED_TOP ->
                "MATCH (m:Method) WHERE ${case.where} RETURN m.signature AS signature " +
                    "ORDER BY signature DESC LIMIT $METHOD_ORDER_LIMIT"
        }
        val response = successfulRequest(cypherPath(query, graphIndex), "Method Cypher ${case.name}")
        val body = JsonParser.parseString(response.body.decodeToString()).asJsonObject
        val columns = body.getAsJsonArray("columns").map { it.asString }
        val rows = body.getAsJsonArray("rows")
        val normalized = when (case.aggregate) {
            MethodCompatibilityAggregate.NONE -> {
                check(columns == METHOD_COLUMNS) { "${case.name} returned unexpected columns: $columns" }
                val methods = rows.map { element -> parseCypherRecord(element.asJsonObject) }
                normalize(case, methods)
            }
            MethodCompatibilityAggregate.COUNT -> {
                check(columns == listOf("total") && rows.size() == 1)
                scalarResult("count:total", rows[0].asJsonObject.get("total").asLong.toString())
            }
            MethodCompatibilityAggregate.ORDERED_TOP -> {
                check(columns == listOf("signature"))
                scalarResult("order:signature", rows.map { it.asJsonObject.get("signature").asString })
            }
        }
        return normalized.copy(bytes = response.body.size.toLong())
    }

    private fun expected(
        case: MethodCompatibilityCase,
        fixture: MethodCompatibilityFixture
    ): MethodCompatibilityResult = normalize(case, fixture.all.filter(case.predicate))

    private fun methodProjectionQuery(case: MethodCompatibilityCase): String =
        "MATCH (m:Method) WHERE ${case.where} " +
            "RETURN m.class AS class, m.name AS name, m.parameter_types AS parameter_types, " +
            "m.return_type AS return_type, m.signature AS signature LIMIT $METHOD_RESULT_LIMIT"

    private fun parseCypherRecord(value: com.google.gson.JsonObject): MethodCompatibilityRecord =
        MethodCompatibilityRecord(
            className = value.get("class").asString,
            name = value.get("name").asString,
            parameterTypes = value.getAsJsonArray("parameter_types").map { it.asString },
            returnType = value.get("return_type").asString,
            signature = value.get("signature").asString
        )

    private fun normalize(
        case: MethodCompatibilityCase,
        methods: List<MethodCompatibilityRecord>
    ): MethodCompatibilityResult = when (case.aggregate) {
        MethodCompatibilityAggregate.NONE -> scalarResult(
            "methods:${METHOD_COLUMNS.joinToString(",")}",
            methods.map {
                listOf(it.className, it.name, it.parameterTypes.joinToString(","), it.returnType, it.signature)
                    .joinToString("\u0000")
            }.sorted()
        )
        MethodCompatibilityAggregate.COUNT -> scalarResult("count:total", methods.size.toString())
        MethodCompatibilityAggregate.ORDERED_TOP -> scalarResult(
            "order:signature",
            methods.map { it.signature }.sortedDescending().take(METHOD_ORDER_LIMIT)
        )
    }

    private fun scalarResult(schema: String, values: Any): MethodCompatibilityResult {
        val canonical = when (values) {
            is List<*> -> values.joinToString("\n")
            else -> values.toString()
        }
        return MethodCompatibilityResult(schema, sha256("$schema\n$canonical"), 0L)
    }

    private fun legacyPath(className: String, graphIndex: Int?): String {
        val prefix = graphIndex?.let { "/api/graphs/service-$it" } ?: "/api"
        return "$prefix/methods?class=${urlEncode(className)}&limit=$METHOD_RESULT_LIMIT"
    }

    private fun cypherPath(query: String, graphIndex: Int?): String {
        val prefix = graphIndex?.let { "/api/graphs/service-$it" } ?: "/api"
        return "$prefix/cypher?limit=$METHOD_RESULT_LIMIT&query=${urlEncode(query)}"
    }

    private fun successfulRequest(path: String, label: String): MethodHttpResponse {
        val response = rawRequest(path)
        check(response.code != HTTP_TOO_MANY_REQUESTS) {
            "$label was rejected with HTTP 429: ${response.body.decodeToString()}"
        }
        check(response.code == HTTP_OK) {
            "$label returned HTTP ${response.code}: ${response.body.decodeToString()}"
        }
        return response
    }

    private fun rawRequest(path: String): MethodHttpResponse {
        val connection = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = METHOD_HTTP_TIMEOUT_MS
        connection.readTimeout = METHOD_HTTP_TIMEOUT_MS
        val code = connection.responseCode
        val body = if (code in HTTP_SUCCESS_RANGE) {
            connection.inputStream.use { it.readBytes() }
        } else {
            connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
        }
        connection.disconnect()
        return MethodHttpResponse(code, body)
    }

    private fun measure(counters: MethodCompatibilityCounters, action: () -> Long): Long {
        val beforeCpu = processCpuTimeNanos()
        val beforeRss = residentSetBytes()
        val bytes = action()
        val afterRss = residentSetBytes()
        counters.requestsSucceeded++
        counters.responseBytes += bytes
        counters.processCpuNanos = (processCpuTimeNanos() - beforeCpu).coerceAtLeast(0L)
        counters.residentSetBeforeBytes = beforeRss
        counters.residentSetAfterBytes = afterRss
        counters.residentSetDeltaBytes = (afterRss - beforeRss).coerceAtLeast(0L)
        counters.graphCount = graphCount.toLong()
        return bytes
    }

    private fun writeCompatibilityManifest() {
        val configured = System.getProperty(METHOD_MANIFEST_PROPERTY)?.takeIf(String::isNotBlank) ?: return
        val line = buildString {
            append(graphCount).append('|').append(scenario)
            services.forEach { service ->
                val case = caseFor(scenario, service.fixture)
                val expected = expected(case, service.fixture)
                append('|')
                    .append(service.graphId)
                    .append(':')
                    .append(service.corpus)
                    .append(':')
                    .append(expected.schema)
                    .append('=')
                    .append(expected.digest)
            }
            services.distinctBy(MethodBenchmarkService::corpus).forEach { source ->
                val case = caseFor(scenario, source.fixture)
                val expected = rootResult(case, source.corpus, expectedByGraph(case), 0L)
                append("|root:")
                    .append(source.corpus)
                    .append(':')
                    .append(expected.schema)
                    .append('=')
                    .append(expected.digest)
            }
            append('\n')
        }
        val path = Path.of(configured)
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            line,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND
        )
    }

    private fun processCpuTimeNanos(): Long =
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: 0L

    private fun residentSetBytes(): Long {
        val status = Path.of("/proc/self/status")
        if (!Files.isRegularFile(status)) return Runtime.getRuntime().totalMemory()
        val line = Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") }
            ?: return Runtime.getRuntime().totalMemory()
        return line.split(Regex("\\s+")).firstNotNullOfOrNull { it.toLongOrNull() }
            ?.times(BYTES_PER_KIB)
            ?: Runtime.getRuntime().totalMemory()
    }

    private companion object {
        private const val METHOD_HTTP_TIMEOUT_MS = 120_000
        private const val METHOD_RESULT_LIMIT = 5_000
        private const val METHOD_ORDER_LIMIT = 5
        private const val TARGET_CYPHER_CONCURRENCY = 4
        private const val TARGET_CYPHER_WORK_BUDGET = 1_000_000L
        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val BYTES_PER_KIB = 1_024L
        private const val METHOD_MANIFEST_PROPERTY = "graphite.method.compatibility.output"
        private val HTTP_SUCCESS_RANGE = 200..299
        private val METHOD_CORPORA = listOf("android", "tika", "hive", "kotlin-compiler")
        private val METHOD_COLUMNS = listOf("class", "name", "parameter_types", "return_type", "signature")

        private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private fun cypherString(value: String): String = "'${value.replace("\\", "\\\\").replace("'", "\\'")}'"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
open class MethodBenchmarkCompatibilityCounters {
    @JvmField
    var requestsSucceeded: Long = 0

    @JvmField
    var responseBytes: Long = 0

    @JvmField
    var processCpuNanos: Long = 0

    @JvmField
    var residentSetBeforeBytes: Long = 0

    @JvmField
    var residentSetAfterBytes: Long = 0

    @JvmField
    var residentSetDeltaBytes: Long = 0

    @JvmField
    var graphCount: Long = 0
}

typealias MethodCompatibilityCounters = MethodBenchmarkCompatibilityCounters
private typealias MethodCompatibilityFixture = MethodBenchmarkCompatibilityFixture
private typealias MethodCompatibilityRecord = MethodBenchmarkCompatibilityRecord
private typealias MethodCompatibilityCase = MethodBenchmarkCompatibilityCase
private typealias MethodCompatibilityAggregate = MethodBenchmarkCompatibilityAggregate
private typealias MethodCompatibilityResult = MethodBenchmarkCompatibilityResult
private typealias MethodHttpResponse = MethodBenchmarkHttpResponse

private data class MethodBenchmarkCompatibilityFixture(
    val corpus: String,
    val all: List<MethodCompatibilityRecord>,
    val className: String,
    val early: MethodBenchmarkPercentileTarget,
    val middle: MethodBenchmarkPercentileTarget,
    val late: MethodBenchmarkPercentileTarget,
    val prefix: String,
    val suffix: String,
    val contains: String,
    val regex: String,
    val orNames: Pair<String, String>
) {
    companion object {
        fun from(corpus: String, methods: List<MethodDescriptor>): MethodCompatibilityFixture {
            val records = methods.map(MethodCompatibilityRecord::from)
            require(records.size >= MIN_PERCENTILE_METHODS) {
                "$corpus method index has only ${records.size} records; percentile fixtures require $MIN_PERCENTILE_METHODS"
            }
            val early = percentileTarget("early", records, 0)
            val middle = percentileTarget("middle", records, (records.lastIndex / 2))
            val late = percentileTarget("late", records, records.lastIndex)
            check(early.percentile <= EARLY_MAX_PERCENTILE) {
                "$corpus early ordinal ${early.ordinal}/${records.size} is above 1%"
            }
            check(kotlin.math.abs(middle.percentile - MIDDLE_PERCENTILE) <= MIDDLE_PERCENTILE_TOLERANCE) {
                "$corpus middle ordinal ${middle.ordinal}/${records.size} is not approximately 50%"
            }
            check(late.percentile >= LATE_MIN_PERCENTILE) {
                "$corpus late ordinal ${late.ordinal}/${records.size} is below 99%"
            }
            val grouped = records.groupBy { it.className }
            val selected = grouped.entries.firstOrNull { (_, values) ->
                values.size in 3..MAX_COMPATIBILITY_CLASS_METHODS && values.map { it.name }.distinct().size >= 2
            } ?: error("$corpus method fixture has no bounded class with representative method names")
            val classMethods = selected.value
            val names = classMethods.map { it.name }.distinct()
            val representative = names.first { it.isNotEmpty() }
            val prefixLength = minOf(3, representative.length)
            val suffixLength = minOf(3, representative.length)
            val containsStart = (representative.length / 3).coerceAtMost(representative.lastIndex)
            val containsEnd = (containsStart + 2).coerceAtMost(representative.length)
            return MethodCompatibilityFixture(
                corpus = corpus,
                all = records,
                className = selected.key,
                early = early,
                middle = middle,
                late = late,
                prefix = representative.take(prefixLength),
                suffix = representative.takeLast(suffixLength),
                contains = representative.substring(containsStart, containsEnd),
                regex = "^${Regex.escape(representative)}$",
                orNames = names[0] to names[1]
            )
        }

        private fun percentileTarget(
            name: String,
            records: List<MethodCompatibilityRecord>,
            index: Int
        ): MethodBenchmarkPercentileTarget {
            val percentile = index.toDouble() / records.lastIndex.toDouble()
            return MethodBenchmarkPercentileTarget(name, records[index], index + 1, percentile)
        }

        private const val MIN_PERCENTILE_METHODS = 100
        private const val MAX_COMPATIBILITY_CLASS_METHODS = 100
        private const val EARLY_MAX_PERCENTILE = 0.01
        private const val MIDDLE_PERCENTILE = 0.50
        private const val MIDDLE_PERCENTILE_TOLERANCE = 0.01
        private const val LATE_MIN_PERCENTILE = 0.99
    }
}

private data class MethodBenchmarkPercentileTarget(
    val name: String,
    val record: MethodCompatibilityRecord,
    val ordinal: Int,
    val percentile: Double
)

private data class MethodBenchmarkService(
    val graphId: String,
    val corpus: String,
    val fixture: MethodCompatibilityFixture
)

private data class MethodBenchmarkCompatibilityRecord(
    val className: String,
    val name: String,
    val parameterTypes: List<String>,
    val returnType: String,
    val signature: String
) {
    companion object {
        fun from(method: MethodDescriptor) = MethodCompatibilityRecord(
            className = method.declaringClass.className,
            name = method.name,
            parameterTypes = method.parameterTypes.map { it.className },
            returnType = method.returnType.className,
            signature = method.signature
        )
    }
}

private data class MethodBenchmarkCompatibilityCase(
    val name: String,
    val where: String,
    val legacyClassName: String,
    val aggregate: MethodCompatibilityAggregate = MethodCompatibilityAggregate.NONE,
    val predicate: (MethodCompatibilityRecord) -> Boolean
)

private enum class MethodBenchmarkCompatibilityAggregate { NONE, COUNT, ORDERED_TOP }

private data class MethodBenchmarkCompatibilityResult(val schema: String, val digest: String, val bytes: Long)

private data class MethodBenchmarkHttpResponse(val code: Int, val body: ByteArray)

private data class MethodBenchmarkRootResponse(
    val byGraph: Map<String, MethodCompatibilityResult>,
    val bytes: Long
)

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

    fun persistedGraph(corpus: String): Path = when (corpus) {
        ANDROID_CORPUS -> persistedAndroidGraph()
        TIKA_CORPUS -> configuredPersistedGraph(TIKA_GRAPH_PATH_PROPERTY, corpus)
        HIVE_CORPUS -> configuredPersistedGraph(HIVE_GRAPH_PATH_PROPERTY, corpus)
        KOTLIN_COMPILER_CORPUS -> configuredPersistedGraph(KOTLIN_COMPILER_GRAPH_PATH_PROPERTY, corpus)
        else -> error("Unknown Explorer benchmark corpus: $corpus")
    }

    private fun configuredPersistedGraph(property: String, corpus: String): Path {
        val configured = System.getProperty(property)
            ?: error("Set -D$property=<path> for the $corpus Explorer benchmark corpus")
        val graphPath = Path.of(configured)
        require(graphPath.isDirectory() && hasPersistedGraph(graphPath)) {
            "Persisted $corpus graph not found at $graphPath"
        }
        return graphPath
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
    private const val TIKA_GRAPH_PATH_PROPERTY = "tika.graph.path"
    private const val HIVE_GRAPH_PATH_PROPERTY = "hive.graph.path"
    private const val KOTLIN_COMPILER_GRAPH_PATH_PROPERTY = "kotlin.compiler.graph.path"
    private const val ANDROID_CORPUS = "android"
    private const val TIKA_CORPUS = "tika"
    private const val HIVE_CORPUS = "hive"
    private const val KOTLIN_COMPILER_CORPUS = "kotlin-compiler"
    private const val MAX_CACHE_WALK_DEPTH = 12
}
