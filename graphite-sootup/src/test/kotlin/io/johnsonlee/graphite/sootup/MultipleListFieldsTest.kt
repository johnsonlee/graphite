package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests that multiple List fields with different generic types are all discovered.
 *
 * This is a regression test for the bug where only some List fields were output
 * while others were skipped because java.util.List was not in includePackages.
 */
class MultipleListFieldsTest {

    @Test
    fun `should discover all List fields regardless of generic type`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.collections"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Use findTypeHierarchy to analyze the return type
        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.collections.CollectionController"
                    name = "getMultipleLists"
                }
            }
        }

        assertTrue(results.isNotEmpty(), "Should find the getMultipleLists method")

        val result = results.first()
        assertTrue(result.returnStructures.isNotEmpty(), "Should have return structures")

        val returnStructure = result.returnStructures.first()
        val fieldNames = returnStructure.fields.keys

        println("=== Discovered Fields ===")
        returnStructure.fields.forEach { (name, field) ->
            println("  $name: ${field.declaredType.className}")
        }

        // All four List fields should be discovered
        val expectedFields = setOf("names", "scores", "users", "orders")
        expectedFields.forEach { fieldName ->
            assertTrue(
                fieldName in fieldNames,
                "Field '$fieldName' should be discovered. Found fields: $fieldNames"
            )
        }

        // Verify all are List types
        expectedFields.forEach { fieldName ->
            val field = returnStructure.fields[fieldName]
            assertTrue(
                field?.declaredType?.className == "java.util.List",
                "Field '$fieldName' should be List type, got: ${field?.declaredType?.className}"
            )
        }
    }

    @Test
    fun `should discover List fields via getter-based strategy`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.collections"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.collections.CollectionController"
                    name = "getMultipleLists"
                }
            }
        }

        val returnStructure = results.first().returnStructures.first()

        // Check that all fields were discovered (should work via getter-based discovery)
        println("=== Field Discovery Test ===")
        println("Total fields discovered: ${returnStructure.fields.size}")

        returnStructure.fields.forEach { (name, field) ->
            println("  $name:")
            println("    declaredType: ${field.declaredType.className}")
            println("    typeArgs: ${field.declaredType.typeArguments.map { it.className }}")
        }

        // All 4 List fields must be discovered
        assertTrue(
            returnStructure.fields.size >= 4,
            "Should discover at least 4 List fields, found: ${returnStructure.fields.size}"
        )
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
