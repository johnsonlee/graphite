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
 * Forward and backward edges are stored as separate BVGraph files for efficient
 * successor and predecessor queries. Node data and metadata are loaded into memory.
 */
internal class WebGraphBackedGraph(
    private val forward: ImmutableGraph,
    private val backward: ImmutableGraph,
    private val nodesById: Map<Int, Node>,
    private val allEdges: List<Edge>,
    private val metadata: GraphMetadata
) : Graph {

    // Index: nodeId -> outgoing edges
    private val outgoingIndex: Map<Int, List<Edge>> by lazy {
        allEdges.groupBy { it.from.value }
    }

    // Index: nodeId -> incoming edges
    private val incomingIndex: Map<Int, List<Edge>> by lazy {
        allEdges.groupBy { it.to.value }
    }

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

    override fun outgoing(id: NodeId): Sequence<Edge> =
        outgoingIndex[id.value]?.asSequence() ?: emptySequence()

    override fun incoming(id: NodeId): Sequence<Edge> =
        incomingIndex[id.value]?.asSequence() ?: emptySequence()

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
