package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.webgraph.ImmutableGraph
import java.io.*

/**
 * A [Graph] backed by WebGraph compression for edges and lazy disk reads for nodes.
 *
 * BVGraph adjacency (compressed, ~30 MB for 5.9M nodes) and edge label maps
 * stay in memory for fast edge traversal.  Node data is read on demand from
 * `graph.nodedata` via [RandomAccessFile.seek], using a pre-built index
 * (nodeId -> file offset).
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - BVGraph forward + backward: ~30 MB
 * - Edge label map: ~364 MB
 * - Node index: ~47 MB (nodeId -> offset)
 * - Node type index: ~24 MB (type -> nodeId list)
 * - StringTable: ~21 MB
 * - **Total: ~486 MB** vs ~4 GB for eager [WebGraphBackedGraph]
 *
 * Created by [GraphStore.loadLazy].
 */
internal class LazyWebGraphBackedGraph(
    private val forward: ImmutableGraph,
    private val backward: ImmutableGraph,
    private val nodeDataFile: File,
    private val stringTable: StringTable,
    private val nodeIndex: Int2LongOpenHashMap,
    private val nodeTypeIndex: Map<Class<out Node>, List<Int>>,
    private val edgeLabelMap: Long2IntOpenHashMap,
    private val comparisonMap: Map<Long, BranchComparison>,
    private val metadata: GraphMetadata
) : Graph, Closeable {

    private val openRafs = java.util.Collections.synchronizedList(mutableListOf<RandomAccessFile>())

    private val rafLocal = ThreadLocal.withInitial {
        RandomAccessFile(nodeDataFile, "r").also { openRafs.add(it) }
    }

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
        if (!nodeIndex.containsKey(id.value)) return null
        val offset = nodeIndex.get(id.value)
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
        return (0 until outdeg).asSequence().map { i ->
            val to = succs[i]
            val key = nodeIdx.toLong() shl 32 or (to.toLong() and 0xFFFFFFFFL)
            val label = edgeLabelMap.get(key)
            val comparison = comparisonMap[key]
            NodeSerializer.decodeEdge(label, NodeId(nodeIdx), NodeId(to), comparison)
        }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        if (nodeIdx >= backward.numNodes()) return emptySequence()
        val preds = backward.successorArray(nodeIdx)
        val indeg = backward.outdegree(nodeIdx)
        return (0 until indeg).asSequence().map { i ->
            val from = preds[i]
            val key = from.toLong() shl 32 or (nodeIdx.toLong() and 0xFFFFFFFFL)
            val label = edgeLabelMap.get(key)
            val comparison = comparisonMap[key]
            NodeSerializer.decodeEdge(label, NodeId(from), NodeId(nodeIdx), comparison)
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
        metadata.supertypes[type.className]?.asSequence() ?: emptySequence()

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
        metadata.subtypes[type.className]?.asSequence() ?: emptySequence()

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
        metadata.methods.values.asSequence().filter { pattern.matches(it) }

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        metadata.enumValues["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        metadata.memberAnnotations["$className#$memberName"] ?: emptyMap()

    override val resources: ResourceAccessor = EmptyResourceAccessor

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun typeHierarchyTypes(): Set<String> =
        metadata.supertypes.keys + metadata.subtypes.keys

    override fun close() {
        openRafs.forEach { runCatching { it.close() } }
        openRafs.clear()
    }

    private fun readNodeAt(offset: Long): Node {
        val raf = rafLocal.get()
        synchronized(raf) {
            raf.seek(offset)
            val dis = DataInputStream(object : InputStream() {
                override fun read(): Int = raf.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
            })
            return NodeSerializer.readNode(dis, stringTable)
        }
    }
}
