package io.johnsonlee.graphite.cypher

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test

class MembershipExpressionTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `STARTS WITH`() {
        assertEquals(true, eval(CypherExpr.StringOp("STARTS WITH", lit("hello world"), lit("hello"))))
        assertEquals(false, eval(CypherExpr.StringOp("STARTS WITH", lit("hello world"), lit("world"))))
    }

    @Test
    fun `ENDS WITH`() {
        assertEquals(true, eval(CypherExpr.StringOp("ENDS WITH", lit("hello world"), lit("world"))))
        assertEquals(false, eval(CypherExpr.StringOp("ENDS WITH", lit("hello world"), lit("hello"))))
    }

    @Test
    fun `CONTAINS`() {
        assertEquals(true, eval(CypherExpr.StringOp("CONTAINS", lit("hello world"), lit("lo wo"))))
        assertEquals(false, eval(CypherExpr.StringOp("CONTAINS", lit("hello world"), lit("xyz"))))
    }

    @Test
    fun `string operator with null returns null`() {
        assertNull(eval(CypherExpr.StringOp("STARTS WITH", lit(null), lit("hello"))))
        assertNull(eval(CypherExpr.StringOp("STARTS WITH", lit("hello"), lit(null))))
    }

    @Test
    fun `IN - element present`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(true, eval(CypherExpr.ListOp("IN", lit(2), list)))
    }

    @Test
    fun `IN - element absent`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(false, eval(CypherExpr.ListOp("IN", lit(5), list)))
    }

    @Test
    fun `IN - null list returns null`() {
        assertNull(eval(CypherExpr.ListOp("IN", lit(1), lit(null))))
    }

    @Test
    fun `IN - null comparison propagates when no element matches`() {
        assertNull(eval(CypherExpr.ListOp("IN", lit(2), lit(listOf(1, null, 3)))))
        assertNull(eval(CypherExpr.ListOp("IN", lit(null), lit(listOf(1, 2, 3)))))
        assertNull(eval(CypherExpr.ListOp("IN", lit(null), lit(listOf(null)))))
    }

    @Test
    fun `IN - matching element takes precedence over null comparison`() {
        assertEquals(true, eval(CypherExpr.ListOp("IN", lit(2), lit(listOf(1, null, 2)))))
    }

    @Test
    fun `IN - preserves numeric coercion and nested collection nulls`() {
        assertEquals(true, eval(CypherExpr.ListOp("IN", lit(2), lit(listOf(2L)))))
        assertNull(eval(CypherExpr.ListOp("IN", lit(listOf(1, null)), lit(listOf(listOf(1, null))))))
        assertEquals(false, eval(CypherExpr.ListOp("IN", lit(listOf(1, null)), lit(listOf(listOf(2, null))))))
    }

    @Test
    fun `IN - empty list returns false even for null`() {
        assertEquals(false, eval(CypherExpr.ListOp("IN", lit(null), lit(emptyList<Any?>()))))
    }

    @Test
    fun `equality propagates nulls nested in collections`() {
        assertNull(eval(CypherExpr.Comparison("=", lit(listOf(1, null)), lit(listOf(1, null)))))
        assertEquals(false, eval(CypherExpr.Comparison("=", lit(listOf(1, null)), lit(listOf(2, null)))))
        assertEquals(true, eval(CypherExpr.Comparison("=", lit(mapOf("x" to 1)), lit(mapOf("x" to 1)))))
        assertNull(eval(CypherExpr.Comparison("=", lit(mapOf("x" to null)), lit(mapOf("x" to null)))))
    }

    @Test
    fun `tracked IN observes cancellation during membership scan`() {
        val checks = AtomicInteger()
        val tracked = ExpressionEvaluator { if (checks.incrementAndGet() == 2) throw CollectionOperationCancelled() }

        assertFailsWith<CollectionOperationCancelled> {
            tracked.evaluate(CypherExpr.ListOp("IN", lit(-1), lit((0 until 10_000).toList())), emptyMap())
        }
        assertEquals(2, checks.get())
    }

    @Test
    fun `tracked IN traverses a sequential input once`() {
        val values = EvaluatorTrackingSequentialList(listOf(1, 2, 3))
        val tracked = ExpressionEvaluator {}

        assertFalse(tracked.evaluate(CypherExpr.ListOp("IN", lit(4), lit(values)), emptyMap()) as Boolean)
        assertEquals(1, values.listIteratorCalls)
    }
}
