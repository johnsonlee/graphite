package io.johnsonlee.graphite.core

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdgeTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    // ========================================================================
    // DataFlowEdge
    // ========================================================================

    @Test
    fun `DataFlowEdge with all kind values`() {
        val from = NodeId.next()
        val to = NodeId.next()
        DataFlowKind.entries.forEach { kind ->
            val edge = DataFlowEdge(from, to, kind)
            assertEquals(from, edge.from)
            assertEquals(to, edge.to)
            assertEquals(kind, edge.kind)
        }
    }

    @Test
    fun `DataFlowKind has 9 values`() {
        assertEquals(9, DataFlowKind.entries.size)
    }

    // ========================================================================
    // CallEdge
    // ========================================================================

    @Test
    fun `CallEdge with virtual flag`() {
        val edge = CallEdge(NodeId.next(), NodeId.next(), isVirtual = true, isDynamic = false)
        assertTrue(edge.isVirtual)
        assertFalse(edge.isDynamic)
    }

    @Test
    fun `CallEdge with dynamic flag`() {
        val edge = CallEdge(NodeId.next(), NodeId.next(), isVirtual = false, isDynamic = true)
        assertFalse(edge.isVirtual)
        assertTrue(edge.isDynamic)
    }

    @Test
    fun `CallEdge isDynamic defaults to false`() {
        val edge = CallEdge(NodeId.next(), NodeId.next(), isVirtual = true)
        assertFalse(edge.isDynamic)
    }

    // ========================================================================
    // TypeEdge
    // ========================================================================

    @Test
    fun `TypeEdge with EXTENDS`() {
        val edge = TypeEdge(NodeId.next(), NodeId.next(), TypeRelation.EXTENDS)
        assertEquals(TypeRelation.EXTENDS, edge.kind)
    }

    @Test
    fun `TypeEdge with IMPLEMENTS`() {
        val edge = TypeEdge(NodeId.next(), NodeId.next(), TypeRelation.IMPLEMENTS)
        assertEquals(TypeRelation.IMPLEMENTS, edge.kind)
    }

    @Test
    fun `TypeRelation has 2 values`() {
        assertEquals(2, TypeRelation.entries.size)
    }

    // ========================================================================
    // ControlFlowEdge
    // ========================================================================

    @Test
    fun `ControlFlowEdge with all kind values`() {
        ControlFlowKind.entries.forEach { kind ->
            val edge = ControlFlowEdge(NodeId.next(), NodeId.next(), kind)
            assertEquals(kind, edge.kind)
            assertNull(edge.comparison)
        }
    }

    @Test
    fun `ControlFlowKind has 7 values`() {
        assertEquals(7, ControlFlowKind.entries.size)
    }

    @Test
    fun `ControlFlowEdge with comparison`() {
        val comparandId = NodeId.next()
        val comp = BranchComparison(ComparisonOp.EQ, comparandId)
        val edge = ControlFlowEdge(NodeId.next(), NodeId.next(), ControlFlowKind.BRANCH_TRUE, comp)
        assertEquals(ComparisonOp.EQ, edge.comparison?.operator)
        assertEquals(comparandId, edge.comparison?.comparandNodeId)
    }

    // ========================================================================
    // BranchComparison
    // ========================================================================

    @Test
    fun `BranchComparison with all ComparisonOp values`() {
        ComparisonOp.entries.forEach { op ->
            val comp = BranchComparison(op, NodeId.next())
            assertEquals(op, comp.operator)
        }
    }

    @Test
    fun `ComparisonOp has 6 values`() {
        assertEquals(6, ComparisonOp.entries.size)
    }

    // ========================================================================
    // BranchScope
    // ========================================================================

    @Test
    fun `BranchScope construction`() {
        val condId = NodeId.next()
        val md = MethodDescriptor(TypeDescriptor("Foo"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.NE, NodeId.next())
        val trueBranch = IntOpenHashSet(intArrayOf(1, 2, 3))
        val falseBranch = IntOpenHashSet(intArrayOf(4, 5))

        val scope = BranchScope(condId, md, comp, trueBranch, falseBranch)
        assertEquals(condId, scope.conditionNodeId)
        assertEquals(3, scope.trueBranchNodeIds.size)
        assertEquals(2, scope.falseBranchNodeIds.size)
    }
}
