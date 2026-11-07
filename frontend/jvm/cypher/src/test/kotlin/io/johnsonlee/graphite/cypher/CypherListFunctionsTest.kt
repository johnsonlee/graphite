package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherListFunctionsTest : CypherFunctionsTestFixture() {

    @Test
    fun `head returns first element`() {
        assertEquals(1, CypherFunctions.call("head", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `head returns null for empty list`() {
        assertNull(CypherFunctions.call("head", listOf(emptyList<Int>())))
    }

    @Test
    fun `tail returns all but first`() {
        assertEquals(listOf(2, 3), CypherFunctions.call("tail", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `last returns last element`() {
        assertEquals(3, CypherFunctions.call("last", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `last returns null for empty list`() {
        assertNull(CypherFunctions.call("last", listOf(emptyList<Int>())))
    }

    @Test
    fun `range with default step`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), CypherFunctions.call("range", listOf(1, 5)))
    }

    @Test
    fun `range with explicit step`() {
        assertEquals(listOf(0L, 2L, 4L), CypherFunctions.call("range", listOf(0, 4, 2)))
    }

    @Test
    fun `range with negative step`() {
        assertEquals(listOf(5L, 3L, 1L), CypherFunctions.call("range", listOf(5, 1, -2)))
    }

    @Test
    fun `range with zero step throws`() {
        assertFailsWith<CypherException> { CypherFunctions.call("range", listOf(1, 5, 0)) }
    }

    @Test
    fun `size of list via size function`() {
        assertEquals(3, CypherFunctions.call("size", listOf(listOf("a", "b", "c"))))
    }

    @Test
    fun `nodes extracts nodes from a list`() {
        val node = IntConstant(NodeId.next(), 42)
        val mixed = listOf(node, "not a node", 99)
        val result = CypherFunctions.call("nodes", listOf(mixed)) as List<*>
        assertEquals(1, result.size)
        assertEquals(node, result[0])
    }

    @Test
    fun `nodes returns null for non-list non-path`() {
        assertNull(CypherFunctions.call("nodes", listOf("not a list")))
    }

    @Test
    fun `relationships extracts edges from a list`() {
        val edge = DataFlowEdge(NodeId.next(), NodeId.next(), DataFlowKind.ASSIGN)
        val mixed = listOf(edge, "not an edge", 99)
        val result = CypherFunctions.call("relationships", listOf(mixed)) as List<*>
        assertEquals(1, result.size)
        assertEquals(edge, result[0])
    }

    @Test
    fun `relationships returns null for non-list non-path`() {
        assertNull(CypherFunctions.call("relationships", listOf("not a list")))
    }

    @Test
    fun `tracked list functions observe cancellation during collection copies`() {
        val node = IntConstant(NodeId.next(), 42)
        val edge = DataFlowEdge(NodeId.next(), NodeId.next(), DataFlowKind.ASSIGN)
        val cases =
            listOf(
                "reverse" to listOf((0 until 10_000).toList()),
                "tail" to listOf((0 until 10_000).toList()),
                "nodes" to listOf(List(10_000) { node }),
                "relationships" to listOf(List(10_000) { edge }))

        for ((name, args) in cases) {
            val checks = AtomicInteger()
            assertFailsWith<CollectionFunctionCancelled>(name) {
                CypherFunctions.call(name, args) { if (checks.incrementAndGet() == 2) throw CollectionFunctionCancelled() }
            }
            assertEquals(2, checks.get(), name)
        }
    }

    @Test
    fun `tracked collection functions preserve completed results`() {
        val node = IntConstant(NodeId.next(), 42)
        val edge = DataFlowEdge(NodeId.next(), NodeId.next(), DataFlowKind.ASSIGN)
        var checks = 0
        val checkCancelled: () -> Unit = { checks++ }

        assertEquals("cba", CypherFunctions.call("reverse", listOf("abc"), checkCancelled))
        assertEquals(listOf(3, 2, 1), CypherFunctions.call("reverse", listOf(listOf(1, 2, 3)), checkCancelled))
        assertEquals(listOf(2, 3), CypherFunctions.call("tail", listOf(listOf(1, 2, 3)), checkCancelled))
        assertEquals(listOf(node), CypherFunctions.call("nodes", listOf(listOf(node, "value")), checkCancelled))
        assertEquals(listOf(edge), CypherFunctions.call("relationships", listOf(listOf(edge, "value")), checkCancelled))
        assertTrue(checks > 0)
    }

    @Test
    fun `tracked reverse preserves supplementary characters`() {
        val value = "A\uD83D\uDE00B\uD83D\uDE80C"

        assertEquals(value.reversed(), CypherFunctions.call("reverse", listOf(value)) {})
    }

    @Test
    fun `tracked reverse and tail stay linear for sequential lists`() {
        val reverseInput = TrackingSequentialList((0 until 10_000).toList())
        val tailInput = TrackingSequentialList((0 until 10_000).toList())

        assertEquals((0 until 10_000).reversed().toList(), CypherFunctions.call("reverse", listOf(reverseInput)) {})
        assertEquals((1 until 10_000).toList(), CypherFunctions.call("tail", listOf(tailInput)) {})
        assertEquals(1, reverseInput.listIteratorCalls)
        assertEquals(1, tailInput.listIteratorCalls)
    }

    @Test
    fun `nodes extracts from Path`() {
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val edge = DataFlowEdge(n1.id, n2.id, DataFlowKind.ASSIGN)
        val path = PathFinder.Path(listOf(n1, n2), listOf(edge))
        val result = CypherFunctions.call("nodes", listOf(path)) as List<*>
        assertEquals(2, result.size)
    }

    @Test
    fun `relationships extracts from Path`() {
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val edge = DataFlowEdge(n1.id, n2.id, DataFlowKind.ASSIGN)
        val path = PathFinder.Path(listOf(n1, n2), listOf(edge))
        val result = CypherFunctions.call("relationships", listOf(path)) as List<*>
        assertEquals(1, result.size)
    }
}
