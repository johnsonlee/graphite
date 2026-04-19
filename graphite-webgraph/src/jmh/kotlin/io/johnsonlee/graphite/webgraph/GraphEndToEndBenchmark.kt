package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import org.openjdk.jmh.annotations.*
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * End-to-end benchmarks that cover the full production pipeline:
 * JAR -> build -> save -> load -> query.
 *
 * This benchmark exists because microbenchmarks alone have repeatedly
 * overstated wins that regressed the real pipeline.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1, jvmArgs = ["-Xmx4g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class GraphEndToEndBenchmark {

    @Benchmark
    fun elasticsearch_build_save_load_query(): Long {
        return runEndToEnd(CorpusKind.ELASTICSEARCH)
    }

    @Benchmark
    fun android_build_save_load_query(): Long {
        return runEndToEnd(CorpusKind.ANDROID)
    }

    private fun runEndToEnd(kind: CorpusKind): Long {
        val persistedDir = Files.createTempDirectory("graphite-${kind.id}-e2e")
        val sourceGraph = JavaProjectLoader(LoaderConfig(buildCallGraph = false)).load(BenchmarkCorpus.resolveJar(kind))

        try {
            GraphStore.save(sourceGraph, persistedDir)
        } finally {
            closeQuietly(sourceGraph)
        }

        val loadedGraph = GraphStore.loadMapped(persistedDir)
        return try {
            val result = loadedGraph.query("MATCH (n:CallSiteNode) RETURN count(*)")
            val count = result.rows.single().values.single() as Number
            count.toLong()
        } finally {
            closeQuietly(loadedGraph)
            persistedDir.toFile().deleteRecursively()
        }
    }

    private fun closeQuietly(graph: Graph) {
        runCatching { (graph as? Closeable)?.close() }
    }
}
