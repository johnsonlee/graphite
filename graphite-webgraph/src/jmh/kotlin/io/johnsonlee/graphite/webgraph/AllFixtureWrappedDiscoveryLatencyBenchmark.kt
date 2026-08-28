package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherResult
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.io.Closeable
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Real heterogeneous multi-graph gate for the production wrapped discovery
 * query. All repository benchmark fixture JARs are represented exactly once.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AllFixtureWrappedDiscoveryLatencyBenchmark {

    private lateinit var executor: CrossGraphCypherExecutor
    private val loadedGraphs = mutableListOf<Graph>()
    private val clearIndexMethods = mutableListOf<Method?>()

    @Setup
    fun setup() {
        val graphs = BenchmarkCorpusKind.entries.map { kind ->
            val graph = GraphStore.loadMapped(BenchmarkCorpus.persistedGraph(kind))
            check(graph.nodeCount(Node::class.java) == kind.expectedNodeCount)
            loadedGraphs += graph
            clearIndexMethods += graph.javaClass.declaredMethods
                .firstOrNull { it.name.startsWith("clearStringPropertyIndexes") }
                ?.also { it.isAccessible = true }
            CypherGraph(kind.id, graph)
        }
        check(graphs.sumOf { it.graph.nodeCount(Node::class.java) ?: 0L } == EXPECTED_ALL_FIXTURE_NODES)
        executor = CrossGraphCypherExecutor(graphs)
    }

    @TearDown
    fun tearDown() {
        loadedGraphs.forEach { (it as? Closeable)?.close() }
    }

    @Benchmark
    fun zeroHitBroadContainsCaseInsensitiveDiscovery(): CypherResult = executeCold(ZERO_HIT_QUERY)

    @Benchmark
    fun denseDistributedMethodContainsCaseInsensitiveDiscovery(): CypherResult =
        executeCold(DENSE_DISTRIBUTED_METHOD_QUERY)

    @Benchmark
    fun earlyGraphClassPrefixCaseInsensitiveDiscovery(): CypherResult = executeCold(EARLY_GRAPH_PREFIX_QUERY)

    @Benchmark
    fun middleGraphsClassPrefixCaseInsensitiveDiscovery(): CypherResult = executeCold(MIDDLE_GRAPHS_PREFIX_QUERY)

    @Benchmark
    fun lateGraphClassPrefixCaseInsensitiveDiscovery(): CypherResult = executeCold(LATE_GRAPH_PREFIX_QUERY)

    @Benchmark
    fun broadlyDistributedClassPrefixCaseInsensitiveDiscovery(): CypherResult =
        executeCold(BROADLY_DISTRIBUTED_PREFIX_QUERY)

    @Benchmark
    fun firstLastGraphBimodalClassPrefixCaseInsensitiveDiscovery(): CypherResult =
        executeCold(FIRST_LAST_GRAPH_BIMODAL_QUERY)

    @Benchmark
    fun skewedMixedClassMethodOperatorCaseInsensitiveDiscovery(): CypherResult =
        executeCold(SKEWED_MIXED_OPERATOR_QUERY)

    private fun executeCold(query: String): CypherResult {
        loadedGraphs.indices.forEach { index -> clearIndexMethods[index]?.invoke(loadedGraphs[index]) }
        return executor.execute(query)
    }
}

/**
 * Builds each eager source graph in sequence and closes it before building the
 * next one. The resulting persisted graphs can then be shared by every JMH
 * revision in the same CI job.
 */
internal object AllFixtureBenchmarkGraphPreparation {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: AllFixtureBenchmarkGraphPreparation <corpus-id> <output-directory>" }
        val kind = BenchmarkCorpusKind.entries.single { it.id == args[0] }
        prepare(kind, Path.of(args[1]).toAbsolutePath().normalize())
    }

    private fun prepare(kind: BenchmarkCorpusKind, output: Path) {
        require(Files.notExists(output)) { "Fixture graph output already exists: $output" }
        val graph = JavaProjectLoader(
            LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )
        ).load(BenchmarkCorpus.resolveJar(kind))
        try {
            GraphStore.save(graph, output)
            check(graph.nodes(Node::class.java).count().toLong() == kind.expectedNodeCount)
        } finally {
            (graph as? Closeable)?.close()
        }
    }
}

/**
 * Hashes complete columns, rows, values, and provenance for every timed query.
 * CI compares these markers across fixed, base, and candidate revisions.
 */
internal object AllFixtureBenchmarkQueryCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: AllFixtureBenchmarkQueryCorrectness <graph-directory>" }
        val root = Path.of(args.single()).toAbsolutePath().normalize()
        val graphs = mutableListOf<Graph>()
        try {
            val sources = BenchmarkCorpusKind.entries.map { kind ->
                GraphStore.loadMapped(root.resolve(kind.id)).also(graphs::add).let { graph ->
                    check(graph.nodeCount(Node::class.java) == kind.expectedNodeCount)
                    CypherGraph(kind.id, graph)
                }
            }
            val executor = CrossGraphCypherExecutor(sources)
            ALL_FIXTURE_QUERIES.forEach { (name, query) ->
                val result = executor.execute(query)
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(result.columns to result.rows).toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }
                println("ALL_FIXTURE_QUERY_RESULT\t$name\trows=${result.rows.size}\tsha256=$digest")
            }
        } finally {
            graphs.asReversed().forEach { (it as? Closeable)?.close() }
        }
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> "string:${value.length}:$value"
        is Number -> "number:${value::class.java.name}:$value"
        is Boolean -> "boolean:$value"
        is Map<*, *> -> value.entries
            .map { canonical(it.key) to canonical(it.value) }
            .sortedBy { it.first }
            .joinToString(prefix = "map:[", postfix = "]") { (key, item) -> "$key=$item" }
        is Set<*> -> value.map(::canonical).sorted().joinToString(prefix = "set:[", postfix = "]")
        is Iterable<*> -> value.joinToString(prefix = "list:[", postfix = "]", transform = ::canonical)
        else -> "object:${value::class.java.name}:${value}"
    }
}

/** Prints per-corpus match counts used to pin the distribution cases below. */
internal object AllFixtureBenchmarkDistributionCalibration {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: AllFixtureBenchmarkDistributionCalibration <graph-directory>" }
        val root = Path.of(args.single()).toAbsolutePath().normalize()
        BenchmarkCorpusKind.entries.forEach { kind ->
            val graph = GraphStore.loadMapped(root.resolve(kind.id))
            try {
                val counts = LongArray(6)
                graph.nodes(io.johnsonlee.graphite.core.CallSiteNode::class.java).forEach { node ->
                    val classes = listOf(node.caller.declaringClass.className, node.callee.declaringClass.className)
                    val methods = listOf(node.caller.name, node.callee.name)
                    if (methods.any { it.lowercase().contains("get") }) counts[0]++
                    if (classes.any { it.lowercase().startsWith("android.") }) counts[1]++
                    if (classes.any {
                            val value = it.lowercase()
                            value.startsWith("org.apache.tika.") || value.startsWith("org.apache.hadoop.hive.")
                        }
                    ) counts[2]++
                    if (classes.any { it.lowercase().startsWith("org.jetbrains.kotlin.") }) counts[3]++
                    if (classes.any { it.lowercase().startsWith("java.") }) counts[4]++
                    if (classes.any { it.lowercase().startsWith("android.") } ||
                        methods.any { it.lowercase().endsWith("provider") }
                    ) counts[5]++
                }
                val expected = EXPECTED_DISTRIBUTIONS.getValue(kind)
                check(counts.contentEquals(expected)) {
                    "Unexpected search-target distribution for ${kind.id}: " +
                        "actual=${counts.contentToString()}, expected=${expected.contentToString()}"
                }
                println("ALL_FIXTURE_DISTRIBUTION\t${kind.id}\t${counts.joinToString("\t")}")
            } finally {
                (graph as? Closeable)?.close()
            }
        }
    }
}

private const val EXPECTED_ALL_FIXTURE_NODES = 19_091_048L

private const val ZERO_HIT_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'
   OR toLower(coalesce(n.caller_name, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'
   OR toLower(coalesce(n.callee_class, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'
   OR toLower(coalesce(n.callee_name, '')) CONTAINS 'graphite_latency_no_such_symbol_9f36'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 250
"""

private const val DENSE_DISTRIBUTED_METHOD_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_name, '')) CONTAINS 'get'
   OR toLower(coalesce(n.callee_name, '')) CONTAINS 'get'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 50
"""

private const val EARLY_GRAPH_PREFIX_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'android.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'android.'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 1
"""

private const val MIDDLE_GRAPHS_PREFIX_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.apache.tika.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.apache.tika.'
   OR toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.apache.hadoop.hive.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.apache.hadoop.hive.'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 250
"""

private const val LATE_GRAPH_PREFIX_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.jetbrains.kotlin.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.jetbrains.kotlin.'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 50
"""

private const val BROADLY_DISTRIBUTED_PREFIX_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'java.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'java.'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 250
"""

private const val FIRST_LAST_GRAPH_BIMODAL_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'android.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'android.'
   OR toLower(coalesce(n.caller_class, '')) STARTS WITH 'org.jetbrains.kotlin.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'org.jetbrains.kotlin.'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 250
"""

private const val SKEWED_MIXED_OPERATOR_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH 'android.'
   OR toLower(coalesce(n.callee_class, '')) STARTS WITH 'android.'
   OR toLower(coalesce(n.caller_name, '')) ENDS WITH 'provider'
   OR toLower(coalesce(n.callee_name, '')) ENDS WITH 'provider'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 250
"""

private val EXPECTED_DISTRIBUTIONS = mapOf(
    BenchmarkCorpusKind.ANDROID to longArrayOf(381_772, 1_089_434, 0, 0, 541_691, 1_090_613),
    BenchmarkCorpusKind.TIKA to longArrayOf(307_444, 0, 43_006, 0, 308_626, 874),
    BenchmarkCorpusKind.HIVE to longArrayOf(380_549, 0, 790_274, 0, 444_906, 602),
    BenchmarkCorpusKind.KOTLIN_COMPILER to longArrayOf(265_980, 0, 0, 891_500, 208_763, 1_509)
)

private val ALL_FIXTURE_QUERIES = listOf(
    "zeroHitBroadContains" to ZERO_HIT_QUERY,
    "denseDistributedMethodContains" to DENSE_DISTRIBUTED_METHOD_QUERY,
    "earlyGraphClassPrefix" to EARLY_GRAPH_PREFIX_QUERY,
    "middleGraphsClassPrefix" to MIDDLE_GRAPHS_PREFIX_QUERY,
    "lateGraphClassPrefix" to LATE_GRAPH_PREFIX_QUERY,
    "broadlyDistributedClassPrefix" to BROADLY_DISTRIBUTED_PREFIX_QUERY,
    "firstLastGraphBimodalClassPrefix" to FIRST_LAST_GRAPH_BIMODAL_QUERY,
    "skewedMixedClassMethodOperator" to SKEWED_MIXED_OPERATOR_QUERY
)
