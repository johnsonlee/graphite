package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StreamingMethodLookup
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CancellationAwareValuesTest {
    @Test
    fun `regex character view preserves subsequences and cancellation when reading them`() {
        val text = "prefix" + "a".repeat(2048) + "suffix"
        var checks = 0
        var cancel = false
        val view = CancellationAwareCharSequence(text) {
            checks++
            if (cancel) throw CypherQueryCancelledException()
        }
        assertEquals(text.length, view.length)
        assertEquals(text, view.toString())
        val slice = view.subSequence(6, 2054)
        assertEquals("a".repeat(2048), slice.toString())
        assertEquals('a', slice[0])
        cancel = true
        assertFailsWith<CypherQueryCancelledException> { repeat(1024) { slice[it] } }
        assertEquals(1, checks)
        assertFailsWith<IndexOutOfBoundsException> { view.subSequence(-1, 1) }
    }

    @Test
    fun `method metadata scan invokes request cancellation before yielding descriptor`() {
        val method = MethodDescriptor(TypeDescriptor("Owner"), "read", emptyList(), TypeDescriptor("int"))
        val base = DefaultGraph.Builder().addMethod(method).build()
        var scans = 0
        val graph = object : Graph by base, StreamingMethodLookup {
            override fun methods(pattern: MethodPattern, scanConsumer: MethodMetadataScanConsumer): Sequence<MethodDescriptor> = sequence {
                scans++
                scanConsumer.inspect()
                yield(method)
            }
        }
        val signal = CypherCancellationSignal()
        val tracker = CypherWorkTracker(CypherExecutionBudget(100), signal)
        val context = PipelineExecutionContext(graph, true)
        val access = PipelineGraphAccess(listOf(CypherGraph("scope", graph)), false, context)
        context.execute(emptyMap(), tracker) {
            assertEquals(listOf(MethodValue(null, method, graph)), access.methodCandidates().toList())
            signal.cancel()
            assertFailsWith<CypherQueryCancelledException> { access.methodCandidates().toList() }
        }
        assertEquals(2, scans)
    }

    @Test
    fun `default storage lookup refuses unsupported capability without charging work`() {
        val graph = DefaultGraph.Builder().build()
        val context = PipelineExecutionContext(graph, true)
        val access = PipelineGraphAccess(listOf(CypherGraph("scope", graph)), false, context)
        val tracker = CypherWorkTracker(CypherExecutionBudget(10))
        context.execute(emptyMap(), tracker) {
            assertNull(access.stringPropertyDisjunctionCandidates(graph, Node::class.java,
                listOf(StringPropertyPredicate("value", null, StringMatchMode.CONTAINS, "needle")), 1))
            access.stringStorageWorkConsumer(1, tracker).consume()
            assertEquals(1L, tracker.diagnostics().workUnitsConsumed)
        }
    }

    @Test
    fun `tracked sorting retains input order when all sort keys tie`() {
        val graph = DefaultGraph.Builder().build()
        val context = PipelineExecutionContext(graph, true)
        val rows = listOf(mapOf("value" to 1, "source" to "a"), mapOf("value" to 1, "source" to "b"))
        val order = CypherClause.OrderBy(listOf(SortItem(CypherExpr.Variable("value"), true)))
        assertEquals(rows, CypherRowOperations(context, false).executeOrderBy(order, rows))
    }
}
