package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CypherResult
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
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
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AndroidBroadDiscoveryBenchmark {
    private lateinit var mappedGraph: Graph
    private var clearStringPropertyIndexes: Method? = null

    @Setup
    fun setup() {
        val graphPath = BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.ANDROID)
        mappedGraph = GraphStore.loadMapped(graphPath)
        clearStringPropertyIndexes = mappedGraph.javaClass.declaredMethods
            .firstOrNull { it.name.startsWith("clearStringPropertyIndexes") }
            ?.also { it.isAccessible = true }
        check(execute().rows.size == BROAD_DISCOVERY_LIMIT)
    }

    @TearDown
    fun tearDown() {
        (mappedGraph as? Closeable)?.close()
    }

    @Benchmark
    fun coldBroadDiscovery(): CypherResult {
        clearStringPropertyIndexes?.invoke(mappedGraph)
        return execute()
    }

    @Benchmark
    fun repeatedBroadDiscovery(): CypherResult = execute()

    private fun execute(): CypherResult = mappedGraph.query(ANDROID_BROAD_DISCOVERY_QUERY)
}

private const val BROAD_DISCOVERY_LIMIT = 120

private const val ANDROID_BROAD_DISCOVERY_QUERY = """
MATCH (n)
WHERE (exists(n.class) AND n.class CONTAINS 'android')
   OR (exists(n.name) AND n.name CONTAINS 'android')
   OR (exists(n.caller_class) AND n.caller_class CONTAINS 'android')
   OR (exists(n.caller_name) AND n.caller_name CONTAINS 'android')
   OR (exists(n.callee_class) AND n.callee_class CONTAINS 'android')
   OR (exists(n.callee_name) AND n.callee_name CONTAINS 'android')
RETURN DISTINCT n.class AS class, n.name AS name,
    n.caller_class AS caller, n.caller_name AS callerMethod,
    n.callee_class AS callee, n.callee_name AS calleeMethod
LIMIT 120
"""
