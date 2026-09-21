package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.ir.IrFormatException
import io.johnsonlee.graphite.ir.IrReader
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * Persist a graph a language frontend handed over as a Graph IR stream
 * (`ir/graphite_ir.proto`), with the writer `build` uses, so `graphite serve` serves it
 * like any other graph.
 */
@Command(name = "import", description = ["Import a Graph IR written by a language frontend and save it to disk"])
class ImportCommand : Callable<Int> {
    @Parameters(index = "0", description = ["The .graphite-ir file a frontend wrote"])
    lateinit var input: Path

    @Option(names = ["-o", "--output"], description = ["Output directory for saved graph"], required = true)
    lateinit var output: Path

    @Option(names = ["-v", "--verbose"], description = ["Enable verbose output"])
    var verbose: Boolean = false

    override fun call(): Int {
        if (!Files.isRegularFile(input)) {
            System.err.println("Error: Input is not a file: $input")
            return 1
        }
        return try {
            System.err.println("Reading IR from: $input")
            val (graph, summary) = IrReader().read(input)
            val frontend = summary.header.frontend
            System.err.println(
                "IR from ${frontend.name} ${frontend.version} (${summary.header.language}): " +
                    "${summary.nodeCount} nodes, ${summary.edgeCount} edges, ${summary.stringCount} strings"
            )
            System.err.println("Saving to: $output")
            GraphStore.save(graph, output, prepareCallSiteStringIndex = true)
            System.err.println("Done.")
            0
        } catch (e: IrFormatException) {
            System.err.println("Error: ${e.message}")
            1
        } catch (e: java.io.IOException) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace(System.err)
            1
        }
    }
}
