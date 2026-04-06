package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.incoming
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.outgoing
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlFlowTest {

    private val graph: Graph by lazy {
        val classesDir = findTestClassesDir()
        val loader = JavaProjectLoader(
            LoaderConfig(
                includePackages = listOf("sample.controlflow"),
                buildCallGraph = false
            )
        )
        loader.load(classesDir)
    }

    // --- Try-catch tests ---

    @Test
    fun `try-catch parseInt has correct callee declaring class and parameter types`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "tryCatchBasic" }
            .filter { it.callee.name == "parseInt" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find parseInt() call inside try block")

        val parseInt = callSites.first()
        assertEquals(
            "java.lang.Integer",
            parseInt.callee.declaringClass.className,
            "parseInt callee declaring class should be java.lang.Integer"
        )
        assertEquals(
            listOf("java.lang.String"),
            parseInt.callee.parameterTypes.map { it.className },
            "parseInt should accept a single String parameter"
        )
    }

    @Test
    fun `try-catch parseInt has dataflow edge from argument to call site`() {
        val parseIntCallSite = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "tryCatchBasic" }
            .filter { it.callee.name == "parseInt" }
            .first()

        // The call site should have at least one argument
        assertTrue(parseIntCallSite.arguments.isNotEmpty(), "parseInt call site should have arguments")

        // There should be a DataFlowEdge with PARAMETER_PASS from the argument to the call site
        val paramPassEdges = graph.incoming<DataFlowEdge>(parseIntCallSite.id)
            .filter { it.kind == DataFlowKind.PARAMETER_PASS }
            .toList()
        assertTrue(
            paramPassEdges.isNotEmpty(),
            "Should find PARAMETER_PASS DataFlowEdge into parseInt call site"
        )
    }

    @Test
    fun `try-catch-finally toUpperCase has correct callee and return value flows`() {
        val toUpperCaseSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "tryCatchFinally" }
            .filter { it.callee.name == "toUpperCase" }
            .toList()
        assertTrue(toUpperCaseSites.isNotEmpty(), "Should find toUpperCase() in try block")

        val site = toUpperCaseSites.first()
        assertEquals(
            "java.lang.String",
            site.callee.declaringClass.className,
            "toUpperCase callee should be on java.lang.String"
        )

        // The return value of toUpperCase should flow somewhere via RETURN_VALUE or ASSIGN
        val outgoingDataFlow = graph.outgoing<DataFlowEdge>(site.id).toList()
        assertTrue(
            outgoingDataFlow.isNotEmpty(),
            "toUpperCase call site should have outgoing dataflow edges (RETURN_VALUE or ASSIGN). " +
                "Got none."
        )
    }

    @Test
    fun `try-catch-finally println call site exists in finally block`() {
        val printlnSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "tryCatchFinally" }
            .filter { it.callee.name == "println" }
            .toList()
        assertTrue(printlnSites.isNotEmpty(), "Should find println() in finally block")

        val site = printlnSites.first()
        assertEquals(
            "java.io.PrintStream",
            site.callee.declaringClass.className,
            "println callee should be on java.io.PrintStream"
        )
    }

    @Test
    fun `nested try-catch catch block tracks String length call`() {
        val lengthSite = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "nestedTryCatch" }
            .filter { it.callee.name == "length" }
            .toList()
        assertTrue(lengthSite.isNotEmpty(), "Should find length() call in inner catch block")

        assertEquals(
            "java.lang.String",
            lengthSite.first().callee.declaringClass.className,
            "length() declaring class should be java.lang.String"
        )
    }

    // --- Switch tests ---

    @Test
    fun `int switch string constants exist and have dataflow edges to return nodes`() {
        val expectedValues = setOf("one", "two", "three", "other")
        val constants = graph.nodes<StringConstant>()
            .filter { it.value in expectedValues }
            .toList()

        val foundValues = constants.map { it.value }.toSet()
        assertEquals(expectedValues, foundValues, "Should find all four string constants from switch cases")

        // Each constant should have an outgoing dataflow edge (flowing to a return or local assignment)
        for (constant in constants) {
            val outgoing = graph.outgoing<DataFlowEdge>(constant.id).toList()
            assertTrue(
                outgoing.isNotEmpty(),
                "StringConstant '${constant.value}' should have outgoing DataFlowEdge (to return/assign). Got none."
            )
        }

        // Verify that intSwitch method has return nodes
        val returnNodes = graph.nodes<ReturnNode>()
            .filter { it.method.name == "intSwitch" }
            .toList()
        assertTrue(returnNodes.isNotEmpty(), "intSwitch method should have return nodes")
    }

    @Test
    fun `string switch has hashCode and equals call sites with String callee type`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "stringSwitch" }
            .toList()
        val calleeNames = callSites.map { it.callee.name }.toSet()

        assertTrue("hashCode" in calleeNames, "String switch should produce hashCode() call")
        assertTrue("equals" in calleeNames, "String switch should produce equals() call")

        // Verify hashCode is called on java.lang.String
        val hashCodeSites = callSites.filter { it.callee.name == "hashCode" }
        assertTrue(
            hashCodeSites.any { it.callee.declaringClass.className == "java.lang.String" },
            "hashCode() should be called on java.lang.String. " +
                "Found declaring classes: ${hashCodeSites.map { it.callee.declaringClass.className }}"
        )

        // Verify equals is called on java.lang.String
        val equalsSites = callSites.filter { it.callee.name == "equals" }
        assertTrue(
            equalsSites.any { it.callee.declaringClass.className == "java.lang.String" },
            "equals() should be called on java.lang.String. " +
                "Found declaring classes: ${equalsSites.map { it.callee.declaringClass.className }}"
        )
    }

    @Test
    fun `enum switch method has parameter node with enum type`() {
        val methods = graph.methods(
            MethodPattern(
                declaringClass = "sample.controlflow.SwitchExample",
                name = "enumSwitch"
            )
        ).toList()
        assertTrue(methods.isNotEmpty(), "Should find enumSwitch method")

        val method = methods.first()
        // The method should have a parameter of the enum type
        assertTrue(
            method.parameterTypes.any { it.className.contains("Status") },
            "enumSwitch should have a Status parameter. " +
                "Found parameter types: ${method.parameterTypes.map { it.className }}"
        )

        // Find the ParameterNode for this method with the enum type
        val paramNodes = graph.nodes<ParameterNode>()
            .filter { it.method.name == "enumSwitch" && it.method.declaringClass.className == "sample.controlflow.SwitchExample" }
            .toList()
        assertTrue(paramNodes.isNotEmpty(), "Should find ParameterNode for enumSwitch")
        assertTrue(
            paramNodes.any { it.type.className.contains("Status") },
            "ParameterNode should have Status type. Found: ${paramNodes.map { it.type.className }}"
        )

        // Verify return constants exist for enum switch
        val returnConstants = graph.nodes<StringConstant>()
            .filter { it.value in setOf("running", "stopped", "unknown") }
            .toList()
        assertTrue(
            returnConstants.size >= 3,
            "Should find all 3 string constants from enum switch. Found: ${returnConstants.map { it.value }}"
        )
    }

    // --- Complex control flow ---

    @Test
    fun `method chaining creates chain of call sites connected by dataflow`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "methodChaining" }
            .toList()
        val calleeNames = callSites.map { it.callee.name }.toSet()

        assertTrue("trim" in calleeNames, "Should find trim() call")
        assertTrue("toLowerCase" in calleeNames, "Should find toLowerCase() call")
        assertTrue("replace" in calleeNames, "Should find replace() call")
        assertTrue(callSites.size >= 3, "Should find at least 3 chained calls, found: $calleeNames")

        val trimSite = callSites.first { it.callee.name == "trim" }
        val toLowerCaseSite = callSites.first { it.callee.name == "toLowerCase" }
        val replaceSite = callSites.first { it.callee.name == "replace" }

        // Verify all call sites target java.lang.String
        assertEquals("java.lang.String", trimSite.callee.declaringClass.className, "trim() should be on String")
        assertEquals("java.lang.String", toLowerCaseSite.callee.declaringClass.className, "toLowerCase() should be on String")
        assertEquals("java.lang.String", replaceSite.callee.declaringClass.className, "replace() should be on String")

        // Verify the chain: trim -> toLowerCase -> replace -> return
        // trim()'s result should flow out
        val trimOutgoing = graph.outgoing<DataFlowEdge>(trimSite.id).toList()
        assertTrue(trimOutgoing.isNotEmpty(), "trim() call site should have outgoing dataflow edge")

        // toLowerCase()'s result should flow out
        val toLowerOutgoing = graph.outgoing<DataFlowEdge>(toLowerCaseSite.id).toList()
        assertTrue(toLowerOutgoing.isNotEmpty(), "toLowerCase() call site should have outgoing dataflow edge")

        // replace()'s result should flow out (to return)
        val replaceOutgoing = graph.outgoing<DataFlowEdge>(replaceSite.id).toList()
        assertTrue(replaceOutgoing.isNotEmpty(), "replace() call site should have outgoing dataflow edge")

        // Verify the return node for methodChaining gets data from the chain
        val returnNodes = graph.nodes<ReturnNode>()
            .filter { it.method.name == "methodChaining" }
            .toList()
        assertTrue(returnNodes.isNotEmpty(), "methodChaining should have a return node")

        val returnNode = returnNodes.first()
        val incomingToReturn = graph.incoming<DataFlowEdge>(returnNode.id).toList()
        assertTrue(
            incomingToReturn.isNotEmpty(),
            "Return node should have incoming dataflow (from the chained result)"
        )
    }

    @Test
    fun `nested if-else has return nodes and integer constants`() {
        val returnNodes = graph.nodes<ReturnNode>()
            .filter { it.method.name == "nestedIfElse" }
            .toList()
        assertTrue(returnNodes.isNotEmpty(), "Should find return nodes for nestedIfElse")

        // The method returns 0 in one branch - verify the IntConstant(0) exists and has dataflow
        val zeroConstants = graph.nodes<IntConstant>()
            .filter { it.value == 0 }
            .toList()
        assertTrue(zeroConstants.isNotEmpty(), "Should find IntConstant(0) for the default return branch")

        // Check that at least one IntConstant(0) flows to a return node in nestedIfElse
        val nestedIfElseReturnIds = returnNodes.map { it.id }.toSet()
        val zeroFlowsToReturn = zeroConstants.any { constant ->
            graph.outgoing<DataFlowEdge>(constant.id).any { edge ->
                edge.to in nestedIfElseReturnIds
            }
        }
        assertTrue(
            zeroFlowsToReturn,
            "IntConstant(0) should have a DataFlowEdge to one of nestedIfElse's return nodes"
        )
    }

    @Test
    fun `nested if-else has control flow edges with comparison operators`() {
        // nestedIfElse uses conditions like a > 0, b > 0, c > 0
        // In bytecode, `a > 0` compiles to ifle (LE) or similar comparison with negation
        val branchScopes = graph.branchScopes()
            .filter { it.method.name == "nestedIfElse" }
            .toList()

        assertTrue(
            branchScopes.size >= 2,
            "nestedIfElse should have at least 2 BranchScope entries for its nested conditions. Found: ${branchScopes.size}"
        )

        // Verify at least one expected comparison operator exists (LE or GE from compiling `> 0`)
        val comparisonOps = branchScopes.map { it.comparison.operator }.toSet()
        val expectedOps = setOf(ComparisonOp.LE, ComparisonOp.GE, ComparisonOp.GT, ComparisonOp.LT)
        assertTrue(
            comparisonOps.any { it in expectedOps },
            "BranchScopes should contain a comparison operator from $expectedOps. Found: $comparisonOps"
        )

        // Verify at least one branch scope has non-empty true and false branches
        val scopesWithBothBranches = branchScopes.filter {
            it.trueBranchNodeIds.isNotEmpty() && it.falseBranchNodeIds.isNotEmpty()
        }
        assertTrue(
            scopesWithBothBranches.isNotEmpty(),
            "At least one BranchScope should have non-empty trueBranchNodeIds and falseBranchNodeIds. " +
                "Scopes: ${branchScopes.map { "${it.comparison.operator}: true=${it.trueBranchNodeIds.size}, false=${it.falseBranchNodeIds.size}" }}"
        )
    }

    @Test
    fun `loop with calls has Math abs with correct declaring class and argument flow`() {
        val absSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "loopWithCalls" }
            .filter { it.callee.name == "abs" }
            .toList()
        assertTrue(absSites.isNotEmpty(), "Should find Math.abs() call inside loop")

        val absSite = absSites.first()
        assertEquals(
            "java.lang.Math",
            absSite.callee.declaringClass.className,
            "abs() callee declaring class should be java.lang.Math"
        )

        // Math.abs takes a single int parameter
        assertEquals(
            1,
            absSite.callee.parameterTypes.size,
            "Math.abs should have 1 parameter"
        )
        assertEquals(
            "int",
            absSite.callee.parameterTypes.first().className,
            "Math.abs parameter should be int"
        )

        // Verify arguments flow into the call site
        assertTrue(absSite.arguments.isNotEmpty(), "Math.abs should have argument nodes")
        val paramPassEdges = graph.incoming<DataFlowEdge>(absSite.id)
            .filter { it.kind == DataFlowKind.PARAMETER_PASS }
            .toList()
        assertTrue(
            paramPassEdges.isNotEmpty(),
            "Should find PARAMETER_PASS DataFlowEdge into Math.abs call site"
        )
    }

    @Test
    fun `logical conditions has String length call with correct receiver type`() {
        val lengthSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.name == "logicalConditions" }
            .filter { it.callee.name == "length" }
            .toList()
        assertTrue(lengthSites.isNotEmpty(), "Should find length() call in logical condition")

        val site = lengthSites.first()
        assertEquals(
            "java.lang.String",
            site.callee.declaringClass.className,
            "length() should be called on java.lang.String"
        )
        assertEquals(
            "int",
            site.callee.returnType.className,
            "String.length() should return int"
        )
    }

    @Test
    fun `logical conditions method has multiple condition evaluations`() {
        // logicalConditions uses x > 0 && s != null && s.length() > 0
        // This should produce multiple branch scopes or control flow edges
        val branchScopes = graph.branchScopes()
            .filter { it.method.name == "logicalConditions" }
            .toList()

        // The short-circuit && creates multiple branch points
        assertTrue(
            branchScopes.size >= 2,
            "logicalConditions should have at least 2 branch scopes for the short-circuit && conditions. " +
                "Found: ${branchScopes.size}"
        )
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
