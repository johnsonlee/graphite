package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.webgraph.ImmutableGraph
import java.io.*
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
 * Unlike [LazyWebGraphBackedGraph] which uses [RandomAccessFile.seek] (one
 * system call per node access), this uses [MappedByteBuffer] which translates
 * to direct memory reads — no system calls after the initial page fault.
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - BVGraph forward + backward: ~30 MB (heap)
 * - Edge label map: ~364 MB (heap)
 * - Node index: ~47 MB (heap, nodeId → offset)
 * - Node type index: ~24 MB (heap, type → nodeId list)
 * - StringTable: ~21 MB (heap)
 * - Node data: ~252 MB (mmap, NOT heap — managed by OS page cache)
 * - **JVM Heap total: ~486 MB** vs ~4 GB for eager [WebGraphBackedGraph]
 *
 * Created by [GraphStore.loadMapped].
 */
internal class MappedWebGraphBackedGraph(
    private val forward: ImmutableGraph,
    private val backward: ImmutableGraph,
    private val mappedNodeData: MappedByteBuffer,
    private val nodeDataVersion: Int,
    private val stringTable: StringTable,
    private val nodeOffsets: LongArray,
    private val nodeTypeIndex: Map<Class<out Node>, List<Int>>,
    private val forwardLabels: ByteArray,
    private val cumulativeOutdeg: LongArray,
    private val comparisonMap: Map<Long, BranchComparison>,
    private val metadata: GraphMetadata,
    override val resources: ResourceAccessor
) : Graph, Closeable {

    private val branchScopeIndex: Map<Int, List<BranchScope>> by lazy {
        metadata.branchScopes.map { raw ->
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
        val offset = nodeOffsets[nodeId]
        if (offset == -1L) return null
        return readNodeAt(offset)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        // Fast path: exact type
        nodeTypeIndex[type]?.let { ids ->
            return ids.asSequence().mapNotNull { node(NodeId(it)) as? T }
        }
        // Slow path: supertype
        return nodeTypeIndex.entries.asSequence()
            .filter { type.isAssignableFrom(it.key) }
            .flatMap { it.value.asSequence() }
            .mapNotNull { node(NodeId(it)) as? T }
    }

    override fun outgoing(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        if (nodeIdx >= forward.numNodes()) return emptySequence()
        val succs = forward.successorArray(nodeIdx)
        val outdeg = forward.outdegree(nodeIdx)
        val labelStart = cumulativeOutdeg[nodeIdx]
        return (0 until outdeg).asSequence().map { i ->
            val to = succs[i]
            val label = forwardLabels[(labelStart + i).toInt()].toInt() and 0xFF
            val key = nodeIdx.toLong() shl 32 or (to.toLong() and 0xFFFFFFFFL)
            val comparison = comparisonMap[key]
            NodeSerializer.decodeEdge(label, NodeId(nodeIdx), NodeId(to), comparison, nodeDataVersion)
        }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        if (nodeIdx >= backward.numNodes()) return emptySequence()
        val preds = backward.successorArray(nodeIdx)
        val indeg = backward.outdegree(nodeIdx)
        return (0 until indeg).asSequence().map { i ->
            val from = preds[i]
            val label = lookupForwardLabel(from, nodeIdx)
            val key = from.toLong() shl 32 or (nodeIdx.toLong() and 0xFFFFFFFFL)
            val comparison = comparisonMap[key]
            NodeSerializer.decodeEdge(label, NodeId(from), NodeId(nodeIdx), comparison, nodeDataVersion)
        }
    }

    private fun lookupForwardLabel(from: Int, to: Int): Int {
        val succs = forward.successorArray(from)
        val outdeg = forward.outdegree(from)
        val pos = java.util.Arrays.binarySearch(succs, 0, outdeg, to)
        return if (pos >= 0) forwardLabels[(cumulativeOutdeg[from] + pos).toInt()].toInt() and 0xFF else 0
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
        metadata.supertypes[type.className]?.asSequence() ?: emptySequence()

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        metadata.subtypes[type.className]?.asSequence() ?: emptySequence()

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        metadata.methods.values.asSequence().filter { pattern.matches(it) }

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        metadata.enumValues["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        metadata.memberAnnotations["$className#$memberName"] ?: emptyMap()

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun typeHierarchyTypes(): Set<String> =
        metadata.supertypes.keys + metadata.subtypes.keys

    override fun close() {
        // MappedByteBuffer is unmapped by GC; no explicit unmap in standard API
    }

    private fun readNodeAt(offset: Long): Node {
        // Create a duplicate to avoid position conflicts across threads
        val buf = mappedNodeData.duplicate()
        buf.position(offset.toInt())
        val dis = DataInputStream(ByteBufferInputStream(buf))
        return NodeSerializer.readNode(dis, stringTable, nodeDataVersion)
    }
}

/**
 * Adapts a [ByteBuffer] as an [InputStream] for use with [DataInputStream].
 * No system calls — reads directly from mapped memory.
 */
private class ByteBufferInputStream(private val buf: ByteBuffer) : InputStream() {
    override fun read(): Int {
        return if (buf.hasRemaining()) buf.get().toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!buf.hasRemaining()) return -1
        val toRead = minOf(len, buf.remaining())
        buf.get(b, off, toRead)
        return toRead
    }

    override fun available(): Int = buf.remaining()
}
