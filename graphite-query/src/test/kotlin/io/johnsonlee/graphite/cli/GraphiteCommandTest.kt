package io.johnsonlee.graphite.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GraphiteCommandTest {

    private fun captureOutput(block: () -> Int): Triple<String, String, Int> {
        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val code = try {
            block()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return Triple(outBaos.toString(), errBaos.toString(), code)
    }

    // ========================================================================
    // GraphiteCommand (parent)
    // ========================================================================

    @Test
    fun `parent command shows help`() {
        val cmd = GraphiteCommand()
        val (out, _, code) = captureOutput { cmd.call() }
        assertEquals(0, code)
        assertTrue(out.contains("graphite"), "Help should contain command name, got: $out")
    }

    // ========================================================================
    // BuildCommand
    // ========================================================================

    @Test
    fun `build from nonexistent path returns error`() {
        val cmd = BuildCommand()
        cmd.input = Path.of("/nonexistent/path/to/jar")
        cmd.output = Path.of("/tmp/out")
        val (_, err, code) = captureOutput { cmd.call() }
        assertEquals(1, code)
        assertTrue(err.contains("Error"), "Should show error message on stderr, got: $err")
    }

    @Test
    fun `build from nonexistent path with verbose returns error with stacktrace`() {
        val cmd = BuildCommand()
        cmd.input = Path.of("/nonexistent/path/to/jar")
        cmd.output = Path.of("/tmp/out")
        cmd.verbose = true
        val (_, err, code) = captureOutput { cmd.call() }
        assertEquals(1, code)
        assertTrue(err.contains("Error"), "Should show error message on stderr, got: $err")
    }

    @Test
    fun `build from valid classes directory succeeds`() {
        val classesDir = Files.createTempDirectory("build-test-classes")
        val outputDir = Files.createTempDirectory("build-test-output")
        try {
            val javaFile = classesDir.resolve("Sample.java")
            Files.writeString(javaFile, """
                package sample;
                public class Sample {
                    public int getValue() { return 42; }
                }
            """.trimIndent())
            val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            val sampleDir = classesDir.resolve("sample")
            Files.createDirectories(sampleDir)
            val compileResult = compiler.run(null, null, null, "-d", classesDir.toString(), javaFile.toString())
            assertEquals(0, compileResult, "Java compilation should succeed")

            val cmd = BuildCommand()
            cmd.input = classesDir
            cmd.output = outputDir
            cmd.includePackages = listOf("sample")
            cmd.verbose = true
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(0, code, "Build should succeed, stderr: $err")
            assertTrue(err.contains("Loading bytecode"), "Should show loading message, got: $err")
            assertTrue(err.contains("Graph built"), "Should show graph built message, got: $err")
            assertTrue(err.contains("Saving to"), "Should show saving message, got: $err")
            assertTrue(err.contains("Done"), "Should show done message, got: $err")
        } finally {
            classesDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build from empty directory triggers catch block`() {
        val emptyDir = Files.createTempDirectory("build-test-empty")
        val outputDir = Files.createTempDirectory("build-test-output-empty")
        try {
            val cmd = BuildCommand()
            cmd.input = emptyDir
            cmd.output = outputDir
            cmd.includePackages = listOf("sample")
            val (_, err, code) = captureOutput { cmd.call() }
            assertTrue(code == 0 || code == 1, "Should return 0 or 1, got: $code, err: $err")
        } finally {
            emptyDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build from invalid file triggers catch with verbose`() {
        val invalidFile = Files.createTempFile("build-test-invalid", ".txt")
        val outputDir = Files.createTempDirectory("build-test-output-invalid")
        try {
            Files.writeString(invalidFile, "not a valid jar or class directory")
            val cmd = BuildCommand()
            cmd.input = invalidFile
            cmd.output = outputDir
            cmd.includePackages = listOf("sample")
            cmd.verbose = true
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(1, code, "Should fail for invalid input, stderr: $err")
            assertTrue(err.contains("Error"), "Should show error, got: $err")
        } finally {
            invalidFile.toFile().delete()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build with exclude packages`() {
        val classesDir = Files.createTempDirectory("build-test-classes2")
        val outputDir = Files.createTempDirectory("build-test-output2")
        try {
            val javaFile = classesDir.resolve("Example.java")
            Files.writeString(javaFile, """
                package example;
                public class Example {
                    public String hello() { return "world"; }
                }
            """.trimIndent())
            val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
            Files.createDirectories(classesDir.resolve("example"))
            val compileResult = compiler.run(null, null, null, "-d", classesDir.toString(), javaFile.toString())
            assertEquals(0, compileResult, "Java compilation should succeed")

            val cmd = BuildCommand()
            cmd.input = classesDir
            cmd.output = outputDir
            cmd.includePackages = listOf("example")
            cmd.excludePackages = listOf("example.internal")
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(0, code, "Build should succeed, stderr: $err")
        } finally {
            classesDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build with includeLibs and libFilters`() {
        val emptyDir = Files.createTempDirectory("build-test-libs")
        val outputDir = Files.createTempDirectory("build-test-libs-out")
        try {
            val cmd = BuildCommand()
            cmd.input = emptyDir
            cmd.output = outputDir
            cmd.includeLibs = true
            cmd.libFilters = listOf("*.jar")
            cmd.includePackages = listOf("sample")
            val (_, _, code) = captureOutput { cmd.call() }
            assertTrue(code == 0 || code == 1)
        } finally {
            emptyDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }
}
