package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
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
}
