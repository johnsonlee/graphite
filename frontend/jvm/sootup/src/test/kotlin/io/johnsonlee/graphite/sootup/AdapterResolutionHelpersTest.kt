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
