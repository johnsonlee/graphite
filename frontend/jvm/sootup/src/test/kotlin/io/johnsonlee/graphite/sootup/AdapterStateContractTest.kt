package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.analysis.DispatchSlot
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.jimple.common.Local
import sootup.core.jimple.common.expr.JStaticInvokeExpr
import sootup.core.jimple.common.expr.JVirtualInvokeExpr

internal class AdapterStateContractTest : AdapterTestFixtures() {
    private val caller = MethodDescriptor(TypeDescriptor("sample.Caller"), "call", emptyList(), TypeDescriptor("void"))

    @Test
    fun `resource fallback retains field generic signatures and treats unavailable method bytes as unknown`() {
        val writer = org.objectweb.asm.ClassWriter(0)
        writer.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "sample/Generic", null, "java/lang/Object", null)
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "names", "Ljava/util/List;",
            "Ljava/util/List<Ljava/lang/String;>;", null).visitEnd()
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "count", "I", null, null).visitEnd()
        writer.visitEnd()
        val adapter = createAdapter(resourceAccessor = object : io.johnsonlee.graphite.input.ResourceAccessor {
            override fun list(pattern: String) = emptySequence<io.johnsonlee.graphite.input.ResourceEntry>()
            override fun open(path: String): java.io.InputStream =
                if (path == "sample/Generic.class") writer.toByteArray().inputStream()
                else throw java.io.IOException("resource no longer available")
        })
        val owner = adapter.view.getClass(identifierFactory.getClassType("sample.resources.MessagesListBundle_ko_KR")).orElseThrow()
        assertEquals(mapOf("names" to "Ljava/util/List<Ljava/lang/String;>;"),
            adapter.descriptors.fieldSignatures(owner as sootup.java.core.JavaSootClass, "sample.Generic"))
        kotlin.test.assertNull(adapter.bytecodeMethods.loadMethodNodesFromResource(owner))
    }

    @Test
    fun `call emission without statement retains caller callee and unknown source line`() {
        val adapter = createAdapter()
        val signature = identifierFactory.getMethodSignature("sample.Callee", "run", "void", emptyList())
        adapter.calls.processInvokeExpr(JStaticInvokeExpr(signature, emptyList()), caller, null)
        val call = adapter.graphBuilder.build().nodes(io.johnsonlee.graphite.core.CallSiteNode::class.java).single()
        assertEquals(caller, call.caller)
        assertEquals("sample.Callee", call.callee.declaringClass.className)
        assertEquals("run", call.callee.name)
        kotlin.test.assertNull(call.lineNumber)
    }

    @Test
    fun `saturated seeds mark lost dispatch evidence without inventing any targets`() {
        val adapter = createAdapter()
        val slot = DispatchSlot.Local(caller, "callback")
        adapter.dispatchFlows.seedSlot(slot, SATURATED_TARGETS)
        val id = adapter.dispatchResolution.slotId(slot)
        assertTrue(adapter.lostSeeds[id])
        assertTrue(adapter.slotTargets[id].isNullOrEmpty())
        adapter.dispatchFlows.seedSlot(slot, emptyList())
        assertTrue(adapter.lostSeeds[id], "an empty seed cannot erase already lost evidence")
    }

    @Test
    fun `unknown call arguments retain separate positions without fabricating value nodes`() {
        val adapter = createAdapter()
        val signature = identifierFactory.getMethodSignature("sample.Callee", "unknown", "java.lang.Object", emptyList())
        val unknown = JStaticInvokeExpr(signature, emptyList())
        val ids = adapter.callKinds.argumentNodeIds(listOf(unknown, unknown), caller)
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1], "unknown argument occurrences are distinct, not aliased")
        val graph = adapter.graphBuilder.build()
        assertTrue(graph.nodes(Node::class.java).none(), "unresolved expressions must not become fabricated constants")
    }

    @Test
    fun `unknown setLocale argument preserves the preceding tracked builder state`() {
        val adapter = createAdapter()
        val builderType = identifierFactory.getClassType("java.util.Locale\$Builder")
        val receiver = Local("builder", builderType)
        val locale = Local("unknownLocale", identifierFactory.getClassType("java.util.Locale"))
        val signature = identifierFactory.getMethodSignature(builderType, "setLocale", builderType, listOf(locale.type))
        val key = adapter.metadata.localKey(caller, receiver.name)
        val before = SootUpAdapter.LocaleBuilderSpec(language = "en", country = "US")
        adapter.localeBuilderSpecsByLocal[key] = before
        LocaleTracking(adapter).updateLocaleBuilderState(caller, signature, JVirtualInvokeExpr(receiver, signature, listOf(locale)), null)
        assertSame(before, adapter.localeBuilderSpecsByLocal[key])
        assertEquals("en_US", adapter.localeBuilderSpecsByLocal[key]?.toLocaleSpec())
    }
}
