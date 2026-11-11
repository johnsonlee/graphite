package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class CombinedInlineWhereTest {
    @Test
    fun `inline rejection prevents evaluating an explicit predicate that would fail`() {
        val graph = DefaultGraph.Builder().addNode(StringConstant(NodeId(1), "first")).build()
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE 1 / 0 > 0 RETURN n.value AS value LIMIT 10").toMutableList()
        clauses[0] = (clauses[0] as CypherClause.Match).copy(where = CypherExpr.Literal(false))
        assertEquals(emptyList(), QueryPipeline(graph).execute(clauses).rows)
    }

    @Test
    fun `inline and explicit predicates both apply before bounded ordering and aggregation`() {
        val graph = DefaultGraph.Builder().addNode(StringConstant(NodeId(1), "first"))
            .addNode(StringConstant(NodeId(2), "second")).build()
        for (suffix in listOf("RETURN n.value AS value LIMIT 10", "RETURN n.value AS value ORDER BY value LIMIT 10",
            "RETURN count(*) AS count")) {
            val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value CONTAINS 's' $suffix").toMutableList()
            clauses[0] = (clauses[0] as CypherClause.Match).copy(where = CypherExpr.Comparison("=",
                CypherExpr.Property(CypherExpr.Variable("n"), "value"), CypherExpr.Literal("second")))
            val expected = if (suffix.contains("count")) listOf(mapOf("count" to 1L)) else listOf(mapOf("value" to "second"))
            assertEquals(expected, QueryPipeline(graph).execute(clauses).rows, suffix)
        }
    }
}
