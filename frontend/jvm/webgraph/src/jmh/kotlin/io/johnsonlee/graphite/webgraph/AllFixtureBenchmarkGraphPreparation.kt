package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds each eager source graph in sequence and closes it before building the
 * next one. Keeping this in a query-independent source file lets CI cache the
 * persisted graphs across benchmark query and gate changes.
 */
internal object AllFixtureBenchmarkGraphPreparation {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: AllFixtureBenchmarkGraphPreparation <corpus-id> <output-directory>" }
        val kind = BenchmarkCorpusKind.entries.single { it.id == args[0] }
        prepare(kind, Path.of(args[1]).toAbsolutePath().normalize())
    }

    private fun prepare(kind: BenchmarkCorpusKind, output: Path) {
        require(Files.notExists(output)) { "Fixture graph output already exists: $output" }
        val graph = JavaProjectLoader(
            LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )
        ).load(BenchmarkCorpus.resolveJar(kind))
        try {
            GraphStore.save(graph, output)
            check(graph.nodes(Node::class.java).count().toLong() == kind.expectedNodeCount)
        } finally {
            (graph as? Closeable)?.close()
        }
    }
}
