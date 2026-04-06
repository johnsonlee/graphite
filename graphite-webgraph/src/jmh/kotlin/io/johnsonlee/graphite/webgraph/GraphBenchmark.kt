package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// ============================================================================
//  Load benchmarks: eager vs lazy on ES and Android graphs
// ============================================================================

/**
 * Benchmarks [GraphStore.load] vs [GraphStore.loadLazy] on the ES graph (968K nodes).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
open class EsLoadBenchmark {

    @Benchmark
    fun eager_load(): Graph = GraphStore.load(Path.of("/tmp/es-graph"))

    @Benchmark
    fun lazy_load(): Graph = GraphStore.loadLazy(Path.of("/tmp/es-graph"))

    @Benchmark
    fun mapped_load(): Graph = GraphStore.loadMapped(Path.of("/tmp/es-graph"))
}

/**
 * Benchmarks [GraphStore.load] vs [GraphStore.loadLazy] on Android SDK graph (5.9M nodes).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AndroidLoadBenchmark {

    @Benchmark
    fun eager_load(): Graph = GraphStore.load(Path.of("/tmp/android-graph-v2"))

    @Benchmark
    fun lazy_load(): Graph = GraphStore.loadLazy(Path.of("/tmp/android-graph-v2"))

    @Benchmark
    fun mapped_load(): Graph = GraphStore.loadMapped(Path.of("/tmp/android-graph-v2"))
}

// ============================================================================
//  Query benchmarks: eager vs lazy on ES graph (968K nodes)
// ============================================================================

/**
 * Compares query performance between eager-loaded (all nodes in memory) and
 * lazy-loaded (nodes read from disk on demand) graphs.
 *
 * ES graph: 968K nodes, ~1M edges.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class EsQueryBenchmark {

    private lateinit var eagerGraph: Graph
    private lateinit var lazyGraph: Graph
    private lateinit var mappedGraph: Graph

    @Setup
    fun setup() {
        val graphPath = Path.of("/tmp/es-graph")
        if (!Files.isDirectory(graphPath)) {
            throw IllegalStateException("ES graph not found at /tmp/es-graph")
        }
        eagerGraph = GraphStore.load(graphPath)
        lazyGraph = GraphStore.loadLazy(graphPath)
        mappedGraph = GraphStore.loadMapped(graphPath)
    }

    @TearDown
    fun tearDown() {
        (lazyGraph as? Closeable)?.close()
        (mappedGraph as? Closeable)?.close()
    }

    // --- Eager ---

    @Benchmark
    fun eager_simpleNodeMatch() = eagerGraph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun eager_intConstantFilter() = eagerGraph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id"
    )

    @Benchmark
    fun eager_countStar() = eagerGraph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun eager_singleHopRelationship() = eagerGraph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun eager_returnDistinct() = eagerGraph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )

    @Benchmark
    fun eager_regexFilter() = eagerGraph.query(
        "MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'org\\.elasticsearch\\..*' RETURN n.callee_name LIMIT 50"
    )

    // --- Lazy ---

    @Benchmark
    fun lazy_simpleNodeMatch() = lazyGraph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun lazy_intConstantFilter() = lazyGraph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id"
    )

    @Benchmark
    fun lazy_countStar() = lazyGraph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun lazy_singleHopRelationship() = lazyGraph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun lazy_returnDistinct() = lazyGraph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )

    @Benchmark
    fun lazy_regexFilter() = lazyGraph.query(
        "MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'org\\.elasticsearch\\..*' RETURN n.callee_name LIMIT 50"
    )

    // --- Mapped ---

    @Benchmark
    fun mapped_simpleNodeMatch() = mappedGraph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun mapped_intConstantFilter() = mappedGraph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id"
    )

    @Benchmark
    fun mapped_countStar() = mappedGraph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun mapped_singleHopRelationship() = mappedGraph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun mapped_returnDistinct() = mappedGraph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )

    @Benchmark
    fun mapped_regexFilter() = mappedGraph.query(
        "MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'org\\.elasticsearch\\..*' RETURN n.callee_name LIMIT 50"
    )
}

// ============================================================================
//  Query benchmarks: eager vs lazy on Android SDK graph (5.9M nodes)
// ============================================================================

/**
 * Compares query performance on Android SDK graph (5.9M nodes, ~6.5M edges).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AndroidQueryBenchmark {

    private lateinit var eagerGraph: Graph
    private lateinit var lazyGraph: Graph
    private lateinit var mappedGraph: Graph

    @Setup
    fun setup() {
        val graphPath = Path.of("/tmp/android-graph-v2")
        if (!Files.isDirectory(graphPath)) {
            throw IllegalStateException("Android graph not found at /tmp/android-graph-v2")
        }
        eagerGraph = GraphStore.load(graphPath)
        lazyGraph = GraphStore.loadLazy(graphPath)
        mappedGraph = GraphStore.loadMapped(graphPath)
    }

    @TearDown
    fun tearDown() {
        (lazyGraph as? Closeable)?.close()
        (mappedGraph as? Closeable)?.close()
    }

    // --- Eager ---

    @Benchmark
    fun eager_simpleNodeMatch() = eagerGraph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun eager_intConstantFilter() = eagerGraph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id LIMIT 100"
    )

    @Benchmark
    fun eager_countStar() = eagerGraph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun eager_singleHopRelationship() = eagerGraph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun eager_returnDistinct() = eagerGraph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )

    // --- Lazy ---

    @Benchmark
    fun lazy_simpleNodeMatch() = lazyGraph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun lazy_intConstantFilter() = lazyGraph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id LIMIT 100"
    )

    @Benchmark
    fun lazy_countStar() = lazyGraph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun lazy_singleHopRelationship() = lazyGraph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun lazy_returnDistinct() = lazyGraph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )

    // --- Mapped ---

    @Benchmark
    fun mapped_simpleNodeMatch() = mappedGraph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun mapped_intConstantFilter() = mappedGraph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id LIMIT 100"
    )

    @Benchmark
    fun mapped_countStar() = mappedGraph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun mapped_singleHopRelationship() = mappedGraph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun mapped_returnDistinct() = mappedGraph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )
}
