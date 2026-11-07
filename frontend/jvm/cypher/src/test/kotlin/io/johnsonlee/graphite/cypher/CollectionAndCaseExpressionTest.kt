package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class CollectionAndCaseExpressionTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `IS NULL`() {
        assertEquals(true, eval(CypherExpr.IsNull(lit(null))))
        assertEquals(false, eval(CypherExpr.IsNull(lit(42))))
    }

    @Test
    fun `IS NOT NULL`() {
        assertEquals(true, eval(CypherExpr.IsNotNull(lit(42))))
        assertEquals(false, eval(CypherExpr.IsNotNull(lit(null))))
    }

    @Test
    fun `simple CASE expression`() {
        val expr =
            CypherExpr.CaseExpr(
                test = variable("x"), whenClauses = listOf(lit(1) to lit("one"), lit(2) to lit("two")), elseExpr = lit("other"))
        assertEquals("one", eval(expr, mapOf("x" to 1)))
        assertEquals("two", eval(expr, mapOf("x" to 2)))
        assertEquals("other", eval(expr, mapOf("x" to 99)))
    }

    @Test
    fun `generic CASE expression`() {
        val expr =
            CypherExpr.CaseExpr(
                test = null,
                whenClauses = listOf(CypherExpr.Comparison(">", variable("x"), lit(0)) to lit("positive")),
                elseExpr = lit("non-positive"))
        assertEquals("positive", eval(expr, mapOf("x" to 5)))
        assertEquals("non-positive", eval(expr, mapOf("x" to -1)))
    }

    @Test
    fun `CASE with no matching when and no else returns null`() {
        val expr = CypherExpr.CaseExpr(test = variable("x"), whenClauses = listOf(lit(1) to lit("one")), elseExpr = null)
        assertNull(eval(expr, mapOf("x" to 99)))
    }

    @Test
    fun `list literal`() {
        val expr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(listOf(1, 2, 3), eval(expr))
    }

    @Test
    fun `empty list literal`() {
        val expr = CypherExpr.ListLiteral(emptyList())
        assertEquals(emptyList<Any?>(), eval(expr))
    }

    @Test
    fun `map literal`() {
        val expr = CypherExpr.MapLiteral(mapOf("name" to lit("test"), "value" to lit(42)))
        assertEquals(mapOf("name" to "test", "value" to 42), eval(expr))
    }

    @Test
    fun `list comprehension with filter and map`() {
        // [x IN [1,2,3,4] WHERE x > 2 | x * 2]
        val listExpr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4)))
        val predicate = CypherExpr.Comparison(">", variable("x"), lit(2))
        val mapExpr = CypherExpr.BinaryOp("*", variable("x"), lit(2))
        val expr = CypherExpr.ListComprehension("x", listExpr, predicate, mapExpr)
        assertEquals(listOf(6L, 8L), eval(expr))
    }

    @Test
    fun `list comprehension without filter`() {
        // [x IN [1,2,3] | x * 10]
        val listExpr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        val mapExpr = CypherExpr.BinaryOp("*", variable("x"), lit(10))
        val expr = CypherExpr.ListComprehension("x", listExpr, null, mapExpr)
        assertEquals(listOf(10L, 20L, 30L), eval(expr))
    }

    @Test
    fun `list comprehension without map expression`() {
        // [x IN [1,2,3,4] WHERE x > 2]
        val listExpr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4)))
        val predicate = CypherExpr.Comparison(">", variable("x"), lit(2))
        val expr = CypherExpr.ListComprehension("x", listExpr, predicate, null)
        assertEquals(listOf(3, 4), eval(expr))
    }

    @Test
    fun `list comprehension on null list returns null`() {
        val expr = CypherExpr.ListComprehension("x", lit(null), null, null)
        assertNull(eval(expr))
    }

    @Test
    fun `predicate function ANY`() {
        val listExpr = CypherExpr.ListLiteral(listOf(lit("A"), lit("B")))
        val matching = CypherExpr.Comparison("=", variable("x"), lit("B"))
        val missing = CypherExpr.Comparison("=", variable("x"), lit("C"))

        assertEquals(true, eval(CypherExpr.PredicateFunction("any", "x", listExpr, matching)))
        assertEquals(false, eval(CypherExpr.PredicateFunction("any", "x", listExpr, missing)))
    }

    @Test
    fun `predicate function ALL`() {
        val listExpr = CypherExpr.ListLiteral(listOf(lit("AX"), lit("BX")))
        val matching = CypherExpr.StringOp("CONTAINS", variable("x"), lit("X"))
        val partial = CypherExpr.StringOp("CONTAINS", variable("x"), lit("A"))

        assertEquals(true, eval(CypherExpr.PredicateFunction("all", "x", listExpr, matching)))
        assertEquals(false, eval(CypherExpr.PredicateFunction("all", "x", listExpr, partial)))
    }

    @Test
    fun `predicate function NONE and SINGLE`() {
        val listExpr = CypherExpr.ListLiteral(listOf(lit("A"), lit("B")))
        val isA = CypherExpr.Comparison("=", variable("x"), lit("A"))
        val isC = CypherExpr.Comparison("=", variable("x"), lit("C"))

        assertEquals(true, eval(CypherExpr.PredicateFunction("none", "x", listExpr, isC)))
        assertEquals(false, eval(CypherExpr.PredicateFunction("none", "x", listExpr, isA)))
        assertEquals(true, eval(CypherExpr.PredicateFunction("single", "x", listExpr, isA)))
        assertEquals(false, eval(CypherExpr.PredicateFunction("single", "x", listExpr, CypherExpr.Literal(true))))
    }

    @Test
    fun `predicate function on null list returns null`() {
        val expr = CypherExpr.PredicateFunction("any", "x", lit(null), CypherExpr.Literal(true))

        assertNull(eval(expr))
    }

    @Test
    fun `subscript positive index`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(1, eval(CypherExpr.Subscript(list, lit(0))))
        assertEquals(3, eval(CypherExpr.Subscript(list, lit(2))))
    }

    @Test
    fun `subscript negative index`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(3, eval(CypherExpr.Subscript(list, lit(-1))))
        assertEquals(1, eval(CypherExpr.Subscript(list, lit(-3))))
    }

    @Test
    fun `subscript out of bounds returns null`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        assertNull(eval(CypherExpr.Subscript(list, lit(5))))
    }

    @Test
    fun `subscript on string`() {
        assertEquals("h", eval(CypherExpr.Subscript(lit("hello"), lit(0))))
        assertEquals("o", eval(CypherExpr.Subscript(lit("hello"), lit(-1))))
    }

    @Test
    fun `slice list`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4)))
        assertEquals(listOf(2, 3), eval(CypherExpr.Slice(list, lit(1), lit(3))))
    }

    @Test
    fun `slice list with no from`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(listOf(1, 2), eval(CypherExpr.Slice(list, null, lit(2))))
    }

    @Test
    fun `slice list with no to`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(listOf(2, 3), eval(CypherExpr.Slice(list, lit(1), null)))
    }

    @Test
    fun `slice string`() {
        assertEquals("ell", eval(CypherExpr.Slice(lit("hello"), lit(1), lit(4))))
    }
}
