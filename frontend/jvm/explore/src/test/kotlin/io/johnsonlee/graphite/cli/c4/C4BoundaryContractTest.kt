package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class C4BoundaryContractTest {
    @Test
    fun `default inference overloads agree and missing entrypoints remain empty`() {
        val graph = DefaultGraph.Builder().build()
        val inferer = C4ModelInferer()
        val model = inferer.inferViewModel(graph, C4Level.CONTEXT)
        assertEquals(model, inferer.inferViewModel(graph, "context"))
        assertEquals(C4WireCodec.encode(model), inferer.buildViewModel(graph, "context"))
        assertEquals(C4Level.CONTEXT, assertNotNull(model.view).type)
        assertEquals(MainReachability(0, 0, 0, 0), SubjectDetector.analyzeMainReachability(emptyList(), emptyList(), "com.example"))
        assertEquals("Example", SubjectDetector.inferName("com.example"))
        assertEquals("routes to", diagramRelationshipLabel(C4RelationshipKind.ROUTES_TO))
    }

    @Test
    fun `component-only model synthesizes its containing runtime boundary`() {
        val component = C4Element(
            "component:checkout", C4ElementType.COMPONENT, "Checkout",
            extensionProperties = mapOf("containerId" to "runtime:checkout", "container" to "Checkout Runtime")
        )
        val model = C4ViewModel(C4Level.COMPONENT, C4Level.modelLevels,
            view = C4View(C4Level.COMPONENT, listOf(component), emptyList()))
        val workspace = C4StructurizrMapper().toWorkspaceModel(model)
        val container = workspace.model.softwareSystems.single().containers.single()
        assertEquals("runtime:checkout", container.id)
        assertEquals("Checkout Runtime", container.name)
        assertEquals(listOf("component:checkout"), container.components.map { it.id })
    }

    @Test
    fun `empty containers retain technology in DSL and configuration scalar lists roundtrip`() {
        val container = C4StructurizrElement("container:worker", "Worker", technology = "JVM")
        val system = C4StructurizrElement("system:app", "App", containers = listOf(container))
        val workspace = C4StructurizrWorkspace("Example", "", emptyMap(),
            C4StructurizrModel(emptyList(), listOf(system)),
            C4StructurizrViews(emptyList(), emptyList(), emptyList(), mapOf("custom" to listOf("one", 2, true))))
        assertTrue(C4StructurizrDslRenderer().render(workspace).contains("container_worker = container \"Worker\" \"\" \"JVM\""))
        val decoded = C4StructurizrCodec.decode(C4StructurizrCodec.encode(workspace))
        assertEquals(listOf("one", 2.0, true), decoded.views.configuration["custom"])
    }

    @Test
    fun `application service renders as a runtime boundary and invalid weights stay absent`() {
        assertEquals(ApplicationDiagramLayerKind.RUNTIME_BOUNDARY,
            ApplicationDiagramLayerKind.fromElementKind(C4ElementKind.APPLICATION_SERVICE))
        assertEquals(C4ContainerLayerRanks.CAPABILITY, ContainerClusterer.dependencyLayerRank("unrecognized"))
        assertNull(C4WireCodec.decodeRelationship(mapOf("from" to "a", "to" to "b", "weight" to true)).weight)
        val service = C4StructurizrElement("service:checkout", "Checkout", properties =
            mapOf("graphite.architectureType" to "application-service"))
        val workspace = C4StructurizrWorkspace("Services", "", mapOf("graphite.level" to "context"),
            C4StructurizrModel(emptyList(), listOf(service)), C4StructurizrViews(emptyList(), emptyList(), emptyList()))
        assertTrue(C4MermaidRenderer().render(C4StructurizrCodec.encode(workspace)).contains("service_checkout(["))
    }

    @Test
    fun `context mapper ignores component entries rather than making software systems`() {
        val model = C4ViewModel(C4Level.CONTEXT, C4Level.modelLevels, view = C4View(C4Level.CONTEXT,
            listOf(C4Element("component:invalid", C4ElementType.COMPONENT, "Invalid")), emptyList()))
        val workspace = C4StructurizrMapper().toWorkspaceModel(model)
        assertEquals(listOf(SUBJECT_FALLBACK_ID), workspace.model.softwareSystems.map { it.id })
        assertTrue(workspace.model.people.isEmpty())
    }
}
