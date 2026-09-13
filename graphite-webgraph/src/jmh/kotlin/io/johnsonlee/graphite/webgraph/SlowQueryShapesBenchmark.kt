package io.johnsonlee.graphite.webgraph

import com.sun.management.OperatingSystemMXBean
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
        "valueHit", "valueMiss", "dynamicHit", "dynamicMiss",
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

/** Untimed oracle parity plus process CPU observations, independent of JMH GC profiling. */
internal object SlowQueryShapesCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..2) { "Usage: SlowQueryShapesCorrectness <android|all> [query-name]" }
        val cases = if (args.size == 2) slowQueryShapeCases.filter { it.name == args[1] } else slowQueryShapeCases
        require(cases.isNotEmpty()) { "Unknown query name: ${args.last()}" }
        val process = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
        cases.forEach { queryCase ->
            // A new mapping and executor per query isolates startup and index history.
            SlowQueryShapesWorkload(args[0]).use { workload ->
                repeat(2) { iteration ->
                    val cpuStart = process.processCpuTime
                    val wallStart = System.nanoTime()
                    val result = workload.execute(queryCase)
                    val wallNanos = System.nanoTime() - wallStart
                    val cpuNanos = process.processCpuTime - cpuStart
                    queryCase.validate(result)
                    val state = if (iteration == 0) "COLD" else "WARM"
                    printSlowQueryShapeDigest(args[0], state, queryCase, result)
                    println(
                        "SLOW_QUERY_SHAPE_RESOURCES\t${args[0]}\t$state\t${queryCase.name}" +
                            "\twallNanos=$wallNanos\tprocessCpuNanos=$cpuNanos"
                    )
                }
            }
        }
    }
}

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
        val nodeReturn = " RETURN id(n) AS id, labels(n) AS labels, n.value AS value, " +
            "n.caller_class AS caller, n.graphId AS graphId LIMIT 50"
        val edgeReturn = " RETURN id(c) AS source, id(n) AS target, type(r) AS relationship, " +
            "c.value AS value, n.caller_class AS caller, n.graphId AS graphId LIMIT 50"
        add(SlowQueryShapeCase("value$suffix", "MATCH (n) WHERE n.value CONTAINS '$value'$nodeReturn", hit))
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
