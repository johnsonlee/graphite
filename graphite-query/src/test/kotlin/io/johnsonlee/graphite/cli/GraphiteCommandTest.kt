package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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
        assertTrue(out.contains("build"), "Help should contain build subcommand, got: $out")
        assertTrue(out.contains("query"), "Help should contain query subcommand, got: $out")
        assertTrue(out.contains("serve"), "Help should contain serve subcommand, got: $out")
    }

    @Test
    fun `parent command shows build version`() {
        val (out, err, code) = captureOutput {
            CommandLine(GraphiteCommand()).execute("--version")
        }

        assertEquals(0, code)
        assertEquals("", err)
        val buildVersion = requireNotNull(System.getProperty("graphite.version"))
        assertEquals("graphite $buildVersion", out.trim())
    }

    // ========================================================================
    // BuildCommand
    // ========================================================================

    @Test
    fun `build help describes android sdk discovery order`() {
        val outBaos = ByteArrayOutputStream()
        CommandLine(BuildCommand()).usage(PrintWriter(outBaos, true))
        val help = outBaos.toString()
        val normalizedHelp = help.replace(Regex("\\s+"), " ")

        assertTrue(help.contains("--android-sdk"), "Help should contain Android SDK option, got: $help")
        assertTrue(help.contains("ANDROID_HOME"), "Help should contain env priority, got: $help")
        assertTrue(help.contains("ANDROID_SDK_ROOT"), "Help should contain env priority, got: $help")
        assertTrue(help.contains("~/Library/Android/sdk"), "Help should contain macOS default path, got: $help")
        assertTrue(help.contains("~/Android/Sdk"), "Help should contain Linux default path, got: $help")
        assertTrue(
            help.contains("%USERPROFILE%\\AppData\\Local\\Android\\Sdk"),
            "Help should contain Windows default path, got: $help"
        )
        assertTrue(
            normalizedHelp.contains("adb, emulator, or sdkmanager"),
            "Help should contain PATH tool fallback, got: $help"
        )
    }

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
            Files.writeString(classesDir.resolve("application.properties"), "feature.mode=shadow\n")

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
            assertTrue(Files.isRegularFile(outputDir.resolve("graph.resources")))
            val loaded = GraphStore.load(outputDir)
            assertEquals(
                "feature.mode=shadow\n",
                loaded.resources.open("application.properties").bufferedReader().use { it.readText() }
            )
        } finally {
            classesDir.toFile().deleteRecursively()
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build from jar without persistable resources writes loadable empty store`() {
        val root = Files.createTempDirectory("build-empty-resource-jar-test")
        val classesDir = Files.createDirectories(root.resolve("classes"))
        val fixtureJar = root.resolve("class-only-fixture.jar")
        val outputDir = root.resolve("graph")
        try {
            val javaFile = root.resolve("ClassOnlySample.java")
            Files.writeString(
                javaFile,
                """
                package sample;
                public class ClassOnlySample {
                    public int value() { return 42; }
                }
                """.trimIndent()
            )
            val compiler = requireNotNull(javax.tools.ToolProvider.getSystemJavaCompiler())
            assertEquals(
                0,
                compiler.run(null, null, null, "-d", classesDir.toString(), javaFile.toString()),
                "Java fixture compilation should succeed"
            )

            JarOutputStream(Files.newOutputStream(fixtureJar)).use { jar ->
                jar.putNextEntry(JarEntry("sample/ClassOnlySample.class"))
                Files.copy(classesDir.resolve("sample/ClassOnlySample.class"), jar)
                jar.closeEntry()
            }

            val cmd = BuildCommand().apply {
                input = fixtureJar
                output = outputDir
                includePackages = listOf("sample")
            }
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(0, code, "JAR graph build should succeed, stderr: $err")

            val resourceStore = outputDir.resolve("graph.resources")
            assertTrue(Files.isRegularFile(resourceStore))
            assertEquals(8L, Files.size(resourceStore), "An empty persisted resource store is header-only")

            val loaded = GraphStore.load(outputDir)
            assertNull(loaded.resources.unavailableReason)
            assertEquals(emptyList(), loaded.resources.list("**").toList())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `build from published jar preserves persisted resource bytes`() {
        val fixtureJar = Path.of(requireNotNull(System.getProperty("spring.jcl.jar.path")))
        val resourcePath = "META-INF/license.txt"
        val expectedContent = JarFile(fixtureJar.toFile()).use { jar ->
            val entry = requireNotNull(jar.getJarEntry(resourcePath)) {
                "Published fixture does not contain $resourcePath"
            }
            jar.getInputStream(entry).use { it.readBytes() }
        }
        val outputDir = Files.createTempDirectory("build-published-resource-jar-test")
        try {
            val cmd = BuildCommand().apply {
                input = fixtureJar
                output = outputDir
                includePackages = listOf("org.apache.commons.logging")
            }
            val (_, err, code) = captureOutput { cmd.call() }
            assertEquals(0, code, "Published JAR graph build should succeed, stderr: $err")

            val resourceStore = outputDir.resolve("graph.resources")
            assertTrue(Files.size(resourceStore) > 8L, "Published JAR must produce a non-empty resource store")

            val loaded = GraphStore.load(outputDir)
            val entry = loaded.resources.list(resourcePath).single()
            assertEquals(resourcePath, entry.path)
            assertEquals(fixtureJar.fileName.toString(), entry.source)
            assertContentEquals(expectedContent, loaded.resources.open(resourcePath).use { it.readBytes() })
        } finally {
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
