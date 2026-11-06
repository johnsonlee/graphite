package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherPathExecutionTest : CypherExecutorTestFixture() {

    @Test
    fun `match single hop dataflow relationship`() {
        val result = executor.execute("MATCH (c:IntConstant)-[:DATAFLOW]->(p:ParameterNode) RETURN c.value, p.index")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["c.value"])
        assertEquals(0, result.rows[0]["p.index"])
    }

    @Test
    fun `match single hop dataflow to CallSiteNode`() {
        val result = executor.execute("MATCH (c:StringConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name")
        assertEquals(2, result.rows.size)
        val names = result.rows.map { it["cs.callee_name"] }.toSet()
        assertTrue(names.contains("save"))
        assertTrue(names.contains("log"))
    }

    @Test
    fun `match relationship without edge type`() {
        val result = executor.execute("MATCH (c:StringConstant)-[]->(cs:CallSiteNode) RETURN c.value, cs.callee_name")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `match variable-length path`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 -> callSite1
        // That's 4 hops from IntConstant to CallSiteNode
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW*..5]->(b:CallSiteNode) RETURN a.value, b.callee_name")
        assertTrue(result.rows.isNotEmpty(), "Should find paths from IntConstant to CallSiteNode via DATAFLOW")
        val row = result.rows.first()
        assertEquals(42, row["a.value"])
        assertEquals("save", row["b.callee_name"])
    }

    @Test
    fun `match variable-length path with limit`() {
        val result = executor.execute("MATCH (a)-[:DATAFLOW*..5]->(b:CallSiteNode) RETURN a.id, b.callee_name LIMIT 2")
        assertTrue(result.rows.size <= 2)
    }

    @Test
    fun `variable-length relationship variable returns relationships`() {
        val result = executor.execute("MATCH (a:IntConstant {value: 42})-[r:DATAFLOW*4]->(b:CallSiteNode) RETURN r")

        assertEquals(1, result.rows.size)
        val relationships = result.rows.single()["r"] as List<*>
        assertEquals(4, relationships.size)
        assertTrue(relationships.all { it is DataFlowEdge })
    }

    @Test
    fun `variable-length path accepts every requested relationship type`() {
        val result = executor.execute("MATCH (a:IntConstant {value: 42})-[r:DATAFLOW|CALL*]->(b:ReturnNode) RETURN r")

        assertEquals(1, result.rows.size)
        val relationships = result.rows.single()["r"] as List<*>
        assertEquals(5, relationships.size)
        assertTrue(relationships.take(4).all { it is DataFlowEdge })
        assertTrue(relationships.last() is CallEdge)
    }

    @Test
    fun `a bound relationship remains a constraint in a later match`() {
        val result =
            executor.execute(
                "MATCH (a:StringConstant)-[r:DATAFLOW]->(b:CallSiteNode {callee_name: 'save'}) " +
                    "MATCH (a)-[r:DATAFLOW]->(x) RETURN x.callee_name AS name")

        assertEquals(listOf("save"), result.rows.map { it["name"] })
    }

    @Test
    fun `a bound relationship list constrains a variable-length match`() {
        val result =
            executor.execute(
                "MATCH (a:ParameterNode)-[r1:DATAFLOW]->(:LocalVariable {name: 'x'})" +
                    "-[r2:DATAFLOW]->(b:LocalVariable {name: 'y'}) " +
                    "WITH a, b, [r2, r1] AS relationships " +
                    "MATCH (a)-[relationships:DATAFLOW*2]->(b) RETURN relationships")

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `a bound relationship is reserved from earlier variable-length segments`() {
        NodeId.reset()
        val first = NodeId.next()
        val second = NodeId.next()
        val third = NodeId.next()
        val builder =
            DefaultGraph.Builder()
                .addNode(IntConstant(first, 1))
                .addNode(IntConstant(second, 2))
                .addNode(IntConstant(third, 3))
                .addEdge(DataFlowEdge(first, second, DataFlowKind.ASSIGN))
                .addEdge(DataFlowEdge(second, third, DataFlowKind.ASSIGN))
        val localExecutor = CypherExecutor(builder.build())

        val result =
            localExecutor.execute(
                "MATCH (:IntConstant {value: 2})-[r:DATAFLOW]->(:IntConstant {value: 3}) " +
                    "MATCH (:IntConstant {value: 1})-[:DATAFLOW*1..2]-(b)-[r:DATAFLOW]-(c) " +
                    "RETURN b.value AS b, c.value AS c")

        assertEquals(listOf(mapOf("b" to 2, "c" to 3)), result.rows)
    }

    @Test
    fun `variable-length relationship types are matched exactly`() {
        val source = IntConstant(NodeId(1), 3)
        val dataFlowTarget = IntConstant(NodeId(2), 2)
        val callTarget = IntConstant(NodeId(3), 3)
        val typeTarget = IntConstant(NodeId(4), 4)
        val mixedGraph =
            DefaultGraph.Builder()
                .addNode(source)
                .addNode(dataFlowTarget)
                .addNode(callTarget)
                .addNode(typeTarget)
                .addEdge(DataFlowEdge(source.id, dataFlowTarget.id, DataFlowKind.ASSIGN))
                .addEdge(CallEdge(source.id, callTarget.id, isVirtual = false))
                .addEdge(TypeEdge(source.id, typeTarget.id, TypeRelation.EXTENDS))
                .build()
        val mixedExecutor = CypherExecutor(mixedGraph)

        val union =
            mixedExecutor.execute(
                "MATCH (a:IntConstant)-[:DATAFLOW|CALL*1..1]->(b:IntConstant) " + "WHERE a.value = 3 RETURN b.id ORDER BY b.id LIMIT 10")
        val invalid =
            mixedExecutor.execute(
                "MATCH (a:IntConstant)-[:D_A_T_A_F_L_O_W*1..1]->(b:IntConstant) " + "WHERE a.value = 3 RETURN b.id ORDER BY b.id LIMIT 10")

        assertEquals(listOf(2, 3), union.rows.map { it["b.id"] })
        assertTrue(invalid.rows.isEmpty())
    }

    @Test
    fun `variable-length incoming path`() {
        val result = executor.execute("MATCH (b:CallSiteNode)<-[:DATAFLOW*..5]-(a:IntConstant) RETURN a.value, b.callee_name LIMIT 1")
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `path variable assignment returns path`() {
        val result = executor.execute("MATCH p = (c:IntConstant)-[:DATAFLOW]->(ps:ParameterNode) RETURN p")
        assertEquals(1, result.columns.size)
        assertEquals("p", result.columns[0])
        assertTrue(result.rows.isNotEmpty())
        val path = result.rows[0]["p"]
        assertTrue(path is List<*>, "Path should be a list of nodes and edges")
        val pathList = path as List<*>
        // Path should have at least startNode, edge, endNode = 3 elements
        assertTrue(pathList.size >= 3, "Path should contain at least 3 elements (node, edge, node), got ${pathList.size}")
    }

    @Test
    fun `path variable without relationship variable still builds path`() {
        val result = executor.execute("MATCH p = (c:IntConstant)-[]->(ps:ParameterNode) RETURN p")
        assertTrue(result.rows.isNotEmpty())
        val path = result.rows[0]["p"]
        assertTrue(path is List<*>, "Path should be a list")
        val pathList = path as List<*>
        assertTrue(pathList.size >= 3, "Path should contain at least 3 elements")
    }

    @Test
    fun `named path limit stops before consuming adjacency tail`() {
        val guarded =
            object : Graph by graph {
                override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> =
                    if (id == intConst42) {
                        sequence {
                            yieldAll(graph.outgoing(id, type))
                            error("Named path matching consumed beyond LIMIT")
                        }
                    } else {
                        graph.outgoing(id, type)
                    }
            }

        val result = CypherExecutor(guarded).execute("MATCH p = (c:IntConstant)-[:DATAFLOW]->(ps:ParameterNode) RETURN p LIMIT 1")

        val path = result.rows.single()["p"] as List<*>
        assertTrue(path.size >= 3, "Path should contain the matched node, edge, and node")
    }

    @Test
    fun `named path with relationship variable retains the bound edge`() {
        val result = executor.execute("MATCH p = (c:IntConstant)-[r:DATAFLOW]->(ps:ParameterNode) RETURN p LIMIT 1")

        val path = result.rows.single()["p"] as List<*>
        assertTrue(path.size >= 3, "Path should contain the explicitly bound relationship")
    }

    @Test
    fun `repeated target variable rejects a different node`() {
        val result =
            executor.execute("MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode)-[:DATAFLOW]->(a) " + "WHERE true RETURN a.value LIMIT 1")

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `variable length path with explicit min and max`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 -> callSite1 = 4 hops
        // *2..5 requires at least 2 hops, so intConst42 should reach callSite1
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW*2..5]->(b:CallSiteNode) RETURN a.value, b.callee_name")
        assertTrue(result.rows.isNotEmpty())
        assertEquals(42, result.rows.first()["a.value"])
    }

    @Test
    fun `variable length path with min only`() {
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW*2..]->(b:LocalVariable) RETURN a.value, b.name LIMIT 5")
        assertNotNull(result)
    }

    @Test
    fun `exact hop variable length path`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 = exactly 3 hops
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW*3]->(b:LocalVariable) RETURN a.value, b.name")
        assertTrue(result.rows.isNotEmpty())
        assertEquals("y", result.rows.first()["b.name"])
    }

    @Test
    fun `list comprehension with pipe expression only`() {
        val result = executor.execute("RETURN [x IN [1, 2, 3] | x * 2] AS doubled")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val doubled = result.rows[0]["doubled"] as List<*>
        assertEquals(listOf(2L, 4L, 6L), doubled)
    }

    @Test
    fun `function with no arguments`() {
        val result = executor.execute("RETURN rand() AS r")
        assertEquals(1, result.rows.size)
        val r = result.rows[0]["r"] as Double
        assertTrue(r in 0.0..1.0)
    }
}
