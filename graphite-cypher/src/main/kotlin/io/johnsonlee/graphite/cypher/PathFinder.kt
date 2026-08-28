package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.Graph

/**
 * Finds paths between nodes in the graph with optional edge type filtering and depth limits.
 */
object PathFinder {

    data class Path(val nodes: List<Node>, val edges: List<Edge>)

    internal data class SearchOptions(
        val targets: Set<NodeId>?,
        val edgeType: Class<out Edge>?,
        val minDepth: Int,
        val maxDepth: Int,
        val direction: Direction,
        val workTracker: CypherWorkTracker? = null
    )

    private data class State(
        val nodeId: NodeId,
        val pathNodes: List<Node>,
        val pathEdges: List<Edge>
    )

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
    ): List<Path> = findPaths(
        graph,
        sources,
        SearchOptions(targets, edgeType, minDepth, maxDepth, direction)
    )

    internal fun findPaths(
        graph: Graph,
        sources: Set<NodeId>,
        options: SearchOptions
    ): List<Path> {
        val results = mutableListOf<Path>()

        for (source in sources) {
            val startNode = loadNode(graph, source, options.workTracker) ?: continue
            bfs(graph, startNode, options, results)
        }

        return results
    }

    private fun bfs(
        graph: Graph,
        startNode: Node,
        options: SearchOptions,
        results: MutableList<Path>
    ) {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<State>()
        queue.add(State(startNode.id, listOf(startNode), emptyList()))

        while (queue.isNotEmpty()) {
            val (current, pathNodes, pathEdges) = queue.removeFirst()
            val depth = pathEdges.size

            if (depth >= options.minDepth && (options.targets == null || current in options.targets)) {
                results.add(Path(pathNodes, pathEdges))
            }

            if (depth >= options.maxDepth) continue
            if (!visited.add(current.value)) continue

            val edges = edgesForDirection(graph, current, options)
            for (edge in edges) {
                val nextId = nextNodeId(edge, current, options.direction)
                val nextNode = loadNode(graph, nextId, options.workTracker) ?: continue
                queue.add(State(nextId, pathNodes + nextNode, pathEdges + edge))
            }
        }
    }

    private fun edgesForDirection(graph: Graph, nodeId: NodeId, options: SearchOptions): List<Edge> =
        when (options.direction) {
            Direction.OUTGOING -> filteredEdges(graph.outgoing(nodeId), options.edgeType, options.workTracker)
            Direction.INCOMING -> filteredEdges(graph.incoming(nodeId), options.edgeType, options.workTracker)
            Direction.BOTH -> filteredEdges(graph.outgoing(nodeId), options.edgeType, options.workTracker) +
                filteredEdges(graph.incoming(nodeId), options.edgeType, options.workTracker)
        }

    private fun nextNodeId(edge: Edge, current: NodeId, direction: Direction): NodeId = when (direction) {
        Direction.OUTGOING -> edge.to
        Direction.INCOMING -> edge.from
        Direction.BOTH -> if (edge.from == current) edge.to else edge.from
    }

    private fun loadNode(graph: Graph, nodeId: NodeId, workTracker: CypherWorkTracker?): Node? {
        workTracker?.consume()
        return graph.node(nodeId)
    }

    private fun filteredEdges(
        edges: Sequence<Edge>,
        edgeType: Class<out Edge>?,
        workTracker: CypherWorkTracker?
    ): List<Edge> {
        val result = mutableListOf<Edge>()
        for (edge in edges) {
            workTracker?.consume()
            if (edgeType == null || edgeType.isInstance(edge)) result.add(edge)
        }
        return result
    }

    enum class Direction { OUTGOING, INCOMING, BOTH }
}
