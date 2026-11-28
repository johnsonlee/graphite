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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The JVM codecs of the node record against its one definition, `backend/storage/schema/node-records.txt`: the tags it names,
 * and for every golden record it holds, the bytes the writer produces and the fields, by name and type, the schema says those
 * bytes hold. The Rust codecs are held to the same file in `node_schema_tests.rs`, so a field added on one side and not the
 * other fails on the side that was left behind.
 */
class NodeRecordSchemaTest {

    private val owner = TypeDescriptor("sample.Owner")
    private val string = TypeDescriptor("java.lang.String")
    private val color = TypeDescriptor("sample.Color")
    private val red = EnumValueReference("sample.Color", "RED")
    private val run = MethodDescriptor(owner, "run", listOf(TypeDescriptor("int"), string), string)
    private val helper = MethodDescriptor(owner, "helper", emptyList(), TypeDescriptor("void"))

    /**
     * The golden records as nodes, by id: one of every kind (its id is its tag), then the variants that reach what those do
     * not: every tagged value, an absent and a present case of every optional field, empty lists. The Rust test expects the
     * same values.
     */
    private val samples: List<Node> = listOf(
        IntConstant(NodeId(0), -42),
        StringConstant(NodeId(1), "hello"),
        LongConstant(NodeId(2), 1234567890123L),
        FloatConstant(NodeId(3), 1.5f),
        DoubleConstant(NodeId(4), -2.25),
        BooleanConstant(NodeId(5), true),
        NullConstant(NodeId(6)),
        EnumConstant(NodeId(7), color, "RED", listOf(7, "label", null)),
        LocalVariable(NodeId(8), "tmp", TypeDescriptor("int"), run),
        FieldNode(NodeId(9), FieldDescriptor(owner, "count", TypeDescriptor("int")), isStatic = true),
        ParameterNode(NodeId(10), 1, TypeDescriptor("java.lang.String"), run),
        ReturnNode(NodeId(11), run, TypeDescriptor("java.lang.String")),
        CallSiteNode(NodeId(12), run, helper, lineNumber = 17, receiver = null, arguments = listOf(NodeId(3), NodeId(5))),
        AnnotationNode(
            NodeId(13), "sample.Tag", "sample.Owner", "run",
            linkedMapOf("value" to "x", "weights" to listOf(1L, 2L), "level" to red)
        ),
        ResourceValueNode(NodeId(14), "app.yml", "mode", true, "yaml", "dev"),
        ResourceFileNode(NodeId(15), "app.yml", "classpath", "yaml", null),
        ClassConstant(NodeId(16), "Lsample/Owner;"),
        // An int that is -1 is a value, not an absent one.
        IntConstant(NodeId(17), -1),
        // Every tagged value, a nested list and an empty one.
        EnumConstant(
            NodeId(18), color, "RED",
            listOf(7, 2L, "label", 0.5f, 1.0e10, false, null, red, listOf(1.5f, emptyList<Any?>(), -2.25))
        ),
        ReturnNode(NodeId(19), helper, null),
        CallSiteNode(NodeId(20), helper, run, lineNumber = null, receiver = NodeId(9), arguments = emptyList()),
        ResourceValueNode(NodeId(21), "app.yml", "count", 2.5, "yaml", null),
        ResourceFileNode(NodeId(22), "app.yml", "classpath", "yaml", "dev"),
        AnnotationNode(
            NodeId(23), "sample.Tag", "sample.Owner", "count",
            linkedMapOf("level" to 0.25f, "mode" to 0.003, "weights" to listOf(false, null), "label" to emptyList<Any?>())
        ),
        BooleanConstant(NodeId(24), false),
        AnnotationNode(NodeId(25), "sample.Tag", "sample.Owner", "run", emptyMap()),
        FieldNode(NodeId(26), FieldDescriptor(owner, "tmp", string), isStatic = false)
    )

    private class Kind(val name: String, val fields: List<Pair<String, String>>)

    private class Schema(lines: List<String>) {
        private val rows = lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split(" ") }
        val kinds = rows.filter { it[0] == "kind" }.associate { row ->
            row[1].toInt() to Kind(row[2], row.drop(3).map { it.substringBefore(':') to it.substringAfter(':') })
        }
        /** value tag -> (its name, the encodings after the tag byte). */
        val values = rows.filter { it[0] == "value" }.associate { it[1].toInt() to (it[2] to it.drop(3)) }
        val strings = rows.filter { it[0] == "string" }.map { it.drop(2).joinToString(" ") }
        val golden = rows.filter { it[0] == "golden" }.associate { it[1].toInt() to it[2] }

        init {
            assertEquals(strings.indices.toList(), rows.filter { it[0] == "string" }.map { it[1].toInt() }, "string ids are dense")
        }
    }

    private fun schemaLines(): List<String> = javaClass.getResourceAsStream("/node-records.txt")!!.bufferedReader().readLines()

    private fun schema(): Schema = Schema(schemaLines())

    private fun table(): StringTable {
        val strings = linkedSetOf<String>()
        samples.forEach { collectNodeStrings(it, strings) }
        return StringTable.build(strings, Files.createTempDirectory("node-record-schema").also { it.toFile().deleteOnExit() })
    }

    private fun written(node: Node, strings: StringTable): String =
        HexFormat.of().formatHex(ByteArrayOutputStream().also { writeGraphNode(DataOutputStream(it), node, strings) }.toByteArray())

    /**
     * Reads a record by the schema alone and renders every field as `name=value`, the value typed by its encoding (`7`, `7L`,
     * `0.5f`, `0.5d`, `"text"`, `-` for an absent optional), so a schema that names or types a field otherwise renders
     * otherwise. [describe] renders the same from a node's own properties.
     */
    private class Walker(private val schema: Schema, bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))

        fun record(): List<String> {
            val id = input.readInt()
            val kind = schema.kinds.getValue(input.readUnsignedByte())
            val fields = kind.fields.map { (name, encoding) -> "$name=${field(encoding)}" }
            check(input.available() == 0) { "${input.available()} bytes follow the fields the schema gives ${kind.name}" }
            return listOf("id=$id", "kind=${kind.name}") + fields
        }

        private fun sid(): String = "\"${schema.strings[input.readInt()]}\""

        private fun <T> list(item: () -> T): String = (0 until input.readInt()).map { item() }.joinToString(",", "[", "]")

        private fun value(): String {
            val (name, encodings) = schema.values.getValue(input.readUnsignedByte())
            return encodings.joinToString(",", "$name(", ")") { field(it) }
        }

        private fun scalar(encoding: String): String? = when (encoding) {
            "i32" -> input.readInt().toString()
            "i64" -> "${input.readLong()}L"
            "f32" -> "${input.readFloat()}f"
            "f64" -> "${input.readDouble()}d"
            "bool" -> when (val byte = input.readUnsignedByte()) {
                0 -> "false"
                1 -> "true"
                else -> error("bool $byte")
            }
            "sid" -> sid()
            "sid?" -> if (field("bool") == "true") sid() else "-"
            "i32?" -> input.readInt().let { if (it == -1) "-" else it.toString() }
            else -> null
        }

        private fun field(encoding: String): String = scalar(encoding) ?: when (encoding) {
            "ids" -> list { input.readInt() }
            "method" -> "${sid()}.${sid()}${list(::sid)}:${sid()}"
            "value" -> value()
            "values" -> list(::value)
            "pairs" -> list { "${sid()}=${value()}" }
            else -> error("the schema names an encoding this test does not know: $encoding")
        }
    }

    private fun text(value: String): String = "\"$value\""

    private fun method(method: MethodDescriptor): String = text(method.declaringClass.className) + "." + text(method.name) +
        method.parameterTypes.joinToString(",", "[", "]") { text(it.className) } + ":" + text(method.returnType.className)

    private fun tagged(value: Any?): String = when (value) {
        is Int -> "Int($value)"
        is Long -> "Long(${value}L)"
        is String -> "String(${text(value)})"
        is Float -> "Float(${value}f)"
        is Double -> "Double(${value}d)"
        is Boolean -> "Boolean($value)"
        null -> "Null()"
        is EnumValueReference -> "EnumRef(${text(value.enumClass)},${text(value.enumName)})"
        is List<*> -> "List(${value.joinToString(",", "[", "]", transform = ::tagged)})"
        else -> error("no tagged value for ${value::class}")
    }

    /** What a record of [node] holds, written from the node's own properties and the names the schema is expected to use. */
    private fun describe(node: Node): List<String> =
        listOf("id=${node.id.value}") + (describeConstant(node) ?: describeResource(node) ?: describeDeclaration(node))

    private fun describeConstant(node: Node): List<String>? = when (node) {
        is IntConstant -> listOf("kind=IntConstant", "value=${node.value}")
        is StringConstant -> listOf("kind=StringConstant", "value=${text(node.value)}")
        is LongConstant -> listOf("kind=LongConstant", "value=${node.value}L")
        is FloatConstant -> listOf("kind=FloatConstant", "value=${node.value}f")
        is DoubleConstant -> listOf("kind=DoubleConstant", "value=${node.value}d")
        is BooleanConstant -> listOf("kind=BooleanConstant", "value=${node.value}")
        is NullConstant -> listOf("kind=NullConstant")
        is ClassConstant -> listOf("kind=ClassConstant", "value=${text(node.value)}")
        else -> null
    }

    private fun describeResource(node: Node): List<String>? = when (node) {
        is AnnotationNode -> listOf(
            "kind=Annotation", "name=${text(node.name)}", "class=${text(node.className)}", "member=${text(node.memberName)}",
            "values=${node.values.entries.joinToString(",", "[", "]") { (key, value) -> "${text(key)}=${tagged(value)}" }}"
        )
        is ResourceValueNode -> listOf(
            "kind=ResourceValue", "path=${text(node.path)}", "key=${text(node.key)}", "value=${tagged(node.value)}",
            "format=${text(node.format)}", "profile=${node.profile?.let(::text) ?: "-"}"
        )
        is ResourceFileNode -> listOf(
            "kind=ResourceFile", "path=${text(node.path)}", "source=${text(node.source)}", "format=${text(node.format)}",
            "profile=${node.profile?.let(::text) ?: "-"}"
        )
        else -> null
    }

    private fun describeDeclaration(node: Node): List<String> = when (node) {
        is EnumConstant -> listOf(
            "kind=EnumConstant", "enum_type=${text(node.enumType.className)}", "name=${text(node.enumName)}",
            "args=${node.constructorArgs.joinToString(",", "[", "]", transform = ::tagged)}"
        )
        is LocalVariable -> listOf(
            "kind=LocalVariable", "name=${text(node.name)}", "type=${text(node.type.className)}", "method=${method(node.method)}"
        )
        is FieldNode -> listOf(
            "kind=Field", "class=${text(node.descriptor.declaringClass.className)}", "name=${text(node.descriptor.name)}",
            "type=${text(node.descriptor.type.className)}", "is_static=${node.isStatic}"
        )
        is ParameterNode -> listOf(
            "kind=Parameter", "index=${node.index}", "type=${text(node.type.className)}", "method=${method(node.method)}"
        )
        is ReturnNode -> listOf(
            "kind=Return", "method=${method(node.method)}", "actual_type=${node.actualType?.let { text(it.className) } ?: "-"}"
        )
        is CallSiteNode -> listOf(
            "kind=CallSite", "caller=${method(node.caller)}", "callee=${method(node.callee)}", "line=${node.lineNumber ?: "-"}",
            "receiver=${node.receiver?.value ?: "-"}", "arguments=${node.arguments.joinToString(",", "[", "]") { "${it.value}" }}"
        )
        else -> error("no schema kind for ${node::class}")
    }

    /**
     * The first golden record whose fields, read by the schema [lines], are not the ones the node it encodes has; `null` when
     * the schema describes every record.
     */
    private fun disagreement(lines: List<String>): String? {
        val schema = Schema(lines)
        for (node in samples) {
            val expected = describe(node)
            val actual = try {
                Walker(schema, HexFormat.of().parseHex(schema.golden.getValue(node.id.value))).record()
            } catch (e: Exception) {
                listOf("unreadable: $e")
            }
            if (actual != expected) return "golden ${node.id.value}: the schema reads $actual, the node is $expected"
        }
        return null
    }

    @Test
    fun `the schema names every kind by the tag the JVM writes`() {
        val schema = schema()
        val first = samples.take(schema.kinds.size)
        assertEquals(first.indices.toSet(), schema.kinds.keys, "one kind per tag, no gap")
        for (node in first) {
            assertEquals(node.id.value, graphNodeTagOf(node), "the first sample of a kind carries its tag as its id")
            val name = node::class.simpleName!!.removeSuffix("Node")
            assertEquals(schema.kinds.getValue(graphNodeTagOf(node)).name, name, "tag ${graphNodeTagOf(node)}")
        }
        assertEquals(samples.indices.toList(), samples.map { it.id.value }, "sample ids are dense")
    }

    @Test
    fun `the JVM writer produces every golden record, and the reader reads it back`() {
        val schema = schema()
        val strings = table()
        assertEquals(schema.strings, schema.strings.indices.map(strings::get), "the string ids the golden records use")
        val actual = samples.associate { it.id.value to written(it, strings) }
        assertEquals(
            schema.golden.toSortedMap().map { (id, hex) -> "golden $id $hex" }.joinToString("\n"),
            actual.toSortedMap().map { (id, hex) -> "golden $id $hex" }.joinToString("\n")
        )
        for (node in samples) {
            val bytes = HexFormat.of().parseHex(schema.golden.getValue(node.id.value))
            val input = DataInputStream(ByteArrayInputStream(bytes))
            assertEquals(node, readGraphNode(input, strings))
            assertEquals(0, input.available(), "the reader consumes the whole record of ${node::class.simpleName}")
        }
    }

    @Test
    fun `the schema's fields are the node's, by name, type and value`() {
        assertNull(disagreement(schemaLines()))
        // The golden records reach every tagged value and both cases of every optional field, or a codec could
        // get one wrong unseen.
        val schema = schema()
        val rendered = samples.map(::describe)
        for ((name, _) in schema.values.values) {
            assertNotNull(rendered.flatten().firstOrNull { "$name(" in it }, "no golden record holds a tagged $name")
        }
        for (kind in schema.kinds.values) {
            for ((field, encoding) in kind.fields.filter { it.second.endsWith("?") }) {
                val cases = rendered.filter { "kind=${kind.name}" in it }
                    .map { row -> row.first { it.startsWith("$field=") } == "$field=-" }
                assertEquals(setOf(true, false), cases.toSet(), "${kind.name}.$field ($encoding): an absent and a present case")
            }
        }
    }

    @Test
    fun `a schema that names or types a field otherwise is caught`() {
        // Review of #229: these left every schema test green, because only a record's length was compared.
        val lines = schemaLines()
        fun mutated(from: String, to: String): List<String> {
            assertEquals(1, lines.count { from in it }, from)
            return lines.map { it.replace(from, to) }
        }
        val mutations = listOf(
            // The same width, another type.
            mutated("LongConstant value:i64", "LongConstant value:f64"),
            mutated("DoubleConstant value:f64", "DoubleConstant value:i64"),
            mutated("FloatConstant value:f32", "FloatConstant value:i32"),
            mutated("StringConstant value:sid", "StringConstant value:i32"),
            mutated("IntConstant value:i32", "IntConstant value:i32?"),
            // The same encoding, the names exchanged.
            mutated("line:i32? receiver:i32?", "receiver:i32? line:i32?"),
            mutated("name:sid class:sid member:sid", "class:sid name:sid member:sid"),
            mutated("path:sid source:sid format:sid", "path:sid format:sid source:sid"),
            mutated("is_static:bool", "static:bool"),
            // The tagged values no record used to hold, and a name exchanged for another.
            mutated("value 3 Float f32", "value 3 Float i32"),
            mutated("value 4 Double f64", "value 4 Double i64"),
            mutated("value 1 Long i64", "value 1 Long f64"),
            mutated("value 5 Boolean bool", "value 5 Flag bool"),
            mutated("value 7 EnumRef sid sid", "value 7 EnumRef sid"),
            // A field more, a field less.
            mutated("ClassConstant value:sid", "ClassConstant value:sid flags:i32"),
            mutated("actual_type:sid?", "")
        )
        for (lines in mutations) {
            assertNotNull(disagreement(lines), lines.first { it !in schemaLines() })
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
