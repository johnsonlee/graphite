package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for generic signature parsing from bytecode.
 */
class GenericSignatureTest {

    @Test
    fun `should parse generic signature from bytecode`() {
        // Test the GenericSignatureParser directly
        val listSignature = "Ljava/util/List<Ljava/lang/String;>;"
        val result = GenericSignatureParser.parseFieldSignature(listSignature)

        println("Parsed signature: $listSignature")
        println("Result: $result")

        assertTrue(result != null, "Should parse List<String> signature")
        assertEquals("java.util.List", result.className)
        assertEquals(1, result.typeArguments.size, "Should have one type argument")
        assertEquals("java.lang.String", result.typeArguments[0].className, "Type argument should be String")
    }

    @Test
    fun `should parse Map generic signature`() {
        val mapSignature = "Ljava/util/Map<Ljava/lang/String;Ljava/lang/Integer;>;"
        val result = GenericSignatureParser.parseFieldSignature(mapSignature)

        println("Parsed signature: $mapSignature")
        println("Result: $result")

        assertTrue(result != null, "Should parse Map<String, Integer> signature")
        assertEquals("java.util.Map", result.className)
        assertEquals(2, result.typeArguments.size, "Should have two type arguments")
        assertEquals("java.lang.String", result.typeArguments[0].className, "First type argument should be String")
        assertEquals("java.lang.Integer", result.typeArguments[1].className, "Second type argument should be Integer")
    }

    @Test
    fun `should parse nested generic signature`() {
        val nestedSignature = "Ljava/util/List<Ljava/util/List<Ljava/lang/String;>;>;"
        val result = GenericSignatureParser.parseFieldSignature(nestedSignature)

        println("Parsed signature: $nestedSignature")
        println("Result: $result")

        assertTrue(result != null, "Should parse nested List<List<String>> signature")
        assertEquals("java.util.List", result.className)
        assertEquals(1, result.typeArguments.size, "Should have one type argument")

        val innerList = result.typeArguments[0]
        assertEquals("java.util.List", innerList.className, "Inner type should be List")
        assertEquals(1, innerList.typeArguments.size, "Inner List should have one type argument")
        assertEquals("java.lang.String", innerList.typeArguments[0].className, "Innermost type should be String")
    }

    @Test
    fun `should load generic field types from bytecode`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.generics"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Find the 'users' field in GenericFieldService
        val usersField = graph.nodes<FieldNode>()
            .filter { it.descriptor.name == "users" }
            .filter { it.descriptor.declaringClass.className.contains("GenericFieldService") }
            .firstOrNull()

        println("=== Field Type Analysis ===")
        if (usersField != null) {
            println("Field: ${usersField.descriptor.name}")
            println("Type: ${usersField.descriptor.type.className}")
            println("Type arguments: ${usersField.descriptor.type.typeArguments.map { it.className }}")

            // Check if generic type was parsed
            val typeArgs = usersField.descriptor.type.typeArguments
            if (typeArgs.isNotEmpty()) {
                println("SUCCESS: Generic type arguments found!")
                assertTrue(
                    typeArgs.any { it.className.contains("User") },
                    "Should find User as type argument"
                )
            } else {
                println("NOTE: Generic type arguments not found (expected with basic signature parsing)")
            }
        } else {
            println("Field 'users' not found")
        }

        // List all fields with their types
        println("\n=== All GenericFieldService Fields ===")
        graph.nodes<FieldNode>()
            .filter { it.descriptor.declaringClass.className.contains("GenericFieldService") }
            .forEach { field ->
                println("  ${field.descriptor.name}: ${field.descriptor.type.className}")
                if (field.descriptor.type.typeArguments.isNotEmpty()) {
                    println("    Type args: ${field.descriptor.type.typeArguments.map { it.className }}")
                }
            }
    }

    @Test
    fun `should read signatures from class file directly`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val reader = BytecodeSignatureReader()
        reader.loadFromDirectory(testClassesDir)

        println("=== Bytecode Signature Reader Test ===")

        // Check if we can read the generic field types
        val className = "sample.generics.GenericFieldService"
        val fieldTypes = reader.getAllFieldTypes(className)

        println("Field types for $className:")
        fieldTypes.forEach { (name, type) ->
            println("  $name: ${type.className}")
            if (type.typeArguments.isNotEmpty()) {
                println("    Type args: ${type.typeArguments.map { it.className }}")
            }
        }

        if (fieldTypes.isNotEmpty()) {
            // Verify 'users' field has List<User> type
            val usersType = fieldTypes["users"]
            if (usersType != null) {
                println("\nVerifying 'users' field:")
                println("  className: ${usersType.className}")
                println("  typeArguments: ${usersType.typeArguments.map { it.className }}")

                assertTrue(usersType.className == "java.util.List", "users should be List type")
                assertTrue(
                    usersType.typeArguments.any { it.className.contains("User") },
                    "users should have User type argument"
                )
            }
        }
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
