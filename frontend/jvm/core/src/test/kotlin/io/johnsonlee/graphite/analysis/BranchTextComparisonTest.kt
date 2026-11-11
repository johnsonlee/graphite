package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import kotlin.test.Test
import kotlin.test.assertEquals

class BranchTextComparisonTest {
    @Test
    fun `argument assumption matches scalar text using established conversion`() {
        val caller = MethodDescriptor(TypeDescriptor("Owner"), "caller", emptyList(), TypeDescriptor("void"))
        val callee = caller.copy(name = "read", parameterTypes = listOf(TypeDescriptor("java.lang.String")),
            returnType = TypeDescriptor("boolean"))
        val call = CallSiteNode(NodeId(1), caller, callee, null, null, listOf(NodeId(2)))
        val text = StringConstant(NodeId(2), "42")
        val graph = DefaultGraph.Builder().addNode(call).addNode(text).addNode(IntConstant(NodeId(5), 0))
            .addNode(StringConstant(NodeId(3), "true branch")).addNode(StringConstant(NodeId(4), "false branch"))
            .addBranchScope(call.id, caller, BranchComparison(ComparisonOp.EQ, NodeId(5)),
                trueBranchNodeIds = intArrayOf(3), falseBranchNodeIds = intArrayOf(4)).build()
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(
            Assumption(MethodPattern(name = "read"), argumentIndex = 0, argumentValue = 42, assumedValue = true)))
        assertEquals(listOf(ControlFlowKind.BRANCH_TRUE), result.deadBranches.map { it.deadKind })
        assertEquals(setOf(3), result.deadBranches.single().deadNodeIds.toIntArray().toSet())
    }
}
