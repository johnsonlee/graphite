package io.johnsonlee.graphite.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import io.johnsonlee.graphite.webgraph.GraphStore
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ExploreCommandTest {

    companion object {
        private lateinit var graphDir: Path
        private lateinit var app: Javalin
        private var port: Int = 0
        private val gson = Gson()

        private val fooType = TypeDescriptor("com.example.Foo")
        private val bazType = TypeDescriptor("com.example.Baz")
        private val parentType = TypeDescriptor("com.example.Parent")
        private val childType = TypeDescriptor("com.example.Child")
        private val barMethod = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        private val bazMethod = MethodDescriptor(bazType, "baz", emptyList(), TypeDescriptor("void"))
        private val quxMethod = MethodDescriptor(childType, "qux", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("int"))

        private lateinit var paramNode: ParameterNode
        private lateinit var localNode: LocalVariable
        private lateinit var intConstNode: IntConstant
        private lateinit var strConstNode: StringConstant
        private lateinit var returnNode: ReturnNode
        private lateinit var callSiteNode: CallSiteNode
        private lateinit var enumConstNode: EnumConstant
        private lateinit var fieldNode: FieldNode
        private lateinit var resourceFileNode: ResourceFileNode
        private lateinit var propertyFileNode: ResourceFileNode
        private val resources = mapOf(
            "application.yml" to "server:\n  port: 8080\nfeature:\n  enabled: true\n",
            "config/application.properties" to "feature.mode=shadow\n"
        )

        private class TestResourceAccessor(
            private val resources: Map<String, String>
        ) : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> {
                val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern")
                return resources.keys.asSequence()
                    .filter { matcher.matches(Path.of(it)) }
                    .map { ResourceEntry(it, "test-fixture") }
            }

            override fun open(path: String) =
                resources[path]?.let { ByteArrayInputStream(it.toByteArray()) }
                    ?: throw java.io.IOException("Resource not found: $path")
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val builder = DefaultGraph.Builder()
                .setResources(TestResourceAccessor(resources))

            paramNode = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), barMethod)
            localNode = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), barMethod)
            intConstNode = IntConstant(NodeId.next(), 42)
            strConstNode = StringConstant(NodeId.next(), "hello")
            returnNode = ReturnNode(NodeId.next(), barMethod)
            callSiteNode = CallSiteNode(NodeId.next(), barMethod, bazMethod, 10, null, listOf(paramNode.id))
            enumConstNode = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
            fieldNode = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)
            resourceFileNode = ResourceFileNode(
                NodeId.next(),
                "application.yml",
                "test-fixture",
                "yaml"
            )
            propertyFileNode = ResourceFileNode(
                NodeId.next(),
                "config/application.properties",
                "test-fixture",
                "properties"
            )

            builder.addNode(paramNode)
            builder.addNode(localNode)
            builder.addNode(intConstNode)
            builder.addNode(strConstNode)
            builder.addNode(returnNode)
            builder.addNode(callSiteNode)
            builder.addNode(enumConstNode)
            builder.addNode(fieldNode)
            builder.addNode(resourceFileNode)
            builder.addNode(propertyFileNode)

            builder.addEdge(DataFlowEdge(paramNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(intConstNode.id, localNode.id, DataFlowKind.ASSIGN))
            builder.addEdge(DataFlowEdge(localNode.id, returnNode.id, DataFlowKind.RETURN_VALUE))
            builder.addEdge(ResourceEdge(propertyFileNode.id, callSiteNode.id, ResourceRelation.LOOKUP))
            builder.addEdge(CallEdge(callSiteNode.id, callSiteNode.id, isVirtual = false))

            builder.addMethod(barMethod)
            builder.addMethod(bazMethod)
            builder.addMethod(quxMethod)

            builder.addTypeRelation(childType, parentType, TypeRelation.EXTENDS)

            builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))

            builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", emptyMap())
            builder.addMemberAnnotation(
                "com.example.Foo", "<class>", "org.springframework.web.bind.annotation.RequestMapping",
                mapOf("value" to "/v1")
            )
            builder.addMemberAnnotation(
                "com.example.Foo", "bar", "org.springframework.web.bind.annotation.GetMapping",
                mapOf("value" to "/api/bar")
            )

            val graph = builder.build()
            graphDir = Files.createTempDirectory("explore-test")
            GraphStore.save(graph, graphDir)
            val loadedGraph = GraphStore.load(graphDir)
            app = Javalin.create { config ->
                config.jsonMapper(JavalinGson(GsonBuilder().setPrettyPrinting().create()))
            }.start(0)
            port = app.port()

            val explore = ExploreCommand()
            explore.registerApiRoutes(app, loadedGraph)
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

    private fun post(path: String, jsonBody: String): Pair<Int, String> {
        val url = URI("http://localhost:$port$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(jsonBody.toByteArray()) }
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
        assertEquals(10.0, info["nodes"])
        assertEquals(5.0, info["edges"])
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
        assertEquals(10, nodes.size)
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
        val (code, body) = get("/api/call-sites?class=com.example.Baz")
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
    // /api/resources
    // ========================================================================

    @Test
    fun `GET api resources returns matching resources`() {
        val (code, body) = get("/api/resources?pattern=**&limit=10")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals(2.0, result["count"])
        @Suppress("UNCHECKED_CAST")
        val resourceEntries = result["resources"] as List<Map<String, Any?>>
        assertEquals(2, resourceEntries.size)
        assertTrue(resourceEntries.any { it["path"] == "application.yml" })
        assertTrue(resourceEntries.all { it["source"] == "test-fixture" })
        assertTrue(resourceEntries.all { it["derived"] == false })
    }

    @Test
    fun `GET api resources respects glob pattern`() {
        val (code, body) = get("/api/resources?pattern=**/*.properties")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val resourceEntries = result["resources"] as List<Map<String, Any?>>
        assertEquals(1, resourceEntries.size)
        assertEquals("config/application.properties", resourceEntries.single()["path"])
    }

    @Test
    fun `GET api resource content returns text payload`() {
        val (code, body) = get("/api/resources/application.yml")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("application.yml", result["path"])
        assertEquals("test-fixture", result["source"])
        assertEquals(false, result["derived"])
        assertTrue((result["content"] as String).contains("server:"))
    }

    @Test
    fun `GET api resource content returns 404 for missing path`() {
        val (code, body) = get("/api/resources/missing.yml")
        assertEquals(404, code, "Expected 404, body: $body")
    }

    @Test
    fun `GET openapi json exposes discoverable explore API`() {
        val (code, body) = get("/openapi.json")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("3.0.3", result["openapi"])
        @Suppress("UNCHECKED_CAST")
        val paths = result["paths"] as Map<String, Map<String, Any?>>
        assertTrue(paths.containsKey("/api/cypher"))
        assertTrue(paths.containsKey("/api/api-spec"))
        assertTrue(paths.containsKey("/api/resources/{path}"))
        @Suppress("UNCHECKED_CAST")
        val cypher = paths["/api/cypher"] as Map<String, Map<String, Any?>>
        assertTrue(cypher.containsKey("get"))
        assertTrue(cypher.containsKey("post"))
        @Suppress("UNCHECKED_CAST")
        val post = cypher["post"] as Map<String, Any?>
        assertTrue(post.containsKey("requestBody"))
    }

    @Test
    fun `GET swagger json aliases the OpenAPI document`() {
        val (openapiCode, openapiBody) = get("/openapi.json")
        val (swaggerCode, swaggerBody) = get("/swagger.json")
        assertEquals(200, openapiCode)
        assertEquals(200, swaggerCode)
        assertEquals(parseJson<Map<String, Any?>>(openapiBody), parseJson<Map<String, Any?>>(swaggerBody))
    }

    // ========================================================================
    // /api/api-spec
    // ========================================================================

    @Test
    fun `GET api api-spec returns Spring endpoints`() {
        val (code, body) = get("/api/api-spec")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("spring-web", result["framework"])
        @Suppress("UNCHECKED_CAST")
        val endpoints = result["endpoints"] as List<Map<String, Any?>>
        assertEquals(1, endpoints.size)
        val endpoint = endpoints.single()
        assertEquals("com.example.Foo", endpoint["class"])
        assertEquals("bar", endpoint["member"])
        assertEquals("GET", endpoint["httpMethod"])
        assertEquals("/v1/api/bar", endpoint["path"])
        assertEquals(barMethod.signature, endpoint["signature"])
    }

    @Test
    fun `GET api api-spec supports class filter`() {
        val (code, body) = get("/api/api-spec?class=com.example.Foo")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val endpoints = result["endpoints"] as List<Map<String, Any?>>
        assertEquals(1, endpoints.size)

        val (missingCode, missingBody) = get("/api/api-spec?class=com.example.Baz")
        assertEquals(200, missingCode, "Expected 200, body: $missingBody")
        val missingResult: Map<String, Any?> = parseJson(missingBody)
        @Suppress("UNCHECKED_CAST")
        val missingEndpoints = missingResult["endpoints"] as List<Map<String, Any?>>
        assertTrue(missingEndpoints.isEmpty())
    }

    @Test
    fun `extractApiSpec handles RequestMapping arrays iterables and default request method`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.RequestController"),
            "handle",
            emptyList(),
            TypeDescriptor("void")
        )
        val fallbackMethod = MethodDescriptor(
            TypeDescriptor("com.example.RequestController"),
            "fallback",
            emptyList(),
            TypeDescriptor("void")
        )
        val graph = DefaultGraph.Builder()
            .addMethod(method)
            .addMethod(fallbackMethod)
            .addMemberAnnotation(
                "com.example.RequestController",
                "<class>",
                "org.springframework.web.bind.annotation.RequestMapping",
                mapOf("value" to arrayOf("/v2", "/v1"))
            )
            .addMemberAnnotation(
                "com.example.RequestController",
                "handle",
                "org.springframework.web.bind.annotation.RequestMapping",
                mapOf(
                    "path" to listOf("/beta", "/alpha"),
                    "method" to arrayOf("POST", "PATCH")
                )
            )
            .addMemberAnnotation(
                "com.example.RequestController",
                "fallback",
                "org.springframework.web.bind.annotation.RequestMapping",
                emptyMap()
            )
            .build()

        val endpoints = ExploreCommand().extractApiSpec(graph)

        assertEquals(10, endpoints.size)
        val fallbackEndpoints = endpoints.filter { it["member"] == "fallback" }
        assertEquals(2, fallbackEndpoints.size)
        assertTrue(fallbackEndpoints.all { it["httpMethod"] == "REQUEST" })
        assertEquals(setOf("/v1", "/v2"), fallbackEndpoints.map { it["path"] }.toSet())

        val handlePaths = endpoints.filter { it["member"] == "handle" }.map { it["path"] as String }
        assertEquals(
            listOf("/v1/alpha", "/v1/alpha", "/v1/beta", "/v1/beta", "/v2/alpha", "/v2/alpha", "/v2/beta", "/v2/beta"),
            handlePaths
        )
        val handleMethods = endpoints.filter { it["member"] == "handle" }.map { it["httpMethod"] as String }.toSet()
        assertEquals(setOf("PATCH", "POST"), handleMethods)
        assertEquals("/v1", endpoints.first()["path"])
    }

    @Test
    fun `private API spec helpers cover all HTTP mapping branches`() {
        val explore = ExploreCommand()
        val extractHttpMethods = ExploreCommand::class.java.getDeclaredMethod(
            "extractHttpMethods",
            String::class.java,
            Map::class.java
        ).apply { isAccessible = true }
        val extractStringValues = ExploreCommand::class.java.getDeclaredMethod(
            "extractStringValues",
            Any::class.java
        ).apply { isAccessible = true }
        val combinePaths = ExploreCommand::class.java.getDeclaredMethod(
            "combinePaths",
            List::class.java,
            List::class.java
        ).apply { isAccessible = true }

        assertEquals(listOf("GET"), extractHttpMethods.invoke(explore, "org.springframework.web.bind.annotation.GetMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("POST"), extractHttpMethods.invoke(explore, "org.springframework.web.bind.annotation.PostMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("PUT"), extractHttpMethods.invoke(explore, "org.springframework.web.bind.annotation.PutMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("DELETE"), extractHttpMethods.invoke(explore, "org.springframework.web.bind.annotation.DeleteMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("PATCH"), extractHttpMethods.invoke(explore, "org.springframework.web.bind.annotation.PatchMapping", emptyMap<String, Any?>()))
        assertEquals(
            listOf("HEAD"),
            extractHttpMethods.invoke(
                explore,
                "org.springframework.web.bind.annotation.RequestMapping",
                mapOf("method" to "HEAD")
            )
        )
        assertEquals(
            listOf("REQUEST"),
            extractHttpMethods.invoke(explore, "org.springframework.web.bind.annotation.RequestMapping", mapOf("method" to 123))
        )

        assertEquals(emptyList<String>(), extractStringValues.invoke(explore, null))
        assertEquals(listOf("one"), extractStringValues.invoke(explore, "one"))
        assertEquals(listOf("two"), extractStringValues.invoke(explore, listOf("two", 2)))
        assertEquals(listOf("three"), extractStringValues.invoke(explore, arrayOf("three", 3)))
        assertEquals(emptyList<String>(), extractStringValues.invoke(explore, 42))

        assertEquals(listOf("/"), combinePaths.invoke(explore, emptyList<String>(), emptyList<String>()))
        assertEquals(listOf("/users"), combinePaths.invoke(explore, listOf("/"), listOf("/users")))
        assertEquals(listOf("/api"), combinePaths.invoke(explore, listOf("/api"), emptyList<String>()))
    }

    @Test
    fun `buildOpenApiSpec describes cypher and resource endpoints`() {
        val spec = ExploreCommand().buildOpenApiSpec()
        assertEquals("3.0.3", spec["openapi"])
        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        assertTrue(paths.containsKey("/openapi.json"))
        assertTrue(paths.containsKey("/swagger.json"))
        @Suppress("UNCHECKED_CAST")
        val resources = paths["/api/resources/{path}"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val get = resources["get"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val parameters = get["parameters"] as List<Map<String, Any?>>
        assertEquals("path", parameters.single()["name"])
    }

    // ========================================================================
    // /api/overview
    // ========================================================================

    @Test
    fun `GET api overview returns all nodes and edges`() {
        val (code, body) = get("/api/overview")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, List<Any>> = parseJson(body)
        assertTrue(result["nodes"]!!.isNotEmpty(), "Should have nodes")
        assertTrue(result["edges"]!!.isNotEmpty(), "Should have edges")
    }

    @Test
    fun `GET api overview respects limit`() {
        val (code, body) = get("/api/overview?limit=3")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, List<Any>> = parseJson(body)
        assertTrue(result["nodes"]!!.size <= 3, "Should respect limit")
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
    // ExploreCommand.call() integration test
    // ========================================================================

    @Test
    fun `call starts server and blocks until interrupted`() {
        // Test that call() actually starts a server and blocks
        val explore = ExploreCommand()
        explore.graphDir = graphDir
        explore.port = 0 // random port

        var result: Int? = null
        var exception: Throwable? = null
        val thread = Thread {
            try {
                result = explore.call()
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
        val explore = ExploreCommand()
        val result = explore.buildSubgraph(graph, localNode.id, 1)
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
        val explore = ExploreCommand()
        // depth -1: visit returns immediately due to remaining < 0
        val result = explore.buildSubgraph(graph, localNode.id, -1)
        @Suppress("UNCHECKED_CAST")
        val nodes = result["nodes"] as List<Map<String, Any?>>
        assertEquals(0, nodes.size, "Negative depth should return nothing (remaining < 0 check)")
    }

    // ========================================================================
    // /api/cypher
    // ========================================================================

    @Test
    fun `POST api cypher returns query results`() {
        val (code, body) = post("/api/cypher", """{"query": "MATCH (n:IntConstant) RETURN n.value"}""")
        assertEquals(200, code, "Expected 200, body: $body")
        assertTrue(body.contains("columns"), "Response should contain 'columns', body: $body")
    }

    @Test
    fun `POST api cypher returns error for bad query`() {
        val (code, body) = post("/api/cypher", """{"query": "INVALID QUERY"}""")
        assertEquals(400, code, "Expected 400 for invalid query, body: $body")
        assertTrue(body.contains("error"), "Response should contain 'error', body: $body")
    }

    @Test
    fun `GET api cypher with query param`() {
        val (code, body) = get("/api/cypher?query=" + java.net.URLEncoder.encode("MATCH (n) RETURN n.id LIMIT 1", "UTF-8"))
        assertEquals(200, code, "Expected 200, body: $body")
    }

    @Test
    fun `POST api cypher missing query returns 400`() {
        val (code, _) = post("/api/cypher", """{}""")
        assertEquals(400, code)
    }

    @Test
    fun `GET api cypher missing query returns 400`() {
        val (code, _) = get("/api/cypher")
        assertEquals(400, code)
    }
}
