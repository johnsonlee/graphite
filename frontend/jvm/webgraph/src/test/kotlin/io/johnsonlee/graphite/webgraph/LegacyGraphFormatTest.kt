package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies legacy graph format. */
class LegacyGraphFormatTest : GraphStoreTestFixture() {

    @Test
    fun `legacy v1 annotation node decodes as string values`() {
        val dir = Files.createTempDirectory("legacy-node-strings")
        try {
            val strings = StringTable.build(listOf("Lcom/example/Foo;", "com.example.Foo", "bar", "value", "hello"), dir)
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeInt(42)
                dos.writeByte(13)
                dos.writeInt(strings.indexOf("Lcom/example/Foo;"))
                dos.writeInt(strings.indexOf("com.example.Foo"))
                dos.writeInt(strings.indexOf("bar"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("value"))
                dos.writeInt(strings.indexOf("hello"))
            }
            val node = readGraphNode(DataInputStream(ByteArrayInputStream(baos.toByteArray())), strings, 1) as AnnotationNode
            assertEquals("hello", node.values["value"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 metadata decodes annotation values as strings`() {
        val dir = Files.createTempDirectory("legacy-metadata-strings")
        try {
            val strings = StringTable.build(listOf("com.example.Foo#bar", "javax.annotation.Nullable", "value", "hello"), dir)
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("com.example.Foo#bar"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("javax.annotation.Nullable"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("value"))
                dos.writeInt(strings.indexOf("hello"))
                dos.writeInt(0)
            }
            val metadata = readGraphMetadata(DataInputStream(ByteArrayInputStream(baos.toByteArray())), strings)
            assertEquals("hello", metadata.memberAnnotations["com.example.Foo#bar"]!!["javax.annotation.Nullable"]!!["value"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 graph directory migrates end to end`() {
        val fixture = createLegacyV1GraphDir()
        try {
            val loaded = GraphStore.load(fixture.dir)
            val loadedNode = loaded.node(fixture.annotationNode.id) as AnnotationNode
            assertEquals("hello", loadedNode.values["value"])
            assertEquals("hello", loaded.memberAnnotations("com.example.Foo", "bar")["javax.annotation.Nullable"]!!["value"])

            Files.deleteIfExists(fixture.dir.resolve("graph.nodeindex"))
            val mapped = GraphStore.load(fixture.dir, GraphStore.LoadMode.MAPPED)
            try {
                val mappedNode = mapped.node(fixture.annotationNode.id) as AnnotationNode
                assertEquals("hello", mappedNode.values["value"])
            } finally {
                (mapped as Closeable).close()
            }

            val migratedDir = Files.createTempDirectory("legacy-v3-migrated")
            try {
                GraphStore.save(loaded, migratedDir)

                DataInputStream(migratedDir.resolve("graph.nodedata").toFile().inputStream()).use { dis ->
                    assertEquals(NodeSerializer.FORMAT_VERSION, NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA))
                }
                DataInputStream(migratedDir.resolve("graph.metadata").toFile().inputStream()).use { dis ->
                    assertEquals(NodeSerializer.FORMAT_VERSION, NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA))
                }

                val migrated = GraphStore.load(migratedDir)
                val migratedNode = migrated.node(fixture.annotationNode.id) as AnnotationNode
                assertEquals("hello", migratedNode.values["value"])
                assertEquals("hello", migrated.memberAnnotations("com.example.Foo", "bar")["javax.annotation.Nullable"]!!["value"])
            } finally {
                migratedDir.toFile().deleteRecursively()
            }
        } finally {
            fixture.dir.toFile().deleteRecursively()
        }
    }
}
