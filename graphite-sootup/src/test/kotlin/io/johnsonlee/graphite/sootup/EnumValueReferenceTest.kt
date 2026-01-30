package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for enum value references — when one enum's constructor argument
 * is a reference to another enum constant.
 *
 * Example patterns:
 * ```java
 * enum TaskConfig {
 *     URGENT(Priority.HIGH),     // constructor arg is another enum constant
 *     NORMAL(Priority.MEDIUM);
 *     TaskConfig(Priority p) { ... }
 * }
 *
 * enum TaskConfigWithId {
 *     URGENT_TASK(100, Priority.HIGH),  // mixed: int + enum reference
 *     NORMAL_TASK(200, Priority.MEDIUM);
 *     TaskConfigWithId(int id, Priority p) { ... }
 * }
 * ```
 */
class EnumValueReferenceTest {

    @Test
    fun `should extract enum value references from TaskConfig`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // Verify TaskConfig.URGENT has Priority.HIGH as constructor arg
        val urgentValues = graph.enumValues("sample.ab.TaskConfig", "URGENT")
        println("TaskConfig.URGENT values: $urgentValues")
        assertNotNull(urgentValues, "Should extract values for TaskConfig.URGENT")
        assertEquals(1, urgentValues.size, "TaskConfig.URGENT should have 1 constructor arg")
        val urgentRef = urgentValues[0]
        assertTrue(urgentRef is EnumValueReference, "Constructor arg should be EnumValueReference, got: ${urgentRef?.javaClass?.simpleName}")
        assertEquals("sample.ab.Priority", (urgentRef as EnumValueReference).enumClass)
        assertEquals("HIGH", urgentRef.enumName)

        // Verify TaskConfig.NORMAL has Priority.MEDIUM as constructor arg
        val normalValues = graph.enumValues("sample.ab.TaskConfig", "NORMAL")
        println("TaskConfig.NORMAL values: $normalValues")
        assertNotNull(normalValues, "Should extract values for TaskConfig.NORMAL")
        assertEquals(1, normalValues.size, "TaskConfig.NORMAL should have 1 constructor arg")
        val normalRef = normalValues[0]
        assertTrue(normalRef is EnumValueReference, "Constructor arg should be EnumValueReference, got: ${normalRef?.javaClass?.simpleName}")
        assertEquals("sample.ab.Priority", (normalRef as EnumValueReference).enumClass)
        assertEquals("MEDIUM", normalRef.enumName)

        // Verify TaskConfig.DEFERRED has Priority.LOW as constructor arg
        val deferredValues = graph.enumValues("sample.ab.TaskConfig", "DEFERRED")
        println("TaskConfig.DEFERRED values: $deferredValues")
        assertNotNull(deferredValues, "Should extract values for TaskConfig.DEFERRED")
        assertEquals(1, deferredValues.size, "TaskConfig.DEFERRED should have 1 constructor arg")
        val deferredRef = deferredValues[0]
        assertTrue(deferredRef is EnumValueReference, "Constructor arg should be EnumValueReference, got: ${deferredRef?.javaClass?.simpleName}")
        assertEquals("sample.ab.Priority", (deferredRef as EnumValueReference).enumClass)
        assertEquals("LOW", deferredRef.enumName)
    }

    @Test
    fun `should extract mixed constructor args with enum references`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false,
            verbose = { println(it) }
        ))

        val graph = loader.load(testClassesDir)

        // Verify TaskConfigWithId.URGENT_TASK has [100, Priority.HIGH]
        val urgentValues = graph.enumValues("sample.ab.TaskConfigWithId", "URGENT_TASK")
        println("TaskConfigWithId.URGENT_TASK values: $urgentValues")
        assertNotNull(urgentValues, "Should extract values for TaskConfigWithId.URGENT_TASK")
        assertEquals(2, urgentValues.size, "TaskConfigWithId.URGENT_TASK should have 2 constructor args")
        assertEquals(100, urgentValues[0], "First arg should be int 100")
        val urgentRef = urgentValues[1]
        assertTrue(urgentRef is EnumValueReference, "Second arg should be EnumValueReference, got: ${urgentRef?.javaClass?.simpleName}")
        assertEquals("sample.ab.Priority", (urgentRef as EnumValueReference).enumClass)
        assertEquals("HIGH", urgentRef.enumName)

        // Verify TaskConfigWithId.NORMAL_TASK has [200, Priority.MEDIUM]
        val normalValues = graph.enumValues("sample.ab.TaskConfigWithId", "NORMAL_TASK")
        println("TaskConfigWithId.NORMAL_TASK values: $normalValues")
        assertNotNull(normalValues, "Should extract values for TaskConfigWithId.NORMAL_TASK")
        assertEquals(2, normalValues.size)
        assertEquals(200, normalValues[0], "First arg should be int 200")
        val normalRef = normalValues[1]
        assertTrue(normalRef is EnumValueReference, "Second arg should be EnumValueReference")
        assertEquals("sample.ab.Priority", (normalRef as EnumValueReference).enumClass)
        assertEquals("MEDIUM", normalRef.enumName)

        // Verify TaskConfigWithId.DEFERRED_TASK has [300, Priority.LOW]
        val deferredValues = graph.enumValues("sample.ab.TaskConfigWithId", "DEFERRED_TASK")
        println("TaskConfigWithId.DEFERRED_TASK values: $deferredValues")
        assertNotNull(deferredValues, "Should extract values for TaskConfigWithId.DEFERRED_TASK")
        assertEquals(2, deferredValues.size)
        assertEquals(300, deferredValues[0], "First arg should be int 300")
        val deferredRef = deferredValues[1]
        assertTrue(deferredRef is EnumValueReference, "Second arg should be EnumValueReference")
        assertEquals("sample.ab.Priority", (deferredRef as EnumValueReference).enumClass)
        assertEquals("LOW", deferredRef.enumName)
    }

    @Test
    fun `should still extract primitive enum values correctly`() {
        val testClassesDir = findTestClassesDir()
        assertTrue(testClassesDir.exists(), "Test classes directory should exist: $testClassesDir")

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample.ab"),
            buildCallGraph = false
        ))

        val graph = loader.load(testClassesDir)

        // Verify Priority enum itself still has correct primitive values
        val highValues = graph.enumValues("sample.ab.Priority", "HIGH")
        assertNotNull(highValues, "Should extract values for Priority.HIGH")
        assertEquals(1, highValues.size)
        assertEquals(1, highValues[0], "Priority.HIGH should have value 1")

        val mediumValues = graph.enumValues("sample.ab.Priority", "MEDIUM")
        assertNotNull(mediumValues, "Should extract values for Priority.MEDIUM")
        assertEquals(2, mediumValues[0], "Priority.MEDIUM should have value 2")

        val lowValues = graph.enumValues("sample.ab.Priority", "LOW")
        assertNotNull(lowValues, "Should extract values for Priority.LOW")
        assertEquals(3, lowValues[0], "Priority.LOW should have value 3")

        // Also verify ExperimentId still works (regression check)
        val checkoutValues = graph.enumValues("sample.ab.ExperimentId", "CHECKOUT_V2")
        assertNotNull(checkoutValues, "Should extract values for ExperimentId.CHECKOUT_V2")
        assertEquals(1002, checkoutValues[0], "ExperimentId.CHECKOUT_V2 should have value 1002")
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
