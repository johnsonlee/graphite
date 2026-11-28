package io.johnsonlee.graphite.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeIdNumberingTest {

    @Test
    fun `a build numbers its nodes from one and later process ids stay above them`() {
        repeat(10) { NodeId.next() }
        val first = NodeId.numbered { List(5) { NodeId.next().value } }
        val second = NodeId.numbered { List(5) { NodeId.next().value } }
        assertEquals(listOf(1, 2, 3, 4, 5), first)
        assertEquals(first, second, "the same build twice numbers the same")
        val large = NodeId.numbered { List(10_000) { NodeId.next().value }.last() }
        assertTrue(NodeId.next().value > large, "a synthetic id never reuses a built one")
    }

    @Test
    fun `a nested build numbers its own nodes and the outer build continues where it was`() {
        val (outer, inner) = NodeId.numbered {
            val before = List(3) { NodeId.next().value }
            val nested = NodeId.numbered { List(2) { NodeId.next().value } }
            (before + NodeId.next().value) to nested
        }
        assertEquals(listOf(1, 2, 3, 4), outer)
        assertEquals(listOf(1, 2), inner)
    }

    @Test
    fun `a failed build still releases its numbering`() {
        runCatching { NodeId.numbered { NodeId.next(); error("build failed") } }
        val afterFailure = NodeId.numbered { NodeId.next().value }
        assertEquals(1, afterFailure)
    }

    @Test
    fun `concurrent builds on different threads number independently`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val builds = List(2) { pool.submit(Callable { NodeId.numbered { List(1_000) { NodeId.next().value } } }) }.map { it.get() }
            assertEquals((1..1_000).toList(), builds[0])
            assertEquals(builds[0], builds[1])
        } finally {
            pool.shutdown()
        }
    }
}
