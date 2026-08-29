package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CypherReviewRegressionTest {

    @Before
    fun resetNodeIds() {
        NodeId.reset()
    }

    @Test
    fun `numeric equality is exact across operators membership and simple case`() {
        val executor = CypherExecutor(DefaultGraph.Builder().build())

        assertEquals(true, executor.singleValue("RETURN 1 = 1.0 AS value"))
        assertEquals(false, executor.singleValue("RETURN 9007199254740993 = 9007199254740992 AS value"))
        assertEquals(false, executor.singleValue("RETURN 9007199254740993 = 9007199254740992.0 AS value"))
        assertEquals(false, executor.singleValue("RETURN 9007199254740993 IN [9007199254740992.0] AS value"))
        assertEquals(false, executor.singleValue("RETURN [9007199254740993] IN [[9007199254740992]] AS value"))
        assertEquals(
            false,
            executor.singleValue(
                "RETURN {value: 9007199254740993} IN [{value: 9007199254740992}] AS value"
            )
        )
        assertEquals(
            "same",
            executor.singleValue("RETURN CASE 1 WHEN 1.0 THEN \"same\" ELSE \"different\" END AS value")
        )
        assertEquals(
            "different",
            executor.singleValue("RETURN CASE null WHEN true THEN \"same\" ELSE \"different\" END AS value")
        )
    }

    @Test
    fun `range membership rejects lossy and out of range conversions`() {
        val executor = CypherExecutor(DefaultGraph.Builder().build())

        assertEquals(true, executor.singleValue("RETURN 2.0 IN range(1, 3) AS value"))
        assertEquals(
            false,
            executor.singleValue(
                "RETURN 9223372036854775808.0 IN range(9223372036854775807, 9223372036854775807) AS value"
            )
        )
    }

    @Test
    fun `distinct grouping aggregation and union use recursive Cypher equality`() {
        val executor = CypherExecutor(DefaultGraph.Builder().build())

        val distinct = executor.execute("UNWIND [1, 1.0] AS x RETURN DISTINCT x AS x")
        assertEquals(1, distinct.rows.size)
        assertEquals(1L, (distinct.rows.single().getValue("x") as Number).toLong())
        assertEquals(1L, executor.singleValue("UNWIND [1, 1.0] AS x RETURN count(DISTINCT x) AS value"))

        val grouped = executor.execute("UNWIND [1, 1.0] AS x RETURN x AS x, count(*) AS count")
        assertEquals(1, grouped.rows.size)
        assertEquals(2L, grouped.rows.single()["count"])

        assertEquals(1, executor.execute("UNWIND [[1], [1.0]] AS x RETURN DISTINCT x AS x").rows.size)
        assertEquals(
            1,
            executor.execute("UNWIND [{value: 1}, {value: 1.0}] AS x RETURN DISTINCT x AS x").rows.size
        )
        assertEquals(
            2,
            executor.execute(
                "UNWIND [9007199254740993, 9007199254740992.0] AS x RETURN DISTINCT x AS x"
            ).rows.size
        )
        assertEquals(1, executor.execute("RETURN 1 AS x UNION RETURN 1.0 AS x").rows.size)
    }

    @Test
    fun `one MATCH clause never reuses a relationship across segments or comma paths`() {
        val executor = twoNodeExecutor()

        assertTrue(
            executor.execute(
                "MATCH (a:IntConstant {value: 1})-[r:DATAFLOW]-(b)-[r:DATAFLOW]-(c) " +
                    "RETURN c.value AS value"
            ).rows.isEmpty()
        )
        assertTrue(
            executor.execute(
                "MATCH (a:IntConstant {value: 1})-[r:DATAFLOW]-(b), " +
                    "(b)-[s:DATAFLOW]-(c) RETURN c.value AS value"
            ).rows.isEmpty()
        )
        assertTrue(
            executor.execute(
                "MATCH (a:IntConstant {value: 1})-[rs:DATAFLOW*1]-(b), " +
                    "(b)-[s:DATAFLOW]-(c) RETURN c.value AS value"
            ).rows.isEmpty()
        )

        val separateMatches = executor.execute(
            "MATCH (a:IntConstant {value: 1})-[r:DATAFLOW]-(b) " +
                "MATCH (b)-[s:DATAFLOW]-(c) RETURN c.value AS value"
        )
        assertEquals(listOf(mapOf("value" to 1)), separateMatches.rows)
    }

    @Test
    fun `named path length supports unqualified zero and nonzero paths`() {
        val executor = twoNodeExecutor()

        val zeroHop = executor.execute(
            "MATCH p=(a:IntConstant {value: 1})-[:DATAFLOW*0]->(a) " +
                "RETURN length(p) AS length, nodes(p) AS nodes, relationships(p) AS relationships"
        )
        assertEquals(0, zeroHop.rows.single()["length"])
        assertEquals(1, (zeroHop.rows.single().getValue("nodes") as List<*>).size)
        assertTrue((zeroHop.rows.single().getValue("relationships") as List<*>).isEmpty())

        assertEquals(
            1,
            executor.singleValue(
                "MATCH p=(a:IntConstant {value: 1})-[:DATAFLOW]->(b) RETURN length(p) AS value"
            )
        )
    }

    @Test
    fun `hidden order expressions retain pre projection bindings`() {
        val first = IntConstant(NodeId.next(), 2)
        val second = IntConstant(NodeId.next(), 1)
        val graph = DefaultGraph.Builder().addNode(first).addNode(second).build()

        val result = CypherExecutor(graph).execute(
            "MATCH (n:IntConstant) RETURN n.id AS id ORDER BY n.value ASC"
        )

        assertEquals(
            listOf(mapOf("id" to second.id.value), mapOf("id" to first.id.value)),
            result.rows
        )
    }

    @Test
    fun `inline property maps use Cypher equality and null semantics`() {
        val first = IntConstant(NodeId.next(), 2)
        val second = IntConstant(NodeId.next(), 1)
        val graph = DefaultGraph.Builder()
            .addNode(first)
            .addNode(second)
            .addEdge(DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN))
            .build()
        val executor = CypherExecutor(graph)

        val numericMatch = executor.execute(
            "MATCH (n:IntConstant {value: 2.0}) RETURN n.id AS id"
        )
        assertEquals(listOf(mapOf("id" to first.id.value)), numericMatch.rows)
        assertTrue(executor.execute("MATCH (n:IntConstant {missing: null}) RETURN n.id AS id").rows.isEmpty())
        assertTrue(
            executor.execute(
                "MATCH (a:IntConstant)-[:DATAFLOW {missing: null}]->(b) RETURN b.id AS id"
            ).rows.isEmpty()
        )
    }

    private fun twoNodeExecutor(): CypherExecutor {
        val first = IntConstant(NodeId.next(), 1)
        val second = IntConstant(NodeId.next(), 2)
        val graph = DefaultGraph.Builder()
            .addNode(first)
            .addNode(second)
            .addEdge(DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN))
            .build()
        return CypherExecutor(graph)
    }

    private fun CypherExecutor.singleValue(query: String): Any? = execute(query).rows.single()["value"]
}
