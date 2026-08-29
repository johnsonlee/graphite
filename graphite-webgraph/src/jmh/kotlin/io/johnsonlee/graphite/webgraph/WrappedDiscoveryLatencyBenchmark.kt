package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherResult
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
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
import org.openjdk.jmh.annotations.Warmup
import java.io.Closeable
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val WRAPPED_DISCOVERY_STRINGS_PER_GRAPH = 5_000
private const val WRAPPED_DISCOVERY_CALL_SITES_PER_GRAPH = 2_000
private const val WRAPPED_DISCOVERY_HIT_INTERVAL = 1_000

/**
 * Stable persisted-graph gate for the production query shape that regressed to
 * roughly one complete scan per graph. The same source is compiled at the
 * fixed pre-PR-95 baseline and at the candidate revision in CI.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1, jvmArgs = ["-Xmx4g"])
open class WrappedDiscoveryLatencyBenchmark {

    @Param("1", "4", "16", "64")
    @JvmField
    var graphCount: Int = 1

    private lateinit var root: Path
    private lateinit var executor: CrossGraphCypherExecutor
    private val loadedGraphs = mutableListOf<Graph>()
    private val clearIndexMethods = mutableListOf<Method?>()

    @Setup
    fun setup() {
        root = Files.createTempDirectory("graphite-wrapped-discovery")
        val graphs = (0 until graphCount).map { graphIndex ->
            val builder = DefaultGraph.Builder()
            repeat(WRAPPED_DISCOVERY_STRINGS_PER_GRAPH) { nodeIndex ->
                builder.addNode(StringConstant(NodeId(nodeIndex), "symbol_${graphIndex}_$nodeIndex"))
            }
            repeat(WRAPPED_DISCOVERY_CALL_SITES_PER_GRAPH) { callSiteIndex ->
                val marker = if (callSiteIndex % WRAPPED_DISCOVERY_HIT_INTERVAL == 0) "VoUcHeR" else "Feature"
                val caller = MethodDescriptor(
                    TypeDescriptor("com.example.${marker}${graphIndex}Service$callSiteIndex"),
                    "create$callSiteIndex",
                    emptyList(),
                    TypeDescriptor("void")
                )
                val callee = MethodDescriptor(
                    TypeDescriptor("com.example.Dependency${callSiteIndex % 20}"),
                    "invoke${callSiteIndex % 100}",
                    emptyList(),
                    TypeDescriptor("void")
                )
                builder.addNode(
                    CallSiteNode(
                        NodeId(WRAPPED_DISCOVERY_STRINGS_PER_GRAPH + callSiteIndex),
                        caller,
                        callee,
                        callSiteIndex,
                        null,
                        emptyList()
                    )
                )
            }

            val graphDir = root.resolve("graph-$graphIndex")
            GraphStore.save(builder.build(), graphDir)
            val graph = GraphStore.loadMapped(graphDir)
            loadedGraphs += graph
            clearIndexMethods += graph.javaClass.declaredMethods
                .firstOrNull { it.name.startsWith("clearStringPropertyIndexes") }
                ?.also { it.isAccessible = true }
            CypherGraph("graph-$graphIndex", graph)
        }
        executor = budgetedLatencyExecutor(graphs)

        val result = executeSuccessfulQuery()
        check(result.rows.all { (it["caller"] as? String)?.lowercase()?.contains("voucher") == true })
    }

    @TearDown
    fun tearDown() {
        loadedGraphs.forEach { (it as? Closeable)?.close() }
        root.toFile().deleteRecursively()
    }

    @Benchmark
    fun wrappedCaseInsensitiveDiscovery(): CypherResult = executeSuccessfulQuery()

    @Benchmark
    fun coldWrappedCaseInsensitiveDiscovery(): CypherResult {
        loadedGraphs.indices.forEach { index -> clearIndexMethods[index]?.invoke(loadedGraphs[index]) }
        return executeSuccessfulQuery()
    }

    private fun executeSuccessfulQuery(): CypherResult = executor.execute(WRAPPED_DISCOVERY_QUERY).also { result ->
        check(result.rows.size == graphCount * EXPECTED_HITS_PER_GRAPH) {
            "Successful query returned ${result.rows.size} rows; " +
                "expected ${graphCount * EXPECTED_HITS_PER_GRAPH}"
        }
    }

    private companion object {
        const val EXPECTED_HITS_PER_GRAPH =
            WRAPPED_DISCOVERY_CALL_SITES_PER_GRAPH / WRAPPED_DISCOVERY_HIT_INTERVAL
    }
}

internal const val WRAPPED_DISCOVERY_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) CONTAINS 'voucher'
   OR toLower(coalesce(n.caller_name, '')) CONTAINS 'voucher'
   OR toLower(coalesce(n.callee_class, '')) CONTAINS 'voucher'
   OR toLower(coalesce(n.callee_name, '')) CONTAINS 'voucher'
RETURN DISTINCT n.graph_id, n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 250
"""
