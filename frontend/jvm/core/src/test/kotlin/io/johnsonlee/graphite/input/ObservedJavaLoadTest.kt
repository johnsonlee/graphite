package io.johnsonlee.graphite.input

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ObservedJavaLoadTest {
    @Test
    fun `method identity preserves parameter and return descriptors`() {
        val method = MethodDescriptor(TypeDescriptor("sample.Reader"), "read", listOf(TypeDescriptor("int")),
            TypeDescriptor("java.lang.String"))
        val identity = JvmMethodIdentity.of(method)
        assertEquals("sample.Reader", identity.owner)
        assertEquals("read", identity.name)
        assertEquals("(I)Ljava/lang/String;", identity.descriptor)
        assertEquals("sample.Reader.read(I)Ljava/lang/String;", identity.identity)
        assertNotEquals(identity.identity, JvmMethodIdentity(identity.owner, identity.name, "(I)Ljava/lang/Object;").identity)
    }

    @Test
    fun `fold evidence owns immutable copies of counts and nested reasons`() {
        val counts = linkedMapOf(2 to 3)
        val reasons = mutableListOf("unresolved target")
        val unsupported = linkedMapOf<Int, List<String>>(4 to reasons)
        val evidence = MethodFoldObservation(counts, unsupported, 12, 8)
        counts[2] = 9
        reasons.clear()
        unsupported.clear()
        assertEquals(mapOf(2 to 3), evidence.foldedCalls)
        assertEquals(mapOf(4 to listOf("unresolved target")), evidence.unsupportedReasons)
        assertEquals(12, evidence.statementsBefore)
        assertEquals(8, evidence.statementsAfter)
        assertFailsWith<UnsupportedOperationException> { (evidence.foldedCalls as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (evidence.unsupportedReasons as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (evidence.unsupportedReasons.getValue(4) as MutableList).clear() }
    }

    @Test
    fun `default result requires and preserves the graph final completeness`() {
        val completeness = AnalysisCompleteness(AnalysisIdentity(), emptyMap(), emptyMap(), emptyMap())
        val graph = DefaultGraph.Builder().setAnalysisCompleteness(completeness).build()
        val result = ObservedJavaLoad(graph, null)
        assertSame(graph, result.graph)
        assertSame(completeness, result.completeness)
        assertNull(result.foldReport)
        assertFailsWith<IllegalArgumentException> { ObservedJavaLoad(DefaultGraph.Builder().build(), null) }
    }

    @Test
    fun `load results can be implemented using core contracts alone`() {
        val graph = DefaultGraph.Builder().build()
        val completeness = AnalysisCompleteness(AnalysisIdentity(), emptyMap(), emptyMap(), emptyMap())
        val result: ObservedJavaLoad = object : ObservedJavaLoad {
            override val graph = graph
            override val completeness = completeness
            override val foldReport: FoldReport? = null
        }
        assertSame(graph, result.graph)
        assertSame(completeness, result.completeness)
        assertNull(result.foldReport)
        val recovery: BodyInterceptionEvidence = BodyInterceptionEvidence("fallback chain", "unsupported instruction")
        assertEquals("fallback chain", recovery.chain)
        assertEquals("unsupported instruction", recovery.reason)
    }
}
