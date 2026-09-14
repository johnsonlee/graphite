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
                cursor.incomingEdge?.let(edges::add)
                cursor = cursor.parent
            }
            nodes.reverse()
            edges.reverse()
            return Path(nodes, edges)
        }
    }

    /**
     * Find all relationship-simple paths from source nodes to target nodes in shortest-first order.
     *
     * @param graph The graph to search
     * @param sources Source node IDs to start from
     * @param targets Optional target node IDs; if null, any reachable node is a valid endpoint
     * @param edgeType Optional edge type filter; if null, all edges are followed
     * @param minDepth Minimum path length (in edges) to include in results
     * @param maxDepth Optional maximum path length (in edges); `null` explores every relationship-simple trail
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
        val queue = ArrayDeque<SearchState>()
        queue.add(SearchState(startNode, incomingEdge = null, parent = null, depth = 0))

        while (queue.isNotEmpty()) {
            val state = queue.removeFirst()
            if (matchesTarget(state, options)) yield(PathMatch(state))
            if (canExpand(state.depth, options.maxDepth)) {
                for (edge in edgesForDirection(graph, state.node.id, options)) {
                    if (!hasAncestorEdge(state, edge)) {
                        val nextId = nextNodeId(edge, state.node.id, options.direction)
                        loadNode(graph, nextId, options.workTracker)?.let { nextNode ->
                            queue.add(SearchState(nextNode, edge, state, state.depth + 1))
                        }
                    }
                }
            }
        }
    }

    /**
     * Traverse one branch at a time so a suspended lazy consumer retains only the active branch,
     * not a complete breadth-first frontier. Nodes may repeat, but an edge already present in the
     * branch is never reused, matching Cypher relationship-simple trail semantics. With no explicit
     * maximum depth, the finite relationship set still guarantees termination.
     *
     * A bounded memo suppresses only suffixes that completed without producing a match or touching
     * an edge from the prefix. Result-producing or prefix-dependent suffixes are always replayed.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun depthFirst(
        graph: Graph,
        startNode: Node,
        options: SearchOptions
    ): Sequence<PathMatch> = sequence {
        val deadSuffixMemo = LinkedHashMap<ExpansionKey, Unit>()
        val start = SearchState(startNode, incomingEdge = null, parent = null, depth = 0)
        if (matchesTarget(start, options)) yield(PathMatch(start))
        if (options.maxDepth != null && options.maxDepth <= 0) return@sequence
        if (options.maxDepth == 1) {
            yieldAll(singleHop(graph, start, options))
            return@sequence
        }

        val startEdges = edgesForDirection(graph, startNode.id, options).iterator()
        if (!startEdges.hasNext()) return@sequence
        val stack = ArrayDeque<SearchFrame>()
        stack.addLast(SearchFrame(start, startEdges))

        while (stack.isNotEmpty()) {
            val frame = stack.last()
            if (!frame.edges.hasNext()) {
                val completed = stack.removeLast()
                if (!completed.foundMatch && !completed.prefixEdgeBlocked) {
                    rememberBounded(
                        deadSuffixMemo,
                        ExpansionKey(completed.state.node.id.value, completed.state.depth),
                        Unit
                    )
                }
                stack.lastOrNull()?.let { parent ->
                    parent.foundMatch = parent.foundMatch || completed.foundMatch
                    parent.prefixEdgeBlocked = parent.prefixEdgeBlocked || completed.prefixEdgeBlocked
                }
                continue
            }

            val edge = frame.edges.next()
            if (hasAncestorEdge(frame.state, edge)) {
                frame.prefixEdgeBlocked = true
                continue
            }
            val nextId = nextNodeId(edge, frame.state.node.id, options.direction)
            val nextNode = loadNode(graph, nextId, options.workTracker) ?: continue
            val nextState = SearchState(nextNode, edge, frame.state, frame.state.depth + 1)
            if (matchesTarget(nextState, options)) {
                frame.foundMatch = true
                yield(PathMatch(nextState))
            }

            if (canExpand(nextState.depth, options.maxDepth)) {
                expandableFrame(graph, nextId, nextState, options, deadSuffixMemo)?.let(stack::addLast)
            }
        }
    }

    private fun canExpand(depth: Int, maxDepth: Int?): Boolean = maxDepth == null || depth < maxDepth

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
        deadSuffixMemo: LinkedHashMap<ExpansionKey, Unit>
    ): SearchFrame? {
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

    private fun hasAncestorEdge(state: SearchState?, edge: Edge): Boolean {
        var cursor = state
        while (cursor != null) {
            if (cursor.incomingEdge == edge) return true
            cursor = cursor.parent
        }
        return false
    }

    private data class SearchFrame(
        val state: SearchState,
        val edges: Iterator<Edge>,
        var foundMatch: Boolean = false,
        var prefixEdgeBlocked: Boolean = false
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
                    .filterNot { it.from == nodeId && it.to == nodeId }
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
