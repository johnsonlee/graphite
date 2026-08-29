package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CypherValueSemanticsTest {
    private lateinit var executor: CypherExecutor

    @Before
    fun setUp() {
        executor = CypherExecutor(DefaultGraph.Builder().build())
    }

    @Test
    fun `null comparison and membership use three valued logic end to end`() {
        val result = executor.execute(
            "RETURN null = null AS equality, null <> null AS inequality, " +
                "2 IN [1, null, 3] AS unknownMembership, " +
                "2 IN [1, null, 2] AS matchingMembership, null IN [] AS emptyMembership"
        )

        val row = result.rows.single()
        assertNull(row["equality"])
        assertNull(row["inequality"])
        assertNull(row["unknownMembership"])
        assertEquals(true, row["matchingMembership"])
        assertEquals(false, row["emptyMembership"])
    }

    @Test
    fun `range membership preserves numeric coercion and null semantics`() {
        val result = executor.execute(
            "RETURN 2 IN range(1, 3) AS integerMatch, " +
                "2.0 IN range(1, 3) AS floatMatch, " +
                "2.5 IN range(1, 3) AS fractionalMiss, " +
                "null IN range(1, 3) AS nullMembership"
        )

        val row = result.rows.single()
        assertEquals(true, row["integerMatch"])
        assertEquals(true, row["floatMatch"])
        assertEquals(false, row["fractionalMiss"])
        assertNull(row["nullMembership"])
    }

    @Test
    fun `count and collect ignore null inputs end to end`() {
        val result = executor.execute(
            "UNWIND [1, null, 2, null] AS value " +
                "RETURN count(value) AS count, collect(value) AS values"
        )

        assertEquals(2L, result.rows.single()["count"])
        assertEquals(listOf(1, 2), result.rows.single()["values"])
    }

    @Test
    fun `structural equality is recursive exact and three valued`() {
        assertEquals(true, cypherEquals(listOf(1), listOf(1.0)))
        assertEquals(false, cypherEquals(listOf(1), listOf(2)))
        assertNull(cypherEquals(listOf(null), listOf(null)))
        assertEquals(true, cypherEquals(mapOf("value" to 1), mapOf("value" to 1.0)))
        assertEquals(false, cypherEquals(mapOf("value" to 1), mapOf("other" to 1)))
        assertNull(cypherEquals(mapOf("value" to null), mapOf("value" to null)))
        assertEquals(true, cypherEquals(Double.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
        assertEquals(false, cypherEquals(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `value keys normalize numbers recursively without losing precision`() {
        assertEquals(cypherValueKey(1), cypherValueKey(1.0))
        assertEquals(cypherValueKey(listOf(mapOf("value" to 1))), cypherValueKey(listOf(mapOf("value" to 1.0))))
        assertNotEquals(cypherValueKey(9007199254740993L), cypherValueKey(9007199254740992.0))
        assertEquals(cypherValueKey(Double.POSITIVE_INFINITY), cypherValueKey(Float.POSITIVE_INFINITY))
        assertNotEquals(cypherValueKey(Double.POSITIVE_INFINITY), cypherValueKey(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `exact long conversion rejects fractional nonfinite and out of range values`() {
        assertEquals(2L, 2.toExactLongOrNull())
        assertEquals(2L, 2.0.toExactLongOrNull())
        assertNull(2.5.toExactLongOrNull())
        assertNull(Double.POSITIVE_INFINITY.toExactLongOrNull())
        assertNull(9223372036854775808.0.toExactLongOrNull())
    }
}
