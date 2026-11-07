package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class MmapGraphBuilderDetailsTest : MmapGraphBuilderTestSupport() {
    @Test
    fun `branchScopes() and branchScopesFor() work`() {
        val (graph, ids, _) = buildTestGraph()

        val allScopes = graph.branchScopes().toList()
        assertEquals(1, allScopes.size)
        val scope = allScopes[0]
        assertEquals(ids["bool1"], scope.conditionNodeId)
        assertEquals(ComparisonOp.EQ, scope.comparison.operator)
        assertEquals(ids["int2"]!!.value, scope.comparison.comparandNodeId.value)
        assertTrue(scope.trueBranchNodeIds.contains(ids["call1"]!!.value))
        assertTrue(scope.falseBranchNodeIds.contains(ids["call2"]!!.value))

        // branchScopesFor specific node
        val scopesForBool1 = graph.branchScopesFor(ids["bool1"]!!).toList()
        assertEquals(1, scopesForBool1.size)

        // branchScopesFor non-condition node
        val scopesForInt1 = graph.branchScopesFor(ids["int1"]!!).toList()
        assertEquals(0, scopesForInt1.size)
    }

    @Test
    fun `typeHierarchyTypes() returns all types with hierarchy info`() {
        val (graph, _, _) = buildTestGraph()

        val types = graph.typeHierarchyTypes()
        assertTrue(types.contains("com.example.ServiceImpl"))
        assertTrue(types.contains("com.example.Service"))
        assertTrue(types.contains("com.example.Runnable"))
        assertTrue(types.contains("com.example.AdvancedService"))
        // Types not in hierarchy should not appear
        assertTrue(!types.contains("com.example.Main"))
    }

    @Test
    fun `graph data persists on disk after builder completes`() {
        // Build a graph, then verify it works -- the builder's temp buffers are gone
        // but the disk files remain accessible
        val id1 = NodeId.next()
        val id2 = NodeId.next()
        val graph =
            MmapGraphBuilder()
                .addNode(IntConstant(id1, 111))
                .addNode(StringConstant(id2, "persisted"))
                .addEdge(DataFlowEdge(id1, id2, DataFlowKind.ASSIGN))
                .addMethod(makeMethod("com.example.Persist", "test"))
                .addTypeRelation(TypeDescriptor("com.example.Child"), TypeDescriptor("com.example.Parent"), TypeRelation.EXTENDS)
                .addEnumValues("com.example.Level", "HIGH", listOf(3))
                .build()

        // Multiple reads to verify disk data is stable
        repeat(3) {
            assertEquals(111, (graph.node(id1) as IntConstant).value)
            assertEquals("persisted", (graph.node(id2) as StringConstant).value)
            assertEquals(1, graph.outgoing(id1).count())
            assertEquals(1, graph.incoming(id2).count())
            assertEquals(1, graph.methods(MethodPattern(name = "test")).count())
            assertEquals(listOf(TypeDescriptor("com.example.Parent")), graph.supertypes(TypeDescriptor("com.example.Child")).toList())
            assertEquals(listOf(3), graph.enumValues("com.example.Level", "HIGH"))
        }
    }
}
