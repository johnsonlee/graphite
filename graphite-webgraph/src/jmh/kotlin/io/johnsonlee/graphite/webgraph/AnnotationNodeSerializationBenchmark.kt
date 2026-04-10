package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// ============================================================================
//  AnnotationNode serialization: measures GraphStore save/load throughput
//  for graphs containing AnnotationNodes mixed with regular nodes.
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='AnnotationNodeSerialization'
// ============================================================================

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(value = 2, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class AnnotationNodeSerializationBenchmark {

    @Param("1000", "10000", "50000")
    var annotationCount: Int = 0

    private lateinit var graph: Graph
    private lateinit var savedDir: Path

    @Setup(Level.Trial)
    fun setup() {
        NodeId.reset()

        val builder = DefaultGraph.Builder()

        // Add 1000 regular nodes as baseline
        for (i in 1..1000) {
            builder.addNode(IntConstant(NodeId.next(), i))
        }

        // Add N AnnotationNodes
        for (i in 1..annotationCount) {
            builder.addNode(AnnotationNode(
                id = NodeId.next(),
                name = "org.example.Annotation$i",
                className = "com.example.Class${i % 100}",
                memberName = "method${i % 50}",
                values = mapOf("value" to "/api/path/$i")
            ))
        }

        graph = builder.build()

        // Pre-save for load benchmark
        savedDir = Files.createTempDirectory("annotation-bench")
        GraphStore.save(graph, savedDir)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        savedDir.toFile().deleteRecursively()
    }

    @Benchmark
    fun save() {
        val dir = Files.createTempDirectory("annotation-bench-save")
        try {
            GraphStore.save(graph, dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Benchmark
    fun load(): Graph {
        return GraphStore.load(savedDir)
    }
}
