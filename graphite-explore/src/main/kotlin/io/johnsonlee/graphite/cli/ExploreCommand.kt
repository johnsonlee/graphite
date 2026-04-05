package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.core.*
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
            val limit = ctx.queryParam("limit")?.toIntOrNull() ?: 5000
            val nodes = mutableListOf<Map<String, Any?>>()
            val edges = mutableListOf<Map<String, Any?>>()
            val nodeIds = mutableSetOf<Int>()

            graph.nodes(Node::class.java).take(limit).forEach { node ->
                nodes.add(nodeToMap(node))
                nodeIds.add(node.id.value)
            }
            for (id in nodeIds.toList()) {
                graph.outgoing(NodeId(id)).forEach { edge ->
                    if (edge.to.value in nodeIds) {
                        edges.add(edgeToMap(edge))
                    }
                }
            }
            ctx.json(mapOf("nodes" to nodes, "edges" to edges))
        }

        app.get("/api/subgraph") { ctx ->
            val centerId = ctx.queryParam("center")?.toIntOrNull() ?: run { ctx.status(400).result("Missing 'center' parameter"); return@get }
            val depth = ctx.queryParam("depth")?.toIntOrNull() ?: 2
            val subgraph = buildSubgraph(graph, NodeId(centerId), depth)
            ctx.json(subgraph)
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
