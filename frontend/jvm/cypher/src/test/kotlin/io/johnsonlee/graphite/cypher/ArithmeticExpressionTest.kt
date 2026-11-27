package io.johnsonlee.graphite.cypher

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ArithmeticExpressionTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `addition of integers`() {
        val expr = CypherExpr.BinaryOp("+", lit(3), lit(4))
        assertEquals(7L, eval(expr))
    }

    @Test
    fun `subtraction of integers`() {
        val expr = CypherExpr.BinaryOp("-", lit(10), lit(3))
        assertEquals(7L, eval(expr))
    }

    @Test
    fun `multiplication of integers`() {
        val expr = CypherExpr.BinaryOp("*", lit(6), lit(7))
        assertEquals(42L, eval(expr))
    }

    @Test
    fun `division of integers`() {
        val expr = CypherExpr.BinaryOp("/", lit(10), lit(2))
        assertEquals(5L, eval(expr))
    }

    @Test
    fun `modulo of integers`() {
        val expr = CypherExpr.BinaryOp("%", lit(10), lit(3))
        assertEquals(1L, eval(expr))
    }

    @Test
    fun `power operator`() {
        val expr = CypherExpr.BinaryOp("^", lit(2), lit(3))
        assertEquals(8.0, eval(expr), "exponentiation is always a float in openCypher")
    }

    @Test
    fun `addition with doubles`() {
        val expr = CypherExpr.BinaryOp("+", lit(1.5), lit(2.5))
        assertEquals(4.0, eval(expr))
    }

    @Test
    fun `arithmetic with null operand returns null`() {
        val expr = CypherExpr.BinaryOp("+", lit(1), lit(null))
        assertNull(eval(expr))
    }

    @Test
    fun `string concatenation`() {
        val expr = CypherExpr.BinaryOp("+", CypherExpr.BinaryOp("+", lit("hello"), lit(" ")), lit("world"))
        assertEquals("hello world", eval(expr))
    }

    @Test
    fun `string plus number`() {
        val expr = CypherExpr.BinaryOp("+", lit("value="), lit(42))
        assertEquals("value=42", eval(expr))
    }

    @Test
    fun `list concatenation`() {
        val left = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        val right = CypherExpr.ListLiteral(listOf(lit(3), lit(4)))
        val expr = CypherExpr.BinaryOp("+", left, right)
        assertEquals(listOf(1, 2, 3, 4), eval(expr))
    }

    @Test
    fun `tracked list concatenation observes cancellation during copy`() {
        val values = (0 until 10_000).toList()
        val checks = AtomicInteger()
        val tracked = ExpressionEvaluator { if (checks.incrementAndGet() == 2) throw CollectionOperationCancelled() }

        assertFailsWith<CollectionOperationCancelled> { tracked.evaluate(CypherExpr.BinaryOp("+", lit(values), lit(values)), emptyMap()) }
        assertEquals(2, checks.get())
    }

    @Test
    fun `tracked list concatenation traverses sequential inputs once`() {
        val left = EvaluatorTrackingSequentialList(listOf(1, 2))
        val right = EvaluatorTrackingSequentialList(listOf(3, 4))
        val tracked = ExpressionEvaluator {}

        assertEquals(listOf(1, 2, 3, 4), tracked.evaluate(CypherExpr.BinaryOp("+", lit(left), lit(right)), emptyMap()))
        assertEquals(1, left.listIteratorCalls)
        assertEquals(1, right.listIteratorCalls)
    }

    @Test
    fun `tracked list operations preserve completed results`() {
        var checks = 0
        val tracked = ExpressionEvaluator { checks++ }

        assertEquals(listOf(1, 2, 3, 4), tracked.evaluate(CypherExpr.BinaryOp("+", lit(listOf(1, 2)), lit(listOf(3, 4))), emptyMap()))
        assertEquals(listOf(1, 2, 3), tracked.evaluate(CypherExpr.BinaryOp("+", lit(listOf(1, 2)), lit(3)), emptyMap()))
        assertEquals(listOf(1, 2, 3), tracked.evaluate(CypherExpr.BinaryOp("+", lit(1), lit(listOf(2, 3))), emptyMap()))
        assertEquals(true, tracked.evaluate(CypherExpr.ListOp("IN", lit(2), lit(listOf(1, 2, 3))), emptyMap()))
        assertEquals(false, tracked.evaluate(CypherExpr.ListOp("IN", lit(4), lit(listOf(1, 2, 3))), emptyMap()))
        assertTrue(checks > 0)
    }

    @Test
    fun `unary minus on int`() {
        val expr = CypherExpr.UnaryOp("-", lit(42))
        assertEquals(-42, eval(expr))
    }

    @Test
    fun `unary minus on double`() {
        val expr = CypherExpr.UnaryOp("-", lit(3.14))
        assertEquals(-3.14, eval(expr))
    }

    @Test
    fun `unary minus on null returns null`() {
        val expr = CypherExpr.UnaryOp("-", lit(null))
        assertNull(eval(expr))
    }

    @Test
    fun `unary plus passes through`() {
        val expr = CypherExpr.UnaryOp("+", lit(42))
        assertEquals(42, eval(expr))
    }

    @Test
    fun `parameter resolves from bindings`() {
        assertEquals("val", eval(CypherExpr.Parameter("p"), mapOf("p" to "val")))
    }

    @Test
    fun `distinct expression passes through`() {
        assertEquals(42, eval(CypherExpr.Distinct(lit(42))))
    }

    @Test
    fun `string comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<", lit("abc"), lit("def"))))
        assertEquals(true, eval(CypherExpr.Comparison("=", lit("abc"), lit("abc"))))
    }

    @Test
    fun `boolean comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<", lit(false), lit(true))))
    }

    @Test
    fun `unknown binary operator throws`() {
        assertFailsWith<CypherException> { eval(CypherExpr.BinaryOp("??", lit(1), lit(2))) }
    }

    @Test
    fun `unknown unary operator throws`() {
        assertFailsWith<CypherException> { eval(CypherExpr.UnaryOp("~", lit(1))) }
    }

    @Test
    fun `unknown comparison operator throws`() {
        assertFailsWith<CypherException> { eval(CypherExpr.Comparison("!!", lit(1), lit(2))) }
    }

    @Test
    fun `unknown string operator throws`() {
        assertFailsWith<CypherException> { eval(CypherExpr.StringOp("LIKE", lit("a"), lit("b"))) }
    }

    @Test
    fun `unknown list operator throws`() {
        assertFailsWith<CypherException> { eval(CypherExpr.ListOp("ALL", lit(1), lit(listOf(1)))) }
    }

    @Test
    fun `unary minus on long`() {
        val expr = CypherExpr.UnaryOp("-", lit(100L))
        assertEquals(-100L, eval(expr))
    }

    @Test
    fun `unary minus on float`() {
        val expr = CypherExpr.UnaryOp("-", lit(2.5f))
        assertEquals(-2.5f, eval(expr))
    }

    @Test
    fun `list add element to list`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        val expr = CypherExpr.BinaryOp("+", list, lit(3))
        assertEquals(listOf(1, 2, 3), eval(expr))
    }

    @Test
    fun `element add to list`() {
        val list = CypherExpr.ListLiteral(listOf(lit(2), lit(3)))
        val expr = CypherExpr.BinaryOp("+", lit(1), list)
        assertEquals(listOf(1, 2, 3), eval(expr))
    }

    @Test
    fun `unary minus on string falls back to toDouble`() {
        val expr = CypherExpr.UnaryOp("-", lit("5"))
        assertEquals(-5.0, eval(expr))
    }

    @Test
    fun `subscript with non-number index returns null`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        assertNull(eval(CypherExpr.Subscript(list, lit("abc"))))
    }

    @Test
    fun `subscript on non-collection returns null`() {
        assertNull(eval(CypherExpr.Subscript(lit(42), lit(0))))
    }

    @Test
    fun `slice on non-collection returns null`() {
        assertNull(eval(CypherExpr.Slice(lit(42), lit(0), lit(1))))
    }

    @Test
    fun `slice string with no from`() {
        assertEquals("he", eval(CypherExpr.Slice(lit("hello"), null, lit(2))))
    }

    @Test
    fun `slice string with no to`() {
        assertEquals("llo", eval(CypherExpr.Slice(lit("hello"), lit(2), null)))
    }

    @Test
    fun `comparison fallback to toString`() {
        // Two non-numeric non-string non-boolean values compared via toString
        val list1 = CypherExpr.ListLiteral(listOf(lit(1)))
        val list2 = CypherExpr.ListLiteral(listOf(lit(2)))
        // Just ensure it doesn't throw - result is toString comparison
        eval(CypherExpr.Comparison("=", list1, list2))
    }

    @Test
    fun `integer arithmetic that leaves a long is an error, not a wrapped or widened value`() {
        for ((op, left, right) in listOf(
            Triple("+", Long.MAX_VALUE, 1L),
            Triple("-", Long.MIN_VALUE, 1L),
            Triple("*", Long.MAX_VALUE, 2L),
            Triple("/", Long.MIN_VALUE, -1L))) {
            val failure = kotlin.test.assertFailsWith<CypherException> { eval(CypherExpr.BinaryOp(op, lit(left), lit(right))) }
            assertEquals("long overflow", failure.message, "$left $op $right")
        }
        assertEquals(0L, eval(CypherExpr.BinaryOp("%", lit(Long.MIN_VALUE), lit(-1L))))
        for ((op, left, right) in listOf(Triple("%", 1, 0), Triple("%", 1.0, 0.0), Triple("/", 1, 0), Triple("/", 1.0, 0.0))) {
            val failure = assertFailsWith<CypherException> { eval(CypherExpr.BinaryOp(op, lit(left), lit(right))) }
            assertEquals("Division by zero", failure.message, "$left $op $right")
        }
        assertEquals(3L, eval(CypherExpr.BinaryOp("/", lit(7), lit(2))))
        assertEquals(1.5, eval(CypherExpr.BinaryOp("%", lit(7.5), lit(2))))
        assertEquals(3.5, eval(CypherExpr.BinaryOp("/", lit(7), lit(2.0))))
    }

    @Test
    fun `substring bounds fail with the message the Rust engine gives on every JDK`() {
        for ((args, message) in listOf(
            listOf<Any?>("abc", 5L) to "begin 5, end 3, length 3",
            listOf<Any?>("abc", -1L) to "begin -1, end 3, length 3",
            listOf<Any?>("abc", 2L, -1L) to "begin 2, end 1, length 3")) {
            assertEquals(message, assertFailsWith<CypherException> { CypherFunctions.call("substring", args) }.message)
        }
        assertEquals("bc", CypherFunctions.call("substring", listOf("abc", 1L, 100L)))
        assertEquals("", CypherFunctions.call("substring", listOf("abc", 3L)))
    }

    @Test
    fun `a pattern that does not compile fails with the Rust engine's one-line message`() {
        val failure = assertFailsWith<CypherException> { eval(CypherExpr.RegexMatch(lit("x"), lit("["))) }
        assertEquals("Unsupported regex construct in pattern: [", failure.message)
    }
}
