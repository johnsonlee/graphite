package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.c4.C4Level
import io.johnsonlee.graphite.c4.C4ModelBuilder
import io.johnsonlee.graphite.c4.C4Options
import io.johnsonlee.graphite.c4.render.JsonRenderer
import io.johnsonlee.graphite.c4.render.MermaidRenderer
import io.johnsonlee.graphite.c4.render.PlantUmlRenderer
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.nameWithoutExtension

@Command(name = "c4", description = ["Render a C4 architecture diagram from a saved graph"])
class C4Command : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Option(names = ["--level"], description = ["C4 viewpoint: context, container, component"], defaultValue = "component")
    var level: String = "component"

    @Option(names = ["--format", "-f"], description = ["Output format: plantuml, mermaid, json"], defaultValue = "plantuml")
    var format: String = "plantuml"

    @Option(names = ["-o", "--output"], description = ["Output file path; stdout when omitted"])
    var output: Path? = null

    @Option(names = ["--include"], description = ["Package prefixes to include (comma-separated)"], split = ",")
    var includePackages: List<String> = emptyList()

    @Option(names = ["--exclude"], description = ["Package prefixes to exclude (comma-separated)"], split = ",")
    var excludePackages: List<String> = emptyList()

    @Option(names = ["--group"], description = ["Grouping strategy: spring, package"], defaultValue = "spring")
    var group: String = "spring"

    @Option(names = ["--group-depth"], description = ["Package depth used for the package grouping fallback"], defaultValue = "3")
    var groupDepth: Int = 3

    @Option(names = ["--system-name"], description = ["System name shown in the diagram"])
    var systemName: String? = null

    @Option(names = ["-v", "--verbose"], description = ["Enable verbose output"])
    var verbose: Boolean = false

    override fun call(): Int {
        if (!Files.isDirectory(graphDir)) {
            System.err.println("Error: not a directory: $graphDir")
            return 1
        }

        val parsedLevel = parseLevel(level) ?: run {
            System.err.println("Error: unknown level: $level (expected context, container, component)")
            return 1
        }
        val parsedFormat = format.lowercase()
        if (parsedFormat !in setOf("plantuml", "mermaid", "json")) {
            System.err.println("Error: unknown format: $format (expected plantuml, mermaid, json)")
            return 1
        }

        try {
            if (verbose) System.err.println("Loading graph from $graphDir")
            val graph = GraphStore.load(graphDir)

            val options = C4Options(
                systemName = systemName ?: graphDir.nameWithoutExtension.ifEmpty { "Application" },
                level = parsedLevel,
                include = includePackages,
                exclude = excludePackages,
                groupByPackage = group.equals("package", ignoreCase = true),
                groupDepth = groupDepth
            )

            val model = C4ModelBuilder.build(graph, options)
            val rendered = when (parsedFormat) {
                "mermaid" -> MermaidRenderer.render(model)
                "json" -> JsonRenderer.render(model)
                else -> PlantUmlRenderer.render(model)
            }

            val out = output
            if (out != null) {
                Files.writeString(out, rendered)
                if (verbose) System.err.println("Wrote ${rendered.length} bytes to $out")
            } else {
                println(rendered)
            }
            return 0
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) e.printStackTrace(System.err)
            return 1
        }
    }

    private fun parseLevel(value: String): C4Level? = when (value.lowercase()) {
        "context" -> C4Level.CONTEXT
        "container" -> C4Level.CONTAINER
        "component" -> C4Level.COMPONENT
        else -> null
    }
}
