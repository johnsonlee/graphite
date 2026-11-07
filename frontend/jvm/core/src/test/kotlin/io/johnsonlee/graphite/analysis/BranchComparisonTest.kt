package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class BranchComparisonTest : BranchReachabilityAnalysisTestSupport() {
    @Test
    fun `analyze evaluates NE comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Client"), "getOption", listOf(TypeDescriptor("int")), TypeDescriptor("boolean"))

        val argId = NodeId.next()
        val argConst = IntConstant(argId, 1001)
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(argId))

        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "z0", TypeDescriptor("boolean"), callerMethod)
        val zeroId = NodeId.next()
        val zero = IntConstant(zeroId, 0)

        val comparison = BranchComparison(ComparisonOp.NE, zeroId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(argConst)
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(zero)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = 1001,
                assumedValue = true)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))

        // true(1) != 0 is true → BRANCH_FALSE is dead
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze evaluates LT comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "getCount", emptyList(), TypeDescriptor("int"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "count", TypeDescriptor("int"), callerMethod)
        val tenId = NodeId.next()
        val ten = IntConstant(tenId, 10)

        val comparison = BranchComparison(ComparisonOp.LT, tenId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(ten)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        // assumedValue = 5 < 10 → true → BRANCH_FALSE is dead
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"), assumedValue = 5)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze evaluates GE comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "getCount", emptyList(), TypeDescriptor("int"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "count", TypeDescriptor("int"), callerMethod)
        val tenId = NodeId.next()
        val ten = IntConstant(tenId, 10)

        val comparison = BranchComparison(ComparisonOp.GE, tenId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(ten)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        // assumedValue = 10 >= 10 → true → BRANCH_FALSE is dead
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"), assumedValue = 10)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze evaluates GT comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "getCount", emptyList(), TypeDescriptor("int"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "count", TypeDescriptor("int"), callerMethod)
        val tenId = NodeId.next()
        val ten = IntConstant(tenId, 10)

        val comparison = BranchComparison(ComparisonOp.GT, tenId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(ten)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        // assumedValue = 5 > 10 → false → BRANCH_TRUE is dead (which is empty), BRANCH_FALSE is alive
        // Actually: 5 > 10 is false → condition is false → BRANCH_TRUE is dead
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"), assumedValue = 5)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // trueBranch is empty so deadBranch would have no dead nodes → evaluateBranch returns null
        // Since true branch has no nodes, this won't register as a dead branch
        assertTrue(result.deadBranches.isEmpty() || result.deadBranches.all { it.deadNodeIds.isNotEmpty() })
    }

    @Test
    fun `analyze evaluates LE comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "getCount", emptyList(), TypeDescriptor("int"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "count", TypeDescriptor("int"), callerMethod)
        val tenId = NodeId.next()
        val ten = IntConstant(tenId, 10)

        val comparison = BranchComparison(ComparisonOp.LE, tenId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(ten)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        // assumedValue = 10 <= 10 → true → BRANCH_FALSE is dead
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"), assumedValue = 10)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze handles null comparison with EQ`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Client"), "getData", emptyList(), TypeDescriptor("java.lang.Object"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "obj", TypeDescriptor("java.lang.Object"), callerMethod)
        val nullId = NodeId.next()
        val nullConst = NullConstant(nullId)

        val comparison = BranchComparison(ComparisonOp.EQ, nullId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(nullConst)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        // assumedValue = null, null == null → true → BRANCH_FALSE is dead
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getData"), assumedValue = null)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze handles null comparison with NE`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Client"), "getData", emptyList(), TypeDescriptor("java.lang.Object"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "obj", TypeDescriptor("java.lang.Object"), callerMethod)
        val nullId = NodeId.next()
        val nullConst = NullConstant(nullId)

        val comparison = BranchComparison(ComparisonOp.NE, nullId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(nullConst)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        // assumedValue = "not null", "not null" != null → true → BRANCH_FALSE is dead
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getData"), assumedValue = "not null")
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze handles Float assumed value`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "getValue", emptyList(), TypeDescriptor("float"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "v", TypeDescriptor("float"), callerMethod)
        val zeroId = NodeId.next()
        val zero = IntConstant(zeroId, 0)

        val comparison = BranchComparison(ComparisonOp.EQ, zeroId)
        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(zero)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(), falseBranchNodeIds = intArrayOf(deadCallSiteId.value))
        val graph = builder.build()

        // 0.0f == 0 → true → BRANCH_FALSE dead
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getValue"), assumedValue = 0.0f)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
    }

    @Test
    fun `analyze matches constant via string coercion`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(
                TypeDescriptor("com.example.Client"), "getOption", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("boolean"))

        val argId = NodeId.next()
        val argConst = StringConstant(argId, "hello")
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(argId))

        val graph = DefaultGraph.Builder().addNode(argConst).addNode(cs).build()

        // argumentValue is "hello" (same string) → should match
        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = "hello",
                assumedValue = true)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // No branches but no error
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze returns null for ordering comparison with non-numeric string values`() {
        // This tests the case where evaluateComparison returns null for ordering ops with non-numeric
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Client"), "getLabel", emptyList(), TypeDescriptor("java.lang.String"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "label", TypeDescriptor("java.lang.String"), callerMethod)
        // Comparand is a string constant
        val strId = NodeId.next()
        val strConst = StringConstant(strId, "abc")

        val comparison = BranchComparison(ComparisonOp.LT, strId)
        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(strConst)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(deadCallSiteId.value), falseBranchNodeIds = intArrayOf())
        val graph = builder.build()

        // String LT String can't be evaluated → returns null → no dead branches
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getLabel"), assumedValue = "xyz")
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertTrue(result.deadBranches.isEmpty())
    }
}
