package io.johnsonlee.graphite.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StringPropertyTupleSetTest {

    private val alpha = listOf("a.Alpha", "run", "b.Beta", "get")
    private val alphaAgain = listOf("a.Alpha", "load", "b.Beta", "get")
    private val gamma = listOf("c.Gamma", "run", null, "get")

    @Test
    fun `tuple set deduplicates while preserving first-seen order and membership`() {
        val tuples = StringPropertyTupleSet(listOf(alpha, gamma, alpha, alphaAgain))

        assertEquals(3, tuples.size)
        assertEquals(listOf(alpha, gamma, alphaAgain), tuples.toList())
        assertTrue(alpha in tuples)
        assertTrue(listOf("c.Gamma", "run", null, "get") in tuples)
        assertFalse(listOf("a.Alpha", "run", "b.Beta", "put") in tuples)
        assertEquals(setOf(alpha, gamma, alphaAgain), tuples)
    }

    @Test
    fun `grouping by a column keeps encounter order and is computed once per column`() {
        val tuples = StringPropertyTupleSet(listOf(alpha, gamma, alphaAgain))

        val byCallerClass = tuples.groupedBy(0)
        assertEquals(listOf("a.Alpha", "c.Gamma"), byCallerClass.keys.toList())
        assertEquals(listOf(alpha, alphaAgain), byCallerClass["a.Alpha"])
        assertEquals(listOf(gamma), byCallerClass["c.Gamma"])
        assertSame(byCallerClass, tuples.groupedBy(0))

        val byCalleeClass = tuples.groupedBy(2)
        assertEquals(listOf("b.Beta", null), byCalleeClass.keys.toList())
        assertEquals(listOf(gamma), byCalleeClass[null])
    }

    @Test
    fun `grouping outside the tuple width or over an empty set is empty`() {
        val tuples = StringPropertyTupleSet(listOf(alpha, listOf("short")))

        assertEquals(emptyMap(), tuples.groupedBy(-1))
        assertEquals(emptyMap(), tuples.groupedBy(4))
        assertEquals(listOf(alpha), tuples.groupedBy(1)["run"])
        assertEquals(1, tuples.groupedBy(1).size)
        assertEquals(emptyMap(), StringPropertyTupleSet(emptyList()).groupedBy(0))
        assertEquals(0, StringPropertyTupleSet(emptyList()).size)
    }
}
