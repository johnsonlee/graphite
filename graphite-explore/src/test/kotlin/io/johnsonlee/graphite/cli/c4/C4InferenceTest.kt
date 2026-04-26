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
        val ids = summarized.map { it["id"] }

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
        assertTrue(capabilityLayout.externalDependencies.any { it["id"] == "dependency:artifact:postgresql-42.7.3" })

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
        @Suppress("UNCHECKED_CAST")
        val elements = componentView["elements"] as List<Map<String, Any?>>

        assertTrue(elements.isNotEmpty())
        assertTrue(elements.all { it["type"] == "component" })
        assertTrue(elements.any { (it["packageUnits"] as? List<*>)?.contains("com.acme.checkout.api") == true })
        assertTrue(elements.all { it["containerId"] == "container:application-runtime" })
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
        val apiEndpoints = listOf(
            mapOf(
                "method" to "POST",
                "path" to "/checkout",
                "class" to apiType.className,
                "member" to "submit"
            )
        )
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
        val apiEndpoints: List<Map<String, Any?>>,
        val systemBoundary: String,
        val subject: SubjectDescriptor
    )
}
