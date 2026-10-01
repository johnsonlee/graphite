package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Persistence of synthetic identities through the optional `GRS` section of `graph.metadata`. */
class SyntheticIdentityPersistenceTest {

    private val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "run", emptyList(), TypeDescriptor("void"))
    private val classKey = "com.example.Foo\$1"
    private val methodKey = "com.example.Foo.lambda\$run\$0(int)"
    private val classFingerprint = "0123456789abcdef0123456789abcdef"
    private val methodFingerprint = "fedcba9876543210fedcba9876543210"

    private fun buildGraph(withIdentities: Boolean = true): Graph {
        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(IntConstant(NodeId(1), 1))
        if (withIdentities) {
            builder.addSyntheticIdentity(classKey, classFingerprint)
            builder.addSyntheticIdentity(methodKey, methodFingerprint)
        }
        return builder.build()
    }

    private fun withSavedGraph(withIdentities: Boolean = true, block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("webgraph-synthetic-identity-test")
        try {
            GraphStore.save(buildGraph(withIdentities), dir)
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun metadata(dir: Path): Path = dir.resolve("graph.metadata")

    private fun assertLoaded(loaded: Graph) {
        assertEquals(mapOf(classKey to classFingerprint, methodKey to methodFingerprint), loaded.syntheticIdentities())
        assertEquals(classFingerprint, loaded.syntheticIdentity(classKey))
        assertNull(loaded.syntheticIdentity("com.example.Foo"))
        assertEquals(2, loaded.syntheticIdentities().size)
        assertTrue(loaded.syntheticIdentities().containsKey(methodKey))
        assertFalse(loaded.syntheticIdentities().containsKey("com.example.Foo"))
        assertEquals(setOf(classKey, methodKey), loaded.syntheticIdentities().keys)
    }

    @Test
    fun `identities survive an eager and a mapped round trip`() = withSavedGraph { dir ->
        assertLoaded(GraphStore.load(dir, GraphStore.LoadMode.EAGER))
        assertLoaded(GraphStore.load(dir, GraphStore.LoadMode.MAPPED))
    }

    @Test
    fun `the section follows the trailer so older readers stop before it`() = withSavedGraph { dir ->
        val bytes = Files.readAllBytes(metadata(dir))
        val sectionBytes = Int.SIZE_BYTES + Int.SIZE_BYTES + 2 * (Int.SIZE_BYTES + NodeSerializer.FINGERPRINT_BYTES)
        val trailerStart = bytes.size - sectionBytes - NodeSerializer.METADATA_TRAILER_BYTES
        val trailer = DataInputStream(ByteArrayInputStream(bytes, trailerStart, NodeSerializer.METADATA_TRAILER_BYTES))
        assertNotNull(NodeSerializer.readMetadataTrailer(trailer), "trailer precedes the section")
        val section = DataInputStream(ByteArrayInputStream(bytes, trailerStart + NodeSerializer.METADATA_TRAILER_BYTES, sectionBytes))
        assertEquals(NodeSerializer.MAGIC_SYNTHETIC_IDENTITIES or NodeSerializer.SYNTHETIC_IDENTITIES_VERSION, section.readInt())
        assertEquals(2, section.readInt())

        // A file written before the section existed: everything after the trailer removed.
        Files.write(metadata(dir), bytes.copyOf(trailerStart + NodeSerializer.METADATA_TRAILER_BYTES))
        val loaded = GraphStore.load(dir)
        assertEquals(emptyMap(), loaded.syntheticIdentities())
        assertEquals(0, loaded.branchScopes().count(), "trailer still read")
    }

    @Test
    fun `a graph without identities writes no section`() = withSavedGraph(withIdentities = false) { dir ->
        val bytes = Files.readAllBytes(metadata(dir))
        val trailerStart = bytes.size - NodeSerializer.METADATA_TRAILER_BYTES
        val trailer = DataInputStream(ByteArrayInputStream(bytes, trailerStart, NodeSerializer.METADATA_TRAILER_BYTES))
        assertNotNull(NodeSerializer.readMetadataTrailer(trailer), "file ends with the trailer")
        assertEquals(emptyMap(), GraphStore.load(dir).syntheticIdentities())
    }

    @Test
    fun `a truncated trailer or section is dropped and the graph still loads`() {
        withSavedGraph(withIdentities = false) { dir ->
            val bytes = Files.readAllBytes(metadata(dir))
            Files.write(metadata(dir), bytes.copyOf(bytes.size - 1))
            val loaded = GraphStore.load(dir)
            assertEquals(1, loaded.nodes(io.johnsonlee.graphite.core.Node::class.java).count(), "node data is intact")
            assertEquals(emptyMap(), loaded.syntheticIdentities())
            assertEquals(0, loaded.branchScopes().count(), "an incomplete trailer leaves the sidecar unbound")
        }
        withSavedGraph { dir ->
            val bytes = Files.readAllBytes(metadata(dir))
            Files.write(metadata(dir), bytes.copyOf(bytes.size - 1))
            val loaded = GraphStore.load(dir)
            assertEquals(emptyMap(), loaded.syntheticIdentities(), "an incomplete identity section is dropped whole")
            assertEquals(1, loaded.nodes(io.johnsonlee.graphite.core.Node::class.java).count())
        }
    }

    @Test
    fun `a negative identity count ends the optional sections`() {
        val dir = Files.createTempDirectory("webgraph-synthetic-identity-count")
        try {
            val strings = StringTable.build(listOf(classKey), dir)
            val base = GraphMetadata(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { dos ->
                dos.writeInt(NodeSerializer.MAGIC_SYNTHETIC_IDENTITIES or NodeSerializer.SYNTHETIC_IDENTITIES_VERSION)
                dos.writeInt(-1)
            }
            val read = NodeSerializer.readMetadataOptionalSections(DataInputStream(ByteArrayInputStream(out.toByteArray())), strings, base)
            assertEquals(emptyMap(), read.syntheticIdentities)
            assertNull(read.branchDefinitionDigest)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `optional sections are read in either order and stop at an unknown header`() {
        val dir = Files.createTempDirectory("webgraph-synthetic-identity-sections")
        try {
            val strings = StringTable.build(listOf(classKey, methodKey), dir)
            val digest = ByteArray(NodeSerializer.DIGEST_BYTES) { it.toByte() }
            val base = GraphMetadata(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
            val withIdentities = base.copy(syntheticIdentities = mapOf(classKey to classFingerprint, methodKey to methodFingerprint))

            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { dos ->
                NodeSerializer.writeSyntheticIdentities(withIdentities, dos, strings)
                NodeSerializer.writeMetadataTrailer(dos, digest)
                dos.writeInt(0x47524D03) // another section's header: not ours, reading stops
                dos.writeInt(42)
            }
            val input = DataInputStream(ByteArrayInputStream(out.toByteArray()))
            val read = NodeSerializer.readMetadataOptionalSections(input, strings, base)
            assertEquals(withIdentities.syntheticIdentities, read.syntheticIdentities)
            assertContentEquals(digest, read.branchDefinitionDigest)
            assertEquals(42, input.readInt(), "the unknown header's payload is left unread")

            val empty = NodeSerializer.readMetadataOptionalSections(DataInputStream(ByteArrayInputStream(ByteArray(0))), strings, base)
            assertEquals(emptyMap(), empty.syntheticIdentities)
            assertNull(empty.branchDefinitionDigest)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `identity keys are string table entries`() {
        val strings = mutableSetOf<String>()
        val metadata = GraphMetadata(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
            .copy(syntheticIdentities = mapOf(classKey to classFingerprint))
        NodeSerializer.collectMetadataStrings(metadata, strings)
        assertEquals(setOf(classKey), strings)
    }

    @Test
    fun `fingerprints round trip through hex and reject malformed input`() {
        val bytes = ByteArray(NodeSerializer.FINGERPRINT_BYTES) { (it * 17).toByte() }
        assertContentEquals(bytes, NodeSerializer.decodeFingerprint(NodeSerializer.encodeFingerprint(bytes)))
        assertEquals(classFingerprint, NodeSerializer.encodeFingerprint(NodeSerializer.decodeFingerprint(classFingerprint)))
        assertFailsWith<IllegalArgumentException> { NodeSerializer.decodeFingerprint("abc") }
        assertFailsWith<IllegalArgumentException> { NodeSerializer.decodeFingerprint("zz" + classFingerprint.drop(2)) }
        val malformed = DefaultGraph.Builder().addSyntheticIdentity(classKey, "nope").build()
        val dir = Files.createTempDirectory("bad-fingerprint")
        try {
            assertTrue(assertFailsWith<IllegalArgumentException> { GraphStore.save(malformed, dir) }.message!!.contains("hex"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
