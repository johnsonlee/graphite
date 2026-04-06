package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.*

/**
 * Tests for method resolution hierarchy, static method calls, constructor calls,
 * super method calls, interface default methods, and method overloading in SootUpAdapter.
 *
 * These tests verify actual resolved types and edge structures, not just existence.
 */
class MethodResolutionTest {

    companion object {
        private val graph: Graph by lazy {
            val classesDir = findTestClassesDir()
            JavaProjectLoader(LoaderConfig(
                includePackages = listOf("sample.resolution"),
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

    // -----------------------------------------------------------------------
    // Method resolution in class hierarchy
    // -----------------------------------------------------------------------

    @Test
    fun `call to inherited method resolves to defining class`() {
        // ConcreteService does NOT override process(), so resolveMethodDefiningClass
        // must walk up to AbstractService where process() is defined.
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.MethodResolutionExample" }
            .filter { it.callee.name == "process" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find call to process()")

        val callee = callSites.first().callee
        assertEquals(
            "sample.resolution.AbstractService", callee.declaringClass.className,
            "process() must resolve to AbstractService where it is defined, not ConcreteService"
        )
        assertEquals("process", callee.name)
        assertEquals(1, callee.parameterTypes.size, "process() takes exactly one parameter")
        assertEquals("int", callee.parameterTypes[0].className, "process() parameter is int")
    }

    @Test
    fun `call to overridden method in abstract class body`() {
        // AbstractService.process() calls this.transform(value) via invokevirtual.
        // transform() is first declared in AbstractService, so it should resolve there.
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.AbstractService" }
            .filter { it.caller.name == "process" }
            .filter { it.callee.name == "transform" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find call to transform() from AbstractService.process()")

        val callee = callSites.first().callee
        assertEquals(
            "sample.resolution.AbstractService", callee.declaringClass.className,
            "transform() should resolve to AbstractService where it is first declared"
        )
        assertEquals(1, callee.parameterTypes.size)
        assertEquals("int", callee.parameterTypes[0].className)
    }

    // -----------------------------------------------------------------------
    // Static method calls
    // -----------------------------------------------------------------------

    @Test
    fun `static method call has null receiver and correct callee`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.MethodResolutionExample" }
            .filter { it.caller.name == "useStatic" }
            .filter { it.callee.name == "computeStatic" }
            .toList()
        assertEquals(1, callSites.size, "Should find exactly one call to computeStatic()")

        val cs = callSites.first()
        assertNull(cs.receiver, "Static method call must have null receiver")
        assertEquals(
            "sample.resolution.MethodResolutionExample", cs.callee.declaringClass.className,
            "computeStatic() is declared in MethodResolutionExample"
        )
        assertEquals(
            listOf("int", "int"),
            cs.callee.parameterTypes.map { it.className },
            "computeStatic() takes (int, int)"
        )
    }

    // -----------------------------------------------------------------------
    // Constructor calls
    // -----------------------------------------------------------------------

    @Test
    fun `constructor call in createService has init callee for ConcreteService`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.MethodResolutionExample" }
            .filter { it.caller.name == "createService" }
            .filter { it.callee.name == "<init>" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find constructor call in createService()")

        val callee = callSites.first().callee
        assertEquals("<init>", callee.name, "Constructor callee must be named <init>")
        assertEquals(
            "sample.resolution.ConcreteService", callee.declaringClass.className,
            "Constructor must target ConcreteService"
        )
    }

    // -----------------------------------------------------------------------
    // Method overloading
    // -----------------------------------------------------------------------

    @Test
    fun `overloaded methods create exactly 3 distinct call sites with correct signatures`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.OverloadExample" }
            .filter { it.caller.name == "callOverloads" }
            .filter { it.callee.name == "compute" }
            .toList()
        assertEquals(3, callSites.size, "Should find exactly 3 calls to overloaded compute()")

        val paramSignatures = callSites.map { cs ->
            cs.callee.parameterTypes.map { it.className }
        }.toSet()
        assertEquals(
            setOf(listOf("int"), listOf("int", "int"), listOf("java.lang.String")),
            paramSignatures,
            "Must have exactly these three overload signatures"
        )
    }

    // -----------------------------------------------------------------------
    // Interface default method
    // -----------------------------------------------------------------------

    @Test
    fun `call to interface default method resolves to interface`() {
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.MethodResolutionExample" }
            .filter { it.caller.name == "useDefault" }
            .filter { it.callee.name == "greet" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find call to greet()")

        val callee = callSites.first().callee
        assertEquals(
            "sample.resolution.Greeter", callee.declaringClass.className,
            "greet() must resolve to Greeter interface where the default method is defined, not FormalGreeter"
        )
        assertEquals(
            listOf("java.lang.String"),
            callee.parameterTypes.map { it.className },
            "greet() takes a single String parameter"
        )
        assertEquals("java.lang.String", callee.returnType.className, "greet() returns String")
    }

    // -----------------------------------------------------------------------
    // Edge verification: DataFlowEdge (PARAMETER_PASS) from arguments to call sites
    // -----------------------------------------------------------------------

    @Test
    fun `PARAMETER_PASS edge exists from argument to call site parameter`() {
        // In handle(int input), the call service.process(input) should produce
        // a PARAMETER_PASS DataFlowEdge from the argument node to the parameter node.
        val callSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.MethodResolutionExample" }
            .filter { it.caller.name == "handle" }
            .filter { it.callee.name == "process" }
            .toList()
        assertTrue(callSites.isNotEmpty(), "Should find call to process()")

        val cs = callSites.first()
        // The call site should have arguments
        assertTrue(cs.arguments.isNotEmpty(), "Call to process(input) must have at least one argument node")

        // Check that outgoing edges from argument nodes include PARAMETER_PASS
        val paramPassEdges = cs.arguments.flatMap { argId ->
            graph.outgoing(argId)
                .filterIsInstance<DataFlowEdge>()
                .filter { it.kind == DataFlowKind.PARAMETER_PASS }
                .toList()
        }
        assertTrue(
            paramPassEdges.isNotEmpty(),
            "Should have PARAMETER_PASS edges from arguments of the process() call"
        )
    }

    // -----------------------------------------------------------------------
    // End-to-end: resolveMethodDefiningClass enables call chain traversal
    // -----------------------------------------------------------------------

    @Test
    fun `resolveMethodDefiningClass enables call chain traversal from Controller through AbstractService`() {
        // Step 1: Find the call site where MethodResolutionExample calls process()
        val controllerCallSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.MethodResolutionExample" }
            .filter { it.callee.name == "process" }
            .toList()
        assertTrue(controllerCallSites.isNotEmpty(), "Controller must call process()")

        // Step 2: Verify callee is AbstractService.process, NOT ConcreteService.process
        val callee = controllerCallSites.first().callee
        assertEquals(
            "sample.resolution.AbstractService", callee.declaringClass.className,
            "The callee must be AbstractService.process() - this proves resolveMethodDefiningClass works"
        )

        // Step 3: Find call sites where the caller IS AbstractService.process()
        // This is the key: because the callee was resolved to AbstractService.process(),
        // we can now follow the call chain INTO AbstractService.process() body and find
        // that it calls transform().
        val abstractServiceCallSites = graph.nodes<CallSiteNode>()
            .filter { it.caller.declaringClass.className == "sample.resolution.AbstractService" }
            .filter { it.caller.name == "process" }
            .toList()
        assertTrue(
            abstractServiceCallSites.isNotEmpty(),
            "AbstractService.process() must have call sites (it calls transform()) - " +
                "this proves the call chain can be followed through the resolved method"
        )

        // Step 4: Verify one of those call sites is transform()
        val transformCalls = abstractServiceCallSites.filter { it.callee.name == "transform" }
        assertTrue(
            transformCalls.isNotEmpty(),
            "AbstractService.process() must call transform() - completing the call chain"
        )

        // Step 5: Verify the full chain is traversable:
        //   MethodResolutionExample.handle() -> AbstractService.process() -> AbstractService.transform()
        // The callee of the controller call matches the caller of the abstract service calls
        assertEquals(
            callee.declaringClass.className,
            abstractServiceCallSites.first().caller.declaringClass.className,
            "Callee declaring class of controller call must match caller declaring class of inner calls"
        )
        assertEquals(
            callee.name,
            abstractServiceCallSites.first().caller.name,
            "Callee method name of controller call must match caller method name of inner calls"
        )
    }
}
