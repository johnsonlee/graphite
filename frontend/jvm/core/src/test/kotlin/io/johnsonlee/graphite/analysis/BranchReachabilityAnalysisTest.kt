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
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class BranchReachabilityAnalysisTest : BranchReachabilityAnalysisTestSupport() {
    @Test
    fun `analyze finds dead branch from simple assumption`() {
        // Setup: getOption(1001) returns true
        // With a true assumed value, comparing the result to zero makes the true branch dead.
        // Actually: if ($z0 == 0), assumedValue=true (which is 1 in JVM)
        // evaluateComparison(1, EQ, 0) → false → dead branch is BRANCH_TRUE

        val method = makeMethod()
        val callerMethod = makeMethod(name = "caller")

        // Call site node
        val argId = NodeId.next()
        val argConst = IntConstant(argId, 1001)

        val callSiteId = NodeId.next()
        val calleeMethod =
            MethodDescriptor(
                TypeDescriptor("com.example.Client"), "getOption", listOf(TypeDescriptor("java.lang.Integer")), TypeDescriptor("boolean"))
        val callSite = CallSiteNode(callSiteId, callerMethod, calleeMethod, 10, null, listOf(argId))

        // Result variable
        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "z0", TypeDescriptor("boolean"), callerMethod)

        // Constant comparand (0)
        val zeroId = NodeId.next()
        val zero = IntConstant(zeroId, 0)

        // DataFlow edge from call site to result var
        val dfEdge = DataFlowEdge(callSiteId, resultVarId, DataFlowKind.ASSIGN)

        // A dead call site in the true branch
        val deadCallee = makeMethod(name = "deadCode")
        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, deadCallee, 20, null, emptyList())

        // Branch scope
        val comparison = BranchComparison(ComparisonOp.EQ, zeroId)

        val builder = DefaultGraph.Builder()
        builder.addNode(argConst)
        builder.addNode(callSite)
        builder.addNode(resultVar)
        builder.addNode(zero)
        builder.addNode(deadCallSite)
        builder.addEdge(dfEdge)
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(deadCallSiteId.value), falseBranchNodeIds = intArrayOf())
        val graph = builder.build()

        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = 1001,
                assumedValue = true)

        val analysis = BranchReachabilityAnalysis(graph)
        val result = analysis.analyze(listOf(assumption))

        // true(1) == 0 is false → BRANCH_TRUE is dead
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_TRUE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze with no matching call sites returns empty`() {
        val graph = DefaultGraph.Builder().build()
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Missing", name = "missing"), assumedValue = true)
        val analysis = BranchReachabilityAnalysis(graph)
        val result = analysis.analyze(listOf(assumption))
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze with assumption without argument constraint matches all call sites`() {
        val method = makeMethod()
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "isEnabled", emptyList(), TypeDescriptor("boolean"))
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())

        val graph = DefaultGraph.Builder().addNode(cs).build()
        val assumption =
            Assumption(methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "isEnabled"), assumedValue = false)
        val analysis = BranchReachabilityAnalysis(graph)
        val result = analysis.analyze(listOf(assumption))
        // No branches to evaluate, but no error
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `findUnreferencedMethods returns methods never called`() {
        val referencedMethod = makeMethod(name = "referenced")
        val unreferencedMethod = makeMethod(name = "unreferenced")
        val callerMethod = makeMethod(name = "caller")

        val cs = CallSiteNode(NodeId.next(), callerMethod, referencedMethod, 10, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(cs)
        builder.addMethod(referencedMethod)
        builder.addMethod(unreferencedMethod)
        builder.addMethod(callerMethod)
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedMethods()

        // unreferenced and caller are never called by any call site's callee
        // referenced is called, so it should not be in the set
        assertTrue(unreferenced.any { it.name == "unreferenced" })
        assertTrue(unreferenced.none { it.name == "referenced" })
    }

    @Test
    fun `findUnreferencedMethods skips constructors`() {
        val init = MethodDescriptor(TypeDescriptor("Foo"), "<init>", emptyList(), TypeDescriptor("void"))
        val clinit = MethodDescriptor(TypeDescriptor("Foo"), "<clinit>", emptyList(), TypeDescriptor("void"))

        val builder = DefaultGraph.Builder()
        builder.addMethod(init)
        builder.addMethod(clinit)
        val graph = builder.build()

        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedMethods()
        assertTrue(unreferenced.isEmpty())
    }

    @Test
    fun `Assumption construction`() {
        val a = Assumption(methodPattern = MethodPattern(name = "test"), argumentIndex = 0, argumentValue = 42, assumedValue = true)
        assertEquals(0, a.argumentIndex)
        assertEquals(42, a.argumentValue)
        assertEquals(true, a.assumedValue)
    }

    @Test
    fun `DeadBranch construction`() {
        val db =
            DeadBranch(
                conditionNodeId = NodeId.next(),
                deadKind = ControlFlowKind.BRANCH_TRUE,
                method = makeMethod(),
                deadNodeIds = IntOpenHashSet(intArrayOf(1, 2, 3)),
                deadCallSites = emptyList())
        assertEquals(ControlFlowKind.BRANCH_TRUE, db.deadKind)
        assertEquals(3, db.deadNodeIds.size)
    }

    @Test
    fun `DeadCodeResult construction`() {
        val result =
            DeadCodeResult(
                deadBranches = emptyList(), deadMethods = emptySet(), deadCallSites = emptySet(), unreferencedMethods = emptySet())
        assertTrue(result.deadBranches.isEmpty())
        assertTrue(result.deadMethods.isEmpty())
    }

    @Test
    fun `analyze matches argument via backward slice`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod =
            MethodDescriptor(TypeDescriptor("com.example.Client"), "getOption", listOf(TypeDescriptor("int")), TypeDescriptor("boolean"))

        // Constant flows through a local variable to the call site arg
        val constId = NodeId.next()
        val varId = NodeId.next()
        val constant = IntConstant(constId, 1001)
        val variable = LocalVariable(varId, "optId", TypeDescriptor("int"), callerMethod)

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(varId))

        val resultVarId = NodeId.next()
        val resultVar = LocalVariable(resultVarId, "z0", TypeDescriptor("boolean"), callerMethod)
        val zeroId = NodeId.next()
        val zero = IntConstant(zeroId, 0)

        val comparison = BranchComparison(ComparisonOp.EQ, zeroId)

        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, makeMethod(name = "dead"), 20, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(constant)
        builder.addNode(variable)
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(zero)
        builder.addNode(deadCallSite)
        builder.addEdge(DataFlowEdge(constId, varId, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(deadCallSiteId.value), falseBranchNodeIds = intArrayOf())
        val graph = builder.build()

        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = 1001,
                assumedValue = true)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // Should find the dead branch
        assertEquals(1, result.deadBranches.size)
    }

    @Test
    fun `analyze skips call site when argument index exceeds argument size`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(TypeDescriptor("com.example.Client"), "getOption", emptyList(), TypeDescriptor("boolean"))

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())

        val graph = DefaultGraph.Builder().addNode(cs).build()

        // Argument index 0 but no arguments
        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = 1001,
                assumedValue = true)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze finds transitively dead methods`() {
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
        val comparison = BranchComparison(ComparisonOp.EQ, zeroId)

        // Dead method: only called from the dead branch
        val deadMethod = makeMethod("com.example.Client", "deadHelper")
        val deadCallSiteId = NodeId.next()
        val deadCallSite = CallSiteNode(deadCallSiteId, callerMethod, deadMethod, 20, null, emptyList())

        // Another call site inside deadHelper that calls another method
        val innerMethod = makeMethod("com.example.Client", "innerHelper")
        val innerCsId = NodeId.next()
        val innerCs = CallSiteNode(innerCsId, deadMethod, innerMethod, 30, null, emptyList())

        val builder = DefaultGraph.Builder()
        builder.addNode(argConst)
        builder.addNode(cs)
        builder.addNode(resultVar)
        builder.addNode(zero)
        builder.addNode(deadCallSite)
        builder.addNode(innerCs)
        builder.addEdge(DataFlowEdge(csId, resultVarId, DataFlowKind.ASSIGN))
        builder.addBranchScope(
            resultVarId, callerMethod, comparison, trueBranchNodeIds = intArrayOf(deadCallSiteId.value), falseBranchNodeIds = intArrayOf())
        val graph = builder.build()

        val assumption =
            Assumption(
                methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
                argumentIndex = 0,
                argumentValue = 1001,
                assumedValue = true)
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // deadHelper should be transitively dead
        assertTrue(result.deadMethods.any { it.name == "deadHelper" })
    }
}
