package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import it.unimi.dsi.lang.MutableString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StringMatchingTest {
    // U+0130 lowercases to "i" followed by U+0307: one UTF-16 unit becomes two.
    private val dottedI = "\u0130"
    private val loweredDottedI = "i\u0307"

    @Test
    fun `length shortcut rejects shorter untransformed and ascii values`() {
        assertFalse(stringMatches("ab", null, StringMatchMode.EQUALS, "abc"))
        assertFalse(stringMatches("Ab", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "abc"))
        assertFalse(reusableContains(MutableString("Ab"), StringValueTransform.LOWERCASE, "abc"))
        assertFalse(reusableContains(MutableString("ab"), null, "abc"))
        assertFalse(reusableMatches(MutableString("Ab"), lower(StringMatchMode.EQUALS, "abc")))
        assertFalse(reusableMatches(MutableString("ab"), predicate(null, StringMatchMode.EQUALS, "abc")))
        assertTrue(stringMatches("ABC", StringValueTransform.LOWERCASE, StringMatchMode.EQUALS, "abc"))
        assertTrue(reusableMatches(MutableString("xABCx"), lower(StringMatchMode.CONTAINS, "abc")))
    }

    @Test
    fun `lowercase mappings that expand a code point still match`() {
        assertEquals(2, dottedI.lowercase().length)
        assertTrue(stringMatches(dottedI, StringValueTransform.LOWERCASE, StringMatchMode.EQUALS, loweredDottedI))
        assertTrue(
            stringMatches("${dottedI}stanbul", StringValueTransform.LOWERCASE, StringMatchMode.STARTS_WITH, "${loweredDottedI}stanbul")
        )
        assertTrue(stringMatches("Ex.$dottedI", StringValueTransform.LOWERCASE, StringMatchMode.ENDS_WITH, "ex.$loweredDottedI"))
        assertTrue(reusableContains(MutableString(dottedI), StringValueTransform.LOWERCASE, loweredDottedI))
        assertTrue(reusableContains(MutableString("a${dottedI}b"), StringValueTransform.LOWERCASE, "a${loweredDottedI}b"))
        assertTrue(reusableMatches(MutableString(dottedI), lower(StringMatchMode.EQUALS, loweredDottedI)))
        assertTrue(reusableMatches(MutableString("${dottedI}x"), lower(StringMatchMode.CONTAINS, "${loweredDottedI}x")))
        // A non-ASCII value that is still too short after lowercasing is rejected on the transformed length.
        assertFalse(stringMatches(dottedI, StringValueTransform.LOWERCASE, StringMatchMode.EQUALS, "${loweredDottedI}x"))
        assertFalse(reusableMatches(MutableString(dottedI), lower(StringMatchMode.EQUALS, "${loweredDottedI}x")))
        // Without a transform the raw length is exact.
        assertFalse(stringMatches(dottedI, null, StringMatchMode.EQUALS, loweredDottedI))
    }

    private fun predicate(transform: StringValueTransform?, mode: StringMatchMode, expected: String) =
        StringPropertyPredicate("caller_class", transform, mode, expected)

    private fun lower(mode: StringMatchMode, expected: String) = predicate(StringValueTransform.LOWERCASE, mode, expected)
}
