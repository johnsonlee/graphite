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

private const val AGENT_GRAPH_COUNT = 16
private const val AGENT_STRINGS_PER_GRAPH = 5_000
private const val AGENT_CALL_SITES_PER_GRAPH = 2_000

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1, jvmArgs = ["-Xmx4g"])
open class AgentBroadDiscoveryMappedQueryBenchmark {

    private lateinit var root: Path
    private lateinit var executor: CrossGraphCypherExecutor
    private val loadedGraphs = mutableListOf<MappedWebGraphBackedGraph>()

    @Setup
    fun setup() {
        root = Files.createTempDirectory("graphite-agent-discovery")
        val graphs = (0 until AGENT_GRAPH_COUNT).map { graphIndex ->
            val builder = DefaultGraph.Builder()
            repeat(AGENT_STRINGS_PER_GRAPH) { nodeIndex ->
                builder.addNode(StringConstant(NodeId(nodeIndex), "symbol_${graphIndex}_$nodeIndex"))
            }
            repeat(AGENT_CALL_SITES_PER_GRAPH) { callSiteIndex ->
                val prefix = if (callSiteIndex % 10 == 0) "ThankYou" else "Feature"
                val caller = MethodDescriptor(
                    TypeDescriptor("com.example.$prefix${graphIndex}Service$callSiteIndex"),
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
                        NodeId(AGENT_STRINGS_PER_GRAPH + callSiteIndex),
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
            val graph = GraphStore.loadMapped(graphDir) as MappedWebGraphBackedGraph
            loadedGraphs += graph
            CypherGraph("graph-$graphIndex", graph)
        }
        executor = CrossGraphCypherExecutor(graphs)

        val result = executor.execute(HIT_QUERY)
        check(result.rows.size == 120)
        check(result.rows.all { (it["caller"] as? String)?.contains("ThankYou") == true })
        check(executor.execute(MISS_QUERY).rows.isEmpty())
    }

    @TearDown
    fun tearDown() {
        loadedGraphs.forEach(Closeable::close)
        root.toFile().deleteRecursively()
    }

    @Benchmark
    fun broadDiscoveryAcrossAllMappedGraphs(): CypherResult = executor.execute(HIT_QUERY)

    @Benchmark
    fun coldBroadDiscoveryAcrossAllMappedGraphs(): CypherResult {
        loadedGraphs.forEach(MappedWebGraphBackedGraph::clearStringPropertyIndexes)
        return executor.execute(HIT_QUERY)
    }

    @Benchmark
    fun coldBroadDiscoveryMissAcrossAllMappedGraphs(): CypherResult {
        loadedGraphs.forEach(MappedWebGraphBackedGraph::clearStringPropertyIndexes)
        return executor.execute(MISS_QUERY)
    }

    private companion object {
        val HIT_QUERY = discoveryQuery("ThankYou")
        val MISS_QUERY = discoveryQuery("MissingAgentFeature")

        private fun discoveryQuery(keyword: String): String =
            "MATCH (n) WHERE " +
                "(exists(n.class) AND n.class CONTAINS '$keyword') OR " +
                "(exists(n.name) AND n.name CONTAINS '$keyword') OR " +
                "(exists(n.caller_class) AND n.caller_class CONTAINS '$keyword') OR " +
                "(exists(n.caller_name) AND n.caller_name CONTAINS '$keyword') OR " +
                "(exists(n.callee_class) AND n.callee_class CONTAINS '$keyword') OR " +
                "(exists(n.callee_name) AND n.callee_name CONTAINS '$keyword') " +
                "RETURN DISTINCT n.class AS class, n.name AS name, " +
                "n.caller_class AS caller, n.caller_name AS callerMethod, " +
                "n.callee_class AS callee, n.callee_name AS calleeMethod LIMIT 120"
    }
}
