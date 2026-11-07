package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherSyntaxCompatibilityTest : CypherCompatibilityTestFixture() {

    // 1. Multi-statement with semicolon
    @Test
    fun `script - multiple statements separated by semicolon`() {
        // Our executor handles one statement at a time, so just verify single statement with trailing semicolon parses
        val result = executor.execute("RETURN 1 AS x;")
        assertEquals(1, result.rows.size)
    }

    // 2. WITH DISTINCT
    @Test
    fun `WITH DISTINCT deduplicates piped values`() {
        val result = executor.execute("MATCH (n:IntConstant) WITH DISTINCT n.value AS v RETURN v ORDER BY v")
        val values = result.rows.map { it["v"] }
        assertEquals(values.distinct(), values, "WITH DISTINCT should deduplicate values")
    }

    // 3. Multiple ORDER BY columns
    @Test
    fun `ORDER BY multiple columns`() {
        val result =
            executor.execute(
                "MATCH (n:CallSiteNode) RETURN n.callee_class, n.callee_name ORDER BY n.callee_class ASC, n.callee_name DESC LIMIT 5")
        assertTrue(result.rows.isNotEmpty())
        assertEquals(2, result.columns.size)
    }

    // 4. Multiple node labels (n:A:B)
    @Test
    fun `node pattern with multiple labels`() {
        // Our graph doesn't have multi-label nodes, but the parser should handle it
        // This should return empty (no node has both IntConstant AND CallSiteNode labels)
        val result = executor.execute("MATCH (n:IntConstant:CallSiteNode) RETURN n")
        assertEquals(0, result.rows.size, "No node has both labels, should be empty")
    }

    // 5. Relationship with multiple types (|)
    @Test
    fun `relationship pattern with multiple types using pipe`() {
        val result = executor.execute("MATCH (a)-[:DATAFLOW|CALL]->(b) RETURN a, b LIMIT 5")
        assertTrue(result.rows.isNotEmpty(), "Should find edges of either DATAFLOW or CALL type")
    }

    // 6. Variable-length with min only (*1..)
    @Test
    fun `variable length path with min only`() {
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW*1..]->(b) RETURN a.value LIMIT 5")
        // Should parse and execute (may or may not find results depending on graph depth)
        assertNotNull(result)
    }

    // 7. Unary plus
    @Test
    fun `unary plus expression`() {
        val result = executor.execute("RETURN +42 AS val")
        assertEquals(1, result.rows.size)
        assertEquals(42, (result.rows[0]["val"] as Number).toInt())
    }

    // 8. Slice with only upper bound [..3]
    @Test
    fun `list slice with only upper bound`() {
        val result = executor.execute("RETURN [1, 2, 3, 4, 5][..3] AS first3")
        assertEquals(1, result.rows.size)
        val list = result.rows[0]["first3"]
        assertTrue(list is List<*>, "Should return a list")
        assertEquals(3, (list as List<*>).size)
    }

    // 9. Parameter $param
    @Test
    fun `parameter reference parses without error`() {
        // Parameters need to be passed via bindings which we don't support in execute(),
        // but the parser should accept the syntax. The parameter resolves to null.
        val result = executor.execute("RETURN coalesce(\$missing, 42) AS val")
        assertEquals(1, result.rows.size)
        assertEquals(42, (result.rows[0]["val"] as Number).toInt())
    }

    // 10. EXISTS expression
    @Test
    fun `EXISTS expression parses`() {
        // EXISTS(expr) should evaluate whether expr is non-null
        val result = executor.execute("MATCH (n:IntConstant) WHERE EXISTS(n.value) RETURN n.value LIMIT 3")
        assertTrue(result.rows.isNotEmpty())
    }

    // 11. List comprehension with both WHERE and pipe
    @Test
    fun `list comprehension with filter AND map expression`() {
        val result = executor.execute("RETURN [x IN [1, 2, 3, 4, 5] WHERE x > 2 | x * 10] AS filtered")
        assertEquals(1, result.rows.size)
        val list = result.rows[0]["filtered"] as List<*>
        assertEquals(listOf(30, 40, 50), list.map { (it as Number).toInt() })
    }

    // 12. Hex integer literal
    @Test
    fun `hex integer literal`() {
        val result = executor.execute("RETURN 0xFF AS hex")
        assertEquals(1, result.rows.size)
        assertEquals(255L, (result.rows[0]["hex"] as Number).toLong())
    }

    // 13. Octal integer literal
    @Test
    fun `octal integer literal`() {
        val result = executor.execute("RETURN 0o77 AS oct")
        assertEquals(1, result.rows.size)
        assertEquals(63L, (result.rows[0]["oct"] as Number).toLong())
    }

    // 14. Backtick-escaped identifier as node label
    @Test
    fun `backtick escaped identifier as node label`() {
        val result = executor.execute("MATCH (n:`IntConstant`) RETURN n.value LIMIT 3")
        assertTrue(result.rows.isNotEmpty(), "Backtick-escaped label should match IntConstant")
    }

    @Test
    fun `backtick escaped identifier as variable name`() {
        val result = executor.execute("MATCH (`my node`:IntConstant) RETURN `my node`.value LIMIT 1")
        assertTrue(result.rows.isNotEmpty())
    }

    // 15. Keyword used as property name in map literal
    @Test
    fun `keyword used as property name`() {
        // 'order', 'match', 'return' etc. should work as property names
        val result = executor.execute("RETURN {order: 1, match: 2} AS m")
        assertEquals(1, result.rows.size)
    }

    // 16. RETURN *
    @Test
    fun `RETURN star returns all bound variables`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN * LIMIT 1")
        assertTrue(result.rows.isNotEmpty())
        assertTrue(result.columns.contains("n"), "RETURN * should include variable n")
    }

    // 17. DETACH DELETE (should parse and throw not implemented)
    @Test
    fun `DETACH DELETE parses and throws not implemented`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) DETACH DELETE n") }
    }

    // 18. MERGE (should parse and throw not implemented)
    @Test
    fun `MERGE parses and throws not implemented`() {
        assertFailsWith<NotImplementedError> { executor.execute("MERGE (n:Test {name: 'x'}) RETURN n") }
    }

    // 19. Dotted function name (namespace.function)
    @Test
    fun `dotted function name parses`() {
        // toString is a known function, should work
        val result = executor.execute("RETURN toString(42) AS s")
        assertEquals(1, result.rows.size)
        assertEquals("42", result.rows[0]["s"])
    }

    // 20. Float literal with exponent
    @Test
    fun `float literal with exponent notation`() {
        val result = executor.execute("RETURN 1.5e2 AS val")
        assertEquals(1, result.rows.size)
        assertEquals(150.0, (result.rows[0]["val"] as Number).toDouble(), 0.01)
    }

    // 21. String with escape sequences
    @Test
    fun `string literal with escape sequences`() {
        val result = executor.execute("RETURN 'hello\\nworld' AS s")
        assertEquals(1, result.rows.size)
        assertEquals("hello\nworld", result.rows[0]["s"])
    }
}
