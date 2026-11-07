package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbandonedMmapGraphTest {
    @Test
    fun `abort preserves unrelated files in a caller supplied directory`() {
        val directory = Files.createTempDirectory("shared-mmap-directory")
        try {
            val marker = directory.resolve("keep.txt")
            val nested = Files.createDirectory(directory.resolve("unrelated"))
            Files.writeString(marker, "user data")
            Files.writeString(directory.resolve("outgoing-offsets.dat"), "untouched index")
            Files.writeString(nested.resolve("nested.txt"), "nested user data")
            val builder = MmapGraphBuilder(directory)
            builder.abort()
            assertEquals("user data", Files.readString(marker))
            assertEquals("nested user data", Files.readString(nested.resolve("nested.txt")))
            assertEquals("untouched index", Files.readString(directory.resolve("outgoing-offsets.dat")))
            assertEquals(setOf("keep.txt", "unrelated", "outgoing-offsets.dat"), Files.list(directory).use { files ->
                files.map { it.fileName.toString() }.toList().toSet()
            })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `abort closes outputs and removes incomplete graph files`() {
        val directory = Files.createTempDirectory("abandoned-mmap-test")
        val builder = MmapGraphBuilder(directory)
        val method = MethodDescriptor(TypeDescriptor("example.App"), "run", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId(0), method))
        assertTrue(Files.list(directory).use { it.findAny().isPresent })
        builder.abort()
        assertFalse(Files.exists(directory), "a failed load must not retain the partial graph")
        builder.abort()
        assertFalse(Files.exists(directory), "cleanup is idempotent")
    }
}
