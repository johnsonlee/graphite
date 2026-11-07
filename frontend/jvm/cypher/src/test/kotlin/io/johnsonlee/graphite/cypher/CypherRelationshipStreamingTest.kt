package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class CypherRelationshipStreamingTest : CypherExecutorTestFixture() {

    @Test
    fun `variable path limit stops a high max hops traversal lazily`() {
        val chain = dataFlowChain(length = 1_000)
        var outgoingCalls = 0
        val countingGraph =
            object : Graph by chain {
                override fun outgoing(node: NodeId): Sequence<Edge> {
                    outgoingCalls++
                    return chain.outgoing(node)
                }
            }

        val result =
            CypherExecutor(countingGraph).execute("MATCH (a:IntConstant)-[:DATAFLOW*1..100000]->(b:LocalVariable) RETURN b.name LIMIT 1")

        assertEquals(listOf(mapOf("b.name" to "local-0")), result.rows)
        assertEquals(1, outgoingCalls)
    }

    @Test
    fun `untyped variable path follows available relationships`() {
        val result =
            CypherExecutor(dataFlowChain(length = 1)).execute("MATCH (a:IntConstant)-[*1..100]->(b:LocalVariable) RETURN b.name LIMIT 1")

        assertEquals(listOf(mapOf("b.name" to "local-0")), result.rows)
    }

    @Test
    fun `single hop limit skips a target with the wrong type`() {
        val result =
            CypherExecutor(dataFlowChain(length = 1))
                .execute("MATCH (a:IntConstant)-[:DATAFLOW]->(b:CallSiteNode) RETURN b.callee_name LIMIT 1")

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `relationship types accept only documented spellings`() {
        val source = IntConstant(NodeId.next(), 1)
        val target = IntConstant(NodeId.next(), 2)
        val relationshipGraph =
            DefaultGraph.Builder()
                .addNode(source)
                .addNode(target)
                .addEdge(DataFlowEdge(source.id, target.id, DataFlowKind.ASSIGN))
                .addEdge(CallEdge(source.id, target.id, isVirtual = false))
                .addEdge(TypeEdge(source.id, target.id, TypeRelation.EXTENDS))
                .addEdge(ControlFlowEdge(source.id, target.id, ControlFlowKind.SEQUENTIAL))
                .addEdge(ResourceEdge(source.id, target.id, ResourceRelation.LOOKUP))
                .build()
        val relationshipExecutor = CypherExecutor(relationshipGraph)
        val accepted =
            mapOf(
                "DATAFLOW" to "DATAFLOW",
                "DATA_FLOW" to "DATAFLOW",
                "data_flow" to "DATAFLOW",
                "CALL" to "CALL",
                "TYPE" to "TYPE",
                "CONTROL_FLOW" to "CONTROL_FLOW",
                "CONTROLFLOW" to "CONTROL_FLOW",
                "RESOURCE" to "RESOURCE_LOOKUP",
                "RESOURCE_LOOKUP" to "RESOURCE_LOOKUP")

        accepted.forEach { (requested, actual) ->
            val result = relationshipExecutor.execute("MATCH (a:IntConstant)-[r:$requested]->(b:IntConstant) RETURN type(r) AS type")
            assertEquals(listOf(mapOf("type" to actual)), result.rows, requested)
        }

        val constrainedCall =
            relationshipExecutor.execute(
                "MATCH (a:IntConstant)-[r:CALL {virtual: false, dynamic: false}]->(b:IntConstant) " + "RETURN type(r) AS type")
        assertEquals(listOf(mapOf("type" to "CALL")), constrainedCall.rows)

        listOf("D_A_T_A_F_L_O_W", "C_A_L_L", "T_Y_P_E", "C_O_N_T_R_O_L_F_L_O_W", "RESOURCE_EDGE").forEach { malformed ->
            val result = relationshipExecutor.execute("MATCH (a:IntConstant)-[r:$malformed]->(b:IntConstant) RETURN type(r) AS type")
            assertTrue(result.rows.isEmpty(), malformed)
        }

        val resourceTypes =
            listOf(
                "RESOURCE_OPEN" to ResourceRelation.OPENS,
                "RESOURCE_LOAD" to ResourceRelation.LOADS,
                "RESOURCE_BUNDLE_CANDIDATE" to ResourceRelation.BUNDLE_CANDIDATE,
                "RESOURCE_LOOKUP" to ResourceRelation.LOOKUP,
                "RESOURCE_KEYS" to ResourceRelation.ENUMERATES)
        val resourceSource = IntConstant(NodeId.next(), -1)
        val resourceTargets = resourceTypes.mapIndexed { index, _ -> IntConstant(NodeId.next(), index) }
        val resourceGraph =
            DefaultGraph.Builder()
                .addNode(resourceSource)
                .apply {
                    resourceTargets.forEachIndexed { index, target ->
                        addNode(target)
                        addEdge(ResourceEdge(resourceSource.id, target.id, resourceTypes[index].second))
                    }
                }
                .build()
        val resourceExecutor = CypherExecutor(resourceGraph)

        resourceTypes.forEachIndexed { index, (requested, _) ->
            val result =
                resourceExecutor.execute(
                    "MATCH (a:IntConstant)-[r:$requested]->(b:IntConstant) " + "WHERE b.value = $index RETURN type(r) AS type")
            assertEquals(listOf(mapOf("type" to requested)), result.rows, requested)
        }
    }

    @Test
    fun `filtered single hop limit streams complete matches before stopping`() {
        var outgoingCalls = 0
        val orderedGraph =
            object : Graph by graph {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    if (type == IntConstant::class.java) {
                        sequenceOf(graph.node(intConst42) as IntConstant, graph.node(intConst7) as IntConstant) as Sequence<T>
                    } else {
                        graph.nodes(type)
                    }

                override fun outgoing(id: NodeId): Sequence<Edge> {
                    outgoingCalls++
                    check(outgoingCalls == 1) { "LIMIT must stop before scanning the next source node" }
                    return graph.outgoing(id)
                }

                override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> {
                    outgoingCalls++
                    check(outgoingCalls == 1) { "LIMIT must stop before scanning the next source node" }
                    return sequence {
                        yieldAll(graph.outgoing(id, type))
                        error("LIMIT must stop before consuming the source adjacency tail")
                    }
                }
            }

        val result =
            CypherExecutor(orderedGraph)
                .execute(
                    "MATCH (a:IntConstant)-[r:DATAFLOW]->(b:ParameterNode) " +
                        "WHERE a.value = 42 AND b.index = 0 AND r.kind = 'PARAMETER_PASS' " +
                        "RETURN a.value AS value, b.index AS parameter LIMIT 1")

        assertEquals(listOf(mapOf("value" to 42, "parameter" to 0)), result.rows)
        assertEquals(1, outgoingCalls)
    }

    @Test
    fun `filtered multi hop limit keeps intermediate relationships lazy`() {
        val owner = TypeDescriptor("com.example.StreamingMultiHop")
        val valueType = TypeDescriptor("int")
        val method = MethodDescriptor(owner, "run", emptyList(), TypeDescriptor("void"))
        val source = IntConstant(NodeId.next(), 1)
        val middle = LocalVariable(NodeId.next(), "middle", valueType, method)
        val target = LocalVariable(NodeId.next(), "target", valueType, method)
        val base =
            DefaultGraph.Builder()
                .addNode(source)
                .addNode(middle)
                .addNode(target)
                .addEdge(DataFlowEdge(source.id, middle.id, DataFlowKind.ASSIGN))
                .addEdge(DataFlowEdge(middle.id, target.id, DataFlowKind.ASSIGN))
                .build()
        val guarded =
            object : Graph by base {
                override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> =
                    if (id == source.id) {
                        sequence {
                            yieldAll(base.outgoing(id, type))
                            error("Intermediate relationship fanout was materialized eagerly")
                        }
                    } else {
                        base.outgoing(id, type)
                    }
            }

        val result =
            CypherExecutor(guarded)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW]->(b:LocalVariable)-[:DATAFLOW]->(c:LocalVariable) " +
                        "WHERE c.name = 'target' RETURN c.name AS name LIMIT 1")

        assertEquals(listOf(mapOf("name" to "target")), result.rows)
    }

    @Test
    fun `filtered variable path limit stops before consuming adjacency tail`() {
        val chain = dataFlowChain(length = 1)
        val source = chain.nodes(IntConstant::class.java).single()
        val guarded =
            object : Graph by chain {
                override fun outgoing(id: NodeId): Sequence<Edge> =
                    if (id == source.id) {
                        sequence {
                            yieldAll(chain.outgoing(id))
                            error("Variable path traversal consumed beyond LIMIT")
                        }
                    } else {
                        chain.outgoing(id)
                    }
            }

        val result =
            CypherExecutor(guarded)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW*1..100]->(b:LocalVariable) " +
                        "WHERE b.name = 'local-0' RETURN b.name AS name LIMIT 1")

        assertEquals(listOf(mapOf("name" to "local-0")), result.rows)
    }

    @Test
    fun `filtered variable path evaluates where after binding relationship variable`() {
        val result =
            CypherExecutor(dataFlowChain(length = 1))
                .execute(
                    "MATCH (a:IntConstant)-[r:DATAFLOW*1..1]->(b:LocalVariable) " + "WHERE r IS NOT NULL RETURN b.name AS name LIMIT 1")

        assertEquals(listOf(mapOf("name" to "local-0")), result.rows)
    }

    @Test
    fun `filtered distinct relationship pagination retains only the requested window`() {
        val owner = TypeDescriptor("com.example.StreamingDistinct")
        val valueType = TypeDescriptor("int")
        val method = MethodDescriptor(owner, "run", emptyList(), TypeDescriptor("void"))
        val source = IntConstant(NodeId.next(), 42)
        val first = ParameterNode(NodeId.next(), 0, valueType, method)
        val second = ParameterNode(NodeId.next(), 1, valueType, method)
        val base = DefaultGraph.Builder().addNode(source).addNode(first).addNode(second).build()
        val guarded =
            object : Graph by base {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> = sequence {
                    yield(DataFlowEdge(source.id, first.id, DataFlowKind.PARAMETER_PASS) as T)
                    yield(DataFlowEdge(source.id, first.id, DataFlowKind.PARAMETER_PASS) as T)
                    yield(DataFlowEdge(source.id, second.id, DataFlowKind.PARAMETER_PASS) as T)
                    error("DISTINCT SKIP LIMIT consumed beyond the requested visible values")
                }
            }

        val result =
            CypherExecutor(guarded)
                .execute(
                    "MATCH (a:IntConstant)-[:DATAFLOW]->(b:ParameterNode) " +
                        "WHERE a.value = 42 RETURN DISTINCT b.index AS index SKIP 1 LIMIT 1")

        assertEquals(listOf(mapOf("index" to 1)), result.rows)
    }
}
