package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossGraphCypherExecutorTest {

    @Test
    fun `qualifies colliding local node ids and records row provenance`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(1), 10)),
            "billing" to graph(IntConstant(NodeId(1), 20))
        )

        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n ORDER BY n.graphId"
        )

        assertEquals(2, result.rows.size)
        assertEquals(
            listOf("billing:1", "orders:1"),
            result.rows.map { (it["n"] as Map<*, *>)["elementId"] }
        )
        assertEquals(
            listOf(listOf("billing"), listOf("orders")),
            result.rows.map(::graphIds)
        )
        assertTrue(result.rows.all { "_graphIds" !in it })
    }

    @Test
    fun `joins independent patterns across graph boundaries`() {
        val executor = executor(
            "orders" to graph(StringConstant(NodeId(1), "shared")),
            "billing" to graph(StringConstant(NodeId(1), "shared"))
        )

        val result = executor.execute(
            """
            MATCH (a:StringConstant), (b:StringConstant)
            WHERE a.value = b.value AND a.graphId < b.graphId
            RETURN graphId(a) AS leftGraph, graphId(b) AS rightGraph, a.value AS value
            """.trimIndent()
        )

        assertEquals(
            listOf(
                mapOf(
                    "leftGraph" to "billing",
                    "rightGraph" to "orders",
                    "value" to "shared",
                    RESULT_METADATA_KEY to mapOf(
                        RESULT_GRAPH_IDS_KEY to listOf("billing", "orders")
                    )
                )
            ),
            result.rows
        )
    }

    @Test
    fun `relationship traversal stays inside the owning graph`() {
        val orders = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val billing = graph(
            IntConstant(NodeId(1), 30),
            IntConstant(NodeId(2), 40)
        )
        val executor = executor("orders" to orders, "billing" to billing)

        val result = executor.execute(
            "MATCH (a:IntConstant)-[:DATAFLOW]->(b:IntConstant) " +
                "RETURN graphId(a) AS graph, a.value AS source, b.value AS target"
        )

        assertEquals(1, result.rows.size)
        assertEquals("orders", result.rows.single()["graph"])
        assertEquals(10, result.rows.single()["source"])
        assertEquals(20, result.rows.single()["target"])
        assertEquals(listOf("orders"), graphIds(result.rows.single()))
    }

    @Test
    fun `aggregates once across all selected graphs and retains contributors`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(1), 10), IntConstant(NodeId(2), 20)),
            "billing" to graph(IntConstant(NodeId(1), 30)),
            "empty" to graph(StringConstant(NodeId(1), "not counted"))
        )

        val result = executor.execute("MATCH (n:IntConstant) RETURN count(n) AS count")

        assertEquals(3L, result.rows.single()["count"])
        assertEquals(listOf("billing", "orders"), graphIds(result.rows.single()))
    }

    @Test
    fun `relationship and named path rows retain graph identity`() {
        val orders = DefaultGraph.Builder()
            .addNode(IntConstant(NodeId(1), 10))
            .addNode(IntConstant(NodeId(2), 20))
            .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
            .build()
        val executor = executor("orders" to orders, "billing" to graph(IntConstant(NodeId(1), 30)))

        val relationship = executor.execute("MATCH (a)-[r:DATAFLOW]->(b) RETURN r")
        assertEquals(listOf("orders"), graphIds(relationship.rows.single()))
        assertEquals("orders", (relationship.rows.single()["r"] as Map<*, *>)["graphId"])

        val namedPath = executor.execute(
            "MATCH p=(a:IntConstant)-[:DATAFLOW]->(b:IntConstant) " +
                "RETURN graphId(p) AS graph, p"
        )
        assertEquals("orders", namedPath.rows.single()["graph"])
        assertEquals("orders", (namedPath.rows.single()["p"] as Map<*, *>)["graphId"])
        assertEquals(listOf("orders"), graphIds(namedPath.rows.single()))

        val incomingPath = executor.execute(
            "MATCH p=(b:IntConstant)<-[:DATAFLOW]-(a:IntConstant) RETURN graphId(p) AS graph, p"
        )
        assertEquals("orders", incomingPath.rows.single()["graph"])

        val variablePath = executor.execute(
            "MATCH (a:IntConstant)-[r:DATAFLOW*1..2]->(b:IntConstant) RETURN r"
        )
        assertEquals("orders", (variablePath.rows.single()["r"] as Map<*, *>)["graphId"])
    }

    @Test
    fun `distinct and union merge provenance from every collapsed row`() {
        val executor = executor(
            "orders" to graph(StringConstant(NodeId(1), "shared")),
            "billing" to graph(StringConstant(NodeId(1), "shared"))
        )

        val distinct = executor.execute(
            "MATCH (n:StringConstant) RETURN DISTINCT n.value AS value LIMIT 1"
        )
        assertEquals("shared", distinct.rows.single()["value"])
        assertEquals(listOf("billing", "orders"), graphIds(distinct.rows.single()))

        val union = executor.execute(
            "MATCH (n:StringConstant) WHERE graphId(n) = 'orders' RETURN n.value AS value " +
                "UNION MATCH (n:StringConstant) WHERE graphId(n) = 'billing' RETURN n.value AS value"
        )
        assertEquals(1, union.rows.size)
        assertEquals(listOf("billing", "orders"), graphIds(union.rows.single()))
    }

    @Test
    fun `supports graph qualified properties functions and fast filtered limits`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(7), 10)),
            "billing" to graph(IntConstant(NodeId(7), 20))
        )

        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.graphId = 'orders' " +
                "RETURN id(n) AS id, elementId(n) AS elementId, properties(n) AS properties LIMIT 1"
        )

        val row = result.rows.single()
        assertEquals(7, row["id"])
        assertEquals("orders:7", row["elementId"])
        assertEquals("orders", (row["properties"] as Map<*, *>)["graphId"])
        assertEquals(listOf("orders"), graphIds(row))
    }

    @Test
    fun `fast string filters preserve qualified identity and provenance`() {
        val executor = executor(
            "orders" to graph(StringConstant(NodeId(1), "feature-order-handler")),
            "billing" to graph(StringConstant(NodeId(1), "feature-billing-handler"))
        )

        val contains = executor.execute(
            "MATCH (n:StringConstant) WHERE n.value CONTAINS 'billing' " +
                "RETURN elementId(n) AS id, n.value AS value LIMIT 1"
        )
        val startsWith = executor.execute(
            "MATCH (n:StringConstant) WHERE n.value STARTS WITH 'feature-order' " +
                "RETURN elementId(n) AS id LIMIT 1"
        )
        val endsWith = executor.execute(
            "MATCH (n:StringConstant) WHERE n.value ENDS WITH 'handler' " +
                "RETURN elementId(n) AS id LIMIT 2"
        )

        assertEquals("billing:1", contains.rows.single()["id"])
        assertEquals("feature-billing-handler", contains.rows.single()["value"])
        assertEquals(listOf("billing"), graphIds(contains.rows.single()))
        assertEquals("orders:1", startsWith.rows.single()["id"])
        assertEquals(listOf("orders:1", "billing:1"), endsWith.rows.map { it["id"] })
        assertEquals(listOf(listOf("orders"), listOf("billing")), endsWith.rows.map(::graphIds))
    }

    @Test
    fun `supports an empty graph set and rejects duplicate graph namespaces`() {
        val empty = CrossGraphCypherExecutor(emptyList()).execute("MATCH (n) RETURN count(n) AS count")
        assertEquals(0L, empty.rows.single()["count"])
        assertEquals(emptyList<String>(), graphIds(empty.rows.single()))

        val constant = CrossGraphCypherExecutor(emptyList()).execute("RETURN 1 AS value")
        assertEquals(emptyList<String>(), graphIds(constant.rows.single()))

        val graph = graph(IntConstant(NodeId(1), 1))
        val error = assertFailsWith<IllegalArgumentException> {
            CrossGraphCypherExecutor(listOf(CypherGraph("service", graph), CypherGraph("service", graph)))
        }
        assertTrue(error.message.orEmpty().contains("unique"))
    }

    @Test
    fun `bounded execution and internal value helpers cover qualified edge cases`() {
        val executor = executor(
            "orders" to graph(IntConstant(NodeId(1), 10), IntConstant(NodeId(2), 20))
        )

        val result = executor.execute("MATCH (n:IntConstant) RETURN n", maxRows = 1)
        assertEquals(1, result.rows.size)
        assertEquals(listOf("orders"), graphIds(result.rows.single()))
        assertNull(nodeValue("not a node"))
        assertNull(edgeValue("not an edge"))
    }

    @Test
    fun `qualified values expose namespaced identity properties and equality`() {
        val first = IntConstant(NodeId(1), 10)
        val second = IntConstant(NodeId(2), 20)
        val edge = DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN)
        val graph = DefaultGraph.Builder()
            .addNode(first)
            .addNode(second)
            .addEdge(edge)
            .build()
        val node = QualifiedNode("orders", graph, first)
        val sameNodeId = QualifiedNode("orders", graph, IntConstant(NodeId(1), 99))
        val qualifiedEdge = QualifiedEdge("orders", graph, edge)
        val sameEdge = QualifiedEdge(
            "orders",
            graph,
            DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN)
        )
        val path = QualifiedPath("orders", listOf(node), listOf(qualifiedEdge))
        val resourceEdge = QualifiedEdge(
            "orders",
            graph,
            ResourceEdge(first.id, second.id, ResourceRelation.LOOKUP)
        )
        val evaluator = ExpressionEvaluator()

        assertEquals(node, sameNodeId)
        assertEquals(node.hashCode(), sameNodeId.hashCode())
        assertNotEquals(node, QualifiedNode("billing", graph, first))
        assertTrue(!node.equals(first))
        assertEquals(qualifiedEdge, sameEdge)
        assertEquals(qualifiedEdge.hashCode(), sameEdge.hashCode())
        assertNotEquals(qualifiedEdge, QualifiedEdge("billing", graph, edge))
        assertTrue(!qualifiedEdge.equals(edge))
        assertEquals(first, nodeValue(first))
        assertEquals(edge, edgeValue(edge))
        assertEquals("1", CypherFunctions.call("elementId", listOf(first)))
        assertNull(CypherFunctions.call("elementId", listOf("not a node")))

        fun property(value: Any, name: String): Any? = evaluator.evaluate(
            CypherExpr.Property(CypherExpr.Variable("value"), name),
            mapOf("value" to value)
        )

        assertEquals("orders", property(node, "graphId"))
        assertEquals("orders:1", property(node, "elementId"))
        assertEquals("orders:1", property(node, "qualifiedId"))
        assertEquals(10, property(node, "value"))
        assertEquals("orders", property(qualifiedEdge, "graphId"))
        assertEquals("ASSIGN", property(qualifiedEdge, "kind"))
        assertEquals("LOOKUP", property(resourceEdge, "kind"))
        assertEquals("orders", property(path, "graphId"))
        assertEquals(1, property(path, "length"))
        assertNull(property(path, "unknown"))
    }

    @Test
    fun `qualified relationship materialization preserves subtype properties`() {
        val first = IntConstant(NodeId(1), 10)
        val second = IntConstant(NodeId(2), 20)
        val graph = DefaultGraph.Builder()
            .addNode(first)
            .addNode(second)
            .addEdge(CallEdge(first.id, second.id, isVirtual = true, isDynamic = false))
            .addEdge(TypeEdge(first.id, second.id, TypeRelation.EXTENDS))
            .addEdge(ControlFlowEdge(first.id, second.id, ControlFlowKind.SEQUENTIAL))
            .addEdge(ResourceEdge(first.id, second.id, ResourceRelation.LOOKUP))
            .build()

        val result = executor("orders" to graph).execute("MATCH (a)-[r]->(b) RETURN r ORDER BY r")
        val relationships = result.rows.map { it.getValue("r") as Map<*, *> }

        assertEquals(4, relationships.size)
        assertTrue(relationships.all { it["graphId"] == "orders" })
        assertTrue(relationships.any { it["virtual"] == true && it["dynamic"] == false })
        assertTrue(relationships.any { it["kind"] == "EXTENDS" })
        assertTrue(relationships.any { it["kind"] == "SEQUENTIAL" })
        assertTrue(relationships.any { it["kind"] == "LOOKUP" })
    }

    private fun executor(vararg graphs: Pair<String, Graph>): CrossGraphCypherExecutor =
        CrossGraphCypherExecutor(graphs.map { (id, graph) -> CypherGraph(id, graph) })

    @Suppress("UNCHECKED_CAST")
    private fun graphIds(row: Map<String, Any?>): List<String> =
        (row.getValue(RESULT_METADATA_KEY) as Map<String, Any?>).getValue(RESULT_GRAPH_IDS_KEY) as List<String>

    private fun graph(vararg nodes: io.johnsonlee.graphite.core.Node): Graph =
        DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
}
