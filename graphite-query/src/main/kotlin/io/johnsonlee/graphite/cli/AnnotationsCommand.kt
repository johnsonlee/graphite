package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(name = "annotations", description = ["Query member annotations"])
class AnnotationsCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Option(names = ["--class", "-c"], description = ["Fully qualified class name"], required = true)
    lateinit var className: String

    @Option(names = ["--member", "-m"], description = ["Member name (field or method)"], required = true)
    lateinit var memberName: String

    override fun call(): Int {
        val graph = GraphStore.load(graphDir)
        val annotations = graph.memberAnnotations(className, memberName)

        if (annotations.isEmpty()) {
            println("No annotations found for $className.$memberName")
        } else {
            println("Annotations for $className.$memberName:")
            for ((fqn, values) in annotations) {
                println("  @$fqn")
                for ((key, value) in values) {
                    println("    $key = $value")
                }
            }
        }
        return 0
    }
}
