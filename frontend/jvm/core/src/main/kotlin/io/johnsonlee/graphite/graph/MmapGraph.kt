package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.input.ResourceAccessor
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val BYTE_MASK = 0xFF
private const val USHORT_MASK = 0xFFFF

/**
 * A [Graph] implementation backed by disk files.
 *
 * Node and edge data are read on demand from `nodes.dat` and `edges.dat` via [RandomAccessFile.seek]. Only lightweight indexes (nodeId ->
 * file offset, nodeType -> nodeIds) are held in memory.
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - Node index: ~47 MB (`LongArray`, 8 bytes per slot x 5.9M)
 * - Edge indexes: ~132 MB (`LongArray` offsets + `IntArray` starts)
 * - Type index: ~24 MB (`IntArray` node ids)
 * - Total: ~200 MB without per-entry `HashMap` / `MutableList` object overhead
 *
 * Created by [MmapGraphBuilder.build].
 */
class MmapGraph
internal constructor(
    nodes: MappedNodeInputs,
    edges: MappedEdgeInputs,
    metadata: GraphMetadataInputs,
    branches: RawBranchInputs,
    override val resources: ResourceAccessor
) : IndexedGraphMetadata(metadata), Closeable, PackedBranchMetadataSource {
    private val dataDir = nodes.directory
    private val nodeIndex = nodes.offsets
    private val nodeTypeIndex = nodes.byType
    private val nodeMethods = nodes.methods
    private val nodeTypes = nodes.types
    private val outgoingIndex = edges.outgoing
    private val branchScopeData = branches.scopes
    private val localDefinitionData = branches.definitions

    private val nodeMmap: ByteBuffer =
        FileChannel.open(dataDir.resolve("nodes.dat"), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
    private val edgeMmap: ByteBuffer =
        FileChannel.open(dataDir.resolve("edges.dat"), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
    private val edgeReader = MmapEdgeReader(edgeMmap)
    private val incomingIndex: EdgeOffsetIndex by lazy { edges.incoming ?: buildIncomingEdgeOffsetIndex() }

    private val branchScopeIndex = lazy { branchScopeData.map { it.toBranchScope() }.groupBy { it.conditionNodeId.value } }

    internal data class EdgeOffsetIndex(val starts: IntArray, val offsets: LongOffsetIndex)

    internal interface LongOffsetIndex {
        operator fun get(position: Int): Long
    }

    internal class HeapLongOffsetIndex(private val offsets: LongArray) : LongOffsetIndex {
        override fun get(position: Int): Long = offsets[position]
    }

    internal class MappedLongOffsetIndex(path: Path) : LongOffsetIndex {
        private val offsets: ByteBuffer =
            FileChannel.open(path, StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

        override fun get(position: Int): Long = offsets.getLong(position * Long.SIZE_BYTES)
    }

    override fun node(id: NodeId): Node? {
        val nodeId = id.value
        val offset = nodeIndex.getOrNull(nodeId)?.takeIf { it >= 0L }
        return offset?.let(::readNodeAt)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        val ids =
            when {
                type == Node::class.java -> return allNodes() as Sequence<T>
                nodeTypeIndex.containsKey(type) -> nodeTypeIndex.getValue(type).asSequence()
                else -> nodeTypeIndex.entries.asSequence().filter { type.isAssignableFrom(it.key) }.flatMap { it.value.asSequence() }
            }
        return ids.mapNotNull { node(NodeId(it)) as? T }
    }

    override fun outgoing(id: NodeId): Sequence<Edge> {
        return edgeOffsets(outgoingIndex, id).map { edgeReader.read(it) }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        return edgeOffsets(incomingIndex, id).map { edgeReader.read(it) }
    }

    fun forEachOutgoingEdge(nodeId: Int, action: (Edge) -> Unit) {
        if (nodeId < 0 || nodeId + 1 >= outgoingIndex.starts.size) return
        val start = outgoingIndex.starts[nodeId]
        val end = outgoingIndex.starts[nodeId + 1]
        if (start == end) return
        val buf = edgeMmap.duplicate()
        for (position in start until end) {
            action(edgeReader.read(buf, outgoingIndex.offsets[position]))
        }
    }

    fun forEachOutgoingTarget(nodeId: Int, action: (Int) -> Unit) {
        if (nodeId < 0 || nodeId + 1 >= outgoingIndex.starts.size) return
        val start = outgoingIndex.starts[nodeId]
        val end = outgoingIndex.starts[nodeId + 1]
        if (start == end) return
        val buf = edgeMmap.duplicate()
        for (position in start until end) {
            buf.position(outgoingIndex.offsets[position].toInt() + Int.SIZE_BYTES)
            action(buf.int)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> = outgoing(id).filter { type.isInstance(it) } as Sequence<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> = incoming(id).filter { type.isInstance(it) } as Sequence<T>

    override fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> =
        nodes(CallSiteNode::class.java).filter { methodPattern.matches(it.callee) }

    override fun branchScopes(): Sequence<BranchScope> = branchScopeIndex.value.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex.value[conditionNodeId.value]?.asSequence() ?: emptySequence()

    private val localDefinitionIndex = lazy { BranchScope.unpackDefinitionTable(localDefinitionData) }

    override fun localDefinitions(): Map<NodeId, List<LocalDefinition>> = localDefinitionIndex.value

    override fun packedBranchScopes(): Sequence<PackedBranchScope>? {
        if (branchScopeIndex.isInitialized()) return null
        return branchScopeData.packedBranchScopeSnapshot()
    }

    override fun packedLocalDefinitions(): Map<Int, IntArray>? {
        if (localDefinitionIndex.isInitialized()) return null
        return localDefinitionData.packedLocalDefinitionSnapshot()
    }

    override fun close() {
        // MappedByteBuffer is unmapped by GC; no explicit unmap in standard API
    }

    private fun readNodeAt(offset: Long): Node {
        val input = ByteBufferDataInput(nodeMmap, offset.toInt())
        input.readInt()
        return MmapGraphBuilder.deserializeNode(input, nodeMethods, nodeTypes)
    }

    private fun allNodes(): Sequence<Node> = sequence {
        val input = ByteBufferDataInput(nodeMmap, 0)
        while (input.hasRemaining()) {
            val len = input.readInt()
            val recordEnd = input.position + len
            yield(MmapGraphBuilder.deserializeNode(input, nodeMethods, nodeTypes))
            input.position = recordEnd
        }
    }

    private fun edgeOffsets(index: EdgeOffsetIndex, id: NodeId): Sequence<Long> {
        val nodeId = id.value
        if (nodeId < 0 || nodeId + 1 >= index.starts.size) return emptySequence()
        val start = index.starts[nodeId]
        val end = index.starts[nodeId + 1]
        return (start until end).asSequence().map { position -> index.offsets[position] }
    }

    private fun buildIncomingEdgeOffsetIndex(): EdgeOffsetIndex {
        val counts = IntArray(nodeIndex.size)
        var totalEdges = 0
        val countBuf = edgeMmap.duplicate()
        while (countBuf.hasRemaining()) {
            countBuf.int
            val to = countBuf.int
            counts[to]++
            edgeReader.skipPayload(countBuf)
            totalEdges++
        }

        val starts = edgeReader.prefixStarts(counts)
        if (totalEdges == 0) {
            return EdgeOffsetIndex(starts, HeapLongOffsetIndex(LongArray(0)))
        }
        val offsetsFile = Files.createTempFile(dataDir, "incoming-offsets", ".dat")
        val offsets = createMappedLongOffsetIndex(offsetsFile, totalEdges)
        System.arraycopy(starts, 0, counts, 0, counts.size)

        val fillBuf = edgeMmap.duplicate()
        while (fillBuf.hasRemaining()) {
            val recordOffset = fillBuf.position().toLong()
            fillBuf.int
            val to = fillBuf.int
            edgeReader.skipPayload(fillBuf)
            offsets.putLong(counts[to]++, recordOffset)
        }

        return EdgeOffsetIndex(starts, MappedLongOffsetIndex(offsetsFile))
    }

    internal class WritableLongOffsetIndex private constructor(private val channel: FileChannel, private val buffer: ByteBuffer) :
        Closeable {
        fun putLong(position: Int, value: Long) {
            buffer.putLong(position * Long.SIZE_BYTES, value)
        }

        override fun close() {
            channel.close()
        }

        companion object {
            fun create(path: Path, entries: Int): WritableLongOffsetIndex {
                val bytes = entries.toLong() * Long.SIZE_BYTES
                require(bytes <= Int.MAX_VALUE) {
                    "Memory-mapped edge offset indexes support at most ${Int.MAX_VALUE / Long.SIZE_BYTES} edges"
                }
                val channel =
                    FileChannel.open(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)
                channel.truncate(bytes)
                val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
                return WritableLongOffsetIndex(channel, buffer)
            }
        }
    }

    internal companion object {
        fun createMappedLongOffsetIndex(path: Path, entries: Int): WritableLongOffsetIndex = WritableLongOffsetIndex.create(path, entries)
    }

    internal class ByteBufferInputStream(private val buf: ByteBuffer) : InputStream() {
        override fun read(): Int = if (buf.hasRemaining()) buf.get().toInt() and BYTE_MASK else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!buf.hasRemaining()) return -1
            val n = minOf(len, buf.remaining())
            buf.get(b, off, n)
            return n
        }
    }

    private class ByteBufferDataInput(private val buf: ByteBuffer, var position: Int) : DataInput {
        fun hasRemaining(): Boolean = position < buf.limit()

        override fun readFully(bytes: ByteArray) {
            readFully(bytes, 0, bytes.size)
        }

        override fun readFully(bytes: ByteArray, off: Int, len: Int) {
            requireRemaining(len)
            for (index in 0 until len) {
                bytes[off + index] = buf.get(position + index)
            }
            position += len
        }

        override fun skipBytes(n: Int): Int {
            if (n <= 0) return 0
            val skipped = minOf(n, buf.limit() - position)
            position += skipped
            return skipped
        }

        override fun readBoolean(): Boolean = readUnsignedByte() != 0

        override fun readByte(): Byte {
            requireRemaining(Byte.SIZE_BYTES)
            return buf.get(position++)
        }

        override fun readUnsignedByte(): Int = readByte().toInt() and BYTE_MASK

        override fun readShort(): Short {
            requireRemaining(Short.SIZE_BYTES)
            val value = buf.getShort(position)
            position += Short.SIZE_BYTES
            return value
        }

        override fun readUnsignedShort(): Int = readShort().toInt() and USHORT_MASK

        override fun readChar(): Char {
            requireRemaining(Char.SIZE_BYTES)
            val value = buf.getChar(position)
            position += Char.SIZE_BYTES
            return value
        }

        override fun readInt(): Int {
            requireRemaining(Int.SIZE_BYTES)
            val value = buf.getInt(position)
            position += Int.SIZE_BYTES
            return value
        }

        override fun readLong(): Long {
            requireRemaining(Long.SIZE_BYTES)
            val value = buf.getLong(position)
            position += Long.SIZE_BYTES
            return value
        }

        override fun readFloat(): Float {
            requireRemaining(Float.SIZE_BYTES)
            val value = buf.getFloat(position)
            position += Float.SIZE_BYTES
            return value
        }

        override fun readDouble(): Double {
            requireRemaining(Double.SIZE_BYTES)
            val value = buf.getDouble(position)
            position += Double.SIZE_BYTES
            return value
        }

        override fun readLine(): String = throw UnsupportedOperationException("ByteBufferDataInput does not support readLine")

        override fun readUTF(): String = DataInputStream.readUTF(this)

        private fun requireRemaining(bytes: Int) {
            if (position < 0 || position > buf.limit() - bytes) throw EOFException()
        }
    }
}
