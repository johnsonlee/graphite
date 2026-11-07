package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PipelineAggregationExpressionsTest : QueryPipelineTestFixture() {

    @Test
    fun `distinct aggregation`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(1), lit(2), lit(2), lit(3))), "x"),
                CypherClause.Return(listOf(returnItem(CypherExpr.FunctionCall("count", listOf(variable("x")), distinct = true), "cnt"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(3L, result.rows[0]["cnt"])
    }

    @Test
    fun `aggregation with Distinct wrapper`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))), "x"),
                CypherClause.Return(
                    listOf(returnItem(CypherExpr.Distinct(CypherExpr.FunctionCall("sum", listOf(variable("x")))), "total"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `non-aggregation function call in aggregation context`() {
        // When a non-aggregation FunctionCall is detected in aggregation items
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2))), "x"),
                CypherClause.Return(
                    listOf(
                        returnItem(CypherExpr.CountStar, "cnt"), returnItem(CypherExpr.FunctionCall("toInteger", listOf(lit(42))), "val"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
        assertEquals(42L, result.rows[0]["val"])
    }

    @Test
    fun `sum and count share one aggregate group`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2))), "x"),
                CypherClause.Return(
                    listOf(
                        returnItem(CypherExpr.FunctionCall("sum", listOf(variable("x"))), "total"),
                        returnItem(CypherExpr.CountStar, "cnt"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(3.0, result.rows[0]["total"])
        assertEquals(2L, result.rows[0]["cnt"])
    }

    @Test
    fun `grouped minimum retains the concrete grouping key`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(
                    listOf(
                        returnItem(prop(variable("n"), "type"), "nodeType"),
                        returnItem(CypherExpr.FunctionCall("min", listOf(prop(variable("n"), "value"))), "minVal"))))
        val result = pipeline.execute(clauses)
        assertEquals(listOf(mapOf("nodeType" to "IntConstant", "minVal" to 7)), result.rows)
    }

    @Test
    fun `unwind non-list value skips row`() {
        val clauses = listOf(CypherClause.Unwind(lit(42), "x"), CypherClause.Return(listOf(returnItem(variable("x"), "x"))))
        val result = pipeline.execute(clauses)
        // 42 is not a list, so no rows produced
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `order by with equal values falls through to next sort item`() {
        val clauses =
            listOf(
                CypherClause.Unwind(
                    CypherExpr.ListLiteral(
                        listOf(
                            CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(2))),
                            CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(1))))),
                    "r"),
                CypherClause.Return(
                    listOf(
                        returnItem(CypherExpr.Property(variable("r"), "a"), "a"),
                        returnItem(CypherExpr.Property(variable("r"), "b"), "b"))),
                CypherClause.OrderBy(listOf(SortItem(variable("a"), ascending = true), SortItem(variable("b"), ascending = true))))
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(1, result.rows[0]["b"])
        assertEquals(2, result.rows[1]["b"])
    }
}
