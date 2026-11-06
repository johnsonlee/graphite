package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import org.junit.Test

class CypherExpressionRenderingTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `toCypherString - Literal null`() {
        assertEquals("null", CypherExpr.Literal(null).toCypherString())
    }

    @Test
    fun `toCypherString - Literal string`() {
        assertEquals("'hello'", CypherExpr.Literal("hello").toCypherString())
    }

    @Test
    fun `toCypherString - Literal string with quote`() {
        assertEquals("'it\\'s'", CypherExpr.Literal("it's").toCypherString())
    }

    @Test
    fun `toCypherString - Literal boolean`() {
        assertEquals("true", CypherExpr.Literal(true).toCypherString())
        assertEquals("false", CypherExpr.Literal(false).toCypherString())
    }

    @Test
    fun `toCypherString - Literal number`() {
        assertEquals("42", CypherExpr.Literal(42).toCypherString())
        assertEquals("3.14", CypherExpr.Literal(3.14).toCypherString())
    }

    @Test
    fun `toCypherString - Variable`() {
        assertEquals("n", CypherExpr.Variable("n").toCypherString())
    }

    @Test
    fun `toCypherString - Property`() {
        assertEquals("n.value", CypherExpr.Property(CypherExpr.Variable("n"), "value").toCypherString())
    }

    @Test
    fun `toCypherString - Parameter`() {
        assertEquals("\$p", CypherExpr.Parameter("p").toCypherString())
    }

    @Test
    fun `toCypherString - FunctionCall`() {
        assertEquals("toLower('A')", CypherExpr.FunctionCall("toLower", listOf(CypherExpr.Literal("A"))).toCypherString())
    }

    @Test
    fun `toCypherString - FunctionCall with DISTINCT`() {
        assertEquals(
            "count(DISTINCT n)", CypherExpr.FunctionCall("count", listOf(CypherExpr.Variable("n")), distinct = true).toCypherString())
    }

    @Test
    fun `toCypherString - BinaryOp`() {
        assertEquals("1 + 2", CypherExpr.BinaryOp("+", CypherExpr.Literal(1), CypherExpr.Literal(2)).toCypherString())
    }

    @Test
    fun `toCypherString - UnaryOp`() {
        assertEquals("-x", CypherExpr.UnaryOp("-", CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - Comparison`() {
        assertEquals("x = 1", CypherExpr.Comparison("=", CypherExpr.Variable("x"), CypherExpr.Literal(1)).toCypherString())
    }

    @Test
    fun `toCypherString - StringOp`() {
        assertEquals(
            "x STARTS WITH 'a'", CypherExpr.StringOp("STARTS WITH", CypherExpr.Variable("x"), CypherExpr.Literal("a")).toCypherString())
    }

    @Test
    fun `toCypherString - ListOp`() {
        assertEquals(
            "x IN [1, 2]",
            CypherExpr.ListOp("IN", CypherExpr.Variable("x"), CypherExpr.ListLiteral(listOf(CypherExpr.Literal(1), CypherExpr.Literal(2))))
                .toCypherString())
    }

    @Test
    fun `toCypherString - RegexMatch`() {
        assertEquals("x =~ 'abc'", CypherExpr.RegexMatch(CypherExpr.Variable("x"), CypherExpr.Literal("abc")).toCypherString())
    }

    @Test
    fun `toCypherString - IsNull`() {
        assertEquals("x IS NULL", CypherExpr.IsNull(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - IsNotNull`() {
        assertEquals("x IS NOT NULL", CypherExpr.IsNotNull(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - Not`() {
        assertEquals("NOT x", CypherExpr.Not(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - And`() {
        assertEquals("a AND b", CypherExpr.And(CypherExpr.Variable("a"), CypherExpr.Variable("b")).toCypherString())
    }

    @Test
    fun `toCypherString - Or`() {
        assertEquals("a OR b", CypherExpr.Or(CypherExpr.Variable("a"), CypherExpr.Variable("b")).toCypherString())
    }

    @Test
    fun `toCypherString - Xor`() {
        assertEquals("a XOR b", CypherExpr.Xor(CypherExpr.Variable("a"), CypherExpr.Variable("b")).toCypherString())
    }

    @Test
    fun `toCypherString - CaseExpr simple`() {
        val expr =
            CypherExpr.CaseExpr(
                test = CypherExpr.Variable("x"),
                whenClauses = listOf(CypherExpr.Literal(1) to CypherExpr.Literal("one")),
                elseExpr = CypherExpr.Literal("other"))
        assertEquals("CASE x WHEN 1 THEN 'one' ELSE 'other' END", expr.toCypherString())
    }

    @Test
    fun `toCypherString - CaseExpr generic`() {
        val expr =
            CypherExpr.CaseExpr(
                test = null,
                whenClauses =
                    listOf(CypherExpr.Comparison(">", CypherExpr.Variable("x"), CypherExpr.Literal(0)) to CypherExpr.Literal("positive")),
                elseExpr = null)
        assertEquals("CASE WHEN x > 0 THEN 'positive' END", expr.toCypherString())
    }

    @Test
    fun `toCypherString - ListLiteral`() {
        assertEquals(
            "[1, 2, 3]",
            CypherExpr.ListLiteral(listOf(CypherExpr.Literal(1), CypherExpr.Literal(2), CypherExpr.Literal(3))).toCypherString())
    }

    @Test
    fun `toCypherString - MapLiteral`() {
        val expr = CypherExpr.MapLiteral(mapOf("name" to CypherExpr.Literal("test")))
        assertEquals("{name: 'test'}", expr.toCypherString())
    }

    @Test
    fun `toCypherString - ListComprehension`() {
        val expr =
            CypherExpr.ListComprehension(
                "x",
                CypherExpr.Variable("list"),
                CypherExpr.Comparison(">", CypherExpr.Variable("x"), CypherExpr.Literal(0)),
                CypherExpr.BinaryOp("*", CypherExpr.Variable("x"), CypherExpr.Literal(2)))
        assertEquals("[x IN list WHERE x > 0 | x * 2]", expr.toCypherString())
    }

    @Test
    fun `toCypherString - ListComprehension without filter or map`() {
        val expr = CypherExpr.ListComprehension("x", CypherExpr.Variable("list"), null, null)
        assertEquals("[x IN list]", expr.toCypherString())
    }

    @Test
    fun `toCypherString - PredicateFunction`() {
        val expr =
            CypherExpr.PredicateFunction(
                "any", "x", CypherExpr.Variable("list"), CypherExpr.Comparison("=", CypherExpr.Variable("x"), CypherExpr.Literal("A")))
        assertEquals("any(x IN list WHERE x = 'A')", expr.toCypherString())
    }

    @Test
    fun `toCypherString - Subscript`() {
        assertEquals("list[0]", CypherExpr.Subscript(CypherExpr.Variable("list"), CypherExpr.Literal(0)).toCypherString())
    }

    @Test
    fun `toCypherString - Slice`() {
        assertEquals(
            "list[1..3]", CypherExpr.Slice(CypherExpr.Variable("list"), CypherExpr.Literal(1), CypherExpr.Literal(3)).toCypherString())
    }

    @Test
    fun `toCypherString - Slice no from`() {
        assertEquals("list[..3]", CypherExpr.Slice(CypherExpr.Variable("list"), null, CypherExpr.Literal(3)).toCypherString())
    }

    @Test
    fun `toCypherString - Slice no to`() {
        assertEquals("list[1..]", CypherExpr.Slice(CypherExpr.Variable("list"), CypherExpr.Literal(1), null).toCypherString())
    }

    @Test
    fun `toCypherString - Distinct`() {
        assertEquals("DISTINCT x", CypherExpr.Distinct(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - CountStar`() {
        assertEquals("count(*)", CypherExpr.CountStar.toCypherString())
    }
}
