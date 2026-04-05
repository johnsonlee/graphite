package io.johnsonlee.graphite.webgraph

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
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * JMH benchmark for Cypher queries on a real Elasticsearch graph (968K nodes).
 *
 * Requires a pre-built graph at `/tmp/es-graph`. If not present, the benchmark
 * is effectively a no-op (returns empty result from a minimal graph).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class QueryBenchmark {

    private lateinit var graph: Graph

    @Setup
    fun setup() {
        val graphPath = Path.of("/tmp/es-graph")
        if (!Files.isDirectory(graphPath)) {
            throw IllegalStateException(
                "ES graph not found at /tmp/es-graph. " +
                "Build it first before running this benchmark."
            )
        }
        graph = GraphStore.load(graphPath)
    }

    @Benchmark
    fun simpleNodeMatch() = graph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun intConstantFilter() = graph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id"
    )

    @Benchmark
    fun regexFilter() = graph.query(
        "MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'org\\.elasticsearch\\..*' RETURN n.callee_name LIMIT 50"
    )

    @Benchmark
    fun countStar() = graph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun singleHopRelationship() = graph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun returnDistinct() = graph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )

    @Benchmark
    fun aggregationCountGroupBy() = graph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_class, count(*) AS cnt"
    )

    @Benchmark
    fun variableLengthPath() = graph.query(
        "MATCH (a:IntConstant)-[:DATAFLOW*..2]->(b:CallSiteNode) RETURN a.value, b.callee_name LIMIT 10"
    )
}

/**
 * Run benchmarks from command line:
 *   java -cp <classpath> io.johnsonlee.graphite.webgraph.QueryBenchmarkRunner
 */
class QueryBenchmarkRunner {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val options = OptionsBuilder()
                .include(QueryBenchmark::class.java.simpleName)
                .build()
            Runner(options).run()
        }
    }
}
