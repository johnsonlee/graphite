package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.TestResourceAccessor
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.localNode
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.paramNode
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.quxMethod
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.strConstNode
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class ExploreGraphApiTest : ExploreCommandTestSupport() {
    @Test
    fun `registry serves concurrent scoped cypher queries across many graphs`() {
        val root = Files.createTempDirectory("explore-registry-concurrent")
        val graphCount = 24
        try {
            repeat(graphCount) { index -> saveConstantGraph(root, "service-$index", 1_000 + index) }
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)

            val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 128, maxWorkUnits = 250_000))
            withRegistryApp(registry, routes) { targetPort ->
                repeat(graphCount) { index ->
                    val (loadCode, loadBody) = put(targetPort, "/api/graphs/service-$index", """{"path":"service-$index"}""")
                    assertEquals(200, loadCode, "Expected 200 for service-$index, body: $loadBody")
                }

                val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
                try {
                    val tasks =
                        (0 until graphCount).flatMap { index ->
                            List(4) {
                                java.util.concurrent.Callable {
                                    val (code, body) =
                                        post(
                                            targetPort,
                                            "/api/graphs/service-$index/cypher",
                                            """{"query":"MATCH (n:IntConstant) RETURN n.value LIMIT 1"}""")
                                    code to singleCypherValue(body)
                                }
                            }
                        }
                    val results = executor.invokeAll(tasks).map { it.get() }
                    results.forEachIndexed { taskIndex, (code, value) ->
                        val graphIndex = taskIndex / 4
                        assertEquals(200, code)
                        assertEquals((1_000 + graphIndex).toDouble(), value)
                    }
                } finally {
                    executor.shutdownNow()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy discovery APIs are unavailable while Cypher discovers methods`() {
        listOf("nodes", "call-sites", "methods").forEach { route ->
            assertEquals(404, get("/api/$route").first, "Root search route /api/$route must be unavailable")
            assertEquals(
                404, get("/api/graphs/standalone/$route").first, "Scoped search route /api/graphs/standalone/$route must be unavailable")
        }

        val (rootCode, rootBody) = post("/api/cypher", """{"query":"MATCH (n) RETURN n.id LIMIT 1"}""")
        assertEquals(200, rootCode, rootBody)
        val methodQuery =
            "MATCH (m:Method) WHERE m.signature = '${quxMethod.signature}' " +
                "RETURN m.signature, m.class, m.name, m.parameter_types, m.return_type"
        val (methodCode, methodBody) = post("/api/cypher", """{"query":"$methodQuery"}""")
        assertEquals(200, methodCode, methodBody)
        val methodResult: Map<String, Any?> = parseJson(methodBody)
        @Suppress("UNCHECKED_CAST") val methodRows = methodResult["rows"] as List<Map<String, Any?>>
        val methodRow = methodRows.single()
        assertEquals(quxMethod.signature, methodRow["m.signature"])
        assertEquals("com.example.Child", methodRow["m.class"])
        assertEquals("qux", methodRow["m.name"])
        assertEquals(listOf("java.lang.String"), methodRow["m.parameter_types"])
        assertEquals("int", methodRow["m.return_type"])
        val (scopedCode, scopedBody) = post("/api/graphs/standalone/cypher", """{"query":"MATCH (n) RETURN n.id LIMIT 1"}""")
        assertEquals(200, scopedCode, scopedBody)
    }

    @Test
    fun `graph-local id routes require an explicit graph id`() {
        listOf(
                "/api/node/${paramNode.id.value}",
                "/api/node/${paramNode.id.value}/outgoing",
                "/api/node/${localNode.id.value}/incoming",
                "/api/subgraph?center=${localNode.id.value}")
            .forEach { path -> assertEquals(404, get(path).first, "$path must not be available without graphId") }
    }

    @Test
    fun `GET api node by id returns node`() {
        val (code, body) = get("/api/graphs/standalone/node/${paramNode.id.value}")
        assertEquals(200, code)
        val node: Map<String, Any?> = parseJson(body)
        assertEquals("ParameterNode", node["type"])
        assertEquals(paramNode.id.value.toDouble(), node["id"])
    }

    @Test
    fun `GET api node by id returns 404 for missing`() {
        val (code, _) = get("/api/graphs/standalone/node/999999")
        assertEquals(404, code)
    }

    @Test
    fun `GET api node by id returns 400 for invalid id`() {
        val (code, _) = get("/api/graphs/standalone/node/notanumber")
        assertEquals(400, code)
    }

    @Test
    fun `GET api node outgoing returns edges`() {
        val (code, body) = get("/api/graphs/standalone/node/${paramNode.id.value}/outgoing")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertTrue(edges.isNotEmpty(), "paramNode should have outgoing edges")
        assertEquals("DataFlow", edges[0]["type"])
    }

    @Test
    fun `GET api node outgoing returns empty for isolated node`() {
        val (code, body) = get("/api/graphs/standalone/node/${strConstNode.id.value}/outgoing")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertTrue(edges.isEmpty(), "strConstNode should have no outgoing edges")
    }

    @Test
    fun `GET api node outgoing respects limit`() {
        val (code, body) = get("/api/graphs/standalone/node/${paramNode.id.value}/outgoing?limit=0")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertTrue(edges.isEmpty(), "limit=0 should return no outgoing edges")
    }

    @Test
    fun `GET api node outgoing returns 400 for invalid id`() {
        val (code, _) = get("/api/graphs/standalone/node/abc/outgoing")
        assertEquals(400, code)
    }

    @Test
    fun `GET api node incoming returns edges`() {
        val (code, body) = get("/api/graphs/standalone/node/${localNode.id.value}/incoming")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertTrue(edges.size >= 2, "localNode should have at least 2 incoming edges (param + intConst)")
    }

    @Test
    fun `GET api node incoming respects limit`() {
        val (code, body) = get("/api/graphs/standalone/node/${localNode.id.value}/incoming?limit=1")
        assertEquals(200, code)
        val edges: List<Map<String, Any?>> = parseJson(body)
        assertEquals(1, edges.size)
    }

    @Test
    fun `GET api node incoming returns 400 for invalid id`() {
        val (code, _) = get("/api/graphs/standalone/node/xyz/incoming")
        assertEquals(400, code)
    }

    @Test
    fun `GET api annotations returns annotation data`() {
        val (code, body) = get("/api/annotations?class=com.example.Foo&member=bar")
        assertEquals(200, code, "Expected 200, body: $body")
        val annotations: Map<String, Map<String, Any?>> = parseJson(body)
        assertTrue(annotations.size >= 2, "Should have at least 2 annotations, got: $annotations")
        assertTrue(annotations.containsKey("javax.annotation.Nullable"), "Should contain Nullable, got keys: ${annotations.keys}")
        assertTrue(
            annotations.containsKey("org.springframework.web.bind.annotation.GetMapping"),
            "Should contain GetMapping, got keys: ${annotations.keys}")
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

    @Test
    fun `real jar build persists and serves resource content`() {
        val root = Files.createTempDirectory("explore-jar-resource-test")
        val classesDir = Files.createDirectories(root.resolve("classes"))
        val graphOutput = root.resolve("graph")
        val fixtureJar = root.resolve("resource-fixture.jar")
        val resourcePath = "config/application.properties"
        val resourceContent = "feature.mode=jar-e2e\n"
        try {
            val javaSource = root.resolve("ResourceSample.java")
            Files.writeString(
                javaSource,
                """
                    package sample;
                    public class ResourceSample {
                        public String mode() { return "jar-e2e"; }
                    }
                    """
                    .trimIndent())
            val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler())
            assertEquals(
                0,
                compiler.run(null, null, null, "-d", classesDir.toString(), javaSource.toString()),
                "Java fixture compilation should succeed")

            JarOutputStream(Files.newOutputStream(fixtureJar)).use { jar ->
                jar.putNextEntry(JarEntry("sample/ResourceSample.class"))
                Files.copy(classesDir.resolve("sample/ResourceSample.class"), jar)
                jar.closeEntry()
                jar.putNextEntry(JarEntry(resourcePath))
                jar.write(resourceContent.toByteArray())
                jar.closeEntry()
            }

            val build =
                BuildCommand().apply {
                    input = fixtureJar
                    output = graphOutput
                    includePackages = listOf("sample")
                }
            val (_, error, code) = captureOutput { build.call() }
            assertEquals(0, code, "JAR graph build should succeed, stderr: $error")
            assertTrue(Files.isRegularFile(graphOutput.resolve("graph.resources")))

            val loaded = GraphStore.loadMapped(graphOutput)
            try {
                withExploreApp(loaded) { targetPort ->
                    val (status, body) = get(targetPort, "/api/resources/$resourcePath")
                    assertEquals(200, status, "Expected persisted JAR resource, body: $body")
                    val result: Map<String, Any?> = parseJson(body)
                    assertEquals(resourcePath, result["path"])
                    assertEquals(fixtureJar.fileName.toString(), result["source"])
                    assertEquals(false, result["derived"])
                    assertEquals(resourceContent, result["content"])
                }
            } finally {
                (loaded as? Closeable)?.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resources API distinguishes an empty store from a missing legacy store`() {
        val root = Files.createTempDirectory("explore-resource-store-state-test")
        val currentDir = root.resolve("current")
        val legacyDir = root.resolve("legacy")
        try {
            val emptyGraph = DefaultGraph.Builder().build()
            GraphStore.save(emptyGraph, currentDir)
            GraphStore.save(emptyGraph, legacyDir)
            Files.delete(legacyDir.resolve("graph.resources"))

            val current = GraphStore.loadMapped(currentDir)
            try {
                withExploreApp(current) { targetPort ->
                    val (status, body) = get(targetPort, "/api/resources")
                    assertEquals(200, status, "Current empty resource store should remain queryable: $body")
                    val result: Map<String, Any?> = parseJson(body)
                    assertEquals(0.0, result["count"])
                }
            } finally {
                (current as Closeable).close()
            }

            val legacy = GraphStore.loadMapped(legacyDir)
            try {
                withExploreApp(legacy) { targetPort ->
                    val (listStatus, listBody) = get(targetPort, "/api/resources")
                    assertEquals(409, listStatus, "Missing resource store must not appear empty: $listBody")
                    assertTrue(listBody.contains("graph.resources is missing"))
                    assertTrue(listBody.contains("rebuild this graph"))

                    val (readStatus, readBody) = get(targetPort, "/api/resources/application.properties")
                    assertEquals(409, readStatus, "Missing resource store must explain failed reads: $readBody")
                    assertTrue(readBody.contains("rebuild this graph"))
                }
            } finally {
                (legacy as Closeable).close()
            }

            val registry = GraphRegistry(Files.createDirectories(root.resolve("registry")), GraphStore.LoadMode.MAPPED)
            registry.load("current", currentDir)
            registry.load("legacy", legacyDir)
            withRegistryApp(registry) { targetPort ->
                val (rootStatus, rootBody) = get(targetPort, "/api/resources")
                assertEquals(409, rootStatus, "All-graph resources must identify unavailable stores: $rootBody")
                assertTrue(rootBody.contains("legacy"))
                assertTrue(rootBody.contains("rebuild this graph"))

                val (currentStatus, currentBody) = get(targetPort, "/api/graphs/current/resources")
                assertEquals(200, currentStatus, "A current graph must remain independently queryable: $currentBody")

                val (legacyStatus, legacyBody) = get(targetPort, "/api/graphs/legacy/resources")
                assertEquals(409, legacyStatus, "A legacy graph must expose the rebuild instruction: $legacyBody")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `GET api resources returns matching resources`() {
        val (code, body) = get("/api/resources?pattern=**&limit=10")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals(2.0, result["count"])
        @Suppress("UNCHECKED_CAST") val resourceEntries = result["resources"] as List<Map<String, Any?>>
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
        @Suppress("UNCHECKED_CAST") val resourceEntries = result["resources"] as List<Map<String, Any?>>
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
    fun `GET api resource content rejects oversized payloads`() {
        val largeGraph = DefaultGraph.Builder().setResources(TestResourceAccessor(mapOf("large.txt" to "x".repeat(1_048_577)))).build()

        withExploreApp(largeGraph) { targetPort ->
            val (code, body) = get(targetPort, "/api/resources/large.txt")
            assertEquals(413, code, "Expected 413, body: $body")
            assertTrue(body.contains("Resource exceeds maximum response size"))
        }
    }

    @Test
    fun `GET openapi json exposes discoverable explore API`() {
        val (code, body) = get("/openapi.json")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("3.0.3", result["openapi"])
        @Suppress("UNCHECKED_CAST") val paths = result["paths"] as Map<String, Map<String, Any?>>
        assertTrue(paths.containsKey("/api/cypher"))
        assertTrue(paths.containsKey("/api/graphs"))
        assertTrue(paths.containsKey("/api/topology"))
        assertTrue(paths.containsKey("/api/graphs/{graphId}"))
        assertTrue(paths.containsKey("/api/graphs/{graphId}/cypher"))
        assertFalse(paths.containsKey("/api/info"))
        assertFalse(paths.containsKey("/api/graphs/{graphId}/info"))
        assertTrue(paths.containsKey("/api/endpoints"))
        assertFalse(paths.containsKey("/api/api-spec"))
        assertTrue(paths.containsKey("/api/resources/{path}"))
        listOf("nodes", "call-sites", "methods").forEach { route ->
            assertFalse(paths.containsKey("/api/$route"))
            assertFalse(paths.containsKey("/api/graphs/{graphId}/$route"))
        }
        @Suppress("UNCHECKED_CAST") val cypher = paths["/api/cypher"] as Map<String, Map<String, Any?>>
        assertTrue(cypher.containsKey("get"))
        assertTrue(cypher.containsKey("post"))
        @Suppress("UNCHECKED_CAST") val post = cypher["post"] as Map<String, Any?>
        assertTrue(post.containsKey("requestBody"))
        assertTrue(post["requestBody"].toString().contains(API_PARAM_TIMEOUT_MILLIS))
        listOf("/api/cypher", "/api/cypher/graphs", "/api/graphs/{graphId}/cypher").forEach { path ->
            @Suppress("UNCHECKED_CAST") val operations = paths.getValue(path) as Map<String, Map<String, Any?>>
            operations.forEach { (method, operation) ->
                @Suppress("UNCHECKED_CAST") val parameters = operation.getValue("parameters") as List<Map<String, Any?>>
                val timeout = parameters.single { it[API_FIELD_NAME] == API_PARAM_TIMEOUT_MILLIS }
                @Suppress("UNCHECKED_CAST") val schema = timeout.getValue("schema") as Map<String, Any?>
                assertEquals("integer", schema[API_FIELD_TYPE], "$method $path timeout schema type")
                assertEquals(1.0, schema["minimum"], "$method $path timeout schema minimum")
            }
        }
        @Suppress("UNCHECKED_CAST") val responses = post["responses"] as Map<String, Any?>
        assertTrue(responses.containsKey("429"))
        assertTrue(responses.containsKey("504"))
        assertTrue(responses.containsKey("503"))
        assertFalse(responses.getValue("429").toString().contains("work budget"))
    }

    @Test
    fun `GET api topology exposes the standalone graph`() {
        val (code, body) = get("/api/topology")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals(1.0, result["graphCount"])
        assertEquals(0.0, result["relationCount"])
        @Suppress("UNCHECKED_CAST") val nodes = result[API_FIELD_NODES] as List<Map<String, Any?>>
        assertEquals(STANDALONE_GRAPH_ID, nodes.single()[API_FIELD_ID])
        assertEquals("Graph", nodes.single()[API_FIELD_TYPE])
    }
}
