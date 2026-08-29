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
        val maxDepth: Int?,
        val direction: Direction,
        val workTracker: CypherWorkTracker? = null,
        val edgeFilter: ((Edge) -> Boolean)? = null
    )

    internal class SearchState(
        val node: Node,
        val incomingEdge: Edge?,
        val parent: SearchState?,
        val depth: Int,
        val usedEdges: Set<Edge>
    )

    internal class PathMatch(private val state: SearchState) {
        fun endNode(): Node = state.node

        fun materialize(workTracker: CypherWorkTracker?): Path {
            workTracker?.consume(state.depth.toLong() * 2 + 1)
            val nodes = ArrayList<Node>(state.depth + 1)
            val edges = ArrayList<Edge>(state.depth)
            var cursor: SearchState? = state
            while (cursor != null) {
                nodes.add(cursor.node)
                cursor.incomingEdge?.let { edge ->
                    edges.add(edge)
                }
                cursor = cursor.parent
            }
            nodes.reverse()
            edges.reverse()
            return Path(nodes, edges)
        }
    }

    /**
     * Find all paths from source nodes to target nodes via BFS.
     *
     * @param graph The graph to search
     * @param sources Source node IDs to start from
     * @param targets Optional target node IDs; if null, any reachable node is a valid endpoint
     * @param edgeType Optional edge type filter; if null, all edges are followed
     * @param minDepth Minimum path length (in edges) to include in results
     * @param maxDepth Optional maximum path length (in edges); `null` explores all relationship-simple trails
     * @param direction Edge traversal direction
     * @return List of paths from sources to targets
     */
    fun findPaths(
        graph: Graph,
        sources: Set<NodeId>,
        targets: Set<NodeId>?,
        edgeType: Class<out Edge>?,
        minDepth: Int = 1,
        maxDepth: Int? = null,
        direction: Direction = Direction.OUTGOING
    ): List<Path> = findPathMatches(
        graph,
        sources,
        SearchOptions(targets, edgeType, minDepth, maxDepth, direction)
    ).map { it.materialize(workTracker = null) }.toList()

    internal fun findPathMatches(
        graph: Graph,
        sources: Set<NodeId>,
        options: SearchOptions
    ): Sequence<PathMatch> = sequence {
        for (source in sources) {
            val startNode = loadNode(graph, source, options.workTracker) ?: continue
            yieldAll(bfs(graph, startNode, options))
        }
    }

    private fun bfs(
        graph: Graph,
        startNode: Node,
        options: SearchOptions
    ): Sequence<PathMatch> = sequence {
        val queue = ArrayDeque<SearchState>()
        queue.add(SearchState(startNode, incomingEdge = null, parent = null, depth = 0, usedEdges = emptySet()))

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            val current = state.node.id

            if (state.depth >= options.minDepth && (options.targets == null || current in options.targets)) {
                yield(PathMatch(state))
            }

            if (options.maxDepth != null && state.depth >= options.maxDepth) continue

            val edges = edgesForDirection(graph, current, options)
            for (edge in edges.filterNot(state.usedEdges::contains)) {
                // Cypher variable-length patterns enumerate relationship-simple trails:
                // nodes may repeat, but the same relationship may not occur twice.
                val nextId = nextNodeId(edge, current, options.direction)
                loadNode(graph, nextId, options.workTracker)?.let { nextNode ->
                    queue.add(
                        SearchState(
                            nextNode,
                            edge,
                            state,
                            state.depth + 1,
                            state.usedEdges + edge
                        )
                    )
                }
            }
        }
    }

    private fun edgesForDirection(graph: Graph, nodeId: NodeId, options: SearchOptions): List<Edge> =
        when (options.direction) {
            Direction.OUTGOING -> filteredEdges(graph.outgoing(nodeId), options)
            Direction.INCOMING -> filteredEdges(graph.incoming(nodeId), options)
            Direction.BOTH -> (
                filteredEdges(graph.outgoing(nodeId), options) +
                    filteredEdges(graph.incoming(nodeId), options)
                ).distinct()
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
        options: SearchOptions
    ): List<Edge> {
        val result = mutableListOf<Edge>()
        for (edge in edges) {
            options.workTracker?.consume()
            val matchesType = options.edgeType == null || options.edgeType.isInstance(edge)
            val matchesFilter = options.edgeFilter == null || options.edgeFilter.invoke(edge)
            if (matchesType && matchesFilter) {
                result.add(edge)
            }
        }
        return result
    }

    enum class Direction { OUTGOING, INCOMING, BOTH }
}
