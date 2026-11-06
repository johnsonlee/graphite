package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.TestResourceAccessor
import io.johnsonlee.graphite.cli.c4.C4ArchitectureService
import io.johnsonlee.graphite.cli.c4.C4ModelInferer
import io.johnsonlee.graphite.cli.c4.C4StructurizrMapper
import io.johnsonlee.graphite.cli.c4.diagramRelationshipLabel
import io.johnsonlee.graphite.cli.c4.reduceDiagramTransitiveEdges
import io.johnsonlee.graphite.cli.c4.reduceSharedInternalFanIn
import io.johnsonlee.graphite.cli.c4.reduceSharedLibraryFanIn
import io.johnsonlee.graphite.cli.c4.reduceTransitiveContainerEdges
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
internal class ExploreC4ViewsContractTest : ExploreCommandTestSupport() {
    @Test
    fun `C4 inferer derives artifact dependencies from graph evidence`() {
        val appType = TypeDescriptor("com.acme.App")
        val appMethod = MethodDescriptor(appType, "run", emptyList(), TypeDescriptor("void"))
        val guavaType = TypeDescriptor("com.google.common.collect.ImmutableList")
        val gsonType = TypeDescriptor("com.google.gson.Gson")
        val builder =
            DefaultGraph.Builder()
                .addMethod(appMethod)
                .addNode(
                    CallSiteNode(
                        NodeId.next(),
                        appMethod,
                        MethodDescriptor(guavaType, "of", emptyList(), TypeDescriptor("java.util.List")),
                        1,
                        null,
                        emptyList()))
                .addNode(
                    CallSiteNode(
                        NodeId.next(),
                        appMethod,
                        MethodDescriptor(
                            gsonType, "toJson", listOf(TypeDescriptor("java.lang.Object")), TypeDescriptor("java.lang.String")),
                        2,
                        null,
                        emptyList()))
        builder.addClassOrigin(guavaType.className, "lib/guava-32.1.3-jre.jar")
        builder.addClassOrigin(gsonType.className, "lib/gson-2.10.1.jar")
        builder.addArtifactDependency("guava-32.1.3-jre", "gson-2.10.1", 4)

        val inferred = C4ModelInferer().buildViewModel(builder.build(), "context", 50)
        @Suppress("UNCHECKED_CAST") val view = inferred["view"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val elements = view["elements"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val relationships = view["relationships"] as List<Map<String, Any?>>

        assertTrue(elements.any { it["id"] == "dependency:artifact:guava-32.1.3-jre" && it["source"] == "artifact" })
        assertTrue(elements.any { it["id"] == "dependency:artifact:gson-2.10.1" && it["source"] == "artifact" })
        assertTrue(
            relationships.any {
                it["from"] == "dependency:artifact:guava-32.1.3-jre" &&
                    it["to"] == "dependency:artifact:gson-2.10.1" &&
                    it["kind"] == "builds-on"
            })
    }

    @Test
    fun `buildC4Model detects Spring Boot app subject from Start-Class artifact`() {
        val startType = TypeDescriptor("com.example.boot.StartApplication")
        val serviceType = TypeDescriptor("com.example.boot.Service")
        val mainMethod = MethodDescriptor(startType, "main", listOf(TypeDescriptor("java.lang.String[]")), TypeDescriptor("void"))
        val serviceMethod = MethodDescriptor(serviceType, "run", emptyList(), TypeDescriptor("void"))
        val graph =
            DefaultGraph.Builder()
                .setResources(
                    TestResourceAccessor(
                        mapOf(
                            JavaArchiveLayout.META_INF_MANIFEST to
                                """
                                Manifest-Version: 1.0
                                ${JavaArchiveLayout.MAIN_CLASS_ATTRIBUTE}: ${JavaArchiveLayout.SPRING_BOOT_LAUNCH_JAR_LAUNCHER}
                                ${JavaArchiveLayout.START_CLASS_ATTRIBUTE}: com.example.boot.StartApplication
                                
                            """
                                    .trimIndent())))
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
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
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
        val graph =
            DefaultGraph.Builder()
                .addMethod(mainMethod)
                .addMethod(internalMethod)
                .addNode(CallSiteNode(NodeId.next(), mainMethod, internalMethod, 1, null, emptyList()))
                .addNode(CallSiteNode(NodeId.next(), internalMethod, runtimeMethod, 1, null, emptyList()))
                .build()

        val result = ExploreCommand().buildC4Model(graph, "container", 50)
        @Suppress("UNCHECKED_CAST") val model = result["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val subject = softwareSystems.first { (it["id"] as? String)?.startsWith("system:") == true }
        @Suppress("UNCHECKED_CAST") val containers = subject["containers"] as List<Map<String, Any?>>
        val kinds = containers.mapNotNull { (it["properties"] as? Map<*, *>)?.get("graphite.kind") as? String }

        assertTrue(kinds.isNotEmpty())
        assertFalse(kinds.contains("integration"), "Runtime-only calls must not imply integration kind: $kinds")
    }

    @Test
    fun `C4 edge reducers keep strong direct evidence and trim shared fan-in`() {
        fun edge(from: String, to: String, weight: Int, kind: String = "uses") =
            mapOf("from" to from, "to" to to, "weight" to weight, "kind" to kind, "description" to kind)

        val transitive =
            listOf(
                edge("container:a", "container:b", 10),
                edge("container:b", "container:c", 10),
                edge("container:a", "container:c", 5),
                edge("container:a", "container:d", 10),
                edge("container:b", "container:d", 1))
        val reducedTransitive = reduceTransitiveContainerEdges(transitive)
        assertFalse(reducedTransitive.any { it["from"] == "container:a" && it["to"] == "container:c" })
        assertTrue(reducedTransitive.any { it["from"] == "container:a" && it["to"] == "container:d" })

        val diagramTransitive = reduceDiagramTransitiveEdges(transitive)
        assertTrue(diagramTransitive.any { it["from"] == "container:a" && it["to"] == "container:d" })
        val runtimeTransitive =
            listOf(
                edge("dependency:library:lucene", "dependency:artifact:log4j-api", 3, "builds-on"),
                edge("dependency:artifact:log4j-api", "dependency:runtime:java", 1, "runs-on"),
                edge("dependency:library:lucene", "dependency:runtime:java", 1, "runs-on"))
        val reducedRuntime = reduceDiagramTransitiveEdges(runtimeTransitive)
        assertFalse(reducedRuntime.any { it["from"] == "dependency:library:lucene" && it["to"] == "dependency:runtime:java" })

        val libraryFanIn =
            listOf(
                edge("container:a", "dependency:artifact:shared", 5, "depends-on"),
                edge("container:b", "dependency:artifact:shared", 10, "depends-on"),
                edge("container:c", "dependency:artifact:shared", 1, "depends-on"),
                edge("dependency:artifact:shared", "dependency:runtime:java", 1, "runs-on"))
        val reducedLibrary = reduceSharedLibraryFanIn(libraryFanIn)
        assertEquals(3, reducedLibrary.size)
        assertFalse(reducedLibrary.any { it["from"] == "container:c" })
        assertTrue(reducedLibrary.any { it["kind"] == "runs-on" })

        val internalFanIn =
            listOf(
                edge("container:a", "container:shared", 1),
                edge("container:b", "container:shared", 5),
                edge("container:c", "container:shared", 2),
                edge("container:d", "container:shared", 4))
        val reducedInternal = reduceSharedInternalFanIn(internalFanIn)
        assertEquals(3, reducedInternal.size)
        assertFalse(reducedInternal.any { it["from"] == "container:a" })
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
        val builder =
            DefaultGraph.Builder()
                .addMethod(apiMethod)
                .addMethod(serviceMethod)
                .addMethod(commonMethod)
                .addNode(CallSiteNode(NodeId.next(), apiMethod, serviceMethod, 1, null, emptyList()))
                .addNode(CallSiteNode(NodeId.next(), serviceMethod, commonMethod, 2, null, emptyList()))
                .addNode(
                    CallSiteNode(
                        NodeId.next(),
                        apiMethod,
                        MethodDescriptor(luceneType, "commit", emptyList(), TypeDescriptor("void")),
                        3,
                        null,
                        emptyList()))
                .addNode(
                    CallSiteNode(
                        NodeId.next(),
                        serviceMethod,
                        MethodDescriptor(logType, "info", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("void")),
                        4,
                        null,
                        emptyList()))
                .addNode(
                    CallSiteNode(
                        NodeId.next(),
                        commonMethod,
                        MethodDescriptor(runtimeType, "size", emptyList(), TypeDescriptor("int")),
                        5,
                        null,
                        emptyList()))
        builder.addClassOrigin(luceneType.className, JavaArchiveLayout.bootInfLibEntry("lucene-core-9.12.0.jar"))
        builder.addClassOrigin(logType.className, JavaArchiveLayout.bootInfLibEntry("log4j-api-2.23.1.jar"))
        builder.addArtifactDependency("lucene-core-9.12.0", "log4j-api-2.23.1", 3)
        builder.addMemberAnnotation(
            apiType.className, "<class>", "org.springframework.web.bind.annotation.RequestMapping", mapOf("value" to "/search"))
        builder.addMemberAnnotation(
            apiType.className, "handle", "org.springframework.web.bind.annotation.GetMapping", mapOf("value" to "/query"))

        val c4 = C4ArchitectureService()
        val workspace = c4.buildModel(builder.build(), "all", 50)
        val mermaid = c4.renderMermaid(workspace)
        val plantUml = c4.renderPlantUml(workspace)

        assertTrue(mermaid.contains("Lucene Core"))
        assertTrue(mermaid.contains("Log4j API"))
        assertTrue(mermaid.contains("builds on"))
        assertFalse(plantUml.contains("Application Layer"), plantUml)
        assertTrue(plantUml.contains("Library Layer"), plantUml)

        val runtimeOnly =
            DefaultGraph.Builder()
                .addMethod(commonMethod)
                .addNode(
                    CallSiteNode(
                        NodeId.next(),
                        commonMethod,
                        MethodDescriptor(runtimeType, "size", emptyList(), TypeDescriptor("int")),
                        6,
                        null,
                        emptyList()))
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
            val externalMethod = MethodDescriptor(TypeDescriptor("partner$index.Client"), "call", emptyList(), TypeDescriptor("void"))
            builder.addNode(CallSiteNode(NodeId.next(), appMethod, externalMethod, index, null, emptyList()))
        }

        val c4 = C4ArchitectureService()
        val workspace = c4.buildModel(builder.build(), "context")
        @Suppress("UNCHECKED_CAST") val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val softwareSystems = model["softwareSystems"] as List<Map<String, Any?>>
        val dependencyCount = softwareSystems.count { (it["id"]?.toString() ?: "").startsWith("dependency:namespace:partner") }
        val mermaid = c4.renderMermaid(workspace)
        val renderedDependencyCount = Regex("dependency_namespace_partner\\d+").findAll(mermaid).map { it.value }.toSet().size

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
                "properties" to mapOf("graphite.relationshipKind" to kind, "graphite.weight" to weight.toString()))

        fun container(id: String, name: String, kind: String, relationships: List<Map<String, Any?>> = emptyList()): Map<String, Any?> =
            mapOf(
                "id" to id,
                "name" to name,
                "description" to "$name responsibility",
                "technology" to "JVM bytecode",
                "properties" to mapOf("graphite.kind" to kind, "graphite.architectureType" to "application-component"),
                "relationships" to relationships)

        val workspace =
            mapOf(
                "properties" to mapOf("graphite.level" to "container"),
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
                                            container(
                                                id = "container:service",
                                                name = "Service",
                                                kind = "orchestrator",
                                                relationships =
                                                    listOf(
                                                        relationship("rel-1", "container:db", "uses", 1000),
                                                        relationship("rel-2", "container:locator", "orchestrates", 900))),
                                            container("container:db", "Db", "capability"),
                                            container(
                                                id = "container:locator",
                                                name = "Locator",
                                                kind = "shared-capability",
                                                relationships = listOf(relationship("rel-3", "container:config", "uses", 80))),
                                            container("container:config", "Config", "shared-capability"))))))

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
        val graphiteModel =
            mapOf(
                "level" to "context",
                "availableLevels" to C4ArchitectureService.LEVELS,
                "view" to
                    mapOf(
                        "type" to "context",
                        "elements" to
                            listOf(
                                mapOf(
                                    "id" to "system:a",
                                    "type" to "softwareSystem",
                                    "name" to "A",
                                    "kind" to "application",
                                    "architectureType" to "software-system"),
                                mapOf(
                                    "id" to "system:b",
                                    "type" to "softwareSystem",
                                    "name" to "B",
                                    "kind" to "external-system",
                                    "architectureType" to "external-system"),
                                mapOf(
                                    "id" to "dependency:raw",
                                    "type" to "softwareSystem",
                                    "name" to "Raw",
                                    "kind" to "library",
                                    "architectureType" to "external-library")),
                        "relationships" to
                            listOf(
                                mapOf("from" to "system:a", "to" to "system:b", "type" to "uses"),
                                mapOf("from" to "system:b", "to" to "dependency:raw"))))
        val workspace = C4StructurizrMapper().toWorkspace(graphiteModel)
        @Suppress("UNCHECKED_CAST") val model = workspace["model"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val systems = model["softwareSystems"] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val relationships = systems.flatMap { (it["relationships"] as? List<Map<String, Any?>>).orEmpty() }
        val mapType = arrayOf<Class<*>>(Map::class.java)

        assertTrue(relationships.any { it["description"] == "uses" })
        assertEquals(
            "collaborates with", diagramRelationshipLabel(mapOf("properties" to mapOf("graphite.relationshipKind" to "collaborates-with"))))
        assertEquals(
            "external-library", invokeDiagramPrivate<String>("diagramArchitectureTypeOf", mapType, mapOf("id" to "dependency:raw")))
        assertEquals("application-component", invokeDiagramPrivate<String>("diagramArchitectureTypeOf", mapType, mapOf("id" to "plain")))
        assertEquals(
            "external-systems",
            invokeDiagramPrivate<String>(
                "diagramLayerOf", mapType, mapOf("properties" to mapOf("graphite.architectureType" to "external-system"))))
        assertEquals(
            "Interface Adapters",
            invokeDiagramPrivate<String>(
                "applicationContainerLayerOf", mapType, mapOf("properties" to mapOf("graphite.kind" to "interface"))))
    }
}
