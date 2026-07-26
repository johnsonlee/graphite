package io.johnsonlee.graphite.webgraph

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
