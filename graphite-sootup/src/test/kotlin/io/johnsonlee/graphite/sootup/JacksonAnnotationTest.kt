package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test for @JsonProperty annotation extraction and field discovery.
 */
class JacksonAnnotationTest {

    @Test
    fun `should extract JsonProperty names from DTO fields`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.jackson"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // Check Jackson field info is extracted
        val userIdInfo = graph.jacksonFieldInfo("sample.jackson.JacksonDTO", "userId")
        println("userId field info: $userIdInfo")
        assertEquals("user_id", userIdInfo?.jsonName, "userId should have @JsonProperty(\"user_id\")")

        val userNameInfo = graph.jacksonFieldInfo("sample.jackson.JacksonDTO", "userName")
        println("userName field info: $userNameInfo")
        assertEquals("user_name", userNameInfo?.jsonName, "userName should have @JsonProperty(\"user_name\")")

        val emailInfo = graph.jacksonFieldInfo("sample.jackson.JacksonDTO", "email")
        println("email field info: $emailInfo")
        assertEquals("email_address", emailInfo?.jsonName, "email should have @JsonProperty(\"email_address\")")

        val passwordInfo = graph.jacksonFieldInfo("sample.jackson.JacksonDTO", "password")
        println("password field info: $passwordInfo")
        assertTrue(passwordInfo?.isIgnored == true, "password should be @JsonIgnore")

        val activeInfo = graph.jacksonFieldInfo("sample.jackson.JacksonDTO", "active")
        println("active field info: $activeInfo")
        assertEquals("is_active", activeInfo?.jsonName, "active should have @JsonProperty(\"is_active\")")
    }

    @Test
    fun `should discover all fields in return type analysis`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.jackson"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)
        val graphite = Graphite.from(graph)

        // Analyze return type for getUser method
        val results = graphite.query {
            findTypeHierarchy {
                method {
                    declaringClass = "sample.jackson.JacksonController"
                    name = "getUser"
                }
            }
        }

        assertTrue(results.isNotEmpty(), "Should have type hierarchy result")
        val result = results.first()

        println("Return structures:")
        result.returnStructures.forEach { structure ->
            println("  Type: ${structure.formatTypeName()}")
            structure.fields.forEach { (name, field) ->
                println("    Field: $name (effectiveJsonName=${field.effectiveJsonName}, isJsonIgnored=${field.isJsonIgnored})")
            }
        }

        // Check that fields are discovered
        val returnStructure = result.returnStructures.firstOrNull()
        assertTrue(returnStructure != null, "Should have return structure")

        val fields = returnStructure.fields
        println("\nDiscovered fields: ${fields.keys}")

        // All non-ignored fields should be present
        assertTrue(fields.containsKey("userId") || fields.any { it.value.effectiveJsonName == "user_id" },
            "Should discover userId field")
        assertTrue(fields.containsKey("userName") || fields.any { it.value.effectiveJsonName == "user_name" },
            "Should discover userName field")
        assertTrue(fields.containsKey("email") || fields.any { it.value.effectiveJsonName == "email_address" },
            "Should discover email field")
        assertTrue(fields.containsKey("age") || fields.any { it.value.effectiveJsonName == "age" },
            "Should discover age field")
        assertTrue(fields.containsKey("active") || fields.any { it.value.effectiveJsonName == "is_active" },
            "Should discover active field")

        // Check effectiveJsonName uses @JsonProperty
        val userIdField = fields.entries.find { it.key == "userId" || it.value.effectiveJsonName == "user_id" }?.value
        assertEquals("user_id", userIdField?.effectiveJsonName, "userId effectiveJsonName should be 'user_id'")

        // password should be ignored
        val passwordField = fields["password"]
        assertTrue(passwordField == null || passwordField.isJsonIgnored, "password should be ignored or not present")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
