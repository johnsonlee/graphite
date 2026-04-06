package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "nodes", description = ["Query nodes by type"])
class NodesCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Option(names = ["--type", "-t"], description = ["Node type: CallSiteNode, ConstantNode, FieldNode, ParameterNode, ReturnNode, LocalVariable"])
    var nodeType: String? = null

    @Option(names = ["--limit", "-l"], description = ["Max results"], defaultValue = "20")
    var limit: Int = 20

    @Option(names = ["--format", "-f"], description = ["Output format: text, json"], defaultValue = "text")
    var format: String = "text"

    override fun call(): Int {
        val graph = GraphStore.load(graphDir)
        val nodeClass = resolveNodeType(nodeType)
        val nodes = graph.nodes(nodeClass).take(limit).toList()

        if (format == "json") {
            println(GsonBuilder().setPrettyPrinting().create().toJson(nodes.map { nodeToMap(it) }))
        } else {
            nodes.forEach { println(formatNode(it)) }
            println("\n${nodes.size} node(s)")
        }
        return 0
    }
}
