package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for static field indirect reference pattern in AB testing.
 *
 * This tests the common pattern where:
 * 1. An enum defines AB test IDs: AbKey.SIMPLE_TEST_ID(1234)
 * 2. A static field holds a list: static final List<AbKey> KEYS = Arrays.asList(AbKey.SIMPLE_TEST_ID)
 * 3. The list is passed to SDK: abClient.getOption(KEYS)
 *
 * The analysis should trace from getOption(KEYS) back through the static field
 * to find the enum constants and extract their constructor argument values.
 */
class StaticFieldIndirectReferenceTest {

    // Expected enum values from AbKey enum constructor arguments
    private val expectedAbKeyValues = mapOf(
        "SIMPLE_TEST_ID" to 1234,
        "CHECKOUT_FLOW" to 5678,
        "NEW_ONBOARDING" to 9999
    )

    @Test
    fun `should find enum constants through static field indirect reference`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false,
            verbose = { println("[LOADER] $it") }
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Diagnostic: Check graph structure for static field pattern
        println("\n=== DIAGNOSTIC: Graph structure for static field pattern ===")

        // Find the static List fields
        graph.nodes(FieldNode::class.java).filter {
            it.descriptor.name in listOf("SIMPLE_TEST_KEYS", "CHECKOUT_KEYS", "SINGLE_KEY") &&
            it.descriptor.declaringClass.className == "sample.ab.AbTestResolver"
        }.forEach { field ->
            println("\nField: ${field.descriptor.declaringClass.simpleName}.${field.descriptor.name}")
            println("  FieldNode ID: ${field.id}")
            println("  Type: ${field.descriptor.type}")

            // Check incoming edges (stores to this field)
            val incomingEdges = graph.incoming(field.id, DataFlowEdge::class.java).toList()
            println("  Incoming edges (stores): ${incomingEdges.size}")
            incomingEdges.forEach { edge ->
                val fromNode = graph.node(edge.from)
                println("    <- ${edge.kind}: ${fromNode?.javaClass?.simpleName} (ID: ${edge.from})")
            }

            // Check outgoing edges (loads from this field)
            val outgoingEdges = graph.outgoing(field.id, DataFlowEdge::class.java).toList()
            println("  Outgoing edges (loads): ${outgoingEdges.size}")
            outgoingEdges.forEach { edge ->
                val toNode = graph.node(edge.to)
                println("    -> ${edge.kind}: ${toNode?.javaClass?.simpleName} (ID: ${edge.to})")
            }
        }

        // Find AbKey enum constant fields
        println("\n=== AbKey enum constant fields ===")
        graph.nodes(FieldNode::class.java).filter {
            it.descriptor.declaringClass.className == "sample.ab.AbKey"
        }.forEach { field ->
            val inCount = graph.incoming(field.id).count()
            val outCount = graph.outgoing(field.id).count()
            println("  ${field.descriptor.name}: incoming=$inCount, outgoing=$outCount")
        }

        // Check enum values extraction
        println("\n=== Enum values from graph ===")
        listOf("SIMPLE_TEST_ID", "CHECKOUT_FLOW", "NEW_ONBOARDING").forEach { enumName ->
            val values = graph.enumValues("sample.ab.AbKey", enumName)
            println("  AbKey.$enumName: $values")
        }

        println("\n=== Running query ===")

        // Query for List<AbKey> parameter with collection expansion
        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.util.List")
                }
                argumentIndex = 0
            }
        }

        println("Found ${results.size} results for getOption(List<AbKey>)")

        // Show all results, not just enum constants
        println("\nAll constants found:")
        results.forEach { result ->
            println("  ${result.constant.javaClass.simpleName}: ${result.constant}")
            println("    Caller: ${result.callSite.caller.name}")
            println("    Path depth: ${result.propagationDepth}")
            result.propagationPath?.let { path ->
                println("    Propagation: ${path.toDisplayString()}")
            }
        }

        val foundEnumConstants = results
            .map { it.constant }
            .filterIsInstance<EnumConstant>()

        println("\nEnum constants found:")
        foundEnumConstants.forEach { enum ->
            println("  ${enum.enumType.simpleName}.${enum.enumName} = ${enum.value}")
        }

        val foundEnumNames = foundEnumConstants.map { it.enumName }.toSet()
        val foundEnumValues = foundEnumConstants.mapNotNull { it.value as? Int }.toSet()

        println("\nExpected enum names: ${expectedAbKeyValues.keys}")
        println("Found enum names: $foundEnumNames")
        println("Expected values: ${expectedAbKeyValues.values.toSet()}")
        println("Found values: $foundEnumValues")

        // Verify we find enum constants from static field patterns
        // Pattern 1: SIMPLE_TEST_KEYS contains SIMPLE_TEST_ID(1234)
        assertTrue(foundEnumNames.contains("SIMPLE_TEST_ID"),
            "Should find SIMPLE_TEST_ID through static field SIMPLE_TEST_KEYS")

        // Pattern 2: CHECKOUT_KEYS contains CHECKOUT_FLOW(5678) and NEW_ONBOARDING(9999)
        assertTrue(foundEnumNames.contains("CHECKOUT_FLOW"),
            "Should find CHECKOUT_FLOW through static field CHECKOUT_KEYS")
        assertTrue(foundEnumNames.contains("NEW_ONBOARDING"),
            "Should find NEW_ONBOARDING through static field CHECKOUT_KEYS")

        // Verify we can extract the integer values from enum constructors
        assertTrue(foundEnumValues.contains(1234),
            "Should find value 1234 from AbKey.SIMPLE_TEST_ID")
        assertTrue(foundEnumValues.contains(5678),
            "Should find value 5678 from AbKey.CHECKOUT_FLOW")
        assertTrue(foundEnumValues.contains(9999),
            "Should find value 9999 from AbKey.NEW_ONBOARDING")
    }

    @Test
    fun `should find enum through single static field reference`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Query specifically for methods that use the SINGLE_KEY pattern
        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.util.List")
                }
                argumentIndex = 0
            }
        }

        // Filter to results from isSingleKeyEnabled method
        val singleKeyResults = results.filter {
            it.callSite.caller.name == "isSingleKeyEnabled"
        }

        println("Results from isSingleKeyEnabled: ${singleKeyResults.size}")
        singleKeyResults.forEach { result ->
            println("  ${result.constant}")
        }

        val foundEnums = singleKeyResults
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .map { it.enumName }
            .toSet()

        // Pattern 3: Arrays.asList(SINGLE_KEY) where SINGLE_KEY = AbKey.SIMPLE_TEST_ID
        assertTrue(foundEnums.contains("SIMPLE_TEST_ID"),
            "Should find SIMPLE_TEST_ID through static field SINGLE_KEY -> AbKey.SIMPLE_TEST_ID")
    }

    @Test
    fun `should find direct enum reference in inline list`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.util.List")
                }
                argumentIndex = 0
            }
        }

        // Filter to results from isDirectEnumEnabled method (baseline - should already work)
        val directResults = results.filter {
            it.callSite.caller.name == "isDirectEnumEnabled"
        }

        println("Results from isDirectEnumEnabled: ${directResults.size}")
        directResults.forEach { result ->
            println("  ${result.constant}")
        }

        val foundEnums = directResults
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .map { it.enumName }
            .toSet()

        // Pattern 4: Arrays.asList(AbKey.SIMPLE_TEST_ID) - direct reference
        assertTrue(foundEnums.contains("SIMPLE_TEST_ID"),
            "Should find SIMPLE_TEST_ID through direct enum reference in Arrays.asList")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
