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
        val workTracker: CypherWorkTracker? = null,
        val edgePredicate: ((Edge) -> Boolean)? = null
    )

    internal class SearchState(
        val node: Node,
        val incomingEdge: Edge?,
        val parent: SearchState?,
        val depth: Int
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
     * Find paths from source nodes to target nodes with a depth-bounded traversal.
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
            yieldAll(depthFirst(graph, startNode, options))
        }
    }

    /**
     * Traverse one branch at a time so suspended lazy consumers retain at most [SearchOptions.maxDepth]
     * search states instead of a complete breadth-first frontier.
     * The shallowest-depth map grows with expanded node IDs, but does not retain nodes or parent paths;
     * only the stack's O(maxDepth) frames retain those objects.
     *
     * Matches keep the graph's edge order within each branch. Cypher does not define a global result
     * order without ORDER BY, so depth-first traversal is safe for the lazy query pipeline while allowing
     * LIMIT to stop before unrelated siblings are loaded.
     */
    private fun depthFirst(
        graph: Graph,
        startNode: Node,
        options: SearchOptions
    ): Sequence<PathMatch> = sequence {
        val expandedAtDepth = mutableMapOf<Int, Int>()
        val start = SearchState(startNode, incomingEdge = null, parent = null, depth = 0)
        if (matchesTarget(start, options)) yield(PathMatch(start))
        if (options.maxDepth <= 0) return@sequence
        expandedAtDepth[startNode.id.value] = 0

        val stack = ArrayDeque<SearchFrame>()
        stack.addLast(SearchFrame(start, edgesForDirection(graph, startNode.id, options).iterator()))

        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (!frame.edges.hasNext()) {
                stack.removeLast()
                continue
            }

            val edge = frame.edges.next()
            val current = frame.state.node.id
            val nextId = nextNodeId(edge, current, options.direction)
            val nextNode = loadNode(graph, nextId, options.workTracker) ?: continue
            val nextState = SearchState(nextNode, edge, frame.state, frame.state.depth + 1)
            if (matchesTarget(nextState, options)) yield(PathMatch(nextState))

            val previousDepth = expandedAtDepth[nextId.value]
            if (nextState.depth < options.maxDepth &&
                (previousDepth == null || nextState.depth < previousDepth)
            ) {
                expandedAtDepth[nextId.value] = nextState.depth
                stack.addLast(SearchFrame(nextState, edgesForDirection(graph, nextId, options).iterator()))
            }
        }
    }

    private data class SearchFrame(
        val state: SearchState,
        val edges: Iterator<Edge>
    )

    private fun matchesTarget(state: SearchState, options: SearchOptions): Boolean =
        state.depth >= options.minDepth && (options.targets == null || state.node.id in options.targets)

    private fun edgesForDirection(graph: Graph, nodeId: NodeId, options: SearchOptions): Sequence<Edge> =
        when (options.direction) {
            Direction.OUTGOING -> filteredEdges(graph.outgoing(nodeId), options)
            Direction.INCOMING -> filteredEdges(graph.incoming(nodeId), options)
            Direction.BOTH -> filteredEdges(graph.outgoing(nodeId), options) +
                filteredEdges(graph.incoming(nodeId), options)
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
    ): Sequence<Edge> = sequence {
        for (edge in edges) {
            options.workTracker?.consume()
            val typeMatches = options.edgeType?.isInstance(edge) != false
            val predicateMatches = options.edgePredicate?.invoke(edge) != false
            if (typeMatches && predicateMatches) yield(edge)
        }
    }

    enum class Direction { OUTGOING, INCOMING, BOTH }
}
