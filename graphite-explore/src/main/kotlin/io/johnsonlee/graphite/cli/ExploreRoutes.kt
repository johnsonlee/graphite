package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.javalin.Javalin
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.IOException

internal class ExploreRoutes {

    private val apiSpecExtractor = ApiSpecExtractor()
    private val openApiSpecBuilder = OpenApiSpecBuilder()
    private val c4 = C4ArchitectureService()

    internal fun register(app: Javalin, graph: Graph) {
        val cypherExecutor = CypherExecutor(graph)

        app.get("/api/info") { ctx ->
            val nodeCount = graph.nodeCount(Node::class.java) ?: graph.nodes(Node::class.java).count().toLong()
            val edgeCount = graph.edgeCount()
                ?: graph.nodes(Node::class.java).sumOf { graph.outgoing(it.id).count().toLong() }
            ctx.json(mapOf(
                API_FIELD_NODES to nodeCount,
                API_FIELD_EDGES to edgeCount,
                API_FIELD_METHODS to graph.methods(MethodPattern()).count().toLong(),
                API_FIELD_CALL_SITES to (
                    graph.nodeCount(CallSiteNode::class.java)
                        ?: graph.nodes(CallSiteNode::class.java).count().toLong()
                    )
            ))
        }

        app.get("/api/nodes") { ctx ->
            val type = ctx.queryParam(API_PARAM_TYPE)
            val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
            val nodeClass = resolveNodeType(type)
            val nodes = graph.nodes(nodeClass).take(limit).toList()
            ctx.json(nodes.map { nodeToMap(it) })
        }

        app.get("/api/node/{id}") { ctx ->
            val id = ctx.pathParam(API_FIELD_ID).toIntOrNull() ?: run {
                ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_INVALID_NODE_ID)
                return@get
            }
            val node = graph.node(NodeId(id)) ?: run {
                ctx.status(HTTP_NOT_FOUND).result(API_ERROR_NODE_NOT_FOUND)
                return@get
            }
            ctx.json(nodeToMap(node))
        }

        app.get("/api/node/{id}/outgoing") { ctx ->
            val id = ctx.pathParam(API_FIELD_ID).toIntOrNull() ?: run {
                ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_INVALID_NODE_ID)
                return@get
            }
            val limit = boundedLimit(ctx, DEFAULT_EDGE_LIMIT, MAX_EDGE_LIMIT)
            val edges = graph.outgoing(NodeId(id)).take(limit).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }

        app.get("/api/node/{id}/incoming") { ctx ->
            val id = ctx.pathParam(API_FIELD_ID).toIntOrNull() ?: run {
                ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_INVALID_NODE_ID)
                return@get
            }
            val limit = boundedLimit(ctx, DEFAULT_EDGE_LIMIT, MAX_EDGE_LIMIT)
            val edges = graph.incoming(NodeId(id)).take(limit).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }

        app.get("/api/call-sites") { ctx ->
            val classPattern = ctx.queryParam(API_PARAM_CLASS)
            val methodPattern = ctx.queryParam(API_PARAM_METHOD)
            val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
            val pattern = MethodPattern(declaringClass = classPattern, name = methodPattern)
            val callSites = graph.callSites(pattern).take(limit).toList()
            ctx.json(callSites.map { nodeToMap(it) })
        }

        app.get("/api/methods") { ctx ->
            val classPattern = ctx.queryParam(API_PARAM_CLASS)
            val namePattern = ctx.queryParam(API_PARAM_NAME)
            val limit = boundedLimit(ctx, DEFAULT_ENTITY_LIMIT, MAX_ENTITY_LIMIT)
            val pattern = MethodPattern(declaringClass = classPattern, name = namePattern)
            val methods = graph.methods(pattern).take(limit).toList()
            ctx.json(methods.map { mapOf(
                "signature" to it.signature,
                API_FIELD_CLASS to it.declaringClass.className,
                API_FIELD_NAME to it.name,
                "returnType" to it.returnType.className
            ) })
        }

        app.get("/api/annotations") { ctx ->
            val className = ctx.queryParam(API_PARAM_CLASS) ?: run {
                ctx.status(HTTP_BAD_REQUEST).result("Missing 'class' parameter")
                return@get
            }
            val memberName = ctx.queryParam(API_PARAM_MEMBER) ?: run {
                ctx.status(HTTP_BAD_REQUEST).result("Missing 'member' parameter")
                return@get
            }
            ctx.json(graph.memberAnnotations(className, memberName))
        }

        app.get("/api/resources") { ctx ->
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

        app.get("/api/resources/<path>") { ctx ->
            val path = ctx.pathParam("path").trimStart('/')
            if (path.isBlank()) {
                ctx.status(HTTP_NOT_FOUND).result(API_ERROR_RESOURCE_NOT_FOUND)
                return@get
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

        app.get("/api/api-spec") { ctx ->
            val limit = boundedLimit(ctx, DEFAULT_API_SPEC_LIMIT, MAX_API_SPEC_LIMIT)
            val classPattern = ctx.queryParam(API_PARAM_CLASS)
            val endpoints = apiSpecExtractor.extract(graph)
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

        app.get("/openapi.json") { ctx ->
            ctx.json(openApiSpecBuilder.build())
        }

        app.get("/swagger.json") { ctx ->
            ctx.json(openApiSpecBuilder.build())
        }

        app.get("/api/architecture/c4") { ctx ->
            val level = ctx.queryParam(API_PARAM_LEVEL) ?: "all"
            val format = resolveC4ResponseFormat(ctx.header("Accept"), ctx.queryParam(API_PARAM_FORMAT))
            if (level !in C4ArchitectureService.LEVELS) {
                ctx.status(HTTP_BAD_REQUEST).json(
                    mapOf(
                        API_FIELD_ERROR to "Invalid 'level' parameter",
                        "allowed" to C4ArchitectureService.LEVELS
                    )
                )
                return@get
            }
            if (format !in C4ArchitectureService.FORMATS) {
                ctx.status(HTTP_BAD_REQUEST).json(
                    mapOf(
                        API_FIELD_ERROR to "Invalid 'format' parameter",
                        "allowed" to C4ArchitectureService.FORMATS
                    )
                )
                return@get
            }
            val model = c4.buildModel(graph, level)
            when (format) {
                "json" -> ctx.contentType("application/vnd.structurizr+json; charset=utf-8").json(model)
                "mermaid" -> ctx.contentType("text/vnd.mermaid; charset=utf-8").result(c4.renderMermaid(model))
                "plantuml" -> ctx.contentType("text/vnd.plantuml; charset=utf-8").result(c4.renderPlantUml(model))
                "dsl" -> ctx.contentType("text/vnd.structurizr.dsl; charset=utf-8").result(c4.renderStructurizrDsl(model))
            }
        }

        app.get("/api/overview") { ctx ->
            val limit = boundedLimit(ctx, DEFAULT_OVERVIEW_LIMIT, MAX_OVERVIEW_LIMIT)
            // Build class-level dependency graph from call sites
            val classEdges = mutableMapOf<Pair<String, String>, Int>() // (callerClass, calleeClass) -> count
            val classCounts = mutableMapOf<String, Int>() // class -> number of call sites

            var scanned = 0
            for (cs in graph.nodes(CallSiteNode::class.java)) {
                if (scanned >= MAX_OVERVIEW_CALL_SITES) break
                scanned++
                val callerClass = cs.caller.declaringClass.className
                val calleeClass = cs.callee.declaringClass.className
                if (callerClass != calleeClass) {
                    val key = callerClass to calleeClass
                    incrementBounded(classEdges, key, MAX_OVERVIEW_EDGES)
                }
                incrementBounded(classCounts, callerClass, MAX_OVERVIEW_CLASSES)
                incrementBounded(classCounts, calleeClass, MAX_OVERVIEW_CLASSES)
            }

            // Take top classes by call site involvement
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
                .filter { it.key.first in topClasses && it.key.second in topClasses }
                .map { (key, count) ->
                    mapOf(
                        "from" to key.first,
                        "to" to key.second,
                        API_FIELD_TYPE to "Call",
                        "weight" to count
                    )
                }

            ctx.json(mapOf(API_FIELD_NODES to nodes, API_FIELD_EDGES to edges))
        }

        app.get("/api/subgraph") { ctx ->
            val centerId = ctx.queryParam(API_PARAM_CENTER)?.toIntOrNull() ?: run {
                ctx.status(HTTP_BAD_REQUEST).result("Missing 'center' parameter")
                return@get
            }
            val depth = boundedDepth(ctx.queryParam(API_PARAM_DEPTH))
            val direction = resolveSubgraphDirection(ctx.queryParam(API_PARAM_DIRECTION)) ?: run {
                ctx.status(HTTP_BAD_REQUEST).result("Invalid 'direction' parameter")
                return@get
            }
            val subgraph = buildSubgraph(graph, NodeId(centerId), depth, direction)
            ctx.json(subgraph)
        }

        app.post("/api/cypher") { ctx ->
            val body = ctx.body()
            val query = try {
                JsonParser.parseString(body).asJsonObject.get(API_PARAM_QUERY).asString
            } catch (e: Exception) {
                ctx.queryParam(API_PARAM_QUERY) ?: run {
                    ctx.status(HTTP_BAD_REQUEST).result("Missing 'query' parameter")
                    return@post
                }
            }
            try {
                val result = cypherExecutor.execute(query, boundedLimit(ctx, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT))
                ctx.json(mapOf(
                    API_FIELD_COLUMNS to result.columns,
                    API_FIELD_ROWS to result.rows,
                    API_FIELD_ROW_COUNT to result.rows.size
                ))
            } catch (e: Exception) {
                ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to (e.message ?: "Query execution failed")))
            }
        }

        app.get("/api/cypher") { ctx ->
            val query = ctx.queryParam(API_PARAM_QUERY) ?: run {
                ctx.status(HTTP_BAD_REQUEST).result("Missing 'query' parameter")
                return@get
            }
            try {
                val result = cypherExecutor.execute(query, boundedLimit(ctx, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT))
                ctx.json(mapOf(
                    API_FIELD_COLUMNS to result.columns,
                    API_FIELD_ROWS to result.rows,
                    API_FIELD_ROW_COUNT to result.rows.size
                ))
            } catch (e: Exception) {
                ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to (e.message ?: "Query execution failed")))
            }
        }
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

    private fun readBoundedResource(graph: Graph, path: String): ByteArray =
        graph.resources.open(path).use { input ->
            val bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1)
            if (bytes.size > MAX_RESOURCE_BYTES) throw ResourceTooLargeException()
            bytes
        }

    private fun boundedLimit(ctx: io.javalin.http.Context, default: Int, max: Int): Int =
        boundedLimit(ctx.queryParam(API_PARAM_LIMIT), default, max)

    private fun boundedLimit(raw: String?, default: Int, max: Int): Int =
        (raw?.toIntOrNull() ?: default).coerceIn(0, max)

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
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PAYLOAD_TOO_LARGE = 413

        private const val DEFAULT_ENTITY_LIMIT = 50
        private const val DEFAULT_EDGE_LIMIT = 200
        private const val DEFAULT_RESOURCE_LIMIT = 100
        private const val DEFAULT_API_SPEC_LIMIT = 200
        private const val DEFAULT_OVERVIEW_LIMIT = 200
        private const val DEFAULT_CYPHER_ROW_LIMIT = 1_000
        private const val DEFAULT_SUBGRAPH_DEPTH = 2
        private const val MAX_ENTITY_LIMIT = 5_000
        private const val MAX_EDGE_LIMIT = 2_000
        private const val MAX_RESOURCE_LIMIT = 1_000
        private const val MAX_API_SPEC_LIMIT = 2_000
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

    private enum class SubgraphDirection(
        val includeOutgoing: Boolean,
        val includeIncoming: Boolean
    ) {
        OUTGOING(includeOutgoing = true, includeIncoming = false),
        INCOMING(includeOutgoing = false, includeIncoming = true),
        BOTH(includeOutgoing = true, includeIncoming = true)
    }

}
