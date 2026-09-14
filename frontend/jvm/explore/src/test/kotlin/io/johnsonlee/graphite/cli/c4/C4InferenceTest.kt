package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C4InferenceTest {

    @Test
    fun `SystemBoundaryDetector derives the dominant owned namespace from graph evidence`() {
        val fixture = checkoutFixture()

        val boundary = SystemBoundaryDetector.derive(fixture.methods, fixture.callSites)

        assertEquals("com.acme.checkout", boundary)
        assertTrue(SystemBoundaryDetector.isInternalClass("com.acme.checkout.api.CheckoutController", boundary))
        assertFalse(SystemBoundaryDetector.isInternalClass("com.acme.catalog.CatalogService", boundary))
    }

    @Test
    fun `SystemBoundaryDetector keeps single-root library namespaces at the public root`() {
        val publicType = TypeDescriptor("okhttp3.OkHttpClient")
        val internalType = TypeDescriptor("okhttp3.internal.connection.RealConnection")
        val publicMethod = method(publicType, "newCall")
        val internalMethod = method(internalType, "connect")

        val boundary = SystemBoundaryDetector.derive(
            methods = listOf(publicMethod, internalMethod),
            callSites = listOf(call(publicMethod, internalMethod))
        )

        assertEquals("okhttp3", boundary)
        assertEquals("okhttp3", SystemBoundaryDetector.dominantNamespace("okhttp3.internal.connection"))
        assertTrue(SystemBoundaryDetector.isInternalClass(publicType.className, boundary))
        assertTrue(SystemBoundaryDetector.isInternalClass(internalType.className, boundary))
        assertEquals("okhttp3.internal", SystemBoundaryDetector.internalPackageUnit(internalType.className, boundary))
    }

    @Test
    fun `ExternalSystemClassifier prefers artifact provenance over namespace fallback`() {
        val fixture = checkoutFixture()

        assertEquals(
            "artifact:postgresql-42.7.3",
            ExternalSystemClassifier.key(fixture.graph, "org.postgresql.Driver")
        )
        assertEquals(
            "namespace:com.partner.payment",
            ExternalSystemClassifier.key(fixture.graph, "com.partner.payment.PaymentGateway")
        )
        assertEquals(
            "namespace:okhttp3",
            ExternalSystemClassifier.key(fixture.graph, "okhttp3.internal.connection.RealConnection")
        )

        val summarized = ExternalSystemClassifier.summarize(
            fixture.graph,
            mapOf(
                "org.postgresql.Driver" to 3,
                "java.util.List" to 2,
                "com.partner.payment.PaymentGateway" to 1
            )
        )
        val ids = summarized.map { it.id }

        assertTrue("dependency:artifact:postgresql-42.7.3" in ids)
        assertTrue("dependency:runtime:java" in ids)
        assertTrue("dependency:namespace:com.partner.payment" in ids)
    }

    @Test
    fun `ContainerClusterer infers capability layout and application runtime boundary directly`() {
        val fixture = checkoutFixture()
        val capabilityLayout = ContainerClusterer.inferLayout(
            graph = fixture.graph,
            methods = fixture.methods,
            callSites = fixture.callSites,
            apiEndpoints = fixture.apiEndpoints,
            systemBoundary = fixture.systemBoundary,
            limit = 8
        )

        assertTrue(capabilityLayout.containers.isNotEmpty())
        assertTrue(capabilityLayout.unitToContainerId.containsKey("com.acme.checkout.api"))
        assertTrue(capabilityLayout.externalDependencies.any { it.id == "dependency:artifact:postgresql-42.7.3" })

        val runtimeLayout = ContainerClusterer.inferOperationalLayout(fixture.subject, capabilityLayout)

        assertEquals(1, runtimeLayout.containers.size)
        assertEquals("container:application-runtime", runtimeLayout.containers.single().id)
        assertEquals(
            capabilityLayout.unitToContainerId.keys,
            runtimeLayout.unitToContainerId.keys
        )
    }

    @Test
    fun `ComponentSelector builds components from capability evidence without CLI routing`() {
        val fixture = checkoutFixture()
        val capabilityLayout = ContainerClusterer.inferLayout(
            graph = fixture.graph,
            methods = fixture.methods,
            callSites = fixture.callSites,
            apiEndpoints = fixture.apiEndpoints,
            systemBoundary = fixture.systemBoundary,
            limit = 8
        )

        val componentView = ComponentSelector.buildView(
            graph = fixture.graph,
            methods = fixture.methods,
            callSites = fixture.callSites,
            apiEndpoints = fixture.apiEndpoints,
            systemBoundary = fixture.systemBoundary,
            limit = 8,
            subject = fixture.subject,
            capabilityLayout = capabilityLayout
        )
        val elements = componentView.elements

        assertTrue(elements.isNotEmpty())
        assertTrue(elements.all { it.type == C4ElementType.COMPONENT })
        assertTrue(elements.any { (it.properties["packageUnits"] as? List<*>)?.contains("com.acme.checkout.api") == true })
        assertTrue(elements.all { it.properties["containerId"] == "container:application-runtime" })
    }

    @Test
    fun `ComponentSelector treats utility naming as weak evidence only`() {
        assertFalse(
            ComponentSelector.isUtilityLikeClass(
                className = "com.acme.checkout.io.InvoiceWriter",
                systemBoundary = "com.acme.checkout",
                endpointCount = 0,
                incomingCrossContainer = 0,
                outgoingCrossContainer = 0,
                capabilityClassCount = 4
            )
        )
        assertFalse(
            ComponentSelector.isUtilityLikeClass(
                className = "com.acme.checkout.support.PaymentHelper",
                systemBoundary = "com.acme.checkout",
                endpointCount = 1,
                incomingCrossContainer = 0,
                outgoingCrossContainer = 0,
                capabilityClassCount = 4
            )
        )
        assertFalse(
            ComponentSelector.isUtilityLikeClass(
                className = "com.acme.checkout.support.PaymentHelper",
                systemBoundary = "com.acme.checkout",
                endpointCount = 0,
                incomingCrossContainer = 2,
                outgoingCrossContainer = 0,
                capabilityClassCount = 4
            )
        )
        assertTrue(
            ComponentSelector.isUtilityLikeClass(
                className = "com.acme.checkout.support.PaymentHelper",
                systemBoundary = "com.acme.checkout",
                endpointCount = 0,
                incomingCrossContainer = 0,
                outgoingCrossContainer = 0,
                capabilityClassCount = 4
            )
        )
    }

    @Test
    fun `C4 typed wire helpers cover enum and map compatibility boundaries`() {
        C4RelationshipType.entries.forEach { type ->
            assertNotNull(C4RelationshipKind.fromType(type))
        }
        C4ElementKind.entries.forEach { kind ->
            assertNotNull(kind.toArchitectureType())
        }

        assertEquals("builds on", diagramRelationshipLabel(C4RelationshipKind.BUILDS_ON))
        assertEquals("uses", diagramRelationshipLabel(mapOf("kind" to "unknown")))
        assertEquals(mapOf("kept" to 1), mapOf("drop" to null, "kept" to 1).toMapWithoutNulls())
        assertEquals("Runtime", diagramElementLabel(mapOf("name" to "Runtime", "properties" to mapOf("graphite.architectureType" to "runtime-platform"))))
        assertEquals("Controller", diagramElementLabel(mapOf("name" to "com.acme.Controller")))
        assertEquals(
            "Lucene Core",
            diagramElementLabel(
                mapOf(
                    "name" to "lucene-core-9.12.0",
                    "properties" to mapOf("graphite.architectureType" to "external-library")
                )
            )
        )
        assertEquals("runtime-platform", mapOf("graphite.architectureType" to "runtime-platform").asStructurizrProperty("graphite.architectureType"))

        val dependency = externalDependencyFromMap(
            mapOf(
                "id" to "dependency:runtime:java",
                "name" to "Java Runtime",
                "weight" to "7",
                "source" to "runtime",
                "kind" to "runtime",
                "confidence" to "high",
                "responsibility" to "Runs JVM bytecode",
                "artifacts" to listOf("java.base")
            )
        )
        assertEquals(ExternalDependencyKind.RUNTIME, dependency.kind)
        assertEquals(7, dependency.weight)

        val relationship = relationshipFromMap(
            mapOf(
                "from" to "a",
                "to" to "b",
                "type" to "runs-on",
                "kind" to "runs-on",
                "weight" to "3"
            )
        )
        assertEquals(C4RelationshipType.RUNS_ON, relationship.type)
        assertEquals(C4RelationshipKind.RUNS_ON, relationship.kind)
        assertEquals(3, relationship.weight)

        assertEquals(C4RelationshipKind.ORCHESTRATES, plannedEdge(mapOf("from" to "a", "to" to "b", "description" to "orchestrates", "kind" to "orchestrates")).kind)
        assertEquals(mapOf("ok" to 1), mapOf(1 to "ignored", "ok" to 1).asStringKeyMap())
        assertEquals(listOf(mapOf("id" to "x")), listOf(mapOf("id" to "x"), "ignored").asStructurizrMaps())
        assertEquals(C4ArchitectureType.EXTERNAL_SYSTEM, ExternalDependencyKind.EXTERNAL_SYSTEM.toArchitectureType())
        assertEquals(C4ArchitectureType.LIBRARY.wireName, C4ArchitectureType.LIBRARY.wireNameOrNull())
        assertEquals(DiagramLayerKind.TECHNOLOGY, DiagramLayerKind.fromArchitectureType(C4ArchitectureType.RUNTIME_PLATFORM))
        assertEquals(ApplicationDiagramLayerKind.INTERNAL_CAPABILITIES, ApplicationDiagramLayerKind.fromElementKind(null))
        assertEquals(0, componentScore("com.acme.Helper", "com.acme", 0, 0, 0, 0, 0))
        assertFalse(isUtilityLikeClass("com.acme.checkout.PaymentService", "com.acme.checkout"))
        assertTrue(clusterInternalPackageUnits(emptyList(), emptyMap()).isEmpty())
        assertEquals(MainReachability(0, 0, 0, 0), analyzeMainReachability(emptyList(), emptyList(), "com.acme"))
        assertEquals("Okhttp3", inferSubjectName("okhttp3"))
        assertEquals(ManifestMetadata(), readManifestMetadata(DefaultGraph.Builder().build()))
        assertEquals("org.apache", dominantNamespace("org.apache.lucene"))
        assertTrue(isReverseDnsNamespace(listOf("org", "apache")))
    }

    @Test
    fun `C4ModelInferer builds all views directly without ExploreCommand mapping`() {
        val fixture = checkoutFixture()

        val inferred = C4ModelInferer().buildViewModel(fixture.graph, "all", 8)

        assertEquals("all", inferred["level"])
        assertNotNull(inferred["context"])
        assertNotNull(inferred["container"])
        assertNotNull(inferred["component"])

        @Suppress("UNCHECKED_CAST")
        val context = inferred["context"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val contextElements = context["elements"] as List<Map<String, Any?>>

        assertTrue(contextElements.any { it["id"] == "system:application" })
        assertTrue(contextElements.any { it["id"] == "dependency:artifact:postgresql-42.7.3" })
    }

    private fun checkoutFixture(): CheckoutFixture {
        NodeId.reset()
        val appType = TypeDescriptor("com.acme.checkout.Application")
        val apiType = TypeDescriptor("com.acme.checkout.api.CheckoutController")
        val serviceType = TypeDescriptor("com.acme.checkout.service.CheckoutService")
        val repositoryType = TypeDescriptor("com.acme.checkout.repository.OrderRepository")
        val paymentType = TypeDescriptor("com.partner.payment.PaymentGateway")
        val postgresType = TypeDescriptor("org.postgresql.Driver")
        val runtimeType = TypeDescriptor("java.util.List")

        val mainMethod = MethodDescriptor(
            appType,
            "main",
            listOf(TypeDescriptor("java.lang.String[]")),
            TypeDescriptor("void")
        )
        val apiMethod = method(apiType, "submit")
        val serviceMethod = method(serviceType, "authorize")
        val repositoryMethod = method(repositoryType, "save")
        val paymentMethod = method(paymentType, "charge")
        val postgresMethod = method(postgresType, "connect")
        val runtimeMethod = method(runtimeType, "size", TypeDescriptor("int"))
        val callSites = listOf(
            call(mainMethod, apiMethod),
            call(apiMethod, serviceMethod),
            call(serviceMethod, repositoryMethod),
            call(serviceMethod, paymentMethod),
            call(repositoryMethod, postgresMethod),
            call(repositoryMethod, runtimeMethod)
        )
        val methods = listOf(mainMethod, apiMethod, serviceMethod, repositoryMethod)
        val graph = DefaultGraph.Builder()
            .addMethod(mainMethod)
            .addMethod(apiMethod)
            .addMethod(serviceMethod)
            .addMethod(repositoryMethod)
            .apply {
                callSites.forEach(::addNode)
                addClassOrigin(postgresType.className, "lib/postgresql-42.7.3.jar")
            }
            .build()
        val apiEndpoints = listOf(ApiEndpointEvidence(apiType.className, "/checkout"))
        val systemBoundary = SystemBoundaryDetector.derive(methods, callSites)
        val subject = SubjectDetector.infer(
            graph = graph,
            methods = methods,
            callSites = callSites,
            endpointCount = apiEndpoints.size,
            systemBoundary = systemBoundary
        )

        return CheckoutFixture(
            graph = graph,
            methods = methods,
            callSites = callSites,
            apiEndpoints = apiEndpoints,
            systemBoundary = systemBoundary,
            subject = subject
        )
    }

    private fun method(
        type: TypeDescriptor,
        name: String,
        returnType: TypeDescriptor = TypeDescriptor("void")
    ): MethodDescriptor =
        MethodDescriptor(type, name, emptyList(), returnType)

    private fun call(caller: MethodDescriptor, callee: MethodDescriptor): CallSiteNode =
        CallSiteNode(NodeId.next(), caller, callee, lineNumber = 1, receiver = null, arguments = emptyList())

    private data class CheckoutFixture(
        val graph: Graph,
        val methods: List<MethodDescriptor>,
        val callSites: List<CallSiteNode>,
        val apiEndpoints: List<ApiEndpointEvidence>,
        val systemBoundary: String,
        val subject: SubjectDescriptor
    )
}
