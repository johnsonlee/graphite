package io.johnsonlee.graphite.webgraph

import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherResult
import io.johnsonlee.graphite.graph.Graph
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
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Identical query text and persisted corpora must be used on main and candidate.
 * Run with -prof gc for normalized allocation; result digests are emitted outside
 * the timed region. COLD clears query indexes, but does not claim cold OS pages.
 * A fresh fork (the default) is required to measure the first query on a mapping.
 * Each workload copies the real persisted graph into a private directory, omitting
 * graph.callsite-string-index. WARM primes that same private mapping. Neither
 * index creation nor graph close writes to the shared source fixture. Setup and
 * cleanup are outside primary latency; first-trial GC profiling includes them.
 * This isolated fixture protocol must not be pooled with earlier measurements
 * that loaded a shared writable fixture directory directly.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 3, jvmArgs = ["-Xmx8g", "-XX:ActiveProcessorCount=4"])
open class SlowQueryShapesBenchmark {
    @Param("android")
    var corpus: String = "android"

    @Param(
        "valueHit", "valueMiss", "qualifiedIdHit", "qualifiedIdMiss", "dynamicHit", "dynamicMiss",
        "wrappedCallerHit", "wrappedCallerMiss", "dataflowSourceHit", "dataflowSourceMiss",
        "dataflowTargetHit", "dataflowTargetMiss"
    )
    var queryName: String = "valueHit"

    @Param("COLD", "WARM")
    var cacheState: String = "COLD"

    private lateinit var workload: SlowQueryShapesWorkload
    private lateinit var queryCase: SlowQueryShapeCase
    private var lastResult: CypherResult? = null

    @Setup(Level.Trial)
    fun setup() {
        queryCase = slowQueryShapeCases.single { it.name == queryName }
        require(cacheState == "COLD" || cacheState == "WARM")
        workload = SlowQueryShapesWorkload(corpus)
        if (cacheState == "WARM") {
            runCatching { queryCase.validate(workload.execute(queryCase)) }.getOrElse { failure ->
                runCatching { workload.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    @Setup(Level.Invocation)
    fun prepareQuery() {
        if (cacheState == "COLD") workload.clearIndexes()
    }

    @Benchmark
    fun execute(): CypherResult = workload.execute(queryCase).also { lastResult = it }

    @TearDown(Level.Trial)
    fun close() {
        try {
            val result = checkNotNull(lastResult)
            queryCase.validate(result)
            printSlowQueryShapeDigest(corpus, cacheState, queryCase, result)
        } finally {
            workload.close()
        }
    }
}

/**
 * Correctness oracle with query-window process CPU and all-Java-thread allocation observations.
 * Allocation includes background Java threads active during the query, not only application
 * query threads; it is neither retained heap nor peak memory. Unsupported counters emit -1.
 * These observations are separate from lifecycle-inclusive first-trial JMH GC profiling.
 */
internal object SlowQueryShapesCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Usage: SlowQueryShapesCorrectness <android|all> [query-name]" }
        val cases = if (args.size == 2) slowQueryShapeCases.filter { it.name == args[1] } else slowQueryShapeCases
        require(cases.isNotEmpty()) { "Unknown query name: ${args.last()}" }
        val process = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        val allocations = queryAllocationCounter()
        cases.forEach { queryCase ->
            // A new mapping and executor per query isolates startup and index history.
            SlowQueryShapesWorkload(args[0]).use { workload ->
                repeat(2) { iteration ->
                    val allocatedBefore = allocatedBytes(allocations)
                    val cpuStart = process.processCpuTime
                    val wallStart = System.nanoTime()
                    val result = workload.execute(queryCase)
                    val wallNanos = System.nanoTime() - wallStart
                    val cpuNanos = process.processCpuTime - cpuStart
                    val allocatedAfter = allocatedBytes(allocations)
                    val allocatedDelta = if (allocatedBefore >= 0 && allocatedAfter >= allocatedBefore) {
                        allocatedAfter - allocatedBefore
                    } else {
                        -1L
                    }
                    queryCase.validate(result)
                    val state = if (iteration == 0) "COLD" else "WARM"
                    printSlowQueryShapeDigest(args[0], state, queryCase, result)
                    println(
                        "SLOW_QUERY_SHAPE_RESOURCES\t${args[0]}\t$state\t${queryCase.name}" +
                            "\twallNanos=$wallNanos\tprocessCpuNanos=$cpuNanos\tallocatedBytes=$allocatedDelta"
                    )
                }
            }
        }
    }
}

private fun queryAllocationCounter(): ThreadMXBean? = runCatching {
    val counter = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return@runCatching null
    if (!counter.isThreadAllocatedMemorySupported) return@runCatching null
    if (!counter.isThreadAllocatedMemoryEnabled) counter.isThreadAllocatedMemoryEnabled = true
    counter.takeIf { it.isThreadAllocatedMemoryEnabled && it.totalThreadAllocatedBytes >= 0 }
}.getOrNull()

private fun allocatedBytes(counter: ThreadMXBean?): Long =
    counter?.let { runCatching { it.totalThreadAllocatedBytes }.getOrDefault(-1L) } ?: -1L

private class SlowQueryShapesWorkload(corpus: String) : Closeable {
    private val loaded = mutableListOf<Graph>()
    private val snapshotRoot: Path
    private val executor: CrossGraphCypherExecutor

    init {
        require(corpus == "android" || corpus == "all") { "Unsupported corpus: $corpus" }
        snapshotRoot = Files.createTempDirectory("graphite-slow-query-shapes-")
        executor = runCatching {
            val kinds = if (corpus == "all") BenchmarkCorpusKind.entries else listOf(BenchmarkCorpusKind.ANDROID)
            val sources = kinds.map { kind ->
                val graph = GraphStore.loadMapped(copyFixture(kind))
                loaded += graph
                check(graph.nodeCount(Node::class.java) == kind.expectedNodeCount)
                CypherGraph(kind.id, graph)
            }
            CrossGraphCypherExecutor(sources, CypherExecutionBudget(2_000_000_000L))
        }.getOrElse { failure ->
            cleanupFailure()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun copyFixture(kind: BenchmarkCorpusKind): Path {
        // Do not call BenchmarkCorpus.persistedGraph for configured shared paths:
        // its identity check opens/closes that graph and may persist a sidecar.
        val source = System.getProperty(kind.graphPathProperty)?.let(Path::of)
            ?: BenchmarkCorpus.persistedGraph(kind)
        require(Files.isDirectory(source)) { "Persisted graph directory not found: $source" }
        val destination = Files.createDirectory(snapshotRoot.resolve(kind.id))
        Files.list(source).use { files ->
            files.filter { it.fileName.toString() != "graph.callsite-string-index" }.forEach { file ->
                require(Files.isRegularFile(file)) { "Unexpected persisted graph entry: $file" }
                Files.copy(file, destination.resolve(file.fileName))
            }
        }
        check(Files.notExists(destination.resolve("graph.callsite-string-index")))
        println(
            "SLOW_QUERY_SHAPE_FIXTURE\tprotocol=private-copy-no-callsite-index-v2" +
                "\tcorpus=${kind.id}\tsource=$source\tsnapshot=$destination\tindexAbsent=true"
        )
        return destination
    }

    fun execute(queryCase: SlowQueryShapeCase): CypherResult = executor.execute(queryCase.query)

    fun clearIndexes() {
        loaded.forEach { graph ->
            val clear = graph.javaClass.declaredMethods.single { it.name.startsWith("clearStringPropertyIndexes") }
            clear.isAccessible = true
            clear.invoke(graph)
        }
    }

    override fun close() {
        cleanupFailure()?.let { throw it }
    }

    private fun cleanupFailure(): Throwable? {
        val failures = loaded.asReversed().mapNotNull { graph ->
            runCatching { (graph as? Closeable)?.close() }.exceptionOrNull()
        }.toMutableList()
        loaded.clear()
        runCatching {
            check(snapshotRoot.toFile().deleteRecursively()) { "Unable to remove private fixture: $snapshotRoot" }
        }.exceptionOrNull()?.let(failures::add)
        return failures.firstOrNull()?.also { first -> failures.drop(1).forEach(first::addSuppressed) }
    }
}

private data class SlowQueryShapeCase(val name: String, val query: String, val expectsHit: Boolean) {
    fun validate(result: CypherResult) {
        check(result.rows.isNotEmpty() == expectsHit) { "$name: unexpected row count ${result.rows.size}" }
        check(result.rows.size <= 50)
    }
}

private val slowQueryShapeCases: List<SlowQueryShapeCase> = buildList {
    for (hit in listOf(true, false)) {
        val suffix = if (hit) "Hit" else "Miss"
        val value = if (hit) "android.permission.INTERNET" else "GraphiteSlowShapeAbsent293746X"
        val caller = if (hit) "android.app.Activity" else "GraphiteSlowShapeAbsent293746X"
        // Real fixture IDs include sparse substring hits up to Android's final node, 5,938,826.
        val qualifiedId = if (hit) "938826" else "93882699"
        val nodeReturn = " RETURN id(n) AS id, labels(n) AS labels, n.value AS value, " +
            "n.caller_class AS caller, n.graphId AS graphId LIMIT 50"
        val edgeReturn = " RETURN id(c) AS source, id(n) AS target, type(r) AS relationship, " +
            "c.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50"
        add(SlowQueryShapeCase("value$suffix", "MATCH (n) WHERE n.value CONTAINS '$value'$nodeReturn", hit))
        add(
            SlowQueryShapeCase(
                "qualifiedId$suffix",
                "MATCH (n) WHERE n.qualifiedId CONTAINS '$qualifiedId' " +
                    "RETURN id(n) AS id, labels(n) AS labels, n.qualifiedId AS qualifiedId, " +
                    "n.graphId AS graphId LIMIT 50",
                hit
            )
        )
        add(
            SlowQueryShapeCase(
                "dynamic$suffix",
                "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS '$value')$nodeReturn",
                hit
            )
        )
        add(
            SlowQueryShapeCase(
                "wrappedCaller$suffix", "MATCH (n) WHERE toString(n.caller_class) CONTAINS '$caller'$nodeReturn", hit
            )
        )
        add(
            SlowQueryShapeCase(
                "dataflowSource$suffix", "MATCH (c)-[r:DATAFLOW]->(n) WHERE c.value CONTAINS '$value'$edgeReturn", hit
            )
        )
        add(
            SlowQueryShapeCase(
                "dataflowTarget$suffix", "MATCH (c)-[r:DATAFLOW]->(n) WHERE n.caller_class CONTAINS '$caller'$edgeReturn", hit
            )
        )
    }
}

private fun printSlowQueryShapeDigest(
    corpus: String,
    state: String,
    queryCase: SlowQueryShapeCase,
    result: CypherResult
) {
    val canonical = canonicalSlowQueryShape(listOf(result.columns, result.rows))
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    println("SLOW_QUERY_SHAPE_RESULT\t$corpus\t$state\t${queryCase.name}\trows=${result.rows.size}\tsha256=$digest")
}

private fun canonicalSlowQueryShape(value: Any?): String = when (value) {
    null -> "null"
    is String -> "string:${value.length}:$value"
    is Number -> "number:${value::class.java.name}:$value"
    is Boolean -> "boolean:$value"
    is Map<*, *> -> value.entries.map { canonicalSlowQueryShape(it.key) to canonicalSlowQueryShape(it.value) }
        .sortedBy { it.first }.joinToString(prefix = "map:[", postfix = "]") { (key, item) -> "$key=$item" }
    is Set<*> -> value.map(::canonicalSlowQueryShape).sorted().joinToString(prefix = "set:[", postfix = "]")
    is Iterable<*> -> value.joinToString(prefix = "list:[", postfix = "]", transform = ::canonicalSlowQueryShape)
    else -> error("Unexpected benchmark result type: ${value::class.java.name}")
}

/**
 * Per-query steady-state evidence. The driver starts a fresh JVM for every query/revision/pair.
 * Fixture creation and complete ordered-result verification remain outside each query timer.
 * The original single-shot benchmark above remains available for separate cold diagnostics.
 */
internal object SlowQueryShapesSteadyState {
    private const val PHASE_MIN_NANOS = 10_000_000_000L
    private const val WARMUP_MIN_CALLS = 5
    private const val MEASUREMENT_MIN_CALLS = 40
    private const val MIN_HEAP_BYTES = 7L * 1_024L * 1_024L * 1_024L
    private const val MAX_HEAP_BYTES = 8L * 1_024L * 1_024L * 1_024L
    private val identities = slowQueryShapeCases.associate { it.name to sha256(it.query.toByteArray(Charsets.UTF_8)) }
    private val header = listOf(
        "phase", "round", "phaseElapsedNanos", "id", "family", "shape", "selectivity", "operator",
        "boundary", "projection", "targetGraphId", "workloadIdentity", "limit", "outcome", "rowCount",
        "responseBytes", "digest", "latencyNanos", "maxHeapBytes"
    ).joinToString("\t")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) {
            "Usage: SlowQueryShapesSteadyState <query-name> <record|verify> <oracle-path> <raw-path>"
        }
        val queryCase = slowQueryShapeCases.single { it.name == args[0] }
        val mode = args[1]
        require(mode == "record" || mode == "verify") { "Unknown correctness mode: $mode" }
        val heapArguments = ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-Xmx") }
        require(heapArguments == listOf("-Xmx8g")) { "Expected exactly one -Xmx8g argument: $heapArguments" }
        require(Runtime.getRuntime().availableProcessors() == 4) { "Expected exactly four available processors" }
        val heap = Runtime.getRuntime().maxMemory()
        check(heap in MIN_HEAP_BYTES..MAX_HEAP_BYTES) { "Expected 8GiB heap configuration; actual=$heap" }
        println(
            "SLOW_QUERY_SHAPE_STEADY_ENV\tprotocol=slow-shapes-timed-v1\tquery=${queryCase.name}" +
                "\tmaxHeapBytes=$heap\tavailableProcessors=${Runtime.getRuntime().availableProcessors()}" +
                "\tjavaRuntimeVersion=${System.getProperty("java.runtime.version")}" +
                "\twarmupMinNanos=$PHASE_MIN_NANOS\twarmupMinCalls=$WARMUP_MIN_CALLS" +
                "\tmeasurementMinNanos=$PHASE_MIN_NANOS\tmeasurementMinCalls=$MEASUREMENT_MIN_CALLS"
        )
        println(
            "SLOW_QUERY_WARM_RUNTIME\tvmVersion=${System.getProperty("java.runtime.version")}" +
                "\tmaxHeapBytes=$heap\tactiveProcessorCount=${Runtime.getRuntime().availableProcessors()}"
        )
        val oraclePath = Path.of(args[2])
        val rawPath = Path.of(args[3])
        require(Files.notExists(rawPath)) { "Refusing to overwrite raw evidence: $rawPath" }
        val oracle = if (mode == "verify") completeOracle(oraclePath, queryCase) else emptyList()
        if (mode == "record") require(Files.notExists(oraclePath)) { "Refusing to overwrite oracle: $oraclePath" }
        rawPath.toAbsolutePath().parent?.let(Files::createDirectories)
        SlowQueryShapesWorkload("android").use { workload ->
            Files.newBufferedWriter(rawPath).use { writer ->
                writer.write("$header\n")
                writer.flush()
                if (mode == "record") {
                    val started = System.nanoTime()
                    val sample = sample(workload, queryCase)
                    writeSample(writer, "record", 1, System.nanoTime() - started, sample, heap)
                    sample.failure?.let { throw it }
                    QueryCorrectnessManifest.requireRecordable(listOf(sample.record), setOf(sample.record.id))
                    QueryCorrectnessManifest.write(oraclePath, listOf(sample.record))
                } else {
                    phase(workload, queryCase, oracle, writer, "warmup", WARMUP_MIN_CALLS, heap)
                    phase(workload, queryCase, oracle, writer, "measurement", MEASUREMENT_MIN_CALLS, heap)
                }
            }
        }
    }

    private fun completeOracle(path: Path, queryCase: SlowQueryShapeCase): List<QueryCorrectnessRecord> {
        val records = QueryCorrectnessManifest.read(path)
        val ids = slowQueryShapeCases.mapTo(mutableSetOf()) { "slow-shapes-${it.name}" }
        QueryCorrectnessManifest.requireRecordable(records, ids)
        return records.filter { it.id == "slow-shapes-${queryCase.name}" }
    }

    private fun phase(
        workload: SlowQueryShapesWorkload,
        queryCase: SlowQueryShapeCase,
        oracle: List<QueryCorrectnessRecord>,
        writer: java.io.BufferedWriter,
        name: String,
        minimumCalls: Int,
        heap: Long
    ) {
        var round = 0
        var elapsed: Long
        val started = System.nanoTime()
        do {
            val sample = sample(workload, queryCase)
            elapsed = System.nanoTime() - started
            round++
            writeSample(writer, name, round, elapsed, sample, heap)
            sample.failure?.let { throw it }
            QueryCorrectnessManifest.verify(oracle, listOf(sample.record))
        } while (round < minimumCalls || elapsed < PHASE_MIN_NANOS)
    }

    private fun sample(workload: SlowQueryShapesWorkload, queryCase: SlowQueryShapeCase): SteadySample {
        val started = System.nanoTime()
        val executed = runCatching { workload.execute(queryCase) }
        val latency = System.nanoTime() - started
        // The execution timer stops before validation, canonicalization, hashing and raw output.
        val result = executed.getOrNull()
        val encoding = runCatching {
            result?.let { canonicalSlowQueryShape(listOf(it.columns, it.rows)).toByteArray(Charsets.UTF_8) }
        }
        val encoded = encoding.getOrNull()
        val failure = executed.exceptionOrNull() ?: encoding.exceptionOrNull()
            ?: result?.let { runCatching { queryCase.validate(it) }.exceptionOrNull() }
        val record = QueryCorrectnessRecord(
            id = "slow-shapes-${queryCase.name}",
            family = "slow-query-shapes",
            shape = queryCase.name.removeSuffix("Hit").removeSuffix("Miss"),
            selectivity = if (queryCase.expectsHit) "targeted" else "zero",
            operator = "cypher",
            boundary = "single-graph",
            projection = "ordered-full-result",
            targetGraphId = "android",
            workloadIdentity = identities.getValue(queryCase.name),
            limit = 50L,
            outcome = if (failure == null) "success" else "failed",
            rowCount = result?.rows?.size?.toLong() ?: 0L,
            responseBytes = encoded?.size?.toLong() ?: 0L,
            digest = sha256(encoded ?: ByteArray(0))
        )
        return SteadySample(record, latency, failure)
    }

    private fun writeSample(
        writer: java.io.BufferedWriter,
        phase: String,
        round: Int,
        elapsed: Long,
        sample: SteadySample,
        heap: Long
    ) {
        writer.write(
            "$phase\t$round\t$elapsed\t${sample.record.encode().replace('|', '\t')}" +
                "\t${sample.latencyNanos}\t$heap\n"
        )
        writer.flush()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class SteadySample(val record: QueryCorrectnessRecord, val latencyNanos: Long, val failure: Throwable?)
}
