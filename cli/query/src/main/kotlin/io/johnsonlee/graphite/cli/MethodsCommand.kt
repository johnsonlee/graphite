package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "methods", description = ["Find methods matching pattern"])
class MethodsCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Option(names = ["--class", "-c"], description = ["Declaring class pattern"])
    var classPattern: String? = null

    @Option(names = ["--name", "-n"], description = ["Method name pattern"])
    var namePattern: String? = null

    @Option(names = ["--limit", "-l"], defaultValue = "50")
    var limit: Int = 50

    override fun call(): Int {
        val graph = GraphStore.load(graphDir)
        val pattern = MethodPattern(declaringClass = classPattern, name = namePattern)
        val methods = graph.methods(pattern).take(limit).toList()

        methods.forEach { m ->
            println("${m.declaringClass.className}.${m.name}(${m.parameterTypes.joinToString(", ") { it.className }}) -> ${m.returnType.className}")
        }
        println("\n${methods.size} method(s)")
        return 0
    }
}
