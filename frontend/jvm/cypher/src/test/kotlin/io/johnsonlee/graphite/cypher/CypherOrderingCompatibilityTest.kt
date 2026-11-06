package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CypherOrderingCompatibilityTest : CypherCompatibilityTestFixture() {

    @Test
    fun `UNWIND - basic list`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN x")
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    @Test
    fun `UNWIND - with WHERE filter`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x WITH x WHERE x > 1 RETURN x")
        assertEquals(2, result.rows.size)
        assertEquals(listOf(2, 3), result.rows.map { it["x"] })
    }

    @Test
    fun `ORDER BY - ascending`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v ASC")
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["v"])
        assertEquals(42, result.rows[1]["v"])
    }

    @Test
    fun `ORDER BY - descending`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v DESC")
        assertEquals(2, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
        assertEquals(7, result.rows[1]["v"])
    }

    @Test
    fun `SKIP and LIMIT`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v ASC SKIP 1 LIMIT 2")
        assertEquals(1, result.rows.size, "Only 2 rows total, skip 1 leaves 1")
        assertEquals(42, result.rows[0]["v"])
    }

    @Test
    fun `ORDER BY with LIMIT`() {
        val result = executor.execute("MATCH (n) RETURN n.id AS nid ORDER BY nid LIMIT 3")
        assertEquals(3, result.rows.size)
        // IDs should be in ascending order
        val ids = result.rows.map { (it["nid"] as Number).toInt() }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `UNION - deduplicates`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v UNION MATCH (n:StringConstant) RETURN n.value AS v")
        assertEquals(listOf("v"), result.columns)
        // 42, 7 from IntConstant + "hello" from StringConstant = 3 distinct
        assertEquals(3, result.rows.size)
        val values = result.rows.map { it["v"] }.toSet()
        assertTrue(values.contains(42))
        assertTrue(values.contains(7))
        assertTrue(values.contains("hello"))
    }

    @Test
    fun `UNION ALL - preserves duplicates`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v UNION ALL MATCH (n:IntConstant) RETURN n.value AS v")
        assertEquals(4, result.rows.size, "2 IntConstants x 2 queries = 4 rows with UNION ALL")
    }
}
