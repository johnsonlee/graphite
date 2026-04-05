package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "info", description = ["Show graph statistics"])
class InfoCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    override fun call(): Int {
        val graph = GraphStore.load(graphDir)

        val nodeCount = graph.nodes(Node::class.java).count()
        val callSiteCount = graph.nodes(CallSiteNode::class.java).count()
        val constantCount = graph.nodes(ConstantNode::class.java).count()
        val fieldCount = graph.nodes(FieldNode::class.java).count()
        val methodCount = graph.methods(MethodPattern()).count()

        var edgeCount = 0L
        graph.nodes(Node::class.java).forEach { node ->
            edgeCount += graph.outgoing(node.id).count()
        }

        println("Graph: $graphDir")
        println("  Nodes:      $nodeCount")
        println("    CallSites:  $callSiteCount")
        println("    Constants:  $constantCount")
        println("    Fields:     $fieldCount")
        println("  Edges:      $edgeCount")
        println("  Methods:    $methodCount")

        return 0
    }
}
