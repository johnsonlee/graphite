package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CypherOrderedRelationshipTest : CypherExecutorTestFixture() {

    @Test
    fun `filtered relationship order by retains only skip plus limit rows`() {
        val owner = TypeDescriptor("com.example.StreamingOrder")
        val valueType = TypeDescriptor("int")
        val method = MethodDescriptor(owner, "run", emptyList(), TypeDescriptor("void"))
        val source = IntConstant(NodeId.next(), 42)
        val targets =
            listOf(
                ParameterNode(NodeId.next(), 0, valueType, method),
                ParameterNode(NodeId.next(), 2, valueType, method),
                ParameterNode(NodeId.next(), 1, valueType, method))
        val base = DefaultGraph.Builder().addNode(source).apply { targets.forEach(::addNode) }.build()
        var consumedEdges = 0
        val guarded =
            object : Graph by base {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> =
                    if (id == source.id && type == DataFlowEdge::class.java) {
                        sequence {
                            repeat(100_000) { index ->
                                consumedEdges++
                                val target = targets[index % targets.size]
                                yield(DataFlowEdge(source.id, target.id, DataFlowKind.PARAMETER_PASS) as T)
                            }
                        }
                    } else {
                        emptySequence()
                    }
            }

        val result =
            CypherExecutor(guarded)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode) " +
                        "WHERE a.value = 42 RETURN DISTINCT b.index AS index " +
                        "ORDER BY index DESC SKIP 1 LIMIT 1")

        assertEquals(listOf(mapOf("index" to 1)), result.rows)
        assertEquals(100_000, consumedEdges, "ORDER BY must inspect every filtered relationship match")
    }

    @Test
    fun `filtered relationship top k preserves expression null and multi-field ordering`() {
        val owner = TypeDescriptor("com.example.StreamingOrderSemantics")
        val valueType = TypeDescriptor("int")
        val method = MethodDescriptor(owner, "run", emptyList(), TypeDescriptor("void"))
        val source = IntConstant(NodeId.next(), 42)
        val firstNullRank = ParameterNode(NodeId.next(), 1, valueType, method)
        val secondNullRank = ParameterNode(NodeId.next(), 1, valueType, method)
        val zeroRank = ParameterNode(NodeId.next(), 0, valueType, method)
        val twoRank = ParameterNode(NodeId.next(), 2, valueType, method)
        val orderedGraph =
            DefaultGraph.Builder()
                .addNode(source)
                .addNode(firstNullRank)
                .addNode(secondNullRank)
                .addNode(zeroRank)
                .addNode(twoRank)
                .addEdge(DataFlowEdge(source.id, firstNullRank.id, DataFlowKind.PARAMETER_PASS))
                .addEdge(DataFlowEdge(source.id, secondNullRank.id, DataFlowKind.PARAMETER_PASS))
                .addEdge(DataFlowEdge(source.id, zeroRank.id, DataFlowKind.PARAMETER_PASS))
                .addEdge(DataFlowEdge(source.id, twoRank.id, DataFlowKind.PARAMETER_PASS))
                .build()

        val result =
            CypherExecutor(orderedGraph)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode) " +
                        "WHERE a.value = 42 RETURN b.index AS index, b.id AS id " +
                        "ORDER BY CASE WHEN index = 1 THEN null ELSE index END ASC, id DESC SKIP 1 LIMIT 2")

        assertEquals(
            listOf(mapOf("index" to 2, "id" to twoRank.id.value), mapOf("index" to 1, "id" to secondNullRank.id.value)), result.rows)

        val hiddenPropertyOrder =
            CypherExecutor(orderedGraph)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode) " +
                        "WHERE a.value = 42 RETURN b.id AS id " +
                        "ORDER BY b.index ASC LIMIT 2")
        assertEquals(listOf(mapOf("id" to zeroRank.id.value), mapOf("id" to firstNullRank.id.value)), hiddenPropertyOrder.rows)

        val hiddenExpressionOrder =
            CypherExecutor(orderedGraph)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode) " +
                        "WHERE a.value = 42 RETURN b.id AS id " +
                        "ORDER BY CASE WHEN b.index = 0 THEN -1 ELSE b.index END ASC LIMIT 2")
        assertEquals(hiddenPropertyOrder.rows, hiddenExpressionOrder.rows)
    }

    @Test
    fun `filtered relationship pagination handles bounded edge cases`() {
        val zeroLimit =
            executor.execute("MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode) " + "WHERE a.value = 42 RETURN b.index AS index LIMIT 0")
        val oversizedPage =
            executor.execute(
                "MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode) " +
                    "WHERE a.value = 42 RETURN b.index AS index SKIP 2147483647 LIMIT 1")

        assertTrue(zeroLimit.rows.isEmpty())
        assertTrue(oversizedPage.rows.isEmpty())
    }

    @Test
    fun `a later pattern expands from its declared source variable`() {
        val owner = TypeDescriptor("com.example.PatternSource")
        val valueType = TypeDescriptor("int")
        val method = MethodDescriptor(owner, "run", emptyList(), TypeDescriptor("void"))
        val source = IntConstant(NodeId.next(), 1)
        val middle = LocalVariable(NodeId.next(), "middle", valueType, method)
        val target = ParameterNode(NodeId.next(), 0, valueType, method)
        val patternGraph =
            DefaultGraph.Builder()
                .addNode(source)
                .addNode(middle)
                .addNode(target)
                .addEdge(DataFlowEdge(source.id, middle.id, DataFlowKind.ASSIGN))
                .addEdge(DataFlowEdge(source.id, target.id, DataFlowKind.PARAMETER_PASS))
                .build()

        val result =
            CypherExecutor(patternGraph)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable), " +
                        "(a)-[:DATAFLOW]->(c:ParameterNode) " +
                        "WHERE b.name = 'middle' RETURN c.index AS index LIMIT 1")

        assertEquals(listOf(mapOf("index" to 0)), result.rows)
    }

    @Test
    fun `limit does not discard a later branch that completes the pattern`() {
        val owner = TypeDescriptor("com.example.BranchLimit")
        val valueType = TypeDescriptor("int")
        val method = MethodDescriptor(owner, "run", emptyList(), TypeDescriptor("void"))
        val source = IntConstant(NodeId.next(), 0)
        val deadEnd = LocalVariable(NodeId.next(), "dead-end", valueType, method)
        val liveMiddle = LocalVariable(NodeId.next(), "live-middle", valueType, method)
        val target = LocalVariable(NodeId.next(), "target", valueType, method)
        val branchGraph =
            DefaultGraph.Builder()
                .addNode(source)
                .addNode(deadEnd)
                .addNode(liveMiddle)
                .addNode(target)
                .addEdge(DataFlowEdge(source.id, deadEnd.id, DataFlowKind.ASSIGN))
                .addEdge(DataFlowEdge(source.id, liveMiddle.id, DataFlowKind.ASSIGN))
                .addEdge(DataFlowEdge(liveMiddle.id, target.id, DataFlowKind.ASSIGN))
                .build()

        val result =
            CypherExecutor(branchGraph)
                .execute("MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable)" + "-[:DATAFLOW]->(c:LocalVariable) RETURN c.name LIMIT 1")

        assertEquals(listOf(mapOf("c.name" to "target")), result.rows)
    }
}
