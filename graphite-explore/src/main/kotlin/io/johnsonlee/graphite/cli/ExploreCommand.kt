package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.webgraph.GraphStore
import io.javalin.Javalin
import io.javalin.json.JavalinGson
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
}
