package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.junit.AfterClass
import org.junit.BeforeClass

class QueryCommandTest {

    companion object {
        private lateinit var graphDir: Path

        @BeforeClass @JvmStatic
        fun setup() {
            graphDir = Files.createTempDirectory("query-cmd-test")
            val builder = DefaultGraph.Builder()

            val fooType = TypeDescriptor("com.example.Foo")
            val method = MethodDescriptor(fooType, "test", emptyList(), TypeDescriptor("void"))
            val constant = IntConstant(NodeId.next(), 42)
            val callSite = CallSiteNode(NodeId.next(), method,
                MethodDescriptor(fooType, "getOption", listOf(TypeDescriptor("int")), TypeDescriptor("void")),
                10, null, listOf(constant.id))

            builder.addNode(constant)
            builder.addNode(callSite)
            builder.addEdge(DataFlowEdge(constant.id, callSite.id, DataFlowKind.PARAMETER_PASS))
            builder.addMethod(method)

            GraphStore.save(builder.build(), graphDir)
        }

        @AfterClass @JvmStatic
        fun teardown() {
            graphDir.toFile().deleteRecursively()
        }
    }

    private fun runCommand(vararg args: String): Triple<String, String, Int> {
        val cmd = QueryCommand()
        cmd.graphDir = graphDir
        cmd.query = args[0]
        if (args.size > 1) cmd.format = args[1]

        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val code = try { cmd.call() } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        return Triple(outBaos.toString(), errBaos.toString(), code)
    }

    @Test
    fun `query command returns 0 on success`() {
        val (stdout, _, code) = runCommand("MATCH (n) RETURN n.id LIMIT 1")
        assertEquals(0, code)
        assertTrue(stdout.contains("row(s)") || stdout.contains("id"))
    }

    @Test
    fun `query json format`() {
        val (stdout, _, code) = runCommand("MATCH (n:IntConstant) RETURN n.value", "json")
        assertEquals(0, code)
        assertTrue(stdout.contains("42") || stdout.contains("columns"))
    }

    @Test
    fun `query csv format`() {
        val (stdout, _, code) = runCommand("MATCH (n:IntConstant) RETURN n.value", "csv")
        assertEquals(0, code)
    }

    @Test
    fun `query error returns 1`() {
        val cmd = QueryCommand()
        cmd.graphDir = Path.of("/nonexistent")
        cmd.query = "MATCH (n) RETURN n"

        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val code = try { cmd.call() } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertEquals(1, code)
    }

    @Test
    fun `verbose flag prints loading messages`() {
        val cmd = QueryCommand()
        cmd.graphDir = graphDir
        cmd.query = "MATCH (n:IntConstant) RETURN n.value"
        cmd.verbose = true

        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val code = try { cmd.call() } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertEquals(0, code)
        val errStr = errBaos.toString()
        assertTrue(errStr.contains("Loading graph"), "Verbose should print loading message")
        assertTrue(errStr.contains("Executing"), "Verbose should print executing message")
    }

    @Test
    fun `verbose error prints stack trace`() {
        val cmd = QueryCommand()
        cmd.graphDir = Path.of("/nonexistent")
        cmd.query = "MATCH (n) RETURN n"
        cmd.verbose = true

        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val code = try { cmd.call() } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertEquals(1, code)
        val errStr = errBaos.toString()
        assertTrue(errStr.contains("Error:"), "Should print error message")
    }

    @Test
    fun `text format with empty result`() {
        val (stdout, _, code) = runCommand("MATCH (n:NullConstant)")
        assertEquals(0, code)
        assertTrue(stdout.contains("no results"))
    }

    @Test
    fun `csv format with string values`() {
        val (stdout, _, code) = runCommand(
            "MATCH (n:CallSiteNode) RETURN n.callee_name", "csv"
        )
        assertEquals(0, code)
        assertTrue(stdout.contains("n.callee_name"))
    }

    @Test
    fun `runtime error during execution returns 1`() {
        val cmd = QueryCommand()
        cmd.graphDir = graphDir
        cmd.query = "MATCH (n) RETURN nonexistent_function(n)"

        val outBaos = ByteArrayOutputStream()
        val errBaos = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBaos))
        System.setErr(PrintStream(errBaos))
        val code = try { cmd.call() } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        assertEquals(1, code)
        assertTrue(errBaos.toString().contains("Error:"))
    }

    @Test
    fun `not a directory returns 1`() {
        val tempFile = Files.createTempFile("test", ".txt")
        try {
            val cmd = QueryCommand()
            cmd.graphDir = tempFile
            cmd.query = "MATCH (n) RETURN n"

            val outBaos = ByteArrayOutputStream()
            val errBaos = ByteArrayOutputStream()
            val oldOut = System.out
            val oldErr = System.err
            System.setOut(PrintStream(outBaos))
            System.setErr(PrintStream(errBaos))
            val code = try { cmd.call() } finally {
                System.setOut(oldOut)
                System.setErr(oldErr)
            }
            assertEquals(1, code)
            assertTrue(errBaos.toString().contains("Not a graph file or directory"))
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }
}
