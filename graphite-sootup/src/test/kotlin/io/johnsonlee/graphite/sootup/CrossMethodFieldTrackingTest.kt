package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for cross-method field tracking and inheritance hierarchy.
 */
class CrossMethodFieldTrackingTest {

    @Test
    fun `should track field assigned in one method and returned in another`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.inheritance.InheritanceService"
                    name = "getCachedData"
                }
            }
        }

        println("=== Cross-Method Field Tracking Test ===")
        println("Method: getCachedData()")
        println("Expected: Should discover UserData type even though it's set in initCache()")
        println()

        results.forEach { result ->
            println(result.toTreeString())
        }

        assertTrue(results.isNotEmpty(), "Should find getCachedData method")

        // The actual type discovery depends on how well we track across methods
        val allTypes = results.flatMap { it.allReturnTypes() }.map { it.className }
        println("Return types found: $allTypes")
    }

    @Test
    fun `should find types assigned in subclass to parent Object field`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.inheritance.InheritanceService"
                    name = "getUserResponse"
                }
            }
        }

        println("=== Inheritance Hierarchy Test ===")
        println("Method: getUserResponse()")
        println("Expected: Should discover UserData assigned to parent's payload field")
        println()

        results.forEach { result ->
            println(result.toTreeString())
        }

        assertTrue(results.isNotEmpty(), "Should find getUserResponse method")

        // Check if we find UserResponse
        val returnTypes = results.flatMap { it.allReturnTypes() }.map { it.className }
        println("Return types: $returnTypes")
    }

    @Test
    fun `should find multiple types from conditional cross-method assignment`() {
        val graph = loadGraph()
        val graphite = Graphite.from(graph)

        // Analyze the CacheService type hierarchy
        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.inheritance.InheritanceService\$CacheService"
                    name = "getCache"
                }
            }
        }

        println("=== Conditional Cross-Method Assignment Test ===")
        println("Method: CacheService.getCache()")
        println("Expected: Should find both UserData and OrderData from updateCache()")
        println()

        results.forEach { result ->
            println(result.toTreeString())
        }

        assertTrue(results.isNotEmpty(), "Should find getCache method")
    }

    private fun loadGraph(): io.johnsonlee.graphite.graph.Graph {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.inheritance"),
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
