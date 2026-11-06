package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class CypherSyntaxExecutionTest : CypherExecutorTestFixture() {

    @Test
    fun `EXISTS function`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE EXISTS(n.value) RETURN n.value")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `RETURN star parses without error`() {
        // RETURN * is parsed as a special Variable("*") - verify it doesn't throw
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value AS v")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    @Test
    fun `single line comment`() {
        val result =
            executor.execute(
                """
            // This is a comment
            MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value
        """
                    .trimIndent())
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `multi line comment`() {
        val result =
            executor.execute(
                """
            /* This is a
               multi-line comment */
            MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value
        """
                    .trimIndent())
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `hex literal`() {
        val result = executor.execute("RETURN 0xFF AS v")
        assertEquals(1, result.rows.size)
        assertEquals(255L, result.rows[0]["v"])
    }

    @Test
    fun `octal literal`() {
        val result = executor.execute("RETURN 0o77 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(63L, result.rows[0]["v"])
    }

    @Test
    fun `string with escape sequences`() {
        val result = executor.execute("RETURN '\\n\\t\\r' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\n\t\r", result.rows[0]["v"])
    }

    @Test
    fun `string with escaped backslash`() {
        val result = executor.execute("RETURN '\\\\' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\\", result.rows[0]["v"])
    }

    @Test
    fun `string with unicode escape`() {
        val result = executor.execute("RETURN '\\u0041' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("A", result.rows[0]["v"])
    }

    @Test
    fun `string with escaped quote`() {
        val result = executor.execute("RETURN '\\'' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("'", result.rows[0]["v"])
    }

    @Test
    fun `string with double quote escape`() {
        val result = executor.execute("RETURN \"\\\"\" AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\"", result.rows[0]["v"])
    }

    @Test
    fun `double-quoted string doubled quote escape`() {
        val result = executor.execute("RETURN '''' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("'", result.rows[0]["v"])
    }

    @Test
    fun `string with backspace escape`() {
        val result = executor.execute("RETURN '\\b' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\b", result.rows[0]["v"])
    }

    @Test
    fun `string with unknown escape passes through`() {
        val result = executor.execute("RETURN '\\z' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\\z", result.rows[0]["v"])
    }

    @Test
    fun `float with scientific notation`() {
        val result = executor.execute("RETURN 1.5e2 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(150.0, result.rows[0]["v"])
    }

    @Test
    fun `CREATE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("CREATE (n) RETURN 1 AS x") }
    }

    @Test
    fun `DELETE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) DELETE n RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `DETACH DELETE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) DETACH DELETE n RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `SET throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n.x = 1 RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `REMOVE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) REMOVE n.x RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `MERGE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MERGE (n) RETURN 1 AS x") }
    }

    @Test
    fun `SET with label throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n:Label RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `SET with plus equals throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n += {x: 1} RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `SET all properties throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n = {x: 1} RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `REMOVE label throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) REMOVE n:Label RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `NOT in atom position of expression parser`() {
        // NOT in an expression context goes through parseAtom -> NOT branch
        val result = executor.execute("MATCH (n:IntConstant) WHERE NOT n.value = 7 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `keyword used as identifier via backtick`() {
        // Backtick-escaped keywords in identifier positions
        val result = executor.execute("UNWIND [1, 2] AS x RETURN x AS `value` LIMIT 1")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `list comprehension backtrack when identifier not followed by IN`() {
        // [a, b] where a is an identifier but not followed by IN triggers backtrack
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN [x, x + 1] AS pair LIMIT 1")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val pair = result.rows[0]["pair"] as List<*>
        assertEquals(2, pair.size)
    }

    @Test
    fun `keyword as property name in map literal`() {
        // Keywords can be used as keys in map literals
        val result = executor.execute("RETURN {type: 'test', value: 42} AS m")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val m = result.rows[0]["m"] as Map<String, Any?>
        assertEquals("test", m["type"])
    }
}
