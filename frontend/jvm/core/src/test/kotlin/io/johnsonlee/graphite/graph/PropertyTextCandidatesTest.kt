package io.johnsonlee.graphite.graph

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropertyTextCandidatesTest {
    @Test
    fun `plural selection retains two longest distinct fragments with stable ties`() {
        assertEquals(listOf("permission", "INTERNET"), propertyTextFragments("android.permission.INTERNET"))
        assertEquals(listOf("alpha", "bravo"), propertyTextFragments("alpha.alpha.bravo.delta"))
        assertEquals(listOf("longestName", "alpha"), propertyTextFragments("alpha.bravo.longestName"))
        assertEquals(listOf("example"), propertyTextFragments("E123.true.example"))
        assertEquals(emptyList(), propertyTextFragments("123.NaN.Infinity"))
    }

    @Test
    fun `dotted method search selects longest stored component fragment`() {
        assertEquals("checkVoucher", propertyTextFragment("com.example.Service.checkVoucher(java.lang.String)"))
        assertEquals("Service", propertyTextFragment("Service.foo("))
    }

    @Test
    fun `joins and list punctuation cannot become fragment characters`() {
        assertEquals("long_argument", propertyTextFragment("[a, long_argument], foo(bar)"))
        assertEquals("qualifier", propertyTextFragment("graph:qualifier-12"))
        assertNull(propertyTextFragment(".(),[]:+-123.45"))
    }

    @Test
    fun `scientific notation tokens are never eligible`() {
        for (needle in listOf("E123", "e123", "1.23E45", "1.23e-45", "E", "eE123")) {
            assertNull(propertyTextFragment(needle), needle)
        }
        assertEquals("example", propertyTextFragment("1.23E45.example"))
    }

    @Test
    fun `all substrings of generated primitive tokens are ineligible`() {
        for (token in listOf("true", "false", "null", "NaN", "Infinity")) {
            for (start in token.indices) {
                for (end in start + 1..token.length) {
                    val substring = token.substring(start, end)
                    assertNull(propertyTextFragment(substring), substring)
                }
            }
        }
    }

    @Test
    fun `selection preserves case underscores and stable ties`() {
        assertEquals("True", propertyTextFragment("True"))
        assertEquals("_id2", propertyTextFragment("_id2"))
        assertEquals("alpha", propertyTextFragment("alpha.bravo"))
        assertNull(propertyTextFragment(""))
        assertNull(propertyTextFragment("查询"))
    }

    @Test
    fun `signature boundary matches retain a necessary stored component`() {
        val components = listOf("com.example.Service", "checkVoucher", "java.lang.String", "int")
        val signature = "${components[0]}.${components[1]}(${components.drop(2).joinToString(",")})"
        // Exhaust every substring, including partial names spanning each synthesized delimiter.
        for (start in signature.indices) {
            for (end in start + 1..signature.length) {
                val needle = signature.substring(start, end)
                for (fragment in propertyTextFragments(needle)) {
                    assertTrue(needle.contains(fragment), needle)
                    assertTrue(components.any { it.contains(fragment) }, "$needle -> $fragment")
                }
            }
        }
    }
}
