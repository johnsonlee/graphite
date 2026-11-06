package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CypherExecutorTest : CypherExecutorTestFixture() {

    @Test
    fun `match nodes by label - CallSiteNode`() {
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN n.callee_name")
        assertEquals(listOf("n.callee_name"), result.columns)
        assertEquals(2, result.rows.size)
        val names = result.rows.map { it["n.callee_name"] }.toSet()
        assertTrue(names.contains("save"))
        assertTrue(names.contains("log"))
    }

    @Test
    fun `match nodes by label with LIMIT`() {
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 1")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `execute with maxRows adds server-side limit`() {
        val result = executor.execute("MATCH (n) RETURN n.id", maxRows = 2)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `execute with maxRows caps oversized literal limit`() {
        val result = executor.execute("MATCH (n) RETURN n.id LIMIT 100", maxRows = 2)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `execute with maxRows caps literal limit above Int range`() {
        val result = executor.execute("MATCH (n) RETURN n.id LIMIT 2147483648", maxRows = 2)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `execute with maxRows preserves smaller literal limit`() {
        val result = executor.execute("MATCH (n) RETURN n.id LIMIT 1", maxRows = 5)
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `execute with maxRows caps string literal limit`() {
        val result = executor.execute("MATCH (n) RETURN n.id LIMIT '100'", maxRows = 2)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `execute with maxRows preserves non numeric literal limit semantics`() {
        val result = executor.execute("MATCH (n) RETURN n.id LIMIT true", maxRows = 2)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `bound variable element id predicate uses normal match semantics`() {
        val result = executor.execute("MATCH (n:IntConstant) MATCH (n) " + "WHERE elementId(n) = '${intConst42.value}' RETURN n.value")

        assertEquals(listOf(mapOf("n.value" to 42)), result.rows)
    }

    @Test
    fun `match IntConstant with WHERE equality`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.id")
        assertEquals(1, result.rows.size)
        assertEquals(intConst42.value, result.rows[0]["n.id"])
    }

    @Test
    fun `match IntConstant with WHERE greater than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value > 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `match IntConstant with WHERE less than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value < 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `match StringConstant`() {
        val result = executor.execute("MATCH (n:StringConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0]["n.value"])
    }

    @Test
    fun `match BooleanConstant`() {
        val result = executor.execute("MATCH (n:BooleanConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["n.value"])
    }

    @Test
    fun `match EnumConstant`() {
        val result = executor.execute("MATCH (n:EnumConstant) RETURN n.name, n.enum_type")
        assertEquals(1, result.rows.size)
        assertEquals("ACTIVE", result.rows[0]["n.name"])
        assertEquals("com.example.Status", result.rows[0]["n.enum_type"])
    }

    @Test
    fun `match FieldNode with regex filter`() {
        val result = executor.execute("MATCH (n:FieldNode) WHERE n.class =~ 'com\\.example\\..*' RETURN n.name")
        assertEquals(1, result.rows.size)
        assertEquals("name", result.rows[0]["n.name"])
    }

    @Test
    fun `match FieldNode with non-matching regex`() {
        val result = executor.execute("MATCH (n:FieldNode) WHERE n.class =~ 'org\\.other\\..*' RETURN n.name")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `match ParameterNode`() {
        val result = executor.execute("MATCH (n:ParameterNode) RETURN n.index, n.type")
        assertEquals(1, result.rows.size)
        assertEquals(0, result.rows[0]["n.index"])
        // "type" resolves to the node-specific type property (parameter type class name)
        assertEquals("int", result.rows[0]["n.type"])
    }

    @Test
    fun `match ReturnNode`() {
        // Use backtick-escaped label because "ReturnNode" conflicts with RETURN keyword
        val result = executor.execute("MATCH (n:`ReturnNode`) RETURN n.method")
        assertEquals(1, result.rows.size)
        assertTrue((result.rows[0]["n.method"] as String).contains("process"))
    }

    @Test
    fun `match LocalVariable`() {
        val result = executor.execute("MATCH (n:LocalVariable) RETURN n.name")
        assertEquals(2, result.rows.size)
        val names = result.rows.map { it["n.name"] }.toSet()
        assertTrue(names.contains("x"))
        assertTrue(names.contains("y"))
    }

    @Test
    fun `return full node as map`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(42, nodeMap["value"])
        assertEquals("IntConstant", nodeMap["type"])
    }

    @Test
    fun `empty result for non-existent value`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 99999 RETURN n.id")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `empty result for non-matching filter`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 999 RETURN n.id")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `match all nodes without label`() {
        val result = executor.execute("MATCH (n) RETURN n.id LIMIT 5")
        assertEquals(5, result.rows.size)
    }

    @Test
    fun `match with STARTS WITH filter`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name STARTS WITH 'sa' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `match with STARTS WITH filter and limit`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name STARTS WITH 'sa' " + "RETURN n.callee_name LIMIT 1")

        assertEquals(listOf(mapOf("n.callee_name" to "save")), result.rows)
    }

    @Test
    fun `match with CONTAINS filter`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name CONTAINS 'av' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `match with ENDS WITH filter`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name ENDS WITH 'og' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("log", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `match with NOT EQUALS filter`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value <> 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }
}
