package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.ArtifactEvidence
import io.johnsonlee.graphite.graph.ArtifactRole
import io.johnsonlee.graphite.graph.ClassEvidence
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.core.MethodDescriptor
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sootup.core.model.MethodModifier
import sootup.core.model.SootClass
import sootup.core.model.SootMethod
import sootup.core.signatures.MethodSignature

internal class AdapterResolutionHelpersTest : AdapterTestFixtures() {
    @Test
    fun `fallback methods visit every signature once in deterministic order`() {
        val methods = fallbackSyntheticMethods()
        val expected = methods.map { it.signature.toString() }.sorted()
        for (ordered in listOf(methods, methods.reversed())) {
            val (visited, _) = collectFallbackSyntheticIdentities(ordered)
            assertEquals(expected, visited)
            assertEquals(expected.associateWith { 1 }, visited.groupingBy { it }.eachCount())
        }
    }

    @Test
    fun `fallback method order preserves complete synthetic class and constructor identities`() {
        val methods = fallbackSyntheticMethods()
        val (_, forward) = collectFallbackSyntheticIdentities(methods)
        val (_, reversed) = collectFallbackSyntheticIdentities(methods.reversed())
        val className = methods.first().declaringClassType.fullyQualifiedName
        assertEquals(setOf(className, "$className.<init>($className)"), forward.keys)
        forward.values.forEach { assertEquals(32, it.length) }
        assertEquals(forward, reversed)
    }

    @Test
    fun `method resolution fallbacks handle missing bodies and failing classes`() {
        val adapter = createAdapter()
        val enumType = identifierFactory.getClassType("sample.fake.EmptyEnum")
        val clinitSignature = identifierFactory.getMethodSignature("sample.fake.EmptyEnum", "<clinit>", "void", emptyList())
        val clinit = object : FakeSootMethod(clinitSignature, listOf(MethodModifier.STATIC)) {
            override fun hasBody(): Boolean = false
        }
        val enumClass = FakeSootClass(enumType, methods = setOf(clinit), enumClass = true)

        invokePrivate<Unit>(adapter, "extractEnumValues", arrayOf(SootClass::class.java), enumClass)

        assertTrue(
            invokePrivate<Set<SootMethod>>(
                adapter,
                "resolveMethodsOrEmpty",
                arrayOf(SootClass::class.java),
                FakeSootClass(identifierFactory.getClassType("sample.fake.Oom"), failure = OutOfMemoryError("boom"))
            ).isEmpty()
        )
        assertTrue(
            invokePrivate<Set<SootMethod>>(
                adapter,
                "resolveMethodsOrEmpty",
                arrayOf(SootClass::class.java),
                FakeSootClass(identifierFactory.getClassType("sample.fake.Illegal"), failure = IllegalStateException("missing"))
            ).isEmpty()
        )

        writeField(adapter, "classesByNameCache", mapOf("sample.fake.EmptyEnum" to enumClass))
        assertTrue(
            invokePrivate<Set<String>>(
                adapter,
                "collectDeclaredMethodSubSignatures",
                arrayOf(String::class.java),
                "sample.fake.EmptyEnum"
            ).any { it.contains("<clinit>") }
        )

        val throwingMethod = object : FakeSootMethod(
            identifierFactory.getMethodSignature("sample.fake.Owner", "boom", "void", emptyList())
        ) {
            override fun hasBody(): Boolean = throw OutOfMemoryError("boom")
        }
        invokePrivate<Unit>(adapter, "processMethod", arrayOf(SootMethod::class.java), throwingMethod)

        // What each fallback leaves in the completeness evidence: a method the build gave up on
        // part way is INCOMPLETE with the failure's message, a body-less method says why it has
        // none, and a class whose methods could not be resolved is noted while it is the pass-2 class.
        val evidence = readField<Map<String, MethodEvidence>>(adapter, "methodEvidence")
        assertEquals(MethodEvidence(AnalysisStatus.INCOMPLETE, "boom"), evidence["sample.fake.Owner.boom()V"])
        for ((name, modifiers, status) in listOf(
            Triple("abstractOne", listOf(MethodModifier.ABSTRACT), AnalysisStatus.ABSTRACT),
            Triple("nativeOne", listOf(MethodModifier.NATIVE), AnalysisStatus.NATIVE),
            Triple("stub", emptyList(), AnalysisStatus.NO_BODY)
        )) {
            val bodyless = object : FakeSootMethod(
                identifierFactory.getMethodSignature("sample.fake.Owner", name, "void", emptyList()), modifiers
            ) {
                override fun hasBody(): Boolean = false
            }
            invokePrivate<Unit>(adapter, "processMethod", arrayOf(SootMethod::class.java), bodyless)
            assertEquals(MethodEvidence(status), evidence["sample.fake.Owner.$name()V"], name)
        }
        assertFalse(readField<Boolean>(adapter, "activeClassResolutionFailed"), "a failure outside pass 2 is not the pass-2 class's")
        // A method resolved again after a failure (the enum pass reads a <clinit> the method pass later streams) drops the record.
        val recovered = identifierFactory.getMethodSignature("sample.fake.Owner", "later", "void", emptyList())
        val record = adapter.operation(
            "recordMethodEvidence", MethodDescriptor::class.java, AnalysisStatus::class.java, String::class.java
        ).apply { isAccessible = true }
        val descriptor = invokePrivate<MethodDescriptor>(adapter, "toMethodDescriptor", arrayOf(MethodSignature::class.java), recovered)
        record.invoke(adapter, descriptor, AnalysisStatus.RESOLUTION_FAILED, "first")
        assertEquals(MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, "first"), evidence["sample.fake.Owner.later()V"])
        record.invoke(adapter, descriptor, AnalysisStatus.ANALYZED, null)
        assertNull(evidence["sample.fake.Owner.later()V"])
        val failing = FakeSootClass(identifierFactory.getClassType("sample.fake.Oom"), failure = OutOfMemoryError("boom"))
        writeField(adapter, "methodCacheOwner", failing)
        assertTrue(invokePrivate<Set<SootMethod>>(adapter, "resolveMethodsOrEmpty", arrayOf(SootClass::class.java), failing).isEmpty())
        assertTrue(readField<Boolean>(adapter, "activeClassResolutionFailed"))
        writeField(adapter, "methodCacheOwner", null)
    }

    @Test
    fun `a class whose methods cannot be resolved is analysed in name only, and the evidence says so`() {
        val broken = FakeSootClass(identifierFactory.getClassType("sample.fake.Broken"), failure = IllegalStateException("missing"))
        val whole = FakeSootClass(identifierFactory.getClassType("sample.fake.Whole"))
        val adapter = SootUpAdapter(
            view = FakeView(listOf(broken, whole), identifierFactory),
            config = LoaderConfig(includePackages = listOf("sample.fake"), buildCallGraph = false),
            extensions = emptyList(),
            resourceAccessor = EmptyResourceAccessor,
            inputLocationSources = emptyMap(),
            singleArtifactSource = "fake.jar",
            graphBuilder = DefaultGraph.Builder()
        )
        val completeness = assertNotNull(adapter.buildGraph().analysisCompleteness())
        assertEquals(ClassEvidence("fake.jar", AnalysisStatus.RESOLUTION_FAILED), completeness.classes["sample.fake.Broken"])
        assertEquals(ClassEvidence("fake.jar", AnalysisStatus.ANALYZED), completeness.classes["sample.fake.Whole"])
        assertEquals(mapOf("fake.jar" to ArtifactEvidence(ArtifactRole.APPLICATION)), completeness.artifacts)
        assertEquals(listOf("sample.fake"), completeness.scope.includePackages)
    }

    @Test
    fun `an extension's annotation enters the graph as an annotation of the bytecode does`() {
        val routes = FakeSootClass(identifierFactory.getClassType("sample.fake.Routes"))
        val extension = object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context: GraphiteContext) {
                val className = sootClass.type.fullyQualifiedName
                context.addMemberAnnotation(
                    className, GraphiteContext.CLASS_MEMBER, "acme.Router",
                    mapOf("prefix" to "/api", "weight" to 3, "tags" to listOf("a", "b"), "blank" to "", "opaque" to StringBuilder("x"))
                )
                context.addMemberAnnotation(className, "list", "acme.Route")
            }
        }
        val adapter = SootUpAdapter(
            view = FakeView(listOf(routes), identifierFactory),
            config = LoaderConfig(includePackages = listOf("sample.fake"), buildCallGraph = false),
            extensions = listOf(extension),
            resourceAccessor = EmptyResourceAccessor,
            inputLocationSources = emptyMap(),
            graphBuilder = DefaultGraph.Builder()
        )

        val graph = adapter.buildGraph()

        // Values are normalized as a bytecode annotation's are: an empty one is dropped, an object is its text.
        val router = mapOf("prefix" to "/api", "weight" to 3, "tags" to listOf("a", "b"), "blank" to null, "opaque" to "x")
        assertEquals(mapOf("acme.Router" to router), graph.memberAnnotations("sample.fake.Routes", "<class>"))
        assertEquals(mapOf("acme.Route" to emptyMap<String, Any?>()), graph.memberAnnotations("sample.fake.Routes", "list"))
        val nodes = graph.nodes(io.johnsonlee.graphite.core.AnnotationNode::class.java).toList()
        assertEquals(
            setOf(Triple("acme.Router", "<class>", router), Triple("acme.Route", "list", emptyMap())),
            nodes.filter { it.className == "sample.fake.Routes" }.map { Triple(it.name, it.memberName, it.values) }.toSet()
        )
    }

    private fun routesAdapter(extension: GraphiteExtension, builder: DefaultGraph.Builder = DefaultGraph.Builder()) = SootUpAdapter(
        view = FakeView(listOf(FakeSootClass(identifierFactory.getClassType("sample.fake.Routes"))), identifierFactory),
        config = LoaderConfig(includePackages = listOf("sample.fake"), buildCallGraph = false),
        extensions = listOf(extension),
        resourceAccessor = EmptyResourceAccessor,
        inputLocationSources = emptyMap(),
        graphBuilder = builder
    )

    private fun routeNodes(graph: io.johnsonlee.graphite.graph.Graph) =
        graph.nodes(io.johnsonlee.graphite.core.AnnotationNode::class.java).toList()
            .filter { it.className == "sample.fake.Routes" }
            .map { Triple(it.name, it.memberName, it.values) }

    @Test
    fun `a second write of an annotation a member already has is refused, and the first stays the only one`() {
        // Review of #229: the metadata is keyed by (class, member, name) and kept the second write, the graph kept a node
        // for each, and the two views of one fact disagreed after a save and a load.
        var refusal: IllegalArgumentException? = null
        var context: GraphiteContext? = null
        val extension = object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context0: GraphiteContext) {
                context = context0
                context0.addMemberAnnotation("sample.fake.Routes", "run", "audit.Route", mapOf("value" to "/first"))
                refusal = assertFailsWith<IllegalArgumentException> {
                    context0.addMemberAnnotation("sample.fake.Routes", "run", "audit.Route", mapOf("value" to "/second"))
                }
                // Another name on the same member is another fact.
                context0.addMemberAnnotation("sample.fake.Routes", "run", "audit.Resolved", mapOf("value" to "/second"))
            }
        }

        val graph = routesAdapter(extension).buildGraph()

        assertEquals(
            "An extension already recorded annotation 'audit.Route' on 'sample.fake.Routes#run'; a member holds one " +
                "annotation of a name. Record the second fact under a name of its own",
            refusal?.message
        )
        val expected = mapOf("audit.Route" to mapOf("value" to "/first"), "audit.Resolved" to mapOf("value" to "/second"))
        assertEquals(expected, graph.memberAnnotations("sample.fake.Routes", "run"))
        assertEquals(expected.map { (name, values) -> Triple(name, "run", values) }, routeNodes(graph))
        // A context kept past the build writes nothing, and says so.
        val late = assertFailsWith<IllegalStateException> {
            assertNotNull(context).addMemberAnnotation("sample.fake.Routes", "run", "audit.Late")
        }
        assertEquals(
            "annotation 'audit.Late' on 'sample.fake.Routes#run' was recorded after the build had read every class; " +
                "an extension writes during visit()",
            late.message
        )
    }

    @Test
    fun `an unhandled duplicate write fails the build`() {
        val extension = object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context: GraphiteContext) {
                repeat(2) { context.addMemberAnnotation("sample.fake.Routes", "run", "audit.Route", mapOf("value" to "/$it")) }
            }
        }
        assertFailsWith<IllegalArgumentException> { routesAdapter(extension).buildGraph() }
    }

    @Test
    fun `an annotation the bytecode carries is not an extension's to record`() {
        val carried = "An extension recorded annotation 'audit.Route' on 'sample.fake.Routes#run', which the bytecode already " +
            "carries. An extension states what the bytecode does not say: record the added fact under a name of its own"
        // Read before the extension writes: refused at the call.
        val early = routesAdapter(object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context: GraphiteContext) = Unit
        })
        early.metadata.openExtensionAnnotations()
        early.metadata.recordAnnotation("sample.fake.Routes", "run", "audit.Route", mapOf("value" to "/bytecode"))
        assertEquals(carried, assertFailsWith<IllegalArgumentException> {
            early.metadata.stageExtensionAnnotation("sample.fake.Routes", "run", "audit.Route", mapOf("value" to "/extension"))
        }.message)

        // Read after the extension wrote (it named a class the build had not reached): refused when the build commits, with
        // none of the extension's annotations written, the accepted ones included.
        val builder = DefaultGraph.Builder()
        val late = routesAdapter(object : GraphiteExtension {
            override fun visit(sootClass: SootClass, context: GraphiteContext) = Unit
        }, builder)
        late.metadata.openExtensionAnnotations()
        late.metadata.stageExtensionAnnotation("sample.fake.Routes", "list", "audit.Route", mapOf("value" to "/accepted"))
        late.metadata.stageExtensionAnnotation("sample.fake.Routes", "run", "audit.Route", mapOf("value" to "/extension"))
        late.metadata.recordAnnotation("sample.fake.Routes", "run", "audit.Route", mapOf("value" to "/bytecode"))
        assertEquals(carried, assertFailsWith<IllegalStateException> { late.metadata.commitExtensionAnnotations() }.message)
        val graph = builder.build()
        assertEquals(mapOf("audit.Route" to mapOf("value" to "/bytecode")), graph.memberAnnotations("sample.fake.Routes", "run"))
        assertEquals(emptyMap(), graph.memberAnnotations("sample.fake.Routes", "list"))
        assertEquals(listOf(Triple("audit.Route", "run", mapOf("value" to "/bytecode"))), routeNodes(graph))

        // The next build starts clean: what a failed build staged is gone, and the name is free again.
        late.metadata.openExtensionAnnotations()
        late.metadata.stageExtensionAnnotation("sample.fake.Routes", "list", "audit.Route", mapOf("value" to "/again"))
        late.metadata.commitExtensionAnnotations()
        assertEquals(
            mapOf("audit.Route" to mapOf("value" to "/again")),
            builder.build().memberAnnotations("sample.fake.Routes", "list")
        )
    }

    @Test
    fun `build graph reports progress for large class sets`() {
        val logs = mutableListOf<String>()
        val classes = (1..500).map {
            FakeSootClass(identifierFactory.getClassType("sample.fake.Progress$it"))
        }
        val adapter = SootUpAdapter(
            view = FakeView(classes, identifierFactory),
            config = LoaderConfig(includePackages = listOf("sample.fake"), buildCallGraph = false, verbose = logs::add),
            extensions = emptyList(),
            resourceAccessor = EmptyResourceAccessor,
            inputLocationSources = emptyMap(),
            graphBuilder = DefaultGraph.Builder()
        )

        adapter.buildGraph()

        assertTrue(logs.any { it.contains("Pass 1 processed 500 classes") })
        assertTrue(logs.any { it.contains("Pass 2 processed 500 classes") })
    }
}
