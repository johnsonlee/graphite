package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.javalin.Javalin
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.core.*
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
            val nodeCount = graph.nodes(Node::class.java).count()
            val edgeCount = graph.nodes(Node::class.java).sumOf { graph.outgoing(it.id).count().toLong() }
            ctx.json(mapOf(
                "nodes" to nodeCount,
                "edges" to edgeCount,
                "methods" to graph.methods(MethodPattern()).count(),
                "callSites" to graph.nodes(CallSiteNode::class.java).count()
            ))
        }

        app.get("/api/nodes") { ctx ->
            val type = ctx.queryParam("type")
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: DEFAULT_ENTITY_LIMIT
            val nodeClass = resolveNodeType(type)
            val nodes = graph.nodes(nodeClass).take(limit).toList()
            ctx.json(nodes.map { nodeToMap(it) })
        }

        app.get("/api/node/{id}") { ctx ->
            val id = ctx.pathParam("id").toIntOrNull() ?: run { ctx.status(HTTP_BAD_REQUEST).result("Invalid node ID"); return@get }
            val node = graph.node(NodeId(id)) ?: run { ctx.status(HTTP_NOT_FOUND).result("Node not found"); return@get }
            ctx.json(nodeToMap(node))
        }

        app.get("/api/node/{id}/outgoing") { ctx ->
            val id = ctx.pathParam("id").toIntOrNull() ?: run { ctx.status(HTTP_BAD_REQUEST).result("Invalid node ID"); return@get }
            val edges = graph.outgoing(NodeId(id)).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }

        app.get("/api/node/{id}/incoming") { ctx ->
            val id = ctx.pathParam("id").toIntOrNull() ?: run { ctx.status(HTTP_BAD_REQUEST).result("Invalid node ID"); return@get }
            val edges = graph.incoming(NodeId(id)).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }

        app.get("/api/call-sites") { ctx ->
            val classPattern = ctx.queryParam("class")
            val methodPattern = ctx.queryParam("method")
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: DEFAULT_ENTITY_LIMIT
            val pattern = MethodPattern(declaringClass = classPattern, name = methodPattern)
            val callSites = graph.callSites(pattern).take(limit).toList()
            ctx.json(callSites.map { nodeToMap(it) })
        }

        app.get("/api/methods") { ctx ->
            val classPattern = ctx.queryParam("class")
            val namePattern = ctx.queryParam("name")
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: DEFAULT_ENTITY_LIMIT
            val pattern = MethodPattern(declaringClass = classPattern, name = namePattern)
            val methods = graph.methods(pattern).take(limit).toList()
            ctx.json(methods.map { mapOf(
                "signature" to it.signature,
                "class" to it.declaringClass.className,
                "name" to it.name,
                "returnType" to it.returnType.className
            ) })
        }

        app.get("/api/annotations") { ctx ->
            val className = ctx.queryParam("class") ?: run { ctx.status(HTTP_BAD_REQUEST).result("Missing 'class' parameter"); return@get }
            val memberName = ctx.queryParam("member") ?: run { ctx.status(HTTP_BAD_REQUEST).result("Missing 'member' parameter"); return@get }
            ctx.json(graph.memberAnnotations(className, memberName))
        }

        app.get("/api/resources") { ctx ->
            val pattern = ctx.queryParam("pattern") ?: "**"
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: DEFAULT_RESOURCE_LIMIT
            val resources = listResources(graph, pattern, limit)
            ctx.json(
                mapOf(
                    "pattern" to pattern,
                    "limit" to limit,
                    "count" to resources.size,
                    "resources" to resources
                )
            )
        }

        app.get("/api/resources/<path>") { ctx ->
            val path = ctx.pathParam("path").trimStart('/')
            if (path.isBlank()) {
                ctx.status(HTTP_NOT_FOUND).result("Resource not found")
                return@get
            }
            try {
                val entry = resolveResourceEntry(graph, path)
                val bytes = graph.resources.open(path).use { it.readBytes() }
                ctx.json(
                    mapOf(
                        "path" to path,
                        "source" to entry?.source,
                        "derived" to false,
                        "size" to bytes.size,
                        "content" to bytes.toString(Charsets.UTF_8)
                    )
                )
            } catch (_: IOException) {
                ctx.status(HTTP_NOT_FOUND).result("Resource not found: $path")
            }
        }

        app.get("/api/api-spec") { ctx ->
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: DEFAULT_API_SPEC_LIMIT
            val classPattern = ctx.queryParam("class")
            val endpoints = apiSpecExtractor.extract(graph)
                .asSequence()
                .filter { classPattern == null || it["class"] == classPattern }
                .take(limit)
                .toList()
            ctx.json(
                mapOf(
                    "framework" to "spring-web",
                    "count" to endpoints.size,
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
            val level = ctx.queryParam("level") ?: "all"
            val format = resolveC4ResponseFormat(ctx.header("Accept"), ctx.queryParam("format"))
            if (level !in C4ArchitectureService.LEVELS) {
                ctx.status(HTTP_BAD_REQUEST).json(
                    mapOf(
                        "error" to "Invalid 'level' parameter",
                        "allowed" to C4ArchitectureService.LEVELS
                    )
                )
                return@get
            }
            if (format !in C4ArchitectureService.FORMATS) {
                ctx.status(HTTP_BAD_REQUEST).json(
                    mapOf(
                        "error" to "Invalid 'format' parameter",
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
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: DEFAULT_OVERVIEW_LIMIT
            // Build class-level dependency graph from call sites
            val classEdges = mutableMapOf<Pair<String, String>, Int>() // (callerClass, calleeClass) -> count
            val classCounts = mutableMapOf<String, Int>() // class -> number of call sites

            graph.nodes(CallSiteNode::class.java).forEach { cs ->
                val callerClass = cs.caller.declaringClass.className
                val calleeClass = cs.callee.declaringClass.className
                if (callerClass != calleeClass) {
                    val key = callerClass to calleeClass
                    classEdges[key] = (classEdges[key] ?: 0) + 1
                }
                classCounts[callerClass] = (classCounts[callerClass] ?: 0) + 1
                classCounts[calleeClass] = (classCounts[calleeClass] ?: 0) + 1
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
                    "type" to "Class",
                    "label" to shortName,
                    "fullName" to cls,
                    "callSites" to (classCounts[cls] ?: 0)
                )
            }

            val edges = classEdges.entries
                .filter { it.key.first in topClasses && it.key.second in topClasses }
                .map { (key, count) ->
                    mapOf(
                        "from" to key.first,
                        "to" to key.second,
                        "type" to "Call",
                        "weight" to count
                    )
                }

            ctx.json(mapOf("nodes" to nodes, "edges" to edges))
        }

        app.get("/api/subgraph") { ctx ->
            val centerId = ctx.queryParam("center")?.toIntOrNull() ?: run { ctx.status(HTTP_BAD_REQUEST).result("Missing 'center' parameter"); return@get }
            val depth = ctx.queryParam("depth")?.toIntOrNull() ?: DEFAULT_SUBGRAPH_DEPTH
            val subgraph = buildSubgraph(graph, NodeId(centerId), depth)
            ctx.json(subgraph)
        }

        app.post("/api/cypher") { ctx ->
            val body = ctx.body()
            val query = try {
                JsonParser.parseString(body).asJsonObject.get("query").asString
            } catch (e: Exception) {
                ctx.queryParam("query") ?: run { ctx.status(HTTP_BAD_REQUEST).result("Missing 'query' parameter"); return@post }
            }
            try {
                val result = cypherExecutor.execute(query)
                ctx.json(mapOf("columns" to result.columns, "rows" to result.rows, "rowCount" to result.rows.size))
            } catch (e: Exception) {
                ctx.status(HTTP_BAD_REQUEST).json(mapOf("error" to (e.message ?: "Query execution failed")))
            }
        }

        app.get("/api/cypher") { ctx ->
            val query = ctx.queryParam("query") ?: run { ctx.status(HTTP_BAD_REQUEST).result("Missing 'query' parameter"); return@get }
            try {
                val result = cypherExecutor.execute(query)
                ctx.json(mapOf("columns" to result.columns, "rows" to result.rows, "rowCount" to result.rows.size))
            } catch (e: Exception) {
                ctx.status(HTTP_BAD_REQUEST).json(mapOf("error" to (e.message ?: "Query execution failed")))
            }
        }
    }

    internal fun buildSubgraph(graph: Graph, center: NodeId, depth: Int): Map<String, Any> {
        val visitedNodes = mutableSetOf<Int>()
        val nodes = mutableListOf<Map<String, Any?>>()
        val edges = mutableListOf<Map<String, Any?>>()

        fun visit(nodeId: NodeId, remaining: Int) {
            if (!visitedNodes.add(nodeId.value) || remaining < 0) return
            val node = graph.node(nodeId) ?: return
            nodes.add(nodeToMap(node))

            if (remaining > 0) {
                for (edge in graph.outgoing(nodeId)) {
                    edges.add(edgeToMap(edge))
                    visit(edge.to, remaining - 1)
                }
                for (edge in graph.incoming(nodeId)) {
                    edges.add(edgeToMap(edge))
                    visit(edge.from, remaining - 1)
                }
            }
        }

        visit(center, depth)
        return mapOf("nodes" to nodes, "edges" to edges)
    }

    private fun resolveResourceEntry(graph: Graph, path: String): ResourceEntry? =
        graph.resources.list("**").firstOrNull { it.path == path }

    private fun listResources(graph: Graph, pattern: String, limit: Int): List<Map<String, Any?>> {
        return graph.resources.list(pattern)
            .map { entry ->
                mapOf("path" to entry.path, "source" to entry.source, "derived" to false)
            }
            .take(limit)
            .toList()
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

        private const val DEFAULT_ENTITY_LIMIT = 50
        private const val DEFAULT_RESOURCE_LIMIT = 100
        private const val DEFAULT_API_SPEC_LIMIT = 200
        private const val DEFAULT_OVERVIEW_LIMIT = 200
        private const val DEFAULT_SUBGRAPH_DEPTH = 2
    }

}
