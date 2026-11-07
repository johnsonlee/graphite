package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

internal class MmapEdgeCodecTest : MmapGraphBuilderTestSupport() {
    @Test
    fun `round-trip DataFlowEdge for each kind`() {
        for (kind in DataFlowKind.entries) {
            NodeId.reset()
            val from = NodeId.next()
            val to = NodeId.next()
            val edge = DataFlowEdge(from, to, kind)
            val graph = MmapGraphBuilder().addNode(IntConstant(from, 1)).addNode(IntConstant(to, 2)).addEdge(edge).build()
            val edges = graph.outgoing(from).toList()
            assertEquals(1, edges.size, "Failed for kind=$kind")
            assertEquals(edge, edges[0], "Failed for kind=$kind")
        }
    }

    @Test
    fun `round-trip ResourceEdge for each relation`() {
        for (relation in ResourceRelation.entries) {
            NodeId.reset()
            val from = NodeId.next()
            val to = NodeId.next()
            val edge = ResourceEdge(from, to, relation)
            val graph =
                MmapGraphBuilder()
                    .addNode(ResourceFileNode(from, "application.properties", "test", "properties", null))
                    .addNode(ResourceValueNode(to, "application.properties", "feature.mode", "shadow", "properties", null))
                    .addEdge(edge)
                    .build()
            val edges = graph.outgoing(from).toList()
            assertEquals(1, edges.size, "Failed for relation=$relation")
            assertEquals(edge, edges[0], "Failed for relation=$relation")
        }
    }

    @Test
    fun `round-trip CallEdge`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = CallEdge(from, to, isVirtual = true, isDynamic = true)
        val graph = MmapGraphBuilder().addNode(IntConstant(from, 1)).addNode(IntConstant(to, 2)).addEdge(edge).build()
        val restored = graph.outgoing(from).first() as CallEdge
        assertEquals(true, restored.isVirtual)
        assertEquals(true, restored.isDynamic)
    }

    @Test
    fun `round-trip CallEdge non-virtual non-dynamic`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = CallEdge(from, to, isVirtual = false, isDynamic = false)
        val graph = MmapGraphBuilder().addNode(IntConstant(from, 1)).addNode(IntConstant(to, 2)).addEdge(edge).build()
        val restored = graph.outgoing(from).first() as CallEdge
        assertEquals(false, restored.isVirtual)
        assertEquals(false, restored.isDynamic)
    }

    @Test
    fun `round-trip TypeEdge for each relation`() {
        for (relation in TypeRelation.entries) {
            NodeId.reset()
            val from = NodeId.next()
            val to = NodeId.next()
            val edge = TypeEdge(from, to, relation)
            val graph = MmapGraphBuilder().addNode(IntConstant(from, 1)).addNode(IntConstant(to, 2)).addEdge(edge).build()
            val edges = graph.outgoing(from).toList()
            assertEquals(1, edges.size, "Failed for relation=$relation")
            assertEquals(edge, edges[0], "Failed for relation=$relation")
        }
    }

    @Test
    fun `round-trip ControlFlowEdge with comparison`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val comparand = NodeId.next()
        val edge = ControlFlowEdge(from, to, ControlFlowKind.BRANCH_TRUE, BranchComparison(ComparisonOp.EQ, comparand))
        val graph =
            MmapGraphBuilder()
                .addNode(IntConstant(from, 1))
                .addNode(IntConstant(to, 2))
                .addNode(IntConstant(comparand, 0))
                .addEdge(edge)
                .build()
        val restored = graph.outgoing(from).first() as ControlFlowEdge
        assertEquals(ControlFlowKind.BRANCH_TRUE, restored.kind)
        assertNotNull(restored.comparison)
        assertEquals(ComparisonOp.EQ, restored.comparison!!.operator)
        assertEquals(comparand, restored.comparison!!.comparandNodeId)
    }

    @Test
    fun `round-trip ControlFlowEdge without comparison`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = ControlFlowEdge(from, to, ControlFlowKind.SEQUENTIAL)
        val graph = MmapGraphBuilder().addNode(IntConstant(from, 1)).addNode(IntConstant(to, 2)).addEdge(edge).build()
        val restored = graph.outgoing(from).first() as ControlFlowEdge
        assertEquals(ControlFlowKind.SEQUENTIAL, restored.kind)
        assertNull(restored.comparison)
    }

    @Test
    fun `round-trip ControlFlowEdge for each kind`() {
        for (kind in ControlFlowKind.entries) {
            NodeId.reset()
            val from = NodeId.next()
            val to = NodeId.next()
            val edge = ControlFlowEdge(from, to, kind)
            val graph = MmapGraphBuilder().addNode(IntConstant(from, 1)).addNode(IntConstant(to, 2)).addEdge(edge).build()
            assertEquals(edge, graph.outgoing(from).first(), "Failed for kind=$kind")
        }
    }

    @Test
    fun `10K nodes and edges round-trip without error`() {
        val builder = MmapGraphBuilder()
        val nodeIds = mutableListOf<NodeId>()

        // Add 10K nodes of various types
        for (i in 0 until 10_000) {
            val id = NodeId.next()
            nodeIds.add(id)
            val node =
                when (i % 5) {
                    0 -> IntConstant(id, i)
                    1 -> StringConstant(id, "str_$i")
                    2 -> LongConstant(id, i.toLong())
                    3 -> BooleanConstant(id, i % 2 == 0)
                    else -> FloatConstant(id, i.toFloat())
                }
            builder.addNode(node)
        }

        // Add 10K edges
        for (i in 0 until 10_000) {
            val from = nodeIds[i]
            val to = nodeIds[(i + 1) % 10_000]
            builder.addEdge(DataFlowEdge(from, to, DataFlowKind.ASSIGN))
        }

        val graph = builder.build()

        // Verify all nodes are present
        for (i in 0 until 10_000) {
            assertNotNull(graph.node(nodeIds[i]), "Node $i missing")
        }

        // Verify edges
        for (i in 0 until 10_000) {
            val outgoing = graph.outgoing(nodeIds[i]).toList()
            assertEquals(1, outgoing.size, "Node $i should have 1 outgoing edge")
        }
    }
}
