package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.javalin.Javalin
import io.javalin.http.Context
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.core.NodeId
import java.io.IOException

internal fun ExploreRoutes.registerStaticGraphRoutes(app: Javalin, stats: GraphStats) {
    val descriptor = mapOf(API_FIELD_ID to STANDALONE_GRAPH_ID) + stats.toApiMap()
    app.get("$API_ROOT/graphs") { ctx ->
        ctx.json(mapOf(API_FIELD_COUNT to 1, "totals" to stats.toApiMap(), API_FIELD_GRAPHS to listOf(descriptor)))
    }
    app.get(API_GRAPH_PATH) { ctx ->
        val id = ctx.pathParam(API_FIELD_GRAPH_ID)
        runCatching { GraphRegistry.validateGraphId(id) }
            .onSuccess { cleanId ->
                if (cleanId == STANDALONE_GRAPH_ID) {
                    ctx.json(mapOf("graph" to descriptor))
                } else {
                    ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to graphNotLoadedMessage(id)))
                }
            }
            .onFailure { error -> ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message)) }
    }
}

internal fun ExploreRoutes.registerRegistryRoutes(app: Javalin, registry: GraphRegistry, topology: TopologyService) {
    app.get("$API_ROOT/graphs") { ctx ->
        val descriptors = registry.list()
        val totals = descriptors.fold(GraphStats.EMPTY) { total, descriptor -> total + descriptor.stats }
        ctx.json(
            mapOf(
                API_FIELD_DATA to registry.dataDir.toString(),
                API_FIELD_LOAD_MODE to registry.defaultLoadMode.name,
                API_FIELD_COUNT to descriptors.size,
                "totals" to totals.toApiMap(),
                API_FIELD_GRAPHS to descriptors.map { it.toApiMap() }))
    }

    app.get(API_GRAPH_PATH) { ctx ->
        val id = ctx.pathParam(API_FIELD_GRAPH_ID)
        runCatching { registry.describe(id) }
            .onSuccess { descriptor ->
                if (descriptor == null) {
                    ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to graphNotLoadedMessage(id)))
                } else {
                    ctx.json(mapOf("graph" to descriptor.toApiMap()))
                }
            }
            .onFailure { error -> ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message)) }
    }

    app.get("$API_ROOT/cypher/graphs") { ctx -> queryLoadedGraphsFromRequest(ctx, registry) }

    app.post("$API_ROOT/cypher/graphs") { ctx -> queryLoadedGraphsFromRequest(ctx, registry) }

    app.put(API_GRAPH_PATH) { ctx -> loadGraphFromRequest(ctx, registry, topology) }

    app.post(API_GRAPH_PATH) { ctx -> loadGraphFromRequest(ctx, registry, topology) }

    app.delete(API_GRAPH_PATH) { ctx ->
        val id = ctx.pathParam(API_FIELD_GRAPH_ID)
        runCatching { registry.unload(id) { topology.rebuild() } }
            .onSuccess { removed ->
                if (removed) {
                    ctx.status(HTTP_NO_CONTENT)
                } else {
                    ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to graphNotLoadedMessage(id)))
                }
            }
            .onFailure { error -> ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message)) }
    }
}

internal fun ExploreRoutes.registerAllGraphRoutes(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    registerAllAnnotationsGet(app, acquire)
    registerAllResourcesGet(app, acquire)
    registerAllResourcesPathGet(app, acquire)
    registerAllEndpointsGet(app, acquire)
    registerAllArchitectureC4Get(app, acquire)
    registerAllOverviewGet(app, acquire)
    registerAllCypherPost(app, acquire)
    registerAllCypherGet(app, acquire)
}

private fun ExploreRoutes.registerAllAnnotationsGet(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.get("$API_ROOT/annotations") { ctx ->
        val className = requiredQueryParam(ctx, API_PARAM_CLASS) ?: return@get
        val memberName = requiredQueryParam(ctx, API_PARAM_MEMBER) ?: return@get
        withAllGraphs(ctx, acquire) { leases -> respondGrouped(ctx, leases) { graph -> graph.memberAnnotations(className, memberName) } }
    }
}

private fun ExploreRoutes.registerAllResourcesGet(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.get("$API_ROOT/resources") { ctx ->
        val pattern = ctx.queryParam(API_PARAM_PATTERN) ?: "**"
        val limit = boundedLimit(ctx, DEFAULT_RESOURCE_LIMIT, MAX_RESOURCE_LIMIT)
        withAllGraphs(ctx, acquire) { leases ->
            if (!requireResourceStores(ctx, leases)) return@withAllGraphs
            val limits = distributedLimits(leases.size, limit)
            val results =
                leases.mapIndexed { index, lease ->
                    val graphLimit = limits[index]
                    val resources = listResources(lease.graph, pattern, graphLimit)
                    grouped(
                        lease.id,
                        mapOf(
                            API_FIELD_PATTERN to pattern,
                            API_PARAM_LIMIT to graphLimit,
                            API_FIELD_COUNT to resources.size,
                            API_FIELD_RESOURCES to resources))
                }
            ctx.json(groupedEnvelope(leases.size, results))
        }
    }
}

private fun ExploreRoutes.registerAllResourcesPathGet(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.get("$API_ROOT/resources/<path>") { ctx ->
        val path = ctx.pathParam(API_FIELD_PATH).trimStart('/')
        if (path.isBlank()) {
            ctx.status(HTTP_NOT_FOUND).result(API_ERROR_RESOURCE_NOT_FOUND)
            return@get
        }
        withAllGraphs(ctx, acquire) { leases ->
            if (!requireResourceStores(ctx, leases)) return@withAllGraphs
            try {
                var remainingBytes = MAX_RESOURCE_BYTES
                val results =
                    leases.mapNotNull { lease ->
                        readResource(lease.graph, path, remainingBytes)?.let { resource ->
                            remainingBytes -= resource[API_FIELD_SIZE] as Int
                            grouped(lease.id, resource)
                        }
                    }
                if (results.isEmpty()) {
                    ctx.status(HTTP_NOT_FOUND).result("$API_ERROR_RESOURCE_NOT_FOUND: $path")
                } else {
                    ctx.json(groupedEnvelope(leases.size, results))
                }
            } catch (_: ResourceTooLargeException) {
                ctx.status(HTTP_PAYLOAD_TOO_LARGE).result("$API_ERROR_RESOURCE_TOO_LARGE: $path")
            }
        }
    }
}

private fun ExploreRoutes.registerAllEndpointsGet(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.get("$API_ROOT/endpoints") { ctx ->
        val limit = boundedLimit(ctx, DEFAULT_ENDPOINT_LIMIT, MAX_ENDPOINT_LIMIT)
        val classPattern = ctx.queryParam(API_PARAM_CLASS)
        withAllGraphs(ctx, acquire) { leases ->
            respondGroupedLimited(ctx, leases, limit) { lease, graphLimit ->
                val endpoints =
                    endpointExtractor
                        .extract(lease.graph)
                        .asSequence()
                        .filter { classPattern == null || it[API_FIELD_CLASS] == classPattern }
                        .take(graphLimit)
                        .toList()
                mapOf("framework" to "spring-web", API_FIELD_COUNT to endpoints.size, "endpoints" to endpoints)
            }
        }
    }
}

private fun ExploreRoutes.registerAllArchitectureC4Get(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.get("$API_ROOT/architecture/c4") { ctx -> registerAllC4Response(ctx, acquire) }
}

private fun ExploreRoutes.registerAllOverviewGet(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.get("$API_ROOT/overview") { ctx ->
        val limit = boundedLimit(ctx, DEFAULT_OVERVIEW_LIMIT, MAX_OVERVIEW_LIMIT)
        withAllGraphs(ctx, acquire) { leases ->
            respondGroupedLimited(ctx, leases, limit) { lease, graphLimit -> buildClassOverview(lease.graph, graphLimit) }
        }
    }
}

private fun ExploreRoutes.registerAllCypherPost(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.post("$API_ROOT/cypher") { ctx -> queryAllGraphs(ctx, acquire) }
}

private fun ExploreRoutes.registerAllCypherGet(app: Javalin, acquire: (Context) -> List<GraphLease>) {
    app.get("$API_ROOT/cypher") { ctx -> queryAllGraphs(ctx, acquire) }
}

internal fun ExploreRoutes.registerGraphLocalIdRoutes(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/node/{id}") { ctx ->
        withGraph(ctx, provider) { graph ->
            val id =
                ctx.pathParam(API_FIELD_ID).toIntOrNull()
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_INVALID_NODE_ID)
                        return@withGraph
                    }
            val node =
                graph.node(NodeId(id))
                    ?: run {
                        ctx.status(HTTP_NOT_FOUND).result(API_ERROR_NODE_NOT_FOUND)
                        return@withGraph
                    }
            ctx.json(nodeToMap(node, graph))
        }
    }

    app.get("$prefix/node/{id}/outgoing") { ctx ->
        withGraph(ctx, provider) { graph ->
            val id =
                ctx.pathParam(API_FIELD_ID).toIntOrNull()
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_INVALID_NODE_ID)
                        return@withGraph
                    }
            val limit = boundedLimit(ctx, DEFAULT_EDGE_LIMIT, MAX_EDGE_LIMIT)
            val edges = graph.outgoing(NodeId(id)).take(limit).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }
    }

    app.get("$prefix/node/{id}/incoming") { ctx ->
        withGraph(ctx, provider) { graph ->
            val id =
                ctx.pathParam(API_FIELD_ID).toIntOrNull()
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_INVALID_NODE_ID)
                        return@withGraph
                    }
            val limit = boundedLimit(ctx, DEFAULT_EDGE_LIMIT, MAX_EDGE_LIMIT)
            val edges = graph.incoming(NodeId(id)).take(limit).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }
    }

    app.get("$prefix/subgraph") { ctx ->
        withGraph(ctx, provider) { graph ->
            val centerId =
                ctx.queryParam(API_PARAM_CENTER)?.toIntOrNull()
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result("Missing 'center' parameter")
                        return@withGraph
                    }
            val depth = boundedDepth(ctx.queryParam(API_PARAM_DEPTH))
            val direction =
                resolveSubgraphDirection(ctx.queryParam(API_PARAM_DIRECTION))
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result("Invalid 'direction' parameter")
                        return@withGraph
                    }
            ctx.json(buildSubgraph(graph, NodeId(centerId), depth, direction))
        }
    }
}

internal fun ExploreRoutes.registerGraphRoutes(app: Javalin, prefix: String, provider: GraphProvider) {
    registerGraphAnnotationsGet(app, prefix, provider)
    registerGraphResourcesGet(app, prefix, provider)
    registerGraphResourcesPathGet(app, prefix, provider)
    registerGraphEndpointsGet(app, prefix, provider)
    registerGraphArchitectureC4Get(app, prefix, provider)
    registerGraphOverviewGet(app, prefix, provider)
    registerGraphCypherPost(app, prefix, provider)
    registerGraphCypherGet(app, prefix, provider)
}

private fun ExploreRoutes.registerGraphAnnotationsGet(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/annotations") { ctx ->
        withGraph(ctx, provider) { graph ->
            val className =
                ctx.queryParam(API_PARAM_CLASS)
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result("Missing 'class' parameter")
                        return@withGraph
                    }
            val memberName =
                ctx.queryParam(API_PARAM_MEMBER)
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result("Missing 'member' parameter")
                        return@withGraph
                    }
            ctx.json(graph.memberAnnotations(className, memberName))
        }
    }
}

private fun ExploreRoutes.registerGraphResourcesGet(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/resources") { ctx ->
        withGraph(ctx, provider) { graph ->
            if (!requireResourceStore(ctx, graph)) return@withGraph
            val pattern = ctx.queryParam(API_PARAM_PATTERN) ?: "**"
            val limit = boundedLimit(ctx, DEFAULT_RESOURCE_LIMIT, MAX_RESOURCE_LIMIT)
            val resources = listResources(graph, pattern, limit)
            ctx.json(
                mapOf(
                    API_FIELD_PATTERN to pattern,
                    API_PARAM_LIMIT to limit,
                    API_FIELD_COUNT to resources.size,
                    API_FIELD_RESOURCES to resources))
        }
    }
}

private fun ExploreRoutes.registerGraphResourcesPathGet(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/resources/<path>") { ctx ->
        withGraph(ctx, provider) { graph ->
            if (!requireResourceStore(ctx, graph)) return@withGraph
            val path = ctx.pathParam("path").trimStart('/')
            if (path.isBlank()) {
                ctx.status(HTTP_NOT_FOUND).result(API_ERROR_RESOURCE_NOT_FOUND)
                return@withGraph
            }
            try {
                val entry = resolveResourceEntry(graph, path)
                val bytes = readBoundedResource(graph, path)
                ctx.json(
                    mapOf(
                        API_FIELD_PATH to path,
                        API_FIELD_SOURCE to entry?.source,
                        API_FIELD_DERIVED to false,
                        API_FIELD_SIZE to bytes.size,
                        API_FIELD_CONTENT to bytes.toString(Charsets.UTF_8)))
            } catch (_: ResourceTooLargeException) {
                ctx.status(HTTP_PAYLOAD_TOO_LARGE).result("$API_ERROR_RESOURCE_TOO_LARGE: $path")
            } catch (_: IOException) {
                ctx.status(HTTP_NOT_FOUND).result("$API_ERROR_RESOURCE_NOT_FOUND: $path")
            }
        }
    }
}

private fun ExploreRoutes.registerGraphEndpointsGet(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/endpoints") { ctx ->
        withGraph(ctx, provider) { graph ->
            val limit = boundedLimit(ctx, DEFAULT_ENDPOINT_LIMIT, MAX_ENDPOINT_LIMIT)
            val classPattern = ctx.queryParam(API_PARAM_CLASS)
            val endpoints =
                endpointExtractor
                    .extract(graph)
                    .asSequence()
                    .filter { classPattern == null || it[API_FIELD_CLASS] == classPattern }
                    .take(limit)
                    .toList()
            ctx.json(mapOf("framework" to "spring-web", API_FIELD_COUNT to endpoints.size, "endpoints" to endpoints))
        }
    }
}

private fun ExploreRoutes.registerGraphArchitectureC4Get(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/architecture/c4") { ctx ->
        withGraph(ctx, provider) { graph ->
            val level = ctx.queryParam(API_PARAM_LEVEL) ?: "all"
            val format = resolveC4ResponseFormat(ctx.header("Accept"), ctx.queryParam(API_PARAM_FORMAT))
            if (level !in C4ArchitectureService.LEVELS) {
                ctx.status(HTTP_BAD_REQUEST)
                    .json(mapOf(API_FIELD_ERROR to "Invalid 'level' parameter", "allowed" to C4ArchitectureService.LEVELS))
                return@withGraph
            }
            if (format !in C4ArchitectureService.FORMATS) {
                ctx.status(HTTP_BAD_REQUEST)
                    .json(mapOf(API_FIELD_ERROR to "Invalid 'format' parameter", "allowed" to C4ArchitectureService.FORMATS))
                return@withGraph
            }
            val model = c4.buildModel(graph, level)
            when (format) {
                "json" -> ctx.contentType("application/vnd.structurizr+json; charset=utf-8").json(model)
                "mermaid" -> ctx.contentType("text/vnd.mermaid; charset=utf-8").result(c4.renderMermaid(model))
                "plantuml" -> ctx.contentType("text/vnd.plantuml; charset=utf-8").result(c4.renderPlantUml(model))
                "dsl" -> ctx.contentType("text/vnd.structurizr.dsl; charset=utf-8").result(c4.renderStructurizrDsl(model))
            }
        }
    }
}

private fun ExploreRoutes.registerGraphOverviewGet(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/overview") { ctx ->
        withGraph(ctx, provider) { graph ->
            val limit = boundedLimit(ctx, DEFAULT_OVERVIEW_LIMIT, MAX_OVERVIEW_LIMIT)
            ctx.json(buildClassOverview(graph, limit))
        }
    }
}

private fun ExploreRoutes.registerGraphCypherPost(app: Javalin, prefix: String, provider: GraphProvider) {
    app.post("$prefix/cypher") { ctx ->
        val body = ctx.body()
        val query =
            try {
                JsonParser.parseString(body).asJsonObject.get(API_PARAM_QUERY).asString
            } catch (_: Exception) {
                ctx.queryParam(API_PARAM_QUERY)
                    ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_MISSING_QUERY)
                        return@post
                    }
            }
        executeSingleGraphCypher(ctx, provider, query)
    }
}

private fun ExploreRoutes.registerGraphCypherGet(app: Javalin, prefix: String, provider: GraphProvider) {
    app.get("$prefix/cypher") { ctx ->
        val query =
            ctx.queryParam(API_PARAM_QUERY)
                ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_MISSING_QUERY)
                    return@get
                }
        executeSingleGraphCypher(ctx, provider, query)
    }
}

private fun graphNotLoadedMessage(id: String): String = "Graph not loaded: $id"
