package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.MethodPattern

internal fun MappedGraphContext.stringPropertyNodeOrder(node: Node): Long = nodeOffsets.offset(node.id.value)

internal fun MappedGraphContext.edgeCount(): Long = edgeCount

internal fun MappedGraphContext.outgoing(id: NodeId): Sequence<Edge> {
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

internal fun MappedGraphContext.incoming(id: NodeId): Sequence<Edge> {
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

internal fun MappedGraphContext.lookupForwardLabel(from: Int, to: Int): Int {
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
internal fun <T : Edge> MappedGraphContext.outgoing(id: NodeId, type: Class<T>): Sequence<T> =
    outgoing(id).filter { type.isInstance(it) } as Sequence<T>

@Suppress("UNCHECKED_CAST")
internal fun <T : Edge> MappedGraphContext.incoming(id: NodeId, type: Class<T>): Sequence<T> =
    incoming(id).filter { type.isInstance(it) } as Sequence<T>

internal fun MappedGraphContext.callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> =
    nodes(CallSiteNode::class.java).filter { methodPattern.matches(it.callee) }
