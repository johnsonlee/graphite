package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class BooleanComparisonExpressionTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `equality comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("=", lit(42), lit(42))))
        assertEquals(false, eval(CypherExpr.Comparison("=", lit(42), lit(7))))
    }

    @Test
    fun `not-equal comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<>", lit(1), lit(2))))
        assertEquals(false, eval(CypherExpr.Comparison("<>", lit(1), lit(1))))
    }

    @Test
    fun `less than comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<", lit(1), lit(2))))
        assertEquals(false, eval(CypherExpr.Comparison("<", lit(2), lit(1))))
    }

    @Test
    fun `greater than comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison(">", lit(2), lit(1))))
    }

    @Test
    fun `less than or equal comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<=", lit(1), lit(1))))
        assertEquals(true, eval(CypherExpr.Comparison("<=", lit(1), lit(2))))
    }

    @Test
    fun `greater than or equal comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison(">=", lit(2), lit(2))))
        assertEquals(true, eval(CypherExpr.Comparison(">=", lit(3), lit(2))))
    }

    @Test
    fun `comparison with null - equality`() {
        assertNull(eval(CypherExpr.Comparison("=", lit(null), lit(null))))
        assertNull(eval(CypherExpr.Comparison("=", lit(null), lit(1))))
        assertNull(eval(CypherExpr.Comparison("=", lit(1), lit(null))))
    }

    @Test
    fun `comparison with null - not-equal`() {
        assertNull(eval(CypherExpr.Comparison("<>", lit(null), lit(null))))
        assertNull(eval(CypherExpr.Comparison("<>", lit(null), lit(1))))
        assertNull(eval(CypherExpr.Comparison("<>", lit(1), lit(null))))
    }

    @Test
    fun `comparison with null - less than`() {
        assertNull(eval(CypherExpr.Comparison("<", lit(null), lit(1))))
    }

    @Test
    fun `AND - true AND true`() {
        assertEquals(true, eval(CypherExpr.And(lit(true), lit(true))))
    }

    @Test
    fun `AND - true AND false`() {
        assertEquals(false, eval(CypherExpr.And(lit(true), lit(false))))
    }

    @Test
    fun `AND - true AND null`() {
        assertNull(eval(CypherExpr.And(lit(true), lit(null))))
    }

    @Test
    fun `AND - false AND null`() {
        assertEquals(false, eval(CypherExpr.And(lit(false), lit(null))))
    }

    @Test
    fun `AND - null AND null`() {
        assertNull(eval(CypherExpr.And(lit(null), lit(null))))
    }

    @Test
    fun `OR - true OR null`() {
        assertEquals(true, eval(CypherExpr.Or(lit(true), lit(null))))
    }

    @Test
    fun `OR - false OR null`() {
        assertNull(eval(CypherExpr.Or(lit(false), lit(null))))
    }

    @Test
    fun `OR - false OR false`() {
        assertEquals(false, eval(CypherExpr.Or(lit(false), lit(false))))
    }

    @Test
    fun `OR - null OR null`() {
        assertNull(eval(CypherExpr.Or(lit(null), lit(null))))
    }

    @Test
    fun `NOT - true`() {
        assertEquals(false, eval(CypherExpr.Not(lit(true))))
    }

    @Test
    fun `NOT - false`() {
        assertEquals(true, eval(CypherExpr.Not(lit(false))))
    }

    @Test
    fun `NOT - null`() {
        assertNull(eval(CypherExpr.Not(lit(null))))
    }

    @Test
    fun `XOR - true XOR false`() {
        assertEquals(true, eval(CypherExpr.Xor(lit(true), lit(false))))
    }

    @Test
    fun `XOR - true XOR true`() {
        assertEquals(false, eval(CypherExpr.Xor(lit(true), lit(true))))
    }

    @Test
    fun `XOR - null XOR true`() {
        assertNull(eval(CypherExpr.Xor(lit(null), lit(true))))
    }
}
