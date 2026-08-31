package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal interface NodeTypeIndex {
    fun ids(type: Class<out Node>): Sequence<Int>
    fun forEachIdWhile(
        type: Class<out Node>,
        startIndex: Int,
        endIndex: Int,
        action: (Int) -> Boolean
    ) {
        for (nodeId in ids(type).drop(startIndex).take(endIndex - startIndex)) {
            if (!action(nodeId)) return
        }
    }
    fun count(type: Class<out Node>): Long
}

internal class MappedNodeTypeIndex private constructor(
    private val buffer: MappedByteBuffer,
    private val rangesByType: Map<Class<out Node>, NodeTypeRange>
) : NodeTypeIndex {

    override fun ids(type: Class<out Node>): Sequence<Int> =
        Sequence { MappedNodeIdIterator(buffer, ranges(type)) }

    override fun forEachIdWhile(
        type: Class<out Node>,
        startIndex: Int,
        endIndex: Int,
        action: (Int) -> Boolean
    ) {
        require(startIndex >= 0 && endIndex >= startIndex)
        var ordinal = 0
        for (range in ranges(type)) {
            val rangeEnd = ordinal + range.count
            if (startIndex < rangeEnd && endIndex > ordinal) {
                val localStart = (startIndex - ordinal).coerceAtLeast(0)
                val localEnd = (endIndex - ordinal).coerceAtMost(range.count)
                var offset = range.offset + localStart * Int.SIZE_BYTES
                repeat(localEnd - localStart) {
                    if (!action(buffer.getInt(offset))) return
                    offset += Int.SIZE_BYTES
                }
            }
            ordinal = rangeEnd
            if (ordinal >= endIndex) break
        }
    }

    override fun count(type: Class<out Node>): Long =
        ranges(type).sumOf { it.count.toLong() }

    private fun ranges(type: Class<out Node>): List<NodeTypeRange> {
        rangesByType[type]?.let { return listOf(it) }
        return rangesByType.entries
            .filter { type.isAssignableFrom(it.key) }
            .map { it.value }
    }

    companion object {
        fun load(path: Path): MappedNodeTypeIndex {
            val ranges = HashMap<Class<out Node>, NodeTypeRange>()
            DataInputStream(BufferedInputStream(path.toFile().inputStream())).use { dis ->
                NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_TYPEINDEX)
                val entryCount = dis.readInt()
                require(entryCount >= 0) { "Invalid mapped node type index entry count: $entryCount" }
                repeat(entryCount) {
                    val tag = dis.readUnsignedByte()
                    val count = dis.readInt()
                    val offset = dis.readLong()
                    require(count >= 0) { "Invalid mapped node type index count: $count" }
                    require(offset <= Int.MAX_VALUE) { "Mapped node type index offset exceeds Int range: $offset" }
                    nodeClassForTag(tag)?.let { type ->
                        ranges[type] = NodeTypeRange(count, offset.toInt())
                    }
                }
            }

            val channel = FileChannel.open(path, StandardOpenOption.READ)
            val mapped = try {
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            } finally {
                channel.close()
            }
            return MappedNodeTypeIndex(mapped, ranges)
        }
    }
}

internal data class NodeTypeRange(
    val count: Int,
    val offset: Int
)

private class MappedNodeIdIterator(
    private val buffer: MappedByteBuffer,
    private val ranges: List<NodeTypeRange>
) : IntIterator() {
    private var rangeIndex = -1
    private var offset = 0
    private var remaining = 0

    override fun hasNext(): Boolean {
        while (remaining == 0 && ++rangeIndex < ranges.size) {
            val range = ranges[rangeIndex]
            offset = range.offset
            remaining = range.count
        }
        return remaining > 0
    }

    override fun nextInt(): Int {
        if (!hasNext()) throw NoSuchElementException()
        val nodeId = buffer.getInt(offset)
        offset += Int.SIZE_BYTES
        remaining--
        return nodeId
    }
}

internal fun nodeClassForTag(tag: Int): Class<out Node>? = NODE_CLASSES_BY_TAG.getOrNull(tag)

internal fun nodeTagEntries(): IntArray = NODE_TAGS.copyOf()

private val NODE_CLASSES_BY_TAG: Array<Class<out Node>?> = arrayOf(
    IntConstant::class.java,
    StringConstant::class.java,
    LongConstant::class.java,
    FloatConstant::class.java,
    DoubleConstant::class.java,
    BooleanConstant::class.java,
    NullConstant::class.java,
    EnumConstant::class.java,
    LocalVariable::class.java,
    FieldNode::class.java,
    ParameterNode::class.java,
    ReturnNode::class.java,
    CallSiteNode::class.java,
    AnnotationNode::class.java,
    ResourceValueNode::class.java,
    ResourceFileNode::class.java
)

private val NODE_TAGS: IntArray = IntArray(NODE_CLASSES_BY_TAG.size) { it }
