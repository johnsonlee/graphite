package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.ResourceEntry
import io.johnsonlee.graphite.webgraph.GraphStore
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import picocli.CommandLine.*
import java.io.IOException
import java.nio.file.FileSystems
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

        // Block until shutdown
        Thread.currentThread().join()
        return 0
    }

    internal fun registerApiRoutes(app: Javalin, graph: Graph) {
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
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 50
            val nodeClass = resolveNodeType(type)
            val nodes = graph.nodes(nodeClass).take(limit).toList()
            ctx.json(nodes.map { nodeToMap(it) })
        }

        app.get("/api/node/{id}") { ctx ->
            val id = ctx.pathParam("id").toIntOrNull() ?: run { ctx.status(400).result("Invalid node ID"); return@get }
            val node = graph.node(NodeId(id)) ?: run { ctx.status(404).result("Node not found"); return@get }
            ctx.json(nodeToMap(node))
        }

        app.get("/api/node/{id}/outgoing") { ctx ->
            val id = ctx.pathParam("id").toIntOrNull() ?: run { ctx.status(400).result("Invalid node ID"); return@get }
            val edges = graph.outgoing(NodeId(id)).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }

        app.get("/api/node/{id}/incoming") { ctx ->
            val id = ctx.pathParam("id").toIntOrNull() ?: run { ctx.status(400).result("Invalid node ID"); return@get }
            val edges = graph.incoming(NodeId(id)).toList()
            ctx.json(edges.map { edgeToMap(it) })
        }

        app.get("/api/call-sites") { ctx ->
            val classPattern = ctx.queryParam("class")
            val methodPattern = ctx.queryParam("method")
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 50
            val pattern = MethodPattern(declaringClass = classPattern, name = methodPattern)
            val callSites = graph.callSites(pattern).take(limit).toList()
            ctx.json(callSites.map { nodeToMap(it) })
        }

        app.get("/api/methods") { ctx ->
            val classPattern = ctx.queryParam("class")
            val namePattern = ctx.queryParam("name")
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 50
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
            val className = ctx.queryParam("class") ?: run { ctx.status(400).result("Missing 'class' parameter"); return@get }
            val memberName = ctx.queryParam("member") ?: run { ctx.status(400).result("Missing 'member' parameter"); return@get }
            ctx.json(graph.memberAnnotations(className, memberName))
        }

        app.get("/api/resources") { ctx ->
            val pattern = ctx.queryParam("pattern") ?: "**"
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 100
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

        app.get("/api/resources/{path}*") { ctx ->
            val path = ctx.path().removePrefix("/api/resources/").trimStart('/')
            if (path.isBlank()) {
                ctx.status(404).result("Resource not found")
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
                ctx.status(404).result("Resource not found: $path")
            }
        }

        app.get("/api/api-spec") { ctx ->
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 200
            val classPattern = ctx.queryParam("class")
            val endpoints = extractApiSpec(graph)
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
            ctx.json(buildOpenApiSpec())
        }

        app.get("/swagger.json") { ctx ->
            ctx.json(buildOpenApiSpec())
        }

        app.get("/api/overview") { ctx ->
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 200
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

            val nodes = topClasses.mapIndexed { idx, cls ->
                val shortName = cls.substringAfterLast('.')
                mapOf(
                    "id" to cls.hashCode(),
                    "type" to "Class",
                    "label" to shortName,
                    "fullName" to cls,
                    "callSites" to (classCounts[cls] ?: 0)
                )
            }

            val nodeIdSet = topClasses.map { it.hashCode() }.toSet()
            val edges = classEdges.entries
                .filter { it.key.first.hashCode() in nodeIdSet && it.key.second.hashCode() in nodeIdSet }
                .map { (key, count) ->
                    mapOf(
                        "from" to key.first.hashCode(),
                        "to" to key.second.hashCode(),
                        "type" to "Call",
                        "weight" to count
                    )
                }

            ctx.json(mapOf("nodes" to nodes, "edges" to edges))
        }

        app.get("/api/subgraph") { ctx ->
            val centerId = ctx.queryParam("center")?.toIntOrNull() ?: run { ctx.status(400).result("Missing 'center' parameter"); return@get }
            val depth = ctx.queryParam("depth")?.toIntOrNull() ?: 2
            val subgraph = buildSubgraph(graph, NodeId(centerId), depth)
            ctx.json(subgraph)
        }

        app.post("/api/cypher") { ctx ->
            val body = ctx.body()
            val query = try {
                com.google.gson.JsonParser.parseString(body).asJsonObject.get("query").asString
            } catch (e: Exception) {
                ctx.queryParam("query") ?: run { ctx.status(400).result("Missing 'query' parameter"); return@post }
            }
            try {
                val executor = CypherExecutor(graph)
                val result = executor.execute(query)
                ctx.json(mapOf("columns" to result.columns, "rows" to result.rows, "rowCount" to result.rows.size))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("error" to (e.message ?: "Query execution failed")))
            }
        }

        app.get("/api/cypher") { ctx ->
            val query = ctx.queryParam("query") ?: run { ctx.status(400).result("Missing 'query' parameter"); return@get }
            try {
                val executor = CypherExecutor(graph)
                val result = executor.execute(query)
                ctx.json(mapOf("columns" to result.columns, "rows" to result.rows, "rowCount" to result.rows.size))
            } catch (e: Exception) {
                ctx.status(400).json(mapOf("error" to (e.message ?: "Query execution failed")))
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

    internal fun extractApiSpec(graph: Graph): List<Map<String, Any?>> {
        val mappingAnnotations = setOf(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
        )

        return graph.methods(MethodPattern())
            .flatMap { method ->
                val className = method.declaringClass.className
                val classAnnotations = graph.memberAnnotations(className, "<class>")
                val memberAnnotations = graph.memberAnnotations(className, method.name)
                val classBasePaths = extractPaths(classAnnotations["org.springframework.web.bind.annotation.RequestMapping"])
                val mappingEntries = memberAnnotations
                    .filterKeys { it in mappingAnnotations }
                    .entries

                mappingEntries.asSequence().flatMap { (annotationName, values) ->
                    val methodPaths = extractPaths(values)
                    val httpMethods = extractHttpMethods(annotationName, values)
                    combinePaths(classBasePaths, methodPaths).asSequence().flatMap { path ->
                        httpMethods.asSequence().map { httpMethod ->
                            mapOf(
                                "class" to className,
                                "member" to method.name,
                                "signature" to method.signature,
                                "httpMethod" to httpMethod,
                                "path" to path,
                                "annotation" to annotationName,
                                "returns" to method.returnType.className,
                                "parameters" to method.parameterTypes.map { it.className },
                                "annotations" to memberAnnotations.keys.sorted()
                            )
                        }
                    }
                }
            }
            .sortedWith(
                compareBy<Map<String, Any?>>(
                    { it["path"] as String },
                    { it["httpMethod"] as String },
                    { it["signature"] as String }
                )
            )
            .toList()
    }

    private fun resolveResourceEntry(graph: Graph, path: String): ResourceEntry? =
        graph.resources.list("**").firstOrNull { it.path == path }

    internal fun buildOpenApiSpec(): Map<String, Any?> = mapOf(
        "openapi" to "3.0.3",
        "info" to mapOf(
            "title" to "Graphite Explore API",
            "version" to "1.0.0",
            "description" to "Machine-readable API surface for Graphite Explore."
        ),
        "paths" to mapOf(
            "/api/info" to mapOf(
                "get" to operation(
                    "Get graph summary statistics",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("Graph statistics"))
                )
            ),
            "/api/nodes" to mapOf(
                "get" to operation(
                    "List graph nodes",
                    parameters = listOf(
                        queryParameter("type", "string", false, "Optional node label/type filter"),
                        queryParameter("limit", "integer", false, "Maximum number of nodes to return")
                    ),
                    responses = mapOf("200" to response("Node list"))
                )
            ),
            "/api/node/{id}" to mapOf(
                "get" to operation(
                    "Fetch a single node by id",
                    parameters = listOf(
                        pathParameter("id", "integer", "Node identifier")
                    ),
                    responses = mapOf(
                        "200" to response("Node payload"),
                        "400" to response("Invalid node id"),
                        "404" to response("Node not found")
                    )
                )
            ),
            "/api/node/{id}/outgoing" to mapOf(
                "get" to operation(
                    "List outgoing edges for a node",
                    parameters = listOf(pathParameter("id", "integer", "Node identifier")),
                    responses = mapOf(
                        "200" to response("Outgoing edges"),
                        "400" to response("Invalid node id")
                    )
                )
            ),
            "/api/node/{id}/incoming" to mapOf(
                "get" to operation(
                    "List incoming edges for a node",
                    parameters = listOf(pathParameter("id", "integer", "Node identifier")),
                    responses = mapOf(
                        "200" to response("Incoming edges"),
                        "400" to response("Invalid node id")
                    )
                )
            ),
            "/api/call-sites" to mapOf(
                "get" to operation(
                    "List call sites",
                    parameters = listOf(
                        queryParameter("class", "string", false, "Optional caller/callee class filter"),
                        queryParameter("method", "string", false, "Optional method name filter"),
                        queryParameter("limit", "integer", false, "Maximum number of results")
                    ),
                    responses = mapOf("200" to response("Call site list"))
                )
            ),
            "/api/methods" to mapOf(
                "get" to operation(
                    "List methods",
                    parameters = listOf(
                        queryParameter("class", "string", false, "Optional declaring class filter"),
                        queryParameter("name", "string", false, "Optional method name filter"),
                        queryParameter("limit", "integer", false, "Maximum number of results")
                    ),
                    responses = mapOf("200" to response("Method list"))
                )
            ),
            "/api/annotations" to mapOf(
                "get" to operation(
                    "Fetch member annotations",
                    parameters = listOf(
                        queryParameter("class", "string", true, "Declaring class name"),
                        queryParameter("member", "string", true, "Member name")
                    ),
                    responses = mapOf(
                        "200" to response("Annotation map"),
                        "400" to response("Missing required parameters")
                    )
                )
            ),
            "/api/resources" to mapOf(
                "get" to operation(
                    "List persisted resources",
                    parameters = listOf(
                        queryParameter("pattern", "string", false, "Glob pattern, defaults to **"),
                        queryParameter("limit", "integer", false, "Maximum number of results")
                    ),
                    responses = mapOf("200" to response("Resource listing"))
                )
            ),
            "/api/resources/{path}" to mapOf(
                "get" to operation(
                    "Read persisted raw resource content",
                    parameters = listOf(
                        pathParameter("path", "string", "Resource path inside the graph payload; may include nested segments")
                    ),
                    responses = mapOf(
                        "200" to response("Raw resource content"),
                        "404" to response("Resource not found")
                    )
                )
            ),
            "/api/api-spec" to mapOf(
                "get" to operation(
                    "Extract framework API endpoints from the graph",
                    parameters = listOf(
                        queryParameter("limit", "integer", false, "Maximum number of endpoints"),
                        queryParameter("class", "string", false, "Optional controller class filter")
                    ),
                    responses = mapOf("200" to response("Extracted framework API specification"))
                )
            ),
            "/api/overview" to mapOf(
                "get" to operation(
                    "Build a class-level overview graph",
                    parameters = listOf(
                        queryParameter("limit", "integer", false, "Maximum number of classes")
                    ),
                    responses = mapOf("200" to response("Overview graph"))
                )
            ),
            "/api/subgraph" to mapOf(
                "get" to operation(
                    "Build a local subgraph around a node",
                    parameters = listOf(
                        queryParameter("center", "integer", true, "Center node id"),
                        queryParameter("depth", "integer", false, "Traversal depth")
                    ),
                    responses = mapOf(
                        "200" to response("Subgraph"),
                        "400" to response("Missing or invalid parameters")
                    )
                )
            ),
            "/api/cypher" to mapOf(
                "get" to operation(
                    "Execute a Cypher query via query string",
                    parameters = listOf(
                        queryParameter("query", "string", true, "Cypher query text")
                    ),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query")
                    )
                ),
                "post" to operation(
                    "Execute a Cypher query via JSON body",
                    parameters = emptyList(),
                    requestBody = mapOf(
                        "required" to true,
                        "content" to mapOf(
                            "application/json" to mapOf(
                                "schema" to mapOf(
                                    "type" to "object",
                                    "properties" to mapOf(
                                        "query" to mapOf(
                                            "type" to "string",
                                            "description" to "Cypher query text"
                                        )
                                    ),
                                    "required" to listOf("query")
                                )
                            )
                        )
                    ),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query")
                    )
                )
            ),
            "/openapi.json" to mapOf(
                "get" to operation(
                    "Fetch this OpenAPI document",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("OpenAPI specification"))
                )
            ),
            "/swagger.json" to mapOf(
                "get" to operation(
                    "Fetch this API document via Swagger-compatible alias",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("OpenAPI specification"))
                )
            )
        )
    )

    private fun operation(
        summary: String,
        parameters: List<Map<String, Any?>>,
        responses: Map<String, Any?>,
        requestBody: Map<String, Any?>? = null
    ): Map<String, Any?> = buildMap {
        put("summary", summary)
        put("responses", responses)
        if (parameters.isNotEmpty()) put("parameters", parameters)
        if (requestBody != null) put("requestBody", requestBody)
    }

    private fun queryParameter(name: String, type: String, required: Boolean, description: String): Map<String, Any?> =
        parameter("query", name, type, required, description)

    private fun pathParameter(name: String, type: String, description: String): Map<String, Any?> =
        parameter("path", name, type, true, description)

    private fun parameter(location: String, name: String, type: String, required: Boolean, description: String): Map<String, Any?> =
        mapOf(
            "in" to location,
            "name" to name,
            "required" to required,
            "description" to description,
            "schema" to mapOf("type" to type)
        )

    private fun response(description: String): Map<String, Any?> =
        mapOf("description" to description)

    private fun listResources(graph: Graph, pattern: String, limit: Int): List<Map<String, Any?>> {
        return graph.resources.list(pattern)
            .map { entry ->
                mapOf("path" to entry.path, "source" to entry.source, "derived" to false)
            }
            .take(limit)
            .toList()
    }

    private fun extractPaths(annotationValues: Map<String, Any?>?): List<String> {
        val paths = extractStringValues(annotationValues?.get("path")) + extractStringValues(annotationValues?.get("value"))
        return if (paths.isEmpty()) listOf("/") else paths
    }

    private fun extractHttpMethods(annotationName: String, annotationValues: Map<String, Any?>): List<String> {
        return when (annotationName) {
            "org.springframework.web.bind.annotation.GetMapping" -> listOf("GET")
            "org.springframework.web.bind.annotation.PostMapping" -> listOf("POST")
            "org.springframework.web.bind.annotation.PutMapping" -> listOf("PUT")
            "org.springframework.web.bind.annotation.DeleteMapping" -> listOf("DELETE")
            "org.springframework.web.bind.annotation.PatchMapping" -> listOf("PATCH")
            else -> extractStringValues(annotationValues["method"]).ifEmpty { listOf("REQUEST") }
        }
    }

    private fun extractStringValues(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> listOf(value)
        is Iterable<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }

    private fun combinePaths(basePaths: List<String>, methodPaths: List<String>): List<String> {
        val bases = if (basePaths.isEmpty()) listOf("/") else basePaths
        val methods = if (methodPaths.isEmpty()) listOf("/") else methodPaths
        return bases.asSequence()
            .flatMap { base -> methods.asSequence().map { method -> normalizePath(base, method) } }
            .distinct()
            .toList()
    }

    private fun normalizePath(base: String, method: String): String {
        val basePart = base.trim().trim('/')
        val methodPart = method.trim().trim('/')
        return when {
            basePart.isEmpty() && methodPart.isEmpty() -> "/"
            basePart.isEmpty() -> "/$methodPart"
            methodPart.isEmpty() -> "/$basePart"
            else -> "/$basePart/$methodPart"
        }
    }

}
