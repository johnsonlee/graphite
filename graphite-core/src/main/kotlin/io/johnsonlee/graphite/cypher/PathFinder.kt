package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph

/**
 * Finds paths between nodes in the graph with optional edge type filtering and depth limits.
 */
object PathFinder {

    data class Path(val nodes: List<Node>, val edges: List<Edge>)

    /**
     * Find all paths from source nodes to target nodes via BFS.
     *
     * @param graph The graph to search
     * @param sources Source node IDs to start from
     * @param targets Optional target node IDs; if null, any reachable node is a valid endpoint
     * @param edgeType Optional edge type filter; if null, all edges are followed
     * @param minDepth Minimum path length (in edges) to include in results
     * @param maxDepth Maximum path length (in edges) to explore
     * @param direction Edge traversal direction
     * @return List of paths from sources to targets
     */
    fun findPaths(
        graph: Graph,
        sources: Set<NodeId>,
        targets: Set<NodeId>?,
        edgeType: Class<out Edge>?,
        minDepth: Int = 1,
        maxDepth: Int = 10,
        direction: Direction = Direction.OUTGOING
    ): List<Path> {
        val results = mutableListOf<Path>()

        for (source in sources) {
            val startNode = graph.node(source) ?: continue
            bfs(graph, startNode, targets, edgeType, minDepth, maxDepth, direction, results)
        }

        return results
    }

    private fun bfs(
        graph: Graph,
        startNode: Node,
        targets: Set<NodeId>?,
        edgeType: Class<out Edge>?,
        minDepth: Int,
        maxDepth: Int,
        direction: Direction,
        results: MutableList<Path>
    ) {
        data class State(val nodeId: NodeId, val pathNodes: List<Node>, val pathEdges: List<Edge>)

        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<State>()
        queue.add(State(startNode.id, listOf(startNode), emptyList()))

        while (queue.isNotEmpty()) {
            val (current, pathNodes, pathEdges) = queue.removeFirst()
            val depth = pathEdges.size

            if (depth >= minDepth && (targets == null || current in targets)) {
                results.add(Path(pathNodes, pathEdges))
            }

            if (depth >= maxDepth) continue
            if (!visited.add(current.value)) continue

            val edges = when (direction) {
                Direction.OUTGOING -> filteredEdges(graph.outgoing(current), edgeType)
                Direction.INCOMING -> filteredEdges(graph.incoming(current), edgeType)
                Direction.BOTH -> filteredEdges(graph.outgoing(current), edgeType) +
                        filteredEdges(graph.incoming(current), edgeType)
            }

            for (edge in edges) {
                val nextId = when (direction) {
                    Direction.OUTGOING -> edge.to
                    Direction.INCOMING -> edge.from
                    Direction.BOTH -> if (edge.from == current) edge.to else edge.from
                }
                val nextNode = graph.node(nextId) ?: continue
                queue.add(State(nextId, pathNodes + nextNode, pathEdges + edge))
            }
        }
    }

    private fun filteredEdges(edges: Sequence<Edge>, edgeType: Class<out Edge>?): List<Edge> =
        if (edgeType != null) edges.filter { edgeType.isInstance(it) }.toList()
        else edges.toList()

    enum class Direction { OUTGOING, INCOMING, BOTH }
}
