package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class CypherExecutionBudgetTest : CypherExecutorTestFixture() {

    @Test
    fun `execution budget rejects non-positive limits`() {
        assertFailsWith<IllegalArgumentException> { CypherExecutionBudget(0) }
    }

    @Test
    fun `execution budget stops a full node scan and resets per query`() {
        val budgeted = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 2))

        val error = assertFailsWith<CypherBudgetExceededException> { budgeted.execute("MATCH (n) RETURN n.id") }
        assertEquals(2L, error.maxWorkUnits)
        assertTrue(error.message.orEmpty().contains("selective label/filter"))

        repeat(2) {
            val result = budgeted.execute("MATCH (n) RETURN n.id LIMIT 2")
            assertEquals(2, result.rows.size)
        }
    }

    @Test
    fun `execution context shares one budget across executors`() {
        val chain = dataFlowChain(length = 1)
        val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 1))

        val first = CypherExecutor(chain, context).execute("MATCH (n:IntConstant) RETURN n.value LIMIT 1")
        assertEquals(listOf(mapOf("n.value" to 0)), first.rows)

        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(chain, context).execute("MATCH (n:IntConstant) RETURN n.value LIMIT 1")
        }
    }

    @Test
    fun `work tracker enforces one exact budget across concurrent workers`() {
        val budget = 10_000
        val tracker = CypherWorkTracker(CypherExecutionBudget(budget.toLong()))
        val successful = AtomicInteger()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        repeat(8) {
            workers.submit {
                start.await()
                while (true) {
                    try {
                        tracker.consume()
                        successful.incrementAndGet()
                    } catch (_: CypherBudgetExceededException) {
                        break
                    }
                }
            }
        }

        start.countDown()
        workers.shutdown()
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(budget, successful.get())
        assertFailsWith<CypherBudgetExceededException> { tracker.consume() }
    }

    @Test
    fun `budgeted pipeline preserves unbudgeted clause results`() {
        val budgeted = CypherExecutor(graph, CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = Long.MAX_VALUE)))
        val queries =
            listOf(
                "UNWIND [3, 1, 2, 2] AS x WITH x WHERE x > 1 " + "RETURN DISTINCT x + 0 AS value ORDER BY value DESC",
                "UNWIND [3, 1, 2, 2] AS x RETURN x, count(x) AS occurrences ORDER BY x DESC",
                "UNWIND [1, 1, 2] AS x RETURN count(DISTINCT x) AS occurrences",
                "MATCH (n:IntConstant) WHERE n.value >= 0 RETURN n.value ORDER BY n.value LIMIT 3")

        for (query in queries) {
            assertEquals(executor.execute(query), budgeted.execute(query), query)
        }
    }

    @Test
    fun `budgeted extrema preserve NaN and signed zero ordering`() {
        val budgeted = CypherExecutor(graph, CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = Long.MAX_VALUE)))

        for (values in listOf("toFloat('NaN'), 1.0", "1.0, toFloat('NaN')")) {
            val row = budgeted.execute("UNWIND [$values] AS x RETURN min(x) AS lo, max(x) AS hi").rows.single()
            assertEquals(1.0, row["lo"])
            assertTrue((row["hi"] as Double).isNaN())
        }

        for (values in listOf("-0.0, 0.0", "0.0, -0.0")) {
            val row = budgeted.execute("UNWIND [$values] AS x RETURN min(x) AS lo, max(x) AS hi").rows.single()
            assertEquals((-0.0).toRawBits(), (row["lo"] as Double).toRawBits())
            assertEquals(0.0.toRawBits(), (row["hi"] as Double).toRawBits())
        }
    }

    @Test
    fun `execution context cancellation stops a graph scan`() {
        val cancellation = CypherCancellationSignal()
        val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 10), cancellation)
        cancellation.cancel()

        assertFailsWith<CypherQueryCancelledException> { CypherExecutor(graph, context).execute("MATCH (n) RETURN n.id LIMIT 1") }
    }

    @Test
    fun `cancellation signal preserves the first cancellation reason`() {
        val cancellation = CypherCancellationSignal()
        assertEquals("Cypher query cancelled", cancellation.cancellationException().message)

        val timeout = CypherQueryTimeoutException(25)
        assertTrue(cancellation.cancel(timeout))
        assertFalse(cancellation.cancel())
        assertTrue(cancellation.isCancelled)
        assertTrue(cancellation.cancellationException() === timeout)

        val thrown = assertFailsWith<CypherQueryTimeoutException> { cancellation.throwIfCancelled() }
        assertTrue(thrown === timeout)
        assertEquals(25, thrown.timeoutMillis)
    }

    @Test
    fun `execution context cancellation remains active across sequential executors`() {
        val cancellation = CypherCancellationSignal()
        val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 10), cancellation)
        assertEquals(listOf(mapOf("value" to 1)), CypherExecutor(graph, context).execute("RETURN 1 AS value").rows)

        cancellation.cancel()

        assertFailsWith<CypherQueryCancelledException> { CypherExecutor(graph, context).execute("MATCH (n) RETURN n.id LIMIT 1") }
    }

    @Test
    fun `execution context cancellation stops graph-free range and unwind work`() {
        val cancellation = CypherCancellationSignal()
        val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 1), cancellation)
        val started = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                started.countDown()
                CypherExecutor(graph, context).execute("UNWIND range(1, 10000000) AS x RETURN x LIMIT 1")
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        worker.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(20)
        cancellation.cancel()
        worker.join(TimeUnit.SECONDS.toMillis(2))

        assertFalse(worker.isAlive, "Graph-free query ignored cancellation")
        assertTrue(failure.get() is CypherQueryCancelledException, "Unexpected failure: ${failure.get()}")
    }

    @Test
    fun `graph-free cancellation checkpoints do not consume graph work budget`() {
        val context = CypherExecutionContext(CypherExecutionBudget(maxWorkUnits = 1))

        val result = CypherExecutor(graph, context).execute("UNWIND range(1, 10000) AS x RETURN x LIMIT 1")

        assertEquals(listOf(mapOf("x" to 1L)), result.rows)
    }

    @Test
    fun `execution budget counts relationship candidates`() {
        val budgeted = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1))

        assertFailsWith<CypherBudgetExceededException> { budgeted.execute("MATCH (a:ParameterNode)-[r]->(b) RETURN b") }
    }

    @Test
    fun `execution budget charges single hop target nodes on fast and generic paths`() {
        val queries =
            listOf(
                "MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable) RETURN b.name LIMIT 1",
                "MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable) RETURN b.name")

        for (query in queries) {
            val budgeted = CypherExecutor(dataFlowChain(length = 1), CypherExecutionBudget(maxWorkUnits = 2))
            assertFailsWith<CypherBudgetExceededException> { budgeted.execute(query) }
        }
    }

    @Test
    fun `execution budget charges edges before type filtering on fast and generic paths`() {
        val owner = TypeDescriptor("com.example.MixedEdges")
        val valueType = TypeDescriptor("int")
        val method = MethodDescriptor(owner, "run", emptyList(), TypeDescriptor("void"))
        val source = IntConstant(NodeId.next(), 0)
        val target = LocalVariable(NodeId.next(), "target", valueType, method)
        val builder = DefaultGraph.Builder().addNode(source).addNode(target)
        repeat(100) {
            val wrongTarget = ReturnNode(NodeId.next(), method, valueType)
            builder.addNode(wrongTarget)
            builder.addEdge(CallEdge(source.id, wrongTarget.id, false))
        }
        builder.addEdge(DataFlowEdge(source.id, target.id, DataFlowKind.ASSIGN))
        val mixedGraph = builder.build()
        val queries =
            listOf(
                "MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable) RETURN b.name LIMIT 1",
                "MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable) RETURN b.name")

        for (query in queries) {
            val budgeted = CypherExecutor(mixedGraph, CypherExecutionBudget(maxWorkUnits = 3))
            assertFailsWith<CypherBudgetExceededException> { budgeted.execute(query) }
        }
    }

    @Test
    fun `execution budget survives a nested execution on the same pipeline`() {
        val chain = dataFlowChain(length = 1)
        lateinit var budgeted: CypherExecutor
        var nested = false
        val reentrantGraph =
            object : Graph by chain {
                override fun outgoing(node: NodeId): Sequence<Edge> {
                    if (!nested) {
                        nested = true
                        budgeted.execute("RETURN 1")
                    }
                    return chain.outgoing(node)
                }
            }
        budgeted = CypherExecutor(reentrantGraph, CypherExecutionBudget(maxWorkUnits = 1))

        assertFailsWith<CypherBudgetExceededException> {
            budgeted.execute("MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable) RETURN b.name LIMIT 1")
        }
    }

    @Test
    fun `execution budget charges element id seeks across union segments`() {
        val budgeted = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1))
        val id = intConst42.value.toString()

        assertFailsWith<CypherBudgetExceededException> {
            budgeted.execute(
                "MATCH (n) WHERE elementId(n) = '$id' RETURN n.id AS id " +
                    "UNION ALL MATCH (n) WHERE elementId(n) = '$id' RETURN n.id AS id")
        }
    }

    @Test
    fun `execution budget stops variable length path traversal`() {
        val budgeted = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 2))

        assertFailsWith<CypherBudgetExceededException> {
            budgeted.execute("MATCH (a:IntConstant)-[:DATAFLOW*2..5]->(b:CallSiteNode) " + "RETURN a.value, b.callee_name")
        }
    }

    @Test
    fun `execution budget charges variable path materialization`() {
        val budgeted = CypherExecutor(dataFlowChain(length = 1), CypherExecutionBudget(maxWorkUnits = 4))

        assertFailsWith<CypherBudgetExceededException> {
            budgeted.execute("MATCH (a:IntConstant)-[r:DATAFLOW*1..10000]->(b:LocalVariable) RETURN r LIMIT 1")
        }
    }
}
