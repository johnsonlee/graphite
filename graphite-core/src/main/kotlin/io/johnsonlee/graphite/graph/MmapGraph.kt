package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
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
 * Node and edge data are read on demand from `nodes.dat` and `edges.dat`
 * via [RandomAccessFile.seek].  Only lightweight indexes (nodeId -> file offset,
 * nodeType -> nodeIds) are held in memory.
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - Node index: ~47 MB (`LongArray`, 8 bytes per slot x 5.9M)
 * - Edge indexes: ~132 MB (`LongArray` offsets + `IntArray` starts)
 * - Type index: ~24 MB (`IntArray` node ids)
 * - Total: ~200 MB without per-entry `HashMap` / `MutableList` object overhead
 *
 * Created by [MmapGraphBuilder.build].
 */
@Suppress("LongParameterList")
class MmapGraph internal constructor(
    private val dataDir: Path,
    private val nodeIndex: LongArray,
    private val nodeTypeIndex: Map<Class<out Node>, IntArray>,
    private val outgoingIndex: EdgeOffsetIndex,
    private val nodeMethods: List<MethodDescriptor>,
    private val nodeTypes: List<TypeDescriptor>,
    private val methodIndex: Map<String, MethodDescriptor>,
    private val typeHierarchy: TypeHierarchy,
    private val enumValuesMap: Map<String, List<Any?>>,
    private val classOriginsMap: Map<String, String>,
    private val artifactDependenciesMap: Map<String, Map<String, Int>>,
    private val memberAnnotationsMap: Map<String, Map<String, Map<String, Any?>>>,
    private val branchScopeData: List<DefaultGraph.RawBranchScope>,
    incomingIndex: EdgeOffsetIndex?,
    override val resources: ResourceAccessor
) : Graph, Closeable {

    private val nodeMmap: ByteBuffer = FileChannel.open(dataDir.resolve("nodes.dat"), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }
    private val edgeMmap: ByteBuffer = FileChannel.open(dataDir.resolve("edges.dat"), StandardOpenOption.READ).use {
        it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
    }
    private val incomingIndex: EdgeOffsetIndex by lazy {
        incomingIndex ?: buildIncomingEdgeOffsetIndex()
    }

    private val branchScopeIndex: Map<Int, List<BranchScope>> by lazy {
        branchScopeData.map { raw ->
            BranchScope(
                conditionNodeId = NodeId(raw.conditionNodeId),
                method = raw.method,
                comparison = raw.comparison,
                trueBranchNodeIds = IntOpenHashSet(raw.trueBranchNodeIds),
                falseBranchNodeIds = IntOpenHashSet(raw.falseBranchNodeIds)
            )
        }.groupBy { it.conditionNodeId.value }
    }

    internal data class EdgeOffsetIndex(
        val starts: IntArray,
        val offsets: LongOffsetIndex
    )

    internal interface LongOffsetIndex {
        operator fun get(position: Int): Long
    }

    internal class HeapLongOffsetIndex(private val offsets: LongArray) : LongOffsetIndex {
        override fun get(position: Int): Long = offsets[position]
    }

    internal class MappedLongOffsetIndex(path: Path) : LongOffsetIndex {
        private val offsets: ByteBuffer = FileChannel.open(path, StandardOpenOption.READ).use {
            it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
        }

        override fun get(position: Int): Long = offsets.getLong(position * Long.SIZE_BYTES)
    }

    override fun node(id: NodeId): Node? {
        val nodeId = id.value
        if (nodeId < 0 || nodeId >= nodeIndex.size) return null
        val offset = nodeIndex[nodeId]
        if (offset < 0L) return null
        return readNodeAt(offset)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        val ids = when {
            type == Node::class.java -> return allNodes() as Sequence<T>
            nodeTypeIndex.containsKey(type) -> nodeTypeIndex.getValue(type).asSequence()
            else -> nodeTypeIndex.entries.asSequence()
                .filter { type.isAssignableFrom(it.key) }
                .flatMap { it.value.asSequence() }
        }
        return ids.mapNotNull { node(NodeId(it)) as? T }
    }

    override fun outgoing(id: NodeId): Sequence<Edge> {
        return edgeOffsets(outgoingIndex, id).map { readEdgeAt(it) }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        return edgeOffsets(incomingIndex, id).map { readEdgeAt(it) }
    }

    fun forEachOutgoingEdge(nodeId: Int, action: (Edge) -> Unit) {
        if (nodeId < 0 || nodeId + 1 >= outgoingIndex.starts.size) return
        val start = outgoingIndex.starts[nodeId]
        val end = outgoingIndex.starts[nodeId + 1]
        if (start == end) return
        val buf = edgeMmap.duplicate()
        for (position in start until end) {
            action(readEdgeAt(buf, outgoingIndex.offsets[position]))
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
    override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> =
        outgoing(id).filter { type.isInstance(it) } as Sequence<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> =
        incoming(id).filter { type.isInstance(it) } as Sequence<T>

    override fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> =
        nodes(CallSiteNode::class.java).filter { methodPattern.matches(it.callee) }

    override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        typeHierarchy.supertypes(type)

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        typeHierarchy.subtypes(type)

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        methodIndex.values.asSequence().filter { pattern.matches(it) }

    override fun methodCount(): Long = methodIndex.size.toLong()

    override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> =
        methods(pattern).take(limit.coerceAtLeast(0)).toList()

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        enumValuesMap["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        memberAnnotationsMap["$className#$memberName"] ?: emptyMap()

    override fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>> =
        memberAnnotationsMap

    override fun classOrigin(className: String): String? = classOriginsMap[className]

    override fun classOrigins(): Map<String, String> = classOriginsMap

    override fun artifactDependencies(): Map<String, Map<String, Int>> = artifactDependenciesMap

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun typeHierarchyTypes(): Set<String> = typeHierarchy.allKeys()

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

    private fun readEdgeAt(offset: Long): Edge {
        val buf = edgeMmap.duplicate()
        return readEdgeAt(buf, offset)
    }

    private fun readEdgeAt(buf: ByteBuffer, offset: Long): Edge {
        buf.position(offset.toInt())
        val from = NodeId(buf.int)
        val to = NodeId(buf.int)
        return when (val tag = buf.get().toInt()) {
            MmapGraphBuilder.TAG_EDGE_DATAFLOW ->
                DataFlowEdge(from, to, DataFlowKind.entries[buf.get().toInt()])
            MmapGraphBuilder.TAG_EDGE_CALL -> {
                val flags = buf.get().toInt()
                CallEdge(from, to, isVirtual = (flags and 1) != 0, isDynamic = (flags and 2) != 0)
            }
            MmapGraphBuilder.TAG_EDGE_TYPE ->
                TypeEdge(from, to, TypeRelation.entries[buf.get().toInt()])
            MmapGraphBuilder.TAG_EDGE_CONTROL_FLOW -> {
                val kind = ControlFlowKind.entries[buf.get().toInt()]
                val hasComparison = buf.get().toInt() == 1
                val comparison = if (hasComparison) {
                    BranchComparison(ComparisonOp.entries[buf.get().toInt()], NodeId(buf.int))
                } else {
                    null
                }
                ControlFlowEdge(from, to, kind, comparison)
            }
            MmapGraphBuilder.TAG_EDGE_RESOURCE ->
                ResourceEdge(from, to, ResourceRelation.entries[buf.get().toInt()])
            else -> error("Unknown edge type tag: $tag")
        }
    }

    private fun edgeOffsets(index: EdgeOffsetIndex, id: NodeId): Sequence<Long> {
        val nodeId = id.value
        if (nodeId < 0 || nodeId + 1 >= index.starts.size) return emptySequence()
        val start = index.starts[nodeId]
        val end = index.starts[nodeId + 1]
        if (start == end) return emptySequence()
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
            skipEdgePayload(countBuf)
            totalEdges++
        }

        val starts = buildPrefixStarts(counts)
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
            skipEdgePayload(fillBuf)
            offsets.putLong(counts[to]++, recordOffset)
        }

        return EdgeOffsetIndex(starts, MappedLongOffsetIndex(offsetsFile))
    }

    internal class WritableLongOffsetIndex private constructor(
        private val channel: FileChannel,
        private val buffer: ByteBuffer
    ) : Closeable {
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
                val channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
                )
                channel.truncate(bytes)
                val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes)
                return WritableLongOffsetIndex(channel, buffer)
            }
        }
    }

    internal companion object {
        fun createMappedLongOffsetIndex(path: Path, entries: Int): WritableLongOffsetIndex =
            WritableLongOffsetIndex.create(path, entries)
    }

    private fun skipEdgePayload(buf: ByteBuffer) {
        when (buf.get().toInt()) {
            MmapGraphBuilder.TAG_EDGE_DATAFLOW,
            MmapGraphBuilder.TAG_EDGE_CALL,
            MmapGraphBuilder.TAG_EDGE_TYPE,
            MmapGraphBuilder.TAG_EDGE_RESOURCE -> buf.get()
            MmapGraphBuilder.TAG_EDGE_CONTROL_FLOW -> {
                buf.get()
                if (buf.get().toInt() == 1) {
                    buf.get()
                    buf.int
                }
            }
            else -> error("Unknown edge type tag")
        }
    }

    private fun buildPrefixStarts(counts: IntArray): IntArray {
        val starts = IntArray(counts.size + 1)
        for (i in counts.indices) {
            starts[i + 1] = starts[i] + counts[i]
        }
        return starts
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

        override fun readLine(): String =
            throw UnsupportedOperationException("ByteBufferDataInput does not support readLine")

        override fun readUTF(): String = DataInputStream.readUTF(this)

        private fun requireRemaining(bytes: Int) {
            if (position < 0 || position > buf.limit() - bytes) throw EOFException()
        }
    }
}
