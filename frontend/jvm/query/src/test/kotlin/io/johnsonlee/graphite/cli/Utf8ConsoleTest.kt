package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class Utf8ConsoleTest {

    private val emDash = byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0x94.toByte())
    private val eAcute = byteArrayOf(0xC3.toByte(), 0xA9.toByte())

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    @Test
    fun `the console writes UTF-8 bytes on both streams and flushes each line`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        try {
            installUtf8Console(out, err)
            System.out.println("é")
            System.err.println("—")
            // Flushed by println, as the JDK's own streams are: nothing waits for exit.
            assertContentEquals(eAcute + '\n'.code.toByte(), out.toByteArray())
            assertContentEquals(emDash + '\n'.code.toByte(), err.toByteArray())
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
    }

    @Test
    fun `by default the console replaces the process's own streams`() {
        val oldOut = System.out
        val oldErr = System.err
        try {
            installUtf8Console()
            assertNotSame(oldOut, System.out)
            assertNotSame(oldErr, System.err)
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
    }

    /**
     * The real entry point under an ASCII locale, where the JVM's default streams wrote `?` for
     * every non-ASCII character: the Rust CLI's bytes, in a result row and in an error message.
     */
    @Test
    fun `graphite query writes UTF-8 under the POSIX locale`() {
        val graphDir = Files.createTempDirectory("utf8-console-test")
        try {
            val builder = DefaultGraph.Builder()
            builder.addMethod(MethodDescriptor(TypeDescriptor("com.example.Foo"), "run", emptyList(), TypeDescriptor("void")))
            GraphStore.save(builder.build(), graphDir)

            // The query text stays ASCII: the JVM decodes its arguments in the locale's charset.
            val row = runMain("query", graphDir.toString(), "RETURN '\\u00e9' AS v", "-f", "csv")
            assertEquals(0, row.code, String(row.err))
            assertTrue(row.out.contains(eAcute), "stdout: ${row.out.toList()}")

            val error = runMain("query", graphDir.toString(), "MATCH (n) DELETE n")
            assertEquals(1, error.code)
            assertTrue(error.err.contains(emDash), "stderr: ${String(error.err, Charsets.UTF_8)}")
        } finally {
            graphDir.toFile().deleteRecursively()
        }
    }

    private class Run(val code: Int, val out: ByteArray, val err: ByteArray)

    private fun runMain(vararg args: String): Run {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            listOf(java, "-cp", System.getProperty("java.class.path"), "io.johnsonlee.graphite.cli.MainKt") + args,
        ).apply {
            val env = environment()
            env.keys.removeIf { it.startsWith("LC_") || it == "LANG" || it == "LANGUAGE" }
            env["LC_ALL"] = "POSIX"
        }.start()
        val err = ByteArrayOutputStream()
        val drainErr = Thread { process.errorStream.copyTo(err) }.apply { start() }
        val out = process.inputStream.readBytes()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "graphite did not exit")
        drainErr.join()
        return Run(process.exitValue(), out, err.toByteArray())
    }
}
