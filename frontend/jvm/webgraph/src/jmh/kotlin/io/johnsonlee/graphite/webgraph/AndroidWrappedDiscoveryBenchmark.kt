package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherResult
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
import java.util.concurrent.TimeUnit

/** Manual benchmark for the exact 1/17-source Android production query. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AndroidWrappedDiscoveryBenchmark {

    @Param("1", "17")
    @JvmField
    var graphCount: Int = 1

    private lateinit var mappedGraph: Graph
    private lateinit var executor: CrossGraphCypherExecutor

    @Setup
    fun setup() {
        mappedGraph = GraphStore.loadMapped(BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.ANDROID))
        check(mappedGraph.nodeCount(io.johnsonlee.graphite.core.Node::class.java) ==
            BenchmarkCorpusKind.ANDROID.expectedNodeCount)
        executor = CrossGraphCypherExecutor(
            (0 until graphCount).map { CypherGraph("android-$it", mappedGraph) }
        )
    }

    @TearDown
    fun tearDown() {
        (mappedGraph as? Closeable)?.close()
    }

    @Benchmark
    fun wrappedCaseInsensitiveDiscovery(): CypherResult = executor.execute(ANDROID_WRAPPED_DISCOVERY_QUERY)
}

private const val ANDROID_WRAPPED_DISCOVERY_QUERY = """
MATCH (n)
WHERE toLower(coalesce(n.caller_class, '')) CONTAINS 'voucher'
   OR toLower(coalesce(n.caller_name, '')) CONTAINS 'voucher'
   OR toLower(coalesce(n.callee_class, '')) CONTAINS 'voucher'
   OR toLower(coalesce(n.callee_name, '')) CONTAINS 'voucher'
RETURN DISTINCT n.graph_id, n.caller_class, n.caller_name, n.callee_class, n.callee_name
LIMIT 250
"""
