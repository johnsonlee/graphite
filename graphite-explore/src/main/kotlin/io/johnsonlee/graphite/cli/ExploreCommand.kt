package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.cli.c4.C4ViewLimits
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

private const val DEFAULT_PORT = 8080
private const val DEFAULT_PORT_TEXT = "8080"
private const val DEFAULT_LOAD_MODE_TEXT = "MAPPED"
private const val DEFAULT_MAX_CONCURRENT_CYPHER_TEXT = "2"
private const val DEFAULT_CYPHER_WORK_BUDGET_TEXT = "250000"

@Command(
    name = "serve",
    description = ["Serve one or more saved Graphite webgraphs over HTTP"],
    mixinStandardHelpOptions = true
)
open class ServeCommand : Callable<Int> {

    @Parameters(index = "0", arity = "0..1", description = ["Optional saved graph directory for single-graph startup"])
    var graphDir: Path? = null

    @Option(names = ["--data"], description = ["Data directory used to resolve relative graph paths and allow empty startup"])
    var data: Path? = null

    @Option(names = ["--graph"], description = ["Initial graph mapping id:path. Repeat for multiple graphs."])
    var graphSpecs: List<String> = emptyList()

    @Option(names = ["--id"], description = ["Required graph id for the optional positional graph"])
    var graphId: String? = null

    @Option(names = ["--port", "-p"], description = ["HTTP port"], defaultValue = DEFAULT_PORT_TEXT)
    var port: Int = DEFAULT_PORT

    @Option(
        names = ["--load-mode"],
        description = [
            "Graph load mode: \${COMPLETION-CANDIDATES}. Defaults to MAPPED for multi-graph heap stability."
        ],
        defaultValue = DEFAULT_LOAD_MODE_TEXT
    )
    var loadMode: GraphStore.LoadMode = GraphStore.LoadMode.MAPPED

    @Option(
        names = ["--topology"],
        description = [
            "Cypher file or directory used at startup to derive graph-to-graph calls. " +
                "Queries return source, target, and optional protocol, operation, weight, evidence."
        ]
    )
    var topology: Path? = null

    @Option(
        names = ["--max-concurrent-cypher"],
        description = ["Maximum number of Cypher queries executing at once"],
        defaultValue = DEFAULT_MAX_CONCURRENT_CYPHER_TEXT
    )
    var maxConcurrentCypher: Int = DEFAULT_MAX_CONCURRENT_CYPHER

    @Option(
        names = ["--cypher-work-budget"],
        description = ["Maximum graph candidate inspections per Cypher request"],
        defaultValue = DEFAULT_CYPHER_WORK_BUDGET_TEXT
    )
    var cypherWorkBudget: Long = DEFAULT_CYPHER_WORK_BUDGET

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun call(): Int {
        if (maxConcurrentCypher <= 0 || cypherWorkBudget <= 0) {
            System.err.println("Error: Cypher concurrency and work budget must be positive")
            return 1
        }
        val hasInitialGraphs = graphDir != null || graphSpecs.isNotEmpty()
        if (!hasInitialGraphs && data == null) {
            System.err.println("Error: --data is required when starting without an initial graph")
            return 1
        }

        val root = resolveDataDir(data, graphDir)
        Files.createDirectories(root)
        val registry = GraphRegistry(root, loadMode)

        val topologyService: TopologyService
        try {
            loadInitialGraphs(registry)
            topologyService = TopologyService(registry, TopologyQuerySource.load(topology))
            topologyService.rebuild()
        } catch (e: RuntimeException) {
            System.err.println("Error: ${e.message}")
            registry.close()
            return 1
        }

        var app: Javalin? = null
        try {
            app = Javalin.create { config ->
                config.jsonMapper(JavalinGson(gson))
                config.staticFiles.add("/web")
            }.start(port)

            registerApiRoutes(app, registry, topologyService)

            System.err.println("Web UI: http://localhost:${app.port()}")
            System.err.println("Data: $root")
            System.err.println("Loaded graphs: ${registry.list().joinToString { it.id }}")
            val topologySummary = topologyService.summary()
            System.err.println(
                "Topology: ${topologySummary.graphCount} graphs, " +
                    "${topologySummary.relationCount} relations"
            )
            System.err.println(
                "Cypher limits: $maxConcurrentCypher concurrent, $cypherWorkBudget work units per request"
            )
            System.err.println("Press Ctrl+C to stop")

            Thread.currentThread().join()
            return 0
        } finally {
            app?.stop()
            topologyService.close()
            registry.close()
        }
    }

    internal fun registerApiRoutes(app: Javalin, graph: Graph) {
        ExploreRoutes(CypherQueryGuard(maxConcurrentCypher, cypherWorkBudget)).register(app, graph)
    }

    internal fun registerApiRoutes(app: Javalin, registry: GraphRegistry, topology: TopologyService) {
        ExploreRoutes(CypherQueryGuard(maxConcurrentCypher, cypherWorkBudget)).register(app, registry, topology)
    }

    internal fun buildSubgraph(graph: Graph, center: NodeId, depth: Int): Map<String, Any> =
        ExploreRoutes().buildSubgraph(graph, center, depth)

    internal fun extractEndpoints(graph: Graph): List<Map<String, Any?>> =
        EndpointExtractor().extract(graph)

    internal fun buildOpenApiSpec(): Map<String, Any?> =
        OpenApiSpecBuilder().build()

    internal fun buildC4Model(
        graph: Graph,
        level: String,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS
    ): Map<String, Any?> =
        C4ArchitectureService().buildModel(graph, level, limit)

    private fun loadInitialGraphs(registry: GraphRegistry) {
        val ids = mutableSetOf<String>()
        graphDir?.let { dir ->
            val id = GraphRegistry.validateGraphId(
                requireNotNull(graphId) { "--id is required when a positional graph directory is provided" }
            )
            require(ids.add(id)) { "Duplicate initial graph id: $id" }
            val descriptor = registry.load(id, dir, loadMode)
            System.err.println("Loaded graph '${descriptor.id}' from ${descriptor.path} using ${descriptor.loadMode} mode")
        }

        for (spec in graphSpecs) {
            val (id, path) = parseGraphSpec(spec)
            require(ids.add(id)) { "Duplicate initial graph id: $id" }
            val descriptor = registry.load(id, path, loadMode)
            System.err.println("Loaded graph '${descriptor.id}' from ${descriptor.path} using ${descriptor.loadMode} mode")
        }
    }

    private fun parseGraphSpec(spec: String): Pair<String, Path> {
        val separator = spec.indexOf(':')
        require(separator > 0 && separator < spec.lastIndex) {
            "Invalid --graph '$spec'. Expected id:path."
        }
        val id = GraphRegistry.validateGraphId(spec.substring(0, separator))
        return id to Path.of(spec.substring(separator + 1))
    }

    private fun resolveDataDir(root: Path?, initialGraph: Path?): Path =
        (root ?: initialGraph?.toAbsolutePath()?.parent ?: Path.of(".")).toAbsolutePath().normalize()
}

@Command(
    name = "graphite-explore",
    description = ["Interactive web visualization for saved Graphite graphs"],
    mixinStandardHelpOptions = true
)
class ExploreCommand : ServeCommand()
