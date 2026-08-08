package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.ClassDependency
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern

internal data class GraphOverviewInput(
    val id: String,
    val graph: Graph,
    val stats: GraphStats
)

private data class GraphRelation(
    val callerGraph: String,
    val calleeGraph: String
)

private data class DeclaredClassesResult(val classes: Set<String>, val truncated: Boolean)

internal fun buildGraphOverview(inputs: List<GraphOverviewInput>): Map<String, Any?> {
    val summaries = inputs.associate { input -> input.id to loadClassOverview(input.graph) }
    val classOwners = mutableMapOf<String, MutableSet<String>>()
    var ownershipTruncated = false
    inputs.forEach { input ->
        val declared = loadDeclaredClasses(input.graph, summaries.getValue(input.id))
        ownershipTruncated = ownershipTruncated || declared.truncated
        declared.classes.forEach { className ->
            classOwners.getOrPut(className, ::linkedSetOf).add(input.id)
        }
    }

    val relationWeights = mutableMapOf<GraphRelation, Int>()
    summaries.forEach { (sourceGraph, overview) ->
        overview.classEdges.forEach { (dependency, weight) ->
            val owners = classOwners[dependency.calleeClass].orEmpty()
            if (sourceGraph !in owners && owners.size == 1) {
                val targetGraph = owners.single()
                val relation = GraphRelation(sourceGraph, targetGraph)
                relationWeights[relation] = (relationWeights[relation] ?: 0) + weight
            }
        }
    }

    val nodes = inputs.map { input ->
        mapOf(
            API_FIELD_ID to input.id,
            API_FIELD_GRAPH_ID to input.id,
            API_FIELD_TYPE to GRAPH_NODE_TYPE,
            API_FIELD_LABEL to input.id
        ) + input.stats.toApiMap()
    }
    val edges = relationWeights.entries
        .sortedWith(compareBy({ it.key.callerGraph }, { it.key.calleeGraph }))
        .map { (relation, weight) ->
            mapOf(
                "from" to relation.callerGraph,
                "to" to relation.calleeGraph,
                API_FIELD_TYPE to GRAPH_CALL_EDGE_TYPE,
                "weight" to weight
            )
        }

    return mapOf(
        "graphCount" to inputs.size,
        API_FIELD_NODES to nodes,
        API_FIELD_EDGES to edges,
        "relationCount" to edges.size,
        "crossGraphCallSites" to relationWeights.values.sum(),
        "truncated" to ownershipTruncated
    )
}

private fun loadDeclaredClasses(graph: Graph, overview: ClassOverview): DeclaredClassesResult {
    graph.declaredClasses()?.let { return DeclaredClassesResult(it, false) }
    val classes = linkedSetOf<String>()
    overview.classEdges.keys.forEach { classes.add(it.callerClass) }
    val methods = graph.methodSlice(MethodPattern(), MAX_GRAPH_OVERVIEW_METHODS + 1)
        ?: graph.methods(MethodPattern()).take(MAX_GRAPH_OVERVIEW_METHODS + 1).toList()
    methods.asSequence()
        .take(MAX_GRAPH_OVERVIEW_METHODS)
        .mapTo(classes) { it.declaringClass.className }
    return DeclaredClassesResult(
        classes,
        graph.methodCount()?.let { it > MAX_GRAPH_OVERVIEW_METHODS } ?: (methods.size > MAX_GRAPH_OVERVIEW_METHODS)
    )
}

private fun loadClassOverview(graph: Graph): ClassOverview =
    runCatching { graph.classOverview(MAX_GRAPH_OVERVIEW_CLASSES) }.getOrNull()
        ?: buildClassOverviewFromCallSites(graph)

private fun buildClassOverviewFromCallSites(graph: Graph): ClassOverview {
    val classEdges = mutableMapOf<ClassDependency, Int>()
    val classCounts = mutableMapOf<String, Int>()
    var scanned = 0
    for (callSite in graph.nodes(CallSiteNode::class.java)) {
        if (scanned >= MAX_GRAPH_OVERVIEW_CALL_SITES) break
        scanned++
        val callerClass = callSite.caller.declaringClass.className
        val calleeClass = callSite.callee.declaringClass.className
        classCounts[callerClass] = (classCounts[callerClass] ?: 0) + 1
        classCounts[calleeClass] = (classCounts[calleeClass] ?: 0) + 1
        if (callerClass != calleeClass) {
            val dependency = ClassDependency(callerClass, calleeClass)
            classEdges[dependency] = (classEdges[dependency] ?: 0) + 1
        }
    }
    return ClassOverview(classCounts, classEdges, scanned)
}

private const val GRAPH_NODE_TYPE = "Graph"
private const val GRAPH_CALL_EDGE_TYPE = "GraphCall"
private const val MAX_GRAPH_OVERVIEW_CLASSES = 1_000
private const val MAX_GRAPH_OVERVIEW_METHODS = 100_000
private const val MAX_GRAPH_OVERVIEW_CALL_SITES = 100_000
