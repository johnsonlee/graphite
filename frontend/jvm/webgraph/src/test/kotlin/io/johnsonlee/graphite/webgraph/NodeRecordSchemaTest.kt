package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ClassConstant
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The JVM codecs of the node record against its one definition, `backend/storage/schema/node-records.txt`: the tags it names,
 * and for every kind the golden record it holds. The Rust codecs are held to the same file in `node_schema_tests.rs`, so a
 * field added on one side and not the other fails on the side that was left behind.
 */
class NodeRecordSchemaTest {

    private val owner = TypeDescriptor("sample.Owner")
    private val string = TypeDescriptor("java.lang.String")
    private val run = MethodDescriptor(owner, "run", listOf(TypeDescriptor("int"), string), string)
    private val helper = MethodDescriptor(owner, "helper", emptyList(), TypeDescriptor("void"))

    /** One node of every kind; its id is its tag. The Rust test expects the same values. */
    private val samples: List<Node> = listOf(
        IntConstant(NodeId(0), -42),
        StringConstant(NodeId(1), "hello"),
        LongConstant(NodeId(2), 1234567890123L),
        FloatConstant(NodeId(3), 1.5f),
        DoubleConstant(NodeId(4), -2.25),
        BooleanConstant(NodeId(5), true),
        NullConstant(NodeId(6)),
        EnumConstant(NodeId(7), TypeDescriptor("sample.Color"), "RED", listOf(7, "label", null)),
        LocalVariable(NodeId(8), "tmp", TypeDescriptor("int"), run),
        FieldNode(NodeId(9), FieldDescriptor(owner, "count", TypeDescriptor("int")), isStatic = true),
        ParameterNode(NodeId(10), 1, TypeDescriptor("java.lang.String"), run),
        ReturnNode(NodeId(11), run, TypeDescriptor("java.lang.String")),
        CallSiteNode(NodeId(12), run, helper, lineNumber = 17, receiver = null, arguments = listOf(NodeId(3), NodeId(5))),
        AnnotationNode(
            NodeId(13), "sample.Tag", "sample.Owner", "run",
            linkedMapOf("value" to "x", "weights" to listOf(1L, 2L), "level" to EnumValueReference("sample.Color", "RED"))
        ),
        ResourceValueNode(NodeId(14), "app.yml", "mode", true, "yaml", "dev"),
        ResourceFileNode(NodeId(15), "app.yml", "classpath", "yaml", null),
        ClassConstant(NodeId(16), "Lsample/Owner;")
    )

    private class Schema(val kinds: Map<Int, String>, val strings: List<String>, val golden: Map<Int, String>)

    private fun schema(): Schema {
        val lines = javaClass.getResourceAsStream("/node-records.txt")!!.bufferedReader().readLines()
            .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        fun rows(keyword: String) = lines.filter { it.startsWith("$keyword ") }.map { it.split(" ", limit = 3) }
        val strings = rows("string")
        assertEquals(strings.indices.toList(), strings.map { it[1].toInt() }, "string ids are dense and in order")
        return Schema(
            kinds = rows("kind").associate { it[1].toInt() to it[2].substringBefore(' ') },
            strings = strings.map { it[2] },
            golden = rows("golden").associate { it[1].toInt() to it[2] }
        )
    }

    private fun table(): StringTable {
        val strings = linkedSetOf<String>()
        samples.forEach { collectNodeStrings(it, strings) }
        return StringTable.build(strings, Files.createTempDirectory("node-record-schema").also { it.toFile().deleteOnExit() })
    }

    private fun written(node: Node, strings: StringTable): String =
        HexFormat.of().formatHex(ByteArrayOutputStream().also { writeGraphNode(DataOutputStream(it), node, strings) }.toByteArray())

    @Test
    fun `the schema names every kind by the tag the JVM writes`() {
        val schema = schema()
        assertEquals(samples.indices.toSet(), schema.kinds.keys, "one kind per tag, no gap")
        for (node in samples) {
            assertEquals(node.id.value, graphNodeTagOf(node), "the sample of a kind carries its tag as its id")
            val name = node::class.simpleName!!.removeSuffix("Node")
            assertEquals(schema.kinds.getValue(graphNodeTagOf(node)), name, "tag ${graphNodeTagOf(node)}")
        }
    }

    @Test
    fun `the JVM writer produces the golden record of every kind, and the reader reads it back`() {
        val schema = schema()
        val strings = table()
        assertEquals(schema.strings, schema.strings.indices.map(strings::get), "the string ids the golden records use")
        val actual = samples.associate { it.id.value to written(it, strings) }
        assertEquals(
            schema.golden.toSortedMap().map { (tag, hex) -> "golden $tag $hex" }.joinToString("\n"),
            actual.toSortedMap().map { (tag, hex) -> "golden $tag $hex" }.joinToString("\n")
        )
        for (node in samples) {
            val bytes = HexFormat.of().parseHex(schema.golden.getValue(node.id.value))
            val input = DataInputStream(ByteArrayInputStream(bytes))
            assertEquals(node, readGraphNode(input, strings))
            assertEquals(0, input.available(), "the reader consumes the whole record of ${node::class.simpleName}")
        }
    }

    @Test
    fun `the required files are the ones the schema lists`() {
        val listed = javaClass.getResourceAsStream("/graph-files.txt")!!.bufferedReader().readLines()
            .map(String::trim).filter { it.startsWith("required ") }.map { it.removePrefix("required ") }
        assertEquals(listed, GraphManifest.REQUIRED_FILES)
    }

    @Test
    fun `the build spill keeps every field of every kind`() {
        val builder = MmapGraphBuilder()
        samples.forEach(builder::addNode)
        val graph = builder.build()
        try {
            for (node in samples) assertEquals(node, graph.node(node.id), "${node::class.simpleName}")
        } finally {
            (graph as? AutoCloseable)?.close()
        }
    }
}
