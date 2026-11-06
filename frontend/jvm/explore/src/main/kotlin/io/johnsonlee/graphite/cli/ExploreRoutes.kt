package io.johnsonlee.graphite.cli

import io.javalin.Javalin
import io.javalin.http.Context
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.graph.Graph

internal class ExploreRoutes(
    internal val cypherGuard: CypherQueryGuard = CypherQueryGuard(),
    internal val cancellationSignalFactory: () -> CypherCancellationSignal = ::CypherCancellationSignal,
    internal val cypherResponseSerializer: CypherResponseSerializer = GsonCypherResponseSerializer(),
    internal val clientCancellationObserver: (Context, CypherCancellationSignal) -> CypherClientCancellation =
        CypherClientCancellation::observe
) {

    internal val endpointExtractor = EndpointExtractor()
    internal val openApiSpecBuilder = OpenApiSpecBuilder()
    internal val c4 = C4ArchitectureService()

    internal fun register(app: Javalin, graph: Graph) {
        registerCypherLifecycle(app)
        val provider = StaticGraphProvider(STANDALONE_GRAPH_ID, graph)
        val stats = graphStats(graph)
        val topology = TopologyGraph.nodesOnly(mapOf(STANDALONE_GRAPH_ID to stats))
        registerStaticGraphRoutes(app, stats)
        app.get("$API_ROOT/topology") { ctx -> ctx.json(topology.toApiMap(listOf(STANDALONE_GRAPH_ID))) }
        registerGraphRoutes(app, API_ROOT, provider)
        val scopedPrefix = API_GRAPH_PATH
        registerGraphRoutes(app, scopedPrefix, provider)
        registerGraphLocalIdRoutes(app, scopedPrefix, provider)
        registerSpecRoutes(app)
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun register(app: Javalin, registry: GraphRegistry, topology: TopologyService) {
        registerCypherLifecycle(app)
        registerRegistryRoutes(app, registry, topology)
        app.get("$API_ROOT/topology") { ctx ->
            val mapped = topology.openApiStream()
            if (mapped == null) {
                ctx.json(topology.toApiMap())
            } else {
                try {
                    ctx.contentType("application/json; charset=utf-8")
                        .header("Content-Length", mapped.contentLength.toString())
                        .result(mapped.input)
                } catch (error: RuntimeException) {
                    mapped.input.close()
                    throw error
                }
            }
        }
        registerAllGraphRoutes(app) { registry.acquireAll() }
        val scopedPrefix = API_GRAPH_PATH
        val provider = RegistryPathGraphProvider(registry)
        registerGraphRoutes(app, scopedPrefix, provider)
        registerGraphLocalIdRoutes(app, scopedPrefix, provider)
        registerSpecRoutes(app)
    }
}
