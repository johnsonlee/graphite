package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// ============================================================================
//  Save/load at 10M scale with 4GB heap
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.filter='GraphBuildPersist'
// ============================================================================

/**
 * End-to-end save/load benchmark at 10M nodes under 4GB heap.
 * Validates that inline nodeindex build works without OOM.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = ["-Xmx4g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class GraphBuildPersistBenchmark {

    @Param("10000000")
    var nodeCount: Int = 0

    @Param("1", "2", "3", "4")
    var compressionThreads: Int = 0

    private lateinit var graph: Graph
    private lateinit var savedDir: Path

    @Setup(Level.Trial)
    fun setup() {
        NodeId.reset()
        val builder = DefaultGraph.Builder()

        val annotationInterval = 10
        for (i in 1..nodeCount) {
            if (i % annotationInterval == 0) {
                builder.addNode(AnnotationNode(
                    id = NodeId.next(),
                    name = "org.example.Ann${i % 100}",
                    className = "com.example.Class${i % 1000}",
                    memberName = "method${i % 500}",
                    values = mapOf("value" to "/path/$i")
                ))
            } else {
                builder.addNode(IntConstant(NodeId.next(), i))
            }
        }

        for (i in 1 until nodeCount) {
            builder.addEdge(DataFlowEdge(NodeId(i), NodeId(i + 1), DataFlowKind.ASSIGN))
        }

        graph = builder.build()

        savedDir = Files.createTempDirectory("graphite-bench")
        GraphStore.save(graph, savedDir, compressionThreads = 2)
    }

    @Benchmark
    fun save() {
        val dir = Files.createTempDirectory("graphite-bench-save")
        try {
            GraphStore.save(graph, dir, compressionThreads = compressionThreads)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Benchmark
    fun load(): Graph = GraphStore.load(savedDir)

    @TearDown(Level.Trial)
    fun teardown() {
        savedDir.toFile().deleteRecursively()
    }
}
