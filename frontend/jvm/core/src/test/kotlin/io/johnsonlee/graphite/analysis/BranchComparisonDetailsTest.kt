package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import kotlin.test.Test
import kotlin.test.assertTrue

internal class BranchComparisonDetailsTest : BranchReachabilityAnalysisTestSupport() {
    @Test
    fun `analyze matches argument via numeric coercion in constantEquals`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Client"), "getOption", listOf(TypeDescriptor("long")), TypeDescriptor("boolean"))

        // Argument is a LongConstant(1001L) but assumption has Int(1001)
        val argId = NodeId.next()
        val argConst = LongConstant(argId, 1001L)
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(argId))

        val graph = DefaultGraph.Builder().addNode(argConst).addNode(cs).build()

        // argumentValue is Int 1001, but the constant is Long 1001L → should match via numeric coercion
        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = 1001,
                assumedValue = true)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // No branches to check but should not error - matching is successful
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze handles non-constant comparand gracefully`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "getOption", emptyList(), TypeDescriptor("boolean"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "z0", TypeDescriptor("boolean"), callerMethod)

        // Comparand is a local variable, not a constant
        val comparandVarId = NodeId.next()
        val comparandVar = LocalVariable(comparandVarId, "threshold", TypeDescriptor("int"), callerMethod)

        val comparison = BranchComparison(ComparisonOp.EQ, comparandVarId)
        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(comparandVar)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(deadCallSiteId.value), falseBranchNodeIds = intArrayOf())
        val graph = builder.build()

        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"), assumedValue = true)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // Can't evaluate → no dead branches
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze matches string argument via toString fallback in constantEquals`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(
                TypeDescriptor("com.example.Client"),
                "getOption",
                listOf(TypeDescriptor("java.lang.String")),
                TypeDescriptor("java.lang.String"))

        // Argument is a StringConstant "hello" - matches via toString fallback since strings
        // are non-numeric, toComparableNumber returns null, so constantEquals falls to
        // a?.toString() == b?.toString() on line 290
        val argId = NodeId.next()
        val argConst = StringConstant(argId, "hello")
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(argId))

        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "z0", TypeDescriptor("java.lang.String"), callerMethod)

        // Comparand is also a string - evaluateComparison null/null nums → EQ fallback
        val comparandId = NodeId.next()
        val comparand = StringConstant(comparandId, "world")
        val comparison = BranchComparison(ComparisonOp.EQ, comparandId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(argConst)
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(comparand)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId,
            callerMethod,
            comparison,
            // assumedValue "expected" != comparand "world" → condition false → true branch dead
            trueBranchNodeIds = intArrayOf(deadCallSiteId.value),
            falseBranchNodeIds = intArrayOf())
        val graph = builder.build()

        // assumedValue is "expected", argument "hello" matches argumentValue "hello" via toString fallback
        // evaluateComparison("expected", EQ, "world") → "expected" == "world" → false → true branch dead
        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = "hello",
                assumedValue = "expected")
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertTrue(result.deadBranches.isNotEmpty())
    }
}
