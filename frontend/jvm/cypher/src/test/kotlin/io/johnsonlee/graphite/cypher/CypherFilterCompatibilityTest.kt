package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import org.junit.Test

class CypherFilterCompatibilityTest : CypherCompatibilityTestFixture() {

    @Test
    fun `WHERE - equality`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - untyped node equality with double quoted string`() {
        val result = executor.execute("MATCH (n) WHERE n.callee_class = \"com.example.Repository\" RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - inequality`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value <> 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - greater than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value > 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - greater than or equal`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value >= 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - less than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value < 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - less than or equal`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value <= 7 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - regex match`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'com\\.example\\..*' RETURN n.callee_name")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - STARTS WITH`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH 'com' RETURN n.callee_name")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - ENDS WITH`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_class ENDS WITH 'Repository' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - CONTAINS`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_class CONTAINS 'example' RETURN n.callee_name")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - labels function with IN`() {
        val result = executor.execute("MATCH (n) WHERE 'CallSiteNode' IN labels(n) RETURN n.callee_name")
        assertEquals(2, result.rows.size)
        assertEquals(setOf("save", "log"), result.rows.map { it["n.callee_name"] }.toSet())
    }

    @Test
    fun `WHERE - ANY over labels function`() {
        val result = executor.execute("MATCH (n) WHERE ANY(label IN labels(n) WHERE label = 'CallSiteNode') RETURN n.callee_name")
        assertEquals(2, result.rows.size)
        assertEquals(setOf("save", "log"), result.rows.map { it["n.callee_name"] }.toSet())
    }

    @Test
    fun `RETURN - ANY over labels function projects boolean values`() {
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN ANY(label IN labels(n) WHERE label = 'CallSiteNode') AS isCallSite")
        assertEquals(2, result.rows.size)
        assertEquals(setOf(true), result.rows.map { it["isCallSite"] }.toSet())
    }

    @Test
    fun `WHERE - ALL over labels function`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE ALL(label IN labels(n) WHERE label CONTAINS 'Constant') RETURN n.value")
        assertEquals(2, result.rows.size)
        assertEquals(setOf(42, 7), result.rows.map { it["n.value"] }.toSet())
    }

    @Test
    fun `WITH - inline WHERE filters projected rows`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WITH n WHERE n.callee_class CONTAINS 'Repository' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - NOT CONTAINS`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE NOT n.callee_class CONTAINS 'Logger' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - NOT STARTS WITH`() {
        val result =
            executor.execute("MATCH (n:CallSiteNode) WHERE NOT n.callee_class STARTS WITH 'com.example.Logger' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - IN list`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value IN [1, 2, 42] RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - IS NULL`() {
        val result = executor.execute("MATCH (n:NullConstant) WHERE n.value IS NULL RETURN n.id")
        assertEquals(1, result.rows.size)
        assertEquals(nullConst.value, result.rows[0]["n.id"])
    }

    @Test
    fun `WHERE - IS NOT NULL`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value IS NOT NULL RETURN n.value")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - AND`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value > 0 AND n.value < 100 RETURN n.value")
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["n.value"] }.toSet()
        assertEquals(setOf(42, 7), values)
    }

    @Test
    fun `WHERE - OR`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 OR n.value = 7 RETURN n.value")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - NOT`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE NOT n.value = 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - XOR`() {
        // 42 > 0 is true, 42 < 0 is false => XOR is true
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value > 0 XOR n.value < 0 RETURN n.value")
        // Both 42 and 7 are > 0 and NOT < 0, so true XOR false = true for both
        assertEquals(2, result.rows.size)
    }
}
