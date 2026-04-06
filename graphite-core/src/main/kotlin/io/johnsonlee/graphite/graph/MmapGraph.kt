package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * A [Graph] implementation backed by disk files.
 *
 * Node and edge data are read on demand from `nodes.dat` and `edges.dat`
 * via [RandomAccessFile.seek].  Only lightweight indexes (nodeId -> file offset,
 * nodeType -> nodeIds) are held in memory.
 *
 * **Memory profile (5.9M nodes, 6.5M edges):**
 * - Node index: ~47 MB (8 bytes per entry x 5.9M)
 * - Edge indexes: ~104 MB (8 bytes x 6.5M x 2 for outgoing+incoming)
 * - Type index: ~24 MB (4 bytes per nodeId)
 * - Total: ~175 MB vs ~8 GB for [DefaultGraph]
 *
 * Created by [MmapGraphBuilder.build].
 */
class MmapGraph internal constructor(
    private val dataDir: Path,
    private val nodeIndex: Map<Int, Long>,
    private val nodeTypeIndex: Map<Class<out Node>, List<Int>>,
    private val outgoingIndex: Map<Int, List<Long>>,
    private val incomingIndex: Map<Int, List<Long>>,
    private val methodIndex: Map<String, MethodDescriptor>,
    private val typeHierarchy: TypeHierarchy,
    private val enumValuesMap: Map<String, List<Any?>>,
    private val memberAnnotationsMap: Map<String, Map<String, Map<String, Any?>>>,
    private val branchScopeData: List<DefaultGraph.RawBranchScope>,
    override val resources: ResourceAccessor
) : Graph, Closeable {

    // Track all opened RAF handles for close()
    private val openNodeRafs = java.util.Collections.synchronizedList(mutableListOf<RandomAccessFile>())
    private val openEdgeRafs = java.util.Collections.synchronizedList(mutableListOf<RandomAccessFile>())

    private val nodeRafLocal = ThreadLocal.withInitial {
        RandomAccessFile(dataDir.resolve("nodes.dat").toFile(), "r").also { openNodeRafs.add(it) }
    }

    private val edgeRafLocal = ThreadLocal.withInitial {
        RandomAccessFile(dataDir.resolve("edges.dat").toFile(), "r").also { openEdgeRafs.add(it) }
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

    override fun node(id: NodeId): Node? {
        val offset = nodeIndex[id.value] ?: return null
        return readNodeAt(offset)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        // Fast path: exact type match
        nodeTypeIndex[type]?.let { ids ->
            return ids.asSequence().mapNotNull { node(NodeId(it)) as? T }
        }
        // Slow path: supertype match
        return nodeTypeIndex.entries.asSequence()
            .filter { type.isAssignableFrom(it.key) }
            .flatMap { it.value.asSequence() }
            .mapNotNull { node(NodeId(it)) as? T }
    }

    override fun outgoing(id: NodeId): Sequence<Edge> {
        val offsets = outgoingIndex[id.value] ?: return emptySequence()
        return offsets.asSequence().map { readEdgeAt(it) }
    }

    override fun incoming(id: NodeId): Sequence<Edge> {
        val offsets = incomingIndex[id.value] ?: return emptySequence()
        return offsets.asSequence().map { readEdgeAt(it) }
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

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        enumValuesMap["$enumClass#$enumName"]

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        memberAnnotationsMap["$className#$memberName"] ?: emptyMap()

    override fun branchScopes(): Sequence<BranchScope> =
        branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

    override fun typeHierarchyTypes(): Set<String> = typeHierarchy.allKeys()

    override fun close() {
        openNodeRafs.forEach { runCatching { it.close() } }
        openEdgeRafs.forEach { runCatching { it.close() } }
        openNodeRafs.clear()
        openEdgeRafs.clear()
    }

    private fun readNodeAt(offset: Long): Node {
        val raf = nodeRafLocal.get()
        synchronized(raf) {
            raf.seek(offset)
            val len = raf.readInt()
            val bytes = ByteArray(len)
            raf.readFully(bytes)
            return MmapGraphBuilder.deserializeNode(bytes)
        }
    }

    private fun readEdgeAt(offset: Long): Edge {
        val raf = edgeRafLocal.get()
        synchronized(raf) {
            raf.seek(offset)
            return MmapGraphBuilder.deserializeEdge(raf)
        }
    }
}
