package io.johnsonlee.graphite.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.javalin.Javalin
import io.javalin.http.Context
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.graph.ClassDependency
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.ResourceEntry
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.IOException
import java.nio.file.Path

@Suppress("LargeClass", "StringLiteralDuplication", "TooManyFunctions")
internal class ExploreRoutes {

    private val endpointExtractor = EndpointExtractor()
    private val openApiSpecBuilder = OpenApiSpecBuilder()
    private val c4 = C4ArchitectureService()

    internal fun register(app: Javalin, graph: Graph) {
        val provider = StaticGraphProvider(STANDALONE_GRAPH_ID, graph)
        val stats = graphStats(graph)
        val topology = TopologyGraph.nodesOnly(mapOf(STANDALONE_GRAPH_ID to stats))
        registerStaticGraphRoutes(app, stats)
        app.get("$API_ROOT/topology") { ctx ->
            ctx.json(topology.toApiMap(listOf(STANDALONE_GRAPH_ID)))
        }
        registerGraphRoutes(app, API_ROOT, provider)
        val scopedPrefix = "$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}"
        registerGraphRoutes(app, scopedPrefix, provider)
        registerGraphLocalIdRoutes(app, scopedPrefix, provider)
        registerSpecRoutes(app)
    }

    internal fun register(app: Javalin, registry: GraphRegistry) {
        val topology = TopologyService(registry, emptyList())
        topology.rebuild()
        register(app, registry, topology)
    }

    internal fun register(app: Javalin, registry: GraphRegistry, topology: TopologyService) {
        registerRegistryRoutes(app, registry, topology)
        app.get("$API_ROOT/topology") { ctx -> ctx.json(topology.toApiMap()) }
        registerAllGraphRoutes(app) { registry.acquireAll() }
        val scopedPrefix = "$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}"
        val provider = RegistryPathGraphProvider(registry)
        registerGraphRoutes(app, scopedPrefix, provider)
        registerGraphLocalIdRoutes(app, scopedPrefix, provider)
        registerSpecRoutes(app)
    }

    private fun registerStaticGraphRoutes(app: Javalin, stats: GraphStats) {
        val descriptor = mapOf(API_FIELD_ID to STANDALONE_GRAPH_ID) + stats.toApiMap()
        app.get("$API_ROOT/graphs") { ctx ->
            ctx.json(
                mapOf(
                    API_FIELD_COUNT to 1,
                    "totals" to stats.toApiMap(),
                    API_FIELD_GRAPHS to listOf(descriptor)
                )
            )
        }
        app.get("$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}") { ctx ->
            val id = ctx.pathParam(API_FIELD_GRAPH_ID)
            runCatching { GraphRegistry.validateGraphId(id) }
                .onSuccess { cleanId ->
                    if (cleanId == STANDALONE_GRAPH_ID) {
                        ctx.json(mapOf("graph" to descriptor))
                    } else {
                        ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to "Graph not loaded: $id"))
                    }
                }
                .onFailure { error ->
                    ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message))
                }
        }
    }

    private fun registerRegistryRoutes(app: Javalin, registry: GraphRegistry, topology: TopologyService) {
        app.get("$API_ROOT/graphs") { ctx ->
            val descriptors = registry.list()
            val totals = descriptors.fold(GraphStats.EMPTY) { total, descriptor ->
                total + descriptor.stats
            }
            ctx.json(
                mapOf(
                    API_FIELD_DATA to registry.dataDir.toString(),
                    API_FIELD_LOAD_MODE to registry.defaultLoadMode.name,
                    API_FIELD_COUNT to descriptors.size,
                    "totals" to totals.toApiMap(),
                    API_FIELD_GRAPHS to descriptors.map { it.toApiMap() }
                )
            )
        }

        app.get("$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}") { ctx ->
            val id = ctx.pathParam(API_FIELD_GRAPH_ID)
            runCatching { registry.describe(id) }
                .onSuccess { descriptor ->
                    if (descriptor == null) {
                        ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to "Graph not loaded: $id"))
                    } else {
                        ctx.json(mapOf("graph" to descriptor.toApiMap()))
                    }
                }
                .onFailure { error ->
                    ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message))
                }
        }

        app.get("$API_ROOT/cypher/graphs") { ctx ->
            queryLoadedGraphsFromRequest(ctx, registry)
        }

        app.post("$API_ROOT/cypher/graphs") { ctx ->
            queryLoadedGraphsFromRequest(ctx, registry)
        }

        app.put("$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}") { ctx ->
            loadGraphFromRequest(ctx, registry, topology)
        }

        app.post("$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}") { ctx ->
            loadGraphFromRequest(ctx, registry, topology)
        }

        app.delete("$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}") { ctx ->
            val id = ctx.pathParam(API_FIELD_GRAPH_ID)
            runCatching { registry.unload(id) }
                .onSuccess { removed ->
                    if (removed) {
                        topology.rebuild()
                        ctx.status(HTTP_NO_CONTENT)
                    } else {
                        ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to "Graph not loaded: $id"))
                    }
                }
                .onFailure { error ->
                    ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message))
                }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun registerAllGraphRoutes(
        app: Javalin,
        acquire: (Context) -> List<GraphLease>
    ) {
        app.get("$API_ROOT/nodes") { ctx ->
            withAllGraphs(ctx, acquire) { leases ->
                val nodeClass = resolveNodeType(ctx.queryParam(API_PARAM_TYPE))
                val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
                respondGroupedLimited(ctx, leases, limit) { lease, graphLimit ->
                    lease.graph.nodes(nodeClass).take(graphLimit)
                        .map { qualifiedNodeToMap(lease.id, it) }
                        .toList()
                }
            }
        }

        app.get("$API_ROOT/call-sites") { ctx ->
            val pattern = MethodPattern(
                declaringClass = ctx.queryParam(API_PARAM_CLASS),
                name = ctx.queryParam(API_PARAM_METHOD)
            )
            val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
            withAllGraphs(ctx, acquire) { leases ->
                respondGroupedLimited(ctx, leases, limit) { lease, graphLimit ->
                    lease.graph.callSites(pattern).take(graphLimit)
                        .map { qualifiedNodeToMap(lease.id, it) }
                        .toList()
                }
            }
        }

        app.get("$API_ROOT/methods") { ctx ->
            val pattern = MethodPattern(
                declaringClass = ctx.queryParam(API_PARAM_CLASS),
                name = ctx.queryParam(API_PARAM_NAME)
            )
            val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
            withAllGraphs(ctx, acquire) { leases ->
                respondGroupedLimited(ctx, leases, limit) { lease, graphLimit ->
                    (lease.graph.methodSlice(pattern, graphLimit)
                        ?: lease.graph.methods(pattern).take(graphLimit).toList())
                        .map(::methodToMap)
                }
            }
        }

        app.get("$API_ROOT/annotations") { ctx ->
            val className = requiredQueryParam(ctx, API_PARAM_CLASS) ?: return@get
            val memberName = requiredQueryParam(ctx, API_PARAM_MEMBER) ?: return@get
            withAllGraphs(ctx, acquire) { leases ->
                respondGrouped(ctx, leases) { graph -> graph.memberAnnotations(className, memberName) }
            }
        }

        app.get("$API_ROOT/resources") { ctx ->
            val pattern = ctx.queryParam(API_PARAM_PATTERN) ?: "**"
            val limit = boundedLimit(ctx, DEFAULT_RESOURCE_LIMIT, MAX_RESOURCE_LIMIT)
            withAllGraphs(ctx, acquire) { leases ->
                val limits = distributedLimits(leases.size, limit)
                val results = leases.mapIndexed { index, lease ->
                    val graphLimit = limits[index]
                    val resources = listResources(lease.graph, pattern, graphLimit)
                    grouped(
                        lease.id,
                        mapOf(
                            API_FIELD_PATTERN to pattern,
                            API_PARAM_LIMIT to graphLimit,
                            API_FIELD_COUNT to resources.size,
                            API_FIELD_RESOURCES to resources
                        )
                    )
                }
                ctx.json(groupedEnvelope(leases.size, results))
            }
        }

        app.get("$API_ROOT/resources/<path>") { ctx ->
            val path = ctx.pathParam(API_FIELD_PATH).trimStart('/')
            if (path.isBlank()) {
                ctx.status(HTTP_NOT_FOUND).result(API_ERROR_RESOURCE_NOT_FOUND)
                return@get
            }
            withAllGraphs(ctx, acquire) { leases ->
                try {
                    var remainingBytes = MAX_RESOURCE_BYTES
                    val results = leases.mapNotNull { lease ->
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

        app.get("$API_ROOT/endpoints") { ctx ->
            val limit = boundedLimit(ctx, DEFAULT_ENDPOINT_LIMIT, MAX_ENDPOINT_LIMIT)
            val classPattern = ctx.queryParam(API_PARAM_CLASS)
            withAllGraphs(ctx, acquire) { leases ->
                respondGroupedLimited(ctx, leases, limit) { lease, graphLimit ->
                    val endpoints = endpointExtractor.extract(lease.graph).asSequence()
                        .filter { classPattern == null || it[API_FIELD_CLASS] == classPattern }
                        .take(graphLimit)
                        .toList()
                    mapOf("framework" to "spring-web", API_FIELD_COUNT to endpoints.size, "endpoints" to endpoints)
                }
            }
        }

        app.get("$API_ROOT/architecture/c4") { ctx ->
            registerAllC4Response(ctx, acquire)
        }

        app.get("$API_ROOT/overview") { ctx ->
            val limit = boundedLimit(ctx, DEFAULT_OVERVIEW_LIMIT, MAX_OVERVIEW_LIMIT)
            withAllGraphs(ctx, acquire) { leases ->
                respondGroupedLimited(ctx, leases, limit) { lease, graphLimit ->
                    buildClassOverview(lease.graph, graphLimit)
                }
            }
        }

        app.post("$API_ROOT/cypher") { ctx -> queryAllGraphs(ctx, acquire) }
        app.get("$API_ROOT/cypher") { ctx -> queryAllGraphs(ctx, acquire) }
    }

    private fun registerGraphLocalIdRoutes(app: Javalin, prefix: String, provider: GraphProvider) {
        app.get("$prefix/node/{id}") { ctx ->
            withGraph(ctx, provider) { graph ->
                val id = ctx.pathParam(API_FIELD_ID).toIntOrNull() ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_INVALID_NODE_ID)
                    return@withGraph
                }
                val node = graph.node(NodeId(id)) ?: run {
                    ctx.status(HTTP_NOT_FOUND).result(API_ERROR_NODE_NOT_FOUND)
                    return@withGraph
                }
                ctx.json(nodeToMap(node))
            }
        }

        app.get("$prefix/node/{id}/outgoing") { ctx ->
            withGraph(ctx, provider) { graph ->
                val id = ctx.pathParam(API_FIELD_ID).toIntOrNull() ?: run {
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
                val id = ctx.pathParam(API_FIELD_ID).toIntOrNull() ?: run {
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
                val centerId = ctx.queryParam(API_PARAM_CENTER)?.toIntOrNull() ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result("Missing 'center' parameter")
                    return@withGraph
                }
                val depth = boundedDepth(ctx.queryParam(API_PARAM_DEPTH))
                val direction = resolveSubgraphDirection(ctx.queryParam(API_PARAM_DIRECTION)) ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result("Invalid 'direction' parameter")
                    return@withGraph
                }
                ctx.json(buildSubgraph(graph, NodeId(centerId), depth, direction))
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun registerGraphRoutes(app: Javalin, prefix: String, provider: GraphProvider) {
        app.get("$prefix/nodes") { ctx ->
            withGraph(ctx, provider) { graph ->
                val type = ctx.queryParam(API_PARAM_TYPE)
                val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
                val nodeClass = resolveNodeType(type)
                val nodes = graph.nodes(nodeClass).take(limit).toList()
                ctx.json(nodes.map { nodeToMap(it) })
            }
        }

        app.get("$prefix/call-sites") { ctx ->
            withGraph(ctx, provider) { graph ->
                val classPattern = ctx.queryParam(API_PARAM_CLASS)
                val methodPattern = ctx.queryParam(API_PARAM_METHOD)
                val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
                val pattern = MethodPattern(declaringClass = classPattern, name = methodPattern)
                val callSites = graph.callSites(pattern).take(limit).toList()
                ctx.json(callSites.map { nodeToMap(it) })
            }
        }

        app.get("$prefix/methods") { ctx ->
            withGraph(ctx, provider) { graph ->
                val classPattern = ctx.queryParam(API_PARAM_CLASS)
                val namePattern = ctx.queryParam(API_PARAM_NAME)
                val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
                val pattern = MethodPattern(declaringClass = classPattern, name = namePattern)
                val methods = graph.methodSlice(pattern, limit) ?: graph.methods(pattern).take(limit).toList()
                ctx.json(
                    methods.map {
                        mapOf(
                            "signature" to it.signature,
                            API_FIELD_CLASS to it.declaringClass.className,
                            API_FIELD_NAME to it.name,
                            "returnType" to it.returnType.className
                        )
                    }
                )
            }
        }

        app.get("$prefix/annotations") { ctx ->
            withGraph(ctx, provider) { graph ->
                val className = ctx.queryParam(API_PARAM_CLASS) ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result("Missing 'class' parameter")
                    return@withGraph
                }
                val memberName = ctx.queryParam(API_PARAM_MEMBER) ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result("Missing 'member' parameter")
                    return@withGraph
                }
                ctx.json(graph.memberAnnotations(className, memberName))
            }
        }

        app.get("$prefix/resources") { ctx ->
            withGraph(ctx, provider) { graph ->
                val pattern = ctx.queryParam(API_PARAM_PATTERN) ?: "**"
                val limit = boundedLimit(ctx, DEFAULT_RESOURCE_LIMIT, MAX_RESOURCE_LIMIT)
                val resources = listResources(graph, pattern, limit)
                ctx.json(
                    mapOf(
                        API_FIELD_PATTERN to pattern,
                        API_PARAM_LIMIT to limit,
                        API_FIELD_COUNT to resources.size,
                        API_FIELD_RESOURCES to resources
                    )
                )
            }
        }

        app.get("$prefix/resources/<path>") { ctx ->
            withGraph(ctx, provider) { graph ->
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
                            API_FIELD_CONTENT to bytes.toString(Charsets.UTF_8)
                        )
                    )
                } catch (_: ResourceTooLargeException) {
                    ctx.status(HTTP_PAYLOAD_TOO_LARGE).result("$API_ERROR_RESOURCE_TOO_LARGE: $path")
                } catch (_: IOException) {
                    ctx.status(HTTP_NOT_FOUND).result("$API_ERROR_RESOURCE_NOT_FOUND: $path")
                }
            }
        }

        app.get("$prefix/endpoints") { ctx ->
            withGraph(ctx, provider) { graph ->
                val limit = boundedLimit(ctx, DEFAULT_ENDPOINT_LIMIT, MAX_ENDPOINT_LIMIT)
                val classPattern = ctx.queryParam(API_PARAM_CLASS)
                val endpoints = endpointExtractor.extract(graph)
                    .asSequence()
                    .filter { classPattern == null || it[API_FIELD_CLASS] == classPattern }
                    .take(limit)
                    .toList()
                ctx.json(
                    mapOf(
                        "framework" to "spring-web",
                        API_FIELD_COUNT to endpoints.size,
                        "endpoints" to endpoints
                    )
                )
            }
        }

        app.get("$prefix/architecture/c4") { ctx ->
            withGraph(ctx, provider) { graph ->
                val level = ctx.queryParam(API_PARAM_LEVEL) ?: "all"
                val format = resolveC4ResponseFormat(ctx.header("Accept"), ctx.queryParam(API_PARAM_FORMAT))
                if (level !in C4ArchitectureService.LEVELS) {
                    ctx.status(HTTP_BAD_REQUEST).json(
                        mapOf(
                            API_FIELD_ERROR to "Invalid 'level' parameter",
                            "allowed" to C4ArchitectureService.LEVELS
                        )
                    )
                    return@withGraph
                }
                if (format !in C4ArchitectureService.FORMATS) {
                    ctx.status(HTTP_BAD_REQUEST).json(
                        mapOf(
                            API_FIELD_ERROR to "Invalid 'format' parameter",
                            "allowed" to C4ArchitectureService.FORMATS
                        )
                    )
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

        app.get("$prefix/overview") { ctx ->
            withGraph(ctx, provider) { graph ->
                val limit = boundedLimit(ctx, DEFAULT_OVERVIEW_LIMIT, MAX_OVERVIEW_LIMIT)
                ctx.json(buildClassOverview(graph, limit))
            }
        }

        app.post("$prefix/cypher") { ctx ->
            withGraph(ctx, provider) { graph ->
                val body = ctx.body()
                val query = try {
                    JsonParser.parseString(body).asJsonObject.get(API_PARAM_QUERY).asString
                } catch (_: Exception) {
                    ctx.queryParam(API_PARAM_QUERY) ?: run {
                        ctx.status(HTTP_BAD_REQUEST).result("Missing 'query' parameter")
                        return@withGraph
                    }
                }
                try {
                    val result = CypherExecutor(graph).execute(query, boundedLimit(ctx, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT))
                    ctx.json(
                        mapOf(
                            API_FIELD_COLUMNS to result.columns,
                            API_FIELD_ROWS to result.rows,
                            API_FIELD_ROW_COUNT to result.rows.size
                        )
                    )
                } catch (e: Exception) {
                    ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to (e.message ?: "Query execution failed")))
                }
            }
        }

        app.get("$prefix/cypher") { ctx ->
            withGraph(ctx, provider) { graph ->
                val query = ctx.queryParam(API_PARAM_QUERY) ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result("Missing 'query' parameter")
                    return@withGraph
                }
                try {
                    val result = CypherExecutor(graph).execute(query, boundedLimit(ctx, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT))
                    ctx.json(
                        mapOf(
                            API_FIELD_COLUMNS to result.columns,
                            API_FIELD_ROWS to result.rows,
                            API_FIELD_ROW_COUNT to result.rows.size
                        )
                    )
                } catch (e: Exception) {
                    ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to (e.message ?: "Query execution failed")))
                }
            }
        }
    }

    private fun methodToMap(method: io.johnsonlee.graphite.core.MethodDescriptor): Map<String, Any?> = mapOf(
        "signature" to method.signature,
        API_FIELD_CLASS to method.declaringClass.className,
        API_FIELD_NAME to method.name,
        "returnType" to method.returnType.className
    )

    private fun requiredQueryParam(ctx: Context, name: String): String? =
        ctx.queryParam(name) ?: run {
            ctx.status(HTTP_BAD_REQUEST).result("Missing '$name' parameter")
            null
        }

    private fun grouped(graphId: String, data: Any?): Map<String, Any?> = mapOf(
        API_FIELD_GRAPH_ID to graphId,
        API_FIELD_DATA to data
    )

    private fun groupedEnvelope(selectedGraphCount: Int, results: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "graphCount" to selectedGraphCount,
        "resultGraphCount" to results.size,
        API_FIELD_RESULTS to results
    )

    private fun respondGrouped(
        ctx: Context,
        leases: List<GraphLease>,
        data: (Graph) -> Any?
    ) {
        ctx.json(groupedEnvelope(leases.size, leases.map { grouped(it.id, data(it.graph)) }))
    }

    private fun respondGroupedLimited(
        ctx: Context,
        leases: List<GraphLease>,
        totalLimit: Int,
        data: (GraphLease, Int) -> Any?
    ) {
        val limits = distributedLimits(leases.size, totalLimit)
        val results = leases.mapIndexed { index, lease -> grouped(lease.id, data(lease, limits[index])) }
        ctx.json(groupedEnvelope(leases.size, results))
    }

    private fun qualifiedNodeToMap(graphId: String, node: Node): Map<String, Any?> =
        nodeToMap(node) + mapOf(
            API_FIELD_GRAPH_ID to graphId,
            "elementId" to "$graphId:${node.id.value}",
            "qualifiedId" to "$graphId:${node.id.value}"
        )

    private inline fun withAllGraphs(
        ctx: Context,
        acquire: (Context) -> List<GraphLease>,
        block: (List<GraphLease>) -> Unit
    ) {
        val leases = try {
            acquire(ctx)
        } catch (error: IllegalArgumentException) {
            ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message))
            return
        } catch (error: GraphNotLoadedException) {
            ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to error.message))
            return
        }
        try {
            block(leases)
        } finally {
            leases.forEach { it.close() }
        }
    }

    private fun distributedLimits(graphCount: Int, totalLimit: Int): IntArray {
        if (graphCount == 0) return IntArray(0)
        val base = totalLimit / graphCount
        val remainder = totalLimit % graphCount
        return IntArray(graphCount) { index -> base + if (index < remainder) 1 else 0 }
    }

    @Suppress("ReturnCount")
    private fun readResource(graph: Graph, path: String, maxBytes: Int): Map<String, Any?>? {
        val entry = resolveResourceEntry(graph, path) ?: return null
        val bytes = try {
            readBoundedResource(graph, path, maxBytes)
        } catch (_: IOException) {
            return null
        }
        return mapOf(
            API_FIELD_PATH to path,
            API_FIELD_SOURCE to entry.source,
            API_FIELD_DERIVED to false,
            API_FIELD_SIZE to bytes.size,
            API_FIELD_CONTENT to bytes.toString(Charsets.UTF_8)
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun queryAllGraphs(ctx: Context, acquire: (Context) -> List<GraphLease>) {
        val query = parseCypherQuery(ctx) ?: return
        val limit = boundedLimit(ctx, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT)
        withAllGraphs(ctx, acquire) { leases ->
            try {
                val result = CrossGraphCypherExecutor(
                    leases.map { lease -> CypherGraph(lease.id, lease.graph) }
                ).execute(query, limit)
                ctx.json(
                    mapOf(
                        API_FIELD_COLUMNS to result.columns,
                        API_FIELD_ROWS to result.rows,
                        API_FIELD_ROW_COUNT to result.rows.size,
                        "graphCount" to leases.size
                    )
                )
            } catch (error: RuntimeException) {
                ctx.status(HTTP_BAD_REQUEST).json(
                    mapOf(API_FIELD_ERROR to (error.message ?: "Query execution failed"))
                )
            }
        }
    }

    private fun parseCypherQuery(ctx: Context): String? {
        val body = ctx.body().trim()
        val query = if (body.isNotEmpty()) {
            runCatching { JsonParser.parseString(body).asJsonObject.get(API_PARAM_QUERY)?.asString }.getOrNull()
        } else {
            null
        } ?: ctx.queryParam(API_PARAM_QUERY)
        if (query.isNullOrBlank()) {
            ctx.status(HTTP_BAD_REQUEST).result("Missing 'query' parameter")
            return null
        }
        return query.trim()
    }

    private fun registerAllC4Response(ctx: Context, acquire: (Context) -> List<GraphLease>) {
        val level = ctx.queryParam(API_PARAM_LEVEL) ?: "all"
        val format = resolveC4ResponseFormat(ctx.header("Accept"), ctx.queryParam(API_PARAM_FORMAT))
        if (level !in C4ArchitectureService.LEVELS) {
            ctx.status(HTTP_BAD_REQUEST).json(
                mapOf(API_FIELD_ERROR to "Invalid 'level' parameter", "allowed" to C4ArchitectureService.LEVELS)
            )
            return
        }
        if (format !in C4ArchitectureService.FORMATS) {
            ctx.status(HTTP_BAD_REQUEST).json(
                mapOf(API_FIELD_ERROR to "Invalid 'format' parameter", "allowed" to C4ArchitectureService.FORMATS)
            )
            return
        }
        withAllGraphs(ctx, acquire) { leases ->
            val models = leases.map { lease -> lease.id to c4.buildModel(lease.graph, level) }
            when (format) {
                "json" -> ctx.contentType("application/json; charset=utf-8").json(
                    groupedEnvelope(models.size, models.map { (id, model) -> grouped(id, model) })
                )
                "mermaid" -> ctx.contentType("text/vnd.mermaid; charset=utf-8").result(
                    models.joinToString("\n\n") { (id, model) -> "%% graphId: $id\n${c4.renderMermaid(model)}" }
                )
                "plantuml" -> ctx.contentType("text/vnd.plantuml; charset=utf-8").result(
                    models.joinToString("\n\n") { (id, model) -> "' graphId: $id\n${c4.renderPlantUml(model)}" }
                )
                "dsl" -> ctx.contentType("text/vnd.structurizr.dsl; charset=utf-8").result(
                    models.joinToString("\n\n") { (id, model) -> "// graphId: $id\n${c4.renderStructurizrDsl(model)}" }
                )
            }
        }
    }

    private fun registerSpecRoutes(app: Javalin) {
        app.get("/openapi.json") { ctx ->
            ctx.json(openApiSpecBuilder.build())
        }

        app.get("/swagger.json") { ctx ->
            ctx.json(openApiSpecBuilder.build())
        }
    }

    private fun loadGraphFromRequest(ctx: Context, registry: GraphRegistry, topology: TopologyService) {
        val id = ctx.pathParam(API_FIELD_GRAPH_ID)
        runCatching {
            val request = parseGraphLoadRequest(ctx)
            val descriptor = registry.load(id, request.path, request.loadMode ?: registry.defaultLoadMode)
            topology.rebuild()
            ctx.json(mapOf("graph" to descriptor.toApiMap()))
        }.onFailure { error ->
            ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message))
        }
    }

    private fun queryLoadedGraphsFromRequest(ctx: Context, registry: GraphRegistry) {
        runCatching {
            val request = parseMultiGraphCypherRequest(ctx)
            val graphIds = if (request.allGraphs) registry.ids() else request.graphIds
            val leases = registry.acquireAll(graphIds)
            try {
                when (request.mode) {
                    MultiGraphQueryMode.CROSS_GRAPH -> {
                        require(request.perGraphLimit == null) { "perGraphLimit is only valid in fanout mode" }
                        require(!request.includeGraphRows) { "includeGraphRows is only valid in fanout mode" }
                        val result = CrossGraphCypherExecutor(
                            leases.map { lease -> CypherGraph(lease.id, lease.graph) }
                        ).execute(request.query, request.limit)
                        ctx.json(
                            mapOf(
                                API_PARAM_MODE to request.mode.wireName,
                                API_FIELD_GRAPHS to graphIds,
                                "graphCount" to graphIds.size,
                                API_FIELD_COLUMNS to result.columns,
                                API_FIELD_ROWS to result.rows,
                                API_FIELD_ROW_COUNT to result.rows.size,
                                API_PARAM_LIMIT to request.limit
                            )
                        )
                    }
                    MultiGraphQueryMode.FANOUT -> {
                        val perGraphLimit = request.perGraphLimit
                            ?: defaultPerGraphLimit(graphIds.size, request.limit)
                        ctx.json(executeFanoutQuery(request, leases, perGraphLimit))
                    }
                }
            } finally {
                leases.forEach { it.close() }
            }
        }.onFailure { error ->
            val status = if (error is GraphNotLoadedException) HTTP_NOT_FOUND else HTTP_BAD_REQUEST
            ctx.status(status).json(mapOf(API_FIELD_ERROR to (error.message ?: "Query execution failed")))
        }
    }

    private fun executeFanoutQuery(
        request: MultiGraphCypherRequest,
        leases: List<GraphLease>,
        perGraphLimit: Int
    ): Map<String, Any?> {
        val results = mutableListOf<MultiGraphCypherResult>()
        var remainingRows = request.limit
        var truncated = false
        for (lease in leases) {
            if (remainingRows <= 0) {
                truncated = true
                break
            }
            val result = CypherExecutor(lease.graph).execute(request.query, minOf(perGraphLimit, remainingRows))
            val rows = result.rows.map { rowWithGraphId(lease.id, it) }
            remainingRows -= rows.size
            truncated = truncated || remainingRows <= 0
            results += MultiGraphCypherResult(lease.id, result.columns, rows)
        }
        return buildMultiGraphCypherResponse(
            results = results,
            requestedGraphCount = leases.size,
            queriedGraphCount = results.size,
            perGraphLimit = perGraphLimit,
            limit = request.limit,
            includeGraphRows = request.includeGraphRows,
            truncated = truncated
        ) + (API_PARAM_MODE to MultiGraphQueryMode.FANOUT.wireName)
    }

    private fun rowWithGraphId(graphId: String, row: Map<String, Any?>): Map<String, Any?> =
        linkedMapOf<String, Any?>(API_FIELD_GRAPH_ID to graphId).apply {
            putAll(row)
            put(API_FIELD_GRAPH_ID, graphId)
        }

    private fun defaultPerGraphLimit(graphCount: Int, limit: Int): Int =
        if (graphCount <= 0) {
            0
        } else {
            ((limit + graphCount - 1) / graphCount).coerceIn(0, MAX_CYPHER_ROW_LIMIT)
        }

    private fun parseGraphLoadRequest(ctx: Context): GraphLoadRequest {
        val body = ctx.body().trim()
        val path = if (body.isBlank()) {
            ctx.queryParam(API_FIELD_PATH) ?: error("Missing 'path' field")
        } else {
            JsonParser.parseString(body).asJsonObject.get(API_FIELD_PATH)?.asString ?: error("Missing 'path' field")
        }
        val loadMode = if (body.isBlank()) {
            ctx.queryParam(API_FIELD_LOAD_MODE)
        } else {
            JsonParser.parseString(body).asJsonObject.get(API_FIELD_LOAD_MODE)?.asString
        }?.let { GraphStore.LoadMode.valueOf(it.uppercase()) }
        return GraphLoadRequest(Path.of(path), loadMode)
    }

    private fun parseMultiGraphCypherRequest(ctx: Context): MultiGraphCypherRequest {
        val body = ctx.body().trim()
        val json = if (body.isBlank()) null else JsonParser.parseString(body).asJsonObject
        val query = if (json == null) {
            ctx.queryParam(API_PARAM_QUERY) ?: error("Missing 'query' parameter")
        } else {
            json.get(API_PARAM_QUERY)?.asString ?: error("Missing 'query' parameter")
        }
        val graphIds = json?.let { parseGraphIdsFromJson(it) } ?: parseGraphIdsFromQuery(ctx)
        require(graphIds.distinct().size == graphIds.size) { "Graph ids must be unique" }
        val allGraphs = parseBoolean(
            firstJsonString(json, API_PARAM_ALL_GRAPHS) ?: firstQueryParam(ctx, API_PARAM_ALL_GRAPHS)
        )
        require(allGraphs.xor(graphIds.isNotEmpty())) {
            "Specify exactly one of 'allGraphs=true' or a non-empty 'graphs' list"
        }
        val mode = MultiGraphQueryMode.parse(
            firstJsonString(json, API_PARAM_MODE) ?: firstQueryParam(ctx, API_PARAM_MODE)
        )
        val perGraphLimit = optionalBoundedLimit(
            firstJsonString(json, API_PARAM_PER_GRAPH_LIMIT, API_FIELD_PER_GRAPH_LIMIT)
                ?: firstQueryParam(ctx, API_PARAM_PER_GRAPH_LIMIT, API_FIELD_PER_GRAPH_LIMIT),
            MAX_CYPHER_ROW_LIMIT
        )
        val limit = boundedLimit(
            firstJsonString(json, API_PARAM_LIMIT) ?: firstQueryParam(ctx, API_PARAM_LIMIT),
            DEFAULT_CYPHER_ROW_LIMIT,
            MAX_CYPHER_ROW_LIMIT
        )
        val includeGraphRows = parseBoolean(
            firstJsonString(json, API_PARAM_INCLUDE_GRAPH_ROWS)
                ?: firstQueryParam(ctx, API_PARAM_INCLUDE_GRAPH_ROWS)
        )
        return MultiGraphCypherRequest(query.trim(), graphIds, allGraphs, mode, perGraphLimit, limit, includeGraphRows)
    }

    private fun parseGraphIdsFromJson(json: JsonObject): List<String> {
        val graphElement = json.get(API_FIELD_GRAPHS) ?: json.get(API_PARAM_GRAPH) ?: return emptyList()
        return when {
            graphElement.isJsonArray -> graphElement.asJsonArray.map { it.asString }
            graphElement.isJsonPrimitive -> splitGraphIds(graphElement.asString)
            else -> error("Invalid 'graphs' field")
        }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseGraphIdsFromQuery(ctx: Context): List<String> {
        val params = ctx.queryParamMap()
        return (params[API_PARAM_GRAPH].orEmpty() + params[API_FIELD_GRAPHS].orEmpty())
            .flatMap(::splitGraphIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun splitGraphIds(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun buildMultiGraphCypherResponse(
        results: List<MultiGraphCypherResult>,
        requestedGraphCount: Int,
        queriedGraphCount: Int,
        perGraphLimit: Int,
        limit: Int,
        includeGraphRows: Boolean,
        truncated: Boolean
    ): Map<String, Any?> {
        val columns = linkedSetOf(API_FIELD_GRAPH_ID)
        results.forEach { columns.addAll(it.columns) }
        val rows = results.flatMap { it.rows }
        return mapOf(
            API_FIELD_COLUMNS to columns.toList(),
            API_FIELD_ROWS to rows,
            API_FIELD_ROW_COUNT to rows.size,
            "graphCount" to requestedGraphCount,
            API_FIELD_QUERIED_GRAPH_COUNT to queriedGraphCount,
            API_FIELD_PER_GRAPH_LIMIT to perGraphLimit,
            API_PARAM_LIMIT to limit,
            API_FIELD_TRUNCATED to truncated,
            API_FIELD_GRAPHS to results.map {
                buildMap {
                    put(API_FIELD_GRAPH_ID, it.graphId)
                    put(API_FIELD_COLUMNS, it.columns)
                    put(API_FIELD_ROW_COUNT, it.rows.size)
                    if (includeGraphRows) put(API_FIELD_ROWS, it.rows)
                }
            }
        )
    }

    private fun firstJsonString(json: JsonObject?, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            json?.get(name)?.takeUnless { it.isJsonNull }?.asString
        }

    private fun firstQueryParam(ctx: Context, vararg names: String): String? =
        names.firstNotNullOfOrNull { ctx.queryParam(it) }

    private fun parseBoolean(raw: String?): Boolean =
        when (raw?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            else -> false
        }

    private inline fun withGraph(ctx: Context, provider: GraphProvider, block: (Graph) -> Unit) {
        val lease = try {
            provider.acquire(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to e.message))
            return
        }
        if (lease == null) {
            ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to "Graph not loaded"))
            return
        }
        lease.use { block(it.graph) }
    }

    internal fun buildSubgraph(graph: Graph, center: NodeId, depth: Int): Map<String, Any> =
        buildSubgraph(graph, center, depth, SubgraphDirection.BOTH)

    @Suppress("NestedBlockDepth")
    private fun buildSubgraph(
        graph: Graph,
        center: NodeId,
        depth: Int,
        direction: SubgraphDirection
    ): Map<String, Any> {
        val visitedNodes = mutableSetOf<Int>()
        val nodes = mutableListOf<Map<String, Any?>>()
        val edges = mutableListOf<Map<String, Any?>>()

        fun visit(nodeId: NodeId, remaining: Int) {
            fun traverse(candidates: Sequence<Edge>, nextNode: (Edge) -> NodeId) {
                for (edge in candidates) {
                    if (edges.size >= MAX_SUBGRAPH_EDGES) break
                    edges.add(edgeToMap(edge))
                    visit(nextNode(edge), remaining - 1)
                }
            }

            if (nodes.size < MAX_SUBGRAPH_NODES && visitedNodes.add(nodeId.value) && remaining >= 0) {
                val node = graph.node(nodeId)
                if (node != null) {
                    nodes.add(nodeToMap(node))
                    if (remaining > 0) {
                        if (direction.includeOutgoing) traverse(graph.outgoing(nodeId)) { it.to }
                        if (direction.includeIncoming) traverse(graph.incoming(nodeId)) { it.from }
                    }
                }
            }
        }

        visit(center, depth)
        return mapOf(API_FIELD_NODES to nodes, API_FIELD_EDGES to edges)
    }

    private fun resolveResourceEntry(graph: Graph, path: String): ResourceEntry? =
        graph.resources.list("**").firstOrNull { it.path == path }

    private fun listResources(graph: Graph, pattern: String, limit: Int): List<Map<String, Any?>> {
        return graph.resources.list(pattern)
            .map { entry ->
                mapOf(API_FIELD_PATH to entry.path, API_FIELD_SOURCE to entry.source, API_FIELD_DERIVED to false)
            }
            .take(limit)
            .toList()
    }

    private fun readBoundedResource(graph: Graph, path: String, maxBytes: Int = MAX_RESOURCE_BYTES): ByteArray =
        graph.resources.open(path).use { input ->
            val bytes = input.readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) throw ResourceTooLargeException()
            bytes
        }

    private fun buildClassOverview(graph: Graph, limit: Int): Map<String, List<Map<String, Any?>>> =
        runCatching { graph.classOverview(limit) }.getOrNull()
            ?.let { buildClassOverview(it, limit) }
            ?: buildClassOverviewFromCallSites(graph, limit)

    private fun buildClassOverview(overview: ClassOverview, limit: Int): Map<String, List<Map<String, Any?>>> =
        buildClassOverview(overview.classCounts, overview.classEdges, limit)

    private fun buildClassOverviewFromCallSites(graph: Graph, limit: Int): Map<String, List<Map<String, Any?>>> {
        val classEdges = mutableMapOf<ClassDependency, Int>()
        val classCounts = mutableMapOf<String, Int>()

        var scanned = 0
        for (cs in graph.nodes(CallSiteNode::class.java)) {
            if (scanned >= MAX_OVERVIEW_CALL_SITES) break
            scanned++
            val callerClass = cs.caller.declaringClass.className
            val calleeClass = cs.callee.declaringClass.className
            if (callerClass != calleeClass) {
                incrementBounded(classEdges, ClassDependency(callerClass, calleeClass), MAX_OVERVIEW_EDGES)
            }
            incrementBounded(classCounts, callerClass, MAX_OVERVIEW_CLASSES)
            incrementBounded(classCounts, calleeClass, MAX_OVERVIEW_CLASSES)
        }

        return buildClassOverview(classCounts, classEdges, limit)
    }

    private fun buildClassOverview(
        classCounts: Map<String, Int>,
        classEdges: Map<ClassDependency, Int>,
        limit: Int
    ): Map<String, List<Map<String, Any?>>> {
        val topClasses = classCounts.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
            .toSet()

        val nodes = topClasses.map { cls ->
            val shortName = cls.substringAfterLast('.')
            mapOf(
                "id" to cls,
                API_FIELD_TYPE to "Class",
                API_FIELD_LABEL to shortName,
                "fullName" to cls,
                API_FIELD_CALL_SITES to (classCounts[cls] ?: 0)
            )
        }

        val edges = classEdges.entries
            .filter { it.key.callerClass in topClasses && it.key.calleeClass in topClasses }
            .map { (key, count) ->
                mapOf(
                    "from" to key.callerClass,
                    "to" to key.calleeClass,
                    API_FIELD_TYPE to "Call",
                    "weight" to count
                )
            }

        return mapOf(API_FIELD_NODES to nodes, API_FIELD_EDGES to edges)
    }

    private fun boundedLimit(ctx: io.javalin.http.Context, default: Int, max: Int): Int =
        boundedLimit(ctx.queryParam(API_PARAM_LIMIT), default, max)

    private fun boundedLimit(raw: String?, default: Int, max: Int): Int =
        (raw?.toIntOrNull() ?: default).coerceIn(0, max)

    private fun optionalBoundedLimit(raw: String?, max: Int): Int? =
        raw?.toIntOrNull()?.coerceIn(0, max)

    private fun boundedDepth(raw: String?): Int =
        (raw?.toIntOrNull() ?: DEFAULT_SUBGRAPH_DEPTH).coerceAtMost(MAX_SUBGRAPH_DEPTH)

    private fun resolveSubgraphDirection(raw: String?): SubgraphDirection? =
        when (raw?.lowercase()) {
            null, "both" -> SubgraphDirection.BOTH
            "outgoing" -> SubgraphDirection.OUTGOING
            "incoming" -> SubgraphDirection.INCOMING
            else -> null
        }

    private fun <K> incrementBounded(counts: MutableMap<K, Int>, key: K, maxKeys: Int) {
        if (key in counts || counts.size < maxKeys) {
            counts[key] = (counts[key] ?: 0) + 1
        }
    }


    private fun resolveC4ResponseFormat(accept: String?, queryFormat: String?): String {
        val accepted = accept.orEmpty().split(',')
            .map { it.substringBefore(';').trim().lowercase() }
            .filter { it.isNotBlank() && it != "*/*" }
        return when {
            accepted.any { it == "text/vnd.plantuml" || it == "text/x-plantuml" || it == "application/vnd.plantuml" } -> "plantuml"
            accepted.any { it == "text/vnd.mermaid" || it == "text/x-mermaid" } -> "mermaid"
            accepted.any { it == "text/vnd.structurizr.dsl" || it == "text/x-structurizr" || it == "application/vnd.structurizr.dsl" } -> "dsl"
            accepted.any { it == "application/vnd.structurizr+json" || it == "application/json" } -> "json"
            !queryFormat.isNullOrBlank() -> queryFormat.lowercase()
            else -> "json"
        }
    }

    companion object {
        private const val API_ROOT = "/api"
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PAYLOAD_TOO_LARGE = 413

        private const val DEFAULT_ENTITY_LIMIT = 50
        private const val DEFAULT_EDGE_LIMIT = 200
        private const val DEFAULT_RESOURCE_LIMIT = 100
        private const val DEFAULT_ENDPOINT_LIMIT = 200
        private const val DEFAULT_OVERVIEW_LIMIT = 200
        private const val DEFAULT_CYPHER_ROW_LIMIT = 1_000
        private const val DEFAULT_SUBGRAPH_DEPTH = 2
        private const val MAX_ENTITY_LIMIT = 5_000
        private const val MAX_EDGE_LIMIT = 2_000
        private const val MAX_RESOURCE_LIMIT = 1_000
        private const val MAX_ENDPOINT_LIMIT = 2_000
        private const val MAX_OVERVIEW_LIMIT = 1_000
        private const val MAX_OVERVIEW_CALL_SITES = 100_000
        private const val MAX_OVERVIEW_CLASSES = 20_000
        private const val MAX_OVERVIEW_EDGES = 50_000
        private const val MAX_CYPHER_ROW_LIMIT = 5_000
        private const val MAX_SUBGRAPH_DEPTH = 4
        private const val MAX_SUBGRAPH_NODES = 2_000
        private const val MAX_SUBGRAPH_EDGES = 5_000
        private const val MAX_RESOURCE_BYTES = 1_048_576
        private const val API_ERROR_RESOURCE_TOO_LARGE = "Resource exceeds maximum response size"
    }

    private class ResourceTooLargeException : RuntimeException()

    private data class GraphLoadRequest(
        val path: Path,
        val loadMode: GraphStore.LoadMode?
    )

    private data class MultiGraphCypherRequest(
        val query: String,
        val graphIds: List<String>,
        val allGraphs: Boolean,
        val mode: MultiGraphQueryMode,
        val perGraphLimit: Int?,
        val limit: Int,
        val includeGraphRows: Boolean
    )

    private data class MultiGraphCypherResult(
        val graphId: String,
        val columns: List<String>,
        val rows: List<Map<String, Any?>>
    )

    private enum class MultiGraphQueryMode(val wireName: String) {
        CROSS_GRAPH("cross-graph"),
        FANOUT("fanout");

        companion object {
            fun parse(value: String?): MultiGraphQueryMode = when (value?.trim()?.lowercase()) {
                null, "", "cross-graph", "cross_graph", "crossgraph" -> CROSS_GRAPH
                "fanout", "fan-out", "fan_out" -> FANOUT
                else -> error("Invalid query mode '$value'. Expected 'cross-graph' or 'fanout'")
            }
        }
    }

    private enum class SubgraphDirection(
        val includeOutgoing: Boolean,
        val includeIncoming: Boolean
    ) {
        OUTGOING(includeOutgoing = true, includeIncoming = false),
        INCOMING(includeOutgoing = false, includeIncoming = true),
        BOTH(includeOutgoing = true, includeIncoming = true)
    }

}
