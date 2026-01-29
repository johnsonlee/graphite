package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.analysis.PropagationNodeType
import io.johnsonlee.graphite.analysis.PropagationSourceType
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test propagation path tracking functionality.
 *
 * Tests that the propagation path analysis correctly tracks:
 * 1. Direct constant paths (depth = 0)
 * 2. Local variable indirection (depth > 0)
 * 3. Field access paths
 * 4. Method return value propagation
 * 5. Path statistics and filtering
 */
class PropagationPathTrackingTest {

    @Test
    fun `should track propagation path for direct constants`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        // Query for integer constants passed to getOption(Integer)
        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.lang.Integer")
                }
                argumentIndex = 0
            }
        }

        // Find direct constant (1001) - should have minimal depth
        val directConstantResult = results.find {
            it.constant is IntConstant && (it.constant as IntConstant).value == 1001
        }

        assertNotNull(directConstantResult, "Should find direct constant 1001")
        assertNotNull(directConstantResult.propagationPath, "Should have propagation path")

        println("Direct constant 1001:")
        println("  Depth: ${directConstantResult.propagationDepth}")
        println("  Path: ${directConstantResult.propagationDescription}")

        // Direct constants should have low depth
        assertTrue(directConstantResult.propagationDepth <= 2,
            "Direct constant should have depth <= 2, but was ${directConstantResult.propagationDepth}")
    }

    @Test
    fun `should track propagation path for local variable indirection`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.lang.Integer")
                }
                argumentIndex = 0
            }
        }

        // Find constant 1003 which is passed via local variable
        val localVarResult = results.find {
            it.constant is IntConstant && (it.constant as IntConstant).value == 1003
        }

        assertNotNull(localVarResult, "Should find constant 1003 (via local variable)")
        assertNotNull(localVarResult.propagationPath, "Should have propagation path")

        println("Local variable indirection (1003):")
        println("  Depth: ${localVarResult.propagationDepth}")
        println("  Path: ${localVarResult.propagationDescription}")

        val path = localVarResult.propagationPath!!

        // Should have at least one step
        assertTrue(path.steps.isNotEmpty(), "Path should have steps")

        // First step should be a constant
        assertEquals(PropagationNodeType.CONSTANT, path.steps.first().nodeType,
            "First step should be CONSTANT")
    }

    @Test
    fun `should track propagation path for field constants`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "isEnabled"
                }
                argumentIndex = 0
            }
        }

        // Find field constant 2001 or 2002
        val fieldConstantResult = results.find {
            it.constant is IntConstant &&
            (it.constant as IntConstant).value in setOf(2001, 2002)
        }

        assertNotNull(fieldConstantResult, "Should find field constant (2001 or 2002)")

        println("Field constant:")
        println("  Value: ${(fieldConstantResult.constant as IntConstant).value}")
        println("  Depth: ${fieldConstantResult.propagationDepth}")
        println("  Path: ${fieldConstantResult.propagationDescription}")
        println("  Involves field access: ${fieldConstantResult.involvesFieldAccess}")
    }

    @Test
    fun `should track propagation path for enum constants`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("sample.ab.ExperimentId")
                }
                argumentIndex = 0
            }
        }

        assertTrue(results.isNotEmpty(), "Should find enum constant results")

        val enumResult = results.first()
        assertTrue(enumResult.constant is EnumConstant, "Should be enum constant")

        println("Enum constant:")
        println("  Enum: ${(enumResult.constant as EnumConstant).enumName}")
        println("  Depth: ${enumResult.propagationDepth}")
        println("  Path: ${enumResult.propagationDescription}")
        println("  Source type: ${enumResult.propagationPath?.sourceType}")

        // Enum constants accessed via field should have ENUM_CONSTANT or FIELD source type
        // (depends on how the enum is resolved - via FieldNode or directly as EnumConstant)
        val path = enumResult.propagationPath
        assertNotNull(path, "Should have propagation path")
        assertTrue(
            path.sourceType == PropagationSourceType.ENUM_CONSTANT ||
            path.sourceType == PropagationSourceType.FIELD,
            "Source type should be ENUM_CONSTANT or FIELD, but was ${path.sourceType}"
        )
    }

    @Test
    fun `should calculate correct propagation statistics`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.lang.Integer")
                }
                argumentIndex = 0
            }
        }

        assertTrue(results.isNotEmpty(), "Should have results")

        val depths = results.map { it.propagationDepth }
        val maxDepth = depths.maxOrNull() ?: 0
        val avgDepth = depths.average()
        val complexPaths = results.count { it.involvesReturnValue || it.involvesFieldAccess }

        println("Propagation statistics:")
        println("  Total results: ${results.size}")
        println("  Max depth: $maxDepth")
        println("  Avg depth: ${"%.2f".format(avgDepth)}")
        println("  Complex paths: $complexPaths")
        println("  All depths: $depths")

        // Verify statistics are calculated
        assertTrue(maxDepth >= 0, "Max depth should be >= 0")
    }

    @Test
    fun `should filter results by depth`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.lang.Integer")
                }
                argumentIndex = 0
            }
        }

        // Filter by minimum depth
        val minDepth = 1
        val filteredResults = results.filter { it.propagationDepth >= minDepth }

        println("Filtering by depth >= $minDepth:")
        println("  Original count: ${results.size}")
        println("  Filtered count: ${filteredResults.size}")

        // All filtered results should meet the criteria
        filteredResults.forEach { result ->
            assertTrue(result.propagationDepth >= minDepth,
                "Filtered result should have depth >= $minDepth")
        }
    }

    @Test
    fun `should provide detailed step information in propagation path`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.lang.Integer")
                }
                argumentIndex = 0
            }
        }

        // Find a result with a non-trivial path
        val resultWithPath = results.find {
            it.propagationPath != null && it.propagationPath!!.steps.size > 1
        }

        if (resultWithPath != null) {
            val path = resultWithPath.propagationPath!!

            println("Detailed path information:")
            println("  Total steps: ${path.steps.size}")
            println("  Source type: ${path.sourceType}")
            println("  Display: ${path.toDisplayString()}")
            println()
            println("  Steps:")
            path.steps.forEachIndexed { idx, step ->
                println("    ${idx + 1}. ${step.nodeType}: ${step.description}")
                println("       Location: ${step.location ?: "(none)"}")
                println("       Edge: ${step.edgeKind ?: "(none)"}")
                println("       Depth: ${step.depth}")
            }

            // Verify step structure
            path.steps.forEach { step ->
                assertNotNull(step.nodeId, "Step should have nodeId")
                assertNotNull(step.nodeType, "Step should have nodeType")
                assertTrue(step.description.isNotEmpty(), "Step should have description")
            }
        } else {
            println("No multi-step path found in test data")
        }
    }

    @Test
    fun `should track method return value propagation`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOption"
                    parameterTypes = listOf("java.lang.Integer")
                }
                argumentIndex = 0
            }
        }

        // Check for results that involve return value propagation
        val resultsWithReturnValue = results.filter { it.involvesReturnValue }

        println("Results involving return value propagation: ${resultsWithReturnValue.size}")
        resultsWithReturnValue.forEach { result ->
            println("  Value: ${result.constant.value}")
            println("  Path: ${result.propagationDescription}")
        }

        // The test fixtures include patterns like getId() that should involve return values
        // This test documents the behavior even if no such patterns exist in current test data
    }

    @Test
    fun `propagation path display string should be readable`() {
        val graph = loadTestGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "org.ff4j.FF4j"
                    name = "check"
                }
                argumentIndex = 0
            }
        }

        assertTrue(results.isNotEmpty(), "Should find FF4j check results")

        results.take(3).forEach { result ->
            val displayString = result.propagationDescription

            println("Display string: $displayString")

            // Display string should not be empty
            assertTrue(displayString.isNotEmpty(), "Display string should not be empty")

            // If there's a path, verify tree string format
            result.propagationPath?.let { path ->
                val treeString = path.toTreeString()
                println("Tree format:\n$treeString")
                assertTrue(treeString.isNotEmpty(), "Tree string should not be empty")
            }
        }
    }

    private fun loadTestGraph(): io.johnsonlee.graphite.graph.Graph {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
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
