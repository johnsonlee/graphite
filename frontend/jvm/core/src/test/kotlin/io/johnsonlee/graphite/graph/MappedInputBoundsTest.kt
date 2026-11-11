package io.johnsonlee.graphite.graph

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import java.nio.file.Files
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MappedInputBoundsTest {
    @Test
    fun `edge count limit is checked before narrowing to an index size`() {
        validateMappedEdgeCount(Int.MAX_VALUE.toLong())
        assertEquals("MmapGraphBuilder supports at most 2147483647 edges in memory-mapped indexes, got 2147483648",
            assertFailsWith<IllegalArgumentException> { validateMappedEdgeCount(Int.MAX_VALUE.toLong() + 1) }.message)
    }

    @Test
    fun `skip makes progress when stream refuses skip and distinguishes truncated payload`() {
        fun input() = DataInputStream(object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
            override fun skip(n: Long): Long = 0
        })
        input().use { stream ->
            skipFully(stream, 0)
            skipFully(stream, 2)
            assertEquals(3, stream.read())
        }
        assertEquals("Unexpected EOF while skipping node payload",
            assertFailsWith<EOFException> { input().use { skipFully(it, 4) } }.message)
        assertEquals("Cannot skip a negative byte count: -1",
            assertFailsWith<IllegalArgumentException> { input().use { skipFully(it, -1) } }.message)
    }

    @Test
    fun `builder rejects a corrupt edge record before creating its index`() {
        val directory = Files.createTempDirectory("corrupt-edge")
        val builder = MmapGraphBuilder(directory)
        try {
            builder.addNode(StringConstant(NodeId(1), "value"))
            Files.write(directory.resolve("edges.dat"), ByteBuffer.allocate(9).putInt(1).putInt(1).put(127).array())
            assertEquals("Unknown edge type tag during skip",
                assertFailsWith<IllegalStateException> { builder.build() }.message)
        } finally {
            builder.abort()
        }
    }

    @Test
    fun `mapped edge decoding and traversal reject unknown tags`() {
        val bytes = ByteBuffer.allocate(9).putInt(1).putInt(2).put(127).array()
        val reader = MmapEdgeReader(ByteBuffer.wrap(bytes))
        assertEquals("Unknown edge type tag: 127", assertFailsWith<IllegalStateException> { reader.read(0) }.message)
        assertEquals("Unknown edge type tag", assertFailsWith<IllegalStateException> {
            reader.skipPayload(ByteBuffer.wrap(byteArrayOf(127)))
        }.message)
    }
}
