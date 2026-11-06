package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class CypherStringFunctionsTest : CypherFunctionsTestFixture() {

    @Test
    fun `toLower converts string to lowercase`() {
        assertEquals("hello", CypherFunctions.call("toLower", listOf("HELLO")))
        assertEquals("hello", CypherFunctions.call("toLowercase", listOf("HELLO")))
    }

    @Test
    fun `toLower returns null for null input`() {
        assertNull(CypherFunctions.call("toLower", listOf(null)))
    }

    @Test
    fun `toUpper converts string to uppercase`() {
        assertEquals("HELLO", CypherFunctions.call("toUpper", listOf("hello")))
        assertEquals("HELLO", CypherFunctions.call("toUppercase", listOf("hello")))
    }

    @Test
    fun `toUpper returns null for null input`() {
        assertNull(CypherFunctions.call("toUpper", listOf(null)))
    }

    @Test
    fun `trim removes whitespace`() {
        assertEquals("hello", CypherFunctions.call("trim", listOf("  hello  ")))
    }

    @Test
    fun `ltrim removes leading whitespace`() {
        assertEquals("hello  ", CypherFunctions.call("ltrim", listOf("  hello  ")))
    }

    @Test
    fun `rtrim removes trailing whitespace`() {
        assertEquals("  hello", CypherFunctions.call("rtrim", listOf("  hello  ")))
    }

    @Test
    fun `substring with start only`() {
        assertEquals("llo", CypherFunctions.call("substring", listOf("hello", 2)))
    }

    @Test
    fun `substring with start and length`() {
        assertEquals("ll", CypherFunctions.call("substring", listOf("hello", 2, 2)))
    }

    @Test
    fun `substring returns null for null input`() {
        assertNull(CypherFunctions.call("substring", listOf(null, 0)))
    }

    @Test
    fun `substring length exceeding string length is clamped`() {
        assertEquals("lo", CypherFunctions.call("substring", listOf("hello", 3, 100)))
    }

    @Test
    fun `split splits string by delimiter`() {
        assertEquals(listOf("a", "b", "c"), CypherFunctions.call("split", listOf("a,b,c", ",")))
    }

    @Test
    fun `replace replaces substring`() {
        assertEquals("hXllo", CypherFunctions.call("replace", listOf("hello", "e", "X")))
    }

    @Test
    fun `reverse reverses a string`() {
        assertEquals("olleh", CypherFunctions.call("reverse", listOf("hello")))
    }

    @Test
    fun `reverse reverses a list`() {
        assertEquals(listOf(3, 2, 1), CypherFunctions.call("reverse", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `reverse returns null for non-string non-list`() {
        assertNull(CypherFunctions.call("reverse", listOf(42)))
    }

    @Test
    fun `size of string returns length`() {
        assertEquals(5, CypherFunctions.call("size", listOf("hello")))
    }

    @Test
    fun `size of list returns count`() {
        assertEquals(3, CypherFunctions.call("size", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `length is alias for size`() {
        assertEquals(5, CypherFunctions.call("length", listOf("hello")))
    }

    @Test
    fun `size returns null for non-string non-list`() {
        assertNull(CypherFunctions.call("size", listOf(42)))
    }

    @Test
    fun `left returns first n characters`() {
        assertEquals("hel", CypherFunctions.call("left", listOf("hello", 3)))
    }

    @Test
    fun `right returns last n characters`() {
        assertEquals("llo", CypherFunctions.call("right", listOf("hello", 3)))
    }
}
