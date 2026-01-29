package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for collection parameter constant tracking.
 *
 * The analysis traces constants inside collection factory calls like:
 * - Arrays.asList(1001, 1002, 1003)
 * - List.of(ExperimentId.A, ExperimentId.B)
 *
 * By default, backward slice traverses all incoming edges, including
 * those from collection factory arguments.
 */
class CollectionConstantTrackingTest {

    // Expected integer constants passed via Arrays.asList
    private val expectedArraysAsListIntegers = setOf(5001, 5002, 5003)

    // Expected integer constants passed via List.of
    private val expectedListOfIntegers = setOf(6001, 6002)

    // Expected integer constants with local variable indirection
    private val expectedVariableIntegers = setOf(7001, 7002, 7003)

    // Expected enum constants passed via Arrays.asList
    private val expectedArraysAsListEnums = setOf("NEW_HOMEPAGE", "CHECKOUT_V2")

    // Expected enum constants passed via List.of
    private val expectedListOfEnums = setOf("PREMIUM_FEATURES", "DARK_MODE")

    @Test
    fun `should find integer constants inside Arrays asList`() {
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
                    name = "getOptions"
                    parameterTypes = listOf("java.util.List")
                }
                argumentIndex = 0
            }
        }

        val foundIntegers = results
            .map { it.constant }
            .filterIsInstance<IntConstant>()
            .map { it.value }
            .toSet()

        println("Found integers: $foundIntegers")

        // Should find constants from all test methods
        val allExpected = expectedArraysAsListIntegers + expectedListOfIntegers + expectedVariableIntegers

        allExpected.forEach { expected ->
            assertTrue(foundIntegers.contains(expected),
                "Should find integer constant: $expected")
        }
    }

    @Test
    fun `should find enum constants inside Arrays asList`() {
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
                    name = "getOptionsByEnum"
                    parameterTypes = listOf("java.util.List")
                }
                argumentIndex = 0
            }
        }

        val foundEnums = results
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .map { it.enumName }
            .toSet()

        println("Found enums: $foundEnums")

        val allExpectedEnums = expectedArraysAsListEnums + expectedListOfEnums

        allExpectedEnums.forEach { expected ->
            assertTrue(foundEnums.contains(expected),
                "Should find enum constant: $expected")
        }
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
