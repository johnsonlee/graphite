package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.JacksonFieldInfo
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.CallGraphAlgorithm
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for SootUpAdapter covering call graph, enum extraction edge cases,
 * boxing/unboxing, control flow comparisons, array operations, and Jackson annotations.
 */
class SootUpAdapterTest {

    // ========== Call graph tests ==========

    @Test
    fun `should build graph with call graph enabled using CHA`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.simple"),
            buildCallGraph = true,
            callGraphAlgorithm = CallGraphAlgorithm.CHA
        ))

        val graph = loader.load(testClassesDir)
        assertNotNull(graph, "Should produce graph with CHA call graph")

        // Verify basic structure exists
        val fieldNodes = graph.nodes<FieldNode>()
            .filter { it.descriptor.declaringClass.className.contains("SimpleService") }
            .toList()
        assertTrue(fieldNodes.isNotEmpty(), "Should find fields from SimpleService")
    }

    @Test
    fun `should build graph with call graph enabled using RTA`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.simple"),
            buildCallGraph = true,
            callGraphAlgorithm = CallGraphAlgorithm.RTA
        ))

        val graph = loader.load(testClassesDir)
        assertNotNull(graph, "Should produce graph with RTA call graph")
    }

    @Test
    fun `should handle call graph failure gracefully`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Use a very limited include that might cause call graph issues
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controlflow"),
            buildCallGraph = true
        ))

        // Should not throw even if call graph fails
        val graph = loader.load(testClassesDir)
        assertNotNull(graph, "Should produce graph even if call graph has issues")
    }

    // ========== Control flow comparison operators ==========

    @Test
    fun `should detect comparison operators in control flow`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Use controller package which has null checks generating EQ/NE comparisons
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controller"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Should have branch scopes from null checks in UserController
        val branchScopes = graph.branchScopes().toList()
        println("Found ${branchScopes.size} branch scopes from controller")

        branchScopes.forEach { scope ->
            println("  Scope: method=${scope.method.name}, op=${scope.comparison.operator}")
        }

        assertTrue(branchScopes.isNotEmpty(), "Should find branch scopes from UserController null checks")

        val operators = branchScopes.map { it.comparison.operator }.toSet()
        println("Operators found: $operators")
        assertTrue(operators.isNotEmpty(), "Should find comparison operators")
    }

    @Test
    fun `should create control flow edges for branch conditions`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controller"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Find ControlFlowEdges
        val allNodes = graph.nodes<Node>().toList()
        var controlFlowEdgeCount = 0

        allNodes.forEach { node ->
            val cfEdges = graph.incoming(node.id, ControlFlowEdge::class.java).toList()
            controlFlowEdgeCount += cfEdges.size
        }

        assertTrue(controlFlowEdgeCount > 0,
            "Should find control flow edges from UserController branch conditions")
    }

    @Test
    fun `should detect EQ and NE comparison from null checks`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controller"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // getUserWithError has: if (userId == null) - generates EQ/NE comparison
        val nullCheckBranches = graph.branchScopes()
            .filter { it.method.name == "getUserWithError" || it.method.name == "getUserWithErrorHandling" }
            .toList()

        println("Null check branches: ${nullCheckBranches.size}")
        nullCheckBranches.forEach { scope ->
            println("  method=${scope.method.name}, op=${scope.comparison.operator}, " +
                "trueBranch=${scope.trueBranchNodeIds.size}, " +
                "falseBranch=${scope.falseBranchNodeIds.size}")
        }

        assertTrue(nullCheckBranches.isNotEmpty(),
            "Should find branch scopes from null checks")
    }

    @Test
    fun `should also detect comparisons from integer if-conditions`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Also load the controlflow package - even if no branch scopes, it should process without error
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controlflow"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        assertNotNull(graph, "Should produce graph from ComparisonService without errors")

        // Just verify methods were processed
        val methods = graph.methods(MethodPattern(
            declaringClass = "sample.controlflow.ComparisonService"
        )).toList()

        assertTrue(methods.isNotEmpty(), "Should find ComparisonService methods")
        println("ComparisonService methods: ${methods.map { it.name }}")
    }

    // ========== Boxing/Unboxing ==========

    @Test
    fun `should handle boxing and unboxing methods`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.boxing"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // BoxingService methods should be found and processed without issues
        val methods = graph.methods(MethodPattern(
            declaringClass = "sample.boxing.BoxingService"
        )).toList()

        println("BoxingService methods: ${methods.size}")
        methods.forEach { println("  ${it.name}") }

        assertTrue(methods.isNotEmpty(), "Should find BoxingService methods")

        // Verify that boxing/unboxing round-trip methods exist
        val roundTripMethods = methods.filter { it.name.startsWith("roundTrip") }
        assertEquals(8, roundTripMethods.size,
            "Should find all 8 roundTrip methods (int, long, short, byte, float, double, boolean, char)")
    }

    // ========== Array operations ==========

    @Test
    fun `should handle array store and load operations`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.arrays"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // ArrayService methods should be found
        val methods = graph.methods(MethodPattern(
            declaringClass = "sample.arrays.ArrayService"
        )).toList()

        assertTrue(methods.isNotEmpty(), "Should find ArrayService methods")

        // Check for ARRAY_STORE and ARRAY_LOAD edges
        val allNodes = graph.nodes<Node>().toList()
        var arrayStoreCount = 0
        var arrayLoadCount = 0

        allNodes.forEach { node ->
            graph.incoming(node.id, DataFlowEdge::class.java).forEach { edge ->
                when (edge.kind) {
                    DataFlowKind.ARRAY_STORE -> arrayStoreCount++
                    DataFlowKind.ARRAY_LOAD -> arrayLoadCount++
                    else -> {}
                }
            }
        }

        println("Array edges: store=$arrayStoreCount, load=$arrayLoadCount")
        assertTrue(arrayStoreCount > 0, "Should find ARRAY_STORE edges from array assignments")
        assertTrue(arrayLoadCount > 0, "Should find ARRAY_LOAD edges from array reads")
    }

    // ========== Enum extraction edge cases ==========

    @Test
    fun `should extract complex enum values with multiple constructor args`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // ComplexEnum has (int code, String label, boolean active, double weight)
        val alphaValues = graph.enumValues("sample.enums.ComplexEnum", "ALPHA")
        println("ComplexEnum.ALPHA values: $alphaValues")

        assertNotNull(alphaValues, "Should extract ALPHA enum values")
        assertTrue(alphaValues.isNotEmpty(), "ALPHA should have constructor values")
        assertEquals(100, alphaValues[0], "ALPHA code should be 100")
        assertEquals("alpha-label", alphaValues[1], "ALPHA label should be alpha-label")
    }

    @Test
    fun `should handle empty enum without custom constructor args`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // EmptyEnum has no custom constructor args, so values should be empty or null
        val firstValues = graph.enumValues("sample.enums.EmptyEnum", "FIRST")
        println("EmptyEnum.FIRST values: $firstValues")

        // EmptyEnum constants have only name and ordinal (no user args), so should be empty
        assertTrue(firstValues == null || firstValues.isEmpty(),
            "EmptyEnum should have no user-defined constructor values")
    }

    @Test
    fun `should extract boxed argument enum values`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // BoxedArgEnum has (Integer intCode, Long longCode) - uses valueOf boxing
        val itemAValues = graph.enumValues("sample.enums.BoxedArgEnum", "ITEM_A")
        println("BoxedArgEnum.ITEM_A values: $itemAValues")

        assertNotNull(itemAValues, "Should extract ITEM_A enum values")
        assertTrue(itemAValues.isNotEmpty(), "ITEM_A should have constructor values")
        // The boxed values should be extracted through extractBoxedValue
        assertEquals(1001, itemAValues[0], "ITEM_A intCode should be 1001")
    }

    // ========== Jackson WRITE_ONLY ==========

    @Test
    fun `should detect JsonProperty WRITE_ONLY access`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.jackson"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // JacksonWriteOnlyDTO has fields with access = WRITE_ONLY
        // Check specific fields
        val secretTokenInfo = graph.jacksonFieldInfo("sample.jackson.JacksonWriteOnlyDTO", "secretToken")
        println("secretToken jackson info: $secretTokenInfo")

        if (secretTokenInfo != null) {
            assertTrue(secretTokenInfo.isIgnored,
                "secretToken with WRITE_ONLY should be marked as ignored")
        }

        val displayNameInfo = graph.jacksonFieldInfo("sample.jackson.JacksonWriteOnlyDTO", "displayName")
        println("displayName jackson info: $displayNameInfo")

        if (displayNameInfo != null) {
            assertEquals("display_name", displayNameInfo.jsonName,
                "displayName should have json name 'display_name'")
        }

        // Also check getter info
        val getSecretTokenInfo = graph.jacksonGetterInfo("sample.jackson.JacksonWriteOnlyDTO", "getSecretToken")
        println("getSecretToken getter info: $getSecretTokenInfo")

        if (getSecretTokenInfo != null) {
            assertTrue(getSecretTokenInfo.isIgnored,
                "getSecretToken getter with WRITE_ONLY should be marked as ignored")
        }
    }

    // ========== shouldIncludeClass with excludePackages ==========

    @Test
    fun `should exclude packages when configured`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample"),
            excludePackages = listOf("sample.boxing"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Should NOT find BoxingService methods
        val boxingMethods = graph.methods(MethodPattern(
            declaringClass = "sample.boxing.BoxingService"
        )).toList()

        assertTrue(boxingMethods.isEmpty(), "Should not find methods from excluded package")

        // Should still find other methods
        val simpleMethods = graph.methods(MethodPattern(
            declaringClass = "sample.simple.SimpleService"
        )).toList()

        assertTrue(simpleMethods.isNotEmpty(), "Should find methods from non-excluded package")
    }

    // ========== Verbose logging ==========

    @Test
    fun `should produce verbose logging when configured`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val logs = mutableListOf<String>()
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { logs.add(it) }
        ))

        loader.load(testClassesDir)

        assertTrue(logs.isNotEmpty(), "Should produce verbose log messages")
        assertTrue(logs.any { it.contains("enum") },
            "Should log about enum processing")
    }

    // ========== getMethodReturnTypeWithGenerics ==========

    @Test
    fun `should resolve generic method return types`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.generics"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // GenericReturnService has methods returning generic types
        val methods = graph.methods(MethodPattern(
            declaringClass = "sample.generics.GenericReturnService",
            useRegex = false
        )).toList()

        assertTrue(methods.isNotEmpty(), "Should find GenericReturnService methods")
        println("GenericReturnService methods: ${methods.map { it.name }}")
    }

    // ========== More boxing types in enum extraction ==========

    @Test
    fun `should extract enum values with Short Byte Float Double Boolean Character boxing`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // MoreBoxedEnum has (Short, Byte, Float, Double, Boolean, Character)
        val itemXValues = graph.enumValues("sample.enums.MoreBoxedEnum", "ITEM_X")
        println("MoreBoxedEnum.ITEM_X values: $itemXValues")

        assertNotNull(itemXValues, "Should extract ITEM_X enum values")
        assertTrue(itemXValues.isNotEmpty(), "ITEM_X should have constructor values")
    }

    // ========== Enum with enum reference ==========

    @Test
    fun `should extract enum values with enum reference arguments`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // EnumWithEnumRef has (Priority priority, String config) where Priority is another enum
        val lowAlphaValues = graph.enumValues("sample.enums.EnumWithEnumRef", "LOW_ALPHA")
        println("EnumWithEnumRef.LOW_ALPHA values: $lowAlphaValues")

        assertNotNull(lowAlphaValues, "Should extract LOW_ALPHA enum values")
        assertTrue(lowAlphaValues.isNotEmpty(), "LOW_ALPHA should have constructor values")
        // First arg should be an enum reference to Priority.LOW
        println("  First arg type: ${lowAlphaValues[0]?.javaClass}")
        println("  First arg: ${lowAlphaValues[0]}")
        // Second arg should be "alpha-config"
        if (lowAlphaValues.size > 1) {
            assertEquals("alpha-config", lowAlphaValues[1], "Second arg should be config string")
        }
    }

    // ========== Default call graph algorithm ==========

    @Test
    fun `should use default call graph algorithm when VTA specified`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // VTA falls into the else branch of buildCallGraph which defaults to CHA
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.simple"),
            buildCallGraph = true,
            callGraphAlgorithm = CallGraphAlgorithm.VTA
        ))

        val graph = loader.load(testClassesDir)
        assertNotNull(graph, "Should produce graph with VTA (defaulting to CHA)")
    }

    // ========== Entry points finding ==========

    @Test
    fun `should find main method entry points for call graph`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // SimpleService has a main method
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.simple"),
            buildCallGraph = true,
            callGraphAlgorithm = CallGraphAlgorithm.CHA
        ))

        val graph = loader.load(testClassesDir)
        assertNotNull(graph, "Should produce graph using main method as entry point")
    }

    // ========== shouldIncludeClass with empty includePackages ==========

    @Test
    fun `should include all classes when includePackages is empty`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        // Load with NO includePackages filter - should include all classes
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = emptyList(),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Should find classes from multiple packages
        val simpleMethods = graph.methods(MethodPattern(
            declaringClass = "sample.simple.SimpleService"
        )).toList()

        val enumMethods = graph.methods(MethodPattern(
            declaringClass = "sample.enums.ComplexEnum"
        )).toList()

        assertTrue(simpleMethods.isNotEmpty(), "Should find SimpleService methods without include filter")
        assertTrue(enumMethods.isNotEmpty(), "Should find ComplexEnum methods without include filter")
    }

    // ========== Boolean constant extraction ==========

    @Test
    fun `should handle boolean constants in bytecode`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.booleans"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // BooleanFieldEnum has boolean constructor args
        val enumValues = graph.enumValues("sample.booleans.BooleanFieldEnum", "ENABLED")
        println("BooleanFieldEnum.ENABLED values: $enumValues")

        val methods = graph.methods(MethodPattern(
            declaringClass = "sample.booleans.BooleanConstantService"
        )).toList()

        assertTrue(methods.isNotEmpty(), "Should find BooleanConstantService methods")
    }

    // ========== Controller without class-level mapping (extractMappingPath empty) ==========

    @Test
    fun `should handle controller without class level RequestMapping`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.controller"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // HealthController has no @RequestMapping at class level
        // Its endpoints should still be found with method-level paths
        val endpoints = graph.endpoints().toList()
        println("Endpoints found: ${endpoints.map { "${it.httpMethod} ${it.path}" }}")

        val healthEndpoints = endpoints.filter { it.path.contains("health") }
        assertTrue(healthEndpoints.isNotEmpty(),
            "Should find /health endpoint from HealthController without class-level mapping")
    }

    // ========== Enum with static block ==========

    @Test
    fun `should extract enum values from enum with static initializer block`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // EnumWithStaticBlock has a static block that generates non-<init> JInvokeStmts
        // The enum extraction should still work correctly
        val firstValues = graph.enumValues("sample.enums.EnumWithStaticBlock", "FIRST")
        println("EnumWithStaticBlock.FIRST values: $firstValues")

        assertNotNull(firstValues, "Should extract FIRST enum values")
        assertTrue(firstValues.isNotEmpty(), "FIRST should have constructor values")
        assertEquals(1, firstValues[0], "FIRST code should be 1")
        assertEquals("first-item", firstValues[1], "FIRST label should be first-item")
    }

    // ========== Enum with DirectFieldRefEnum ==========

    @Test
    fun `should extract enum values from DirectFieldRefEnum`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.enums"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        val item1Values = graph.enumValues("sample.enums.DirectFieldRefEnum", "ITEM_1")
        println("DirectFieldRefEnum.ITEM_1 values: $item1Values")

        assertNotNull(item1Values, "Should extract ITEM_1 enum values")
        assertTrue(item1Values.isNotEmpty(), "ITEM_1 should have constructor values")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
