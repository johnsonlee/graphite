package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlannerFallbackTest {
    private val first = StringConstant(NodeId(1), "first")
    private val second = StringConstant(NodeId(2), "second")
    private val graph = DefaultGraph.Builder().addNode(first).addNode(second)
        .addEdge(DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN)).build()
    private val executor = CypherExecutor(graph)

    @Test
    fun `unknown call edge property cannot satisfy an inline constraint`() {
        val calls = DefaultGraph.Builder().addNode(first).addNode(second).addEdge(CallEdge(first.id, second.id, true, false)).build()
        val query = CypherExecutor(calls)
        assertEquals(listOf(mapOf("value" to "second")), query.execute(
            "MATCH ()-[r {virtual: true}]->(n) RETURN n.value AS value").rows)
        assertEquals(emptyList(), query.execute("MATCH ()-[r {unsupported: true}]->(n) RETURN n.value AS value").rows)
    }

    @Test
    fun `unnamed source falls back while retaining relationship direction and filtering`() {
        assertEquals(listOf(mapOf("value" to "second")), executor.execute("MATCH ()-->(n) RETURN n.value AS value LIMIT 3").rows)
        assertEquals(listOf(mapOf("value" to "second")),
            executor.execute("MATCH ()-->(n) WHERE n.value CONTAINS 'sec' RETURN n.value AS value LIMIT 3").rows)
    }

    @Test
    fun `non histogram unwind preserves group values and counts`() {
        assertEquals(listOf(mapOf("label" to "literal", "count" to 2L)),
            executor.execute("MATCH (n) UNWIND ['literal'] AS label RETURN label, count(*) AS count ORDER BY count LIMIT 10").rows)
        assertEquals(listOf(mapOf("label" to "StringConstant", "value" to "first"),
            mapOf("label" to "Constant", "value" to "first"),
            mapOf("label" to "StringConstant", "value" to "second"), mapOf("label" to "Constant", "value" to "second")),
            executor.execute("MATCH (n) UNWIND labels(n) AS label RETURN label, n.value AS value ORDER BY value LIMIT 10").rows)
    }

    @Test
    fun `projection rejects aggregation nested inside unsupported scalar expressions`() {
        for (query in listOf("RETURN toString(count(*))", "RETURN count(*) + 1", "MATCH (m:Method) RETURN count(*) + 1")) {
            assertFailsWith<CypherAggregationException>(query) { executor.execute(query) }
        }
    }

    @Test
    fun `graph routing keeps residual conjunction and refuses dynamic graph names`() {
        val context = PipelineExecutionContext(graph, false)
        val planner = GraphSourcePlanner(listOf(CypherGraph("a", graph)), context)
        fun expression(text: String) = (CypherDslAdapter.parse("RETURN $text").single() as CypherClause.Return).items.single().expression
        val route = planner.conjunctiveGraphIdRoute(
            expression("(n.graphId = 'a' AND n.value = 'first') AND n.type = 'StringConstant'"), "n")
        assertEquals(setOf("a"), route.graphIds)
        assertEquals(expression("n.value = 'first' AND n.type = 'StringConstant'"), route.residual)
        assertEquals(false, route.conflicting)
        assertNull(planner.graphIdValue(expression("toString(42)")))
        assertNull(planner.conjunctiveGraphIdRoute(expression("n.graphId IN other.ids"), "n").graphIds)
    }
}
