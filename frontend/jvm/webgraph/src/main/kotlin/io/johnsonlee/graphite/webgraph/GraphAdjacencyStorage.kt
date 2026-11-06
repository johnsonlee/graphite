package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MmapGraph
import it.unimi.dsi.webgraph.ImmutableGraph
import java.nio.file.Path
import java.util.Arrays

/**
 * Build forward adjacency, labels, and comparisons in 2 sequential passes.
 *
 * Pass 1: count unique outdegree per node. Pass 2: fill sorted targets + encode labels + extract comparisons.
 */
internal fun buildForwardGraphData(
    graph: Graph,
    numNodes: Int,
    comparisonMap: MutableMap<Long, BranchComparison>
): Pair<GraphStore.PrecomputedAdjacency, ByteArray> {
    val scratch = ForwardDataScratch()
    val outdeg = IntArray(numNodes)
    for (node in 0 until numNodes) {
        scratch.resetTargets()
        for (edge in graph.outgoing(NodeId(node))) {
            scratch.addTarget(edge.to.value)
        }
        outdeg[node] = scratch.uniqueTargetCount()
    }

    val offsets = IntArray(numNodes + 1)
    for (i in 0 until numNodes) {
        val next = offsets[i].toLong() + outdeg[i].toLong()
        require(next <= Int.MAX_VALUE) { "Graph has too many edges for in-memory adjacency: $next" }
        offsets[i + 1] = next.toInt()
    }
    val totalArcs = offsets[numNodes]
    val targets = IntArray(totalArcs)
    val labels = ByteArray(totalArcs)

    for (node in 0 until numNodes) {
        scratch.resetEdges()
        for (edge in graph.outgoing(NodeId(node))) {
            scratch.addEdge(edge)
        }
        val written = scratch.emitSortedEdges(node, offsets[node], targets, labels, comparisonMap)
        check(written == outdeg[node]) { "Forward adjacency mismatch for node $node: wrote $written edges, expected ${outdeg[node]}" }
    }

    return GraphStore.PrecomputedAdjacency(numNodes, targets, offsets) to labels
}

internal fun buildForwardGraphData(
    graph: MmapGraph,
    numNodes: Int,
    comparisonMap: MutableMap<Long, BranchComparison>
): Pair<GraphStore.PrecomputedAdjacency, ByteArray> {
    val scratch = ForwardDataScratch()
    val outdeg = IntArray(numNodes)
    for (node in 0 until numNodes) {
        scratch.resetTargets()
        graph.forEachOutgoingTarget(node) { target -> scratch.addTarget(target) }
        outdeg[node] = scratch.uniqueTargetCount()
    }

    val offsets = IntArray(numNodes + 1)
    for (i in 0 until numNodes) {
        val next = offsets[i].toLong() + outdeg[i].toLong()
        require(next <= Int.MAX_VALUE) { "Graph has too many edges for in-memory adjacency: $next" }
        offsets[i + 1] = next.toInt()
    }
    val totalArcs = offsets[numNodes]
    val targets = IntArray(totalArcs)
    val labels = ByteArray(totalArcs)

    for (node in 0 until numNodes) {
        scratch.resetEdges()
        graph.forEachOutgoingEdge(node) { edge -> scratch.addEdge(edge) }
        val written = scratch.emitSortedEdges(node, offsets[node], targets, labels, comparisonMap)
        check(written == outdeg[node]) { "Forward adjacency mismatch for node $node: wrote $written edges, expected ${outdeg[node]}" }
    }

    return GraphStore.PrecomputedAdjacency(numNodes, targets, offsets) to labels
}

/** Build backward adjacency from forward BVGraph. */
internal fun loadBackwardGraph(dir: Path, forward: ImmutableGraph): ImmutableGraph =
    loadPersistedBackwardGraph(dir)
        ?: run {
            val backward = buildBackwardGraphFromForward(forward)
            persistAndLoadBackwardGraph(dir, backward) ?: compressAndLoadTransientBackwardGraph(backward) ?: backward
        }

/**
 * Build backward (transpose) adjacency from forward BVGraph. Two passes over the compressed forward graph -- no intermediate
 * collections. Memory: IntArray(totalEdges) + IntArray(numNodes+1) + IntArray(numNodes) work array.
 */
internal fun buildBackwardGraphFromForward(forward: ImmutableGraph): GraphStore.PrecomputedImmutableGraph {
    val numNodes = forward.numNodes()

    // Pass 1: count indegree
    val backwardDeg = IntArray(numNodes)
    for (node in 0 until numNodes) {
        val succs = forward.successorArray(node)
        val outdeg = forward.outdegree(node)
        for (i in 0 until outdeg) {
            backwardDeg[succs[i]]++
        }
    }

    // Build offsets
    val offsets = IntArray(numNodes + 1)
    for (i in 0 until numNodes) {
        val next = offsets[i].toLong() + backwardDeg[i].toLong()
        require(next <= Int.MAX_VALUE) { "Graph has too many edges for in-memory transpose: $next" }
        offsets[i + 1] = next.toInt()
    }

    // Pass 2: fill targets
    val targets = IntArray(offsets[numNodes])
    val fillPos = IntArray(numNodes)
    for (node in 0 until numNodes) {
        val succs = forward.successorArray(node)
        val outdeg = forward.outdegree(node)
        for (i in 0 until outdeg) {
            val to = succs[i]
            targets[offsets[to] + fillPos[to]] = node
            fillPos[to]++
        }
    }

    // Sort each node's predecessors
    for (node in 0 until numNodes) {
        val start = offsets[node]
        val end = offsets[node + 1]
        java.util.Arrays.sort(targets, start, end)
    }

    return GraphStore.PrecomputedImmutableGraph(GraphStore.PrecomputedAdjacency(numNodes, targets, offsets))
}
