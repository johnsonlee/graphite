package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Test annotation extraction from Spring MVC annotations.
 * Verifies that SootUpAdapter stores all annotations as member annotations.
 */
class EndpointExtractionTest {

    private val expectedMappingAnnotations = mapOf(
        "getUser" to "org.springframework.web.bind.annotation.GetMapping",
        "listUsers" to "org.springframework.web.bind.annotation.GetMapping",
        "createUser" to "org.springframework.web.bind.annotation.PostMapping",
        "updateUser" to "org.springframework.web.bind.annotation.PutMapping",
        "deleteUser" to "org.springframework.web.bind.annotation.DeleteMapping",
        "getOrder" to "org.springframework.web.bind.annotation.GetMapping",
        "healthCheck" to "org.springframework.web.bind.annotation.RequestMapping"
    )

    @Test
    fun `should store Spring MVC annotations as member annotations`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.api"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // Find methods with Spring mapping annotations
        val methods = graph.methods(MethodPattern()).toList()
        val springMappingFqns = setOf(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping"
        )

        val endpointMethods = methods.filter { method ->
            val annotations = graph.memberAnnotations(method.declaringClass.className, method.name)
            annotations.keys.any { it in springMappingFqns }
        }

        println("Found ${endpointMethods.size} endpoint methods:")
        endpointMethods.forEach { method ->
            val annotations = graph.memberAnnotations(method.declaringClass.className, method.name)
            val mappingAnnotation = annotations.keys.first { it in springMappingFqns }
            println("  ${method.name} -> $mappingAnnotation ${annotations[mappingAnnotation]}")
        }

        assertTrue(endpointMethods.isNotEmpty(), "Should find at least one endpoint method")

        // Verify expected methods have their mapping annotations
        expectedMappingAnnotations.forEach { (methodName, expectedAnnotation) ->
            val found = endpointMethods.any { method ->
                val annotations = graph.memberAnnotations(method.declaringClass.className, method.name)
                method.name == methodName && annotations.containsKey(expectedAnnotation)
            }
            assertTrue(found, "Should find method '$methodName' with annotation '$expectedAnnotation'")
        }
    }

    @Test
    fun `should store class-level RequestMapping as class annotation`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.api"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Find classes with @RequestMapping at class level
        val methods = graph.methods(MethodPattern()).toList()
        val classNames = methods.map { it.declaringClass.className }.distinct()

        val classesWithMapping = classNames.filter { className ->
            val classAnnotations = graph.memberAnnotations(className, "<class>")
            classAnnotations.containsKey("org.springframework.web.bind.annotation.RequestMapping")
        }

        println("Classes with @RequestMapping:")
        classesWithMapping.forEach { className ->
            val annotations = graph.memberAnnotations(className, "<class>")
            println("  $className -> ${annotations["org.springframework.web.bind.annotation.RequestMapping"]}")
        }

        assertTrue(classesWithMapping.isNotEmpty(), "Should find at least one class with @RequestMapping")
    }

    @Test
    fun `should store all annotations on methods including non-Spring ones`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.api"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Check that methods have their annotations stored
        val methods = graph.methods(MethodPattern()).toList()
        val methodsWithAnnotations = methods.filter { method ->
            val annotations = graph.memberAnnotations(method.declaringClass.className, method.name)
            annotations.isNotEmpty()
        }

        println("Methods with annotations: ${methodsWithAnnotations.size}")
        assertTrue(methodsWithAnnotations.isNotEmpty(), "Should find methods with annotations")
    }

    @Test
    fun `AnnotationNodes are created from sample controllers`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.api"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        val annotations = graph.nodes(AnnotationNode::class.java).toList()
        assertTrue(annotations.isNotEmpty(), "Should have AnnotationNodes")

        // Check that GetMapping annotations are present
        val getMappings = annotations.filter { it.name.contains("GetMapping") }
        assertTrue(getMappings.isNotEmpty(), "Should have @GetMapping annotations")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
