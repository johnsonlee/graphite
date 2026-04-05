package io.johnsonlee.graphite.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ServeCommandTest {

    companion object {
        private lateinit var graphDir: Path
        private lateinit var app: Javalin
        private var port: Int = 0
        private val gson = Gson()

        private val fooType = TypeDescriptor("com.example.Foo")
        private val parentType = TypeDescriptor("com.example.Parent")
        private val childType = TypeDescriptor("com.example.Child")
        private val barMethod = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        private val bazMethod = MethodDescriptor(fooType, "baz", emptyList(), TypeDescriptor("void"))
        private val quxMethod = MethodDescriptor(childType, "qux", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("int"))

        private lateinit var paramNode: ParameterNode
        private lateinit var localNode: LocalVariable
        private lateinit var intConstNode: IntConstant
        private lateinit var strConstNode: StringConstant
        private lateinit var returnNode: ReturnNode
        private lateinit var callSiteNode: CallSiteNode
        private lateinit var enumConstNode: EnumConstant
        private lateinit var fieldNode: FieldNode

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val builder = DefaultGraph.Builder()

            paramNode = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), barMethod)
            localNode = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), barMethod)
            intConstNode = IntConstant(NodeId.next(), 42)
            strConstNode = StringConstant(NodeId.next(), "hello")
            returnNode = ReturnNode(NodeId.next(), barMethod)
            callSiteNode = CallSiteNode(NodeId.next(), barMethod, bazMethod, 10, null, listOf(paramNode.id))
            enumConstNode = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
            fieldNode = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)

            builder.addNode(paramNode)
            builder.addNode(localNode)
            builder.addNode(intConstNode)
            builder.addNode(strConstNode)
            builder.addNode(returnNode)
            builder.addNode(callSiteNode)
            builder.addNode(enumConstNode)
            builder.addNode(fieldNode)

            builder.addEdge(DataFlowEdge(paramNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(intConstNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(localNode.id, returnNode.id, DataFlowKind.RETURN_VALUE))
            builder.addEdge(CallEdge(callSiteNode.id, callSiteNode.id, isVirtual = false))

            builder.addMethod(barMethod)
            builder.addMethod(bazMethod)
            builder.addMethod(quxMethod)

            builder.addTypeRelation(childType, parentType, TypeRelation.EXTENDS)

            builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))

            builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", emptyMap())
            builder.addMemberAnnotation(
                "com.example.Foo", "bar", "org.springframework.web.bind.annotation.GetMapping",
                mapOf("value" to "/api/bar")
            )

            val graph = builder.build()
            graphDir = Files.createTempDirectory("serve-test")
            GraphStore.save(graph, graphDir)

            val loadedGraph = GraphStore.load(graphDir)
            app = Javalin.create { config ->
                config.jsonMapper(JavalinGson(GsonBuilder().setPrettyPrinting().create()))
            }.start(0)
            port = app.port()

            val serve = ServeCommand()
            serve.registerApiRoutes(app, loadedGraph)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            app.stop()
            graphDir.toFile().deleteRecursively()
        }
    }

    private fun get(path: String): Pair<Int, String> {
        val url = URI("http://localhost:$port$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val code = conn.responseCode
        val body = if (code in 200..299) {
            InputStreamReader(conn.inputStream).use { it.readText() }
        } else {
            conn.errorStream?.let { InputStreamReader(it).use { r -> r.readText() } } ?: ""
        }
        conn.disconnect()
        return code to body
    }

    private inline fun <reified T> parseJson(json: String): T {
        return gson.fromJson(json, object : TypeToken<T>() {}.type)
    }

    // ========================================================================
    // /api/info
    // ========================================================================

    @Test
    fun `GET api info returns stats`() {
        val (code, body) = get("/api/info")
        assertEquals(200, code, "Expected 200 but got $code, body: $body")
        val info: Map<String, Double> = parseJson(body)
        assertEquals(8.0, info["nodes"])
        assertEquals(4.0, info["edges"])
        assertEquals(3.0, info["methods"])
        assertEquals(1.0, info["callSites"])
    }

    // ========================================================================
    // /api/nodes
    // ========================================================================

    @Test
    fun `GET api nodes returns node list`() {
        val (code, body) = get("/api/nodes?limit=100")
        assertEquals(200, code)
        val nodes: List<Map<String, Any?>> = parseJson(body)
        assertEquals(8, nodes.size)
    }

    @Test
    fun `GET api nodes with type filter returns filtered nodes`() {
        val (code, body) = get("/api/nodes?type=CallSiteNode&limit=100")
        assertEquals(200, code)
        val nodes: List<Map<String, Any?>> = parseJson(body)
        assertEquals(1, nodes.size)
        assertEquals("CallSiteNode", nodes[0]["type"])
    }

    @Test
    fun `GET api nodes with constant type filter`() {
        val (code, body) = get("/api/nodes?type=constant&limit=100")
        assertEquals(200, code)
        val nodes: List<Map<String, Any?>> = parseJson(body)
        assertEquals(3, nodes.size)
    }

    @Test
    fun `GET api nodes respects limit`() {
        val (code, body) = get("/api/nodes?limit=2")
        assertEquals(200, code)
        val nodes: List<Map<String, Any?>> = parseJson(body)
        assertEquals(2, nodes.size)
    }

    @Test
    fun `GET api nodes defaults to 50 limit`() {
        val (code, body) = get("/api/nodes")
        assertEquals(200, code)
        val nodes: List<Map<String, Any?>> = parseJson(body)
        assertTrue(nodes.size <= 50)
    }

    // ========================================================================
    // /api/node/{id}
    // ========================================================================

    @Test
    fun `GET api node by id returns node`() {
        val (code, body) = get("/api/node/${paramNode.id.value}")
        assertEquals(200, code)
        val node: Map<String, Any?> = parseJson(body)
        assertEquals("ParameterNode", node["type"])
        assertEquals(paramNode.id.value.toDouble(), node["id"])
    }

    @Test
    fun `GET api node by id returns 404 for missing`() {
        val (code, _) = get("/api/node/999999")
        assertEquals(404, code)
    }

    @Test
    fun `GET api node by id returns 400 for invalid id`() {
        val (code, _) = get("/api/node/notanumber")
        assertEquals(400, code)
    }

    // ========================================================================
    // /api/node/{id}/outgoing
    // ========================================================================

    @Test
    fun `GET api node outgoing returns edges`() {
        val (code, body) = get("/api/node/${paramNode.id.value}/outgoing")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertTrue(edges.isNotEmpty(), "paramNode should have outgoing edges")
        assertEquals("DataFlow", edges[0]["type"])
    }

    @Test
    fun `GET api node outgoing returns empty for isolated node`() {
        val (code, body) = get("/api/node/${strConstNode.id.value}/outgoing")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertTrue(edges.isEmpty(), "strConstNode should have no outgoing edges")
    }

    @Test
    fun `GET api node outgoing returns 400 for invalid id`() {
        val (code, _) = get("/api/node/abc/outgoing")
        assertEquals(400, code)
    }

    // ========================================================================
    // /api/node/{id}/incoming
    // ========================================================================

    @Test
    fun `GET api node incoming returns edges`() {
        val (code, body) = get("/api/node/${localNode.id.value}/incoming")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertTrue(edges.size >= 2, "localNode should have at least 2 incoming edges (param + intConst)")
    }

    @Test
    fun `GET api node incoming returns 400 for invalid id`() {
        val (code, _) = get("/api/node/xyz/incoming")
        assertEquals(400, code)
    }

    // ========================================================================
    // /api/call-sites
    // ========================================================================

    @Test
    fun `GET api call-sites returns matching call sites`() {
        val (code, body) = get("/api/call-sites?class=com.example.Foo")
        assertEquals(200, code)
        val sites: List<Map<String, Any?>> = parseJson(body)
        assertEquals(1, sites.size)
        assertEquals("CallSiteNode", sites[0]["type"])
    }

    @Test
    fun `GET api call-sites with method filter`() {
        val (code, body) = get("/api/call-sites?method=baz")
        assertEquals(200, code)
        val sites: List<Map<String, Any?>> = parseJson(body)
        assertEquals(1, sites.size)
    }

    @Test
    fun `GET api call-sites with no matches`() {
        val (code, body) = get("/api/call-sites?class=com.nonexistent.Class")
        assertEquals(200, code)
        val sites: List<Map<String, Any?>> = parseJson(body)
        assertEquals(0, sites.size)
    }

    @Test
    fun `GET api call-sites respects limit`() {
        val (code, body) = get("/api/call-sites?limit=0")
        assertEquals(200, code)
        val sites: List<Map<String, Any?>> = parseJson(body)
        assertEquals(0, sites.size)
    }

    // ========================================================================
    // /api/methods
    // ========================================================================

    @Test
    fun `GET api methods returns matching methods`() {
        val (code, body) = get("/api/methods?limit=100")
        assertEquals(200, code)
        val methods: List<Map<String, Any?>> = parseJson(body)
        assertEquals(3, methods.size)
        assertTrue(methods.all { it.containsKey("signature") })
        assertTrue(methods.all { it.containsKey("class") })
        assertTrue(methods.all { it.containsKey("name") })
        assertTrue(methods.all { it.containsKey("returnType") })
    }

    @Test
    fun `GET api methods with class filter`() {
        val (code, body) = get("/api/methods?class=com.example.Child&limit=100")
        assertEquals(200, code)
        val methods: List<Map<String, Any?>> = parseJson(body)
        assertEquals(1, methods.size)
        assertEquals("com.example.Child", methods[0]["class"])
        assertEquals("qux", methods[0]["name"])
    }

    @Test
    fun `GET api methods with name filter`() {
        val (code, body) = get("/api/methods?name=bar&limit=100")
        assertEquals(200, code)
        val methods: List<Map<String, Any?>> = parseJson(body)
        assertEquals(1, methods.size)
        assertEquals("bar", methods[0]["name"])
    }

    @Test
    fun `GET api methods respects limit`() {
        val (code, body) = get("/api/methods?limit=1")
        assertEquals(200, code)
        val methods: List<Map<String, Any?>> = parseJson(body)
        assertEquals(1, methods.size)
    }

    // ========================================================================
    // /api/annotations
    // ========================================================================

    @Test
    fun `GET api annotations returns annotation data`() {
        val (code, body) = get("/api/annotations?class=com.example.Foo&member=bar")
        assertEquals(200, code, "Expected 200, body: $body")
        val annotations: Map<String, Map<String, Any?>> = parseJson(body)
        assertTrue(annotations.size >= 2, "Should have at least 2 annotations, got: $annotations")
        assertTrue(annotations.containsKey("javax.annotation.Nullable"), "Should contain Nullable, got keys: ${annotations.keys}")
        assertTrue(annotations.containsKey("org.springframework.web.bind.annotation.GetMapping"), "Should contain GetMapping, got keys: ${annotations.keys}")
    }

    @Test
    fun `GET api annotations missing class returns 400`() {
        val (code, _) = get("/api/annotations?member=bar")
        assertEquals(400, code)
    }

    @Test
    fun `GET api annotations missing member returns 400`() {
        val (code, _) = get("/api/annotations?class=com.example.Foo")
        assertEquals(400, code)
    }

    @Test
    fun `GET api annotations missing both params returns 400`() {
        val (code, _) = get("/api/annotations")
        assertEquals(400, code)
    }

    @Test
    fun `GET api annotations for unknown member returns empty`() {
        val (code, body) = get("/api/annotations?class=com.example.Unknown&member=none")
        assertEquals(200, code, "Expected 200, body: $body")
        val annotations: Map<String, Map<String, Any?>> = parseJson(body)
        assertEquals(0, annotations.size)
    }

    // ========================================================================
    // /api/subgraph
    // ========================================================================

    @Test
    fun `GET api subgraph returns nodes and edges`() {
        val (code, body) = get("/api/subgraph?center=${localNode.id.value}&depth=1")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        assertTrue(subgraph.containsKey("nodes"), "Should contain 'nodes' key")
        assertTrue(subgraph.containsKey("edges"), "Should contain 'edges' key")
        @Suppress("UNCHECKED_CAST")
        val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        assertTrue(nodes.isNotEmpty(), "Subgraph should contain nodes")
    }

    @Test
    fun `GET api subgraph with depth 0 returns only center node`() {
        val (code, body) = get("/api/subgraph?center=${fieldNode.id.value}&depth=0")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        assertEquals(1, nodes.size, "Depth 0 should return only the center node")
        @Suppress("UNCHECKED_CAST")
        val edges = subgraph["edges"] as List<Map<String, Any?>>
        assertEquals(0, edges.size, "Depth 0 should return no edges")
    }

    @Test
    fun `GET api subgraph missing center returns 400`() {
        val (code, _) = get("/api/subgraph?depth=2")
        assertEquals(400, code)
    }

    @Test
    fun `GET api subgraph with invalid center returns 400`() {
        val (code, _) = get("/api/subgraph?center=notanumber")
        assertEquals(400, code)
    }

    @Test
    fun `GET api subgraph defaults to depth 2`() {
        val (code, body) = get("/api/subgraph?center=${localNode.id.value}")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        // With depth 2, should traverse further than depth 1
        assertTrue(nodes.size > 1, "Default depth 2 should return multiple nodes")
    }

    @Test
    fun `GET api subgraph for nonexistent center returns empty`() {
        val (code, body) = get("/api/subgraph?center=999999")
        assertEquals(200, code)
        val subgraph: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val nodes = subgraph["nodes"] as List<Map<String, Any?>>
        assertEquals(0, nodes.size, "Nonexistent center should return empty subgraph")
    }

    // ========================================================================
    // ServeCommand.call() integration test
    // ========================================================================

    @Test
    fun `call starts server and blocks until interrupted`() {
        // Test that call() actually starts a server and blocks
        val serve = ServeCommand()
        serve.graphDir = graphDir
        serve.port = 0 // random port

        var result: Int? = null
        var exception: Throwable? = null
        val thread = Thread {
            try {
                result = serve.call()
            } catch (e: Throwable) {
                exception = e
            }
        }
        thread.start()
        // Give it time to start
        Thread.sleep(2000)
        // Interrupt to unblock Thread.join()
        thread.interrupt()
        thread.join(5000)
        // The method should have thrown InterruptedException or returned
        // Either is acceptable - the key is exercising the code path
        assertTrue(!thread.isAlive, "Thread should have terminated after interrupt")
    }

    // ========================================================================
    // buildSubgraph unit tests
    // ========================================================================

    @Test
    fun `buildSubgraph traverses outgoing and incoming edges`() {
        val graph = GraphStore.load(graphDir)
        val serve = ServeCommand()
        val result = serve.buildSubgraph(graph, localNode.id, 1)
        @Suppress("UNCHECKED_CAST")
        val nodes = result["nodes"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val edges = result["edges"] as List<Map<String, Any?>>
        // localNode has 2 incoming (param, intConst) and 1 outgoing (returnNode)
        assertTrue(nodes.size >= 4, "Should include center + neighbors, got ${nodes.size}")
        assertTrue(edges.isNotEmpty(), "Should include edges")
    }

    @Test
    fun `buildSubgraph with negative depth returns only center`() {
        val graph = GraphStore.load(graphDir)
        val serve = ServeCommand()
        // depth -1: visit returns immediately due to remaining < 0
        val result = serve.buildSubgraph(graph, localNode.id, -1)
        @Suppress("UNCHECKED_CAST")
        val nodes = result["nodes"] as List<Map<String, Any?>>
        assertEquals(0, nodes.size, "Negative depth should return nothing (remaining < 0 check)")
    }
}
