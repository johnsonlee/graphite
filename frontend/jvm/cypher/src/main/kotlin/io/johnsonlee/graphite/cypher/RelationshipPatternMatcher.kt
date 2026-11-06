package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeEdge

/** Relationship expansion, variable-length trails and relationship predicates. */
internal class RelationshipPatternMatcher(
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val pathState: PatternPathState,
    private val nodeMatcher: NodePatternMatcher
) {
    /**
     * Match a relationship + target node from the current source node binding.
     */
    fun matchRelationship(
        sourceNodePattern: PatternElement.NodePattern,
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>,
        limit: Int?
    ): List<Map<String, Any?>> {
        val matches = matchRelationshipLazily(sourceNodePattern, rel, targetNodePattern, bindings)
        return (limit?.let(matches::take) ?: matches).toList()
    }

    fun matchRelationshipLazily(
        sourceNodePattern: PatternElement.NodePattern,
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        bindings: Map<String, Any?>,
        resultPredicate: ((Map<String, Any?>) -> Boolean)? = null
    ): Sequence<Map<String, Any?>> {
        val sourceNode = sourceNodePattern.variable
            ?.let(bindings::get)
            ?.let(graphAccess::nodeCursor)
            ?: graphAccess.findLastBoundNode(bindings)
            ?: return emptySequence()
        val resolvedEdgeTypes = rel.types.mapNotNull(NodePropertyAccessor::resolveEdgeType).distinct()
        val edgeClass = resolvedEdgeTypes.singleOrNull()
        return when {
            rel.types.isNotEmpty() && resolvedEdgeTypes.isEmpty() -> emptySequence()
            rel.variableLength ->
                matchVariableLengthPathLazily(
                    rel,
                    targetNodePattern,
                    sourceNode,
                    bindings,
                    edgeClass,
                    resultPredicate
                )
            else -> matchSingleHopLazily(rel, targetNodePattern, sourceNode, bindings, edgeClass)
        }
    }

    private fun matchSingleHopLazily(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?
    ): Sequence<Map<String, Any?>> = sequence {
        val edges = graphAccess.edgesForDirection(sourceNode, rel.direction, edgeClass)
        val unavailableRelationships = pathState.unavailableRelationships(rel, bindings, sourceNode.source)

        for (edge in edges) {
            if (edge.edge in unavailableRelationships || !pathState.matchesRelationshipBinding(rel, edge.value, bindings)) continue
            val newBindings = bindSingleHop(edge, rel, targetNodePattern, sourceNode, bindings)
            if (newBindings != null) yield(newBindings)
        }
    }

    private fun bindSingleHop(
        edge: EdgeCursor,
        rel: PatternElement.RelationshipPattern,
        targetPattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>
    ): Map<String, Any?>? {
        val targetId = graphAccess.resolveTargetId(edge.edge, sourceNode.node.id, rel.direction)
        val targetNode = graphAccess.trackedNode(sourceNode.source.graph, targetId) ?: return null
        val targetValue = graphAccess.nodeValue(sourceNode.source, targetNode)
        return if (matchesRelConstraints(edge.edge, rel, bindings)) {
            nodeMatcher.matchTargetNode(targetPattern, targetValue, bindings)?.toMutableMap()?.apply {
                if (rel.variable != null) {
                    this[rel.variable] = edge.value
                    rowOperations.addProvenance(this, edge.value)
                }
                pathState.addUsedRelationshipsIfTracked(this, sourceNode.source, listOf(edge.edge))
                pathState.addMatchedPathSegmentIfTracked(
                    this, MatchedPathSegment(listOf(edge.value, targetValue), listOf(edge.edge))
                )
            }
        } else {
            null
        }
    }

    private fun matchVariableLengthPathLazily(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        edgeClass: Class<out Edge>?,
        resultPredicate: ((Map<String, Any?>) -> Boolean)?
    ): Sequence<Map<String, Any?>> = sequence {
        val direction = when (rel.direction) {
            Direction.OUTGOING -> PathFinder.Direction.OUTGOING
            Direction.INCOMING -> PathFinder.Direction.INCOMING
            Direction.BOTH -> PathFinder.Direction.BOTH
        }

        val workTracker = if (context.workTrackingEnabled) context.workTracker else null
        val unavailableRelationships = pathState.unavailableRelationships(rel, bindings, sourceNode.source)
        val nodePredicate = variablePathNodePredicate(
            rel,
            targetNodePattern,
            sourceNode,
            bindings,
            resultPredicate
        )
        val paths = PathFinder.findPathMatches(
            graph = sourceNode.source.graph,
            sources = setOf(sourceNode.node.id),
            options = PathFinder.SearchOptions(
                targets = null,
                edgeType = edgeClass,
                minDepth = rel.minHops ?: 1,
                maxDepth = rel.maxHops,
                direction = direction,
                workTracker = workTracker,
                edgePredicate = { edge ->
                    edge !in unavailableRelationships && matchesRelConstraints(edge, rel, bindings)
                },
                nodePredicate = nodePredicate
            )
        )

        for (pathMatch in paths) {
            val endValue = graphAccess.nodeValue(sourceNode.source, pathMatch.endNode())
            val targetMatch = nodeMatcher.matchTargetNode(targetNodePattern, endValue, bindings) ?: continue

            val newBindings = targetMatch.toMutableMap()
            if (!mustMaterializePath(rel, bindings) ||
                bindMaterializedPath(pathMatch, rel, sourceNode, bindings, newBindings, workTracker)
            ) {
                yield(newBindings)
            }
        }
    }

    private fun bindMaterializedPath(
        pathMatch: PathFinder.PathMatch,
        rel: PatternElement.RelationshipPattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        newBindings: MutableMap<String, Any?>,
        workTracker: CypherWorkTracker?
    ): Boolean {
        val path = pathMatch.materialize(workTracker)
        val relationshipValues = path.edges.map { graphAccess.edgeValue(sourceNode.source, it) }
        if (!pathState.matchesRelationshipBinding(rel, relationshipValues, bindings)) return false
        if (rel.variable != null) {
            newBindings[rel.variable] = relationshipValues
            rowOperations.addProvenance(newBindings, relationshipValues)
        }
        pathState.addUsedRelationshipsIfTracked(newBindings, sourceNode.source, path.edges)
        if (INTERNAL_PATH_START_NODE_KEY in bindings) {
            val pathTail = buildList {
                path.edges.indices.forEach { index ->
                    add(relationshipValues[index])
                    add(graphAccess.nodeValue(sourceNode.source, path.nodes[index + 1]))
                }
            }
            pathState.addMatchedPathSegmentIfTracked(newBindings, MatchedPathSegment(pathTail, path.edges))
        }
        return true
    }

    private fun mustMaterializePath(
        rel: PatternElement.RelationshipPattern,
        bindings: Map<String, Any?>
    ): Boolean = rel.variable != null ||
        INTERNAL_PATH_START_NODE_KEY in bindings ||
        INTERNAL_RELATIONSHIP_MATCH_STATE_KEY in bindings

    private fun variablePathNodePredicate(
        rel: PatternElement.RelationshipPattern,
        targetNodePattern: PatternElement.NodePattern,
        sourceNode: NodeCursor,
        bindings: Map<String, Any?>,
        resultPredicate: ((Map<String, Any?>) -> Boolean)?
    ): ((Node) -> Boolean)? {
        val targetNeedsFiltering = targetNodePattern.labels.isNotEmpty() ||
            targetNodePattern.properties.isNotEmpty() ||
            targetNodePattern.variable?.let(bindings::containsKey) == true
        val canPushResultPredicate = resultPredicate != null && rel.variable == null
        return if (targetNeedsFiltering || canPushResultPredicate) {
            { node: Node ->
                val endValue = graphAccess.nodeValue(sourceNode.source, node)
                val targetMatch = nodeMatcher.matchTargetNode(targetNodePattern, endValue, bindings)
                targetMatch != null &&
                    (!canPushResultPredicate || resultPredicate?.invoke(targetMatch) == true)
            }
        } else {
            null
        }
    }

    fun matchesRelConstraints(
        edge: Edge,
        rel: PatternElement.RelationshipPattern,
        bindings: Map<String, Any?>
    ): Boolean {
        if (rel.types.isNotEmpty()) {
            val typeMatches = rel.types.any { requested ->
                matchesRelationshipType(edge, requested)
            }
            if (!typeMatches) {
                return false
            }
        }

        // Check inline property constraints
        return rel.properties.all { (key, value) ->
            val exprValue = context.evaluator.evaluate(value, bindings)
            val edgeValue = when (edge) {
                is DataFlowEdge -> if (key == "kind") edge.kind.name else null
                is CallEdge -> when (key) {
                    "virtual" -> edge.isVirtual
                    "dynamic" -> edge.isDynamic
                    else -> null
                }
                is TypeEdge -> if (key == "kind") edge.kind.name else null
                is ControlFlowEdge -> if (key == "kind") edge.kind.name else null
                is ResourceEdge -> if (key == "kind") edge.kind.name else null
            }
            cypherEquals(edgeValue, exprValue) == true
        }
    }

    /**
     * Matches only the relationship spellings that are part of the Cypher surface.
     *
     * Keep the aliases explicit: removing every underscore made malformed names
     * such as `D_A_T_A_F_L_O_W` indistinguishable from `DATAFLOW` and allocated
     * normalized strings for every candidate edge.
     */
    private fun matchesRelationshipType(edge: Edge, requested: String): Boolean = when (edge) {
        is DataFlowEdge ->
            requested.equals("DATAFLOW", ignoreCase = true) ||
                requested.equals("DATA_FLOW", ignoreCase = true)
        is CallEdge -> requested.equals("CALL", ignoreCase = true)
        is TypeEdge -> requested.equals("TYPE", ignoreCase = true)
        is ControlFlowEdge ->
            requested.equals("CONTROL_FLOW", ignoreCase = true) ||
                requested.equals("CONTROLFLOW", ignoreCase = true)
        is ResourceEdge ->
            requested.equals("RESOURCE", ignoreCase = true) || when (edge.kind) {
                ResourceRelation.OPENS -> requested.equals("RESOURCE_OPEN", ignoreCase = true)
                ResourceRelation.LOADS -> requested.equals("RESOURCE_LOAD", ignoreCase = true)
                ResourceRelation.BUNDLE_CANDIDATE ->
                    requested.equals("RESOURCE_BUNDLE_CANDIDATE", ignoreCase = true)
                ResourceRelation.LOOKUP -> requested.equals("RESOURCE_LOOKUP", ignoreCase = true)
                ResourceRelation.ENUMERATES -> requested.equals("RESOURCE_KEYS", ignoreCase = true)
            }
    }
}
