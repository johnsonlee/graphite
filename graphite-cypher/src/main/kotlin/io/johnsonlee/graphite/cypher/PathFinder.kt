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
        val edgePredicate: ((Edge) -> Boolean)? = null,
        val nodePredicate: ((Node) -> Boolean)? = null
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
     * Find all paths from source nodes to target nodes via breadth-first search.
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
        val options = SearchOptions(targets, edgeType, minDepth, maxDepth, direction)
        return sources.asSequence().flatMap { source ->
            val startNode = loadNode(graph, source, workTracker = null)
            if (startNode == null) emptySequence() else breadthFirst(graph, startNode, options)
        }.map { it.materialize(workTracker = null) }.toList()
    }

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

    /** Preserve the public [findPaths] shortest-first contract. */
    private fun breadthFirst(
        graph: Graph,
        startNode: Node,
        options: SearchOptions
    ): Sequence<PathMatch> = sequence {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<SearchState>()
        queue.add(SearchState(startNode, incomingEdge = null, parent = null, depth = 0))

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            val current = state.node.id

            if (state.depth == 0 && matchesTarget(state, options)) yield(PathMatch(state))
            if (state.depth >= options.maxDepth) continue
            if (!visited.add(current.value)) continue

            for (edge in edgesForDirection(graph, current, options)) {
                val nextId = nextNodeId(edge, current, options.direction)
                val nextNode = loadNode(graph, nextId, options.workTracker) ?: continue
                val nextState = SearchState(nextNode, edge, state, state.depth + 1)
                if (matchesTarget(nextState, options)) yield(PathMatch(nextState))
                queue.add(nextState)
            }
        }
    }

    /**
     * Traverse one branch at a time so suspended lazy consumers retain at most [SearchOptions.maxDepth]
     * search states instead of a complete breadth-first frontier.
     * Nodes already present on the current branch are not expanded again, so cycles terminate without
     * retaining a traversal-wide visited set. A fixed-size memo suppresses only suffixes that completed
     * without producing a match or encountering an ancestor back-edge. Result-producing suffixes are
     * always replayed for every prefix, preserving Cypher row multiplicity; cycle-dependent suffixes are
     * never memoized, preserving paths that become legal under a different ancestor set. Eviction can only
     * cause safe re-expansion. Only the stack's O(maxDepth) frames retain nodes and parent paths.
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
        val deadSuffixMemo = LinkedHashMap<ExpansionKey, Unit>()
        val start = SearchState(startNode, incomingEdge = null, parent = null, depth = 0)
        if (matchesTarget(start, options)) yield(PathMatch(start))
        if (options.maxDepth <= 0) return@sequence
        if (options.maxDepth == 1) {
            yieldAll(singleHop(graph, start, options))
            return@sequence
        }

        val stack = ArrayDeque<SearchFrame>()
        val startEdges = edgesForDirection(graph, startNode.id, options).iterator()
        if (!startEdges.hasNext()) return@sequence
        stack.addLast(SearchFrame(start, startEdges))

        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (!frame.edges.hasNext()) {
                val completed = stack.removeLast()
                if (!completed.foundMatch && !completed.cycleBlocked) {
                    rememberBounded(
                        deadSuffixMemo,
                        ExpansionKey(completed.state.node.id.value, completed.state.depth),
                        Unit
                    )
                }
                stack.lastOrNull()?.let { parent ->
                    parent.foundMatch = parent.foundMatch || completed.foundMatch
                    parent.cycleBlocked = parent.cycleBlocked || completed.cycleBlocked
                }
                continue
            }

            val edge = frame.edges.next()
            val current = frame.state.node.id
            val nextId = nextNodeId(edge, current, options.direction)
            val nextNode = loadNode(graph, nextId, options.workTracker) ?: continue
            val nextState = SearchState(nextNode, edge, frame.state, frame.state.depth + 1)
            if (matchesTarget(nextState, options)) {
                frame.foundMatch = true
                yield(PathMatch(nextState))
            }

            if (nextState.depth < options.maxDepth) {
                expandableFrame(
                    graph,
                    nextId,
                    nextState,
                    options,
                    deadSuffixMemo,
                    frame
                )?.let(stack::addLast)
            }
        }
    }

    private fun singleHop(
        graph: Graph,
        start: SearchState,
        options: SearchOptions
    ): Sequence<PathMatch> = sequence {
        for (edge in edgesForDirection(graph, start.node.id, options)) {
            val nextId = nextNodeId(edge, start.node.id, options.direction)
            val nextNode = loadNode(graph, nextId, options.workTracker) ?: continue
            val nextState = SearchState(nextNode, edge, start, depth = 1)
            if (matchesTarget(nextState, options)) yield(PathMatch(nextState))
        }
    }

    @Suppress("ReturnCount")
    private fun expandableFrame(
        graph: Graph,
        nodeId: NodeId,
        state: SearchState,
        options: SearchOptions,
        deadSuffixMemo: LinkedHashMap<ExpansionKey, Unit>,
        parentFrame: SearchFrame
    ): SearchFrame? {
        if (hasAncestor(state.parent, nodeId)) {
            parentFrame.cycleBlocked = true
            return null
        }

        if (ExpansionKey(nodeId.value, state.depth) in deadSuffixMemo) return null
        val edges = edgesForDirection(graph, nodeId, options).iterator()
        if (!edges.hasNext()) return null
        return SearchFrame(state, edges)
    }

    private fun <K, V> rememberBounded(memo: LinkedHashMap<K, V>, key: K, value: V) {
        if (!memo.containsKey(key) && memo.size >= DEAD_SUFFIX_MEMO_CAPACITY) {
            val eldest = memo.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        memo[key] = value
    }

    private fun hasAncestor(state: SearchState?, nodeId: NodeId): Boolean {
        var cursor = state
        while (cursor != null) {
            if (cursor.node.id == nodeId) return true
            cursor = cursor.parent
        }
        return false
    }

    private data class SearchFrame(
        val state: SearchState,
        val edges: Iterator<Edge>,
        var foundMatch: Boolean = false,
        var cycleBlocked: Boolean = false
    )

    private data class ExpansionKey(
        val nodeId: Int,
        val depth: Int
    )

    private fun matchesTarget(state: SearchState, options: SearchOptions): Boolean =
        state.depth >= options.minDepth &&
            (options.targets == null || state.node.id in options.targets) &&
            (options.nodePredicate?.invoke(state.node) != false)

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

    private const val DEAD_SUFFIX_MEMO_CAPACITY = 16_384
}
