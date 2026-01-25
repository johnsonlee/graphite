package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test that validates the REST API return type analysis use case.
 *
 * Original pain point:
 * - Controller methods often declare return type as Object, ResponseEntity<?>, or other generic types
 * - Need to find the ACTUAL types being returned for i18n/API documentation purposes
 *
 * This test uses compiled sample code (sample.controller.UserController) that has methods
 * returning Object but actually returning UserDTO, OrderDTO, etc.
 */
class ReturnTypeAnalysisTest {

    /**
     * Expected mappings from method name to actual return types in UserController.java
     */
    private val expectedReturnTypes = mapOf(
        "getUser" to setOf("sample.controller.UserDTO"),
        "getUserWithError" to setOf("sample.controller.UserDTO", "sample.controller.ErrorResponse"),
        "getOrder" to setOf("sample.controller.OrderDTO"),
        "getUserViaLocalVariable" to setOf("sample.controller.UserDTO"),
        "getUserViaMethodCall" to setOf("sample.controller.UserDTO")
    )

    @Test
    fun `should find actual return types for methods declared as Object`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controller"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Find methods in UserController that return Object
        val objectReturnMethods = graph.methods(MethodPattern(
            declaringClass = "sample.controller.UserController",
            returnType = "java.lang.Object"
        )).toList()

        println("Found ${objectReturnMethods.size} methods returning Object in UserController")

        val actualReturnTypes = mutableMapOf<String, MutableSet<String>>()

        objectReturnMethods.forEach { method ->
            println("\nAnalyzing ${method.name}():")
            println("  Declared return type: ${method.returnType.className}")

            val types = mutableSetOf<String>()

            // Find return nodes in this method
            val returnNodes = graph.nodes<ReturnNode>()
                .filter { it.method.signature == method.signature }
                .toList()

            println("  Found ${returnNodes.size} return statements")

            returnNodes.forEach { returnNode ->
                // Get incoming dataflow edges to the return node and trace back to find actual types
                findActualTypesFromNode(returnNode.id, graph, types, visited = mutableSetOf())
            }

            actualReturnTypes[method.name] = types
        }

        println("\n=== Results ===")
        actualReturnTypes.forEach { (method, types) ->
            println("$method(): ${types.joinToString()}")
        }

        // Assertions that validate the use case
        assertTrue(objectReturnMethods.isNotEmpty(),
            "Should find methods returning Object")

        expectedReturnTypes.forEach { (methodName, expectedTypes) ->
            val foundTypes = actualReturnTypes[methodName] ?: emptySet()
            expectedTypes.forEach { expectedType ->
                assertTrue(foundTypes.contains(expectedType),
                    "Method $methodName should have actual return type $expectedType, found: $foundTypes")
            }
        }
    }

    @Test
    fun `should use query DSL to find actual return types`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controller"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Use the declarative query API
        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.controller.UserController"
                    returnType = "java.lang.Object"
                }
            }
        }

        println("Query results: ${results.size}")
        results.forEach { result ->
            println("  ${result.method.name}():")
            println("    Declared: ${result.declaredType.className}")
            println("    Actual: ${result.actualTypes.map { it.className }}")
        }

        // Verify each expected method has correct actual types
        expectedReturnTypes.forEach { (methodName, expectedTypes) ->
            val result = results.find { it.method.name == methodName }
            assertTrue(result != null, "Should find result for method $methodName")

            val foundTypes = result!!.actualTypes.map { it.className }.toSet()
            expectedTypes.forEach { expectedType ->
                assertTrue(foundTypes.contains(expectedType),
                    "Method $methodName should have actual return type $expectedType via Query DSL")
            }
        }
    }

    @Test
    fun `should find actual types in ResponseEntity body`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controller"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Find methods returning ResponseEntity<?>
        val responseEntityMethods = graph.methods(MethodPattern(
            declaringClass = "sample.controller.UserController"
        )).filter {
            it.returnType.className.contains("ResponseEntity")
        }.toList()

        println("Found ${responseEntityMethods.size} methods returning ResponseEntity")

        val bodyTypes = mutableMapOf<String, MutableSet<String>>()

        responseEntityMethods.forEach { method ->
            println("\nAnalyzing ${method.name}():")
            val types = mutableSetOf<String>()

            // Find calls to ResponseEntity.ok(body) or ResponseEntity.body(body)
            val okCallSites = graph.callSites(MethodPattern(
                declaringClass = "org.springframework.http.ResponseEntity",
                name = "ok"
            )).filter { it.caller.signature == method.signature }.toList()

            val bodyCallSites = graph.callSites(MethodPattern(
                declaringClass = "org.springframework.http.ResponseEntity\$BodyBuilder",
                name = "body"
            )).filter { it.caller.signature == method.signature }.toList()

            val allCallSites = okCallSites + bodyCallSites

            println("  Found ${allCallSites.size} calls to ResponseEntity.ok()/body()")

            allCallSites.forEach { callSite ->
                callSite.arguments.forEach { argId ->
                    // Get incoming dataflow edges to the argument
                    graph.incoming(argId, DataFlowEdge::class.java).forEach { edge ->
                        val sourceNode = graph.node(edge.from)
                        if (sourceNode is LocalVariable && sourceNode.type.className != "java.lang.Object") {
                            types.add(sourceNode.type.className)
                            println("    -> Body type: ${sourceNode.type.className}")
                        }
                    }
                    // Also check if the argument itself is a local variable
                    val argNode = graph.node(argId)
                    if (argNode is LocalVariable && argNode.type.className != "java.lang.Object") {
                        types.add(argNode.type.className)
                        println("    -> Body type (direct): ${argNode.type.className}")
                    }
                }
            }

            bodyTypes[method.name] = types
        }

        println("\n=== ResponseEntity Body Types ===")
        bodyTypes.forEach { (method, types) ->
            println("$method(): ${types.joinToString()}")
        }

        assertTrue(responseEntityMethods.isNotEmpty(),
            "Should find ResponseEntity methods")

        // getUserAsResponseEntity should have UserDTO as body type
        assertTrue(bodyTypes["getUserAsResponseEntity"]?.contains("sample.controller.UserDTO") == true,
            "getUserAsResponseEntity should have UserDTO as body type")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        // Handle both cases: running from root or from submodule directory
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }

    /**
     * Recursively trace back through dataflow edges to find actual types.
     * This handles cases where values flow through intermediate locals.
     */
    private fun findActualTypesFromNode(
        nodeId: NodeId,
        graph: Graph,
        types: MutableSet<String>,
        visited: MutableSet<NodeId>
    ) {
        if (nodeId in visited) return
        visited.add(nodeId)

        graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
            val sourceNode = graph.node(edge.from)
            when (sourceNode) {
                is LocalVariable -> {
                    val typeName = sourceNode.type.className
                    if (typeName != "java.lang.Object" && typeName != "unknown") {
                        types.add(typeName)
                        println("    -> Actual type from local: $typeName")
                    } else {
                        // Continue tracing back if type is unknown/Object
                        findActualTypesFromNode(edge.from, graph, types, visited)
                    }
                }
                is CallSiteNode -> {
                    val calleeReturnType = sourceNode.callee.returnType.className
                    if (calleeReturnType != "java.lang.Object" && calleeReturnType != "void") {
                        types.add(calleeReturnType)
                        println("    -> Actual type from method call: ${sourceNode.callee.name}() -> $calleeReturnType")
                    }
                }
                else -> {}
            }
        }
    }
}
