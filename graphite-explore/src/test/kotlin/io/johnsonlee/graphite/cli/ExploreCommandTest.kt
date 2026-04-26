package io.johnsonlee.graphite.cli

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.cli.c4.C4ModelInferer
import io.johnsonlee.graphite.cli.c4.C4StructurizrMapper
import io.johnsonlee.graphite.cli.c4.C4ViewLimits
import io.johnsonlee.graphite.cli.c4.diagramRelationshipLabel
import io.johnsonlee.graphite.cli.c4.externalArchitectureType
import io.johnsonlee.graphite.cli.c4.reduceDiagramTransitiveEdges
import io.johnsonlee.graphite.cli.c4.reduceSharedInternalFanIn
import io.johnsonlee.graphite.cli.c4.reduceSharedLibraryFanIn
import io.johnsonlee.graphite.cli.c4.reduceTransitiveContainerEdges
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.JavaArchiveLayout
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

    private fun get(path: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val url = URI("http://localhost:$port$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokeExplorePrivate(
        target: Any,
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?
    ): T {
        val method = runCatching { target::class.java.getDeclaredMethod(name, *parameterTypes) }
            .getOrElse {
                listOf(
                    "io.johnsonlee.graphite.cli.c4.SystemBoundaryDetectorKt",
                    "io.johnsonlee.graphite.cli.c4.ExternalSystemClassifierKt",
                    "io.johnsonlee.graphite.cli.c4.SubjectDetectorKt",
                    "io.johnsonlee.graphite.cli.c4.ContainerClustererKt",
                    "io.johnsonlee.graphite.cli.c4.ComponentSelectorKt"
                ).firstNotNullOfOrNull { className ->
                    runCatching {
                        Class.forName(className).getDeclaredMethod(name, *parameterTypes)
                    }.getOrNull()
                } ?: throw it
            }
        method.isAccessible = true
        val receiver = if (method.declaringClass == target::class.java) target else null
        return method.invoke(receiver, *args) as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokeDiagramPrivate(
        name: String,
        parameterTypes: Array<Class<*>>,
        vararg args: Any?
    ): T {
        val method = Class.forName("io.johnsonlee.graphite.cli.c4.C4RenderingPlanKt")
            .getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(null, *args) as T
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
    fun `GET api resource content supports nested paths`() {
        val (code, body) = get("/api/resources/config/application.properties")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("config/application.properties", result["path"])
        assertEquals("test-fixture", result["source"])
        assertTrue((result["content"] as String).contains("feature.mode=shadow"))
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
        val extractor = ApiSpecExtractor()
        val extractHttpMethods = ApiSpecExtractor::class.java.getDeclaredMethod(
            "extractHttpMethods",
            String::class.java,
            Map::class.java
        ).apply { isAccessible = true }
        val extractStringValues = ApiSpecExtractor::class.java.getDeclaredMethod(
            "extractStringValues",
            Any::class.java
        ).apply { isAccessible = true }
        val combinePaths = ApiSpecExtractor::class.java.getDeclaredMethod(
            "combinePaths",
            List::class.java,
            List::class.java
        ).apply { isAccessible = true }

        assertEquals(listOf("GET"), extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.GetMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("POST"), extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.PostMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("PUT"), extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.PutMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("DELETE"), extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.DeleteMapping", emptyMap<String, Any?>()))
        assertEquals(listOf("PATCH"), extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.PatchMapping", emptyMap<String, Any?>()))
        assertEquals(
            listOf("HEAD"),
            extractHttpMethods.invoke(
                extractor,
                "org.springframework.web.bind.annotation.RequestMapping",
                mapOf("method" to "HEAD")
            )
        )
        assertEquals(
            listOf("REQUEST"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.RequestMapping", mapOf("method" to 123))
        )

        assertEquals(emptyList<String>(), extractStringValues.invoke(extractor, null))
        assertEquals(listOf("one"), extractStringValues.invoke(extractor, "one"))
        assertEquals(listOf("two"), extractStringValues.invoke(extractor, listOf("two", 2)))
        assertEquals(listOf("three"), extractStringValues.invoke(extractor, arrayOf("three", 3)))
        assertEquals(emptyList<String>(), extractStringValues.invoke(extractor, 42))

        assertEquals(listOf("/"), combinePaths.invoke(extractor, emptyList<String>(), emptyList<String>()))
        assertEquals(listOf("/users"), combinePaths.invoke(extractor, listOf("/"), listOf("/users")))
        assertEquals(listOf("/api"), combinePaths.invoke(extractor, listOf("/api"), emptyList<String>()))
    }

    @Test
    fun `buildOpenApiSpec describes cypher and resource endpoints`() {
        val spec = ExploreCommand().buildOpenApiSpec()
        assertEquals("3.0.3", spec["openapi"])
        @Suppress("UNCHECKED_CAST")
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        assertTrue(paths.containsKey("/openapi.json"))
        assertTrue(paths.containsKey("/swagger.json"))
        assertTrue(paths.containsKey("/api/architecture/c4"))
        @Suppress("UNCHECKED_CAST")
        val resources = paths["/api/resources/{path}"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val get = resources["get"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val parameters = get["parameters"] as List<Map<String, Any?>>
        assertEquals("path", parameters.single()["name"])
        @Suppress("UNCHECKED_CAST")
        val c4 = paths["/api/architecture/c4"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val c4Get = c4["get"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val c4Parameters = c4Get["parameters"] as List<Map<String, Any?>>
        assertTrue(c4Parameters.any { it["name"] == "format" })
        assertTrue(c4Parameters.any { it["description"]?.toString()?.contains("dsl") == true })
        assertFalse(c4Parameters.any { it["name"] == "limit" })
    }

    // ========================================================================
    // /api/architecture/c4
    // ========================================================================

    @Test
    fun `GET api architecture c4 returns all views by default`() {
        val (code, body) = get("/api/architecture/c4")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("Graphite C4 Workspace", result["name"])
        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, String>
        assertEquals("all", properties["graphite.level"])
        @Suppress("UNCHECKED_CAST")
        val views = result["views"] as Map<String, Any?>
        assertTrue((views["systemContextViews"] as List<*>).isNotEmpty())
        assertTrue((views["containerViews"] as List<*>).isNotEmpty())
        assertTrue((views["componentViews"] as List<*>).isEmpty())
    }

    @Test
    fun `GET api architecture c4 returns context view`() {
        val (code, body) = get("/api/architecture/c4?level=context")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val properties = result["properties"] as Map<String, String>
        assertEquals("context", properties["graphite.level"])
        @Suppress("UNCHECKED_CAST")
        val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val contextViews = views["systemContextViews"] as List<Map<String, Any?>>
        assertEquals(1, contextViews.size)
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val people = model["people"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        val subject = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        val subjectId = subject["id"] as String
        assertTrue(subjectId == "system:application" || subjectId == "system:library")
        assertTrue(people.any { (it["id"] as? String)?.startsWith("person:") == true })
        assertTrue((((subject["properties"] as Map<*, *>)["graphite.responsibility"] as? String).isNullOrBlank()).not())
    }

    @Test
    fun `GET api architecture c4 returns container view`() {
        val (code, body) = get("/api/architecture/c4?level=container")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val containerViews = views["containerViews"] as List<Map<String, Any?>>
        assertEquals(1, containerViews.size)
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val application = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST")
        val containers = application["containers"] as List<Map<String, Any?>>
        assertEquals(
            0,
            containers.size,
            "C4 container level must not synthesize a runtime boundary for a library/package-only graph"
        )
    }

    @Test
    fun `GET api architecture c4 returns component view`() {
        val (code, body) = get("/api/architecture/c4?level=component")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST")
        val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val componentViews = views["componentViews"] as List<Map<String, Any?>>
        assertTrue(componentViews.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val application = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST")
        val containers = application["containers"] as List<Map<String, Any?>>
        val allComponents = containers.flatMap { (it["components"] as? List<Map<String, Any?>>).orEmpty() }
        assertTrue(allComponents.isEmpty())
    }

    @Test
    fun `GET api architecture c4 rejects invalid level`() {
        val (code, body) = get("/api/architecture/c4?level=invalid")
        assertEquals(400, code)
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("Invalid 'level' parameter", result["error"])
        @Suppress("UNCHECKED_CAST")
        val allowed = result["allowed"] as List<String>
        assertTrue(allowed.contains("all"))
    }

    @Test
    fun `GET api architecture c4 returns mermaid text`() {
        val (code, body) = get("/api/architecture/c4?level=context&format=mermaid")
        assertEquals(200, code, "Expected 200, body: $body")
        assertTrue(body.startsWith("graph TD"), "Expected Mermaid graph, body: $body")
        assertTrue(body.contains("system_"))
    }

    @Test
    fun `GET api architecture c4 returns plantuml text`() {
        val (code, body) = get("/api/architecture/c4?level=context&format=plantuml")
        assertEquals(200, code, "Expected 200, body: $body")
        assertTrue(body.startsWith("@startuml"), "Expected PlantUML document, body: $body")
        assertTrue(body.contains("system_") || body.contains("system:"))
        assertTrue(body.trimEnd().endsWith("@enduml"))
    }

    @Test
    fun `GET api architecture c4 returns structurizr dsl text`() {
        val (code, body) = get("/api/architecture/c4?level=context&format=dsl")
        assertEquals(200, code, "Expected 200, body: $body")
        assertTrue(body.startsWith("workspace \"Graphite C4 Workspace\""), "Expected Structurizr DSL workspace, body: $body")
        assertTrue(body.contains("model {"), "Expected Structurizr DSL model block, body: $body")
        assertTrue(body.contains("views {"), "Expected Structurizr DSL views block, body: $body")
        assertTrue(body.contains("systemContext"), "Expected Structurizr DSL system context view, body: $body")
    }

    @Test
    fun `GET api architecture c4 structured formats ignore diagram limits`() {
        val (jsonCode, jsonBody) = get("/api/architecture/c4?level=context&format=json&limit=0")
        assertEquals(200, jsonCode, "Expected 200, body: $jsonBody")
        val result: Map<String, Any?> = parseJson(jsonBody)
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        assertTrue(softwareSystems.isNotEmpty(), "Structured JSON must not be cropped by diagram limits")

        val (dslCode, dslBody) = get("/api/architecture/c4?level=context&format=dsl&limit=0")
        assertEquals(200, dslCode, "Expected 200, body: $dslBody")
        assertTrue(dslBody.contains("softwareSystem"), "Structured DSL must not be cropped by diagram limits")
    }

    @Test
    fun `GET api architecture c4 uses accept header before query format`() {
        val (plantCode, plantBody) = get(
            "/api/architecture/c4?level=context&format=json",
            mapOf("Accept" to "text/vnd.plantuml")
        )
        assertEquals(200, plantCode, "Expected 200, body: $plantBody")
        assertTrue(plantBody.startsWith("@startuml"), "Expected PlantUML document, body: $plantBody")

        val (jsonCode, jsonBody) = get(
            "/api/architecture/c4?level=context&format=plantuml",
            mapOf("Accept" to "application/vnd.structurizr+json")
        )
        assertEquals(200, jsonCode, "Expected 200, body: $jsonBody")
        assertTrue(jsonBody.trimStart().startsWith("{"), "Expected Structurizr JSON document, body: $jsonBody")

        val (dslCode, dslBody) = get(
            "/api/architecture/c4?level=context&format=json",
            mapOf("Accept" to "text/vnd.structurizr.dsl")
        )
        assertEquals(200, dslCode, "Expected 200, body: $dslBody")
        assertTrue(dslBody.startsWith("workspace \"Graphite C4 Workspace\""), "Expected Structurizr DSL document, body: $dslBody")
    }

    @Test
    fun `GET api architecture c4 container plantuml does not synthesize library runtime boundary`() {
        val (code, body) = get("/api/architecture/c4?level=container&format=plantuml")
        assertEquals(200, code, "Expected 200, body: $body")
        assertTrue(body.startsWith("@startuml"), "Expected PlantUML document, body: $body")
        assertFalse(body.contains("package \"Runtime Boundary\""), "Library-only graph must not synthesize runtime boundary, body: $body")
    }

    @Test
    fun `GET api architecture c4 renders every text format and level`() {
        val formats = listOf("mermaid", "plantuml")
        val levels = listOf("all", "context", "container", "component")

        formats.forEach { format ->
            levels.forEach { level ->
                val (code, body) = get("/api/architecture/c4?level=$level&format=$format")
                assertEquals(200, code, "Expected 200 for $level/$format, body: $body")
                when (format) {
                    "mermaid" -> assertTrue(body.contains("graph TD"), "Expected Mermaid graph for $level")
                    "plantuml" -> assertTrue(body.contains("@startuml") && body.contains("@enduml"), "Expected PlantUML document for $level")
                }
                if (level == "all") {
                    assertTrue(body.contains("Context"), "Expected context section in all/$format")
                    assertTrue(body.contains("Container"), "Expected container section in all/$format")
                    assertTrue(body.contains("Component"), "Expected component section in all/$format")
                }
            }
        }
    }

    @Test
    fun `GET api architecture c4 mermaid output stays under github edge limit`() {
        val edges = Regex("-->").findAll(get("/api/architecture/c4?level=context&format=mermaid").second).count()
        assertTrue(edges <= C4ViewLimits.MAX_TEXT_DIAGRAM_EDGES, "Expected Mermaid edges to be capped, got $edges")
    }

    @Test
    fun `GET api architecture c4 rejects invalid format`() {
        val (code, body) = get("/api/architecture/c4?format=invalid")
        assertEquals(400, code)
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("Invalid 'format' parameter", result["error"])
        @Suppress("UNCHECKED_CAST")
        val allowed = result["allowed"] as List<String>
        assertTrue(allowed.contains("json"))
        assertTrue(allowed.contains("dsl"))
        assertTrue(allowed.contains("mermaid"))
        assertTrue(allowed.contains("plantuml"))
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

    @Test
    fun `GET api overview uses stable class names as node ids`() {
        val (code, body) = get("/api/overview")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, List<Map<String, Any?>>> = parseJson(body)
        val nodes = result["nodes"].orEmpty()
        val edges = result["edges"].orEmpty()
        assertTrue(nodes.any { it["id"] == "com.example.Foo" && it["fullName"] == "com.example.Foo" })
        assertTrue(nodes.any { it["id"] == "com.example.Baz" && it["fullName"] == "com.example.Baz" })
        assertTrue(edges.any { it["from"] == "com.example.Foo" && it["to"] == "com.example.Baz" })
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

    @Test
    fun `buildC4Model does not invent containers or components for library-only graphs`() {
        val graph = GraphStore.load(graphDir)
        val explore = ExploreCommand()
        val result = explore.buildC4Model(graph, "all", 50)
        assertEquals("Graphite C4 Workspace", result["name"])
        @Suppress("UNCHECKED_CAST")
        val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val systemContextViews = views["systemContextViews"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val containerViews = views["containerViews"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val componentViews = views["componentViews"] as List<Map<String, Any?>>
        assertTrue(systemContextViews.isNotEmpty())
        assertTrue(containerViews.isNotEmpty())
        assertTrue(componentViews.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val application = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST")
        val containers = application["containers"] as List<Map<String, Any?>>
        assertTrue(containers.isEmpty())
    }

    @Test
    fun `buildC4Model groups external systems by artifact provenance when available`() {
        val internalType = TypeDescriptor("com.example.AppService")
        val internalMethod = MethodDescriptor(internalType, "run", emptyList(), TypeDescriptor("void"))
        val luceneWriterType = TypeDescriptor("org.apache.lucene.index.IndexWriter")
        val luceneReaderType = TypeDescriptor("org.apache.lucene.index.DirectoryReader")
        val logType = TypeDescriptor("org.apache.logging.log4j.Logger")
        val runtimeType = TypeDescriptor("java.util.List")
        val builder = DefaultGraph.Builder()

        builder.addMethod(internalMethod)
        builder.addNode(CallSiteNode(NodeId.next(), internalMethod, MethodDescriptor(luceneWriterType, "commit", emptyList(), TypeDescriptor("void")), 10, null, emptyList()))
        builder.addNode(CallSiteNode(NodeId.next(), internalMethod, MethodDescriptor(luceneReaderType, "open", emptyList(), luceneReaderType), 11, null, emptyList()))
        builder.addNode(CallSiteNode(NodeId.next(), internalMethod, MethodDescriptor(logType, "info", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void")), 12, null, emptyList()))
        builder.addNode(CallSiteNode(NodeId.next(), internalMethod, MethodDescriptor(runtimeType, "size", emptyList(), TypeDescriptor("int")), 13, null, emptyList()))
        builder.addClassOrigin(luceneWriterType.className, JavaArchiveLayout.bootInfLibEntry("lucene-core-9.11.1.jar"))
        builder.addClassOrigin(luceneReaderType.className, JavaArchiveLayout.bootInfLibEntry("lucene-core-9.11.1.jar"))
        builder.addClassOrigin(logType.className, JavaArchiveLayout.bootInfLibEntry("log4j-api-2.23.1.jar"))
        builder.addArtifactDependency("lucene-core-9.11.1", "log4j-api-2.23.1", 7)

        val explore = ExploreCommand()
        val result = explore.buildC4Model(builder.build(), "context", 50)
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        val names = softwareSystems.mapNotNull { it["name"] as? String }

        assertTrue(names.contains("lucene-core-9.11.1"))
        assertTrue(names.contains("log4j-api-2.23.1"))
        assertTrue(names.contains("Java Runtime"))
        assertFalse(names.contains("org.apache"))
        @Suppress("UNCHECKED_CAST")
        val luceneSystem = softwareSystems.first { it["name"] == "lucene-core-9.11.1" }
        @Suppress("UNCHECKED_CAST")
        val relationships = luceneSystem["relationships"] as List<Map<String, Any?>>
        assertTrue(
            relationships.any {
                    it["destinationId"] == "dependency:artifact:log4j-api-2.23.1"
            }
        )
    }

    @Test
    fun `buildC4Model infers external software systems from referenced absent classes`() {
        val appType = TypeDescriptor("com.acme.checkout.CheckoutService")
        val appMethod = MethodDescriptor(appType, "pay", emptyList(), TypeDescriptor("void"))
        val paymentType = TypeDescriptor("com.partner.payment.PaymentGateway")
        val paymentMethod = MethodDescriptor(paymentType, "charge", emptyList(), TypeDescriptor("void"))
        val graph = DefaultGraph.Builder()
            .addMethod(appMethod)
            .addNode(CallSiteNode(NodeId.next(), appMethod, paymentMethod, 20, null, emptyList()))
            .build()

        val workspace = ExploreCommand().buildC4Model(graph, "context", 50)
        @Suppress("UNCHECKED_CAST")
        val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val systems = model["softwareSystems"] as List<Map<String, Any?>>
        val external = systems.first { it["id"] == "dependency:namespace:com.partner.payment" }
        val properties = external["properties"] as Map<*, *>

        assertEquals("com.partner.payment", external["name"])
        assertEquals("external-system", properties["graphite.kind"])
        assertEquals("external-system", properties["graphite.architectureType"])
        assertTrue((properties["graphite.responsibility"] as? String).orEmpty().contains("external software system"))

        val service = C4ArchitectureService()
        assertTrue(service.renderMermaid(workspace).contains("External Systems"))
        assertTrue(service.renderPlantUml(workspace).contains("package \"External Systems\""))
    }

    @Test
    fun `C4 inferer derives artifact dependencies from graph evidence`() {
        val appType = TypeDescriptor("com.acme.App")
        val appMethod = MethodDescriptor(appType, "run", emptyList(), TypeDescriptor("void"))
        val guavaType = TypeDescriptor("com.google.common.collect.ImmutableList")
        val gsonType = TypeDescriptor("com.google.gson.Gson")
        val builder = DefaultGraph.Builder()
            .addMethod(appMethod)
            .addNode(CallSiteNode(NodeId.next(), appMethod, MethodDescriptor(guavaType, "of", emptyList(), TypeDescriptor("java.util.List")), 1, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), appMethod, MethodDescriptor(gsonType, "toJson", listOf(TypeDescriptor("java.lang.Object")), TypeDescriptor("java.lang.String")), 2, null, emptyList()))
        builder.addClassOrigin(guavaType.className, "lib/guava-32.1.3-jre.jar")
        builder.addClassOrigin(gsonType.className, "lib/gson-2.10.1.jar")
        builder.addArtifactDependency("guava-32.1.3-jre", "gson-2.10.1", 4)

        val inferred = C4ModelInferer().buildViewModel(builder.build(), "context", 50)
        @Suppress("UNCHECKED_CAST")
        val view = inferred["view"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val elements = view["elements"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val relationships = view["relationships"] as List<Map<String, Any?>>

        assertTrue(elements.any { it["id"] == "dependency:artifact:guava-32.1.3-jre" && it["source"] == "artifact" })
        assertTrue(elements.any { it["id"] == "dependency:artifact:gson-2.10.1" && it["source"] == "artifact" })
        assertTrue(
            relationships.any {
                it["from"] == "dependency:artifact:guava-32.1.3-jre" &&
                    it["to"] == "dependency:artifact:gson-2.10.1" &&
                    it["kind"] == "builds-on"
            }
        )
    }

    @Test
    fun `buildC4Model detects Spring Boot app subject from Start-Class artifact`() {
        val startType = TypeDescriptor("com.example.boot.StartApplication")
        val serviceType = TypeDescriptor("com.example.boot.Service")
        val mainMethod = MethodDescriptor(startType, "main", listOf(TypeDescriptor("java.lang.String[]")), TypeDescriptor("void"))
        val serviceMethod = MethodDescriptor(serviceType, "run", emptyList(), TypeDescriptor("void"))
        val graph = DefaultGraph.Builder()
            .setResources(
                TestResourceAccessor(
                    mapOf(
                        JavaArchiveLayout.META_INF_MANIFEST to """
                            Manifest-Version: 1.0
                            ${JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE}: ${JavaArchiveLayout.SPRING_BOOT_LAUNCH_JAR_LAUNCHER}
                            ${JavaArchiveLayout.START_CLASS_ATTRIBUTE}: com.example.boot.StartApplication
                            
                        """.trimIndent()
                    )
                )
            )
            .addMethod(mainMethod)
            .addMethod(serviceMethod)
            .addNode(CallSiteNode(NodeId.next(), mainMethod, serviceMethod, 1, null, emptyList()))
            .apply {
                addClassOrigin(startType.className, JavaArchiveLayout.bootInfLibEntry("my-app.jar"))
                addClassOrigin(serviceType.className, JavaArchiveLayout.bootInfLibEntry("my-app.jar"))
            }
            .build()

        val explore = ExploreCommand()
        val result = explore.buildC4Model(graph, "context", 50)
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        val subject = softwareSystems.first { it["id"] == "system:application" }

        assertEquals("My App", subject["name"])
    }

    @Test
    fun `buildC4Model ignores runtime calls when inferring container integration kind`() {
        val mainType = TypeDescriptor("com.example.runtime.Main")
        val mainMethod = MethodDescriptor(mainType, "main", listOf(TypeDescriptor("java.lang.String[]")), TypeDescriptor("void"))
        val internalType = TypeDescriptor("com.example.runtime.Api")
        val internalMethod = MethodDescriptor(internalType, "run", emptyList(), TypeDescriptor("void"))
        val runtimeType = TypeDescriptor("java.util.List")
        val runtimeMethod = MethodDescriptor(runtimeType, "size", emptyList(), TypeDescriptor("int"))
        val graph = DefaultGraph.Builder()
            .addMethod(mainMethod)
            .addMethod(internalMethod)
            .addNode(CallSiteNode(NodeId.next(), mainMethod, internalMethod, 1, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), internalMethod, runtimeMethod, 1, null, emptyList()))
            .build()

        val result = ExploreCommand().buildC4Model(graph, "container", 50)
        @Suppress("UNCHECKED_CAST")
        val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val subject = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST")
        val containers = subject["containers"] as List<Map<String, Any?>>
        val kinds = containers.mapNotNull { (it["properties"] as? Map<*, *>)?.get("graphite.kind") as? String }

        assertTrue(kinds.isNotEmpty())
        assertFalse(kinds.contains("integration"), "Runtime-only calls must not imply integration kind: $kinds")
    }

    @Test
    fun `C4 edge reducers keep strong direct evidence and trim shared fan-in`() {
        fun edge(from: String, to: String, weight: Int, kind: String = "uses") = mapOf(
            "from" to from,
            "to" to to,
            "weight" to weight,
            "kind" to kind,
            "description" to kind
        )

        val transitive = listOf(
            edge("container:a", "container:b", 10),
            edge("container:b", "container:c", 10),
            edge("container:a", "container:c", 5),
            edge("container:a", "container:d", 10),
            edge("container:b", "container:d", 1)
        )
        val reducedTransitive = reduceTransitiveContainerEdges(transitive)
        assertFalse(reducedTransitive.any { it["from"] == "container:a" && it["to"] == "container:c" })
        assertTrue(reducedTransitive.any { it["from"] == "container:a" && it["to"] == "container:d" })

        val diagramTransitive = reduceDiagramTransitiveEdges(transitive)
        assertTrue(diagramTransitive.any { it["from"] == "container:a" && it["to"] == "container:d" })
        val runtimeTransitive = listOf(
            edge("dependency:library:lucene", "dependency:artifact:log4j-api", 3, "builds-on"),
            edge("dependency:artifact:log4j-api", "dependency:runtime:java", 1, "runs-on"),
            edge("dependency:library:lucene", "dependency:runtime:java", 1, "runs-on")
        )
        val reducedRuntime = reduceDiagramTransitiveEdges(runtimeTransitive)
        assertFalse(reducedRuntime.any { it["from"] == "dependency:library:lucene" && it["to"] == "dependency:runtime:java" })

        val libraryFanIn = listOf(
            edge("container:a", "dependency:artifact:shared", 5, "depends-on"),
            edge("container:b", "dependency:artifact:shared", 10, "depends-on"),
            edge("container:c", "dependency:artifact:shared", 1, "depends-on"),
            edge("dependency:artifact:shared", "dependency:runtime:java", 1, "runs-on")
        )
        val reducedLibrary = reduceSharedLibraryFanIn(libraryFanIn)
        assertEquals(3, reducedLibrary.size)
        assertFalse(reducedLibrary.any { it["from"] == "container:c" })
        assertTrue(reducedLibrary.any { it["kind"] == "runs-on" })

        val internalFanIn = listOf(
            edge("container:a", "container:shared", 1),
            edge("container:b", "container:shared", 5),
            edge("container:c", "container:shared", 2),
            edge("container:d", "container:shared", 4)
        )
        val reducedInternal = reduceSharedInternalFanIn(internalFanIn)
        assertEquals(3, reducedInternal.size)
        assertFalse(reducedInternal.any { it["from"] == "container:a" })
    }

    @Test
    fun `C4 classifier helpers cover runtime namespace and responsibility branches`() {
        val inferer = C4ModelInferer()
        val intType = Int::class.javaPrimitiveType!!
        val graphStringTypes = arrayOf<Class<*>>(Graph::class.java, String::class.java)
        val graphStringListTypes = arrayOf<Class<*>>(Graph::class.java, String::class.java, List::class.java)
        val listType = arrayOf<Class<*>>(List::class.java)
        val stringType = arrayOf<Class<*>>(String::class.java)
        val fourIntTypes = arrayOf<Class<*>>(intType, intType, intType, intType)
        val fiveIntTypes = arrayOf<Class<*>>(intType, intType, intType, intType, intType)
        val twoStringTypes = arrayOf<Class<*>>(String::class.java, String::class.java)
        val threeStringTypes = arrayOf<Class<*>>(String::class.java, String::class.java, String::class.java)
        val graphListTypes = arrayOf<Class<*>>(Graph::class.java, List::class.java)
        val listListTypes = arrayOf<Class<*>>(List::class.java, List::class.java)
        val setStringListTypes = arrayOf<Class<*>>(Set::class.java, String::class.java, List::class.java)
        val setMapMapStringTypes = arrayOf<Class<*>>(Set::class.java, Map::class.java, Map::class.java, String::class.java)
        val containerPairTypes = arrayOf<Class<*>>(String::class.java, String::class.java, String::class.java, String::class.java)
        val componentPairTypes = arrayOf<Class<*>>(
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        )
        val componentResponsibilityTypes = arrayOf<Class<*>>(String::class.java, intType, intType, intType, intType)

        assertEquals(
            "runtime:java",
            invokeExplorePrivate<String>(inferer, "externalSystemKey", graphStringTypes, null, "java.util.List")
        )
        assertEquals(
            "runtime:kotlin",
            invokeExplorePrivate<String>(inferer, "externalSystemKey", graphStringTypes, null, "kotlin.String")
        )
        assertEquals(
            "runtime:scala",
            invokeExplorePrivate<String>(inferer, "externalSystemKey", graphStringTypes, null, "scala.Option")
        )
        assertEquals(
            "namespace:joptsimple",
            invokeExplorePrivate<String>(inferer, "externalSystemKey", graphStringTypes, null, "joptsimple.OptionParser")
        )
        assertEquals(
            "org",
            invokeExplorePrivate<String>(inferer, "namespaceGroup", stringType, "org.Foo.Bar")
        )
        assertEquals(
            "",
            invokeExplorePrivate<String>(inferer, "namespaceGroup", stringType, "")
        )
        assertEquals(
            "alpha",
            invokeExplorePrivate<String>(inferer, "namespaceGroup", stringType, "alpha.beta.gamma.delta")
        )

        assertEquals("Java Runtime", invokeExplorePrivate<String>(inferer, "externalSystemName", graphStringListTypes, null, "runtime:java", emptyList<String>()))
        assertEquals("Kotlin Runtime", invokeExplorePrivate<String>(inferer, "externalSystemName", graphStringListTypes, null, "runtime:kotlin", emptyList<String>()))
        assertEquals("Scala Runtime", invokeExplorePrivate<String>(inferer, "externalSystemName", graphStringListTypes, null, "runtime:scala", emptyList<String>()))
        assertEquals("lucene-core-9.12.0", invokeExplorePrivate<String>(inferer, "externalSystemName", graphStringListTypes, null, "artifact:lucene-core-9.12.0", emptyList<String>()))
        assertEquals("org.apache.lucene", invokeExplorePrivate<String>(inferer, "externalSystemName", graphStringListTypes, null, "namespace:org.apache.lucene", emptyList<String>()))
        val originFallbackGraph = DefaultGraph.Builder()
            .apply { addClassOrigin("custom.External", "lib/custom-client.jar") }
            .build()
        assertEquals("lib/custom-client.jar", invokeExplorePrivate<String>(inferer, "externalSystemName", graphStringListTypes, originFallbackGraph, "opaque", listOf("custom.External")))
        assertEquals("artifact", invokeExplorePrivate<String>(inferer, "externalSystemSource", stringType, "artifact:lucene-core"))
        assertEquals("runtime", invokeExplorePrivate<String>(inferer, "externalSystemSource", stringType, "runtime:java"))
        assertEquals("namespace", invokeExplorePrivate<String>(inferer, "externalSystemSource", stringType, "namespace:org.apache"))
        assertEquals("runtime", invokeExplorePrivate<String>(inferer, "externalSystemKind", stringType, "runtime:java"))
        assertEquals("library", invokeExplorePrivate<String>(inferer, "externalSystemKind", stringType, "artifact:lucene-core"))
        assertEquals("external-system", invokeExplorePrivate<String>(inferer, "externalSystemKind", stringType, "namespace:org.apache"))
        assertEquals("high", invokeExplorePrivate<String>(inferer, "externalSystemConfidence", stringType, "artifact:lucene-core"))
        assertEquals("high", invokeExplorePrivate<String>(inferer, "externalSystemConfidence", stringType, "runtime:java"))
        assertEquals("medium", invokeExplorePrivate<String>(inferer, "externalSystemConfidence", stringType, "namespace:org.apache"))
        assertEquals(
            "Provides language and platform runtime services used by the application",
            invokeExplorePrivate<String>(inferer, "externalSystemResponsibility", twoStringTypes, "runtime", "runtime:java")
        )
        assertEquals(
            "Provides reusable library capabilities linked from the application runtime",
            invokeExplorePrivate<String>(inferer, "externalSystemResponsibility", twoStringTypes, "library", "artifact:lucene-core")
        )
        assertEquals(
            "Represents an inferred external software system boundary grouped from referenced classes",
            invokeExplorePrivate<String>(inferer, "externalSystemResponsibility", twoStringTypes, "external-system", "namespace:org.apache")
        )
        assertEquals(
            "Language and platform runtime supporting the subject system",
            invokeExplorePrivate<String>(inferer, "externalSystemDescription", stringType, "runtime")
        )
        assertEquals(
            "External collaborator inferred from code graph evidence",
            invokeExplorePrivate<String>(inferer, "externalSystemDescription", stringType, "other")
        )
        assertEquals(
            "Search",
            invokeExplorePrivate<String>(inferer, "inferSubjectName", threeStringTypes, "com.acme.search", null, "com.acme.SearchApp")
        )
        assertEquals("application-service", invokeExplorePrivate<String>(inferer, "inferContainerArchitectureType", stringType, "application-runtime"))
        assertEquals("application-service", invokeExplorePrivate<String>(inferer, "inferContainerArchitectureType", stringType, "interface"))
        assertEquals("application-component", invokeExplorePrivate<String>(inferer, "inferContainerArchitectureType", stringType, "capability"))
        assertEquals("application-service", invokeExplorePrivate<String>(inferer, "inferComponentArchitectureType", stringType, "coordination"))
        assertEquals("application-component", invokeExplorePrivate<String>(inferer, "inferComponentArchitectureType", stringType, "domain-component"))
        assertEquals("runtime-platform", externalArchitectureType("runtime"))
        assertEquals("external-library", externalArchitectureType("library"))
        assertEquals("external-system", externalArchitectureType("external-system"))

        assertEquals("interface", invokeExplorePrivate<String>(inferer, "inferContainerKind", fourIntTypes, 1, 0, 0, 0))
        assertEquals("integration", invokeExplorePrivate<String>(inferer, "inferContainerKind", fourIntTypes, 0, 1, 1, 3))
        assertEquals("orchestrator", invokeExplorePrivate<String>(inferer, "inferContainerKind", fourIntTypes, 0, 1, 3, 0))
        assertEquals("shared-capability", invokeExplorePrivate<String>(inferer, "inferContainerKind", fourIntTypes, 0, 3, 1, 0))
        assertEquals("capability", invokeExplorePrivate<String>(inferer, "inferContainerKind", fourIntTypes, 0, 1, 1, 0))
        assertTrue(invokeExplorePrivate<String>(inferer, "containerDescription", stringType, "capability").contains("Internal capability"))
        assertNull(invokeExplorePrivate<String?>(inferer, "operationalContainerResponsibility", stringType, "capability"))
        assertEquals(2, invokeExplorePrivate<Int>(inferer, "containerDependencyLayerRank", stringType, "capability"))
        assertTrue(
            invokeExplorePrivate<String>(
                inferer,
                "buildContainerRationale",
                setStringListTypes,
                setOf("com.acme.api", "com.acme.service"),
                "com.acme.api",
                emptyList<String>()
            ).contains("mutually dependent")
        )
        assertEquals(
            "Api and Service",
            invokeExplorePrivate<String>(
                inferer,
                "inferContainerName",
                setMapMapStringTypes,
                linkedSetOf("com.acme.api", "com.acme.service"),
                linkedMapOf("com.acme.api" to 10, "com.acme.service" to 10),
                mapOf("api" to 1, "service" to 1),
                "com.acme"
            )
        )
        assertEquals("entrypoint", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 1, 0, 0, 0))
        assertEquals("integration", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 0, 0, 2))
        assertEquals("orchestrator", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 0, 2, 0))
        assertEquals("shared-capability", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 2, 0, 0))
        assertEquals("coordination", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 1, 1, 0))
        assertEquals("domain-component", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 0, 0, 0))

        assertEquals("routes-to", invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "interface", "capability"))
        assertEquals("orchestrates", invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "orchestrator", "capability"))
        assertEquals("uses", invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "capability", "shared-capability"))
        assertEquals("uses", invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "capability", "integration"))
        assertEquals("collaborates-with", invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "capability", "capability"))
        assertEquals(
            "container:transport" to "container:core",
            invokeExplorePrivate<Pair<String, String>>(
                inferer,
                "canonicalContainerRelationshipPair",
                containerPairTypes,
                "container:core",
                "container:transport",
                "shared-capability",
                "orchestrator"
            )
        )
        assertEquals(
            "container:transport" to "container:core",
            invokeExplorePrivate<Pair<String, String>>(
                inferer,
                "canonicalContainerRelationshipPair",
                containerPairTypes,
                "container:transport",
                "container:core",
                "orchestrator",
                "shared-capability"
            )
        )
        assertEquals(
            "component:IndexShard" to "component:ActionListener",
            invokeExplorePrivate<Pair<String, String>>(
                inferer,
                "canonicalComponentRelationshipPair",
                componentPairTypes,
                "component:ActionListener",
                "component:IndexShard",
                "shared-capability",
                "shared-capability",
                "shared-capability",
                "orchestrator"
            )
        )
        assertEquals(
            "component:SearchService" to "component:IndicesService",
            invokeExplorePrivate<Pair<String, String>>(
                inferer,
                "canonicalComponentRelationshipPair",
                componentPairTypes,
                "component:SearchService",
                "component:IndicesService",
                "orchestrator",
                "orchestrator",
                "orchestrator",
                "orchestrator"
            )
        )
        val dependencyBoundaryGraph = DefaultGraph.Builder()
            .apply { addArtifactDependency("client-a", "missing-b", 1) }
            .build()
        val dependencyBoundary = invokeExplorePrivate<Set<String>>(
            inferer,
            "selectRuntimeBoundaryLibraryIds",
            graphListTypes,
            dependencyBoundaryGraph,
            listOf(mapOf("id" to "dependency:artifact:client-a", "kind" to "library"))
        )
        assertEquals(setOf("dependency:artifact:client-a"), dependencyBoundary)
        val callsiteOnlyMethod = MethodDescriptor(TypeDescriptor("com.callsites.Root"), "run", emptyList(), TypeDescriptor("void"))
        val callsiteOnlyTarget = MethodDescriptor(TypeDescriptor("java.util.List"), "size", emptyList(), TypeDescriptor("int"))
        assertEquals(
            "com.callsites",
            invokeExplorePrivate<String>(
                inferer,
                "deriveSystemBoundary",
                listListTypes,
                emptyList<MethodDescriptor>(),
                listOf(CallSiteNode(NodeId.next(), callsiteOnlyMethod, callsiteOnlyTarget, 1, null, emptyList()))
            )
        )

        assertTrue(invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 1, 0, 0, 0, 0).contains("inbound system interface"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 1, 1, 4).contains("outward-facing capability"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 4, 1, 0).contains("shared internal capability"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 1, 4, 0).contains("orchestration boundary"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 2, 2, 0).contains("balanced internal collaboration"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 0, 0, 0, 0).contains("cohesive internal capability"))

        assertTrue(invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 1, 0, 0, 0).contains("external requests"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 0, 0, 3).contains("external collaborators"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 0, 3, 0).contains("Coordinates work"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 3, 0, 0).contains("shared internal capability"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 2, 2, 0).contains("coordination path"))
        assertTrue(invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 0, 0, 0).contains("structurally central"))

        assertEquals("Api routes work to Service", invokeExplorePrivate<String>(inferer, "describeArchitecturalDependency", threeStringTypes, "routes-to", "Api", "Service"))
        assertEquals("Api orchestrates Service", invokeExplorePrivate<String>(inferer, "describeArchitecturalDependency", threeStringTypes, "orchestrates", "Api", "Service"))
        assertEquals("Api uses Service", invokeExplorePrivate<String>(inferer, "describeArchitecturalDependency", threeStringTypes, "uses", "Api", "Service"))
        assertEquals("Api collaborates with Service", invokeExplorePrivate<String>(inferer, "describeArchitecturalDependency", threeStringTypes, "collaborates-with", "Api", "Service"))
        fun componentRelationship(from: String?, to: String?, kind: String = "uses", weight: Int = 1) = mapOf(
            "from" to from,
            "to" to to,
            "kind" to kind,
            "weight" to weight,
            "description" to kind
        )
        assertEquals(
            1,
            invokeExplorePrivate<List<Map<String, Any?>>>(
                inferer,
                "selectReadableComponentRelationships",
                listType,
                listOf(componentRelationship("component:a", "component:b"))
            ).size
        )
        val fallbackSelected = invokeExplorePrivate<List<Map<String, Any?>>>(
            inferer,
            "selectReadableComponentRelationships",
            listType,
            (1..8).map { index ->
                componentRelationship("component:source", "component:target$index", weight = 20 - index)
            } + listOf(
                componentRelationship(null, "component:missing-source", weight = 1),
                componentRelationship("component:missing-target", null, weight = 1)
            )
        )
        assertTrue(fallbackSelected.size >= 6)
        assertTrue(fallbackSelected.size <= 12)
        assertFalse(fallbackSelected.any { it["from"] == null || it["to"] == null })
        val collaborationOnlySelected = invokeExplorePrivate<List<Map<String, Any?>>>(
            inferer,
            "selectReadableComponentRelationships",
            listType,
            listOf(
                componentRelationship("component:a", "component:b", "collaborates-with", 2),
                componentRelationship("component:b", "component:c", "collaborates-with", 1)
            )
        )
        assertEquals(2, collaborationOnlySelected.size)

        assertEquals("(default)", invokeExplorePrivate<String>(inferer, "internalPackageUnit", twoStringTypes, "NoPackage", "com.example"))
        assertEquals("com.example.api", invokeExplorePrivate<String>(inferer, "internalPackageUnit", twoStringTypes, "com.example.api.Controller", "com.example"))
        assertEquals("org.apache", invokeExplorePrivate<String>(inferer, "internalPackageUnit", twoStringTypes, "org.apache.lucene.IndexWriter", "com.example"))

        val fallbackModel = inferer.buildViewModel(DefaultGraph.Builder().addMethod(callsiteOnlyMethod).build(), "unknown", 5)
        assertEquals("unknown", fallbackModel["level"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("context", (fallbackModel["view"] as Map<String, Any?>)["type"])

        val utilMethod = MethodDescriptor(TypeDescriptor("com.example.common.JsonUtil"), "parse", emptyList(), TypeDescriptor("void"))
        val otherUtilMethod = MethodDescriptor(TypeDescriptor("com.example.common.StringUtil"), "trim", emptyList(), TypeDescriptor("void"))
        val utilityMainMethod = MethodDescriptor(
            TypeDescriptor("com.example.UtilityApplication"),
            "main",
            listOf(TypeDescriptor("java.lang.String[]")),
            TypeDescriptor("void")
        )
        val utilityOnlyComponentModel = inferer.buildViewModel(
            DefaultGraph.Builder()
                .addMethod(utilityMainMethod)
                .addMethod(utilMethod)
                .addMethod(otherUtilMethod)
                .addNode(CallSiteNode(NodeId.next(), utilityMainMethod, utilMethod, 1, null, emptyList()))
                .addNode(CallSiteNode(NodeId.next(), utilMethod, otherUtilMethod, 2, null, emptyList()))
                .build(),
            "component",
            5
        )
        @Suppress("UNCHECKED_CAST")
        val utilityView = utilityOnlyComponentModel["view"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val utilityElements = utilityView["elements"] as List<Map<String, Any?>>
        assertTrue(utilityElements.any { (it["classes"] as? List<*>)?.contains("com.example.common.JsonUtil") == true })
    }

    @Test
    fun `C4 text renderers cover external libraries runtime only and internal dependencies`() {
        val apiType = TypeDescriptor("com.example.api.ApiController")
        val serviceType = TypeDescriptor("com.example.service.SearchService")
        val commonType = TypeDescriptor("com.example.common.SharedKernel")
        val apiMethod = MethodDescriptor(apiType, "handle", emptyList(), TypeDescriptor("void"))
        val serviceMethod = MethodDescriptor(serviceType, "search", emptyList(), TypeDescriptor("void"))
        val commonMethod = MethodDescriptor(commonType, "normalize", emptyList(), TypeDescriptor("void"))
        val luceneType = TypeDescriptor("org.apache.lucene.index.IndexWriter")
        val logType = TypeDescriptor("org.apache.logging.log4j.Logger")
        val runtimeType = TypeDescriptor("java.util.List")
        val builder = DefaultGraph.Builder()
            .addMethod(apiMethod)
            .addMethod(serviceMethod)
            .addMethod(commonMethod)
            .addNode(CallSiteNode(NodeId.next(), apiMethod, serviceMethod, 1, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), serviceMethod, commonMethod, 2, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), apiMethod, MethodDescriptor(luceneType, "commit", emptyList(), TypeDescriptor("void")), 3, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), serviceMethod, MethodDescriptor(logType, "info", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void")), 4, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), commonMethod, MethodDescriptor(runtimeType, "size", emptyList(), TypeDescriptor("int")), 5, null, emptyList()))
        builder.addClassOrigin(luceneType.className, JavaArchiveLayout.bootInfLibEntry("lucene-core-9.12.0.jar"))
        builder.addClassOrigin(logType.className, JavaArchiveLayout.bootInfLibEntry("log4j-api-2.23.1.jar"))
        builder.addArtifactDependency("lucene-core-9.12.0", "log4j-api-2.23.1", 3)
        builder.addMemberAnnotation(
            apiType.className,
            "<class>",
            "org.springframework.web.bind.annotation.RequestMapping",
            mapOf("value" to "/search")
        )
        builder.addMemberAnnotation(
            apiType.className,
            "handle",
            "org.springframework.web.bind.annotation.GetMapping",
            mapOf("value" to "/query")
        )

        val c4 = C4ArchitectureService()
        val workspace = c4.buildModel(builder.build(), "all", 50)
        val mermaid = c4.renderMermaid(workspace)
        val plantUml = c4.renderPlantUml(workspace)

        assertTrue(mermaid.contains("Lucene Core"))
        assertTrue(mermaid.contains("Log4j API"))
        assertTrue(mermaid.contains("builds on"))
        assertFalse(plantUml.contains("Application Layer"), plantUml)
        assertTrue(plantUml.contains("Library Layer"), plantUml)

        val runtimeOnly = DefaultGraph.Builder()
            .addMethod(commonMethod)
            .addNode(CallSiteNode(NodeId.next(), commonMethod, MethodDescriptor(runtimeType, "size", emptyList(), TypeDescriptor("int")), 6, null, emptyList()))
            .build()
        val runtimeWorkspace = c4.buildModel(runtimeOnly, "context", 50)
        val runtimePlantUml = c4.renderPlantUml(runtimeWorkspace)
        assertTrue(runtimePlantUml.contains("Java Runtime"))
        assertTrue(runtimePlantUml.contains("runs on"))
    }

    @Test
    fun `C4 uses one full workspace model while text renderers crop their view`() {
        val appType = TypeDescriptor("com.example.App")
        val appMethod = MethodDescriptor(appType, "run", emptyList(), TypeDescriptor("void"))
        val builder = DefaultGraph.Builder().addMethod(appMethod)
        (1..20).forEach { index ->
            val externalMethod = MethodDescriptor(
                TypeDescriptor("partner$index.Client"),
                "call",
                emptyList(),
                TypeDescriptor("void")
            )
            builder.addNode(CallSiteNode(NodeId.next(), appMethod, externalMethod, index, null, emptyList()))
        }

        val c4 = C4ArchitectureService()
        val workspace = c4.buildModel(builder.build(), "context")
        @Suppress("UNCHECKED_CAST")
        val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        val dependencyCount = softwareSystems.count {
            (it["id"]?.toString() ?: "").startsWith("dependency:namespace:partner")
        }
        val mermaid = c4.renderMermaid(workspace)
        val renderedDependencyCount = Regex("dependency_namespace_partner\\d+").findAll(mermaid)
            .map { it.value }
            .toSet()
            .size

        assertEquals(20, dependencyCount, "Structurizr workspace JSON model must keep every inferred dependency")
        assertTrue(renderedDependencyCount < dependencyCount, "Mermaid should crop the full model for readability: $mermaid")
        assertTrue(mermaid.contains("Mermaid view truncated"), mermaid)
    }

    @Test
    fun `C4 container renderers preserve inbound evidence for shared foundation containers`() {
        fun relationship(id: String, to: String, kind: String, weight: Int): Map<String, Any?> =
            mapOf(
                "id" to id,
                "destinationId" to to,
                "description" to kind,
                "technology" to "call",
                "kind" to kind,
                "properties" to mapOf(
                    "graphite.relationshipKind" to kind,
                    "graphite.weight" to weight.toString()
                )
            )

        fun container(
            id: String,
            name: String,
            kind: String,
            relationships: List<Map<String, Any?>> = emptyList()
        ): Map<String, Any?> =
            mapOf(
                "id" to id,
                "name" to name,
                "description" to "$name responsibility",
                "technology" to "JVM bytecode",
                "properties" to mapOf(
                    "graphite.kind" to kind,
                    "graphite.architectureType" to "application-component"
                ),
                "relationships" to relationships
            )

        val workspace = mapOf(
            "properties" to mapOf("graphite.level" to "container"),
            "model" to mapOf(
                "people" to emptyList<Map<String, Any?>>(),
                "softwareSystems" to listOf(
                    mapOf(
                        "id" to "system:application",
                        "name" to "Application",
                        "description" to "Application",
                        "properties" to mapOf("graphite.architectureType" to "software-system"),
                        "containers" to listOf(
                            container(
                                id = "container:service",
                                name = "Service",
                                kind = "orchestrator",
                                relationships = listOf(
                                    relationship("rel-1", "container:db", "uses", 1000),
                                    relationship("rel-2", "container:locator", "orchestrates", 900)
                                )
                            ),
                            container("container:db", "Db", "capability"),
                            container(
                                id = "container:locator",
                                name = "Locator",
                                kind = "shared-capability",
                                relationships = listOf(
                                    relationship("rel-3", "container:config", "uses", 80)
                                )
                            ),
                            container("container:config", "Config", "shared-capability")
                        )
                    )
                )
            )
        )

        val c4 = C4ArchitectureService()
        val plantUml = c4.renderPlantUml(workspace)
        val mermaid = c4.renderMermaid(workspace)

        assertTrue(plantUml.contains("container_service --> container_locator : orchestrates"), plantUml)
        assertTrue(plantUml.contains("container_locator --> container_config : uses"), plantUml)
        assertTrue(mermaid.contains("container_service -->|orchestrates| container_locator"), mermaid)
        assertTrue(mermaid.contains("container_locator -->|uses| container_config"), mermaid)
    }

    @Test
    fun `C4 mapper and diagram helpers cover fallback semantics`() {
        val graphiteModel = mapOf(
            "level" to "context",
            "availableLevels" to C4ArchitectureService.LEVELS,
            "view" to mapOf(
                "type" to "context",
                "elements" to listOf(
                    mapOf("id" to "system:a", "type" to "softwareSystem", "name" to "A", "kind" to "application", "architectureType" to "software-system"),
                    mapOf("id" to "system:b", "type" to "softwareSystem", "name" to "B", "kind" to "external-system", "architectureType" to "external-system"),
                    mapOf("id" to "dependency:raw", "type" to "softwareSystem", "name" to "Raw", "kind" to "library", "architectureType" to "external-library")
                ),
                "relationships" to listOf(
                    mapOf("from" to "system:a", "to" to "system:b", "type" to "uses"),
                    mapOf("from" to "system:b", "to" to "dependency:raw")
                )
            )
        )
        val workspace = C4StructurizrMapper().toWorkspace(graphiteModel)
        @Suppress("UNCHECKED_CAST")
        val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val systems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val relationships = systems.flatMap { (it["relationships"] as? List<Map<String, Any?>>).orEmpty() }
        val mapType = arrayOf<Class<*>>(Map::class.java)

        assertTrue(relationships.any { it["description"] == "uses" })
        assertEquals("collaborates with", diagramRelationshipLabel(mapOf("properties" to mapOf("graphite.relationshipKind" to "collaborates-with"))))
        assertEquals("external-library", invokeDiagramPrivate<String>("diagramArchitectureTypeOf", mapType, mapOf("id" to "dependency:raw")))
        assertEquals("application-component", invokeDiagramPrivate<String>("diagramArchitectureTypeOf", mapType, mapOf("id" to "plain")))
        assertEquals(
            "external-systems",
            invokeDiagramPrivate<String>("diagramLayerOf", mapType, mapOf("properties" to mapOf("graphite.architectureType" to "external-system")))
        )
        assertEquals(
            "Interface Adapters",
            invokeDiagramPrivate<String>("applicationContainerLayerOf", mapType, mapOf("properties" to mapOf("graphite.kind" to "interface")))
        )
    }

    @Test
    fun `C4 renderers cover truncation and component fallback plans`() {
        val relationships = (1..205).map { index ->
            mapOf(
                "id" to "rel-$index",
                "destinationId" to "dependency:external-$index",
                "description" to "uses",
                "properties" to mapOf(
                    "graphite.relationshipKind" to "uses",
                    "graphite.weight" to "1"
                )
            )
        }
        val externalSystems = (1..205).map { index ->
            mapOf(
                "id" to "dependency:external-$index",
                "name" to "External $index",
                "description" to "External system $index",
                "properties" to mapOf("graphite.architectureType" to "external-library")
            )
        }
        val contextWorkspace = mapOf(
            "properties" to mapOf("graphite.level" to "context"),
            "model" to mapOf(
                "people" to emptyList<Map<String, Any?>>(),
                "softwareSystems" to listOf(
                    mapOf(
                        "id" to "system:application",
                        "name" to "Application",
                        "description" to "Application",
                        "properties" to mapOf("graphite.architectureType" to "software-system"),
                        "relationships" to relationships
                    )
                ) + externalSystems
            )
        )
        val componentWorkspace = mapOf(
            "properties" to mapOf("graphite.level" to "component"),
            "model" to mapOf(
                "people" to emptyList<Map<String, Any?>>(),
                "softwareSystems" to listOf(
                    mapOf(
                        "id" to "system:application",
                        "name" to "Application",
                        "description" to "Application",
                        "properties" to mapOf("graphite.architectureType" to "software-system"),
                        "containers" to listOf(
                            mapOf(
                                "id" to "container:runtime",
                                "name" to "Runtime",
                                "description" to "Runtime",
                                "properties" to mapOf("graphite.kind" to "application-runtime"),
                                "components" to listOf(
                                    mapOf(
                                        "id" to "component:standalone",
                                        "name" to "Standalone",
                                        "description" to "Standalone",
                                        "properties" to mapOf("graphite.architectureType" to "application-component")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val componentRelationships = (1..205).map { index ->
            mapOf(
                "id" to "component-rel-$index",
                "destinationId" to "component:target-$index",
                "description" to "uses",
                "properties" to mapOf(
                    "graphite.relationshipKind" to "uses",
                    "graphite.weight" to "1"
                )
            )
        }
        val componentTruncationWorkspace = mapOf(
            "properties" to mapOf("graphite.level" to "component"),
            "model" to mapOf(
                "people" to emptyList<Map<String, Any?>>(),
                "softwareSystems" to listOf(
                    mapOf(
                        "id" to "system:application",
                        "name" to "Application",
                        "description" to "Application",
                        "properties" to mapOf("graphite.architectureType" to "software-system"),
                        "containers" to listOf(
                            mapOf(
                                "id" to "container:runtime",
                                "name" to "Runtime",
                                "description" to "Runtime",
                                "properties" to mapOf("graphite.kind" to "application-runtime"),
                                "components" to listOf(
                                    mapOf(
                                        "id" to "component:source",
                                        "name" to "Source",
                                        "description" to "Source",
                                        "properties" to mapOf("graphite.architectureType" to "application-component"),
                                        "relationships" to componentRelationships
                                    )
                                ) + (1..205).map { index ->
                                    mapOf(
                                        "id" to "component:target-$index",
                                        "name" to "Target $index",
                                        "description" to "Target $index",
                                        "properties" to mapOf("graphite.architectureType" to "application-component")
                                    )
                                }
                            )
                        )
                    )
                )
            )
        )
        val c4 = C4ArchitectureService()

        assertTrue(c4.renderMermaid(contextWorkspace).contains("Mermaid view truncated"))
        assertTrue(c4.renderPlantUml(contextWorkspace).contains("PlantUML view truncated"))
        assertTrue(c4.renderMermaid(componentWorkspace).contains("component_standalone"))
        assertTrue(c4.renderMermaid(componentTruncationWorkspace).contains("Mermaid view truncated"))
    }

    @Test
    fun `C4 Structurizr DSL renderer covers elements relationships and views`() {
        val workspace = mapOf(
            "name" to "Architecture \"Workspace\"",
            "model" to mapOf(
                "people" to listOf(
                    mapOf(
                        "id" to "person:operator",
                        "name" to "Operator",
                        "description" to "Runs the system",
                        "relationships" to listOf(
                            mapOf("id" to "rel-1", "destinationId" to "system:application", "description" to "starts")
                        )
                    )
                ),
                "softwareSystems" to listOf(
                    mapOf(
                        "id" to "system:application",
                        "name" to "Application",
                        "description" to "Subject",
                        "relationships" to listOf(
                            mapOf("id" to "rel-2", "destinationId" to "dependency:queue", "description" to "publishes to")
                        ),
                        "containers" to listOf(
                            mapOf(
                                "id" to "container:runtime",
                                "name" to "Runtime",
                                "description" to "JVM runtime",
                                "technology" to "Java",
                                "relationships" to listOf(
                                    mapOf("id" to "rel-3", "destinationId" to "dependency:queue", "description" to "uses")
                                ),
                                "components" to listOf(
                                    mapOf(
                                        "id" to "component:api",
                                        "name" to "API",
                                        "description" to "Handles requests",
                                        "technology" to "Kotlin",
                                        "relationships" to listOf(
                                            mapOf("id" to "rel-4", "destinationId" to "component:service", "description" to "calls")
                                        )
                                    ),
                                    mapOf(
                                        "id" to "component:service",
                                        "name" to "Service",
                                        "description" to "Coordinates work",
                                        "technology" to "Kotlin"
                                    )
                                )
                            )
                        )
                    ),
                    mapOf(
                        "id" to "dependency:queue",
                        "name" to "Queue",
                        "description" to "External queue"
                    ),
                    mapOf(
                        "id" to "dependency.queue",
                        "name" to "Queue Alias",
                        "description" to "Exercises identifier collision handling"
                    )
                )
            ),
            "views" to mapOf(
                "systemContextViews" to listOf(mapOf("key" to "context", "softwareSystemId" to "system:application")),
                "containerViews" to listOf(mapOf("key" to "container", "softwareSystemId" to "system:application")),
                "componentViews" to listOf(mapOf("key" to "component", "containerId" to "container:runtime"))
            )
        )

        val dsl = C4ArchitectureService().renderStructurizrDsl(workspace)

        assertTrue(dsl.contains("workspace \"Architecture \\\"Workspace\\\"\""), dsl)
        assertTrue(dsl.contains("person \"Operator\""), dsl)
        assertTrue(dsl.contains("softwareSystem \"Application\""), dsl)
        assertTrue(dsl.contains("container \"Runtime\""), dsl)
        assertTrue(dsl.contains("component \"API\""), dsl)
        assertTrue(dsl.contains("-> g_system_application \"starts\""), dsl)
        assertTrue(dsl.contains("-> g_dependency_queue \"publishes to\""), dsl)
        assertTrue(dsl.contains("-> g_component_service \"calls\""), dsl)
        assertTrue(dsl.contains("systemContext g_system_application \"context\""), dsl)
        assertTrue(dsl.contains("container g_system_application \"container\""), dsl)
        assertTrue(dsl.contains("component g_container_runtime \"component\""), dsl)
        assertTrue(dsl.contains("theme default"), dsl)
    }

    @Test
    fun `C4 model keeps runtime container and artifact dependency relationships`() {
        fun method(className: String, name: String = "run") =
            MethodDescriptor(TypeDescriptor(className), name, emptyList(), TypeDescriptor("void"))

        val main = MethodDescriptor(
            TypeDescriptor("com.acme.Main"),
            "main",
            listOf(TypeDescriptor("java.lang.String[]")),
            TypeDescriptor("void")
        )
        val internalMethods = listOf(
            method("com.acme.api.SearchController", "handle"),
            method("com.acme.service.SearchService", "search"),
            method("com.acme.repository.SearchRepository", "query"),
            method("com.acme.common.JsonSupport", "parse"),
            method("com.acme.model.DocumentModel", "shape")
        )
        val lucene = method("org.apache.lucene.index.IndexWriter", "commit")
        val log4j = method("org.apache.logging.log4j.Logger", "info")
        val runtime = method("java.util.Optional", "orElse")
        val builder = DefaultGraph.Builder().addMethod(main)
        internalMethods.forEach(builder::addMethod)
        builder.addNode(CallSiteNode(NodeId.next(), main, internalMethods.first(), 1, null, emptyList()))
        var line = 10
        internalMethods.forEachIndexed { index, caller ->
            internalMethods.drop(index + 1).forEach { callee ->
                builder.addNode(CallSiteNode(NodeId.next(), caller, callee, line++, null, emptyList()))
            }
        }
        builder.addNode(CallSiteNode(NodeId.next(), internalMethods[0], lucene, line++, null, emptyList()))
        builder.addNode(CallSiteNode(NodeId.next(), internalMethods[1], log4j, line++, null, emptyList()))
        builder.addNode(CallSiteNode(NodeId.next(), internalMethods[2], runtime, line++, null, emptyList()))
        builder.addClassOrigin(lucene.declaringClass.className, "lib/lucene-core-9.12.0.jar")
        builder.addClassOrigin(log4j.declaringClass.className, "lib/log4j-api-2.23.1.jar")
        builder.addArtifactDependency("lucene-core-9.12.0", "log4j-api-2.23.1", 4)
        builder.addMemberAnnotation(
            "com.acme.api.SearchController",
            "<class>",
            "org.springframework.web.bind.annotation.RequestMapping",
            mapOf("value" to "/search")
        )
        builder.addMemberAnnotation(
            "com.acme.api.SearchController",
            "handle",
            "org.springframework.web.bind.annotation.PostMapping",
            mapOf("value" to "/query")
        )

        val workspace = ExploreCommand().buildC4Model(builder.build(), "all", 50)
        @Suppress("UNCHECKED_CAST")
        val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val people = model["people"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val systems = model["softwareSystems"] as List<Map<String, Any?>>
        val application = systems.first { it["id"] == "system:application" }
        @Suppress("UNCHECKED_CAST")
        val containers = application["containers"] as List<Map<String, Any?>>
        val containerRelationships = containers.flatMap { (it["relationships"] as? List<Map<String, Any?>>).orEmpty() }
        val dependencyRelationships = systems.flatMap { (it["relationships"] as? List<Map<String, Any?>>).orEmpty() }

        assertTrue(people.any { it["id"] == "person:http-clients" })
        assertEquals(1, containers.size, "C4 container level should show the deployable runtime boundary, not package clusters")
        val runtimeProperties = containers.single()["properties"] as Map<*, *>
        assertEquals("application-service", runtimeProperties["graphite.kind"])
        assertTrue((runtimeProperties["graphite.internalCapabilities"] as? String).orEmpty().contains("com.acme.api"))
        @Suppress("UNCHECKED_CAST")
        val runtimeComponents = containers.single()["components"] as List<Map<String, Any?>>
        val apiComponent = runtimeComponents.first {
            @Suppress("UNCHECKED_CAST")
            val properties = it["properties"] as Map<String, String>
            properties["graphite.classes"].orEmpty().contains("com.acme.api.SearchController")
        }
        @Suppress("UNCHECKED_CAST")
        val apiComponentProperties = apiComponent["properties"] as Map<String, String>
        assertTrue(
            apiComponentProperties["graphite.classes"].orEmpty().contains("com.acme.api.SearchController"),
            "C4 component identity should be the capability group, with implementation classes kept as evidence"
        )
        assertFalse(runtimeComponents.any { it["id"] == "component:com.acme.api.SearchController" })
        assertFalse(containerRelationships.any { (it["destinationId"] as? String)?.startsWith("container:") == true })
        assertTrue(containerRelationships.any { it["destinationId"] == "dependency:artifact:lucene-core-9.12.0" })
        assertTrue(systems.any { it["id"] == "dependency:artifact:log4j-api-2.23.1" })
        assertTrue(dependencyRelationships.any { it["destinationId"] == "dependency:artifact:log4j-api-2.23.1" })
        val luceneRelationships = systems
            .first { it["id"] == "dependency:artifact:lucene-core-9.12.0" }["relationships"] as? List<Map<String, Any?>>
            ?: emptyList()
        val log4jRelationships = systems
            .first { it["id"] == "dependency:artifact:log4j-api-2.23.1" }["relationships"] as? List<Map<String, Any?>>
            ?: emptyList()
        assertTrue(luceneRelationships.any { it["destinationId"] == "dependency:artifact:log4j-api-2.23.1" })
        assertFalse(luceneRelationships.any { it["destinationId"] == "dependency:runtime:java" })
        assertTrue(log4jRelationships.any { it["destinationId"] == "dependency:runtime:java" })
    }

    @Test
    fun `C4 context groups sibling library artifacts into one system`() {
        val appType = TypeDescriptor("com.acme.App")
        val appMethod = MethodDescriptor(appType, "run", emptyList(), TypeDescriptor("void"))
        val luceneCore = MethodDescriptor(TypeDescriptor("org.apache.lucene.index.IndexWriter"), "commit", emptyList(), TypeDescriptor("void"))
        val luceneQueries = MethodDescriptor(TypeDescriptor("org.apache.lucene.queries.CustomScoreQuery"), "rewrite", emptyList(), TypeDescriptor("void"))
        val luceneHighlighter = MethodDescriptor(TypeDescriptor("org.apache.lucene.search.uhighlight.UnifiedHighlighter"), "highlight", emptyList(), TypeDescriptor("void"))
        val runtime = MethodDescriptor(TypeDescriptor("java.util.List"), "size", emptyList(), TypeDescriptor("int"))
        val builder = DefaultGraph.Builder()
            .addMethod(appMethod)
            .addNode(CallSiteNode(NodeId.next(), appMethod, luceneCore, 1, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), appMethod, luceneQueries, 2, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), appMethod, luceneHighlighter, 3, null, emptyList()))
            .addNode(CallSiteNode(NodeId.next(), appMethod, runtime, 4, null, emptyList()))

        builder.addClassOrigin(luceneCore.declaringClass.className, "lib/lucene-core-9.12.0.jar")
        builder.addClassOrigin(luceneQueries.declaringClass.className, "lib/lucene-queries-9.12.0.jar")
        builder.addClassOrigin(luceneHighlighter.declaringClass.className, "lib/lucene-highlighter-9.12.0.jar")
        builder.addArtifactDependency("lucene-highlighter-9.12.0", "lucene-queries-9.12.0", 5)
        builder.addArtifactDependency("lucene-queries-9.12.0", "lucene-core-9.12.0", 7)

        val c4 = C4ArchitectureService()
        val workspace = c4.buildModel(builder.build(), "context", 50)
        val plantUml = c4.renderPlantUml(workspace)

        assertTrue(plantUml.contains("component \"Lucene\""), plantUml)
        assertFalse(plantUml.contains("Lucene Core"), plantUml)
        assertFalse(plantUml.contains("Lucene Queries"), plantUml)
        assertFalse(plantUml.contains("Lucene Highlighter"), plantUml)
        assertEquals(1, Regex("dependency_library_lucene --> dependency_runtime_java").findAll(plantUml).count(), plantUml)
    }

    @Test
    fun `C4 subject detection covers manifest continuation and plain main applications`() {
        val bootMainType = TypeDescriptor("com.example.very.LongApplication")
        val bootWorkerType = TypeDescriptor("com.example.very.Worker")
        val bootMain = MethodDescriptor(bootMainType, "main", listOf(TypeDescriptor("java.lang.String[]")), TypeDescriptor("void"))
        val bootWorker = MethodDescriptor(bootWorkerType, "run", emptyList(), TypeDescriptor("void"))
        val bootGraph = DefaultGraph.Builder()
            .setResources(
                TestResourceAccessor(
                    mapOf(
                        JavaArchiveLayout.META_INF_MANIFEST to """
                            Manifest-Version: 1.0
                            ${JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE}: ${JavaArchiveLayout.SPRING_BOOT_WAR_LAUNCHER}
                            ${JavaArchiveLayout.START_CLASS_ATTRIBUTE}: com.example.very.Long
                             Application

                        """.trimIndent()
                    )
                )
            )
            .addMethod(bootMain)
            .addMethod(bootWorker)
            .addNode(CallSiteNode(NodeId.next(), bootMain, bootWorker, 1, null, emptyList()))
            .apply {
                addClassOrigin(bootMainType.className, JavaArchiveLayout.webInfLibEntry("long-app.jar"))
                addClassOrigin(bootWorkerType.className, JavaArchiveLayout.webInfLibEntry("long-app.jar"))
            }
            .build()

        val cliMainType = TypeDescriptor("com.tool.cli.Main")
        val cliWorkerType = TypeDescriptor("com.tool.cli.Worker")
        val cliMain = MethodDescriptor(cliMainType, "main", listOf(TypeDescriptor("java.lang.String[]")), TypeDescriptor("void"))
        val cliWorker = MethodDescriptor(cliWorkerType, "execute", emptyList(), TypeDescriptor("void"))
        val cliGraph = DefaultGraph.Builder()
            .addMethod(cliMain)
            .addMethod(cliWorker)
            .addNode(CallSiteNode(NodeId.next(), cliMain, cliWorker, 1, null, emptyList()))
            .build()

        fun subject(workspace: Map<String, Any?>): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val model = workspace["model"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val systems = model["softwareSystems"] as List<Map<String, Any?>>
            return systems.first { (it["id"] as? String)?.startsWith("system:") == true }
        }

        val explore = ExploreCommand()
        assertEquals("Long App", subject(explore.buildC4Model(bootGraph, "context", 50))["name"])
        listOf(
            JavaArchiveLayout.SPRING_BOOT_LAUNCH_WAR_LAUNCHER,
            JavaArchiveLayout.SPRING_BOOT_PROPERTIES_LAUNCHER
        ).forEach { launcher ->
            val launcherGraph = DefaultGraph.Builder()
                .setResources(
                    TestResourceAccessor(
                        mapOf(
                            JavaArchiveLayout.META_INF_MANIFEST to """
                                Manifest-Version: 1.0
                                ${JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE}: $launcher
                                ${JavaArchiveLayout.START_CLASS_ATTRIBUTE}: com.example.very.LongApplication

                            """.trimIndent()
                        )
                    )
                )
                .addMethod(bootMain)
                .addMethod(bootWorker)
                .addNode(CallSiteNode(NodeId.next(), bootMain, bootWorker, 1, null, emptyList()))
                .apply {
                    addClassOrigin(bootMainType.className, JavaArchiveLayout.webInfLibEntry("long-app.jar"))
                    addClassOrigin(bootWorkerType.className, JavaArchiveLayout.webInfLibEntry("long-app.jar"))
                }
                .build()
            assertEquals("Long App", subject(explore.buildC4Model(launcherGraph, "context", 50))["name"])
        }
        val cliSubject = subject(explore.buildC4Model(cliGraph, "context", 50))
        assertEquals("system:application", cliSubject["id"])
        @Suppress("UNCHECKED_CAST")
        val model = explore.buildC4Model(cliGraph, "context", 50)["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val people = model["people"] as List<Map<String, Any?>>
        assertTrue(people.any { it["id"] == "person:operators" })
    }

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
