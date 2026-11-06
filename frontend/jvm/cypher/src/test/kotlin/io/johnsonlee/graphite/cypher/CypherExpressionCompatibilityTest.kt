package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class CypherExpressionCompatibilityTest : CypherCompatibilityTestFixture() {

    @Test
    fun `expression - addition`() {
        val result = executor.execute("RETURN 1 + 2")
        assertEquals(1, result.rows.size)
        assertEquals(3L, result.rows[0].values.first(), "1 + 2 = 3")
    }

    @Test
    fun `expression - subtraction`() {
        val result = executor.execute("RETURN 10 - 3")
        assertEquals(1, result.rows.size)
        assertEquals(7L, result.rows[0].values.first())
    }

    @Test
    fun `expression - multiplication`() {
        val result = executor.execute("RETURN 2 * 3")
        assertEquals(1, result.rows.size)
        assertEquals(6L, result.rows[0].values.first())
    }

    @Test
    fun `expression - division`() {
        val result = executor.execute("RETURN 10 / 3")
        assertEquals(1, result.rows.size)
        // Integer division in Cypher may produce double
        val value = (result.rows[0].values.first() as Number).toDouble()
        assertTrue(value > 3.0 && value < 4.0, "10/3 should be ~3.33")
    }

    @Test
    fun `expression - modulo`() {
        val result = executor.execute("RETURN 10 % 3")
        assertEquals(1, result.rows.size)
        assertEquals(1.0, (result.rows[0].values.first() as Number).toDouble(), "10 % 3 = 1")
    }

    @Test
    fun `expression - exponentiation`() {
        val result = executor.execute("RETURN 2 ^ 3")
        assertEquals(1, result.rows.size)
        assertEquals(8.0, (result.rows[0].values.first() as Number).toDouble())
    }

    @Test
    fun `expression - unary minus`() {
        val result = executor.execute("RETURN -42")
        assertEquals(1, result.rows.size)
        assertEquals(-42, result.rows[0].values.first())
    }

    @Test
    fun `expression - string concatenation`() {
        val result = executor.execute("RETURN 'hello' + ' ' + 'world'")
        assertEquals(1, result.rows.size)
        assertEquals("hello world", result.rows[0].values.first())
    }

    @Test
    fun `expression - list literal`() {
        val result = executor.execute("RETURN [1, 2, 3]")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows[0].values.first())
    }

    @Test
    fun `expression - subscript`() {
        val result = executor.execute("RETURN [1, 2, 3][0]")
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows[0].values.first())
    }

    @Test
    fun `expression - slice`() {
        val result = executor.execute("RETURN [1, 2, 3][1..3]")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(2, 3), result.rows[0].values.first())
    }

    @Test
    fun `expression - map literal`() {
        val result = executor.execute("RETURN {name: 'test', value: 42}")
        assertEquals(1, result.rows.size)
        val map = result.rows[0].values.first()
        assertTrue(map is Map<*, *>)
        @Suppress("UNCHECKED_CAST") val m = map as Map<String, Any?>
        assertEquals("test", m["name"])
        assertEquals(42, m["value"])
    }

    @Test
    fun `expression - list comprehension with filter`() {
        val result = executor.execute("RETURN [x IN [1, 2, 3] WHERE x > 1]")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(2, 3), result.rows[0].values.first())
    }

    @Test
    fun `expression - list comprehension with map`() {
        val result = executor.execute("RETURN [x IN [1, 2, 3] | x * 2]")
        assertEquals(1, result.rows.size)
        val values = result.rows[0].values.first() as List<*>
        assertEquals(3, values.size)
        // Values should be 2.0, 4.0, 6.0 (due to arithmetic returning doubles when mixing)
        val numValues = values.map { (it as Number).toDouble() }
        assertEquals(listOf(2.0, 4.0, 6.0), numValues)
    }

    @Test
    fun `CASE - generic WHEN THEN ELSE`() {
        val result = executor.execute("RETURN CASE WHEN 1 > 0 THEN 'yes' ELSE 'no' END")
        assertEquals(1, result.rows.size)
        assertEquals("yes", result.rows[0].values.first())
    }

    @Test
    fun `CASE - simple form`() {
        val result = executor.execute("RETURN CASE 42 WHEN 1 THEN 'one' WHEN 42 THEN 'forty-two' ELSE 'other' END")
        assertEquals(1, result.rows.size)
        assertEquals("forty-two", result.rows[0].values.first())
    }

    @Test
    fun `CASE - else branch`() {
        val result = executor.execute("RETURN CASE 99 WHEN 1 THEN 'one' WHEN 42 THEN 'forty-two' ELSE 'other' END")
        assertEquals(1, result.rows.size)
        assertEquals("other", result.rows[0].values.first())
    }

    @Test
    fun `CASE - with graph data`() {
        val result =
            executor.execute(
                """MATCH (n:IntConstant)
               RETURN CASE WHEN n.value > 10 THEN 'big' ELSE 'small' END AS category, n.value AS v
               ORDER BY v""")
        assertEquals(2, result.rows.size)
        assertEquals("small", result.rows[0]["category"]) // 7
        assertEquals("big", result.rows[1]["category"]) // 42
    }

    @Test
    fun `write - CREATE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("CREATE (n:Test {name: 'test'})") }
    }

    @Test
    fun `write - DELETE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n:IntConstant) DELETE n") }
    }

    @Test
    fun `write - SET throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n:IntConstant) SET n.value = 1") }
    }

    @Test
    fun `write - REMOVE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n:IntConstant) REMOVE n.value") }
    }
}
