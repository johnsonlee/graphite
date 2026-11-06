package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import org.junit.Test

class PipelinePaginationCoercionTest : QueryPipelineTestFixture() {

    @Test
    fun `skip with string value defaults to 0`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.Skip(lit("abc")) // non-numeric string -> 0
                )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
    }

    @Test
    fun `skip with null defaults to 0`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.Skip(lit(null)) // null -> 0
                )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `limit with string evaluates to int`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.Limit(lit("2")) // string "2" -> 2
                )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
    }
}
