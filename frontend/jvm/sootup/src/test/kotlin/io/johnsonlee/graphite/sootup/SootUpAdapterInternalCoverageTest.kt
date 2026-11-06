package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sootup.core.jimple.common.Local
import sootup.java.core.jimple.basic.JavaLocal

internal class SootUpAdapterInternalCoverageTest : AdapterTestFixtures() {
    @Test
    fun `annotation normalization helpers cover empty and collection values`() {
        val adapter = createAdapter()

        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), null))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), "\"\""))
        assertEquals("alpha", invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), "\"alpha\""))
        assertEquals(listOf("a"), invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), listOf("a", "")))
        assertEquals(listOf("b"), invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), arrayOf("b", "")))
        assertEquals(1, invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), 1))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), emptyList<String>()))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), emptyArray<String>()))
        assertNull(invokePrivate<Any?>(adapter, "normalizeAnnotationValue", arrayOf(Any::class.java), object {
            override fun toString(): String = "null"
        }))
        assertEquals("", invokePrivate<String>(adapter, "getAnnotationFullName", arrayOf(Any::class.java), null))
        assertTrue(invokePrivate<Map<String, Any?>>(adapter, "getAnnotationValues", arrayOf(Any::class.java), null).isEmpty())
        assertEquals("sample.Annotation", invokePrivate<String>(adapter, "getAnnotationFullName", arrayOf(Any::class.java),
            FakeAnnotationUsage()))
        assertEquals(mapOf("value" to "\"ok\""), invokePrivate<Map<String, Any?>>(adapter, "getAnnotationValues",
            arrayOf(Any::class.java), FakeAnnotationUsage()))
    }

    /**
     * Propagation shares one set between a slot and the slots it fans out to: an interface
     * method's parameter reaches the same parameter of thousands of lambda classes, and a copy
     * per slot of a large set exhausted the heap on an Android build. Only a slot where
     * distinct sets meet gets a set of its own.
     */
    @Test
    fun `propagation shares target sets along flows and copies only where sets merge`() {
        val method = MethodDescriptor(TypeDescriptor("sample.lambda.AnonymousClassExample"), "share", emptyList(), TypeDescriptor("void"))
        val targets = (1..3).map { DispatchTarget.FunctionObject("sample.lambda.AnonymousClassExample\$$it") }
        val source = DispatchSlot.Local(method, "source")
        val empty = DispatchSlot.Local(method, "empty")
        val subset = DispatchSlot.Local(method, "subset")
        val superset = DispatchSlot.Local(method, "superset")
        val disjoint = DispatchSlot.Local(method, "disjoint")
        fun resolved(cycle: Boolean): Pair<SootUpAdapter, Set<DispatchTarget>> {
            val adapter = createAdapter(listOf("sample.lambda"))
            val shared = linkedSetOf(targets[0], targets[1])
            setTargets(adapter, source, shared)
            setTargets(adapter, subset, linkedSetOf(targets[0]))
            setTargets(adapter, superset, linkedSetOf(targets[0], targets[1], targets[2]))
            setTargets(adapter, disjoint, linkedSetOf(targets[2]))
            listOf(empty, subset, superset, disjoint).forEach { flow(adapter, source, it) }
            // a flow recorded twice is swept once
            flow(adapter, source, empty)
            if (cycle) flow(adapter, disjoint, source)
            invokePrivate<Unit>(adapter, "resolveFunctionalDispatch", emptyArray())
            assertEquals(setOf(targets[0], targets[1]), shared, "never mutated")
            assertEquals(setOf(targets[0], targets[1], targets[2]), targetsOf(adapter, superset))
            assertEquals(listOf(targets[2], targets[0], targets[1]), targetsOf(adapter, disjoint)?.toList())
            return adapter to shared
        }

        val (acyclic, shared) = resolved(cycle = false)
        assertSame(shared, targetsOf(acyclic, empty))
        assertSame(shared, targetsOf(acyclic, subset))

        // a flow back into the source closes a cycle: the next sweep carries the merged set
        // round, and every slot on the way shares that one set
        val (cyclic, _) = resolved(cycle = true)
        val merged = targetsOf(cyclic, disjoint)
        assertSame(merged, targetsOf(cyclic, source))
        assertSame(merged, targetsOf(cyclic, empty))
        assertSame(merged, targetsOf(cyclic, subset))
    }

    /**
     * A holder that more than 64 function values reach is saturated: it resolves nothing,
     * passes nothing on, and keeps nothing that arrives later, while what other paths bring
     * to the slots it flows to stays. Without the cap a context-insensitive fixpoint over a
     * large Android app resolves every call on a shared `Object` parameter to thousands of
     * lambdas and outgrows the heap.
     */
    @Test
    fun `a holder past the target cap saturates and passes nothing on`() {
        val adapter = createAdapter(listOf("sample.lambda"))
        val method = MethodDescriptor(TypeDescriptor("sample.lambda.AnonymousClassExample"), "saturate", emptyList(),
            TypeDescriptor("void"))
        val many = (1..65).map { DispatchTarget.FunctionObject("sample.lambda.AnonymousClassExample\$$it") }
        val precise = DispatchTarget.FunctionObject("sample.lambda.AnonymousClassExample\$precise")
        val hub = DispatchSlot.Local(method, "hub")
        val sink = DispatchSlot.Local(method, "sink")
        setTargets(adapter, sink, linkedSetOf(precise))
        flow(adapter, hub, sink)
        fun add(slot: DispatchSlot, targets: Collection<DispatchTarget>): Boolean =
            invokePrivate(adapter, "addTargets", arrayOf(Int::class.javaPrimitiveType!!, Set::class.java), slotId(adapter,
                slot), LinkedHashSet(targets))

        assertTrue(add(hub, many.take(64)))
        assertEquals(64, targetsOf(adapter, hub)?.size)
        assertTrue(add(hub, many.takeLast(1)))
        assertTrue(targetsOf(adapter, hub)!!.isEmpty(), "saturated")
        assertFalse(add(hub, listOf(precise)), "nothing changes a saturated holder")
        assertTrue(targetsOf(adapter, hub)!!.isEmpty())
        assertFalse(add(sink, targetsOf(adapter, hub)!!), "a saturated input adds nothing")

        invokePrivate<Unit>(adapter, "resolveFunctionalDispatch", emptyArray())
        assertEquals(setOf(precise), targetsOf(adapter, sink))

        // the same cap holds the function values a local is tracked as holding within a method
        val register = JavaLocal("\$u0", identifierFactory.getType("java.lang.Object"), emptyList())
        val dynamicTargets = readField<MutableMap<Any, Set<DispatchTarget>>>(adapter, "dynamicTargets")
        val key = invokePrivate<Any>(adapter, "localKey", arrayOf(MethodDescriptor::class.java, String::class.java), method, register.name)
        many.forEach { target ->
            invokePrivate<Unit>(adapter, "mergeLocalTargets", arrayOf(MethodDescriptor::class.java, Local::class.java,
                Collection::class.java), method, register, listOf(target))
        }
        assertTrue(dynamicTargets[key]!!.isEmpty(), "saturated")
        invokePrivate<Unit>(adapter, "mergeLocalTargets", arrayOf(MethodDescriptor::class.java, Local::class.java,
            Collection::class.java), method, register, listOf(precise))
        assertTrue(dynamicTargets[key]!!.isEmpty(), "nothing changes a saturated local")
    }
}
