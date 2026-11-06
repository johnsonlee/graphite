package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherProjectionCompatibilityTest : CypherCompatibilityTestFixture() {

    @Test
    fun `RETURN - node reference`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN n")
        assertEquals(1, result.rows.size)
        val node = result.rows[0]["n"]
        assertTrue(node is Map<*, *>, "Materialized node should be a map")
        @Suppress("UNCHECKED_CAST") val map = node as Map<String, Any?>
        assertEquals(42, map["value"])
        assertEquals("IntConstant", map["type"])
    }

    @Test
    fun `RETURN - property access`() {
        val result = executor.execute("MATCH (n:StringConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0]["n.value"])
    }

    @Test
    fun `RETURN - alias`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN n.value AS val")
        assertEquals(listOf("val"), result.columns)
        assertEquals(42, result.rows[0]["val"])
    }

    @Test
    fun `RETURN DISTINCT`() {
        // Both callSites have callee_class starting with "com.example"
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN DISTINCT n.caller_class")
        assertEquals(1, result.rows.size)
        assertEquals("com.example.Service", result.rows[0]["n.caller_class"])
    }

    @Test
    fun `RETURN - count star`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN count(*)")
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["count(*)"])
    }

    @Test
    fun `RETURN - count expression`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN count(n)")
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["count(n)"])
    }

    @Test
    fun `RETURN - sum`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN sum(n.value)")
        assertEquals(1, result.rows.size)
        assertEquals(49.0, result.rows[0]["sum(n.value)"], "sum(42 + 7) = 49")
    }

    @Test
    fun `RETURN - avg`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN avg(n.value)")
        assertEquals(1, result.rows.size)
        assertEquals(24.5, result.rows[0]["avg(n.value)"], "avg(42, 7) = 24.5")
    }

    @Test
    fun `RETURN - min and max`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN min(n.value), max(n.value)")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["min(n.value)"], "min should be 7")
        assertEquals(42, result.rows[0]["max(n.value)"], "max should be 42")
    }

    @Test
    fun `RETURN - collect`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN collect(n.value)")
        assertEquals(1, result.rows.size)
        val collected = result.rows[0]["collect(n.value)"] as List<*>
        assertEquals(2, collected.size)
        assertTrue(collected.containsAll(listOf(42, 7)))
    }

    @Test
    fun `RETURN - grouped aggregation`() {
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN n.caller_class, count(*) AS cnt")
        assertEquals(1, result.rows.size, "Both callSites share the same caller_class")
        assertEquals("com.example.Service", result.rows[0]["n.caller_class"])
        assertEquals(2L, result.rows[0]["cnt"])
    }

    @Test
    fun `WITH - basic piping with filter`() {
        val result = executor.execute("MATCH (n:IntConstant) WITH n.value AS v WHERE v > 0 RETURN v")
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["v"] }.toSet()
        assertEquals(setOf(42, 7), values)
    }

    @Test
    fun `ORDER BY a grouping key written as the projected expression`() {
        // `n` is gone after the aggregation; the sort key is the column named by its text.
        val result = executor.execute("MATCH (n) RETURN n.type, count(*) ORDER BY n.type")
        val types = result.rows.map { it["n.type"] as String }
        assertTrue(types.size > 2, types.toString())
        assertEquals(types.sorted(), types)
        val descending = executor.execute("MATCH (n) RETURN n.type, count(*) ORDER BY n.type DESC")
        assertEquals(types.reversed(), descending.rows.map { it["n.type"] })
    }

    @Test
    fun `ORDER BY an aggregate written as the projected expression is rejected`() {
        // An aggregate in the sort key is evaluated outside RETURN, which both engines reject.
        val failure = assertFailsWith<RuntimeException> { executor.execute("MATCH (n) RETURN n.type, count(*) ORDER BY count(*) DESC") }
        assertEquals("Aggregation function 'count' must be used in RETURN or WITH clause", failure.message)
    }

    @Test
    fun `WITH - aggregation, ORDER BY, and LIMIT`() {
        val result =
            executor.execute("MATCH (n:CallSiteNode) WITH n.callee_class AS cls, count(*) AS cnt ORDER BY cnt DESC LIMIT 3 RETURN cls, cnt")
        assertTrue(result.rows.isNotEmpty())
        // Each callSite has a different callee_class, so each group has count 1
        for (row in result.rows) {
            assertNotNull(row["cls"])
            assertNotNull(row["cnt"])
        }
    }
}
