package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Test cases for analyzing nested type structures.
 *
 * Goal: Given a method that returns ApiResponse<PageData<User>>, analyze:
 * 1. The return type structure (ApiResponse with nested PageData with nested User)
 * 2. What actual types are assigned to Object fields (e.g., metadata, extra)
 * 3. What actual types are used for generic type parameters
 *
 * This is different from method call tracing - it's about understanding
 * the complete type structure of returned objects.
 */
class NestedTypeAnalysisTest {

    // ========== Type Hierarchy Query Tests ==========

    /**
     * Test the new findTypeHierarchy query API.
     */
    @Test
    fun `should use findTypeHierarchy query for simple response`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.nested.NestedTypeService"
                    name = "getUserResponse"
                }
            }
        }

        println("=== Type Hierarchy Query: getUserResponse ===")
        results.forEach { result ->
            println(result.toTreeString())
        }

        assertTrue(results.isNotEmpty(), "Should find getUserResponse method")

        val result = results.first()
        assertTrue(
            result.returnStructures.any { it.className.contains("ApiResponse") },
            "Should find ApiResponse as return type"
        )

        // Check if metadata field is analyzed
        val apiResponse = result.returnStructures.firstOrNull { it.className.contains("ApiResponse") }
        if (apiResponse != null && apiResponse.fields.isNotEmpty()) {
            println("\nFields found in ApiResponse:")
            apiResponse.fields.forEach { (name, field) ->
                println("  $name: declared=${field.declaredType.className}, actual=${field.actualTypes.map { it.className }}")
            }

            val metadataField = apiResponse.fields["metadata"]
            if (metadataField != null) {
                assertTrue(
                    metadataField.actualTypes.any { it.className.contains("RequestMetadata") },
                    "metadata field should be assigned RequestMetadata"
                )
            }
        }
    }

    /**
     * Test type hierarchy for nested generic types.
     */
    @Test
    fun `should analyze nested type hierarchy for PageData`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.nested.NestedTypeService"
                    name = "getUserListResponse"
                }
            }
        }

        println("=== Type Hierarchy Query: getUserListResponse ===")
        println("Expected: ApiResponse<PageData<User>>")
        println()
        results.forEach { result ->
            println(result.toTreeString())
        }

        assertTrue(results.isNotEmpty(), "Should find getUserListResponse method")

        val result = results.first()
        assertTrue(
            result.returnStructures.any { it.className.contains("ApiResponse") },
            "Should find ApiResponse as return type"
        )

        // Verify the nested structure
        val apiResponse = result.returnStructures.firstOrNull { it.className.contains("ApiResponse") }
        if (apiResponse != null) {
            println("\n=== Detailed Field Analysis ===")
            printTypeStructure(apiResponse, "")
        }
    }

    /**
     * Test type hierarchy for conditional Object field assignments.
     */
    @Test
    fun `should find multiple types for conditional Object field`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.nested.NestedTypeService"
                    name = "getUserDetailResponse"
                }
            }
        }

        println("=== Type Hierarchy Query: getUserDetailResponse ===")
        println("Expected: profile field can be AdminInfo or ProfileInfo")
        println()
        results.forEach { result ->
            println(result.toTreeString())
        }

        assertTrue(results.isNotEmpty(), "Should find getUserDetailResponse method")
    }

    // ========== Legacy Tests (for comparison) ==========

    /**
     * Current capability test: Can we at least find the top-level return type?
     */
    @Test
    fun `should find top-level return type for simple generic response`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.nested.NestedTypeService"
                    name = "getUserResponse"
                }
            }
        }

        println("=== Simple Generic Response (findActualReturnTypes) ===")
        results.forEach { result ->
            println("Method: ${result.method.name}()")
            println("  Declared: ${result.declaredType.className}")
            println("  Actual types: ${result.actualTypes.map { it.className }}")
        }

        assertTrue(results.isNotEmpty(), "Should find getUserResponse method")

        val actualTypes = results.first().actualTypes.map { it.className }
        assertTrue(
            actualTypes.any { it.contains("ApiResponse") },
            "Should find ApiResponse as return type. Found: $actualTypes"
        )
    }

    /**
     * Test: Can we find actual types assigned to Object fields?
     */
    @Test
    fun `should find actual types assigned to Object fields`() {
        val graph = loadGraph()

        // Find all call sites to setMetadata(Object) in getUserResponse
        val callSites = graph.callSites(MethodPattern(
            name = "setMetadata"
        )).filter {
            it.caller.name == "getUserResponse"
        }.toList()

        println("=== Object Field Assignment Analysis ===")
        println("Found ${callSites.size} calls to setMetadata in getUserResponse")

        val assignedTypes = mutableSetOf<String>()

        callSites.forEach { callSite ->
            println("\nCall site: ${callSite.callee.signature}")
            if (callSite.arguments.isNotEmpty()) {
                val argNodeId = callSite.arguments[0]
                findTypesForNode(argNodeId, graph, assignedTypes, mutableSetOf())
            }
        }

        println("\nTypes assigned to metadata field: $assignedTypes")

        assertTrue(
            assignedTypes.any { it.contains("RequestMetadata") },
            "Should find RequestMetadata assigned to metadata. Found: $assignedTypes"
        )
    }

    /**
     * Test: Find all types assigned to Object fields across multiple methods.
     */
    @Test
    fun `should find all types assigned to Object field across methods`() {
        val graph = loadGraph()

        // Find all setMetadata calls
        val callSites = graph.callSites(MethodPattern(
            declaringClass = "sample.nested.NestedTypeService\$ApiResponse",
            name = "setMetadata"
        )).toList()

        println("=== All metadata field assignments ===")
        println("Found ${callSites.size} calls to setMetadata")

        val metadataTypes = mutableMapOf<String, MutableSet<String>>()

        callSites.forEach { callSite ->
            val callerMethod = callSite.caller.name
            val types = metadataTypes.getOrPut(callerMethod) { mutableSetOf() }

            if (callSite.arguments.isNotEmpty()) {
                val argNodeId = callSite.arguments[0]
                findTypesForNode(argNodeId, graph, types, mutableSetOf())
            }
        }

        println("\nMetadata types by method:")
        metadataTypes.forEach { (method, types) ->
            println("  $method: $types")
        }

        // Verify expected types
        assertTrue(
            metadataTypes["getUserResponse"]?.any { it.contains("RequestMetadata") } == true,
            "getUserResponse should assign RequestMetadata"
        )
        assertTrue(
            metadataTypes["getOrderResponse"]?.any { it.contains("ResponseMetadata") } == true,
            "getOrderResponse should assign ResponseMetadata"
        )
    }

    /**
     * Test: Find all possible types for Object fields with conditional assignment.
     */
    @Test
    fun `should find multiple types for conditionally assigned Object field`() {
        val graph = loadGraph()

        println("=== Conditional Object Field Assignment ===")
        println("Method: getUserDetailResponse()")
        println("profile field can be: AdminInfo or ProfileInfo")
        println()

        // Find setProfile calls
        val setProfileCalls = graph.callSites(MethodPattern(
            name = "setProfile"
        )).filter {
            it.caller.name == "getUserDetailResponse"
        }.toList()

        println("Found ${setProfileCalls.size} calls to setProfile")

        val profileTypes = mutableSetOf<String>()
        setProfileCalls.forEach { callSite ->
            if (callSite.arguments.isNotEmpty()) {
                findTypesForNode(callSite.arguments[0], graph, profileTypes, mutableSetOf())
            }
        }

        println("Types assigned to profile: $profileTypes")

        // Should find both AdminInfo and ProfileInfo
        assertTrue(
            profileTypes.any { it.contains("AdminInfo") },
            "Should find AdminInfo. Found: $profileTypes"
        )
        assertTrue(
            profileTypes.any { it.contains("ProfileInfo") },
            "Should find ProfileInfo. Found: $profileTypes"
        )
    }

    /**
     * Test: Verify 10-level deeply nested generic type analysis.
     * L1<L2<L3<L4<L5<L6<L7<L8<L9<L10>>>>>>>>>>
     */
    @Test
    fun `should analyze 10-level deeply nested generic types`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.nested.DeepNestedTypeService"
                    name = "getDeepNestedResponse"
                }
                // Use higher maxDepth to allow 10 levels of nesting
                // (depth is consumed ~2x per level due to field + generic analysis)
                config { copy(maxDepth = 25) }
            }
        }

        println("=== 10-Level Deep Nested Type Analysis ===")
        println("Expected: L1<L2<L3<L4<L5<L6<L7<L8<L9<L10>>>>>>>>>>")
        println()

        assertTrue(results.isNotEmpty(), "Should find getDeepNestedResponse method")

        val result = results.first()
        assertTrue(
            result.returnStructures.any { it.className.contains("L1") },
            "Should find L1 as return type"
        )

        // Print the full nested structure
        result.returnStructures.forEach { structure ->
            println("Return type structure:")
            printDeepTypeStructure(structure, 0)
        }

        // Verify we can trace down to at least L10 (10 levels)
        val maxDepthReached = measureTypeDepth(result.returnStructures.first())
        println("\nMax depth reached: $maxDepthReached levels")
        assertTrue(
            maxDepthReached >= 10,
            "Should reach at least 10 levels of nesting. Reached: $maxDepthReached"
        )
    }

    /**
     * Test: Verify 5-level nested type for comparison.
     */
    @Test
    fun `should analyze 5-level nested generic types`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.nested.DeepNestedTypeService"
                    name = "getMediumNestedResponse"
                }
            }
        }

        println("=== 5-Level Nested Type Analysis ===")
        println("Expected: L1<L2<L3<L4<L5<String>>>>>")
        println()

        assertTrue(results.isNotEmpty(), "Should find getMediumNestedResponse method")

        val result = results.first()
        result.returnStructures.forEach { structure ->
            println("Return type structure:")
            printDeepTypeStructure(structure, 0)
        }

        val maxDepthReached = measureTypeDepth(result.returnStructures.first())
        println("\nMax depth reached: $maxDepthReached levels")
        assertTrue(
            maxDepthReached >= 5,
            "Should reach at least 5 levels of nesting. Reached: $maxDepthReached"
        )
    }

    /**
     * Test: Analyze Result<T, E> either pattern.
     */
    @Test
    fun `should analyze Result either pattern with success and failure types`() {
        val graph = loadGraph()

        println("=== Result Either Pattern ===")
        println("Method: getResultWrapper()")
        println("Returns: Result<User, ErrorInfo>")
        println()

        val graphite = Graphite.from(graph)
        val results = graphite.query {
            findActualReturnTypes {
                method {
                    declaringClass = "sample.nested.NestedTypeService"
                    name = "getResultWrapper"
                }
            }
        }

        println("Return types found:")
        results.forEach { result ->
            println("  ${result.actualTypes.map { it.className }}")
        }

        val actualTypes = results.flatMap { it.actualTypes }.map { it.className }
        assertTrue(
            actualTypes.any { it.contains("Result") },
            "Should find Result type. Found: $actualTypes"
        )
    }

    // ========== Helper functions ==========

    private fun printDeepTypeStructure(structure: TypeStructure, level: Int) {
        val indent = "  ".repeat(level)
        val typeArgs = if (structure.typeArguments.isNotEmpty()) {
            "<${structure.typeArguments.values.joinToString(", ") { it.formatTypeName() }}>"
        } else ""
        println("${indent}Level $level: ${structure.type.simpleName}$typeArgs")

        // Print type arguments recursively
        structure.typeArguments.forEach { (_, argStructure) ->
            printDeepTypeStructure(argStructure, level + 1)
        }

        // Print field values recursively
        structure.fields.forEach { (name, field) ->
            field.actualTypes.forEach { actualType ->
                if (actualType.className.contains("\$L") || actualType.className == "java.lang.String") {
                    printDeepTypeStructure(actualType, level + 1)
                }
            }
        }
    }

    private fun measureTypeDepth(structure: TypeStructure): Int {
        var maxDepth = 1

        // Check type arguments
        structure.typeArguments.forEach { (_, argStructure) ->
            val argDepth = 1 + measureTypeDepth(argStructure)
            if (argDepth > maxDepth) {
                maxDepth = argDepth
            }
        }

        // Check fields for nested types
        structure.fields.forEach { (_, field) ->
            field.actualTypes.forEach { actualType ->
                if (actualType.className.contains("\$L") || actualType.className == "java.lang.String") {
                    val fieldDepth = 1 + measureTypeDepth(actualType)
                    if (fieldDepth > maxDepth) {
                        maxDepth = fieldDepth
                    }
                }
            }
        }

        return maxDepth
    }

    private fun printTypeStructure(structure: TypeStructure, indent: String) {
        println("${indent}${structure.formatTypeName()}")
        structure.fields.forEach { (name, field) ->
            val actualTypesStr = if (field.actualTypes.isEmpty()) {
                ""
            } else {
                " → ${field.actualTypes.joinToString(", ") { it.formatTypeName() }}"
            }
            println("$indent  ├── $name: ${field.declaredType.simpleName}$actualTypesStr")
            field.actualTypes.forEach { actualType ->
                if (actualType.fields.isNotEmpty()) {
                    printTypeStructure(actualType, "$indent  │   ")
                }
            }
        }
    }

    private fun findTypesForNode(
        nodeId: NodeId,
        graph: Graph,
        types: MutableSet<String>,
        visited: MutableSet<NodeId>
    ) {
        if (nodeId in visited) return
        visited.add(nodeId)

        val node = graph.node(nodeId)
        when (node) {
            is LocalVariable -> {
                val typeName = node.type.className
                if (typeName != "java.lang.Object" && typeName != "unknown") {
                    types.add(typeName)
                }
                // Continue tracing backward
                graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
                    findTypesForNode(edge.from, graph, types, visited)
                }
            }
            is CallSiteNode -> {
                val returnType = node.callee.returnType.className
                if (returnType != "java.lang.Object" && returnType != "void") {
                    types.add(returnType)
                }
            }
            is ConstantNode -> {
                // Skip constants for type analysis
            }
            else -> {
                graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
                    findTypesForNode(edge.from, graph, types, visited)
                }
            }
        }
    }

    private fun loadGraph(): Graph {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.nested"),
            buildCallGraph = false
        ))

        return loader.load(testClassesDir)
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
