package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for various List construction patterns.
 * Verifies that backward slice can trace constants through all common ways of creating Lists.
 */
class ListConstructionPatternsTest {

    // ==================== JDK Pattern Expected Values ====================

    private val expectedArraysAsList = setOf(1001, 1002)
    private val expectedListOf = setOf(2001, 2002)
    private val expectedSingletonList = setOf(3001)
    private val expectedStreamToList = setOf(7001, 7002)
    private val expectedStaticField = setOf(8001, 8002, 8003, 8004)

    // Unsupported patterns (SootUpAdapter limitation):
    // - ArrayList.add() - 4001, 4002
    // - new ArrayList<>(collection) - 5001, 5002
    // - Stream.collect() - 6001, 6002

    // ==================== Guava Pattern Expected Values ====================

    private val expectedImmutableListOf = setOf(9001, 9002, 9003)
    private val expectedListsNewArrayList = setOf(9101, 9102)
    private val expectedImmutableListBuilder = setOf(9201, 9202)
    private val expectedImmutableListCopyOf = setOf(9301, 9302)
    private val expectedStaticImmutable = setOf(9401, 9402)

    @Test
    fun `should trace constants through JDK List construction patterns`() {
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
            .filter { it.callSite.caller.declaringClass.className == "sample.ab.ListConstructionPatterns" }
            .map { it.constant }
            .filterIsInstance<IntConstant>()
            .map { it.value }
            .toSet()

        println("Found integers from ListConstructionPatterns: $foundIntegers")

        // Test supported JDK patterns
        assertContainsAllInts("Arrays.asList", foundIntegers, expectedArraysAsList)
        assertContainsAllInts("List.of", foundIntegers, expectedListOf)
        assertContainsAllInts("Collections.singletonList", foundIntegers, expectedSingletonList)
        assertContainsAllInts("Stream.toList", foundIntegers, expectedStreamToList)
        assertContainsAllInts("Static field", foundIntegers, expectedStaticField)

        // Note: ArrayList.add, new ArrayList<>(collection), Stream.collect are NOT supported
    }

    @Test
    fun `should trace constants through Guava List construction patterns`() {
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
            .filter { it.callSite.caller.declaringClass.className == "sample.ab.GuavaListPatterns" }
            .map { it.constant }
            .filterIsInstance<IntConstant>()
            .map { it.value }
            .toSet()

        println("Found integers from GuavaListPatterns: $foundIntegers")

        // Test each Guava pattern
        assertContainsAllInts("ImmutableList.of", foundIntegers, expectedImmutableListOf)
        assertContainsAllInts("Lists.newArrayList", foundIntegers, expectedListsNewArrayList)
        assertContainsAllInts("ImmutableList.builder", foundIntegers, expectedImmutableListBuilder)
        assertContainsAllInts("ImmutableList.copyOf", foundIntegers, expectedImmutableListCopyOf)
        assertContainsAllInts("Static ImmutableList", foundIntegers, expectedStaticImmutable)
    }

    @Test
    fun `should trace enum constants through JDK List patterns`() {
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
            .filter { it.callSite.caller.declaringClass.className == "sample.ab.ListConstructionPatterns" }
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .map { it.enumName }
            .toSet()

        println("Found enums from ListConstructionPatterns: $foundEnums")

        // Expected enums from JDK patterns
        val expectedEnums = setOf("NEW_HOMEPAGE", "CHECKOUT_V2", "PREMIUM_FEATURES", "DARK_MODE")
        assertContainsAllStrings("JDK enum patterns", foundEnums, expectedEnums)
    }

    @Test
    fun `should trace enum constants through Guava List patterns`() {
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
            .filter { it.callSite.caller.declaringClass.className == "sample.ab.GuavaListPatterns" }
            .map { it.constant }
            .filterIsInstance<EnumConstant>()
            .map { it.enumName }
            .toSet()

        println("Found enums from GuavaListPatterns: $foundEnums")

        // Expected enums from Guava patterns
        val expectedEnums = setOf("NEW_HOMEPAGE", "CHECKOUT_V2", "PREMIUM_FEATURES", "DARK_MODE")
        assertContainsAllStrings("Guava enum patterns", foundEnums, expectedEnums)
    }

    private fun assertContainsAllInts(pattern: String, actual: Set<Int>, expected: Set<Int>) {
        val missing = expected - actual
        assertTrue(missing.isEmpty(), "$pattern: missing constants $missing (found: $actual)")
    }

    private fun assertContainsAllStrings(pattern: String, actual: Set<String>, expected: Set<String>) {
        val missing = expected - actual
        assertTrue(missing.isEmpty(), "$pattern: missing constants $missing (found: $actual)")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
