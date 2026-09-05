package io.johnsonlee.graphite.webgraph

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawExactStringPropertyFlagsTest {
    @Test
    fun `property unions preserve the original OR for every node combination`() {
        val properties = listOf(0, 0, 1, 2, 3)
        val matches = listOf(intArrayOf(0, 2, 2), intArrayOf(3), intArrayOf(2, 7), intArrayOf(1), intArrayOf(0, 7))
        val flags = assertNotNull(rawExactStringPropertyFlags(8, properties, matches))
        assertContentEquals(byteArrayOf(9, 4, 3, 1, 0, 0, 0, 10), flags)

        // Exhaustive four-field tuples include exclusive hits, overlaps, and complete misses.
        repeat(4_096) { ordinal ->
            val node = IntArray(4) { property -> (ordinal shr (property * 3)) and 7 }
            val expected = properties.indices.any { predicate -> node[properties[predicate]] in matches[predicate] }
            val actual = node.indices.any { property -> flags[node[property]].toInt() and (1 shl property) != 0 }
            assertEquals(expected, actual, "Node ${node.toList()}")
        }
    }

    @Test
    fun `sparse candidates keep hash membership`() {
        assertNull(rawExactStringPropertyFlags(1_000, listOf(0, 1, 2, 3), List(4) { intArrayOf(7) }))
    }

    @Test
    fun `admission includes the exact original key array payload boundary`() {
        // Three entries need capacity four plus the reserved zero slot: 5 * 4 bytes.
        assertEquals(20, assertNotNull(rawExactStringPropertyFlags(20, listOf(0), listOf(intArrayOf(0, 1, 2)))).size)
        assertNull(rawExactStringPropertyFlags(21, listOf(0), listOf(intArrayOf(0, 1, 2))))
        // Crossing the default load factor grows capacity to eight, hence 9 * 4 bytes.
        assertEquals(36, assertNotNull(rawExactStringPropertyFlags(36, listOf(0), listOf(intArrayOf(0, 1, 2, 3)))).size)
        assertNull(rawExactStringPropertyFlags(37, listOf(0), listOf(intArrayOf(0, 1, 2, 3))))
    }

    @Test
    fun `admission counts every original array even with duplicate properties and IDs`() {
        val duplicates = listOf(intArrayOf(0, 0, 0, 0))
        val flags = assertNotNull(rawExactStringPropertyFlags(36, listOf(3), duplicates))
        assertContentEquals(ByteArray(36).also { it[0] = 8 }, flags)
        assertNull(rawExactStringPropertyFlags(37, listOf(3), duplicates))

        val separatePredicates = listOf(intArrayOf(0), intArrayOf(23))
        val combined = assertNotNull(rawExactStringPropertyFlags(24, listOf(2, 2), separatePredicates))
        assertContentEquals(ByteArray(24).also {
            it[0] = 4
            it[23] = 4
        }, combined)
        assertNull(rawExactStringPropertyFlags(25, listOf(2, 2), separatePredicates))
    }

    @Test
    fun `empty candidate sets cannot match any field`() {
        assertContentEquals(ByteArray(12), rawExactStringPropertyFlags(12, listOf(0), listOf(intArrayOf())))
        assertNull(rawExactStringPropertyFlags(13, listOf(0), listOf(intArrayOf())))
    }

    @Test
    fun `invalid candidate IDs decline the entire table`() {
        for (invalid in listOf(-1, 8, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertNull(rawExactStringPropertyFlags(8, listOf(0, 3), listOf(intArrayOf(0, 7), intArrayOf(2, invalid))))
        }
    }

    @Test
    fun `mismatched predicates and invalid properties decline the entire table`() {
        assertNull(rawExactStringPropertyFlags(8, listOf(0), listOf(intArrayOf(0), intArrayOf(1))))
        assertNull(rawExactStringPropertyFlags(8, listOf(0, 1), listOf(intArrayOf(0))))
        for (invalid in listOf(-1, 4, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertNull(rawExactStringPropertyFlags(8, listOf(0, invalid), listOf(intArrayOf(0), intArrayOf(1))))
        }
    }

    @Test
    fun `preexisting caller interruption cancels construction without clearing the flag`() {
        val thread = Thread.currentThread()
        val wasInterrupted = thread.isInterrupted
        thread.interrupt()
        try {
            assertFailsWith<CancellationException> {
                rawExactStringPropertyFlags(8, listOf(0), listOf(intArrayOf(0, 7)))
            }
            assertTrue(thread.isInterrupted)
        } finally {
            if (!wasInterrupted) Thread.interrupted()
        }
    }
}
