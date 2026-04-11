package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "query", description = ["Execute a Cypher query against a saved graph"])
class QueryCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Parameters(index = "1", description = ["Cypher query string"])
    lateinit var query: String

    @Option(names = ["--format", "-f"], description = ["Output format: text, json, csv"], defaultValue = "text")
    var format: String = "text"

    @Option(names = ["-v", "--verbose"], description = ["Enable verbose output"])
    var verbose: Boolean = false

    private val gson = GsonBuilder().setPrettyPrinting().create()

    override fun call(): Int {
        try {
            if (!java.nio.file.Files.isDirectory(graphDir) && !java.nio.file.Files.isRegularFile(graphDir)) {
                System.err.println("Error: Not a graph file or directory: $graphDir")
                return 1
            }

            if (verbose) System.err.println("Loading graph from $graphDir...")
            val graph = GraphStore.load(graphDir)

            if (verbose) System.err.println("Executing: $query")
            val executor = CypherExecutor(graph)
            val result = executor.execute(query)

            when (format.lowercase()) {
                "json" -> {
                    println(gson.toJson(mapOf(
                        "columns" to result.columns,
                        "rows" to result.rows,
                        "rowCount" to result.rows.size
                    )))
                }
                "csv" -> {
                    if (result.columns.isNotEmpty()) {
                        println(result.columns.joinToString(","))
                        result.rows.forEach { row ->
                            println(result.columns.joinToString(",") { col ->
                                val v = row[col]
                                if (v is String) "\"${v.replace("\"", "\"\"")}\"" else v?.toString() ?: ""
                            })
                        }
                    }
                }
                else -> {
                    // Text table format
                    if (result.columns.isEmpty()) {
                        println("(no results)")
                        return 0
                    }

                    // Calculate column widths
                    val widths = result.columns.map { col ->
                        val dataWidth = result.rows.maxOfOrNull { row -> (row[col]?.toString() ?: "null").length } ?: 0
                        maxOf(col.length, dataWidth, 4)
                    }

                    // Header
                    val header = result.columns.mapIndexed { i, col -> col.padEnd(widths[i]) }.joinToString(" | ")
                    println(header)
                    println(widths.joinToString("-+-") { "-".repeat(it) })

                    // Rows
                    result.rows.forEach { row ->
                        val line = result.columns.mapIndexed { i, col ->
                            (row[col]?.toString() ?: "null").padEnd(widths[i])
                        }.joinToString(" | ")
                        println(line)
                    }

                    println("\n${result.rows.size} row(s)")
                }
            }

            return 0
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace(System.err)
            return 1
        }
    }
}
