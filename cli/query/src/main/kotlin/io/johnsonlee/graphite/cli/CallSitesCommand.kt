package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "call-sites", description = ["Find call sites matching pattern"])
class CallSitesCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Option(names = ["--class", "-c"], description = ["Declaring class pattern (supports * wildcard)"])
    var classPattern: String? = null

    @Option(names = ["--method", "-m"], description = ["Method name pattern"])
    var methodPattern: String? = null

    @Option(names = ["--limit", "-l"], defaultValue = "50")
    var limit: Int = 50

    @Option(names = ["--format", "-f"], defaultValue = "text")
    var format: String = "text"

    override fun call(): Int {
        val graph = GraphStore.load(graphDir)
        val pattern = MethodPattern(declaringClass = classPattern, name = methodPattern)
        val callSites = graph.callSites(pattern).take(limit).toList()

        if (format == "json") {
            println(GsonBuilder().setPrettyPrinting().create().toJson(callSites.map { nodeToMap(it) }))
        } else {
            callSites.forEach { cs ->
                println("${cs.caller.declaringClass.className}.${cs.caller.name} -> ${cs.callee.declaringClass.className}.${cs.callee.name}")
            }
            println("\n${callSites.size} call site(s)")
        }
        return 0
    }
}
