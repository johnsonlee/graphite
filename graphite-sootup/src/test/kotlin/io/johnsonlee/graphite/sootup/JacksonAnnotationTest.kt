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

        // Check Jackson field info is extracted via memberAnnotations
        val userIdAnnotations = graph.memberAnnotations("sample.jackson.JacksonDTO", "userId")
        println("userId annotations: $userIdAnnotations")
        assertEquals("user_id", userIdAnnotations["com.fasterxml.jackson.annotation.JsonProperty"]?.get("value"),
            "userId should have @JsonProperty(\"user_id\")")

        val userNameAnnotations = graph.memberAnnotations("sample.jackson.JacksonDTO", "userName")
        println("userName annotations: $userNameAnnotations")
        assertEquals("user_name", userNameAnnotations["com.fasterxml.jackson.annotation.JsonProperty"]?.get("value"),
            "userName should have @JsonProperty(\"user_name\")")

        val emailAnnotations = graph.memberAnnotations("sample.jackson.JacksonDTO", "email")
        println("email annotations: $emailAnnotations")
        assertEquals("email_address", emailAnnotations["com.fasterxml.jackson.annotation.JsonProperty"]?.get("value"),
            "email should have @JsonProperty(\"email_address\")")

        val passwordAnnotations = graph.memberAnnotations("sample.jackson.JacksonDTO", "password")
        println("password annotations: $passwordAnnotations")
        assertTrue(passwordAnnotations.containsKey("com.fasterxml.jackson.annotation.JsonIgnore"),
            "password should be @JsonIgnore")

        val activeAnnotations = graph.memberAnnotations("sample.jackson.JacksonDTO", "active")
        println("active annotations: $activeAnnotations")
        assertEquals("is_active", activeAnnotations["com.fasterxml.jackson.annotation.JsonProperty"]?.get("value"),
            "active should have @JsonProperty(\"is_active\")")
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
                println("    Field: $name (type=${field.declaredType.className})")
            }
        }

        // Check that fields are discovered
        val returnStructure = result.returnStructures.firstOrNull()
        assertTrue(returnStructure != null, "Should have return structure")

        val fields = returnStructure.fields
        println("\nDiscovered fields: ${fields.keys}")

        // All fields should be present
        assertTrue(fields.containsKey("userId"),
            "Should discover userId field")
        assertTrue(fields.containsKey("userName"),
            "Should discover userName field")
        assertTrue(fields.containsKey("email"),
            "Should discover email field")
        assertTrue(fields.containsKey("age"),
            "Should discover age field")
        assertTrue(fields.containsKey("active"),
            "Should discover active field")

        // Verify field name matches map key
        val userIdField = fields["userId"]
        assertEquals("userId", userIdField?.name, "userId field name should be 'userId'")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
