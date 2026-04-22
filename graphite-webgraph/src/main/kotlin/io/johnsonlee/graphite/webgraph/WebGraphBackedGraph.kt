package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.ResourceAccessor
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.webgraph.ImmutableGraph

/**
 * A [Graph] implementation backed by WebGraph's compressed adjacency structure.
 *
 * Edges are reconstructed on-the-fly from the BVGraph successor lists and
 * a compact label map (8 bits per edge) instead of storing full [Edge] objects.
 * [ControlFlowEdge.comparison] data is stored in a separate map keyed by
 * `(from << 32 | to)`.
 */
internal class WebGraphBackedGraph(
    private val forward: ImmutableGraph,
    private val backward: ImmutableGraph,
    private val nodesById: Map<Int, Node>,
    private val nodeDataVersion: Int,
    private val forwardLabels: ByteArray,
    private val cumulativeOutdeg: LongArray,
    private val comparisonMap: Map<Long, BranchComparison>,
    private val metadata: GraphMetadata,
    override val resources: ResourceAccessor
) : Graph {

    /** Pre-computed index: concrete node class -> list of nodes of that class. */
    private val nodesByType: Map<Class<out Node>, List<Node>> = nodesById.values.groupBy { it::class.java }

    // Lazy branch scope materialization
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

    override fun node(id: NodeId): Node? = nodesById[id.value]

    @Suppress("UNCHECKED_CAST")
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
        // Fast path: exact type match
        nodesByType[type]?.let { return (it as List<T>).asSequence() }
        // Slow path: supertype match
        return nodesByType.entries.asSequence()
            .filter { type.isAssignableFrom(it.key) }
            .flatMap { it.value.asSequence() } as Sequence<T>
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
}
