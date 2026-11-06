package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PipelineFilterTest : QueryPipelineTestFixture() {

    @Test
    fun `where equality filter`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Where(CypherExpr.Comparison("=", prop(variable("n"), "value"), lit(42))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["value"])
    }

    @Test
    fun `where regex filter`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "CallSiteNode")))),
                CypherClause.Where(CypherExpr.RegexMatch(prop(variable("n"), "callee_name"), lit("sa.*"))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "callee_name"), "name"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["name"])
    }

    @Test
    fun `where AND condition`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Where(
                    CypherExpr.And(
                        CypherExpr.Comparison(">", prop(variable("n"), "value"), lit(0)),
                        CypherExpr.Comparison("<", prop(variable("n"), "value"), lit(10)))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["value"])
    }

    @Test
    fun `where NOT condition`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Where(CypherExpr.Not(CypherExpr.Comparison("=", prop(variable("n"), "value"), lit(42)))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["value"])
    }

    @Test
    fun `filtered node limit counts rows after where`() {
        NodeId.reset()
        val builder = DefaultGraph.Builder()

        val first = NodeId.next()
        builder.addNode(IntConstant(first, 7))

        val second = NodeId.next()
        builder.addNode(IntConstant(second, 42))

        val localPipeline = QueryPipeline(builder.build())
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Where(CypherExpr.Comparison("=", prop(variable("n"), "value"), lit(42))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value"))),
                CypherClause.Limit(lit(1)))
        val result = localPipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["value"])
    }

    @Test
    fun `filtered node zero limit returns no rows`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Where(CypherExpr.Comparison(">", prop(variable("n"), "value"), lit(0))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value"))),
                CypherClause.Limit(lit(0)))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `filtered node limit supports unlabeled node scans`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n")))),
                CypherClause.Where(CypherExpr.Comparison("=", prop(variable("n"), "id"), lit(strConstHello.value))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "id"), "id"))),
                CypherClause.Limit(lit(1)))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(strConstHello.value, result.rows[0]["id"])
    }

    @Test
    fun `filtered distinct limit streams generic predicates`() {
        val result = CypherExecutor(graph).execute("MATCH (n:IntConstant) WHERE n.value > 0 " + "RETURN DISTINCT n.type AS type LIMIT 1")

        assertEquals(listOf("IntConstant"), result.rows.map { it["type"] })
    }

    @Test
    fun `filtered distinct string disjunction stops after the single graph limit`() {
        val result =
            CypherExecutor(graph)
                .execute(
                    "MATCH (n) WHERE " +
                        "(exists(n.caller_class) AND n.caller_class CONTAINS 'Service') OR " +
                        "(exists(n.callee_class) AND n.callee_class CONTAINS 'Service') " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
    }
}
