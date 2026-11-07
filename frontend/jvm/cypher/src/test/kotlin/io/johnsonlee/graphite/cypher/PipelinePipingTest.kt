package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class PipelinePipingTest : QueryPipelineTestFixture() {

    @Test
    fun `with pipeline`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.With(listOf(returnItem(prop(variable("n"), "value"), "val"))),
                CypherClause.Where(CypherExpr.Comparison(">", variable("val"), lit(10))),
                CypherClause.Return(listOf(returnItem(variable("val"), "result"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["result"])
    }

    @Test
    fun `with distinct`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.With(listOf(returnItem(lit("constant"), "label")), distinct = true),
                CypherClause.Return(listOf(returnItem(variable("label"), "label"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals("constant", result.rows[0]["label"])
    }

    @Test
    fun `with inline where`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.With(
                    items = listOf(returnItem(prop(variable("n"), "value"), "val")),
                    where = CypherExpr.Comparison("=", variable("val"), lit(42))),
                CypherClause.Return(listOf(returnItem(variable("val"), "val"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["val"])
    }

    @Test
    fun `unwind list literal`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    @Test
    fun `unwind then filter`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(10), lit(20), lit(30))), "x"),
                CypherClause.Where(CypherExpr.Comparison(">", variable("x"), lit(15))),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))))
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(20, 30), result.rows.map { it["x"] })
    }

    @Test
    fun `union distinct`() {
        // Union is handled at a higher level; simulate two branch executions
        val clauses1 =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))))
        val clauses2 =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(2), lit(3), lit(4))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))))
        val result1 = pipeline.execute(clauses1)
        val result2 = pipeline.execute(clauses2)
        // UNION (distinct)
        val combined = (result1.rows + result2.rows).distinct()
        assertEquals(4, combined.size)
    }

    @Test
    fun `union all`() {
        val clauses1 =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))))
        val clauses2 =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(2), lit(3))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))))
        val result1 = pipeline.execute(clauses1)
        val result2 = pipeline.execute(clauses2)
        // UNION ALL (preserves duplicates)
        val combined = result1.rows + result2.rows
        assertEquals(4, combined.size)
    }

    @Test
    fun `write clauses throw NotImplementedError`() {
        assertFailsWith<NotImplementedError> { pipeline.execute(listOf(CypherClause.Create(listOf(pattern(nodePattern("m")))))) }
        assertFailsWith<NotImplementedError> { pipeline.execute(listOf(CypherClause.Delete(listOf(variable("m"))))) }
        assertFailsWith<NotImplementedError> { pipeline.execute(listOf(CypherClause.Set(emptyList()))) }
        assertFailsWith<NotImplementedError> { pipeline.execute(listOf(CypherClause.Remove(emptyList()))) }
    }

    @Test
    fun `union clause inline throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { pipeline.execute(listOf(CypherClause.Union(all = false))) }
    }

    @Test
    fun `empty match pattern returns existing bindings`() {
        val clauses = listOf(CypherClause.Match(listOf(CypherPattern(emptyList()))), CypherClause.Return(listOf(returnItem(lit(1), "one"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows[0]["one"])
    }

    @Test
    fun `columns inferred from row keys`() {
        val clauses = listOf(CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1))), "x"))
        val result = pipeline.execute(clauses)
        assertEquals(listOf("x"), result.columns)
    }
}
