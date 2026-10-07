package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StreamingStringPropertyProjection
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StreamingOrderedPropertyProjectionTest {
    private val calls = listOf(
        call(0, "zero", "Z", "z"), call(1, "first", "Å", "n"), call(2, "second", "Å", "n"),
        call(3, "three", "A", "a"), call(4, "first", "Å", "n")
    )
    private val base = orderedGraph(calls)

    @Test
    fun `streaming top k preserves Unicode multi-key ties duplicates aliases and limits without node access`() {
        val expectedAll = listOf(
            mapOf("p0" to "A", "p1" to "a", "p2" to "three"),
            mapOf("p0" to "Z", "p1" to "z", "p2" to "zero"),
            mapOf("p0" to "Å", "p1" to "n", "p2" to "first"),
            mapOf("p0" to "Å", "p1" to "n", "p2" to "second"),
            mapOf("p0" to "Å", "p1" to "n", "p2" to "first")
        )
        for (limit in listOf(1, 4, 5, 20)) {
            val projected = ProjectionGraph(base, calls, forbidNodes = true)
            val result = QueryPipeline(projected).execute(query(limit = limit))
            assertEquals(listOf("p0", "p1", "p2"), result.columns)
            assertEquals(expectedAll.take(limit), result.rows)
            assertEquals(QueryPipeline(base).execute(query(limit = limit)).rows, result.rows)
            assertEquals(1, projected.scans)
            assertEquals(calls.size, projected.emitted)
        }
    }

    @Test
    fun `duplicate requested properties and descending ordering reuse the ordinary comparator`() {
        val clauses = query(properties = listOf("callee_class", "callee_class", "caller_name")).toMutableList()
        clauses[2] = CypherClause.OrderBy(listOf(SortItem(CypherExpr.Variable("p0"), false)))
        val projected = ProjectionGraph(base, calls, forbidNodes = true)
        val result = QueryPipeline(projected).execute(clauses)
        assertEquals(QueryPipeline(base).execute(clauses).rows, result.rows)
        assertEquals(listOf("first", "second", "first", "zero", "three"), result.rows.map { it["p2"] })
        assertTrue(result.rows.all { it["p0"] == it["p1"] })
    }

    @Test
    fun `unsupported projection shape or capability rejection retains full fallback`() {
        val unsupportedProperty = query(properties = listOf("line"))
        val distinct = query().toMutableList().apply {
            this[1] = (this[1] as CypherClause.Return).copy(distinct = true)
        }
        val expression = query().toMutableList().apply {
            this[1] = CypherClause.Return(listOf(ReturnItem(CypherExpr.Literal("constant"), "p0")))
            this[2] = CypherClause.OrderBy(listOf(SortItem(CypherExpr.Variable("p0"))))
        }
        for (clauses in listOf(unsupportedProperty, distinct, expression, query(limit = MAX_ORDERED_TOP_K_ROWS + 1))) {
            val projected = ProjectionGraph(base, calls)
            assertEquals(QueryPipeline(base).execute(clauses).rows, QueryPipeline(projected).execute(clauses).rows)
            assertEquals(0, projected.scans)
        }
        val refusing = ProjectionGraph(base, calls, supported = false)
        assertEquals(QueryPipeline(base).execute(query()).rows, QueryPipeline(refusing).execute(query()).rows)
        assertEquals(1, refusing.scans)
        assertEquals(0, refusing.emitted)
    }

    @Test
    fun `inline match where reaches complete filtering instead of either ordered scan`() {
        val clauses = query().toMutableList()
        clauses[0] = (clauses[0] as CypherClause.Match).copy(
            where = CypherExpr.Comparison("=", property("caller_name"), CypherExpr.Literal("second"))
        )
        val projected = ProjectionGraph(base, calls)
        val expected = listOf(mapOf("p0" to "Å", "p1" to "n", "p2" to "second"))
        assertEquals(expected, QueryPipeline(projected).execute(clauses).rows)
        assertEquals(expected, QueryPipeline(base).execute(clauses).rows)
        assertEquals(0, projected.scans)
        val explicitWhere = query().toMutableList().apply {
            add(1, CypherClause.Where(CypherExpr.Comparison("=", property("caller_name"), CypherExpr.Literal("second"))))
        }
        assertEquals(expected, QueryPipeline(projected).execute(explicitWhere).rows)
        assertEquals(0, projected.scans)
    }

    @Test
    fun `qualified sources retain provenance and tracked requests retain qualified fallback`() {
        val projected = ProjectionGraph(base, calls)
        val qualified = listOf(CypherGraph("one", projected), CypherGraph("two", projected))
        val oracle = listOf(CypherGraph("one", base), CypherGraph("two", base))
        assertEquals(QueryPipeline(oracle).execute(query()).rows, QueryPipeline(qualified).execute(query()).rows)
        assertEquals(QueryPipeline(oracle.take(1)).execute(query()).rows, QueryPipeline(qualified.take(1)).execute(query()).rows)
        assertEquals(0, projected.scans)
        for (budget in listOf(2L, 100L)) {
            val expectedTracker = CypherWorkTracker(CypherExecutionBudget(budget))
            val actualTracker = CypherWorkTracker(CypherExecutionBudget(budget))
            val expected = runCatching { QueryPipeline(oracle.take(1), true).execute(query(), expectedTracker) }
            val actual = runCatching { QueryPipeline(qualified.take(1), true).execute(query(), actualTracker) }
            assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
            assertEquals(expected.getOrNull()?.rows, actual.getOrNull()?.rows)
            assertEquals(expectedTracker.diagnostics(), actualTracker.diagnostics())
            assertEquals(0, projected.scans)
        }
    }

    @Test
    fun `tracked streaming charges every typed node before accepting rows even with small top k`() {
        for (budget in listOf(1L, 4L, 5L, 6L)) {
            val projected = ProjectionGraph(base, calls, forbidNodes = true)
            val expectedTracker = CypherWorkTracker(CypherExecutionBudget(budget))
            val actualTracker = CypherWorkTracker(CypherExecutionBudget(budget))
            val expected = runCatching { QueryPipeline(base, true).execute(query(limit = 1), expectedTracker) }
            val actual = runCatching { QueryPipeline(projected, true).execute(query(limit = 1), actualTracker) }
            assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
            assertEquals(expectedTracker.diagnostics(), actualTracker.diagnostics())
            assertEquals(minOf(budget, calls.size.toLong()), actualTracker.diagnostics().workUnitsConsumed)
            assertEquals(minOf(budget, calls.size.toLong()).toInt(), projected.emitted)
            assertEquals(1, projected.scans)
            if (budget < calls.size) {
                assertEquals(budget, (actual.exceptionOrNull() as CypherBudgetExceededException).maxWorkUnits)
            } else {
                assertEquals(expected.getOrThrow().rows, actual.getOrThrow().rows)
                assertEquals(listOf(mapOf("p0" to "A", "p1" to "a", "p2" to "three")), actual.getOrThrow().rows)
            }
        }
    }

    @Test
    fun `tracked streaming shares a request budget already consumed by an earlier query`() {
        val ordered = "MATCH (n:CallSiteNode) RETURN n.callee_class AS p0, n.callee_name AS p1, " +
            "n.caller_name AS p2 ORDER BY p0, p1 DESC LIMIT 1"
        for (budget in listOf(6L, 7L)) {
            val expectedContext = CypherExecutionContext(CypherExecutionBudget(budget))
            val actualContext = CypherExecutionContext(CypherExecutionBudget(budget))
            val prefix = "MATCH (n:CallSiteNode) RETURN n.caller_name AS caller LIMIT 2"
            val prefixRows = listOf(mapOf("caller" to "zero"), mapOf("caller" to "first"))
            assertEquals(prefixRows, CypherExecutor(base, expectedContext).execute(prefix).rows)
            assertEquals(prefixRows, CypherExecutor(base, actualContext).execute(prefix).rows)
            assertEquals(2L, actualContext.diagnostics.workUnitsConsumed)
            val projected = ProjectionGraph(base, calls, forbidNodes = true)
            val expected = runCatching { CypherExecutor(base, expectedContext).execute(ordered) }
            val actual = runCatching { CypherExecutor(projected, actualContext).execute(ordered) }
            assertEquals(expected.exceptionOrNull()?.javaClass, actual.exceptionOrNull()?.javaClass)
            assertEquals(expected.getOrNull()?.rows, actual.getOrNull()?.rows)
            assertEquals(expectedContext.diagnostics, actualContext.diagnostics)
            assertEquals(budget, actualContext.diagnostics.workUnitsConsumed)
            assertEquals(1, projected.scans)
            assertEquals((budget - 2).toInt(), projected.emitted)
        }
    }

    @Test
    fun `tracked unsupported capability and zero limit do not double charge work`() {
        val refusing = ProjectionGraph(base, calls, supported = false)
        val expectedTracker = CypherWorkTracker(CypherExecutionBudget(5))
        val actualTracker = CypherWorkTracker(CypherExecutionBudget(5))
        assertEquals(QueryPipeline(base, true).execute(query(), expectedTracker).rows,
            QueryPipeline(refusing, true).execute(query(), actualTracker).rows)
        assertEquals(expectedTracker.diagnostics(), actualTracker.diagnostics())
        assertEquals(5L, actualTracker.diagnostics().workUnitsConsumed)
        assertEquals(1, refusing.scans)
        assertEquals(0, refusing.emitted)
        val zeroTracker = CypherWorkTracker(CypherExecutionBudget(1))
        val zero = ProjectionGraph(base, calls, forbidNodes = true)
        assertEquals(emptyList(), QueryPipeline(zero, true).execute(query(limit = 0), zeroTracker).rows)
        assertEquals(0L, zeroTracker.diagnostics().workUnitsConsumed)
        assertEquals(0, zero.scans)
        val empty = ProjectionGraph(orderedGraph(emptyList()), emptyList(), forbidNodes = true)
        assertEquals(emptyList(), QueryPipeline(empty, true).execute(query(), zeroTracker).rows)
        assertEquals(0L, zeroTracker.diagnostics().workUnitsConsumed)
        assertEquals(1, empty.scans)
    }

    @Test
    fun `tracked projection preserves cancellation and failure without charging or decoding later nodes`() {
        val signal = CypherCancellationSignal()
        val reason = CypherQueryCancelledException("tracked projection cancelled")
        val tracker = CypherWorkTracker(CypherExecutionBudget(5), signal)
        val cancelled = ProjectionGraph(base, calls, forbidNodes = true, beforeEmit = { index ->
            if (index == 2) signal.cancel(reason)
        })
        assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
            QueryPipeline(cancelled, true).execute(query(), tracker)
        })
        assertEquals(2, cancelled.emitted)
        assertEquals(2L, tracker.diagnostics().workUnitsConsumed)
        val failure = IllegalStateException("tracked storage failed")
        val failureTracker = CypherWorkTracker(CypherExecutionBudget(5))
        val failed = ProjectionGraph(base, calls, forbidNodes = true, beforeEmit = { index ->
            if (index == 2) throw failure
        })
        assertSame(failure, assertFailsWith<IllegalStateException> {
            QueryPipeline(failed, true).execute(query(), failureTracker)
        })
        assertEquals(2, failed.emitted)
        assertEquals(2L, failureTracker.diagnostics().workUnitsConsumed)
        val afterSignal = CypherCancellationSignal()
        val afterTracker = CypherWorkTracker(CypherExecutionBudget(5), afterSignal)
        val after = ProjectionGraph(base, calls, forbidNodes = true, afterScan = { afterSignal.cancel(reason) })
        assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
            QueryPipeline(after, true).execute(query(), afterTracker)
        })
        assertEquals(calls.size, after.emitted)
        assertEquals(5L, afterTracker.diagnostics().workUnitsConsumed)
    }

    @Test
    fun `untracked streaming scan honors cancellation without charging graph work`() {
        val successfulTracker = CypherWorkTracker(CypherExecutionBudget(1))
        val successful = ProjectionGraph(base, calls, forbidNodes = true)
        assertEquals(QueryPipeline(base).execute(query()).rows,
            QueryPipeline(successful, false).execute(query(), successfulTracker).rows)
        assertEquals(0L, successfulTracker.diagnostics().workUnitsConsumed)
        val signal = CypherCancellationSignal()
        val reason = CypherQueryCancelledException("stop projection")
        val tracker = CypherWorkTracker(CypherExecutionBudget(1), signal)
        val projected = ProjectionGraph(base, calls, forbidNodes = true, beforeEmit = { index ->
            if (index == 1) signal.cancel(reason)
        })
        val thrown = assertFailsWith<CypherQueryCancelledException> {
            QueryPipeline(projected, false).execute(query(), tracker)
        }
        assertSame(reason, thrown)
        assertEquals(1, projected.emitted)
        assertEquals(0L, tracker.diagnostics().workUnitsConsumed)
    }

    @Test
    fun `empty and zero limit admitted projections honor cancellation without changing unrelated zero limit`() {
        val signal = CypherCancellationSignal().apply { cancel() }
        val tracker = CypherWorkTracker(CypherExecutionBudget(1), signal)
        for (limit in listOf(0, 1)) {
            val projected = ProjectionGraph(orderedGraph(emptyList()), emptyList(), forbidNodes = true)
            assertFailsWith<CypherQueryCancelledException> {
                QueryPipeline(projected, false).execute(query(limit = limit), tracker)
            }
            assertEquals(0, projected.emitted)
        }
        assertEquals(emptyList(), QueryPipeline(base, false).execute(query(limit = 0), tracker).rows)
        val trackedZero = ProjectionGraph(base, calls, forbidNodes = true)
        assertFailsWith<CypherQueryCancelledException> {
            QueryPipeline(trackedZero, true).execute(query(limit = 0), tracker)
        }
        assertEquals(0, trackedZero.scans)
        assertEquals(0L, tracker.diagnostics().workUnitsConsumed)
        val active = ProjectionGraph(base, calls, forbidNodes = true)
        assertEquals(emptyList(), QueryPipeline(active).execute(query(limit = 0)).rows)
        assertEquals(0, active.scans)
        val empty = ProjectionGraph(orderedGraph(emptyList()), emptyList(), forbidNodes = true)
        val emptyResult = QueryPipeline(empty).execute(query())
        assertEquals(listOf("p0", "p1", "p2"), emptyResult.columns)
        assertEquals(emptyList(), emptyResult.rows)
        assertEquals(1, empty.scans)
        assertEquals(0, empty.emitted)
    }

    @Test
    fun `interruption aborts tracked and untracked scans and leaves interrupt status set`() {
        val projected = ProjectionGraph(base, calls, forbidNodes = true, beforeEmit = { index ->
            if (index == 1) Thread.currentThread().interrupt()
        })
        try {
            val failure = assertFailsWith<CypherQueryCancelledException> { QueryPipeline(projected).execute(query()) }
            assertEquals(CypherQueryCancelledException::class.java, failure.javaClass)
            assertEquals("String projection interrupted", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, projected.emitted)
        } finally {
            Thread.interrupted()
        }
        val tracked = ProjectionGraph(base, calls, forbidNodes = true, beforeEmit = { index ->
            if (index == 1) Thread.currentThread().interrupt()
        })
        val tracker = CypherWorkTracker(CypherExecutionBudget(5))
        try {
            val failure = assertFailsWith<CypherQueryCancelledException> {
                QueryPipeline(tracked, true).execute(query(), tracker)
            }
            assertEquals(CypherQueryCancelledException::class.java, failure.javaClass)
            assertEquals("String projection interrupted", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, tracked.emitted)
            assertEquals(1L, tracker.diagnostics().workUnitsConsumed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `consumer failure is propagated without falling back after partial streaming`() {
        val failure = IllegalStateException("projection failed")
        val projected = ProjectionGraph(base, calls, forbidNodes = true, beforeEmit = { index ->
            if (index == 1) throw failure
        })
        assertSame(failure, assertFailsWith<IllegalStateException> { QueryPipeline(projected).execute(query()) })
        assertEquals(1, projected.emitted)
    }

    @Test
    fun `cancellation immediately after storage completion is checked before returning top k`() {
        val signal = CypherCancellationSignal()
        val reason = CypherQueryCancelledException("cancel after scan")
        val projected = ProjectionGraph(base, calls, forbidNodes = true, afterScan = { signal.cancel(reason) })
        val tracker = CypherWorkTracker(CypherExecutionBudget(1), signal)
        assertSame(reason, assertFailsWith<CypherQueryCancelledException> {
            QueryPipeline(projected, false).execute(query(), tracker)
        })
        assertEquals(calls.size, projected.emitted)
    }

    private fun query(
        properties: List<String> = listOf("callee_class", "callee_name", "caller_name"),
        limit: Int = 20
    ): List<CypherClause> = listOf(
        CypherClause.Match(listOf(CypherPattern(listOf(PatternElement.NodePattern("n", listOf("CallSiteNode")))))),
        CypherClause.Return(properties.mapIndexed { index, name -> ReturnItem(property(name), "p$index") }),
        CypherClause.OrderBy(properties.indices.take(2).map { SortItem(CypherExpr.Variable("p$it"), it == 0) }),
        CypherClause.Limit(CypherExpr.Literal(limit))
    )

    private fun property(name: String) = CypherExpr.Property(CypherExpr.Variable("n"), name)

    private fun call(id: Int, caller: String, calleeClass: String, callee: String) = CallSiteNode(
        NodeId(id), MethodDescriptor(TypeDescriptor("Caller"), caller, emptyList(), TypeDescriptor("void")),
        MethodDescriptor(TypeDescriptor(calleeClass), callee, emptyList(), TypeDescriptor("void")), id, null, emptyList()
    )

    private fun orderedGraph(calls: List<CallSiteNode>): Graph {
        val delegate = DefaultGraph.Builder().apply { calls.forEach { addNode(it) } }.build()
        return object : Graph by delegate {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                calls.asSequence().filter(type::isInstance).map(type::cast)
        }
    }

    private class ProjectionGraph(
        private val delegate: Graph,
        private val calls: List<CallSiteNode>,
        private val forbidNodes: Boolean = false,
        private val supported: Boolean = true,
        private val beforeEmit: (Int) -> Unit = {},
        private val afterScan: () -> Unit = {}
    ) : Graph by delegate, StreamingStringPropertyProjection {
        var scans = 0
        var emitted = 0

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            check(!forbidNodes) { "Full nodes must not be materialized" }
            return delegate.nodes(type)
        }

        override fun forEachStringPropertyProjection(
            type: Class<out Node>,
            projectedProperties: List<String>,
            checkCancelled: () -> Unit,
            consumer: (StringPropertyProjectionRow) -> Unit
        ): Boolean {
            scans++
            if (!supported) return false
            checkCancelled()
            calls.forEachIndexed { index, call ->
                beforeEmit(index)
                checkCancelled()
                consumer(StringPropertyProjectionRow(projectedProperties.map { name ->
                    when (name) {
                        "caller_class" -> call.caller.declaringClass.className
                        "caller_name" -> call.caller.name
                        "callee_class" -> call.callee.declaringClass.className
                        "callee_name" -> call.callee.name
                        else -> error("Unsupported property $name")
                    }
                }))
                emitted++
            }
            checkCancelled()
            afterScan()
            return true
        }
    }
}
