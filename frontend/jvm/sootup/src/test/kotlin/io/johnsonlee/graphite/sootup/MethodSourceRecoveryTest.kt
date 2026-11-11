package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.AnalysisStatus
import io.johnsonlee.graphite.graph.MethodEvidence
import io.johnsonlee.graphite.input.LoaderConfig
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.frontend.BodySource
import sootup.core.frontend.ResolveException
import sootup.core.jimple.basic.NoPositionInformation
import sootup.core.model.Body
import sootup.core.model.MethodModifier
import sootup.core.signatures.MethodSignature
import sootup.java.core.JavaSootClass
import sootup.java.core.JavaSootMethod
import sootup.java.core.views.JavaView

internal class MethodSourceRecoveryTest : AdapterTestFixtures() {
    @Test
    fun `detached method failures retain evidence and release scratch while later methods survive`() {
        val logs = mutableListOf<String>()
        val adapter = adapter(JavaView(emptyList()), logs)
        val owner = FakeSootClass(identifierFactory.getClassType("sample.fake.Recovery"))
        val failures = resolutionFailures() + OutOfMemoryError("conversion exhausted")
        val bodies = mutableListOf<ScratchBodySource>()
        val methods =
            failures.mapIndexed { index, failure ->
                val signature = identifierFactory.getMethodSignature(owner.type, "broken$index", "void", emptyList())
                val body = ScratchBodySource(signature).also(bodies::add)
                object : JavaSootMethod(body, signature, emptyList(), emptyList(), NoPositionInformation.getInstance()) {
                    override fun getModifiers(): Set<MethodModifier> = throw failure
                }
            }
        val goodSignature = identifierFactory.getMethodSignature(owner.type, "survivor", "void", emptyList())
        val goodBody = ScratchBodySource(goodSignature).also(bodies::add)
        val good = JavaSootMethod(goodBody, goodSignature, emptyList(), emptyList(), NoPositionInformation.getInstance())
        adapter.methodCacheOwner = owner
        adapter.bytecodeMethodsCache[owner] = methods + good

        val survivors = checkNotNull(adapter.methodSources.streamMethodsOrNull(owner)).toList()

        val survivor = survivors.single()
        assertEquals(goodSignature, survivor.signature)
        assertNotSame(good, survivor, "a detached wrapper must own its body cache")
        assertSame(goodBody, survivor.bodySource)
        assertEquals(
            failures
                .mapIndexed { index, failure ->
                    "sample.fake.Recovery.broken$index()V" to MethodEvidence(AnalysisStatus.RESOLUTION_FAILED, failure.message)
                }
                .toMap(),
            adapter.completeness.analysisCompleteness().methods)
        assertEquals(failures.size, logs.size)
        failures.forEachIndexed { index, failure ->
            val diagnostic = if (failure is OutOfMemoryError) "OOM during streaming resolution" else checkNotNull(failure.message)
            assertTrue(logs.any { it.contains("broken$index") && it.contains(diagnostic) })
        }
        bodies.forEach { body ->
            assertNull(body.insnIndexCache)
            assertNull(body.localVarTypeAnnotationIndex)
        }
    }

    @Test
    fun `call graph resolution failures still release every class conversion buffer`() {
        resolutionFailures().forEach { failure ->
            val owner = FakeSootClass(identifierFactory.getClassType("sample.fake.CallGraphRecovery"))
            val signature = identifierFactory.getMethodSignature(owner.type, "main", "void", emptyList())
            val body = ScratchBodySource(signature)
            val method = JavaSootMethod(body, signature, emptyList(), emptyList(), NoPositionInformation.getInstance())
            var classReads = 0
            val view =
                object : JavaView(emptyList()) {
                    override fun getClasses(): Stream<JavaSootClass> {
                        classReads++
                        if (classReads == 1) throw failure
                        return Stream.of(owner)
                    }
                }
            val logs = mutableListOf<String>()
            val adapter = adapter(view, logs)
            adapter.methodCacheOwner = owner
            adapter.bytecodeMethodsCache[owner] = listOf(method)

            adapter.methodSources.processCallGraph()

            assertEquals(2, classReads, "cleanup must enumerate the view after resolution fails")
            assertEquals(listOf("Call graph unavailable for the current classpath: ${failure.message}"), logs)
            assertNull(body.insnIndexCache)
            assertNull(body.localVarTypeAnnotationIndex)
        }
    }

    @Test
    fun `truncated streaming annotations preserve failure evidence and allow the next declaration`() {
        val logs = mutableListOf<String>()
        val view = JavaView(emptyList())
        val adapter = adapter(view, logs)
        val owner = FakeSootClass(identifierFactory.getClassType("sample.fake.Annotations"))
        val node = sootup.java.bytecode.frontend.conversion.GraphiteClassNode(view, owner.classSource.analysisInputLocation)
        val broken = node.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "broken", "()V", null, null)
            as sootup.java.bytecode.frontend.conversion.AsmMethodSource
        broken.visibleAnnotations = mutableListOf(org.objectweb.asm.tree.AnnotationNode("Lsample/Tag;").apply {
            values = mutableListOf<Any>("value") // Missing the paired value in a foreign ASM provider's metadata.
        })
        val good = node.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "survivor", "()V", null, null)
            as sootup.java.bytecode.frontend.conversion.AsmMethodSource
        val methods = adapter.methodSources.streamMethods(owner, listOf(broken, good)).toList()
        assertEquals(listOf("survivor"), methods.map { it.name })
        val evidence = adapter.completeness.analysisCompleteness().methods.getValue("sample.fake.Annotations.broken()V")
        assertEquals(AnalysisStatus.RESOLUTION_FAILED, evidence.status)
        assertTrue(logs.single().contains("broken()V"))
    }

    @Test
    fun `immutable foreign conversion metadata is left intact without aborting cleanup`() {
        val adapter = adapter(JavaView(emptyList()), mutableListOf())
        val source = ImmutableScratchBodySource
        val before = ImmutableScratchBodySource.insnIndexCache
        adapter.methodSources.releaseConversionState(source)
        assertSame(before, ImmutableScratchBodySource.insnIndexCache)
    }

    private object ImmutableScratchBodySource {
        @JvmField val insnIndexCache: Any = Any()
    }

    private fun adapter(view: JavaView, logs: MutableList<String>): SootUpAdapter =
        SootUpAdapter(
            view,
            LoaderConfig(buildCallGraph = true, verbose = logs::add),
            extensions = emptyList(),
            inputLocationSources = emptyMap(),
            singleArtifactSource = "recovery.jar")

    private fun resolutionFailures(): List<Throwable> =
        listOf(
            ResolveException("cannot resolve method", java.nio.file.Path.of("recovery.class")),
            IndexOutOfBoundsException("truncated method metadata"),
            IllegalArgumentException("invalid method metadata"),
            IllegalStateException("unavailable method metadata"))

    private class ScratchBodySource(private val signature: MethodSignature) : BodySource {
        @JvmField var insnIndexCache: Any? = Any()
        @JvmField var localVarTypeAnnotationIndex: Any? = Any()

        override fun getSignature(): MethodSignature = signature

        override fun resolveBody(modifiers: Iterable<MethodModifier>): Body = error("body resolution is not required for detaching")

        override fun resolveAnnotationsDefaultValue(): Any? = null
    }
}
