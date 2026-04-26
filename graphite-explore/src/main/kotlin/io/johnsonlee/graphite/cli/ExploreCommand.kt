package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.cli.c4.C4ViewLimits
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "graphite-explore",
    description = ["Interactive web visualization for saved Graphite graphs"],
    mixinStandardHelpOptions = true
)
class ExploreCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Option(names = ["--port", "-p"], description = ["HTTP port"], defaultValue = "8080")
    var port: Int = 8080

    private val gson = GsonBuilder().setPrettyPrinting().create()

    override fun call(): Int {
        val graph = GraphStore.load(graphDir)
        System.err.println("Loaded graph from $graphDir")

        val app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(gson))
            config.staticFiles.add("/web")
        }.start(port)

        registerApiRoutes(app, graph)

        System.err.println("Web UI: http://localhost:$port")
        System.err.println("Press Ctrl+C to stop")

        Thread.currentThread().join()
        return 0
    }

    internal fun registerApiRoutes(app: Javalin, graph: Graph) {
        ExploreRoutes().register(app, graph)
    }

    internal fun buildSubgraph(graph: Graph, center: NodeId, depth: Int): Map<String, Any> =
        ExploreRoutes().buildSubgraph(graph, center, depth)

    internal fun extractApiSpec(graph: Graph): List<Map<String, Any?>> =
        ApiSpecExtractor().extract(graph)

    internal fun buildOpenApiSpec(): Map<String, Any?> =
        OpenApiSpecBuilder().build()

    internal fun buildC4Model(
        graph: Graph,
        level: String,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS
    ): Map<String, Any?> =
        C4ArchitectureService().buildModel(graph, level, limit)
}
