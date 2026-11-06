package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class CypherConversionFunctionsTest : CypherFunctionsTestFixture() {

    @Test
    fun `toInteger from number`() {
        assertEquals(42L, CypherFunctions.call("toInteger", listOf(42)))
        assertEquals(42L, CypherFunctions.call("toInteger", listOf(42.9)))
    }

    @Test
    fun `toInteger from string`() {
        assertEquals(42L, CypherFunctions.call("toInteger", listOf("42")))
    }

    @Test
    fun `toInteger from boolean`() {
        assertEquals(1L, CypherFunctions.call("toInteger", listOf(true)))
        assertEquals(0L, CypherFunctions.call("toInteger", listOf(false)))
    }

    @Test
    fun `toInteger from invalid string returns null`() {
        assertNull(CypherFunctions.call("toInteger", listOf("abc")))
    }

    @Test
    fun `toInteger from null returns null`() {
        assertNull(CypherFunctions.call("toInteger", listOf(null)))
    }

    @Test
    fun `toInt is alias for toInteger`() {
        assertEquals(42L, CypherFunctions.call("toInt", listOf(42)))
    }

    @Test
    fun `toFloat from number`() {
        assertEquals(42.0, CypherFunctions.call("toFloat", listOf(42)))
    }

    @Test
    fun `toFloat from string`() {
        assertEquals(3.14, CypherFunctions.call("toFloat", listOf("3.14")))
    }

    @Test
    fun `toFloat from invalid string returns null`() {
        assertNull(CypherFunctions.call("toFloat", listOf("abc")))
    }

    @Test
    fun `toFloat from null returns null`() {
        assertNull(CypherFunctions.call("toFloat", listOf(null)))
    }

    @Test
    fun `toBoolean from boolean`() {
        assertEquals(true, CypherFunctions.call("toBoolean", listOf(true)))
        assertEquals(false, CypherFunctions.call("toBoolean", listOf(false)))
    }

    @Test
    fun `toBoolean from string`() {
        assertEquals(true, CypherFunctions.call("toBoolean", listOf("true")))
        assertEquals(true, CypherFunctions.call("toBoolean", listOf("TRUE")))
        assertEquals(false, CypherFunctions.call("toBoolean", listOf("false")))
    }

    @Test
    fun `toBoolean from invalid string returns null`() {
        assertNull(CypherFunctions.call("toBoolean", listOf("maybe")))
    }

    @Test
    fun `toBoolean from null returns null`() {
        assertNull(CypherFunctions.call("toBoolean", listOf(null)))
    }

    @Test
    fun `toString converts value`() {
        assertEquals("42", CypherFunctions.call("toString", listOf(42)))
        assertEquals("true", CypherFunctions.call("toString", listOf(true)))
        assertEquals("hello", CypherFunctions.call("toString", listOf("hello")))
    }

    @Test
    fun `toString returns null for null input`() {
        assertNull(CypherFunctions.call("toString", listOf(null)))
    }
}
