package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.ClassDependency
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.Graph

internal fun ExploreRoutes.buildSubgraph(graph: Graph, center: NodeId, depth: Int): Map<String, Any> =
    buildSubgraph(graph, center, depth, SubgraphDirection.BOTH)

/**
 * Depth-first from [center], outgoing edges before incoming ones, each edge fully explored before the next. The walk keeps its own stack of
 * edge iterators instead of recursing, so a long chain (the node cap alone allows 2,000 hops) cannot overflow the thread stack.
 */
internal fun ExploreRoutes.buildSubgraph(graph: Graph, center: NodeId, depth: Int, direction: SubgraphDirection): Map<String, Any> {
    val visitedNodes = mutableSetOf<Int>()
    val nodes = mutableListOf<Map<String, Any?>>()
    val edges = mutableListOf<Map<String, Any?>>()
    val pending = ArrayDeque<SubgraphFrame>()

    fun enter(nodeId: NodeId, remaining: Int) {
        val admitted = nodes.size < MAX_SUBGRAPH_NODES && visitedNodes.add(nodeId.value) && remaining >= 0
        val node = if (admitted) graph.node(nodeId) else null
        if (node != null) {
            nodes.add(nodeToMap(node, graph))
            // Pushed in reverse so outgoing edges are walked first.
            if (remaining > 0 && direction.includeIncoming) {
                pending.addLast(SubgraphFrame(graph.incoming(nodeId).iterator(), Edge::from, remaining - 1))
            }
            if (remaining > 0 && direction.includeOutgoing) {
                pending.addLast(SubgraphFrame(graph.outgoing(nodeId).iterator(), Edge::to, remaining - 1))
            }
        }
    }

    enter(center, depth)
    while (pending.isNotEmpty()) {
        val frame = pending.last()
        if (!frame.candidates.hasNext() || edges.size >= MAX_SUBGRAPH_EDGES) {
            pending.removeLast()
            continue
        }
        val edge = frame.candidates.next()
        edges.add(edgeToMap(edge))
        enter(frame.nextNode(edge), frame.remaining)
    }
    return mapOf(API_FIELD_NODES to nodes, API_FIELD_EDGES to edges)
}

internal fun ExploreRoutes.buildClassOverview(graph: Graph, limit: Int): Map<String, List<Map<String, Any?>>> =
    runCatching { graph.classOverview(limit) }.getOrNull()?.let { buildClassOverview(it, limit) }
        ?: buildClassOverviewFromCallSites(graph, limit)

internal fun ExploreRoutes.buildClassOverview(overview: ClassOverview, limit: Int): Map<String, List<Map<String, Any?>>> =
    buildClassOverview(overview.classCounts, overview.classEdges, limit)

internal fun ExploreRoutes.buildClassOverview(
    classCounts: Map<String, Int>,
    classEdges: Map<ClassDependency, Int>,
    limit: Int
): Map<String, List<Map<String, Any?>>> {
    val topClasses = classCounts.entries.sortedByDescending { it.value }.take(limit).map { it.key }.toSet()

    val nodes =
        topClasses.map { cls ->
            val shortName = cls.substringAfterLast('.')
            mapOf(
                "id" to cls,
                API_FIELD_TYPE to "Class",
                API_FIELD_LABEL to shortName,
                "fullName" to cls,
                API_FIELD_CALL_SITES to (classCounts[cls] ?: 0))
        }

    val edges =
        classEdges.entries
            .filter { it.key.callerClass in topClasses && it.key.calleeClass in topClasses }
            .map { (key, count) -> mapOf("from" to key.callerClass, "to" to key.calleeClass, API_FIELD_TYPE to "Call", "weight" to count) }

    return mapOf(API_FIELD_NODES to nodes, API_FIELD_EDGES to edges)
}

internal fun ExploreRoutes.buildClassOverviewFromCallSites(graph: Graph, limit: Int): Map<String, List<Map<String, Any?>>> {
    val classEdges = mutableMapOf<ClassDependency, Int>()
    val classCounts = mutableMapOf<String, Int>()

    var scanned = 0
    for (cs in graph.nodes(CallSiteNode::class.java)) {
        if (scanned >= MAX_OVERVIEW_CALL_SITES) break
        scanned++
        val callerClass = cs.caller.declaringClass.className
        val calleeClass = cs.callee.declaringClass.className
        if (callerClass != calleeClass) {
            incrementBounded(classEdges, ClassDependency(callerClass, calleeClass), MAX_OVERVIEW_EDGES)
        }
        incrementBounded(classCounts, callerClass, MAX_OVERVIEW_CLASSES)
        incrementBounded(classCounts, calleeClass, MAX_OVERVIEW_CLASSES)
    }

    return buildClassOverview(classCounts, classEdges, limit)
}

internal fun <K> ExploreRoutes.incrementBounded(counts: MutableMap<K, Int>, key: K, maxKeys: Int) {
    if (key in counts || counts.size < maxKeys) {
        counts[key] = (counts[key] ?: 0) + 1
    }
}
