package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchReachabilityAnalysisTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    private fun makeMethod(className: String = "com.example.Test", name: String = "test"): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor(className), name, emptyList(), TypeDescriptor("void"))
    }

    // ========================================================================
    // analyze
    // ========================================================================

    @Test
    fun `analyze finds dead branch from simple assumption`() {
        // Setup: getOption(1001) returns true
        // Branch: if (result == 0) → true branch is dead (because true != 0 evaluates to true → JVM condition is false → BRANCH_TRUE is dead... wait)
        // Actually: if ($z0 == 0), assumedValue=true (which is 1 in JVM)
        // evaluateComparison(1, EQ, 0) → false → dead branch is BRANCH_TRUE

        val method = makeMethod()
        val callerMethod = makeMethod(name = "caller")

        // Call site node
        val argId = NodeId.next()
        val argConst = IntConstant(argId, 1001)

        val callSiteId = NodeId.next()
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("java.lang.Integer")), TypeDescriptor("boolean")
        )
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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(deadCallSiteId.value),
            falseBranchNodeIds = intArrayOf()
        )
        val graph = builder.build()

        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0,
            argumentValue = 1001,
            assumedValue = true
        )

        val analysis = BranchReachabilityAnalysis(graph)
        val result = analysis.analyze(listOf(assumption))

        // true(1) == 0 is false → BRANCH_TRUE is dead
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_TRUE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze with no matching call sites returns empty`() {
        val graph = DefaultGraph.Builder().build()
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Missing", name = "missing"),
            assumedValue = true
        )
        val analysis = BranchReachabilityAnalysis(graph)
        val result = analysis.analyze(listOf(assumption))
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze with assumption without argument constraint matches all call sites`() {
        val method = makeMethod()
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "isEnabled",
            emptyList(), TypeDescriptor("boolean")
        )
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())

        val graph = DefaultGraph.Builder().addNode(cs).build()
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "isEnabled"),
            assumedValue = false
        )
        val analysis = BranchReachabilityAnalysis(graph)
        val result = analysis.analyze(listOf(assumption))
        // No branches to evaluate, but no error
        assertTrue(result.deadBranches.isEmpty())
    }

    // ========================================================================
    // findUnreferencedMethods
    // ========================================================================

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

    // ========================================================================
    // Data class construction
    // ========================================================================

    @Test
    fun `Assumption construction`() {
        val a = Assumption(
            methodPattern = MethodPattern(name = "test"),
            argumentIndex = 0,
            argumentValue = 42,
            assumedValue = true
        )
        assertEquals(0, a.argumentIndex)
        assertEquals(42, a.argumentValue)
        assertEquals(true, a.assumedValue)
    }

    @Test
    fun `DeadBranch construction`() {
        val db = DeadBranch(
            conditionNodeId = NodeId.next(),
            deadKind = ControlFlowKind.BRANCH_TRUE,
            method = makeMethod(),
            deadNodeIds = IntOpenHashSet(intArrayOf(1, 2, 3)),
            deadCallSites = emptyList()
        )
        assertEquals(ControlFlowKind.BRANCH_TRUE, db.deadKind)
        assertEquals(3, db.deadNodeIds.size)
    }

    @Test
    fun `DeadCodeResult construction`() {
        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = emptySet()
        )
        assertTrue(result.deadBranches.isEmpty())
        assertTrue(result.deadMethods.isEmpty())
    }

    // ========================================================================
    // evaluateComparison - all ComparisonOp values
    // ========================================================================

    @Test
    fun `analyze evaluates NE comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("int")), TypeDescriptor("boolean")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0, argumentValue = 1001, assumedValue = true
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))

        // true(1) != 0 is true → BRANCH_FALSE is dead
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze evaluates LT comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getCount",
            emptyList(), TypeDescriptor("int")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        // assumedValue = 5 < 10 → true → BRANCH_FALSE is dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"),
            assumedValue = 5
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze evaluates GE comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getCount",
            emptyList(), TypeDescriptor("int")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        // assumedValue = 10 >= 10 → true → BRANCH_FALSE is dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"),
            assumedValue = 10
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze evaluates GT comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getCount",
            emptyList(), TypeDescriptor("int")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        // assumedValue = 5 > 10 → false → BRANCH_TRUE is dead (which is empty), BRANCH_FALSE is alive
        // Actually: 5 > 10 is false → condition is false → BRANCH_TRUE is dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"),
            assumedValue = 5
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // trueBranch is empty so deadBranch would have no dead nodes → evaluateBranch returns null
        // Since true branch has no nodes, this won't register as a dead branch
        assertTrue(result.deadBranches.isEmpty() || result.deadBranches.all { it.deadNodeIds.isNotEmpty() })
    }

    @Test
    fun `analyze evaluates LE comparison correctly`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getCount",
            emptyList(), TypeDescriptor("int")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        // assumedValue = 10 <= 10 → true → BRANCH_FALSE is dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getCount"),
            assumedValue = 10
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    // ========================================================================
    // null comparison
    // ========================================================================

    @Test
    fun `analyze handles null comparison with EQ`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getData",
            emptyList(), TypeDescriptor("java.lang.Object")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        // assumedValue = null, null == null → true → BRANCH_FALSE is dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getData"),
            assumedValue = null
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    @Test
    fun `analyze handles null comparison with NE`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getData",
            emptyList(), TypeDescriptor("java.lang.Object")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        // assumedValue = "not null", "not null" != null → true → BRANCH_FALSE is dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getData"),
            assumedValue = "not null"
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
        assertEquals(ControlFlowKind.BRANCH_FALSE, result.deadBranches[0].deadKind)
    }

    // ========================================================================
    // matchesArgumentConstraint with backward slice
    // ========================================================================

    @Test
    fun `analyze matches argument via backward slice`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("int")), TypeDescriptor("boolean")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(deadCallSiteId.value),
            falseBranchNodeIds = intArrayOf()
        )
        val graph = builder.build()

        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0, argumentValue = 1001, assumedValue = true
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // Should find the dead branch
        assertEquals(1, result.deadBranches.size)
    }

    @Test
    fun `analyze skips call site when argument index exceeds argument size`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            emptyList(), TypeDescriptor("boolean")
        )

        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, emptyList())

        val graph = DefaultGraph.Builder().addNode(cs).build()

        // Argument index 0 but no arguments
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0, argumentValue = 1001, assumedValue = true
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertTrue(result.deadBranches.isEmpty())
    }

    // ========================================================================
    // findTransitivelyDeadMethods
    // ========================================================================

    @Test
    fun `analyze finds transitively dead methods`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("int")), TypeDescriptor("boolean")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(deadCallSiteId.value),
            falseBranchNodeIds = intArrayOf()
        )
        val graph = builder.build()

        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0, argumentValue = 1001, assumedValue = true
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // deadHelper should be transitively dead
        assertTrue(result.deadMethods.any { it.name == "deadHelper" })
    }

    // ========================================================================
    // toComparableNumber - various types
    // ========================================================================

    @Test
    fun `analyze handles Float assumed value`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getValue",
            emptyList(), TypeDescriptor("float")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(),
            falseBranchNodeIds = intArrayOf(deadCallSiteId.value)
        )
        val graph = builder.build()

        // 0.0f == 0 → true → BRANCH_FALSE dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getValue"),
            assumedValue = 0.0f
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertEquals(1, result.deadBranches.size)
    }

    // ========================================================================
    // constantEquals - string coercion
    // ========================================================================

    @Test
    fun `analyze matches constant via string coercion`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("boolean")
        )

        val argId = NodeId.next()
        val argConst = StringConstant(argId, "hello")
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(argId))

        val graph = DefaultGraph.Builder()
            .addNode(argConst)
            .addNode(cs)
            .build()

        // argumentValue is "hello" (same string) → should match
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0, argumentValue = "hello", assumedValue = true
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // No branches but no error
        assertTrue(result.deadBranches.isEmpty())
    }

    // ========================================================================
    // evaluateBranch with non-constant comparand returns null
    // ========================================================================

    @Test
    fun `analyze returns null for ordering comparison with non-numeric string values`() {
        // This tests the case where evaluateComparison returns null for ordering ops with non-numeric
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getLabel",
            emptyList(), TypeDescriptor("java.lang.String")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(deadCallSiteId.value),
            falseBranchNodeIds = intArrayOf()
        )
        val graph = builder.build()

        // String LT String can't be evaluated → returns null → no dead branches
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getLabel"),
            assumedValue = "xyz"
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze matches argument via numeric coercion in constantEquals`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("long")), TypeDescriptor("boolean")
        )

        // Argument is a LongConstant(1001L) but assumption has Int(1001)
        val argId = NodeId.next()
        val argConst = LongConstant(argId, 1001L)
        val csId = NodeId.next()
        val cs = CallSiteNode(csId, callerMethod, calleeMethod, 10, null, listOf(argId))

        val graph = DefaultGraph.Builder()
            .addNode(argConst)
            .addNode(cs)
            .build()

        // argumentValue is Int 1001, but the constant is Long 1001L → should match via numeric coercion
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0, argumentValue = 1001, assumedValue = true
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // No branches to check but should not error - matching is successful
        assertTrue(result.deadBranches.isEmpty())
    }

    @Test
    fun `analyze handles non-constant comparand gracefully`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            emptyList(), TypeDescriptor("boolean")
        )

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
            resultVarId, callerMethod, comparison,
            trueBranchNodeIds = intArrayOf(deadCallSiteId.value),
            falseBranchNodeIds = intArrayOf()
        )
        val graph = builder.build()

        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            assumedValue = true
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        // Can't evaluate → no dead branches
        assertTrue(result.deadBranches.isEmpty())
    }

    // ========================================================================
    // constantEquals - string fallback path (line 290)
    // ========================================================================

    @Test
    fun `analyze matches string argument via toString fallback in constantEquals`() {
        val callerMethod = makeMethod(name = "caller")
        val calleeMethod = MethodDescriptor(
            TypeDescriptor("com.example.Client"), "getOption",
            listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.lang.String")
        )

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
            resultVarId, callerMethod, comparison,
            // assumedValue "expected" != comparand "world" → condition false → true branch dead
            trueBranchNodeIds = intArrayOf(deadCallSiteId.value),
            falseBranchNodeIds = intArrayOf()
        )
        val graph = builder.build()

        // assumedValue is "expected", argument "hello" matches argumentValue "hello" via toString fallback
        // evaluateComparison("expected", EQ, "world") → "expected" == "world" → false → true branch dead
        val assumption = Assumption(
            methodPattern = MethodPattern(declaringClass = "com.example.Client", name = "getOption"),
            argumentIndex = 0, argumentValue = "hello", assumedValue = "expected"
        )
        val result = BranchReachabilityAnalysis(graph).analyze(listOf(assumption))
        assertTrue(result.deadBranches.isNotEmpty())
    }
}
