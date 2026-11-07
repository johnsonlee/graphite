package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.NodeIdCandidateLookup
import io.johnsonlee.graphite.graph.NodePropertyTextCandidates
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import java.util.function.IntPredicate

internal class MappedNodeCapabilities(private val context: MappedGraphContext) :
    NodePropertyTextCandidates, NodeIdCandidateLookup, StringPropertyLookupOrder {
    override val includesDeclaredTypeProperties: Boolean
        get() = context.includesDeclaredTypeProperties

    override fun stringPropertyNodeOrder(node: Node): Long = context.stringPropertyNodeOrder(node)

    override fun <T : Node> nodesMatchingId(type: Class<T>, predicate: IntPredicate, workConsumer: GraphWorkConsumer?): Sequence<T> =
        context.nodesMatchingId(type, predicate, workConsumer)

    override fun <T : Node> propertyTextCandidates(type: Class<T>, fragment: String, workConsumer: GraphWorkConsumer?): Sequence<T>? =
        context.propertyTextCandidates(type, fragment, workConsumer)

    override fun <T : Node> propertyTextCandidates(
        type: Class<T>,
        fragments: List<String>,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T>? = context.propertyTextCandidates(type, fragments, workConsumer)
}
