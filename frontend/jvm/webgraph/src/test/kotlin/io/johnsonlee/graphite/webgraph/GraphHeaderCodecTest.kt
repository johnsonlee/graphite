package io.johnsonlee.graphite.webgraph

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Verifies graph header codec. */
class GraphHeaderCodecTest : GraphStoreTestFixture() {

    @Test
    fun `writeHeader and readHeader round-trip via DataInputStream`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_METADATA)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        assertEquals(NodeSerializer.FORMAT_VERSION, version)
    }

    @Test
    fun `readHeader with invalid magic via DataInputStream throws`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(0x12345678) // wrong magic
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertFailsWith<IllegalArgumentException> { NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA) }
    }

    @Test
    fun `readHeader with legacy version via DataInputStream succeeds`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        assertEquals(1, version)
    }

    @Test
    fun `readHeader with invalid magic via RandomAccessFile throws`() {
        val tmpFile = Files.createTempFile("bad-magic", ".bin")
        try {
            // Write wrong magic into the file
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos ->
                dos.writeInt(0x12345678) // wrong magic
            }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                assertFailsWith<IllegalArgumentException> { NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA) }
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader via RandomAccessFile round-trip`() {
        val tmpFile = Files.createTempFile("raf-header", ".bin")
        try {
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos -> NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEDATA) }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                val version = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                assertEquals(NodeSerializer.FORMAT_VERSION, version)
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader with legacy version via RandomAccessFile succeeds`() {
        val tmpFile = Files.createTempFile("raf-unsupported-version", ".bin")
        try {
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos -> dos.writeInt(NodeSerializer.MAGIC_NODEDATA or 0x01) }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                val version = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                assertEquals(1, version)
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader with unknown version throws`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x04)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val error = assertFailsWith<IllegalArgumentException> { NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA) }
        assertTrue(error.message!!.contains("Unsupported GraphStore format version 4"))
    }

    @Test
    fun `invalid magic in metadata file throws on load`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("bad-magic-test")
        try {
            GraphStore.save(graph, dir)
            // Corrupt the metadata file
            val metadataFile = dir.resolve("graph.metadata").toFile()
            val bytes = metadataFile.readBytes()
            bytes[0] = 0x00 // corrupt magic
            metadataFile.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> { GraphStore.load(dir) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
