package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherAdvancedCompatibilityTest : CypherCompatibilityTestFixture() {

    @Test
    fun `complex query - multi-hop dataflow path with filter`() {
        // Trace dataflow from IntConstant(42) through the graph
        val result =
            executor.execute(
                """MATCH (src:IntConstant {value: 42})-[:DATAFLOW*1..4]->(dst)
               RETURN dst.id""")
        assertTrue(result.rows.isNotEmpty(), "Should find nodes reachable via dataflow from IntConstant(42)")
        val reachableIds = result.rows.map { it["dst.id"] }.toSet()
        assertTrue(reachableIds.contains(param1.value), "param1 reachable at hop 1")
        assertTrue(reachableIds.contains(localVar1.value), "localVar1 reachable at hop 2")
        assertTrue(reachableIds.contains(localVar2.value), "localVar2 reachable at hop 3")
        assertTrue(reachableIds.contains(callSite1.value), "callSite1 reachable at hop 4")
    }

    @Test
    fun `RETURN star is supported`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN *")
        assertEquals(1, result.rows.size)
        // The * should include n
        assertTrue(result.rows[0].containsKey("n") || result.rows[0].containsKey("*"), "RETURN * should include matched variables")
    }

    @Test
    fun `nested function calls`() {
        val result = executor.execute("RETURN toInteger(toString(42))")
        assertEquals(1, result.rows.size)
        assertEquals(42L, result.rows[0].values.first())
    }

    @Test
    fun `boolean literal in RETURN`() {
        val result = executor.execute("RETURN true, false")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0].values.first())
        assertEquals(false, result.rows[0].values.last())
    }

    @Test
    fun `null literal in RETURN`() {
        val result = executor.execute("RETURN null")
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0].values.first())
    }

    @Test
    fun `MATCH with CALL edge type`() {
        val result = executor.execute("MATCH (a)-[r:CALL]->(b) RETURN a.id, b.id")
        assertEquals(1, result.rows.size, "Only one CALL edge: callSite1 -> return1")
    }

    @Test
    fun `aggregation - count with DISTINCT`() {
        // Both callSites share the same caller_class
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN count(DISTINCT n.caller_class) AS cnt")
        assertEquals(1, result.rows.size)
        assertEquals(1L, result.rows[0]["cnt"], "Only 1 distinct caller_class")
    }

    @Test
    fun `WHERE with multiple conditions chained`() {
        val result =
            executor.execute(
                """MATCH (n:CallSiteNode)
               WHERE n.callee_class STARTS WITH 'com'
               AND n.callee_name = 'save'
               AND n.line = 10
               RETURN n.callee_name""")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WITH piping preserves data between clauses`() {
        val result =
            executor.execute(
                """MATCH (n:IntConstant)
               WITH n.value AS v
               WHERE v = 42
               RETURN v""")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    @Test
    fun `UNWIND combined with MATCH`() {
        val result =
            executor.execute(
                """UNWIND [42, 7] AS targetValue
               MATCH (n:IntConstant)
               WHERE n.value = targetValue
               RETURN n.value AS v ORDER BY v""")
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["v"])
        assertEquals(42, result.rows[1]["v"])
    }

    @Test
    fun `expression - negative number in arithmetic`() {
        val result = executor.execute("RETURN 5 + (-3)")
        assertEquals(1, result.rows.size)
        val value = (result.rows[0].values.first() as Number).toLong()
        assertEquals(2L, value)
    }

    @Test
    fun `MATCH - EnumConstant properties`() {
        val result = executor.execute("MATCH (n:EnumConstant) RETURN n.name, n.enum_type")
        assertEquals(1, result.rows.size)
        assertEquals("ACTIVE", result.rows[0]["n.name"])
        assertEquals("com.example.Status", result.rows[0]["n.enum_type"])
    }

    @Test
    fun `MATCH - BooleanConstant`() {
        val result = executor.execute("MATCH (n:BooleanConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["n.value"])
    }

    @Test
    fun `MATCH - LongConstant`() {
        val result = executor.execute("MATCH (n:LongConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(999999999999L, result.rows[0]["n.value"])
    }

    @Test
    fun `MATCH - FloatConstant`() {
        val result = executor.execute("MATCH (n:FloatConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        val value = (result.rows[0]["n.value"] as Number).toDouble()
        assertTrue(value > 3.13 && value < 3.15, "FloatConstant should be ~3.14")
    }

    @Test
    fun `MATCH - DoubleConstant`() {
        val result = executor.execute("MATCH (n:DoubleConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(2.718, result.rows[0]["n.value"])
    }

    @Test
    fun `MATCH - FieldNode properties`() {
        val result = executor.execute("MATCH (n:FieldNode) RETURN n.name, n.class, n.static")
        assertEquals(1, result.rows.size)
        assertEquals("name", result.rows[0]["n.name"])
        assertEquals("com.example.Service", result.rows[0]["n.class"])
        assertEquals(false, result.rows[0]["n.static"])
    }

    @Test
    fun `MATCH - ParameterNode properties`() {
        val result = executor.execute("MATCH (n:ParameterNode) RETURN n.index, n.type")
        assertEquals(1, result.rows.size)
        assertEquals(0, result.rows[0]["n.index"])
        assertEquals("int", result.rows[0]["n.type"])
    }

    @Test
    fun `MATCH - LocalVariable properties`() {
        val result = executor.execute("MATCH (n:LocalVariable) RETURN n.name AS name ORDER BY name")
        assertEquals(2, result.rows.size)
        assertEquals("x", result.rows[0]["name"])
        assertEquals("y", result.rows[1]["name"])
    }

    @Test
    fun `empty MATCH result with WHERE filtering everything`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 999 RETURN n.value")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `LIMIT 0 returns empty result`() {
        val result = executor.execute("MATCH (n) RETURN n LIMIT 0")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `SKIP beyond result set returns empty`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value SKIP 100")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `function - size of list`() {
        val result = executor.execute("RETURN size([1, 2, 3, 4])")
        assertEquals(1, result.rows.size)
        assertEquals(4, result.rows[0].values.first())
    }

    @Test
    fun `expression - deeply nested parentheses`() {
        val result = executor.execute("RETURN ((1 + 2) * (3 + 4))")
        assertEquals(1, result.rows.size)
        assertEquals(21L, (result.rows[0].values.first() as Number).toLong())
    }

    @Test
    fun `CASE with no matching WHEN and no ELSE returns null`() {
        val result = executor.execute("RETURN CASE 99 WHEN 1 THEN 'one' END")
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0].values.first())
    }

    @Test
    fun `multiple WHERE conditions with OR and AND precedence`() {
        // AND binds tighter than OR
        // This should match: callee_name = 'save' OR (callee_name = 'log' AND line = 20)
        val result =
            executor.execute(
                """MATCH (n:CallSiteNode)
               WHERE n.callee_name = 'save' OR n.callee_name = 'log' AND n.line = 20
               RETURN n.callee_name AS name ORDER BY name""")
        assertEquals(2, result.rows.size)
        assertEquals("log", result.rows[0]["name"])
        assertEquals("save", result.rows[1]["name"])
    }
}
