package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutor
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
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1, jvmArgs = ["-Xmx16g"])
open class AndroidSchemaDiscoveryBenchmark {
    private lateinit var mappedGraph: Graph
    private lateinit var budgetedExecutor: CypherExecutor

    @Setup
    fun setup() {
        mappedGraph = GraphStore.loadMapped(
            BenchmarkCorpus.persistedGraph(BenchmarkCorpusKind.ANDROID)
        )
        check(mappedGraph.nodeCount(Node::class.java) == BenchmarkCorpusKind.ANDROID.expectedNodeCount)
        budgetedExecutor = CypherExecutor(
            mappedGraph,
            CypherExecutionBudget(SCHEMA_DISCOVERY_WORK_BUDGET)
        )
        val sample = sampleLabelsAndKeys()
        check(sample.rows.size == SCHEMA_SAMPLE_LIMIT)
        val histogram = labelHistogram()
        check(histogram.rows.isNotEmpty())
        check(
            histogram.rows.all {
                (it[SCHEMA_COUNT_COLUMN] as? Number)?.toLong()?.let { count -> count > 0L } == true
            }
        )
    }

    @TearDown
    fun tearDown() {
        (mappedGraph as? Closeable)?.close()
    }

    @Benchmark
    fun sampleLabelsAndKeys(): CypherResult = mappedGraph.query(SAMPLE_LABELS_AND_KEYS_QUERY)

    @Benchmark
    fun labelHistogram(): CypherResult = mappedGraph.query(LABEL_HISTOGRAM_QUERY)

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @Warmup(iterations = 1)
    @Measurement(iterations = 3)
    fun boundedPropertyKeyHistogram(): Long = try {
        budgetedExecutor.execute(PROPERTY_KEY_HISTOGRAM_QUERY)
        error("property-key histogram unexpectedly completed within its work budget")
    } catch (error: CypherBudgetExceededException) {
        error.maxWorkUnits
    }
}

private const val SCHEMA_SAMPLE_LIMIT = 20
private const val SCHEMA_COUNT_COLUMN = "c"
private const val SCHEMA_DISCOVERY_WORK_BUDGET = 250_000L

private const val SAMPLE_LABELS_AND_KEYS_QUERY = """
MATCH (n)
RETURN labels(n) AS labels, keys(n) AS keys
LIMIT 20
"""

private const val LABEL_HISTOGRAM_QUERY = """
MATCH (n)
UNWIND labels(n) AS label
RETURN label, count(*) AS c
ORDER BY c DESC
LIMIT 50
"""

private const val PROPERTY_KEY_HISTOGRAM_QUERY = """
MATCH (n)
UNWIND keys(n) AS key
RETURN key, count(*) AS c
ORDER BY c DESC
LIMIT 50
"""
