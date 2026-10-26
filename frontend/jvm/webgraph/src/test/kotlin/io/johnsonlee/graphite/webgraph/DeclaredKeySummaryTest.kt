package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One partition of the `graph.declared-keys` sidecar as a test decodes it: first node id and count. */
internal data class DecodedPartition(val first: Int, val count: Long)

/** The `graph.declared-keys` sidecar decoded by a test, independently of the writer. */
internal data class DecodedDeclaredKeys(
    val typesDigest: ByteArray,
    val layoutDigest: ByteArray,
    val fieldCount: Long,
    val methodCount: Long,
    /** Field, Parameter, Return: `null` when absent, else [unbound, bound]. */
    val partitions: List<List<DecodedPartition>?>
) {
    companion object {
        fun decode(bytes: ByteArray): DecodedDeclaredKeys {
            val payload = bytes.size - 32
            assertContentEquals(
                MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(payload)), bytes.copyOfRange(payload, bytes.size),
                "the sidecar ends with the SHA-256 of the bytes before it"
            )
            val buffer = ByteBuffer.wrap(bytes, 0, payload)
            assertEquals(DeclaredKeySummaryBuilder.MAGIC or DeclaredKeySummaryBuilder.VERSION, buffer.int)
            val digest = ByteArray(32).also(buffer::get)
            val layout = ByteArray(32).also(buffer::get)
            val fields = buffer.long
            val methods = buffer.long
            val partitions = List(3) {
                when (buffer.get().toInt()) {
                    0 -> null
                    1 -> List(2) { DecodedPartition(buffer.int, buffer.long) }
                    else -> error("presence flag")
                }
            }
            assertEquals(0, buffer.remaining(), "nothing follows the three tags but the digest")
            return DecodedDeclaredKeys(digest, layout, fields, methods, partitions)
        }

        /** What the native reader computes: the partitions of every declaration-bearing node in type-index order. */
        fun expected(graph: Graph): List<List<DecodedPartition>?> {
            val table = graph.declaredTypes()
            val fields = graph.nodes(FieldNode::class.java).toList()
            val parameters = graph.nodes(ParameterNode::class.java).toList()
            val returns = graph.nodes(ReturnNode::class.java).toList()
            fun method(method: MethodDescriptor) =
                table.methods[MemberTypeKey(method.declaringClass.className, method.name, method.descriptor)]
            fun partitions(nodes: List<Node>, bound: (Node) -> Boolean): List<DecodedPartition> {
                val first = IntArray(2)
                val count = LongArray(2)
                for (node in nodes) {
                    val index = if (bound(node)) 1 else 0
                    if (count[index] == 0L) first[index] = node.id.value
                    count[index]++
                }
                return List(2) { DecodedPartition(first[it], count[it]) }
            }
            return listOf(
                if (table.fields.isEmpty()) null else partitions(fields) { node ->
                    val d = (node as FieldNode).descriptor
                    MemberTypeKey(d.declaringClass.className, d.name, jvmTypeDescriptor(d.type.className)) in table.fields
                },
                if (table.methods.isEmpty()) null else partitions(parameters) { node ->
                    val p = node as ParameterNode
                    method(p.method)?.let { p.index in it.parameterTypes.indices } ?: false
                },
                if (table.methods.isEmpty()) null else partitions(returns) { node -> method((node as ReturnNode).method) != null }
            )
        }
    }
}

class DeclaredKeySummaryTest {
    private val owner = TypeDescriptor("keys.Owner")
    private val string = TypeDescriptor("java.lang.String")
    private val declared = MethodDescriptor(owner, "echo", listOf(string, TypeDescriptor("int")), string)
    private val undeclared = MethodDescriptor(owner, "other", listOf(string), TypeDescriptor("void"))
    private val table = DeclaredTypeTable(
        listOf(DeclaredType("class", "java.lang.String"), DeclaredType("primitive", "int")),
        mapOf(MemberTypeKey("keys.Owner", "name", "Ljava/lang/String;") to 0),
        mapOf(MemberTypeKey("keys.Owner", "echo", "(Ljava/lang/String;I)Ljava/lang/String;") to MethodTypes(listOf(0, 1), 0)),
        emptyMap()
    )

    private fun field(id: Int, name: String) = FieldNode(NodeId(id), FieldDescriptor(owner, name, string), isStatic = false)
    private fun parameter(id: Int, index: Int, method: MethodDescriptor) = ParameterNode(NodeId(id), index, string, method)

    private fun summary(vararg nodes: Node): DecodedDeclaredKeys {
        val builder = DeclaredKeySummaryBuilder(table)
        nodes.forEach(builder::add)
        return DecodedDeclaredKeys.decode(builder.encode(ByteArray(32) { 7 }, ByteArray(32) { 9 }))
    }

    @Test
    fun `partitions count bound and unbound nodes per tag with the first id of each`() {
        val decoded = summary(
            field(3, "other"), field(5, "name"), field(8, "name"),
            parameter(10, 0, declared), parameter(11, 1, declared), parameter(12, 2, declared), parameter(13, 0, undeclared),
            ReturnNode(NodeId(20), undeclared), ReturnNode(NodeId(21), declared),
            // Another field after the other tags: each tag keeps its own order.
            FieldNode(NodeId(30), FieldDescriptor(owner, "x", string), isStatic = true)
        )
        assertContentEquals(ByteArray(32) { 7 }, decoded.typesDigest)
        assertEquals(1, decoded.fieldCount)
        assertEquals(1, decoded.methodCount)
        assertEquals(listOf(DecodedPartition(3, 2), DecodedPartition(5, 2)), decoded.partitions[0])
        assertEquals(listOf(DecodedPartition(12, 2), DecodedPartition(10, 2)), decoded.partitions[1])
        assertEquals(listOf(DecodedPartition(20, 1), DecodedPartition(21, 1)), decoded.partitions[2])
    }

    @Test
    fun `a tag without members has no partitions and an empty tag keeps zero counts`() {
        val fieldless = DeclaredKeySummaryBuilder(DeclaredTypeTable(table.types, emptyMap(), table.methods, emptyMap()))
        fieldless.add(field(1, "name"))
        val decoded = DecodedDeclaredKeys.decode(fieldless.encode(ByteArray(32), ByteArray(32)))
        assertEquals(0, decoded.fieldCount)
        assertNull(decoded.partitions[0], "no field declarations: the reader computes nothing either")
        assertEquals(listOf(DecodedPartition(0, 0), DecodedPartition(0, 0)), decoded.partitions[1])
        assertEquals(listOf(DecodedPartition(0, 0), DecodedPartition(0, 0)), decoded.partitions[2])
    }

    @Test
    fun `the conditions under which the native reader declines leave the tag absent`() {
        assertNull(summary(field(5, "name"), field(3, "name")).partitions[0], "ids not ascending")
        val wide = MethodDescriptor(owner, "wide", List(DeclaredKeySummaryBuilder.MAX_PARAMETERS + 1) { string }, string)
        assertNull(summary(parameter(1, 0, wide)).partitions[1], "more parameters than the summary decoder reads")
        assertNull(summary(ReturnNode(NodeId(1), wide)).partitions[2])
        val long = TypeDescriptor("x".repeat(DeclaredKeySummaryBuilder.MAX_KEY_BYTES.toInt()))
        val oversized = FieldNode(NodeId(1), FieldDescriptor(long, "name", string), isStatic = false)
        assertNull(summary(oversized).partitions[0], "oversized identity")
        assertNull(summary(ReturnNode(NodeId(1), MethodDescriptor(owner, "m", listOf(long), string))).partitions[2])
        // Each tag declines on its own.
        val decoded = summary(field(5, "name"), field(3, "name"), ReturnNode(NodeId(9), declared))
        assertNull(decoded.partitions[0])
        assertEquals(listOf(DecodedPartition(0, 0), DecodedPartition(9, 1)), decoded.partitions[2])
        assertFailsWith<IllegalArgumentException> { DeclaredKeySummaryBuilder(table).encode(ByteArray(31), ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { DeclaredKeySummaryBuilder(table).encode(ByteArray(32), ByteArray(33)) }
        assertContentEquals(ByteArray(32) { 9 }, summary().layoutDigest)
    }

    @Test
    fun `utf8 length counts bytes without encoding`() {
        for (text in listOf("", "ascii", "é", "类型", "🚀", "a\u0000b", "x".repeat(1000) + "🚀")) {
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), DeclaredKeySummaryBuilder.utf8Length(text), text)
        }
    }

    @Test
    fun `a saved graph carries the sidecar bound to its table and a table-less graph none`() {
        val dir = Files.createTempDirectory("declared-keys")
        try {
            val graph = DefaultGraph.Builder().apply {
                setDeclaredTypes(table)
                for (node in listOf(field(1, "name"), field(2, "other"), parameter(3, 0, declared), parameter(4, 0, undeclared),
                    ReturnNode(NodeId(5), declared))) addNode(node)
            }.build()
            GraphStore.save(graph, dir)
            val sidecar = dir.resolve(DeclaredKeySummaryBuilder.FILE_NAME)
            val decoded = DecodedDeclaredKeys.decode(Files.readAllBytes(sidecar))
            val types = Files.readAllBytes(dir.resolve("graph.types"))
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(types), decoded.typesDigest)
            // The node layout binding: the SHA-256 of graph.nodedata, recorded in forward.properties as well.
            val nodedata = Files.readAllBytes(dir.resolve("graph.nodedata"))
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(nodedata), decoded.layoutDigest)
            assertEquals(
                HexFormat.of().formatHex(decoded.layoutDigest),
                ForwardProperties.read(dir, ForwardProperties.NODE_LAYOUT_KEY)
            )
            assertEquals(DecodedDeclaredKeys.expected(graph), decoded.partitions)
            assertEquals(1, decoded.fieldCount)
            assertEquals(1, decoded.methodCount)
            // A save without declarations removes the sidecar an earlier save left.
            GraphStore.save(DefaultGraph.Builder().apply { addNode(field(1, "name")) }.build(), dir)
            assertFalse(Files.exists(sidecar))
            assertTrue(Files.exists(dir.resolve("graph.nodedata")))
            // A graph without declarations still records its node layout.
            assertEquals(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("graph.nodedata")))),
                ForwardProperties.read(dir, ForwardProperties.NODE_LAYOUT_KEY)
            )
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    @Test
    fun `two graphs with one table and swapped node ids never accept each other's sidecars`() {
        val root = Files.createTempDirectory("declared-keys-layout")
        try {
            // Same table, same strings, same metadata: only which field sits at which node id differs.
            val first = DefaultGraph.Builder().apply {
                setDeclaredTypes(table)
                addNode(field(1, "name")); addNode(field(2, "other"))
                addNode(parameter(3, 0, declared)); addNode(parameter(4, 0, undeclared))
            }.build()
            val second = DefaultGraph.Builder().apply {
                setDeclaredTypes(table)
                addNode(field(1, "other")); addNode(field(2, "name"))
                addNode(parameter(3, 0, undeclared)); addNode(parameter(4, 0, declared))
            }.build()
            val firstDir = Files.createDirectory(root.resolve("first"))
            val secondDir = Files.createDirectory(root.resolve("second"))
            GraphStore.save(first, firstDir)
            GraphStore.save(second, secondDir)
            val typesKey = ForwardProperties.DECLARED_TYPES_KEY
            val layoutKey = ForwardProperties.NODE_LAYOUT_KEY
            assertEquals(ForwardProperties.read(firstDir, typesKey), ForwardProperties.read(secondDir, typesKey), "one table")
            assertNotEquals(ForwardProperties.read(firstDir, layoutKey), ForwardProperties.read(secondDir, layoutKey), "two layouts")
            val firstKeys = DecodedDeclaredKeys.decode(Files.readAllBytes(firstDir.resolve(DeclaredKeySummaryBuilder.FILE_NAME)))
            val secondKeys = DecodedDeclaredKeys.decode(Files.readAllBytes(secondDir.resolve(DeclaredKeySummaryBuilder.FILE_NAME)))
            assertContentEquals(firstKeys.typesDigest, secondKeys.typesDigest)
            assertEquals(listOf(DecodedPartition(2, 1), DecodedPartition(1, 1)), firstKeys.partitions[0])
            assertEquals(listOf(DecodedPartition(1, 1), DecodedPartition(2, 1)), secondKeys.partitions[0])
            assertEquals(HexFormat.of().formatHex(secondKeys.layoutDigest), ForwardProperties.read(secondDir, layoutKey))
            assertNotEquals(HexFormat.of().formatHex(secondKeys.layoutDigest), ForwardProperties.read(firstDir, layoutKey))
            // The second graph's presence sidecar under the first graph's directory: bound to the same
            // table, but not to these nodes, so it reads as absent and the first graph's answers come
            // from its table. Accepted, it would have said node 1 has no declaration.
            val presence = DeclaredPresence.FILE_NAME
            val own = Files.readAllBytes(firstDir.resolve(presence))
            Files.copy(secondDir.resolve(presence), firstDir.resolve(presence), StandardCopyOption.REPLACE_EXISTING)
            val nodeBits = 5
            assertNull(DeclaredPresence.load(firstDir, nodeBits, 0))
            val swapped = GraphStore.loadMapped(firstDir)
            try {
                val binding = assertNotNull(swapped.declaredTypeBinding(), "a GTY05 mapped graph binds by IDs")
                val name = swapped.nodes(FieldNode::class.java).single { it.descriptor.name == "name" }
                assertEquals(1, name.id.value)
                assertEquals(DeclaredTypeBinding.UNANSWERED, binding.nodePresence(name))
                assertEquals(0, binding.fieldTypeId(name), "the table still binds the field at node 1")
                assertEquals(
                    listOf(mapOf("name" to "name", "declared" to true), mapOf("name" to "other", "declared" to false)),
                    CypherExecutor(swapped).execute(
                        "MATCH (n:Field) RETURN n.name AS name, n.generic_type IS NOT NULL AS declared ORDER BY name"
                    ).rows
                )
            } finally { (swapped as? AutoCloseable)?.close() }
            Files.write(firstDir.resolve(presence), own)
            val restored = assertNotNull(DeclaredPresence.load(firstDir, nodeBits, 0))
            assertEquals(DeclaredTypeBinding.PRESENT, restored.node(1))
            assertEquals(DeclaredTypeBinding.ABSENT, restored.node(2))
            assertEquals(DeclaredTypeBinding.PRESENT, restored.node(3))
            assertEquals(DeclaredTypeBinding.ABSENT, restored.node(4))
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
