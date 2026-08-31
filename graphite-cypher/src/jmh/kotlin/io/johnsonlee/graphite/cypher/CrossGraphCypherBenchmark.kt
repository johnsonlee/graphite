package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

private const val CROSS_GRAPH_COUNT = 16
private const val NODES_PER_GRAPH = 5_000
private const val CALL_CHAIN_DEPTH = 8
private const val TARGET_GRAPH_INDEX = CROSS_GRAPH_COUNT - 1
private const val TARGET_VALUE = "needle_feature_entry"

/**
 * Measures the two query shapes used by an agent researching a feature:
 * broad keyword discovery followed by graph-qualified call-chain expansion.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class CrossGraphCypherBenchmark {

    private lateinit var executor: CrossGraphCypherExecutor

    @Setup
    fun setup() {
        val graphs = (0 until CROSS_GRAPH_COUNT).map { graphIndex ->
            val builder = DefaultGraph.Builder()
            for (nodeIndex in 0 until NODES_PER_GRAPH) {
                val value = if (graphIndex == TARGET_GRAPH_INDEX && nodeIndex == 0) {
                    TARGET_VALUE
                } else {
                    "symbol_${graphIndex}_$nodeIndex"
                }
                builder.addNode(StringConstant(NodeId(nodeIndex), value))
            }
            if (graphIndex == TARGET_GRAPH_INDEX) {
                for (nodeIndex in 0 until CALL_CHAIN_DEPTH) {
                    builder.addEdge(
                        DataFlowEdge(
                            NodeId(nodeIndex),
                            NodeId(nodeIndex + 1),
                            DataFlowKind.PARAMETER_PASS
                        )
                    )
                }
            }
            CypherGraph("graph-$graphIndex", builder.build())
        }
        executor = CrossGraphCypherExecutor(graphs)

        val search = executor.execute(KEYWORD_QUERY)
        check(search.rows.size == 1 && search.rows.single()["id"] == "$TARGET_GRAPH_ID:0")
        val chain = executor.execute(callChainQuery(search.rows.single().getValue("id") as String))
        check(chain.rows.size == CALL_CHAIN_DEPTH)
        check(
            executor.execute(GRAPH_IDS_QUERY).rows.map { it["graphId"] } ==
                (0 until CROSS_GRAPH_COUNT).map { "graph-$it" }.sorted()
        )
    }

    @Benchmark
    fun keywordMissAcrossAllGraphs(): CypherResult = executor.execute(
        "MATCH (n:StringConstant) " +
            "WHERE n.value CONTAINS 'missing_feature_keyword' " +
            "RETURN elementId(n) AS id, n.value AS value LIMIT 20"
    )

    @Benchmark
    fun keywordLateHitAcrossAllGraphs(): CypherResult = executor.execute(KEYWORD_QUERY)

    @Benchmark
    fun keywordThenCallChain(): CypherResult {
        val search = executor.execute(KEYWORD_QUERY)
        val seed = search.rows.single().getValue("id") as String
        return executor.execute(callChainQuery(seed))
    }

    @Benchmark
    fun orderedDistinctGraphIds(): CypherResult = executor.execute(GRAPH_IDS_QUERY)

    private fun callChainQuery(seed: String): String =
        "MATCH (a:StringConstant) WHERE elementId(a) = '$seed' " +
            "WITH a MATCH (a)-[:DATAFLOW*1..$CALL_CHAIN_DEPTH]->(b:StringConstant) " +
            "RETURN elementId(a) AS source, elementId(b) AS target, b.value AS value " +
            "LIMIT $CALL_CHAIN_DEPTH"

    private companion object {
        const val TARGET_GRAPH_ID = "graph-$TARGET_GRAPH_INDEX"
        const val KEYWORD_QUERY =
            "MATCH (n:StringConstant) WHERE n.value CONTAINS '$TARGET_VALUE' " +
                "RETURN elementId(n) AS id, n.value AS value LIMIT 20"
        const val GRAPH_IDS_QUERY =
            "MATCH (n) RETURN DISTINCT n.graphId AS graphId ORDER BY graphId LIMIT 100"
    }
}
