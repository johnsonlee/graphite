package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StreamingStringPropertyProjection
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.WorkAwareStreamingStringPropertyProjection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OrderedProjectionAdmissionTest {
    @Test
    fun `full heap rejects worse and later tied rows without reading their payload`() {
        val graph = RowsGraph(listOf(values("A", "a", "first"), values("Z", "z", "worse"), values("A", "a", "later")))
        val tracker = CypherWorkTracker(CypherExecutionBudget(3))
        val result = QueryPipeline(graph, true).execute(query(1), tracker)
        assertEquals(listOf("p0", "p1", "p2", "p3"), result.columns)
        assertEquals(listOf(row("A", "a", "first")), result.rows)
        assertEquals(listOf(1, 0, 0), graph.payloadReads.toList())
        assertEquals(3, graph.emitted)
        assertEquals(3L, tracker.diagnostics().workUnitsConsumed)
        assertEquals(listOf("first", "worse", "later"), graph.savedValues.map { it[2] }, "callback values remain valid")
    }

    @Test
    fun `evictions preserve mixed directions nulls Unicode and repeated projected aliases`() {
        val values = listOf(values("Z", "b", "old"), values("A", "a", "early-a"), values("A", "b", "early-b"),
            values("A", "b", "late-b"), values(null, "z", "null"), values("😀", "a", "supplementary"), values("Å", "a", "unicode"))
        val order = listOf(SortItem(CypherExpr.Variable("p3")), SortItem(CypherExpr.Variable("p1"), false),
            SortItem(CypherExpr.Variable("p2"), false), SortItem(CypherExpr.Variable("p3"), false))
        val expected = listOf(row("A", "b", "late-b"), row("A", "b", "early-b"), row("A", "a", "early-a"),
            row("Z", "b", "old"), row("Å", "a", "unicode"), row("😀", "a", "supplementary"), row(null, "z", "null"))
        for (limit in listOf(1, 3, 20)) {
            val result = QueryPipeline(RowsGraph(values)).execute(query(limit, order))
            assertEquals(expected.take(limit), result.rows)
            assertTrue(result.rows.all { it["p0"] == it["p3"] })
        }
        val descending = listOf(SortItem(CypherExpr.Variable("p3"), false), SortItem(CypherExpr.Variable("p1")),
            SortItem(CypherExpr.Variable("p2")))
        assertEquals(listOf("null", "supplementary", "unicode", "old", "early-a", "early-b", "late-b"),
            QueryPipeline(RowsGraph(values)).execute(query(20, descending)).rows.map { it["p2"] })
    }

    @Test
    fun `losers remain charged against the same previously consumed request budget`() {
        val values = listOf(values("A", "a", "first")) + List(4) { values("Z", "z", "loser-$it") }
        for (budget in listOf(3L, 6L, 7L, 8L)) {
            val context = CypherExecutionContext(CypherExecutionBudget(budget))
            context.workTracker.consume(2)
            val graph = RowsGraph(values)
            val result = runCatching { QueryPipeline(graph, true).execute(query(1), context.workTracker) }
            assertEquals(minOf(budget, 7L), context.diagnostics.workUnitsConsumed)
            assertEquals(minOf(budget - 2, 5L).toInt(), graph.emitted)
            assertEquals(listOf(1, 0, 0, 0, 0), graph.payloadReads.toList())
            if (budget < 7) {
                assertEquals(budget, (result.exceptionOrNull() as CypherBudgetExceededException).maxWorkUnits)
            } else {
                assertEquals(listOf(row("A", "a", "first")), result.getOrThrow().rows)
            }
        }
    }

    @Test
    fun `losing suffix still observes signal cancellation storage errors and interruption`() {
        val values = listOf(values("A", "a", "first"), values("Z", "z", "loser"), values("Z", "z", "last"))
        val reason = CypherQueryCancelledException("cancel a losing candidate")
        val signal = CypherCancellationSignal()
        val tracker = CypherWorkTracker(CypherExecutionBudget(10), signal)
        val cancelled = RowsGraph(values, beforeEmit = { if (it == 2) signal.cancel(reason) })
        assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
            QueryPipeline(cancelled, true).execute(query(1), tracker)
        })
        assertEquals(2, cancelled.emitted)
        assertEquals(2L, tracker.diagnostics().workUnitsConsumed)
        val failure = IllegalArgumentException("storage failed on a losing candidate")
        val failed = RowsGraph(values, beforeEmit = { if (it == 2) throw failure })
        assertSame(failure, assertFailsWith<IllegalArgumentException> { QueryPipeline(failed).execute(query(1)) })
        assertEquals(2, failed.emitted)
        val interrupted = RowsGraph(values, beforeEmit = { if (it == 1) Thread.currentThread().interrupt() })
        try {
            assertFailsWith<CypherQueryCancelledException> { QueryPipeline(interrupted).execute(query(1)) }
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, interrupted.emitted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `nested projection keeps preflight row work and cancellation attached to its own request`() {
        val outer = CypherWorkTracker(CypherExecutionBudget(6))
        val inner = CypherWorkTracker(CypherExecutionBudget(6))
        val cancelledSignal = CypherCancellationSignal()
        val reason = CypherQueryCancelledException("nested request cancelled")
        val cancelled = CypherWorkTracker(CypherExecutionBudget(6), cancelledSignal)
        var nested = false
        lateinit var pipeline: QueryPipeline
        val rows = RowsGraph(listOf(values("A", "a", "first"), values("Z", "z", "second"), values("Z", "z", "third"))) {
            if (!nested) {
                nested = true
                assertEquals(listOf(row("A", "a", "first")), pipeline.execute(query(1), inner).rows)
                cancelledSignal.cancel(reason)
                assertSame(reason, assertFailsWith<CypherQueryCancelledException> { pipeline.execute(query(1), cancelled) })
            }
        }
        val graph = object : Graph by rows, WorkAwareStreamingStringPropertyProjection {
            override fun forEachStringPropertyProjection(
                type: Class<out Node>, projectedProperties: List<String>, checkCancelled: () -> Unit,
                consumer: (StringPropertyProjectionRow) -> Unit
            ): Boolean = error("Tracked query must use the work-aware capability")

            override fun forEachStringPropertyProjection(
                type: Class<out Node>, projectedProperties: List<String>, preflightWorkConsumer: GraphWorkConsumer?,
                checkCancelled: () -> Unit, consumer: (StringPropertyProjectionRow) -> Unit
            ): Boolean {
                repeat(3) { preflightWorkConsumer?.consume() }
                return rows.forEachStringPropertyProjection(type, projectedProperties, checkCancelled, consumer)
            }
        }
        pipeline = QueryPipeline(graph, true)
        assertEquals(listOf(row("A", "a", "first")), pipeline.execute(query(1), outer).rows)
        assertEquals(6L, outer.diagnostics().workUnitsConsumed)
        assertEquals(6L, inner.diagnostics().workUnitsConsumed)
        assertEquals(0L, cancelled.diagnostics().workUnitsConsumed)
    }

    @Test
    fun `untracked projection still observes an explicitly supplied cancellation tracker`() {
        val signal = CypherCancellationSignal()
        val reason = CypherQueryCancelledException("untracked caller cancellation")
        val tracker = CypherWorkTracker(CypherExecutionBudget(1), signal)
        val graph = RowsGraph(listOf(values("A", "a", "first"), values("Z", "z", "later"))) {
            if (it == 1) signal.cancel(reason)
        }
        assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
            QueryPipeline(graph).execute(query(1), tracker)
        })
        assertEquals(1, graph.emitted)
        assertEquals(0L, tracker.diagnostics().workUnitsConsumed)
    }

    private fun query(
        limit: Int,
        order: List<SortItem> = listOf(SortItem(CypherExpr.Variable("p0")), SortItem(CypherExpr.Variable("p1")))
    ): List<CypherClause> = listOf(
        CypherClause.Match(listOf(CypherPattern(listOf(PatternElement.NodePattern("n", listOf("CallSiteNode")))))),
        CypherClause.Return(PROPERTIES.mapIndexed { index, property ->
            ReturnItem(CypherExpr.Property(CypherExpr.Variable("n"), property), "p$index")
        }),
        CypherClause.OrderBy(order),
        CypherClause.Limit(CypherExpr.Literal(limit))
    )

    private fun values(type: String?, name: String?, payload: String?) = listOf(type, name, payload, type)

    private fun row(type: String?, name: String?, payload: String?): Map<String, Any?> =
        linkedMapOf("p0" to type, "p1" to name, "p2" to payload, "p3" to type)

    private class RowsGraph(
        private val rows: List<List<String?>>,
        private val beforeEmit: (Int) -> Unit = {}
    ) : Graph by DefaultGraph.Builder().build(), StreamingStringPropertyProjection {
        val payloadReads = IntArray(rows.size)
        val savedValues = mutableListOf<List<String?>>()
        var emitted = 0

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("Node fallback must not decode projection rows")

        override fun forEachStringPropertyProjection(
            type: Class<out Node>,
            projectedProperties: List<String>,
            checkCancelled: () -> Unit,
            consumer: (StringPropertyProjectionRow) -> Unit
        ): Boolean {
            assertEquals(PROPERTIES, projectedProperties)
            checkCancelled()
            rows.forEachIndexed { rowIndex, values ->
                beforeEmit(rowIndex)
                checkCancelled()
                val stableValues = object : AbstractList<String?>() {
                    override val size: Int = values.size
                    override fun get(index: Int): String? {
                        if (index == 2) payloadReads[rowIndex]++
                        return values[index]
                    }
                }
                savedValues += stableValues
                consumer(StringPropertyProjectionRow(stableValues))
                emitted++
            }
            checkCancelled()
            return true
        }
    }

    companion object {
        private val PROPERTIES = listOf("callee_class", "callee_name", "caller_name", "callee_class")
    }
}
