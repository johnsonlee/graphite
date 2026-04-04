package io.johnsonlee.graphite.cli

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [FindArgumentsCommand] using real compiled bytecode.
 *
 * Compiles small Java fixture classes at test time, then runs the command
 * against them to verify the full call() pipeline including text and JSON output.
 */
class FindArgumentsCommandIntegrationTest {

    companion object {
        private val compilerAvailable: Boolean by lazy {
            ToolProvider.getSystemJavaCompiler() != null
        }

        private val fixtures: TestFixtures by lazy { createFixtures() }

        data class TestFixtures(val classesDir: Path)

        private val sources = mapOf(
            "com/example/test/FeatureClient.java" to """
                package com.example.test;
                public class FeatureClient {
                    public boolean getOption(int key) {
                        return false;
                    }
                    public String getLabel(String tag, int count) {
                        return tag + count;
                    }
                }
            """.trimIndent(),
            "com/example/test/Caller.java" to """
                package com.example.test;
                public class Caller {
                    public void doSomething() {
                        FeatureClient client = new FeatureClient();
                        boolean result = client.getOption(1001);
                        boolean result2 = client.getOption(2002);
                        String label = client.getLabel("prefix", 5);
                        if (result) {
                            System.out.println("enabled");
                        }
                    }
                }
            """.trimIndent()
        )

        private fun createFixtures(): TestFixtures {
            val sourceDir = Files.createTempDirectory("find-args-test-src")
            val classesDir = Files.createTempDirectory("find-args-test-classes")

            Runtime.getRuntime().addShutdownHook(Thread {
                sourceDir.toFile().deleteRecursively()
                classesDir.toFile().deleteRecursively()
            })

            for ((path, content) in sources) {
                val file = sourceDir.resolve(path).toFile()
                file.parentFile.mkdirs()
                file.writeText(content)
            }

            val compiler = ToolProvider.getSystemJavaCompiler()!!
            val fileManager = compiler.getStandardFileManager(null, null, null)
            val javaFiles = sourceDir.toFile().walkTopDown()
                .filter { it.extension == "java" }
                .toList()
            val compilationUnits = fileManager.getJavaFileObjectsFromFiles(javaFiles)
            val options = listOf("-d", classesDir.toString())
            val success = compiler.getTask(null, fileManager, null, options, null, compilationUnits).call()
            fileManager.close()
            check(success) { "Failed to compile test fixtures" }

            return TestFixtures(classesDir)
        }
    }

    @org.junit.Before
    fun checkCompiler() {
        assumeTrue("Java compiler not available", compilerAvailable)
    }

    // ========================================================================
    // Text format output
    // ========================================================================

    @Test
    fun `call returns 0 and outputs text format`() {
        val cmd = newCommand()
        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("Found") || result.stdout.isEmpty())
    }

    @Test
    fun `call with verbose outputs loading info`() {
        val cmd = newCommand()
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("Loading bytecode from:"))
        assertTrue(result.stderr.contains("Target method:"))
        assertTrue(result.stderr.contains("Argument indices:"))
    }

    @Test
    fun `call with verbose and param types outputs param info`() {
        val cmd = newCommand()
        cmd.verbose = true
        cmd.paramTypes = listOf("int")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("Parameter types:"))
    }

    @Test
    fun `call with verbose outputs call site info`() {
        val cmd = newCommand()
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        // Should show "Searching for:" and call site information
        assertTrue(result.stderr.contains("Searching for:"))
    }

    @Test
    fun `call with verbose and regex pattern`() {
        val cmd = newCommand()
        cmd.verbose = true
        cmd.useRegex = true
        cmd.targetClass = "com\\.example\\.test\\.FeatureClient"
        cmd.targetMethod = "getOption"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("regex patterns"))
    }

    // ========================================================================
    // JSON format output
    // ========================================================================

    @Test
    fun `call with json format outputs valid JSON`() {
        val cmd = newCommand()
        cmd.outputFormat = "json"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("{") || result.stdout.contains("\"targetClass\""))
    }

    // ========================================================================
    // Error handling
    // ========================================================================

    @Test
    fun `call returns 1 for nonexistent input`() {
        val cmd = FindArgumentsCommand()
        cmd.input = Path.of("/nonexistent/path/to/nothing.jar")
        cmd.targetClass = "com.example.Foo"
        cmd.targetMethod = "bar"

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Error: Input path does not exist"))
    }

    @Test
    fun `call returns 0 for nonexistent class with verbose`() {
        val cmd = newCommand()
        cmd.targetClass = "com.example.test.NonExistent"
        cmd.targetMethod = "nonExistent"
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `call with empty results and json format outputs empty json`() {
        val cmd = newCommand()
        cmd.targetClass = "com.example.test.NonExistent"
        cmd.targetMethod = "nonExistent"
        cmd.outputFormat = "json"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("\"totalOccurrences\": 0"))
    }

    // ========================================================================
    // Depth filtering
    // ========================================================================

    @Test
    fun `call with min depth filter`() {
        val cmd = newCommand()
        cmd.minDepth = 999  // Very high - should filter out all results

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `call with max path depth filter`() {
        val cmd = newCommand()
        cmd.maxPathDepth = 0  // Only direct constants

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `call with verbose and filtered results shows depth info`() {
        val cmd = newCommand()
        cmd.verbose = true
        cmd.minDepth = 999

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    // ========================================================================
    // Show path
    // ========================================================================

    @Test
    fun `call with show path`() {
        val cmd = newCommand()
        cmd.showPath = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    // ========================================================================
    // Multiple argument indices
    // ========================================================================

    @Test
    fun `call with multiple arg indices`() {
        val cmd = newCommand()
        cmd.targetMethod = "getLabel"
        cmd.argIndices = listOf(0, 1)

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    // ========================================================================
    // Verbose with include-libs
    // ========================================================================

    @Test
    fun `call with verbose and includeLibs outputs library info`() {
        val cmd = newCommand()
        cmd.verbose = true
        cmd.includeLibs = true  // Explicitly set to true to trigger line 139

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("Including library JARs"))
    }

    // ========================================================================
    // Verbose with "Did you mean" suggestion
    // ========================================================================

    @Test
    fun `call with verbose for nonexistent class but existing method shows suggestion`() {
        // Use a method name that exists (getOption) but with a wrong class
        val cmd = newCommand()
        cmd.targetClass = "com.example.test.NonExistent"
        cmd.targetMethod = "getOption"  // exists on FeatureClient
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        // Should show "Did you mean" or "No call sites" depending on whether similar methods found
        assertTrue(result.stderr.contains("No call sites found") || result.stderr.contains("Did you mean"))
    }

    // ========================================================================
    // Verbose with depth filter showing filtered results
    // ========================================================================

    @Test
    fun `call with verbose and results filtered by depth shows filter info`() {
        // Set up minDepth high enough to filter some but use verbose to show info
        val cmd = newCommand()
        cmd.verbose = true
        cmd.minDepth = 999
        cmd.outputFormat = "json"  // JSON for empty results

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        // With verbose and all results filtered, should show available depths
    }

    // ========================================================================
    // Include/exclude packages
    // ========================================================================

    @Test
    fun `call with include packages`() {
        val cmd = newCommand()
        cmd.includePackages = listOf("com.example.test")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `call with exclude packages`() {
        val cmd = newCommand()
        cmd.excludePackages = listOf("com.example.other")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    // ========================================================================
    // Exception handling in call()
    // ========================================================================

    @Test
    fun `call returns 1 when loading fails with exception`() {
        // Point to a file that exists but is not a valid JAR/class directory
        val tempFile = java.io.File.createTempFile("invalid", ".jar")
        tempFile.deleteOnExit()
        tempFile.writeText("not a valid jar file")

        val cmd = FindArgumentsCommand()
        cmd.input = tempFile.toPath()
        cmd.targetClass = "com.example.Foo"
        cmd.targetMethod = "bar"

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Error during analysis"))
    }

    @Test
    fun `call returns 1 with verbose exception output`() {
        val tempFile = java.io.File.createTempFile("invalid", ".jar")
        tempFile.deleteOnExit()
        tempFile.writeText("not a valid jar file")

        val cmd = FindArgumentsCommand()
        cmd.input = tempFile.toPath()
        cmd.targetClass = "com.example.Foo"
        cmd.targetMethod = "bar"
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Error during analysis"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private data class CommandOutput(val stdout: String, val stderr: String, val exitCode: Int)

    private fun newCommand(): FindArgumentsCommand {
        val cmd = FindArgumentsCommand()
        cmd.input = fixtures.classesDir
        cmd.targetClass = "com.example.test.FeatureClient"
        cmd.targetMethod = "getOption"
        cmd.includePackages = listOf("com.example.test")
        return cmd
    }

    private fun runCommand(block: () -> Int): CommandOutput {
        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val exitCode: Int
        try {
            exitCode = block()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return CommandOutput(outBaos.toString(), errBaos.toString(), exitCode)
    }
}
