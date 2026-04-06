package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * JMH benchmarks for graph save/load via WebGraph.
 *
 * Requires a pre-built graph at `/tmp/es-graph`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1, jvmArgs = ["-Xmx8g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class GraphSaveBenchmark {

    private lateinit var esGraph: Graph
    private lateinit var saveDir: Path

    @Setup
    fun setup() {
        val esPath = Path.of("/tmp/es-graph")
        if (!Files.isDirectory(esPath)) {
            throw IllegalStateException("ES graph not found at /tmp/es-graph")
        }
        esGraph = GraphStore.load(esPath)
        saveDir = Files.createTempDirectory("bench-save")
    }

    @TearDown
    fun teardown() {
        saveDir.toFile().deleteRecursively()
    }

    @Benchmark
    fun saveElasticsearchGraph() {
        val dir = saveDir.resolve("es-${System.nanoTime()}")
        GraphStore.save(esGraph, dir)
    }

    @Benchmark
    fun loadElasticsearchGraph(): Graph {
        return GraphStore.load(Path.of("/tmp/es-graph"))
    }
}
