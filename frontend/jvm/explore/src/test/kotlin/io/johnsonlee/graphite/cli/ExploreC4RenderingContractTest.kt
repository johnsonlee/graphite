package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.TestResourceAccessor
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.JavaArchiveLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class ExploreC4RenderingContractTest : ExploreCommandTestSupport() {
    @Test
    fun `C4 renderers cover truncation and component fallback plans`() {
        val contextWorkspace = contextTruncationWorkspace()
        val componentWorkspace =
            mapOf(
                "properties" to mapOf("graphite.level" to "component"),
                "model" to
                    mapOf(
                        "people" to emptyList<Map<String, Any?>>(),
                        "softwareSystems" to
                            listOf(
                                mapOf(
                                    "id" to "system:application",
                                    "name" to "Application",
                                    "description" to "Application",
                                    "properties" to mapOf("graphite.architectureType" to "software-system"),
                                    "containers" to
                                        listOf(
                                            mapOf(
                                                "id" to "container:runtime",
                                                "name" to "Runtime",
                                                "description" to "Runtime",
                                                "properties" to mapOf("graphite.kind" to "application-runtime"),
                                                "components" to
                                                    listOf(
                                                        mapOf(
                                                            "id" to "component:standalone",
                                                            "name" to "Standalone",
                                                            "description" to "Standalone",
                                                            "properties" to
                                                                mapOf("graphite.architectureType" to "application-component")))))))))
        val componentRelationships =
            (1..205).map { index ->
                mapOf(
                    "id" to "component-rel-$index",
                    "destinationId" to "component:target-$index",
                    "description" to "uses",
                    "properties" to mapOf("graphite.relationshipKind" to "uses", "graphite.weight" to "1"))
            }
        val componentTruncationWorkspace =
            mapOf(
                "properties" to mapOf("graphite.level" to "component"),
                "model" to
                    mapOf(
                        "people" to emptyList<Map<String, Any?>>(),
                        "softwareSystems" to
                            listOf(
                                mapOf(
                                    "id" to "system:application",
                                    "name" to "Application",
                                    "description" to "Application",
                                    "properties" to mapOf("graphite.architectureType" to "software-system"),
                                    "containers" to
                                        listOf(
                                            mapOf(
                                                "id" to "container:runtime",
                                                "name" to "Runtime",
                                                "description" to "Runtime",
                                                "properties" to mapOf("graphite.kind" to "application-runtime"),
                                                "components" to
                                                    listOf(
                                                        mapOf(
                                                            "id" to "component:source",
                                                            "name" to "Source",
                                                            "description" to "Source",
                                                            "properties" to mapOf("graphite.architectureType" to "application-component"),
                                                            "relationships" to componentRelationships)) +
                                                        (1..205).map { index ->
                                                            mapOf(
                                                                "id" to "component:target-$index",
                                                                "name" to "Target $index",
                                                                "description" to "Target $index",
                                                                "properties" to
                                                                    mapOf("graphite.architectureType" to "application-component"))
                                                        }))))))
        val c4 = C4ArchitectureService()

        assertTrue(c4.renderMermaid(contextWorkspace).contains("Mermaid view truncated"))
        assertTrue(c4.renderPlantUml(contextWorkspace).contains("PlantUML view truncated"))
        assertTrue(c4.renderMermaid(componentWorkspace).contains("component_standalone"))
        assertTrue(c4.renderMermaid(componentTruncationWorkspace).contains("Mermaid view truncated"))
    }

    @Test
    fun `C4 Structurizr DSL renderer covers elements relationships and views`() {
        val workspace =
            mapOf(
                "name" to "Architecture \"Workspace\"",
                "model" to
                    mapOf(
                        "people" to
                            listOf(
                                mapOf(
                                    "id" to "person:operator",
                                    "name" to "Operator",
                                    "description" to "Runs the system",
                                    "relationships" to
                                        listOf(
                                            mapOf("id" to "rel-1", "destinationId" to "system:application", "description" to "starts")))),
                        "softwareSystems" to
                            listOf(
                                mapOf(
                                    "id" to "system:application",
                                    "name" to "Application",
                                    "description" to "Subject",
                                    "relationships" to
                                        listOf(
                                            mapOf("id" to "rel-2", "destinationId" to "dependency:queue", "description" to "publishes to")),
                                    "containers" to
                                        listOf(
                                            mapOf(
                                                "id" to "container:runtime",
                                                "name" to "Runtime",
                                                "description" to "JVM runtime",
                                                "technology" to "Java",
                                                "relationships" to
                                                    listOf(
                                                        mapOf(
                                                            "id" to "rel-3",
                                                            "destinationId" to "dependency:queue",
                                                            "description" to "uses")),
                                                "components" to
                                                    listOf(
                                                        mapOf(
                                                            "id" to "component:api",
                                                            "name" to "API",
                                                            "description" to "Handles requests",
                                                            "technology" to "Kotlin",
                                                            "relationships" to
                                                                listOf(
                                                                    mapOf(
                                                                        "id" to "rel-4",
                                                                        "destinationId" to "component:service",
                                                                        "description" to "calls"))),
                                                        mapOf(
                                                            "id" to "component:service",
                                                            "name" to "Service",
                                                            "description" to "Coordinates work",
                                                            "technology" to "Kotlin"))))),
                                mapOf("id" to "dependency:queue", "name" to "Queue", "description" to "External queue"),
                                mapOf(
                                    "id" to "dependency.queue",
                                    "name" to "Queue Alias",
                                    "description" to "Exercises identifier collision handling"))),
                "views" to
                    mapOf(
                        "systemContextViews" to listOf(mapOf("key" to "context", "softwareSystemId" to "system:application")),
                        "containerViews" to listOf(mapOf("key" to "container", "softwareSystemId" to "system:application")),
                        "componentViews" to listOf(mapOf("key" to "component", "containerId" to "container:runtime"))))

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

        val main =
            MethodDescriptor(TypeDescriptor("com.acme.Main"), "main", listOf(TypeDescriptor("java.lang.String[]")), TypeDescriptor("void"))
        val internalMethods =
            listOf(
                method("com.acme.api.SearchController", "handle"),
                method("com.acme.service.SearchService", "search"),
                method("com.acme.repository.SearchRepository", "query"),
                method("com.acme.common.JsonSupport", "parse"),
                method("com.acme.model.DocumentModel", "shape"))
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
            mapOf("value" to "/search"))
        builder.addMemberAnnotation(
            "com.acme.api.SearchController", "handle", "org.springframework.web.bind.annotation.PostMapping", mapOf("value" to "/query"))

        val workspace = ExploreCommand().buildC4Model(builder.build(), "all", 50)
        @Suppress("UNCHECKED_CAST") val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val people = model["people"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val systems = model["softwareSystems"] as List<Map<String, Any?>>
        val application = systems.first { it["id"] == "system:application" }
        @Suppress("UNCHECKED_CAST") val containers = application["containers"] as List<Map<String, Any?>>
        val containerRelationships = containers.flatMap { (it["relationships"] as? List<Map<String, Any?>>).orEmpty() }
        val dependencyRelationships = systems.flatMap { (it["relationships"] as? List<Map<String, Any?>>).orEmpty() }

        assertTrue(people.any { it["id"] == "person:http-clients" })
        assertEquals(1, containers.size, "C4 container level should show the deployable runtime boundary, not package clusters")
        val runtimeProperties = containers.single()["properties"] as Map<*, *>
        assertEquals("application-service", runtimeProperties["graphite.kind"])
        assertTrue((runtimeProperties["graphite.internalCapabilities"] as? String).orEmpty().contains("com.acme.api"))
        @Suppress("UNCHECKED_CAST") val runtimeComponents = containers.single()["components"] as List<Map<String, Any?>>
        val apiComponent =
            runtimeComponents.first {
                @Suppress("UNCHECKED_CAST") val properties = it["properties"] as Map<String, String>
                properties["graphite.classes"].orEmpty().contains("com.acme.api.SearchController")
            }
        @Suppress("UNCHECKED_CAST") val apiComponentProperties = apiComponent["properties"] as Map<String, String>
        assertTrue(
            apiComponentProperties["graphite.classes"].orEmpty().contains("com.acme.api.SearchController"),
            "C4 component identity should be the capability group, with implementation classes kept as evidence")
        assertFalse(runtimeComponents.any { it["id"] == "component:com.acme.api.SearchController" })
        assertFalse(containerRelationships.any { (it["destinationId"] as? String)?.startsWith("container:") == true })
        assertTrue(containerRelationships.any { it["destinationId"] == "dependency:artifact:lucene-core-9.12.0" })
        assertTrue(systems.any { it["id"] == "dependency:artifact:log4j-api-2.23.1" })
        assertTrue(dependencyRelationships.any { it["destinationId"] == "dependency:artifact:log4j-api-2.23.1" })
        val luceneRelationships =
            systems.first { it["id"] == "dependency:artifact:lucene-core-9.12.0" }["relationships"] as? List<Map<String, Any?>>
                ?: emptyList()
        val log4jRelationships =
            systems.first { it["id"] == "dependency:artifact:log4j-api-2.23.1" }["relationships"] as? List<Map<String, Any?>> ?: emptyList()
        assertTrue(luceneRelationships.any { it["destinationId"] == "dependency:artifact:log4j-api-2.23.1" })
        assertFalse(luceneRelationships.any { it["destinationId"] == "dependency:runtime:java" })
        assertTrue(log4jRelationships.any { it["destinationId"] == "dependency:runtime:java" })
    }

    @Test
    fun `C4 context groups sibling library artifacts into one system`() {
        val appType = TypeDescriptor("com.acme.App")
        val appMethod = MethodDescriptor(appType, "run", emptyList(), TypeDescriptor("void"))
        val luceneCore =
            MethodDescriptor(TypeDescriptor("org.apache.lucene.index.IndexWriter"), "commit", emptyList(), TypeDescriptor("void"))
        val luceneQueries =
            MethodDescriptor(TypeDescriptor("org.apache.lucene.queries.CustomScoreQuery"), "rewrite", emptyList(), TypeDescriptor("void"))
        val luceneHighlighter =
            MethodDescriptor(
                TypeDescriptor("org.apache.lucene.search.uhighlight.UnifiedHighlighter"), "highlight", emptyList(), TypeDescriptor("void"))
        val runtime = MethodDescriptor(TypeDescriptor("java.util.List"), "size", emptyList(), TypeDescriptor("int"))
        val builder =
            DefaultGraph.Builder()
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
        val bootGraph =
            DefaultGraph.Builder()
                .setResources(
                    TestResourceAccessor(
                        mapOf(
                            JavaArchiveLayout.META_INF_MANIFEST to
                                """
                                Manifest-Version: 1.0
                                ${JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE}: ${JavaArchiveLayout.SPRING_BOOT_WAR_LAUNCHER}
                                ${JavaArchiveLayout.START_CLASS_ATTRIBUTE}: com.example.very.Long
                                 Application
    
                            """
                                    .trimIndent())))
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
        val cliGraph =
            DefaultGraph.Builder()
                .addMethod(cliMain)
                .addMethod(cliWorker)
                .addNode(CallSiteNode(NodeId.next(), cliMain, cliWorker, 1, null, emptyList()))
                .build()

        fun subject(workspace: Map<String, Any?>): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST") val model = workspace["model"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val systems = model["softwareSystems"] as List<Map<String, Any?>>
            return systems.first { (it["id"] as? String)?.startsWith("system:") == true }
        }

        val explore = ExploreCommand()
        assertEquals("Long App", subject(explore.buildC4Model(bootGraph, "context", 50))["name"])
        listOf(JavaArchiveLayout.SPRING_BOOT_LAUNCH_WAR_LAUNCHER, JavaArchiveLayout.SPRING_BOOT_PROPERTIES_LAUNCHER).forEach { launcher ->
            val launcherGraph =
                DefaultGraph.Builder()
                    .setResources(
                        TestResourceAccessor(
                            mapOf(
                                JavaArchiveLayout.META_INF_MANIFEST to
                                    """
                                    Manifest-Version: 1.0
                                    ${JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE}: $launcher
                                    ${JavaArchiveLayout.START_CLASS_ATTRIBUTE}: com.example.very.LongApplication
    
                                """
                                        .trimIndent())))
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
        @Suppress("UNCHECKED_CAST") val model = explore.buildC4Model(cliGraph, "context", 50)["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val people = model["people"] as List<Map<String, Any?>>
        assertTrue(people.any { it["id"] == "person:operators" })
    }

    @Test
    fun `POST api cypher returns query results`() {
        val (code, body) = post("/api/cypher", """{"query": "MATCH (n:IntConstant) RETURN n.value"}""")
        assertEquals(200, code, "Expected 200, body: $body")
        assertTrue(body.contains("columns"), "Response should contain 'columns', body: $body")
    }

    @Test
    fun `POST api cypher returns a structured empty result when nothing matches`() {
        val (code, body) = post("/api/cypher", """{"query":"MATCH (n:IntConstant) WHERE n.value = -1 RETURN n.value"}""")

        assertEquals(200, code, body)
        val result: Map<String, Any?> = parseJson(body)
        assertEquals(0.0, result[API_FIELD_ROW_COUNT])
        assertEquals(emptyList<Any>(), result[API_FIELD_ROWS])
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
    fun `GET api cypher applies server-side row limit`() {
        val query = java.net.URLEncoder.encode("MATCH (n) RETURN n.id", "UTF-8")
        val (code, body) = get("/api/cypher?query=$query&limit=1")
        assertEquals(200, code, "Expected 200, body: $body")
        val result: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val rows = result["rows"] as List<Map<String, Any?>>
        assertEquals(1, rows.size)
        assertEquals(1.0, result["rowCount"])
    }
}
