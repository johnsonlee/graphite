package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// ============================================================================
//  FLAT vs COMPRESSED format comparison at 10M scale
//  Measures: save time, load time, disk size
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.filter='StorageFormat'
// ============================================================================

@State(Scope.Benchmark)
open class StorageFormatBenchmarkBase {

    @Param("10000000")
    var nodeCount: Int = 0

    protected lateinit var graph: Graph
    protected lateinit var flatPath: Path
    protected lateinit var compressedDir: Path

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

        // Pre-save both formats for load benchmarks
        flatPath = Files.createTempFile("graphite-bench-", ".dat")
        GraphStore.save(graph, flatPath, format = StorageFormat.FLAT)

        compressedDir = Files.createTempDirectory("graphite-bench-compressed")
        GraphStore.save(graph, compressedDir, format = StorageFormat.COMPRESSED)

        // Print disk sizes
        val flatSize = flatPath.toFile().length()
        val compressedSize = Files.walk(compressedDir).filter { Files.isRegularFile(it) }
            .mapToLong { Files.size(it) }.sum()
        System.err.println()
        System.err.println("=== Disk Size (10M nodes) ===")
        System.err.println("  FLAT:       ${flatSize / 1024 / 1024} MB ($flatSize bytes)")
        System.err.println("  COMPRESSED: ${compressedSize / 1024 / 1024} MB ($compressedSize bytes)")
        System.err.println()
    }

    @TearDown(Level.Trial)
    fun teardown() {
        flatPath.toFile().delete()
        compressedDir.toFile().deleteRecursively()
    }
}

/** FLAT vs COMPRESSED save — 4GB heap. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = ["-Xmx4g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class StorageFormatSave4g : StorageFormatBenchmarkBase() {

    @Benchmark
    fun save_flat() {
        val tmp = Files.createTempFile("bench-flat-", ".dat")
        try { GraphStore.save(graph, tmp, format = StorageFormat.FLAT) }
        finally { tmp.toFile().delete() }
    }

    @Benchmark
    fun save_compressed() {
        val tmp = Files.createTempDirectory("bench-comp-")
        try { GraphStore.save(graph, tmp, format = StorageFormat.COMPRESSED) }
        finally { tmp.toFile().deleteRecursively() }
    }
}

/** FLAT vs COMPRESSED save — 8GB heap. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = ["-Xmx8g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class StorageFormatSave8g : StorageFormatBenchmarkBase() {

    @Benchmark
    fun save_flat() {
        val tmp = Files.createTempFile("bench-flat-", ".dat")
        try { GraphStore.save(graph, tmp, format = StorageFormat.FLAT) }
        finally { tmp.toFile().delete() }
    }

    @Benchmark
    fun save_compressed() {
        val tmp = Files.createTempDirectory("bench-comp-")
        try { GraphStore.save(graph, tmp, format = StorageFormat.COMPRESSED) }
        finally { tmp.toFile().deleteRecursively() }
    }
}

/** FLAT vs COMPRESSED load — 4GB heap. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = ["-Xmx4g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class StorageFormatLoad4g : StorageFormatBenchmarkBase() {

    @Benchmark
    fun load_flat(): Graph = GraphStore.load(flatPath)

    @Benchmark
    fun load_compressed(): Graph = GraphStore.load(compressedDir)
}

/** FLAT vs COMPRESSED load — 8GB heap. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = ["-Xmx8g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class StorageFormatLoad8g : StorageFormatBenchmarkBase() {

    @Benchmark
    fun load_flat(): Graph = GraphStore.load(flatPath)

    @Benchmark
    fun load_compressed(): Graph = GraphStore.load(compressedDir)
}
