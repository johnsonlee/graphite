package io.johnsonlee.graphite.webgraph

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Arrays

internal interface NodeOffsetIndex {
    val size: Int
    fun offset(nodeId: Int): Long
}

internal interface MutableNodeOffsetIndex : NodeOffsetIndex {
    fun set(nodeId: Int, offset: Long)
    fun ensureSize(size: Int)
}

internal class IntNodeOffsetIndex(initialSize: Int) : MutableNodeOffsetIndex {
    private var offsets = IntArray(initialSize) { MISSING_OFFSET_INT }

    override val size: Int
        get() = offsets.size

    override fun offset(nodeId: Int): Long = offsets[nodeId].toLong()

    override fun set(nodeId: Int, offset: Long) {
        require(offset <= Int.MAX_VALUE) { "Node data offset exceeds Int range: $offset" }
        offsets[nodeId] = offset.toInt()
    }

    override fun ensureSize(size: Int) {
        if (size <= offsets.size) return
        val oldSize = offsets.size
        offsets = offsets.copyOf(size)
        Arrays.fill(offsets, oldSize, offsets.size, MISSING_OFFSET_INT)
    }

    private companion object {
        const val MISSING_OFFSET_INT = -1
    }
}

internal class LongNodeOffsetIndex(initialSize: Int) : MutableNodeOffsetIndex {
    private var offsets = LongArray(initialSize) { MISSING_OFFSET_LONG }

    override val size: Int
        get() = offsets.size

    override fun offset(nodeId: Int): Long = offsets[nodeId]

    override fun set(nodeId: Int, offset: Long) {
        offsets[nodeId] = offset
    }

    override fun ensureSize(size: Int) {
        if (size <= offsets.size) return
        val oldSize = offsets.size
        offsets = offsets.copyOf(size)
        Arrays.fill(offsets, oldSize, offsets.size, MISSING_OFFSET_LONG)
    }

    private companion object {
        const val MISSING_OFFSET_LONG = -1L
    }
}

internal class MappedNodeOffsetIndex private constructor(
    private val buffer: MappedByteBuffer,
    override val size: Int
) : NodeOffsetIndex {

    override fun offset(nodeId: Int): Long {
        val stored = buffer.getLong(DATA_START + nodeId * Long.SIZE_BYTES)
        return if (stored == MISSING_OFFSET_STORED) MISSING_OFFSET else stored - 1L
    }

    companion object {
        private const val DATA_START = Int.SIZE_BYTES + Int.SIZE_BYTES
        private const val MISSING_OFFSET_STORED = 0L
        private const val MISSING_OFFSET = -1L

        fun load(path: Path): MappedNodeOffsetIndex {
            val count = DataInputStream(BufferedInputStream(path.toFile().inputStream())).use { dis ->
                NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEOFFSETS)
                dis.readInt()
            }
            require(count >= 0) { "Invalid mapped node offset count: $count" }

            val channel = FileChannel.open(path, StandardOpenOption.READ)
            val mapped = try {
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            } finally {
                channel.close()
            }
            return MappedNodeOffsetIndex(mapped, count)
        }
    }
}
