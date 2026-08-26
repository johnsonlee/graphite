package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.*
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// ============================================================================
//  Load benchmarks: eager vs mapped on large real-world graphs
// ============================================================================

/**
 * Benchmarks eager and mapped loading on Android SDK graph (5.9M nodes).
 * The persisted graph is auto-prepared from the fixture JAR if needed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AndroidLoadBenchmark {
    private lateinit var graphPath: Path

    @Setup(Level.Trial)
    fun setup() {
        graphPath = BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.ANDROID)
    }

    @Benchmark
    fun eager_load(): Long = loadAndTouch { GraphStore.load(graphPath, GraphStore.LoadMode.EAGER) }

    @Benchmark
    fun mapped_load(): Long = loadAndTouch { GraphStore.loadMapped(graphPath) }

    private fun loadAndTouch(loader: () -> Graph): Long {
        val graph = loader()
        return try {
            graph.nodes(Node::class.java).take(1).count().toLong()
        } finally {
            (graph as? Closeable)?.close()
        }
    }
}

// ============================================================================
//  Query benchmarks: eager vs mapped on large real-world graphs
// ============================================================================

/**
 * Compares query performance on Android SDK graph (5.9M nodes, ~6.5M edges).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AndroidQueryBenchmark {
    @Benchmark
    fun eager_simpleNodeMatch(state: AndroidEagerQueryBenchmarkState) = state.graph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun eager_intConstantFilter(state: AndroidEagerQueryBenchmarkState) = state.graph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id LIMIT 100"
    )

    @Benchmark
    fun eager_countStar(state: AndroidEagerQueryBenchmarkState) = state.graph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun eager_singleHopRelationship(state: AndroidEagerQueryBenchmarkState) = state.graph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun eager_returnDistinct(state: AndroidEagerQueryBenchmarkState) = state.graph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )

    @Benchmark
    fun mapped_simpleNodeMatch(state: AndroidMappedQueryBenchmarkState) = state.graph.query(
        "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
    )

    @Benchmark
    fun mapped_intConstantFilter(state: AndroidMappedQueryBenchmarkState) = state.graph.query(
        "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id LIMIT 100"
    )

    @Benchmark
    fun mapped_countStar(state: AndroidMappedQueryBenchmarkState) = state.graph.query(
        "MATCH (n:CallSiteNode) RETURN count(*)"
    )

    @Benchmark
    fun mapped_singleHopRelationship(state: AndroidMappedQueryBenchmarkState) = state.graph.query(
        "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
    )

    @Benchmark
    fun mapped_returnDistinct(state: AndroidMappedQueryBenchmarkState) = state.graph.query(
        "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    )
}

@State(Scope.Benchmark)
open class AndroidEagerQueryBenchmarkState {
    lateinit var graph: Graph

    @Setup(Level.Trial)
    fun setup() {
        graph = GraphStore.load(
            BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.ANDROID),
            GraphStore.LoadMode.EAGER
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() = closeGraph(graph)
}

@State(Scope.Benchmark)
open class AndroidMappedQueryBenchmarkState {
    lateinit var graph: Graph

    @Setup(Level.Trial)
    fun setup() {
        graph = GraphStore.loadMapped(BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.ANDROID))
    }

    @TearDown(Level.Trial)
    fun tearDown() = closeGraph(graph)
}

/** Applies the Android load benchmark protocol to Tika, Hive, and the Kotlin compiler. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class LargeCorpusLoadBenchmark {
    @Param("TIKA", "HIVE", "KOTLIN_COMPILER")
    lateinit var corpus: String

    private lateinit var graphPath: Path

    @Setup(Level.Trial)
    fun setup() {
        graphPath = BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.valueOf(corpus))
    }

    @Benchmark
    fun eager_load(): Long = loadAndTouch { GraphStore.load(graphPath, GraphStore.LoadMode.EAGER) }

    @Benchmark
    fun mapped_load(): Long = loadAndTouch { GraphStore.loadMapped(graphPath) }

    private fun loadAndTouch(loader: () -> Graph): Long {
        val graph = loader()
        return try {
            graph.nodes(Node::class.java).take(1).count().toLong()
        } finally {
            (graph as? Closeable)?.close()
        }
    }
}

/** Applies the Android query benchmark protocol to Tika, Hive, and the Kotlin compiler. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class LargeCorpusQueryBenchmark {
    @Benchmark
    fun eager_simpleNodeMatch(state: LargeCorpusEagerQueryBenchmarkState) = state.graph.query(SIMPLE_NODE_QUERY)

    @Benchmark
    fun eager_intConstantFilter(state: LargeCorpusEagerQueryBenchmarkState) = state.graph.query(INT_CONSTANT_QUERY)

    @Benchmark
    fun eager_countStar(state: LargeCorpusEagerQueryBenchmarkState) = state.graph.query(COUNT_QUERY)

    @Benchmark
    fun eager_singleHopRelationship(state: LargeCorpusEagerQueryBenchmarkState) = state.graph.query(SINGLE_HOP_QUERY)

    @Benchmark
    fun eager_returnDistinct(state: LargeCorpusEagerQueryBenchmarkState) = state.graph.query(DISTINCT_QUERY)

    @Benchmark
    fun mapped_simpleNodeMatch(state: LargeCorpusMappedQueryBenchmarkState) = state.graph.query(SIMPLE_NODE_QUERY)

    @Benchmark
    fun mapped_intConstantFilter(state: LargeCorpusMappedQueryBenchmarkState) = state.graph.query(INT_CONSTANT_QUERY)

    @Benchmark
    fun mapped_countStar(state: LargeCorpusMappedQueryBenchmarkState) = state.graph.query(COUNT_QUERY)

    @Benchmark
    fun mapped_singleHopRelationship(state: LargeCorpusMappedQueryBenchmarkState) = state.graph.query(SINGLE_HOP_QUERY)

    @Benchmark
    fun mapped_returnDistinct(state: LargeCorpusMappedQueryBenchmarkState) = state.graph.query(DISTINCT_QUERY)

    private companion object {
        const val SIMPLE_NODE_QUERY = "MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100"
        const val INT_CONSTANT_QUERY = "MATCH (n:IntConstant) WHERE n.value = 0 RETURN n.id LIMIT 100"
        const val COUNT_QUERY = "MATCH (n:CallSiteNode) RETURN count(*)"
        const val SINGLE_HOP_QUERY =
            "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 20"
        const val DISTINCT_QUERY = "MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class LIMIT 20"
    }
}

@State(Scope.Benchmark)
open class LargeCorpusEagerQueryBenchmarkState {
    @Param("TIKA", "HIVE", "KOTLIN_COMPILER")
    lateinit var corpus: String

    lateinit var graph: Graph

    @Setup(Level.Trial)
    fun setup() {
        graph = GraphStore.load(
            BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.valueOf(corpus)),
            GraphStore.LoadMode.EAGER
        )
    }

    @TearDown(Level.Trial)
    fun tearDown() = closeGraph(graph)
}

@State(Scope.Benchmark)
open class LargeCorpusMappedQueryBenchmarkState {
    @Param("TIKA", "HIVE", "KOTLIN_COMPILER")
    lateinit var corpus: String

    lateinit var graph: Graph

    @Setup(Level.Trial)
    fun setup() {
        graph = GraphStore.loadMapped(BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.valueOf(corpus)))
    }

    @TearDown(Level.Trial)
    fun tearDown() = closeGraph(graph)
}

private fun closeGraph(graph: Graph) {
    (graph as? Closeable)?.close()
}
