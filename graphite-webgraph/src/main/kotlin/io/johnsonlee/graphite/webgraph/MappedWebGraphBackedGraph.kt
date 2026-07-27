package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.webgraph.ImmutableGraph
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInput
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer

/**
 * A [Graph] backed by WebGraph compression for edges and memory-mapped I/O for nodes.
 *
 * Unlike [WebGraphBackedGraph] which deserializes all nodes into heap, this
 * implementation memory-maps the `graph.nodedata` file. The OS manages paging:
 * accessed nodes are cached in physical RAM via the page cache, unused nodes
 * stay on disk. No JVM heap is used for node storage.
 *
 * Unlike a seek-based node reader, this uses [MappedByteBuffer] which translates
 * to direct memory reads -- no system calls after the initial page fault.
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - BVGraph forward: loaded during graph open
 * - BVGraph backward: built lazily on first incoming query
 * - Edge label map: loaded during graph open
 * - Node offset index: mmap-backed, nodeId -> offset
 * - Node type index: mmap-backed, type -> nodeId list
 * - StringTable: ~21 MB (heap)
 * - Node data: ~252 MB (mmap, NOT heap — managed by OS page cache)
 * - **Open heap before edge traversal: ~92 MB plus object overhead** vs ~4 GB
 *   for eager [WebGraphBackedGraph]
 *
 * Created by [GraphStore.loadMapped].
 */
@Suppress("LongParameterList")
internal class MappedWebGraphBackedGraph(
    private val forward: ImmutableGraph,
    private val backward: Lazy<ImmutableGraph>,
    private val mappedNodeData: MappedByteBuffer,
    private val nodeDataVersion: Int,
    private val stringTable: StringTable,
    private val nodeOffsets: NodeOffsetIndex,
    private val nodeTypeIndex: NodeTypeIndex,
    private val forwardLabels: ByteArray,
    private val cumulativeOutdeg: IntArray,
    private val edgeCount: Long,
    private val metadataFile: File,
    private val methodCount: Long,
    private val comparisonLookup: BranchComparisonLookup,
    private val metadata: Lazy<GraphMetadata>,
    private val classOverviewProvider: (Int) -> ClassOverview?,
    private val resourceAccessor: Lazy<ResourceAccessor>
) : Graph, Closeable {

    override val resources: ResourceAccessor
        get() = resourceAccessor.value

    private val branchScopeIndex: Map<Int, List<BranchScope>> by lazy {
        metadata.value.branchScopes.map { raw ->
            BranchScope(
                conditionNodeId = NodeId(raw.conditionNodeId),
                method = raw.method,
                comparison = raw.comparison,
                trueBranchNodeIds = IntOpenHashSet(raw.trueBranchNodeIds),
                falseBranchNodeIds = IntOpenHashSet(raw.falseBranchNodeIds)
            )
        }.groupBy { it.conditionNodeId.value }
    }

    override fun node(id: NodeId): Node? {
        val nodeId = id.value
        if (nodeId < 0 || nodeId >= nodeOffsets.size) return null
        val offset = nodeOffsets.offset(nodeId)
        if (offset == -1L) return null
        return readNodeAt(offset)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        return nodeTypeIndex.ids(type).mapNotNull { node(NodeId(it)) as? T }
    }

    override fun nodeCount(type: Class<out Node>): Long =
        nodeTypeIndex.count(type)

    override fun edgeCount(): Long = edgeCount

    override fun outgoing(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        if (nodeIdx >= forward.numNodes()) return emptySequence()
        val succs = forward.successorArray(nodeIdx)
        val outdeg = forward.outdegree(nodeIdx)
        val labelStart = cumulativeOutdeg[nodeIdx]
        return (0 until outdeg).asSequence().map { i ->
            val to = succs[i]
            val label = forwardLabels[labelStart + i].toInt() and BYTE_MASK
            val comparison = comparisonForEdge(label, nodeIdx, to, nodeDataVersion, comparisonLookup)
            NodeSerializer.decodeEdge(label, NodeId(nodeIdx), NodeId(to), comparison, nodeDataVersion)
        }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        val backwardGraph = backward.value
        if (nodeIdx >= backwardGraph.numNodes()) return emptySequence()
        val preds = backwardGraph.successorArray(nodeIdx)
        val indeg = backwardGraph.outdegree(nodeIdx)
        return (0 until indeg).asSequence().map { i ->
            val from = preds[i]
            val label = lookupForwardLabel(from, nodeIdx)
            val comparison = comparisonForEdge(label, from, nodeIdx, nodeDataVersion, comparisonLookup)
            NodeSerializer.decodeEdge(label, NodeId(from), NodeId(nodeIdx), comparison, nodeDataVersion)
        }
    }

    private fun lookupForwardLabel(from: Int, to: Int): Int {
        val succs = forward.successorArray(from)
        val outdeg = forward.outdegree(from)
        val pos = java.util.Arrays.binarySearch(succs, 0, outdeg, to)
        return if (pos >= 0) {
            forwardLabels[cumulativeOutdeg[from] + pos].toInt() and BYTE_MASK
        } else {
            0
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
        metadata.value.supertypes[type.className]?.asSequence() ?: emptySequence()

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        metadata.value.subtypes[type.className]?.asSequence() ?: emptySequence()

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        metadata.value.methods.values.asSequence().filter { pattern.matches(it) }

    override fun methodCount(): Long = methodCount

    override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> =
        DataInputStream(BufferedInputStream(metadataFile.inputStream())).use { dis ->
            NodeSerializer.readMetadataMethodSlice(dis, stringTable, pattern, limit)
        }

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        metadata.value.enumValues["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        metadata.value.memberAnnotations["$className#$memberName"] ?: emptyMap()

    override fun classOrigin(className: String): String? = metadata.value.classOrigins[className]

    override fun classOrigins(): Map<String, String> = metadata.value.classOrigins

    override fun artifactDependencies(): Map<String, Map<String, Int>> = metadata.value.artifactDependencies

    override fun classOverview(limit: Int): ClassOverview? = classOverviewProvider(limit)

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun typeHierarchyTypes(): Set<String> =
        metadata.value.supertypes.keys + metadata.value.subtypes.keys

    override fun close() {
        // MappedByteBuffer is unmapped by GC; no explicit unmap in standard API
    }

    private fun readNodeAt(offset: Long): Node {
        val input = ByteBufferDataInput(mappedNodeData, offset.toInt())
        return NodeSerializer.readNode(input, stringTable, nodeDataVersion)
    }
}

/**
 * Adapts a [ByteBuffer] as [DataInput] for node deserialization.
 * No system calls -- reads directly from mapped memory.
 */
private class ByteBufferDataInput(private val buf: ByteBuffer, private var position: Int) : DataInput {

    override fun readFully(bytes: ByteArray) {
        readFully(bytes, 0, bytes.size)
    }

    override fun readFully(bytes: ByteArray, off: Int, len: Int) {
        requireRemaining(len)
        for (i in 0 until len) {
            bytes[off + i] = buf.get(position + i)
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

    private companion object {
        private const val USHORT_MASK = 0xFFFF
    }
}
