package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.cypher.CypherResult
import io.johnsonlee.graphite.graph.DefaultGraph
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val ADMISSION_RESOURCE_NODES = 50_000
private const val ADMISSION_FIELD_NODES = 50_000

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1, jvmArgs = ["-Xmx4g"])
open class MappedStringAdmissionBenchmark {

    @Benchmark
    fun earlyHitAfterAdmittedMiss(state: AdmittedMissBenchmarkState): CypherResult = state.earlyHit()

    @Benchmark
    fun earlyHitAfterLruEviction(state: EvictedIndexBenchmarkState): CypherResult = state.earlyHit()
}

@State(Scope.Thread)
open class AdmittedMissBenchmarkState : MappedStringAdmissionBenchmarkState() {
    @Setup(Level.Invocation)
    fun admitMiss() {
        clearIndexes()
        check(execute(MISSING_PATH_QUERY).rows.isEmpty())
    }
}

@State(Scope.Thread)
open class EvictedIndexBenchmarkState : MappedStringAdmissionBenchmarkState() {
    @Setup(Level.Invocation)
    fun evictFirstIndex() {
        clearIndexes()
        INDEX_ADMISSION_QUERIES.forEach { query ->
            repeat(2) { check(execute(query).rows.isEmpty()) }
        }
        check(indexCount() == 4)
        check(indexCount(ResourceFileNode::class.java, "path") == 0)
    }
}

@State(Scope.Thread)
open class MappedStringAdmissionBenchmarkState {
    private lateinit var root: Path
    private lateinit var mapped: MappedWebGraphBackedGraph
    private lateinit var executor: CypherExecutor

    @Setup(Level.Trial)
    fun setupGraph() {
        val graph = DefaultGraph.Builder().apply {
            repeat(ADMISSION_RESOURCE_NODES) { index ->
                addNode(ResourceFileNode(NodeId(index), "path-$index", "source-$index", "format-$index"))
            }
            repeat(ADMISSION_FIELD_NODES) { index ->
                addNode(
                    FieldNode(
                        NodeId(ADMISSION_RESOURCE_NODES + index),
                        FieldDescriptor(
                            TypeDescriptor("example.Owner$index"),
                            "field-$index",
                            TypeDescriptor("example.Type$index")
                        ),
                        false
                    )
                )
            }
        }.build()
        root = Files.createTempDirectory("graphite-string-admission")
        GraphStore.save(graph, root)
        mapped = GraphStore.loadMapped(root) as MappedWebGraphBackedGraph
        executor = CypherExecutor(mapped)
        check(earlyHit().rows.single()["value"] == "path-0")
    }

    @TearDown(Level.Trial)
    fun tearDownGraph() {
        mapped.close()
        root.toFile().deleteRecursively()
    }

    fun earlyHit(): CypherResult = executor.execute(EARLY_PATH_QUERY)

    protected fun clearIndexes() = mapped.clearStringPropertyIndexes()

    protected fun execute(query: String): CypherResult = executor.execute(query)

    protected fun indexCount(type: Class<out Node>? = null, property: String? = null): Int =
        mapped.stringPropertyIndexCount(type, property)

    private companion object {
        const val EARLY_PATH_QUERY =
            "MATCH (n:ResourceFileNode) WHERE n.path CONTAINS 'path-0' " +
                "RETURN n.path AS value LIMIT 1"
    }
}

private const val MISSING_PATH_QUERY =
    "MATCH (n:ResourceFileNode) WHERE n.path CONTAINS 'missing-path' RETURN n.path AS value LIMIT 1"

private val INDEX_ADMISSION_QUERIES = listOf(
    MISSING_PATH_QUERY,
    "MATCH (n:ResourceFileNode) WHERE n.source CONTAINS 'missing-source' RETURN n.source AS value LIMIT 1",
    "MATCH (n:ResourceFileNode) WHERE n.format CONTAINS 'missing-format' RETURN n.format AS value LIMIT 1",
    "MATCH (n:FieldNode) WHERE n.class CONTAINS 'missing-class' RETURN n.class AS value LIMIT 1",
    "MATCH (n:FieldNode) WHERE n.name CONTAINS 'missing-name' RETURN n.name AS value LIMIT 1"
)
