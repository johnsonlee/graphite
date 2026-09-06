package io.johnsonlee.graphite.cypher

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectProjectionCypherRowTest {

    @Test
    fun `row exposes columns in order with metadata appended and behaves like an ordered map`() {
        val layout = DirectProjectionRowLayout(listOf("n.caller_class", "n.callee_name"), RESULT_METADATA_KEY)
        val metadata = mapOf(RESULT_GRAPH_IDS_KEY to listOf("graph-a"))
        val row = layout.row(listOf("example.Caller", null), metadata, setOf("graph-a"))

        val expected = linkedMapOf<String, Any?>(
            "n.caller_class" to "example.Caller",
            "n.callee_name" to null,
            RESULT_METADATA_KEY to metadata
        )
        assertEquals<Map<String, Any?>>(expected, row)
        assertEquals(expected.hashCode(), row.hashCode())
        assertEquals(expected.entries.toList(), row.entries.toList())
        assertEquals(listOf("n.caller_class", "n.callee_name", RESULT_METADATA_KEY), row.keys.toList())
        assertEquals(3, row.size)
        assertFalse(row.isEmpty())
        assertTrue(row.containsKey("n.callee_name"))
        assertTrue(RESULT_METADATA_KEY in row)
        assertFalse(row.containsKey("n.missing"))
        assertNull(row["n.callee_name"])
        assertNull(row["n.missing"])
        assertEquals("example.Caller", row["n.caller_class"])
        assertEquals(setOf("graph-a"), row.graphIds)
        assertEquals(expected.toString(), row.toString())
    }

    @Test
    fun `duplicate columns collapse to the first position with the last value like an insertion ordered map`() {
        val columns = listOf("a", "b", "a")
        val layout = DirectProjectionRowLayout(columns, null)
        val row = layout.row(listOf("first", "middle", "last"), null, emptySet())

        val expected = LinkedHashMap<String, Any?>()
        columns.forEachIndexed { index, column -> expected[column] = listOf("first", "middle", "last")[index] }
        assertEquals<Map<String, Any?>>(expected, row)
        assertEquals(listOf("a", "b"), row.keys.toList())
        assertEquals("last", row["a"])
        assertEquals(2, row.size)
    }

    @Test
    fun `rows without metadata omit the key and unqualified rows carry no graph ids`() {
        val layout = DirectProjectionRowLayout(listOf("x"), null)
        val row = layout.row(listOf("value"), null, emptySet())

        assertEquals<Map<String, Any?>>(mapOf("x" to "value"), row)
        assertFalse(RESULT_METADATA_KEY in row)
        assertTrue(row.graphIds.isEmpty())
        assertEquals(mapOf("x" to "value", "y" to 1), row + ("y" to 1))
    }
}
