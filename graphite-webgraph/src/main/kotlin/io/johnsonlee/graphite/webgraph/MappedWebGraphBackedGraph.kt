package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.webgraph.ImmutableGraph
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
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
 * - BVGraph forward: loaded lazily on first edge traversal
 * - BVGraph backward: built lazily on first incoming query
 * - Edge label map: loaded lazily on first edge traversal
 * - Node index: ~47 MB (heap, nodeId → offset)
 * - Node type index: ~24 MB (heap, type → nodeId list)
 * - StringTable: ~21 MB (heap)
 * - Node data: ~252 MB (mmap, NOT heap — managed by OS page cache)
 * - **Open heap before edge traversal: ~92 MB plus object overhead** vs ~4 GB
 *   for eager [WebGraphBackedGraph]
 *
 * Created by [GraphStore.loadMapped].
 */
internal class MappedWebGraphBackedGraph(
    private val forward: Lazy<ImmutableGraph>,
    private val backward: Lazy<ImmutableGraph>,
    private val mappedNodeData: MappedByteBuffer,
    private val nodeDataVersion: Int,
    private val stringTable: StringTable,
    private val nodeOffsets: LongArray,
    private val nodeTypeIndex: Map<Class<out Node>, IntArray>,
    private val forwardLabels: Lazy<ByteArray>,
    private val cumulativeOutdeg: Lazy<LongArray>,
    private val comparisonMap: Lazy<Map<Long, BranchComparison>>,
    private val metadata: Lazy<GraphMetadata>,
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

    override fun nodeCount(type: Class<out Node>): Long =
        nodeTypeIndex[type]?.size?.toLong()
            ?: nodeTypeIndex.entries.asSequence()
                .filter { type.isAssignableFrom(it.key) }
                .sumOf { it.value.size.toLong() }

    override fun outgoing(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        val forwardGraph = forward.value
        if (nodeIdx >= forwardGraph.numNodes()) return emptySequence()
        val succs = forwardGraph.successorArray(nodeIdx)
        val outdeg = forwardGraph.outdegree(nodeIdx)
        val labels = forwardLabels.value
        val labelStart = cumulativeOutdeg.value[nodeIdx]
        return (0 until outdeg).asSequence().map { i ->
            val to = succs[i]
            val label = labels[(labelStart + i).toInt()].toInt() and BYTE_MASK
            val key = nodeIdx.toLong() shl INT_BITS or (to.toLong() and UNSIGNED_INT_MASK)
            val comparison = comparisonMap.value[key]
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
            val key = from.toLong() shl INT_BITS or (nodeIdx.toLong() and UNSIGNED_INT_MASK)
            val comparison = comparisonMap.value[key]
            NodeSerializer.decodeEdge(label, NodeId(from), NodeId(nodeIdx), comparison, nodeDataVersion)
        }
    }

    private fun lookupForwardLabel(from: Int, to: Int): Int {
        val forwardGraph = forward.value
        val succs = forwardGraph.successorArray(from)
        val outdeg = forwardGraph.outdegree(from)
        val pos = java.util.Arrays.binarySearch(succs, 0, outdeg, to)
        return if (pos >= 0) {
            forwardLabels.value[(cumulativeOutdeg.value[from] + pos).toInt()].toInt() and BYTE_MASK
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

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        metadata.value.enumValues["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        metadata.value.memberAnnotations["$className#$memberName"] ?: emptyMap()

    override fun classOrigin(className: String): String? = metadata.value.classOrigins[className]

    override fun classOrigins(): Map<String, String> = metadata.value.classOrigins

    override fun artifactDependencies(): Map<String, Map<String, Int>> = metadata.value.artifactDependencies

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
        return if (buf.hasRemaining()) buf.get().toInt() and BYTE_MASK else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!buf.hasRemaining()) return -1
        val toRead = minOf(len, buf.remaining())
        buf.get(b, off, toRead)
        return toRead
    }

    override fun available(): Int = buf.remaining()
}
