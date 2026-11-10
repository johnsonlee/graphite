package io.johnsonlee.graphite.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MethodObservationTest {
    @Test
    fun `advertised call site properties all accept literal selectors`() {
        assertEquals(setOf("callee_class", "callee_name", "callee_signature", "callee_descriptor",
            "caller_class", "caller_name", "caller_signature", "caller_descriptor", "ordinal"), ConstantFold.CALL_SITE_PROPERTIES)
        val selectors = ConstantFold.CALL_SITE_PROPERTIES.associateWith { if (it == "ordinal") "0" else "target" }
        val rule = ConstantFold.parse(mapOf("match" to mapOf("CallSite" to selectors), "value" to false))
        assertTrue(rule.matchesCallSite(selectors))
        assertFalse(rule.matchesCallSite(selectors + ("caller_name" to "other")))
    }

    @Test
    fun `agreement compares folding counts diagnostics and both statement totals`() {
        fun observation(calls: Map<Int, Int> = mapOf(0 to 2), reasons: Map<Int, List<String>> = mapOf(1 to listOf("unsupported")),
                        before: Int? = 5, after: Int? = 3) = MethodFoldObservation(calls, reasons, before, after)
        val expected = observation()
        assertTrue(expected.agreesWith(observation()))
        assertFalse(expected.agreesWith(observation(calls = mapOf(0 to 1))))
        assertFalse(expected.agreesWith(observation(reasons = emptyMap())))
        assertFalse(expected.agreesWith(observation(before = null)))
        assertFalse(expected.agreesWith(observation(after = 4)))
        assertTrue(observation(before = null, after = null).agreesWith(observation(before = null, after = null)))
    }

    @Test
    fun `equal fold rules remain interchangeable as hash map keys`() {
        val document = mapOf("match" to mapOf("CallSite" to mapOf("callee_name" to "enabled")), "value" to false)
        val rule = ConstantFold.parse(document)
        val equivalent = ConstantFold.parse(document)
        assertEquals(rule, equivalent)
        assertEquals(rule.hashCode(), equivalent.hashCode())
        assertEquals("fold", mapOf(rule to "fold")[equivalent])
        val identity = AnalysisIdentity(includePackages = listOf("b", "a"), dependencyPackages = listOf("y", "x"))
        val reordered = identity.copy(includePackages = listOf("a", "b"), dependencyPackages = listOf("x", "y"))
        assertEquals(identity, reordered)
        assertEquals(identity.hashCode(), reordered.hashCode())
        assertEquals("graph", mapOf(identity to "graph")[reordered])
    }
}
