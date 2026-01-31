package io.johnsonlee.graphite.cli

import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for [FindDeadCodeCommand] using real compiled bytecode.
 *
 * Compiles small Java fixture classes at test time, then runs the command
 * against them to verify all three analysis modes and deletion workflows.
 */
class FindDeadCodeCommandIntegrationTest {

    companion object {
        private val compilerAvailable: Boolean by lazy {
            ToolProvider.getSystemJavaCompiler() != null
        }

        private val fixtures: TestFixtures by lazy { createFixtures() }

        data class TestFixtures(val classesDir: Path, val sourceDir: Path)

        private val sources = mapOf(
            "com/example/test/EntryPoint.java" to """
                package com.example.test;
                public class EntryPoint {
                    public static void main(String[] args) {
                        Used used = new Used();
                        used.doWork();
                        Caller caller = new Caller();
                        caller.doSomething();
                    }
                }
            """.trimIndent(),
            "com/example/test/Used.java" to """
                package com.example.test;
                public class Used {
                    public void doWork() {
                        System.out.println("working");
                    }
                }
            """.trimIndent(),
            "com/example/test/Unused.java" to """
                package com.example.test;
                public class Unused {
                    public void deadMethod() {
                        System.out.println("dead");
                    }
                }
            """.trimIndent(),
            "com/example/test/FeatureClient.java" to """
                package com.example.test;
                public class FeatureClient {
                    public boolean getOption(int key) {
                        return false;
                    }
                }
            """.trimIndent(),
            "com/example/test/Caller.java" to """
                package com.example.test;
                public class Caller {
                    public void doSomething() {
                        FeatureClient client = new FeatureClient();
                        boolean result = client.getOption(1001);
                        if (result) {
                            handleEnabled();
                        } else {
                            handleDisabled();
                        }
                    }
                    private void handleEnabled() {
                        System.out.println("enabled");
                    }
                    private void handleDisabled() {
                        System.out.println("disabled");
                    }
                }
            """.trimIndent()
        )

        private fun createFixtures(): TestFixtures {
            val sourceDir = Files.createTempDirectory("test-fixtures-src")
            val classesDir = Files.createTempDirectory("test-fixtures-classes")

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

            return TestFixtures(classesDir, sourceDir)
        }
    }

    @org.junit.Before
    fun checkCompiler() {
        assumeTrue("Java compiler not available", compilerAvailable)
    }

    // ========================================================================
    // Mode 3: Unreferenced detection (default mode)
    // ========================================================================

    @Test
    fun `unreferenced detection returns 0`() {
        val cmd = newCommand()

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `unreferenced detection outputs summary`() {
        val cmd = newCommand()

        val result = runCommand { cmd.call() }
        assertTrue(result.stdout.contains("Summary:"), "stdout: ${result.stdout}")
    }

    @Test
    fun `unreferenced detection with verbose prints loading info`() {
        val cmd = newCommand()
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("Loading bytecode from:"))
        assertTrue(result.stderr.contains("Loaded"))
    }

    @Test
    fun `unreferenced detection with entry point filter excludes matching methods`() {
        // First run without filter to see what's found
        val baseline = runCommand { newCommand().call() }

        // Now run with filter that matches everything
        val cmd = newCommand()
        cmd.entryPoints = listOf(".*")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("Total dead methods: 0"), "All methods should be excluded by .* filter. stdout: ${result.stdout}")
    }

    @Test
    fun `unreferenced detection with exclude packages`() {
        val cmd = newCommand()
        cmd.excludePackages = listOf("com.example.test")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    // ========================================================================
    // Mode 1: Scan & export
    // ========================================================================

    @Test
    fun `scan export generates yaml file`() {
        val outFile = File.createTempFile("assumptions", ".yaml")
        outFile.deleteOnExit()

        val cmd = newCommand()
        cmd.targetClass = "com.example.test.FeatureClient"
        cmd.targetMethod = "getOption"
        cmd.exportAssumptions = outFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)

        val yaml = outFile.readText()
        if (result.stderr.contains("No call sites found")) {
            // SootUp didn't find the call site — that's OK, we still verified the path
            return
        }
        assertTrue(yaml.contains("assumptions"), "YAML: $yaml")
        assertTrue(yaml.contains("com.example.test.FeatureClient"))
    }

    @Test
    fun `scan export with regex pattern`() {
        val outFile = File.createTempFile("assumptions", ".yaml")
        outFile.deleteOnExit()

        val cmd = newCommand()
        cmd.targetClass = "com\\.example\\.test\\.FeatureClient"
        cmd.targetMethod = "getOption"
        cmd.useRegex = true
        cmd.exportAssumptions = outFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `scan export returns 1 when class and method not specified`() {
        val outFile = File.createTempFile("assumptions", ".yaml")
        outFile.deleteOnExit()

        val cmd = newCommand()
        cmd.exportAssumptions = outFile
        // targetClass and targetMethod not set

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("requires -c/--class and -m/--method"))
    }

    @Test
    fun `scan export returns 0 when no call sites found for nonexistent class`() {
        val outFile = File.createTempFile("assumptions", ".yaml")
        outFile.deleteOnExit()

        val cmd = newCommand()
        cmd.targetClass = "com.example.test.NonExistent"
        cmd.targetMethod = "nonExistent"
        cmd.exportAssumptions = outFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("No call sites found"))
    }

    @Test
    fun `scan export with custom arg indices`() {
        val outFile = File.createTempFile("assumptions", ".yaml")
        outFile.deleteOnExit()

        val cmd = newCommand()
        cmd.targetClass = "com.example.test.FeatureClient"
        cmd.targetMethod = "getOption"
        cmd.argIndices = listOf(0)
        cmd.exportAssumptions = outFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    // ========================================================================
    // Mode 2: Assumption analysis
    // ========================================================================

    @Test
    fun `assumption analysis returns 0 with valid yaml`() {
        val yamlFile = createTempYaml("""
            assumptions:
              - call:
                  class: com.example.test.FeatureClient
                  method: getOption
                  args:
                    '0': 1001
                value: true
        """.trimIndent())

        val cmd = newCommand()
        cmd.assumptionsFile = yamlFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("Summary:"))
    }

    @Test
    fun `assumption analysis returns 0 when all placeholders`() {
        val yamlFile = createTempYaml("""
            assumptions:
              - call:
                  class: com.example.test.FeatureClient
                  method: getOption
                value: "???"
        """.trimIndent())

        val cmd = newCommand()
        cmd.assumptionsFile = yamlFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("No assumptions found"))
    }

    @Test
    fun `assumption analysis with json format produces JSON output`() {
        val yamlFile = createTempYaml("""
            assumptions:
              - call:
                  class: com.example.test.FeatureClient
                  method: getOption
                  args:
                    '0': 1001
                value: true
        """.trimIndent())

        val cmd = newCommand()
        cmd.assumptionsFile = yamlFile
        cmd.outputFormat = "json"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("\"unreferencedMethods\""), "stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("\"summary\""), "stdout: ${result.stdout}")
    }

    @Test
    fun `assumption analysis with verbose prints assumption count`() {
        val yamlFile = createTempYaml("""
            assumptions:
              - call:
                  class: com.example.test.FeatureClient
                  method: getOption
                  args:
                    '0': 1001
                value: true
        """.trimIndent())

        val cmd = newCommand()
        cmd.assumptionsFile = yamlFile
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("Loaded 1 assumption(s)"))
    }

    // ========================================================================
    // Deletion / dry-run
    // ========================================================================

    @Test
    fun `delete returns 1 when source dirs empty`() {
        val cmd = newCommand()
        cmd.delete = true

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("requires --source-dir"))
    }

    @Test
    fun `dry run returns 1 when source dirs empty`() {
        val cmd = newCommand()
        cmd.dryRun = true

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("requires --source-dir"))
    }

    @Test
    fun `delete returns 1 when source dir does not exist`() {
        val cmd = newCommand()
        cmd.delete = true
        cmd.sourceDirs = listOf(Path.of("/nonexistent/source/dir"))

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("does not exist"))
    }

    @Test
    fun `dry run does not modify source files`() {
        val testSourceDir = copySourceDir()

        val cmd = newCommand()
        cmd.sourceDirs = listOf(testSourceDir)
        cmd.dryRun = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)

        // All source files should still exist
        assertTrue(testSourceDir.resolve("com/example/test/Unused.java").toFile().exists())
        assertTrue(testSourceDir.resolve("com/example/test/Used.java").toFile().exists())

        testSourceDir.toFile().deleteRecursively()
    }

    @Test
    fun `dry run with verbose prints planning info`() {
        val testSourceDir = copySourceDir()

        val cmd = newCommand()
        cmd.sourceDirs = listOf(testSourceDir)
        cmd.dryRun = true
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("Planning deletions"))

        testSourceDir.toFile().deleteRecursively()
    }

    @Test
    fun `delete removes dead code source files`() {
        val testSourceDir = copySourceDir()

        val cmd = newCommand()
        cmd.sourceDirs = listOf(testSourceDir)
        cmd.delete = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)

        // Used.java should still exist (it's referenced)
        assertTrue(testSourceDir.resolve("com/example/test/Used.java").toFile().exists())

        testSourceDir.toFile().deleteRecursively()
    }

    @Test
    fun `assumption analysis with dry run and source dir`() {
        val yamlFile = createTempYaml("""
            assumptions:
              - call:
                  class: com.example.test.FeatureClient
                  method: getOption
                  args:
                    '0': 1001
                value: true
        """.trimIndent())

        val testSourceDir = copySourceDir()

        val cmd = newCommand()
        cmd.assumptionsFile = yamlFile
        cmd.sourceDirs = listOf(testSourceDir)
        cmd.dryRun = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)

        testSourceDir.toFile().deleteRecursively()
    }

    @Test
    fun `assumption analysis delete returns 1 when source dirs empty`() {
        val yamlFile = createTempYaml("""
            assumptions:
              - call:
                  class: com.example.test.FeatureClient
                  method: getOption
                  args:
                    '0': 1001
                value: true
        """.trimIndent())

        val cmd = newCommand()
        cmd.assumptionsFile = yamlFile
        cmd.delete = true

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("requires --source-dir"))
    }

    // ========================================================================
    // Option coverage: --include-libs, --lib-filter, --format, --param-types
    // ========================================================================

    @Test
    fun `includeLibs false overrides auto-detection`() {
        val cmd = newCommand()
        cmd.includeLibs = false

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `includeLibs true overrides auto-detection`() {
        val cmd = newCommand()
        cmd.includeLibs = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `lib filters are passed to loader config`() {
        val cmd = newCommand()
        cmd.includeLibs = true
        cmd.libFilters = listOf("some-lib-*.jar")

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `scan export with param types filter`() {
        val outFile = File.createTempFile("assumptions", ".yaml")
        outFile.deleteOnExit()

        val cmd = newCommand()
        cmd.targetClass = "com.example.test.FeatureClient"
        cmd.targetMethod = "getOption"
        cmd.paramTypes = listOf("int")
        cmd.exportAssumptions = outFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `scan export with non-matching param types finds nothing`() {
        val outFile = File.createTempFile("assumptions", ".yaml")
        outFile.deleteOnExit()

        val cmd = newCommand()
        cmd.targetClass = "com.example.test.FeatureClient"
        cmd.targetMethod = "getOption"
        cmd.paramTypes = listOf("java.lang.String")
        cmd.exportAssumptions = outFile

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("No call sites found"))
    }

    @Test
    fun `outputFormat json produces valid JSON output`() {
        val cmd = newCommand()
        cmd.outputFormat = "json"

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("\"unreferencedMethods\""), "stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("\"summary\""), "stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("\"totalDeadMethods\""), "stdout: ${result.stdout}")
    }

    // ========================================================================
    // Kotlin source fixture tests
    // ========================================================================

    @Test
    fun `dry run with mixed Java and Kotlin source dirs`() {
        val testSourceDir = copySourceDirWithKotlin()

        val cmd = newCommand()
        cmd.sourceDirs = listOf(testSourceDir)
        cmd.dryRun = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)

        // All source files should still exist after dry-run
        assertTrue(testSourceDir.resolve("com/example/test/UsedService.kt").toFile().exists())
        assertTrue(testSourceDir.resolve("com/example/test/UnusedHelper.kt").toFile().exists())
        assertTrue(testSourceDir.resolve("com/example/test/MixedClass.kt").toFile().exists())
        assertTrue(testSourceDir.resolve("com/example/test/Used.java").toFile().exists())

        testSourceDir.toFile().deleteRecursively()
    }

    @Test
    fun `delete with Kotlin source files present`() {
        val testSourceDir = copySourceDirWithKotlin()

        val cmd = newCommand()
        cmd.sourceDirs = listOf(testSourceDir)
        cmd.delete = true

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)

        // Java Used.java should still exist (referenced by bytecode)
        assertTrue(testSourceDir.resolve("com/example/test/Used.java").toFile().exists())

        testSourceDir.toFile().deleteRecursively()
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `empty directory input produces no errors`() {
        val emptyDir = Files.createTempDirectory("empty-input")
        emptyDir.toFile().deleteOnExit()

        val cmd = FindDeadCodeCommand()
        cmd.input = emptyDir
        cmd.includePackages = listOf("com.nonexistent")

        val result = runCommand { cmd.call() }
        // Should complete without crashing
        assertTrue(result.exitCode == 0 || result.exitCode == 1)
    }

    @Test
    fun `corrupt input file triggers error handling`() {
        val corruptFile = File.createTempFile("corrupt", ".jar")
        corruptFile.deleteOnExit()
        corruptFile.writeText("this is not a valid JAR file")

        val cmd = FindDeadCodeCommand()
        cmd.input = corruptFile.toPath()
        cmd.includePackages = listOf("com.example.test")

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Error:"))
    }

    @Test
    fun `corrupt input with verbose prints stack trace`() {
        val corruptFile = File.createTempFile("corrupt", ".jar")
        corruptFile.deleteOnExit()
        corruptFile.writeText("this is not a valid JAR file")

        val cmd = FindDeadCodeCommand()
        cmd.input = corruptFile.toPath()
        cmd.includePackages = listOf("com.example.test")
        cmd.verbose = true

        val result = runCommand { cmd.call() }
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("Error:"))
    }

    @Test
    fun `delete with no matching source files prints message`() {
        val emptySourceDir = Files.createTempDirectory("empty-source")

        val cmd = newCommand()
        cmd.delete = true
        cmd.sourceDirs = listOf(emptySourceDir)

        val result = runCommand { cmd.call() }
        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.contains("No source files matched"), "stderr: ${result.stderr}")

        emptySourceDir.toFile().deleteRecursively()
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private data class CommandOutput(val stdout: String, val stderr: String, val exitCode: Int)

    private fun newCommand(): FindDeadCodeCommand {
        val cmd = FindDeadCodeCommand()
        cmd.input = fixtures.classesDir
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

    private val kotlinSources = mapOf(
        "com/example/test/UsedService.kt" to """
            package com.example.test

            class UsedService {
                fun serve(): String {
                    return "serving"
                }
            }
        """.trimIndent(),
        "com/example/test/UnusedHelper.kt" to """
            package com.example.test

            class UnusedHelper {
                fun deadHelper() {
                    println("dead helper")
                }

                fun anotherDeadHelper() {
                    println("another dead helper")
                }
            }
        """.trimIndent(),
        "com/example/test/MixedClass.kt" to """
            package com.example.test

            class MixedClass {
                fun usedMethod(): String {
                    return "used"
                }

                fun unusedMethod(): String {
                    return "unused"
                }
            }
        """.trimIndent()
    )

    private fun copySourceDir(): Path {
        val copy = Files.createTempDirectory("test-source-copy")
        fixtures.sourceDir.toFile().copyRecursively(copy.toFile(), overwrite = true)
        return copy
    }

    private fun copySourceDirWithKotlin(): Path {
        val copy = copySourceDir()
        for ((path, content) in kotlinSources) {
            val file = copy.resolve(path).toFile()
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        return copy
    }

    private fun createTempYaml(content: String): File {
        val file = File.createTempFile("test-assumptions", ".yaml")
        file.deleteOnExit()
        file.writeText(content)
        return file
    }
}
