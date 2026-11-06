package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PipelineProjectionTest : QueryPipelineTestFixture() {

    @Test
    fun `return property projection`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "StringConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value")))))
        val result = pipeline.execute(clauses)
        assertEquals(listOf("n.value"), result.columns)
        assertEquals("hello", result.rows[0]["n.value"])
    }

    @Test
    fun `return with alias`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "StringConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "val"))))
        val result = pipeline.execute(clauses)
        assertEquals(listOf("val"), result.columns)
        assertEquals("hello", result.rows[0]["val"])
    }

    @Test
    fun `return distinct`() {
        // Both call sites have the same callee_class prefix "com.example."
        // Match all nodes, project type, distinct
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(lit("int"), "type")), distinct = true))
        val result = pipeline.execute(clauses)
        // Two IntConstants but constant "int" value, distinct => 1
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `return distinct property with limit`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "CallSiteNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "callee_class"), "cls")), distinct = true),
                CypherClause.Limit(lit(1)))
        val result = pipeline.execute(clauses)
        assertEquals(listOf("cls"), result.columns)
        assertEquals(1, result.rows.size)
        assertEquals("com.example.Repository", result.rows[0]["cls"])
    }

    @Test
    fun `return distinct property zero limit returns no rows`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "CallSiteNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "callee_class"), "cls")), distinct = true),
                CypherClause.Limit(lit(0)))
        val result = pipeline.execute(clauses)
        assertEquals(listOf("cls"), result.columns)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `return distinct property with limit supports unlabeled node scans`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "id"), "id")), distinct = true),
                CypherClause.Limit(lit(1)))
        val result = pipeline.execute(clauses)
        assertEquals(listOf("id"), result.columns)
        assertEquals(1, result.rows.size)
        assertEquals(intConst42.value, result.rows[0]["id"])
    }

    @Test
    fun `return count star`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(CypherExpr.CountStar, "cnt"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
    }

    @Test
    fun `return sum aggregation`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(CypherExpr.FunctionCall("sum", listOf(prop(variable("n"), "value"))), "total"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(49.0, result.rows[0]["total"]) // 42 + 7
    }

    @Test
    fun `return avg aggregation`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(CypherExpr.FunctionCall("avg", listOf(prop(variable("n"), "value"))), "average"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(24.5, result.rows[0]["average"]) // (42 + 7) / 2
    }

    @Test
    fun `return collect aggregation`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(CypherExpr.FunctionCall("collect", listOf(prop(variable("n"), "value"))), "values"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val values = result.rows[0]["values"] as List<Any?>
        assertTrue(values.containsAll(listOf(42, 7)))
    }

    @Test
    fun `group by label and count`() {
        // Match IntConstant and CallSiteNode (these types don't have a "type" property
        // that shadows the node type name), so we use id property and group by label
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "type"), "nodeType"), returnItem(CypherExpr.CountStar, "cnt"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        // IntConstant nodes don't have a node-specific "type" property, so it falls
        // back to nodeTypeName which returns "IntConstant"
        assertEquals("IntConstant", result.rows[0]["nodeType"])
        assertEquals(2L, result.rows[0]["cnt"])
    }
}
