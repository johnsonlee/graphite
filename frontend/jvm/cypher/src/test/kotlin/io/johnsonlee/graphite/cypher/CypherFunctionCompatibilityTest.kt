package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherFunctionCompatibilityTest : CypherCompatibilityTestFixture() {

    @Test
    fun `function - toInteger`() {
        val result = executor.execute("RETURN toInteger('42')")
        assertEquals(1, result.rows.size)
        assertEquals(42L, result.rows[0].values.first())
    }

    @Test
    fun `function - toFloat`() {
        val result = executor.execute("RETURN toFloat('3.14')")
        assertEquals(1, result.rows.size)
        assertEquals(3.14, result.rows[0].values.first())
    }

    @Test
    fun `function - toString`() {
        val result = executor.execute("RETURN toString(42)")
        assertEquals(1, result.rows.size)
        assertEquals("42", result.rows[0].values.first())
    }

    @Test
    fun `function - size of string`() {
        val result = executor.execute("RETURN size('hello')")
        assertEquals(1, result.rows.size)
        assertEquals(5, result.rows[0].values.first())
    }

    @Test
    fun `function - length of string`() {
        val result = executor.execute("RETURN length('hello')")
        assertEquals(1, result.rows.size)
        assertEquals(5, result.rows[0].values.first())
    }

    @Test
    fun `function - trim`() {
        val result = executor.execute("RETURN trim('  hello  ')")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0].values.first())
    }

    @Test
    fun `function - toLower`() {
        val result = executor.execute("RETURN toLower('HELLO')")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0].values.first())
    }

    @Test
    fun `function - toUpper`() {
        val result = executor.execute("RETURN toUpper('hello')")
        assertEquals(1, result.rows.size)
        assertEquals("HELLO", result.rows[0].values.first())
    }

    @Test
    fun `function - replace`() {
        val result = executor.execute("RETURN replace('hello', 'l', 'r')")
        assertEquals(1, result.rows.size)
        assertEquals("herro", result.rows[0].values.first())
    }

    @Test
    fun `function - substring`() {
        val result = executor.execute("RETURN substring('hello', 1, 3)")
        assertEquals(1, result.rows.size)
        assertEquals("ell", result.rows[0].values.first())
    }

    @Test
    fun `function - left`() {
        val result = executor.execute("RETURN left('hello', 3)")
        assertEquals(1, result.rows.size)
        assertEquals("hel", result.rows[0].values.first())
    }

    @Test
    fun `function - right`() {
        val result = executor.execute("RETURN right('hello', 3)")
        assertEquals(1, result.rows.size)
        assertEquals("llo", result.rows[0].values.first())
    }

    @Test
    fun `function - reverse`() {
        val result = executor.execute("RETURN reverse('hello')")
        assertEquals(1, result.rows.size)
        assertEquals("olleh", result.rows[0].values.first())
    }

    @Test
    fun `function - split`() {
        val result = executor.execute("RETURN split('a,b,c', ',')")
        assertEquals(1, result.rows.size)
        assertEquals(listOf("a", "b", "c"), result.rows[0].values.first())
    }

    @Test
    fun `function - abs`() {
        val result = executor.execute("RETURN abs(-42)")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0].values.first())
    }

    @Test
    fun `function - ceil`() {
        val result = executor.execute("RETURN ceil(3.2)")
        assertEquals(1, result.rows.size)
        assertEquals(4.0, result.rows[0].values.first())
    }

    @Test
    fun `function - floor`() {
        val result = executor.execute("RETURN floor(3.8)")
        assertEquals(1, result.rows.size)
        assertEquals(3.0, result.rows[0].values.first())
    }

    @Test
    fun `function - round`() {
        val result = executor.execute("RETURN round(3.5)")
        assertEquals(1, result.rows.size)
        assertEquals(4.0, result.rows[0].values.first())
    }

    @Test
    fun `function - sign`() {
        val result = executor.execute("RETURN sign(-42)")
        assertEquals(1, result.rows.size)
        assertEquals(-1, result.rows[0].values.first())
    }

    @Test
    fun `function - rand`() {
        val result = executor.execute("RETURN rand()")
        assertEquals(1, result.rows.size)
        val value = result.rows[0].values.first() as Double
        assertTrue(value >= 0.0 && value < 1.0, "rand() should return value in [0, 1)")
    }

    @Test
    fun `function - coalesce`() {
        val result = executor.execute("RETURN coalesce(null, 42)")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0].values.first())
    }

    @Test
    fun `function - head`() {
        val result = executor.execute("RETURN head([1, 2, 3])")
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows[0].values.first())
    }

    @Test
    fun `function - last`() {
        val result = executor.execute("RETURN last([1, 2, 3])")
        assertEquals(1, result.rows.size)
        assertEquals(3, result.rows[0].values.first())
    }

    @Test
    fun `function - tail`() {
        val result = executor.execute("RETURN tail([1, 2, 3])")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(2, 3), result.rows[0].values.first())
    }

    @Test
    fun `function - range`() {
        val result = executor.execute("RETURN range(1, 5)")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.rows[0].values.first())
    }

    @Test
    fun `function - keys on map`() {
        // keys() on a map literal - the CypherFunctions.keys only handles Node currently.
        // This tests whether it works for maps too.
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN keys(n)")
        assertEquals(1, result.rows.size)
        val keys = result.rows[0].values.first()
        assertNotNull(keys, "keys(n) should return property keys of the node")
        assertTrue(keys is List<*>)
        assertTrue((keys as List<*>).contains("value"))
        assertTrue(keys.contains("id"))
    }
}
