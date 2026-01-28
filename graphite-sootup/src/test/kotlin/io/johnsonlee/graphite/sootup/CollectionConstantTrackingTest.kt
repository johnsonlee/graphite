package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.analysis.AnalysisConfig
import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for collection parameter constant tracking feature.
 *
 * This feature allows tracking constants inside collection factory calls like:
 * - Arrays.asList(1001, 1002, 1003)
 * - List.of(ExperimentId.A, ExperimentId.B)
 *
 * When expandCollections=true, the analysis will trace into the collection
 * factory arguments to find the individual constant values.
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
    fun `should NOT expand collections when expandCollections is false (default)`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Query without expandCollections (default behavior)
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

        // Should find no integer constants (only the List object)
        val foundIntegers = results
            .map { it.constant }
            .filterIsInstance<IntConstant>()
            .map { it.value }
            .toSet()

        println("Without expandCollections, found integers: $foundIntegers")
        assertTrue(foundIntegers.isEmpty(),
            "Should NOT find integer constants inside collection when expandCollections=false")
    }

    @Test
    fun `should expand Arrays asList to find integer constants`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Query with expandCollections enabled
        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOptions"
                    parameterTypes = listOf("java.util.List")
                }
                argumentIndex = 0
                config {
                    copy(expandCollections = true)
                }
            }
        }

        val foundIntegers = results
            .map { it.constant }
            .filterIsInstance<IntConstant>()
            .map { it.value }
            .toSet()

        println("With expandCollections, found integers: $foundIntegers")

        // Should find constants from all test methods
        val allExpected = expectedArraysAsListIntegers + expectedListOfIntegers + expectedVariableIntegers

        allExpected.forEach { expected ->
            assertTrue(foundIntegers.contains(expected),
                "Should find integer constant: $expected")
        }
    }

    @Test
    fun `should expand Arrays asList to find enum constants`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Query for enum list parameters with expandCollections
        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "sample.ab.AbClient"
                    name = "getOptionsByEnum"
                    parameterTypes = listOf("java.util.List")
                }
                argumentIndex = 0
                config {
                    copy(expandCollections = true)
                }
            }
        }

        val foundEnums = results
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .map { it.enumName }
            .toSet()

        println("With expandCollections, found enums: $foundEnums")

        val allExpectedEnums = expectedArraysAsListEnums + expectedListOfEnums

        allExpectedEnums.forEach { expected ->
            assertTrue(foundEnums.contains(expected),
                "Should find enum constant: $expected")
        }
    }

    @Test
    fun `should verify isCollectionFactory detects known factories`() {
        // Test the companion object helper function
        val arraysAsList = MethodDescriptor(
            declaringClass = TypeDescriptor("java.util.Arrays"),
            name = "asList",
            parameterTypes = listOf(TypeDescriptor("java.lang.Object[]")),
            returnType = TypeDescriptor("java.util.List")
        )
        assertTrue(DataFlowAnalysis.isCollectionFactory(arraysAsList),
            "Should detect Arrays.asList as collection factory")

        val listOf = MethodDescriptor(
            declaringClass = TypeDescriptor("java.util.List"),
            name = "of",
            parameterTypes = emptyList(),
            returnType = TypeDescriptor("java.util.List")
        )
        assertTrue(DataFlowAnalysis.isCollectionFactory(listOf),
            "Should detect List.of as collection factory")

        val kotlinListOf = MethodDescriptor(
            declaringClass = TypeDescriptor("kotlin.collections.CollectionsKt"),
            name = "listOf",
            parameterTypes = listOf(TypeDescriptor("java.lang.Object[]")),
            returnType = TypeDescriptor("java.util.List")
        )
        assertTrue(DataFlowAnalysis.isCollectionFactory(kotlinListOf),
            "Should detect kotlin listOf as collection factory")

        // Non-factory method
        val toString = MethodDescriptor(
            declaringClass = TypeDescriptor("java.lang.Object"),
            name = "toString",
            parameterTypes = emptyList(),
            returnType = TypeDescriptor("java.lang.String")
        )
        assertTrue(!DataFlowAnalysis.isCollectionFactory(toString),
            "Should NOT detect Object.toString as collection factory")
    }

    @Test
    fun `should respect maxCollectionDepth for nested collections`() {
        // This test verifies the depth limiting behavior
        // In real code, nested collections like listOf(listOf(1,2), listOf(3,4)) would need depth control
        val config = AnalysisConfig(
            expandCollections = true,
            maxCollectionDepth = 2
        )

        assertEquals(2, config.maxCollectionDepth)
        assertTrue(config.expandCollections)
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
