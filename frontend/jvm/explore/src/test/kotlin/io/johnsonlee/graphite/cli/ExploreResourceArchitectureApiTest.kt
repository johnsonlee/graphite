package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.barMethod
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.resources
import io.johnsonlee.graphite.cli.c4.C4ViewLimits
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class ExploreResourceArchitectureApiTest : ExploreCommandTestSupport() {
    @Test
    fun `GET swagger json aliases the OpenAPI document`() {
        val (openapiCode, openapiBody) = get("/openapi.json")
        val (swaggerCode, swaggerBody) = get("/swagger.json")
        assertEquals(200, openapiCode)
        assertEquals(200, swaggerCode)
        assertEquals(parseJson<Map<String, Any?>>(openapiBody), parseJson<Map<String, Any?>>(swaggerBody))
    }

    @Test
    fun `GET api endpoints returns Spring endpoints`() {
        val (code, body) = get("/api/endpoints")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("spring-web", result["framework"])
        @Suppress("UNCHECKED_CAST") val endpoints = result["endpoints"] as List<Map<String, Any?>>
        assertEquals(1, endpoints.size)
        val endpoint = endpoints.single()
        assertEquals("com.example.Foo", endpoint["class"])
        assertEquals("bar", endpoint["member"])
        assertEquals("GET", endpoint["httpMethod"])
        assertEquals("/v1/api/bar", endpoint["path"])
        assertEquals(barMethod.signature, endpoint["signature"])
        assertEquals(404, get("/api/api-spec").first)
    }

    @Test
    fun `GET api endpoints supports class filter`() {
        val (code, body) = get("/api/endpoints?class=com.example.Foo")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val endpoints = result["endpoints"] as List<Map<String, Any?>>
        assertEquals(1, endpoints.size)

        val (missingCode, missingBody) = get("/api/endpoints?class=com.example.Baz")
        assertEquals(200, missingCode, "Expected 200, body: $missingBody")
        val missingResult: Map<String, Any?> = parseJson(missingBody)
        @Suppress("UNCHECKED_CAST") val missingEndpoints = missingResult["endpoints"] as List<Map<String, Any?>>
        assertTrue(missingEndpoints.isEmpty())
    }

    @Test
    fun `extractEndpoints handles RequestMapping arrays iterables and default request method`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.RequestController"), "handle", emptyList(), TypeDescriptor("void"))
        val fallbackMethod =
            MethodDescriptor(TypeDescriptor("com.example.RequestController"), "fallback", emptyList(), TypeDescriptor("void"))
        val graph =
            DefaultGraph.Builder()
                .addMethod(method)
                .addMethod(fallbackMethod)
                .addMemberAnnotation(
                    "com.example.RequestController",
                    "<class>",
                    "org.springframework.web.bind.annotation.RequestMapping",
                    mapOf("value" to arrayOf("/v2", "/v1")))
                .addMemberAnnotation(
                    "com.example.RequestController",
                    "handle",
                    "org.springframework.web.bind.annotation.RequestMapping",
                    mapOf("path" to listOf("/beta", "/alpha"), "method" to arrayOf("POST", "PATCH")))
                .addMemberAnnotation(
                    "com.example.RequestController", "fallback", "org.springframework.web.bind.annotation.RequestMapping", emptyMap())
                .build()

        val endpoints = ExploreCommand().extractEndpoints(graph)

        assertEquals(10, endpoints.size)
        val fallbackEndpoints = endpoints.filter { it["member"] == "fallback" }
        assertEquals(2, fallbackEndpoints.size)
        assertTrue(fallbackEndpoints.all { it["httpMethod"] == "REQUEST" })
        assertEquals(setOf("/v1", "/v2"), fallbackEndpoints.map { it["path"] }.toSet())

        val handlePaths = endpoints.filter { it["member"] == "handle" }.map { it["path"] as String }
        assertEquals(
            listOf("/v1/alpha", "/v1/alpha", "/v1/beta", "/v1/beta", "/v2/alpha", "/v2/alpha", "/v2/beta", "/v2/beta"), handlePaths)
        val handleMethods = endpoints.filter { it["member"] == "handle" }.map { it["httpMethod"] as String }.toSet()
        assertEquals(setOf("PATCH", "POST"), handleMethods)
        assertEquals("/v1", endpoints.first()["path"])
    }

    @Test
    fun `private endpoint helpers cover all HTTP mapping branches`() {
        val extractor = EndpointExtractor()
        val extractHttpMethods =
            EndpointExtractor::class.java.getDeclaredMethod("extractHttpMethods", String::class.java, Map::class.java).apply {
                isAccessible = true
            }
        val extractStringValues =
            EndpointExtractor::class.java.getDeclaredMethod("extractStringValues", Any::class.java).apply { isAccessible = true }
        val combinePaths =
            EndpointExtractor::class.java.getDeclaredMethod("combinePaths", List::class.java, List::class.java).apply {
                isAccessible = true
            }

        assertEquals(
            listOf("GET"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.GetMapping", emptyMap<String, Any?>()))
        assertEquals(
            listOf("POST"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.PostMapping", emptyMap<String, Any?>()))
        assertEquals(
            listOf("PUT"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.PutMapping", emptyMap<String, Any?>()))
        assertEquals(
            listOf("DELETE"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.DeleteMapping", emptyMap<String, Any?>()))
        assertEquals(
            listOf("PATCH"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.PatchMapping", emptyMap<String, Any?>()))
        assertEquals(
            listOf("HEAD"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.RequestMapping", mapOf("method" to "HEAD")))
        assertEquals(
            listOf("REQUEST"),
            extractHttpMethods.invoke(extractor, "org.springframework.web.bind.annotation.RequestMapping", mapOf("method" to 123)))

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
        @Suppress("UNCHECKED_CAST") val info = spec["info"] as Map<String, Any?>
        val buildVersion = requireNotNull(System.getProperty("graphite.version"))
        assertEquals(buildVersion, info["version"])
        assertTrue(GraphiteVersionProvider().getVersion().contentEquals(arrayOf("graphite $buildVersion")))
        @Suppress("UNCHECKED_CAST") val paths = spec["paths"] as Map<String, Map<String, Any?>>
        assertTrue(paths.containsKey("/openapi.json"))
        assertTrue(paths.containsKey("/swagger.json"))
        assertTrue(paths.containsKey("/api/architecture/c4"))
        assertTrue(paths.containsKey("/api/cypher/graphs"))
        assertTrue(paths.containsKey("/api/topology"))
        @Suppress("UNCHECKED_CAST") val graphDetail = paths["/api/graphs/{graphId}"] as Map<String, Map<String, Any?>>
        assertTrue(graphDetail.containsKey("get"))
        assertFalse(paths.containsKey("/api/node/{id}"))
        assertFalse(paths.containsKey("/api/node/{id}/outgoing"))
        assertFalse(paths.containsKey("/api/node/{id}/incoming"))
        assertFalse(paths.containsKey("/api/subgraph"))
        assertTrue(paths.containsKey("/api/graphs/{graphId}/node/{id}"))
        assertTrue(paths.containsKey("/api/graphs/{graphId}/subgraph"))
        assertTrue(paths.containsKey("/api/graphs/{graphId}/resources/{path}"))
        assertTrue(paths.containsKey("/api/graphs/{graphId}/endpoints"))
        assertTrue(paths.containsKey("/api/graphs/{graphId}/architecture/c4"))
        listOf("nodes", "call-sites", "methods").forEach { route ->
            assertFalse(paths.containsKey("/api/$route"))
            assertFalse(paths.containsKey("/api/graphs/{graphId}/$route"))
        }
        @Suppress("UNCHECKED_CAST") val resources = paths["/api/resources/{path}"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val get = resources["get"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val parameters = get["parameters"] as List<Map<String, Any?>>
        assertEquals("path", parameters.single()["name"])
        @Suppress("UNCHECKED_CAST") val responses = get["responses"] as Map<String, Any?>
        assertTrue(responses.containsKey("409"))
        @Suppress("UNCHECKED_CAST") val c4 = paths["/api/architecture/c4"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val c4Get = c4["get"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val c4Parameters = c4Get["parameters"] as List<Map<String, Any?>>
        assertTrue(c4Parameters.any { it["name"] == "format" })
        assertTrue(c4Parameters.any { it["description"]?.toString()?.contains("dsl") == true })
        assertFalse(c4Parameters.any { it["name"] == "limit" })
    }

    @Test
    fun `GET api architecture c4 returns all views by default`() {
        val (code, body) = get("/api/architecture/c4")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("Graphite C4 Workspace", result["name"])
        @Suppress("UNCHECKED_CAST") val properties = result["properties"] as Map<String, String>
        assertEquals("all", properties["graphite.level"])
        @Suppress("UNCHECKED_CAST") val views = result["views"] as Map<String, Any?>
        assertTrue((views["systemContextViews"] as List<*>).isNotEmpty())
        assertTrue((views["containerViews"] as List<*>).isNotEmpty())
        assertTrue((views["componentViews"] as List<*>).isEmpty())
    }

    @Test
    fun `GET api architecture c4 returns context view`() {
        val (code, body) = get("/api/architecture/c4?level=context")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val properties = result["properties"] as Map<String, String>
        assertEquals("context", properties["graphite.level"])
        @Suppress("UNCHECKED_CAST") val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val contextViews = views["systemContextViews"] as List<Map<String, Any?>>
        assertEquals(1, contextViews.size)
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val people = model["people"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
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
        @Suppress("UNCHECKED_CAST") val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val containerViews = views["containerViews"] as List<Map<String, Any?>>
        assertEquals(1, containerViews.size)
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val application = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST") val containers = application["containers"] as List<Map<String, Any?>>
        assertEquals(0, containers.size, "C4 container level must not synthesize a runtime boundary for a library/package-only graph")
    }

    @Test
    fun `GET api architecture c4 returns component view`() {
        val (code, body) = get("/api/architecture/c4?level=component")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val views = result["views"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val componentViews = views["componentViews"] as List<Map<String, Any?>>
        assertTrue(componentViews.isEmpty())
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val application = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST") val containers = application["containers"] as List<Map<String, Any?>>
        val allComponents = containers.flatMap { (it["components"] as? List<Map<String, Any?>>).orEmpty() }
        assertTrue(allComponents.isEmpty())
    }

    @Test
    fun `GET api architecture c4 rejects invalid level`() {
        val (code, body) = get("/api/architecture/c4?level=invalid")
        assertEquals(400, code)
        val result: Map<String, Any?> = parseJson(body)
        assertEquals("Invalid 'level' parameter", result["error"])
        @Suppress("UNCHECKED_CAST") val allowed = result["allowed"] as List<String>
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
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        assertTrue(softwareSystems.isNotEmpty(), "Structured JSON must not be cropped by diagram limits")

        val (dslCode, dslBody) = get("/api/architecture/c4?level=context&format=dsl&limit=0")
        assertEquals(200, dslCode, "Expected 200, body: $dslBody")
        assertTrue(dslBody.contains("softwareSystem"), "Structured DSL must not be cropped by diagram limits")
    }

    @Test
    fun `GET api architecture c4 uses accept header before query format`() {
        val (plantCode, plantBody) = get("/api/architecture/c4?level=context&format=json", mapOf("Accept" to "text/vnd.plantuml"))
        assertEquals(200, plantCode, "Expected 200, body: $plantBody")
        assertTrue(plantBody.startsWith("@startuml"), "Expected PlantUML document, body: $plantBody")

        val (jsonCode, jsonBody) =
            get("/api/architecture/c4?level=context&format=plantuml", mapOf("Accept" to "application/vnd.structurizr+json"))
        assertEquals(200, jsonCode, "Expected 200, body: $jsonBody")
        assertTrue(jsonBody.trimStart().startsWith("{"), "Expected Structurizr JSON document, body: $jsonBody")

        val (dslCode, dslBody) = get("/api/architecture/c4?level=context&format=json", mapOf("Accept" to "text/vnd.structurizr.dsl"))
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
                    "plantuml" ->
                        assertTrue(body.contains("@startuml") && body.contains("@enduml"), "Expected PlantUML document for $level")
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
        @Suppress("UNCHECKED_CAST") val allowed = result["allowed"] as List<String>
        assertTrue(allowed.contains("json"))
        assertTrue(allowed.contains("dsl"))
        assertTrue(allowed.contains("mermaid"))
        assertTrue(allowed.contains("plantuml"))
    }

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
}
