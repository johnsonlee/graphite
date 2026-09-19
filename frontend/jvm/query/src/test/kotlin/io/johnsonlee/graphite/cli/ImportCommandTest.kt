package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.ir.IR_SCHEMA_VERSION
import io.johnsonlee.graphite.ir.v1.CallSite
import io.johnsonlee.graphite.ir.v1.Chunk
import io.johnsonlee.graphite.ir.v1.DataFlowEdge as IrDataFlowEdge
import io.johnsonlee.graphite.ir.v1.Edge
import io.johnsonlee.graphite.ir.v1.EdgeBatch
import io.johnsonlee.graphite.ir.v1.Frontend
import io.johnsonlee.graphite.ir.v1.Header
import io.johnsonlee.graphite.ir.v1.MethodBatch
import io.johnsonlee.graphite.ir.v1.MethodRef
import io.johnsonlee.graphite.ir.v1.Node
import io.johnsonlee.graphite.ir.v1.NodeBatch
import io.johnsonlee.graphite.ir.v1.StringBatch
import io.johnsonlee.graphite.ir.v1.StringConstant as IrStringConstant
import io.johnsonlee.graphite.ir.v1.Trailer
import io.johnsonlee.graphite.ir.v1.TypeRef
import io.johnsonlee.graphite.webgraph.GraphStore
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import picocli.CommandLine

class ImportCommandTest {

    private fun run(vararg args: String): Pair<String, Int> {
        val err = ByteArrayOutputStream()
        val oldErr = System.err
        System.setErr(PrintStream(err))
        val code = try {
            CommandLine(GraphiteCommand()).execute("import", *args)
        } finally {
            System.setErr(oldErr)
        }
        return err.toString() to code
    }

    /** A two-node Swift graph: `"checkout"` flows into `CartService.track(String)`. */
    private fun writeIr(dir: Path): Path {
        val strings = listOf("AcmeShop.CartService", "checkout", "Swift.String", "Swift.Void", "track")
        fun type(name: String) = TypeRef.newBuilder().setName(strings.indexOf(name))
        fun method(name: String, vararg params: String) = MethodRef.newBuilder()
            .setDeclaringClass(type("AcmeShop.CartService")).setName(strings.indexOf(name))
            .addAllParameterTypes(params.map { type(it).build() }).setReturnType(type("Swift.Void"))
        val chunks = listOf(
            Chunk.newBuilder().setHeader(
                Header.newBuilder().setSchemaVersion(IR_SCHEMA_VERSION).setLanguage("swift")
                    .setFrontend(Frontend.newBuilder().setName("graphite-frontend-apple").setVersion("0.1.0"))
            ),
            Chunk.newBuilder().setStrings(StringBatch.newBuilder().addAllValues(strings)),
            Chunk.newBuilder().setNodes(
                NodeBatch.newBuilder()
                    .addNodes(
                        Node.newBuilder().setId(0)
                            .setStringConstant(IrStringConstant.newBuilder().setValue(strings.indexOf("checkout")))
                    )
                    .addNodes(
                        Node.newBuilder().setId(1).setCallSite(
                            CallSite.newBuilder().setCaller(method("checkout")).setCallee(method("track", "Swift.String"))
                                .setLine(12).addArguments(0)
                        )
                    )
            ),
            Chunk.newBuilder().setEdges(
                EdgeBatch.newBuilder().addEdges(
                    Edge.newBuilder().setFrom(0).setTo(1)
                        .setDataFlow(IrDataFlowEdge.newBuilder().setKind(io.johnsonlee.graphite.ir.v1.DataFlowKind.PARAMETER_PASS))
                )
            ),
            Chunk.newBuilder().setMethods(MethodBatch.newBuilder().addMethods(method("checkout"))),
            Chunk.newBuilder().setTrailer(Trailer.newBuilder().setNodeCount(2).setEdgeCount(1).setStringCount(strings.size.toLong()))
        )
        val file = dir.resolve("acme.graphite-ir")
        Files.newOutputStream(file).use { out -> chunks.forEach { it.build().writeDelimitedTo(out) } }
        return file
    }

    @Test
    fun `import saves a graph the store can load back`() {
        val dir = Files.createTempDirectory("import-cmd-test")
        try {
            val ir = writeIr(dir)
            val out = dir.resolve("graph")
            val (err, code) = run(ir.toString(), "-o", out.toString())
            assertEquals(0, code, err)
            assertTrue(err.contains("graphite-frontend-apple 0.1.0 (swift): 2 nodes, 1 edges, 5 strings"), err)

            val graph = GraphStore.load(out)
            assertEquals("checkout", (graph.node(NodeId(1)) as StringConstant).value)
            val call = graph.node(NodeId(2)) as CallSiteNode
            assertEquals("AcmeShop.CartService.track(Swift.String)", call.callee.signature)
            assertEquals(12, call.lineNumber)
            assertEquals(listOf(NodeId(1)), call.arguments)
            val edge = graph.outgoing(NodeId(1)).single() as DataFlowEdge
            assertEquals(DataFlowKind.PARAMETER_PASS, edge.kind)
            assertEquals(1, graph.methods(io.johnsonlee.graphite.graph.MethodPattern()).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `import parses its input, output and verbose flag`() {
        val command = ImportCommand()
        CommandLine(command).parseArgs("/src/acme.graphite-ir", "-o", "/out/acme", "-v")
        assertEquals(Path.of("/src/acme.graphite-ir"), command.input)
        assertEquals(Path.of("/out/acme"), command.output)
        assertTrue(command.verbose)
        assertEquals(false, ImportCommand().verbose)
        assertFailsWith<UninitializedPropertyAccessException> { ImportCommand().input }
        assertFailsWith<UninitializedPropertyAccessException> { ImportCommand().output }
        val direct = ImportCommand()
        direct.input = Path.of("/nonexistent/acme.graphite-ir")
        direct.output = Path.of("/nonexistent/out")
        val oldErr = System.err
        System.setErr(PrintStream(ByteArrayOutputStream()))
        try {
            assertEquals(1, direct.call())
        } finally {
            System.setErr(oldErr)
        }
    }

    @Test
    fun `import rejects a missing input`() {
        val dir = Files.createTempDirectory("import-cmd-test")
        try {
            val (err, code) = run(dir.resolve("nope.graphite-ir").toString(), "-o", dir.resolve("graph").toString())
            assertEquals(1, code)
            assertTrue(err.contains("Input is not a file"), err)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `import rejects a malformed stream`() {
        val dir = Files.createTempDirectory("import-cmd-test")
        try {
            val ir = dir.resolve("empty.graphite-ir")
            Files.write(ir, ByteArray(0))
            val (err, code) = run(ir.toString(), "-o", dir.resolve("graph").toString())
            assertEquals(1, code)
            assertTrue(err.contains("Error: IR stream is empty"), err)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `import reports an unwritable output`() {
        val dir = Files.createTempDirectory("import-cmd-test")
        try {
            val ir = writeIr(dir)
            val blocked = dir.resolve("blocked")
            Files.write(blocked, byteArrayOf(1))
            val (err, code) = run(ir.toString(), "-o", blocked.resolve("graph").toString(), "-v")
            assertEquals(1, code)
            assertTrue(err.contains("Error:"), err)
            assertTrue(err.contains("IOException") || err.contains("at "), err)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
