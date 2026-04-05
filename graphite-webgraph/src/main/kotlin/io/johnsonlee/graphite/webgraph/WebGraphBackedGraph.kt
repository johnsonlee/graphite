package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.EmptyResourceAccessor
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
    private val edgeLabelMap: Map<Long, Int>,
    private val comparisonMap: Map<Long, BranchComparison>,
    private val metadata: GraphMetadata
) : Graph {

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
    override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
        nodesById.values.asSequence().filter { type.isInstance(it) } as Sequence<T>

    override fun outgoing(id: NodeId): Sequence<Edge> {
        val nodeIdx = id.value
        if (nodeIdx >= forward.numNodes()) return emptySequence()
        val succs = forward.successorArray(nodeIdx)
        val outdeg = forward.outdegree(nodeIdx)
        return (0 until outdeg).asSequence().map { i ->
            val to = succs[i]
            val key = nodeIdx.toLong() shl 32 or (to.toLong() and 0xFFFFFFFFL)
            val label = edgeLabelMap[key] ?: 0
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
            val label = edgeLabelMap[key] ?: 0
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
}
