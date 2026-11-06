package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Path
import java.util.concurrent.Callable
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters

@Command(name = "query", description = ["Execute a Cypher query against a saved graph"])
class QueryCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"]) lateinit var graphDir: Path

    @Parameters(index = "1", description = ["Cypher query string"]) lateinit var query: String

    @Option(names = ["--format", "-f"], description = ["Output format: text, json, csv"]) var format: String = "text"

    @Option(names = ["-v", "--verbose"], description = ["Enable verbose output"]) var verbose: Boolean = false

    private val gson = GsonBuilder().setPrettyPrinting().create()

    override fun call(): Int =
        runCliCommand(verbose) {
            if (!java.nio.file.Files.isDirectory(graphDir)) {
                commandError("Not a directory: $graphDir")
            } else {
                executeQuery()
            }
        }

    private fun executeQuery(): Int {
        if (verbose) System.err.println("Loading graph from $graphDir...")
        val graph = GraphStore.load(graphDir)
        if (verbose) System.err.println("Executing: $query")
        val result = CypherExecutor(graph).execute(query)
        QueryResultPrinter(gson).print(result, format)
        return 0
    }
}
