package io.johnsonlee.graphite.core

import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `ResourceEdge with all relation values`() {
        val from = NodeId.next()
        val to = NodeId.next()
        ResourceRelation.entries.forEach { kind ->
            val edge = ResourceEdge(from, to, kind)
            assertEquals(from, edge.from)
            assertEquals(to, edge.to)
            assertEquals(kind, edge.kind)
        }
    }

    @Test
    fun `ResourceRelation has 5 values`() {
        assertEquals(5, ResourceRelation.entries.size)
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
        assertEquals(emptyList(), scope.trueDefinitions)
        assertEquals(emptyList(), scope.falseDefinitions)
    }

    // ========================================================================
    // LocalDefinition packing
    // ========================================================================

    @Test
    fun `packDefinitions and unpackDefinitions round-trip`() {
        val definitions = listOf(
            LocalDefinition(stmtOrdinal = 4, localNodeId = NodeId(10), constantNodeId = NodeId(20)),
            LocalDefinition(stmtOrdinal = 7, localNodeId = NodeId(10), constantNodeId = null),
            LocalDefinition(stmtOrdinal = 9, localNodeId = NodeId(10), constantNodeId = NodeId(21))
        )
        val packed = BranchScope.packDefinitions(definitions)
        assertTrue(packed.contentEquals(intArrayOf(4, 10, 20, 7, 10, BranchScope.NO_CONSTANT, 9, 10, 21)))
        assertEquals(definitions, BranchScope.unpackDefinitions(packed))
        assertTrue(definitions[0].isConstant)
        assertFalse(definitions[1].isConstant)
    }

    @Test
    fun `empty definitions pack to the shared empty array`() {
        assertTrue(BranchScope.packDefinitions(emptyList()) === BranchScope.EMPTY_DEFINITIONS)
        assertEquals(emptyList(), BranchScope.unpackDefinitions(BranchScope.EMPTY_DEFINITIONS))
    }

    @Test
    fun `unpackDefinitionTable expands per-local packed tables`() {
        assertEquals(emptyMap(), BranchScope.unpackDefinitionTable(emptyMap()))
        val table = BranchScope.unpackDefinitionTable(mapOf(10 to intArrayOf(4, 10, 20), 11 to BranchScope.EMPTY_DEFINITIONS))
        assertEquals(listOf(LocalDefinition(4, NodeId(10), NodeId(20))), table[NodeId(10)])
        assertEquals(emptyList(), table[NodeId(11)])
    }

    @Test
    fun `unpackDefinitions rejects a length that is not a multiple of the stride`() {
        assertFailsWith<IllegalArgumentException> { BranchScope.unpackDefinitions(intArrayOf(1, 2)) }
    }

    @Test
    fun `BranchScope carries definitions per side`() {
        val md = MethodDescriptor(TypeDescriptor("com.example.Foo"), "check", emptyList(), TypeDescriptor("boolean"))
        val definition = LocalDefinition(1, NodeId(2), NodeId(3))
        val scope = BranchScope(
            NodeId(0), md, BranchComparison(ComparisonOp.EQ, NodeId(3)),
            IntOpenHashSet(), IntOpenHashSet(),
            trueDefinitions = listOf(definition)
        )
        assertEquals(listOf(definition), scope.trueDefinitions)
        assertEquals(emptyList(), scope.falseDefinitions)
        assertEquals(1, definition.stmtOrdinal)
        assertEquals(NodeId(2), definition.localNodeId)
        assertEquals(NodeId(3), definition.constantNodeId)
    }
}
