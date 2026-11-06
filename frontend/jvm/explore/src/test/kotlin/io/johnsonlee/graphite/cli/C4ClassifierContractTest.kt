package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cli.c4.C4ModelInferer
import io.johnsonlee.graphite.cli.c4.C4Relationship
import io.johnsonlee.graphite.cli.c4.C4RelationshipKind
import io.johnsonlee.graphite.cli.c4.C4RelationshipType
import io.johnsonlee.graphite.cli.c4.ExternalDependency
import io.johnsonlee.graphite.cli.c4.ExternalDependencyKind
import io.johnsonlee.graphite.cli.c4.ExternalSystemClassifier
import io.johnsonlee.graphite.cli.c4.externalArchitectureType
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class C4ClassifierContractTest : ExploreCommandTestSupport() {
    private val inferer = C4ModelInferer()
    private val intType = Int::class.javaPrimitiveType!!
    private val listType = arrayOf<Class<*>>(List::class.java)
    private val stringType = arrayOf<Class<*>>(String::class.java)
    private val fourIntTypes = arrayOf<Class<*>>(intType, intType, intType, intType)
    private val fiveIntTypes = arrayOf<Class<*>>(intType, intType, intType, intType, intType)
    private val twoStringTypes = arrayOf<Class<*>>(String::class.java, String::class.java)
    private val threeStringTypes = arrayOf<Class<*>>(String::class.java, String::class.java, String::class.java)
    private val graphListTypes = arrayOf<Class<*>>(Graph::class.java, List::class.java)
    private val listListTypes = arrayOf<Class<*>>(List::class.java, List::class.java)
    private val setStringListTypes = arrayOf<Class<*>>(Set::class.java, String::class.java, List::class.java)
    private val setMapMapStringTypes = arrayOf<Class<*>>(Set::class.java, Map::class.java, Map::class.java, String::class.java)
    private val containerPairTypes = arrayOf<Class<*>>(String::class.java, String::class.java, String::class.java, String::class.java)
    private val componentPairTypes =
        arrayOf<Class<*>>(
            String::class.java, String::class.java, String::class.java, String::class.java, String::class.java, String::class.java)
    private val componentResponsibilityTypes = arrayOf<Class<*>>(String::class.java, intType, intType, intType, intType)

    @Test
    fun `external dependency classification preserves runtime and namespace evidence`() {
        assertEquals("runtime:java", ExternalSystemClassifier.key(null, "java.util.List"))
        assertEquals("runtime:kotlin", ExternalSystemClassifier.key(null, "kotlin.String"))
        assertEquals("runtime:scala", ExternalSystemClassifier.key(null, "scala.Option"))
        assertEquals("namespace:joptsimple", ExternalSystemClassifier.key(null, "joptsimple.OptionParser"))
        assertEquals("org", ExternalSystemClassifier.namespaceGroup("org.Foo.Bar"))
        assertEquals("", ExternalSystemClassifier.namespaceGroup(""))
        assertEquals("alpha", ExternalSystemClassifier.namespaceGroup("alpha.beta.gamma.delta"))

        assertEquals("Java Runtime", ExternalSystemClassifier.name(null, "runtime:java", emptyList()))
        assertEquals("Kotlin Runtime", ExternalSystemClassifier.name(null, "runtime:kotlin", emptyList()))
        assertEquals("Scala Runtime", ExternalSystemClassifier.name(null, "runtime:scala", emptyList()))
        assertEquals("lucene-core-9.12.0", ExternalSystemClassifier.name(null, "artifact:lucene-core-9.12.0", emptyList()))
        assertEquals("org.apache.lucene", ExternalSystemClassifier.name(null, "namespace:org.apache.lucene", emptyList()))
        val originFallbackGraph = DefaultGraph.Builder().apply { addClassOrigin("custom.External", "lib/custom-client.jar") }.build()
        assertEquals("lib/custom-client.jar", ExternalSystemClassifier.name(originFallbackGraph, "opaque", listOf("custom.External")))
        assertEquals("artifact", ExternalSystemClassifier.source("artifact:lucene-core"))
        assertEquals("runtime", ExternalSystemClassifier.source("runtime:java"))
        assertEquals("namespace", ExternalSystemClassifier.source("namespace:org.apache"))
        assertEquals("runtime", ExternalSystemClassifier.kind("runtime:java"))
        assertEquals("library", ExternalSystemClassifier.kind("artifact:lucene-core"))
        assertEquals("external-system", ExternalSystemClassifier.kind("namespace:org.apache"))
        assertEquals("high", ExternalSystemClassifier.confidence("artifact:lucene-core"))
        assertEquals("high", ExternalSystemClassifier.confidence("runtime:java"))
        assertEquals("medium", ExternalSystemClassifier.confidence("namespace:org.apache"))
        assertEquals(
            "Provides language and platform runtime services used by the application",
            ExternalSystemClassifier.responsibility("runtime", "runtime:java"))
        assertEquals(
            "Provides reusable library capabilities linked from the application runtime",
            ExternalSystemClassifier.responsibility("library", "artifact:lucene-core"))
        assertEquals(
            "Represents an inferred external software system boundary grouped from referenced classes",
            ExternalSystemClassifier.responsibility("external-system", "namespace:org.apache"))
        assertEquals("Language and platform runtime supporting the subject system", ExternalSystemClassifier.description("runtime"))
        assertEquals("External collaborator inferred from code graph evidence", ExternalSystemClassifier.description("other"))
    }

    @Test
    fun `architecture kinds and dependency direction preserve classifier results`() {
        assertEquals(
            "Search",
            invokeExplorePrivate<String>(inferer, "inferSubjectName", threeStringTypes, "com.acme.search", null, "com.acme.SearchApp"))
        assertEquals(
            "application-service",
            invokeExplorePrivate<String>(inferer, "inferContainerArchitectureType", stringType, "application-runtime"))
        assertEquals(
            "application-service", invokeExplorePrivate<String>(inferer, "inferContainerArchitectureType", stringType, "interface"))
        assertEquals(
            "application-component", invokeExplorePrivate<String>(inferer, "inferContainerArchitectureType", stringType, "capability"))
        assertEquals(
            "application-service", invokeExplorePrivate<String>(inferer, "inferComponentArchitectureType", stringType, "coordination"))
        assertEquals(
            "application-component",
            invokeExplorePrivate<String>(inferer, "inferComponentArchitectureType", stringType, "domain-component"))
        assertEquals("runtime-platform", externalArchitectureType(ExternalDependencyKind.RUNTIME).wireName)
        assertEquals("external-library", externalArchitectureType(ExternalDependencyKind.LIBRARY).wireName)
        assertEquals("external-system", externalArchitectureType(ExternalDependencyKind.EXTERNAL_SYSTEM).wireName)

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
                    emptyList<String>())
                .contains("mutually dependent"))
        assertEquals(
            "Api and Service",
            invokeExplorePrivate<String>(
                inferer,
                "inferContainerName",
                setMapMapStringTypes,
                linkedSetOf("com.acme.api", "com.acme.service"),
                linkedMapOf("com.acme.api" to 10, "com.acme.service" to 10),
                mapOf("api" to 1, "service" to 1),
                "com.acme"))
        assertEquals("entrypoint", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 1, 0, 0, 0))
        assertEquals("integration", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 0, 0, 2))
        assertEquals("orchestrator", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 0, 2, 0))
        assertEquals("shared-capability", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 2, 0, 0))
        assertEquals("coordination", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 1, 1, 0))
        assertEquals("domain-component", invokeExplorePrivate<String>(inferer, "inferComponentKind", fourIntTypes, 0, 0, 0, 0))

        assertEquals(
            "routes-to",
            invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "interface", "capability"))
        assertEquals(
            "orchestrates",
            invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "orchestrator", "capability"))
        assertEquals(
            "uses",
            invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "capability", "shared-capability"))
        assertEquals(
            "uses", invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "capability", "integration"))
        assertEquals(
            "collaborates-with",
            invokeExplorePrivate<String>(inferer, "inferArchitecturalDependencyKind", twoStringTypes, "capability", "capability"))
        assertEquals(
            "container:transport" to "container:core",
            invokeExplorePrivate<Pair<String, String>>(
                inferer,
                "canonicalContainerRelationshipPair",
                containerPairTypes,
                "container:core",
                "container:transport",
                "shared-capability",
                "orchestrator"))
        assertEquals(
            "container:transport" to "container:core",
            invokeExplorePrivate<Pair<String, String>>(
                inferer,
                "canonicalContainerRelationshipPair",
                containerPairTypes,
                "container:transport",
                "container:core",
                "orchestrator",
                "shared-capability"))
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
                "orchestrator"))
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
                "orchestrator"))
    }

    @Test
    fun `runtime boundaries and callsite only subjects preserve evidence`() {
        val dependencyBoundaryGraph = DefaultGraph.Builder().apply { addArtifactDependency("client-a", "missing-b", 1) }.build()
        val dependencyBoundary =
            invokeExplorePrivate<Set<String>>(
                inferer,
                "selectRuntimeBoundaryLibraryIds",
                graphListTypes,
                dependencyBoundaryGraph,
                listOf(
                    ExternalDependency(
                        id = "dependency:artifact:client-a",
                        name = "client-a",
                        weight = 1,
                        source = "artifact",
                        kind = ExternalDependencyKind.LIBRARY,
                        confidence = "high",
                        responsibility = "test dependency")))
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
                listOf(CallSiteNode(NodeId.next(), callsiteOnlyMethod, callsiteOnlyTarget, 1, null, emptyList()))))
    }

    @Test
    fun `component readability and responsibilities preserve classifier branches`() {
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 1, 0, 0, 0, 0)
                .contains("inbound system interface"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 1, 1, 4)
                .contains("outward-facing capability"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 4, 1, 0)
                .contains("shared internal capability"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 1, 4, 0)
                .contains("orchestration boundary"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 10, 2, 2, 0)
                .contains("balanced internal collaboration"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferContainerResponsibility", fiveIntTypes, 0, 0, 0, 0, 0)
                .contains("cohesive internal capability"))

        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 1, 0, 0, 0)
                .contains("external requests"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 0, 0, 3)
                .contains("external collaborators"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 0, 3, 0)
                .contains("Coordinates work"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 3, 0, 0)
                .contains("shared internal capability"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 2, 2, 0)
                .contains("coordination path"))
        assertTrue(
            invokeExplorePrivate<String>(inferer, "inferComponentResponsibility", componentResponsibilityTypes, "Search", 0, 0, 0, 0)
                .contains("structurally central"))

        assertEquals(
            "Api routes work to Service",
            invokeExplorePrivate<String>(inferer, "describeArchitecturalDependency", threeStringTypes, "routes-to", "Api", "Service"))
        assertEquals(
            "Api orchestrates Service",
            invokeExplorePrivate<String>(inferer, "describeArchitecturalDependency", threeStringTypes, "orchestrates", "Api", "Service"))
        assertEquals(
            "Api uses Service",
            invokeExplorePrivate<String>(inferer, "describeArchitecturalDependency", threeStringTypes, "uses", "Api", "Service"))
        assertEquals(
            "Api collaborates with Service",
            invokeExplorePrivate<String>(
                inferer, "describeArchitecturalDependency", threeStringTypes, "collaborates-with", "Api", "Service"))
        fun componentRelationship(from: String, to: String, kind: String = "uses", weight: Int = 1) =
            C4Relationship(
                from = from,
                to = to,
                type = C4RelationshipType.fromWire(kind),
                kind = C4RelationshipKind.fromWire(kind),
                description = kind,
                weight = weight)
        assertEquals(
            1,
            invokeExplorePrivate<List<C4Relationship>>(
                    inferer, "selectReadableComponentRelationships", listType, listOf(componentRelationship("component:a", "component:b")))
                .size)
        val fallbackSelected =
            invokeExplorePrivate<List<C4Relationship>>(
                inferer,
                "selectReadableComponentRelationships",
                listType,
                (1..8).map { index -> componentRelationship("component:source", "component:target$index", weight = 20 - index) })
        assertTrue(fallbackSelected.size >= 6)
        assertTrue(fallbackSelected.size <= 12)
        assertFalse(fallbackSelected.any { it.from.isBlank() || it.to.isBlank() })
        val collaborationOnlySelected =
            invokeExplorePrivate<List<C4Relationship>>(
                inferer,
                "selectReadableComponentRelationships",
                listType,
                listOf(
                    componentRelationship("component:a", "component:b", "collaborates-with", 2),
                    componentRelationship("component:b", "component:c", "collaborates-with", 1)))
        assertEquals(2, collaborationOnlySelected.size)
    }

    @Test
    fun `package scopes fallback views and utility capabilities preserve model contents`() {
        val callsiteOnlyMethod = MethodDescriptor(TypeDescriptor("com.callsites.Root"), "run", emptyList(), TypeDescriptor("void"))
        assertEquals("(default)", invokeExplorePrivate<String>(inferer, "internalPackageUnit", twoStringTypes, "NoPackage", "com.example"))
        assertEquals(
            "com.example.api",
            invokeExplorePrivate<String>(inferer, "internalPackageUnit", twoStringTypes, "com.example.api.Controller", "com.example"))
        assertEquals(
            "org.apache",
            invokeExplorePrivate<String>(inferer, "internalPackageUnit", twoStringTypes, "org.apache.lucene.IndexWriter", "com.example"))

        val fallbackModel = inferer.buildViewModel(DefaultGraph.Builder().addMethod(callsiteOnlyMethod).build(), "unknown", 5)
        assertEquals("all", fallbackModel["level"])
        @Suppress("UNCHECKED_CAST") assertEquals("context", (fallbackModel["context"] as Map<String, Any?>)["type"])

        val utilMethod = MethodDescriptor(TypeDescriptor("com.example.common.JsonUtil"), "parse", emptyList(), TypeDescriptor("void"))
        val otherUtilMethod = MethodDescriptor(TypeDescriptor("com.example.common.StringUtil"), "trim", emptyList(), TypeDescriptor("void"))
        val utilityMainMethod =
            MethodDescriptor(
                TypeDescriptor("com.example.UtilityApplication"),
                "main",
                listOf(TypeDescriptor("java.lang.String[]")),
                TypeDescriptor("void"))
        val utilityOnlyComponentModel =
            inferer.buildViewModel(
                DefaultGraph.Builder()
                    .addMethod(utilityMainMethod)
                    .addMethod(utilMethod)
                    .addMethod(otherUtilMethod)
                    .addNode(CallSiteNode(NodeId.next(), utilityMainMethod, utilMethod, 1, null, emptyList()))
                    .addNode(CallSiteNode(NodeId.next(), utilMethod, otherUtilMethod, 2, null, emptyList()))
                    .build(),
                "component",
                5)
        @Suppress("UNCHECKED_CAST") val utilityView = utilityOnlyComponentModel["view"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val utilityElements = utilityView["elements"] as List<Map<String, Any?>>
        assertTrue(utilityElements.any { (it["classes"] as? List<*>)?.contains("com.example.common.JsonUtil") == true })
    }
}
