package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test that validates the AB ID detection use case with various patterns:
 *
 * 1. Direct integer constants: getOption(1001)
 * 2. Enum constants: getOption(ExperimentId.CHECKOUT_V2)
 * 3. Local variable indirection: int id = 1003; getOption(id)
 * 4. Field constants: getOption(CHECKOUT_EXPERIMENT_ID)
 * 5. String constants with FF4J: ff4j.check("feature-key")
 */
class FeatureFlagAnalysisTest {

    // Expected integer constants passed to AbClient.getOption(Integer) and isEnabled(int)
    // Direct constants: 1001, 1002
    // Via local variable (int): 1003
    // Via local variable (Integer with unboxing): 1004
    // Via same-class field constant: 2001, 2002
    // Via cross-class field constant (AbTestIds): 3001, 3002, 3003, 3004
    private val expectedIntegerIds = setOf(
        1001, 1002, 1003, 1004,  // FeatureFlagService
        2001, 2002,              // FeatureFlagService field constants
        3001, 3002, 3003, 3004   // PlatformFeatureService cross-class constants
    )

    // Expected enum constants passed to AbClient.getOption(ExperimentId)
    private val expectedEnumIds = setOf(
        "CHECKOUT_V2",
        "PREMIUM_FEATURES",
        "DARK_MODE"
    )

    // Expected enum constant values (from ExperimentId enum)
    private val expectedEnumValues = mapOf(
        "NEW_HOMEPAGE" to 1001,
        "CHECKOUT_V2" to 1002,
        "PREMIUM_FEATURES" to 1003,
        "DARK_MODE" to 1004
    )

    // Expected string constants passed to FF4j.check(String)
    private val expectedStringIds = setOf(
        "new-welcome-page",
        "premium-discount",
        "basic-discount",
        "promo-banner",
        "new-checkout"
    )

    @Test
    fun `should find integer constants passed to AbClient getOption`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Find call sites to AbClient.getOption(Integer)
        val getOptionCallSites = graph.callSites(MethodPattern(
            declaringClass = "sample.ab.AbClient",
            name = "getOption",
            parameterTypes = listOf("java.lang.Integer")
        )).toList()

        // Find call sites to AbClient.isEnabled(int)
        val isEnabledCallSites = graph.callSites(MethodPattern(
            declaringClass = "sample.ab.AbClient",
            name = "isEnabled"
        )).toList()

        val allCallSites = getOptionCallSites + isEnabledCallSites

        println("Found ${allCallSites.size} call sites to AbClient methods")

        val foundIntegerIds = mutableSetOf<Int>()

        allCallSites.forEach { callSite ->
            println("\nCall site in ${callSite.caller.name}:")

            if (callSite.arguments.isNotEmpty()) {
                val argNodeId = callSite.arguments[0]
                findIntegerConstants(argNodeId, graph, foundIntegerIds, mutableSetOf())
            }
        }

        println("\n=== Integer ID Results ===")
        println("Expected: $expectedIntegerIds")
        println("Found: $foundIntegerIds")

        assertTrue(allCallSites.isNotEmpty(), "Should find call sites to AbClient")

        expectedIntegerIds.forEach { expected ->
            assertTrue(foundIntegerIds.contains(expected),
                "Should find integer ID: $expected")
        }
    }

    @Test
    fun `should extract enum constant values from bytecode`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false,
            verbose = { println(it) }  // Enable verbose logging
        ))

        val graph = loader.load(testClassesDir)

        // Verify enum values are extracted
        expectedEnumValues.forEach { (enumName, expectedValue) ->
            val values = graph.enumValues("sample.ab.ExperimentId", enumName)
            println("Enum $enumName: values = $values")
            assertTrue(values != null && values.isNotEmpty(),
                "Should extract values for enum $enumName")
            assertEquals(expectedValue, values?.firstOrNull(),
                "Enum $enumName should have value $expectedValue")
        }
    }

    @Test
    fun `should include enum values in query results`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Query for enum constants
        val enumResults = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("sample.ab.ExperimentId")
                }
                argumentIndex = 0
            }
        }

        println("Enum results with values:")
        enumResults.forEach { result ->
            val constant = result.constant
            if (constant is EnumConstant) {
                println("  ${constant.enumName}: value=${constant.value}, constructorArgs=${constant.constructorArgs}")
                // Verify that enum values are populated
                val expectedValue = expectedEnumValues[constant.enumName]
                if (expectedValue != null) {
                    assertEquals(expectedValue, constant.value,
                        "Enum ${constant.enumName} should have value $expectedValue")
                }
            }
        }

        // At least some enum results should have values
        val enumsWithValues = enumResults
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .filter { it.value != null }

        assertTrue(enumsWithValues.isNotEmpty(),
            "At least some enum constants should have extracted values")
    }

    @Test
    fun `should find enum constants passed to AbClient getOption`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Find call sites to AbClient.getOption(ExperimentId)
        val callSites = graph.callSites(MethodPattern(
            declaringClass = "sample.ab.AbClient",
            name = "getOption",
            parameterTypes = listOf("sample.ab.ExperimentId")
        )).toList()

        println("Found ${callSites.size} call sites to AbClient.getOption(ExperimentId)")

        val foundEnumIds = mutableSetOf<String>()

        callSites.forEach { callSite ->
            println("\nCall site in ${callSite.caller.name}:")

            if (callSite.arguments.isNotEmpty()) {
                val argNodeId = callSite.arguments[0]
                findEnumConstants(argNodeId, graph, foundEnumIds, mutableSetOf())
            }
        }

        println("\n=== Enum ID Results ===")
        println("Expected: $expectedEnumIds")
        println("Found: $foundEnumIds")

        assertTrue(callSites.isNotEmpty(), "Should find call sites to AbClient.getOption(ExperimentId)")

        expectedEnumIds.forEach { expected ->
            assertTrue(foundEnumIds.contains(expected),
                "Should find enum ID: $expected")
        }
    }

    @Test
    fun `should find string constants passed to FF4j check`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)
        val dataflow = graphite.dataflow()

        // Find call sites to FF4j.check(String)
        val callSites = graph.callSites(MethodPattern(
            declaringClass = "org.ff4j.FF4j",
            name = "check"
        )).toList()

        println("Found ${callSites.size} call sites to ff4j.check()")

        val foundStringIds = mutableSetOf<String>()

        callSites.forEach { callSite ->
            println("\nCall site in ${callSite.caller.name}:")

            callSite.arguments.forEachIndexed { index, argNodeId ->
                val argNode = graph.node(argNodeId)

                if (argNode is StringConstant) {
                    foundStringIds.add(argNode.value)
                    println("  -> Found string ID (direct): \"${argNode.value}\"")
                } else {
                    // Use backward slice to find string constants
                    val sliceResult = dataflow.backwardSlice(argNodeId)
                    sliceResult.constants().filterIsInstance<StringConstant>().forEach { constant ->
                        foundStringIds.add(constant.value)
                        println("  -> Found string ID (via dataflow): \"${constant.value}\"")
                    }
                }
            }
        }

        println("\n=== String ID Results ===")
        println("Expected: $expectedStringIds")
        println("Found: $foundStringIds")

        assertTrue(callSites.isNotEmpty(), "Should find call sites to ff4j.check()")

        expectedStringIds.forEach { expected ->
            assertTrue(foundStringIds.contains(expected),
                "Should find string ID: $expected")
        }

        assertEquals(expectedStringIds, foundStringIds,
            "Should find exactly the expected string IDs")
    }

    @Test
    fun `should use query DSL to find all AB test constants`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // 1. Use Query DSL for FF4j string constants
        val ff4jResults = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "org.ff4j.FF4j"
                    name = "check"
                }
                argumentIndex = 0
            }
        }

        println("FF4j Query DSL results: ${ff4jResults.size}")
        val foundStrings = ff4jResults
            .map { it.constant }
            .filterIsInstance<StringConstant>()
            .map { it.value }
            .toSet()

        println("Found string IDs via Query DSL: $foundStrings")

        assertEquals(expectedStringIds, foundStrings,
            "Query DSL should find all expected string IDs")

        // 2. Use Query DSL for integer constants (AbClient.getOption(Integer) + isEnabled(int))
        val integerResults = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.lang.Integer")
                }
                argumentIndex = 0
            }
        } + graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "isEnabled"
                }
                argumentIndex = 0
            }
        }

        println("Integer Query DSL results: ${integerResults.size}")
        val foundIntegers = integerResults
            .map { it.constant }
            .filterIsInstance<IntConstant>()
            .map { it.value }
            .toSet()

        println("Found integer IDs via Query DSL: $foundIntegers")

        assertEquals(expectedIntegerIds, foundIntegers,
            "Query DSL should find all expected integer IDs")

        // 3. Use Query DSL for enum constants (AbClient.getOption(ExperimentId))
        val enumResults = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("sample.ab.ExperimentId")
                }
                argumentIndex = 0
            }
        }

        println("Enum Query DSL results: ${enumResults.size}")
        val foundEnums = enumResults
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .map { it.enumName }
            .toSet()

        println("Found enum IDs via Query DSL: $foundEnums")

        assertEquals(expectedEnumIds, foundEnums,
            "Query DSL should find all expected enum IDs")
    }

    /**
     * Recursively find integer constants flowing to a node.
     */
    private fun findIntegerConstants(
        nodeId: NodeId,
        graph: Graph,
        results: MutableSet<Int>,
        visited: MutableSet<NodeId>
    ) {
        if (nodeId in visited) return
        visited.add(nodeId)

        val node = graph.node(nodeId)
        when (node) {
            is IntConstant -> {
                results.add(node.value)
                println("  -> Found integer ID: ${node.value}")
            }
            is LocalVariable, is FieldNode -> {
                // Trace backward through dataflow edges
                graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
                    findIntegerConstants(edge.from, graph, results, visited)
                }
            }
            else -> {}
        }
    }

    /**
     * Recursively find enum constants flowing to a node.
     */
    private fun findEnumConstants(
        nodeId: NodeId,
        graph: Graph,
        results: MutableSet<String>,
        visited: MutableSet<NodeId>
    ) {
        if (nodeId in visited) return
        visited.add(nodeId)

        val node = graph.node(nodeId)
        when (node) {
            is EnumConstant -> {
                results.add(node.enumName)
                println("  -> Found enum ID: ${node.enumType.simpleName}.${node.enumName}")
            }
            is FieldNode -> {
                // Static field access like ExperimentId.CHECKOUT_V2 is a field load
                val fieldName = node.descriptor.name
                if (node.descriptor.declaringClass.className == "sample.ab.ExperimentId") {
                    results.add(fieldName)
                    println("  -> Found enum ID (via field): ExperimentId.$fieldName")
                }
            }
            is LocalVariable -> {
                // Trace backward through dataflow edges
                graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
                    findEnumConstants(edge.from, graph, results, visited)
                }
            }
            else -> {}
        }
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
