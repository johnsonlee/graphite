package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherResult
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
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val MAPPED_GRAPH_COUNT = 16
private const val MAPPED_NODES_PER_GRAPH = 5_000
private const val MAPPED_CHAIN_DEPTH = 8
private const val MAPPED_TARGET_GRAPH_INDEX = MAPPED_GRAPH_COUNT - 1
private const val MAPPED_TARGET_GRAPH_ID = "graph-$MAPPED_TARGET_GRAPH_INDEX"
private const val MAPPED_TARGET_VALUE = "needle_feature_entry"

/** Production-path counterpart to the in-memory cross-graph Cypher benchmark. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1, jvmArgs = ["-Xmx4g"])
open class CrossGraphMappedQueryBenchmark {

    private lateinit var root: Path
    private lateinit var executor: CrossGraphCypherExecutor
    private val loadedGraphs = mutableListOf<Closeable>()

    @Setup
    fun setup() {
        root = Files.createTempDirectory("graphite-cross-graph-query")
        val graphs = (0 until MAPPED_GRAPH_COUNT).map { graphIndex ->
            val builder = DefaultGraph.Builder()
            for (nodeIndex in 0 until MAPPED_NODES_PER_GRAPH) {
                val value = if (graphIndex == MAPPED_TARGET_GRAPH_INDEX && nodeIndex == 0) {
                    MAPPED_TARGET_VALUE
                } else {
                    "symbol_${graphIndex}_$nodeIndex"
                }
                builder.addNode(StringConstant(NodeId(nodeIndex), value))
            }
            if (graphIndex == MAPPED_TARGET_GRAPH_INDEX) {
                for (nodeIndex in 0 until MAPPED_CHAIN_DEPTH) {
                    builder.addEdge(
                        DataFlowEdge(
                            NodeId(nodeIndex),
                            NodeId(nodeIndex + 1),
                            DataFlowKind.PARAMETER_PASS
                        )
                    )
                }
            }

            val graphDir = root.resolve("graph-$graphIndex")
            GraphStore.save(builder.build(), graphDir)
            val graph = GraphStore.loadMapped(graphDir)
            loadedGraphs.add(graph as Closeable)
            CypherGraph("graph-$graphIndex", graph)
        }
        executor = CrossGraphCypherExecutor(graphs)

        val search = executor.execute(KEYWORD_QUERY)
        check(search.rows.size == 1 && search.rows.single()["id"] == "$MAPPED_TARGET_GRAPH_ID:0")
        val chain = executor.execute(callChainQuery(search.rows.single().getValue("id") as String))
        check(chain.rows.size == MAPPED_CHAIN_DEPTH)
    }

    @TearDown
    fun tearDown() {
        loadedGraphs.forEach(Closeable::close)
        root.toFile().deleteRecursively()
    }

    @Benchmark
    fun keywordMissAcrossAllMappedGraphs(): CypherResult = executor.execute(
        "MATCH (n:StringConstant) " +
            "WHERE n.value CONTAINS 'missing_feature_keyword' " +
            "RETURN elementId(n) AS id, n.value AS value LIMIT 20"
    )

    @Benchmark
    fun keywordLateHitAcrossAllMappedGraphs(): CypherResult = executor.execute(KEYWORD_QUERY)

    @Benchmark
    fun keywordThenMappedCallChain(): CypherResult {
        val search = executor.execute(KEYWORD_QUERY)
        val seed = search.rows.single().getValue("id") as String
        return executor.execute(callChainQuery(seed))
    }

    private fun callChainQuery(seed: String): String =
        "MATCH (a:StringConstant) WHERE elementId(a) = '$seed' " +
            "WITH a MATCH (a)-[:DATAFLOW*1..$MAPPED_CHAIN_DEPTH]->(b:StringConstant) " +
            "RETURN elementId(a) AS source, elementId(b) AS target, b.value AS value " +
            "LIMIT $MAPPED_CHAIN_DEPTH"

    private companion object {
        const val KEYWORD_QUERY =
            "MATCH (n:StringConstant) WHERE n.value CONTAINS '$MAPPED_TARGET_VALUE' " +
                "RETURN elementId(n) AS id, n.value AS value LIMIT 20"
    }
}
