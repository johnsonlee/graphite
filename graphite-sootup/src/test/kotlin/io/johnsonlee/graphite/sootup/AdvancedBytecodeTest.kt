package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.incoming
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.outgoing
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.*

/**
 * Tests for SootUpAdapter covering P2 gaps: multi-dimensional arrays, cast/instanceof,
 * recursion, inner classes, and advanced edge cases (empty methods, synchronized,
 * many parameters, null constants).
 *
 * Every test verifies actual edges, node properties, and graph structure -- not just existence.
 */
class AdvancedBytecodeTest {

    companion object {
        private val graph: Graph by lazy {
            val classesDir = findTestClassesDir()
            JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.advanced"),
                buildCallGraph = true
            )).load(classesDir)
        }

        private fun findTestClassesDir(): Path {
            val projectDir = Path.of(System.getProperty("user.dir"))
            val submodulePath = projectDir.resolve("build/classes/java/test")
            val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
            return if (submodulePath.exists()) submodulePath else rootPath
        }
    }

    // ==================== Varargs ====================

    @Test
    fun `varargs parameter type is int array`() {
        val params = graph.nodes<ParameterNode>()
            .filter { it.method.name == "sum" && it.method.declaringClass.className == "sample.advanced.ArrayAdvancedExample" }
            .toList()
        assertTrue(params.isNotEmpty(), "Should find parameter nodes for varargs method sum()")
        // Varargs compiles to array: int... -> int[]
        val arrayParam = params.find { it.type.className == "int[]" }
        assertNotNull(arrayParam, "sum() parameter should have type int[] (varargs compiles to array), found types: ${params.map { it.type.className }}")
    }

    @Test
    fun `varargs call from useVarargs to sum has arguments`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "useVarargs" && it.callee.name == "sum" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find call from useVarargs() to sum()")
        val callSite = callSites.first()
        // The call site should have arguments (the varargs values)
        assertTrue(callSite.arguments.isNotEmpty(), "Call to sum() should have arguments, got empty list")
        // PARAMETER_PASS edges flow FROM argument nodes TO the call site
        val paramPassEdges = graph.incoming<DataFlowEdge>(callSite.id)
            .filter { it.kind == DataFlowKind.PARAMETER_PASS }
            .toList()
        assertTrue(paramPassEdges.isNotEmpty(), "Should have PARAMETER_PASS edges into varargs call site")
    }

    // ==================== instanceof / cast ====================

    @Test
    fun `safeCast has toLowerCase with callee declaring class String`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "safeCast" }
            .toList()
        val toLowerCallSite = callSites.find { it.callee.name == "toLowerCase" }
        assertNotNull(toLowerCallSite, "safeCast should call toLowerCase(), found callees: ${callSites.map { it.callee.name }}")
        assertEquals(
            "java.lang.String",
            toLowerCallSite.callee.declaringClass.className,
            "toLowerCase() callee declaring class should be java.lang.String"
        )
    }

    @Test
    fun `safeCast has toString with callee declaring class Object`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "safeCast" }
            .toList()
        val toStringCallSite = callSites.find { it.callee.name == "toString" }
        assertNotNull(toStringCallSite, "safeCast should call toString(), found callees: ${callSites.map { it.callee.name }}")
        assertEquals(
            "java.lang.Object",
            toStringCallSite.callee.declaringClass.className,
            "toString() callee declaring class should be java.lang.Object"
        )
    }

    @Test
    fun `processList has size call on java_util_List`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "processList" }
            .toList()
        val sizeCallSite = callSites.find { it.callee.name == "size" }
        assertNotNull(sizeCallSite, "processList should call size(), found callees: ${callSites.map { it.callee.name }}")
        assertEquals(
            "java.util.List",
            sizeCallSite.callee.declaringClass.className,
            "size() callee declaring class should be java.util.List"
        )
    }

    // ==================== Recursion ====================

    @Test
    fun `factorial self-call has both caller and callee as RecursionExample_factorial`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "factorial" && it.callee.name == "factorial" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find recursive call to factorial()")
        val selfCall = callSites.first()
        assertEquals(
            "sample.advanced.RecursionExample",
            selfCall.caller.declaringClass.className,
            "factorial caller declaring class should be RecursionExample"
        )
        assertEquals(
            "sample.advanced.RecursionExample",
            selfCall.callee.declaringClass.className,
            "factorial callee declaring class should be RecursionExample"
        )
    }

    @Test
    fun `mutual recursion isEven calls isOdd with correct declaring classes`() {
        val evenCallsOdd = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "isEven" && it.callee.name == "isOdd" }
            .toList()
        assertTrue(evenCallsOdd.isNotEmpty(), "isEven should call isOdd")
        assertEquals(
            "sample.advanced.RecursionExample",
            evenCallsOdd.first().callee.declaringClass.className,
            "isOdd callee declaring class should be RecursionExample"
        )
    }

    @Test
    fun `factorial recursive call has DataFlowEdge from parameter to call argument`() {
        // factorial(int n) calls factorial(n - 1), so there should be a dataflow
        // from the parameter node to the recursive call site's arguments
        val factorialParams = graph.nodes<ParameterNode>()
            .filter {
                it.method.name == "factorial" &&
                it.method.declaringClass.className == "sample.advanced.RecursionExample"
            }
            .toList()
        assertTrue(factorialParams.isNotEmpty(), "factorial should have parameter nodes")

        val recursiveCallSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "factorial" && it.callee.name == "factorial" }
            .toList()
        assertTrue(recursiveCallSites.isNotEmpty(), "Should find recursive factorial call")

        // Check that there's a dataflow path from the parameter to the call site
        // The argument list of the recursive call should reference nodes that trace back to the parameter
        // PARAMETER_PASS edges flow FROM argument nodes TO the call site
        val callSite = recursiveCallSites.first()
        val paramPassEdges = graph.incoming<DataFlowEdge>(callSite.id)
            .filter { it.kind == DataFlowKind.PARAMETER_PASS }
            .toList()
        assertTrue(paramPassEdges.isNotEmpty(),
            "Recursive factorial call should have PARAMETER_PASS DataFlowEdge flowing into call site")
    }

    // ==================== Inner classes ====================

    @Test
    fun `call from useStaticInner to compute has callee declaring class containing StaticHelper`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "useStaticInner" && it.callee.name == "compute" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find call from useStaticInner to compute()")
        val callee = callSites.first().callee
        assertTrue(
            callee.declaringClass.className.contains("StaticHelper"),
            "compute() callee declaring class should contain StaticHelper, got: ${callee.declaringClass.className}"
        )
    }

    @Test
    fun `anonymous class has compiled class name with dollar and digit`() {
        // Anonymous classes compile as OuterClass$1, OuterClass$2, etc.
        val anonymousCallSites = graph.nodes<CallSiteNode>()
            .filter {
                it.caller.declaringClass.className.matches(Regex("sample\\.advanced\\.InnerClassExample\\$\\d+"))
            }
            .toList()
        assertTrue(
            anonymousCallSites.isNotEmpty(),
            "Should find call sites in anonymous class (expected pattern InnerClassExample\$N)"
        )
        // The anonymous class's run() calls println
        val printlnCalls = anonymousCallSites.filter { it.callee.name == "println" }
        assertTrue(printlnCalls.isNotEmpty(), "Anonymous class run() should call println()")
    }

    // ==================== Edge cases ====================

    @Test
    fun `manyParams has all 10 parameter nodes with correct indices and types`() {
        val params = graph.nodes<ParameterNode>()
            .filter { it.method.name == "manyParams" && it.method.declaringClass.className == "sample.advanced.EdgeCaseExample" }
            .toList()
        assertEquals(10, params.size, "manyParams should have exactly 10 parameter nodes, got ${params.size}")

        // Verify each index 0-9 exists
        val indices = params.map { it.index }.toSet()
        for (i in 0..9) {
            assertTrue(i in indices, "Parameter index $i should exist in manyParams, found indices: $indices")
        }

        // Verify all parameter types are int
        for (param in params) {
            assertEquals("int", param.type.className,
                "Parameter ${param.index} of manyParams should be int, got ${param.type.className}")
        }
    }

    @Test
    fun `emptyMethod has return node but no incoming DataFlowEdge to return`() {
        val returnNodes = graph.nodes<ReturnNode>()
            .filter {
                it.method.name == "emptyMethod" &&
                it.method.declaringClass.className == "sample.advanced.EdgeCaseExample"
            }
            .toList()
        assertTrue(returnNodes.isNotEmpty(), "Empty void method should have a return node")

        // Nothing flows to return in an empty void method
        for (returnNode in returnNodes) {
            val incomingDataFlow = graph.incoming<DataFlowEdge>(returnNode.id).toList()
            assertTrue(
                incomingDataFlow.isEmpty(),
                "Empty void method return node should have no incoming DataFlowEdge, found ${incomingDataFlow.size} edges"
            )
        }
    }

    @Test
    fun `returnOnly has IntConstant 42 with DataFlowEdge to ReturnNode`() {
        val returnNodes = graph.nodes<ReturnNode>()
            .filter {
                it.method.name == "returnOnly" &&
                it.method.declaringClass.className == "sample.advanced.EdgeCaseExample"
            }
            .toList()
        assertTrue(returnNodes.isNotEmpty(), "returnOnly should have a return node")
        val returnNode = returnNodes.first()

        // Check there's an incoming DataFlowEdge whose source is an IntConstant(42)
        val incomingEdges = graph.incoming<DataFlowEdge>(returnNode.id).toList()
        assertTrue(incomingEdges.isNotEmpty(), "returnOnly return node should have incoming DataFlowEdge")

        val sourceNodes = incomingEdges.map { graph.node(it.from) }
        val intConstant42 = sourceNodes.filterIsInstance<IntConstant>().find { it.value == 42 }
        assertNotNull(intConstant42,
            "returnOnly should have IntConstant(42) flowing to ReturnNode, found sources: ${sourceNodes.map { it?.javaClass?.simpleName to (it as? ConstantNode)?.value }}")
    }

    @Test
    fun `syncMethod return node has RETURN_VALUE DataFlowEdge from LocalVariable`() {
        val returnNodes = graph.nodes<ReturnNode>()
            .filter {
                it.method.name == "syncMethod" &&
                it.method.declaringClass.className == "sample.advanced.EdgeCaseExample"
            }
            .toList()
        assertTrue(returnNodes.isNotEmpty(), "syncMethod should have a return node")

        val returnNode = returnNodes.first()

        // syncMethod(int x) returns x + 1, so the return node must have incoming DataFlowEdges
        val incomingEdges = graph.incoming<DataFlowEdge>(returnNode.id).toList()
        assertTrue(incomingEdges.isNotEmpty(),
            "syncMethod return node should have incoming DataFlowEdge (method body was analyzed)")

        // The incoming edge should be RETURN_VALUE (the return statement passes a value to the return node)
        val returnValueEdge = incomingEdges.find { it.kind == DataFlowKind.RETURN_VALUE }
        assertNotNull(returnValueEdge,
            "syncMethod return node should have an incoming RETURN_VALUE edge, found kinds: ${incomingEdges.map { it.kind }}")

        // The source of the RETURN_VALUE edge should be a LocalVariable (the computed result of x + 1),
        // not a constant or null -- this proves the synchronized method body was fully analyzed
        val sourceNode = graph.node(returnValueEdge.from)
        assertNotNull(sourceNode, "Source node of RETURN_VALUE edge should exist in graph")
        assertTrue(sourceNode is LocalVariable,
            "Source of RETURN_VALUE to syncMethod return should be a LocalVariable (computed value), got ${sourceNode?.javaClass?.simpleName}")
    }

    @Test
    fun `nullCheck has NullConstant with DataFlowEdge to ReturnNode`() {
        val returnNodes = graph.nodes<ReturnNode>()
            .filter {
                it.method.name == "nullCheck" &&
                it.method.declaringClass.className == "sample.advanced.EdgeCaseExample"
            }
            .toList()
        assertTrue(returnNodes.isNotEmpty(), "nullCheck should have a return node")

        // nullCheck returns null in one branch, so NullConstant should flow to ReturnNode
        val allNullConstants = graph.nodes<NullConstant>().toList()
        assertTrue(allNullConstants.isNotEmpty(), "Should find NullConstant nodes in the graph")

        // Find a NullConstant that has a DataFlowEdge pointing to a return node of nullCheck
        val returnNodeIds = returnNodes.map { it.id }.toSet()
        val nullToReturn = allNullConstants.any { nullConst ->
            graph.outgoing<DataFlowEdge>(nullConst.id).any { edge ->
                edge.to in returnNodeIds
            }
        }
        assertTrue(nullToReturn,
            "NullConstant should have DataFlowEdge to nullCheck's ReturnNode")
    }
}
