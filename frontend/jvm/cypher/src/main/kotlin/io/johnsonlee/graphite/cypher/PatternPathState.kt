package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node

internal const val INTERNAL_MATCHED_PATH_SEGMENTS_KEY = "\u0000graphite.matchedPathSegments"

internal const val INTERNAL_CURRENT_NODE_KEY = "\u0000graphite.currentNode"

internal const val INTERNAL_PATH_START_NODE_KEY = "\u0000graphite.pathStartNode"

internal const val INTERNAL_RELATIONSHIP_MATCH_STATE_KEY = "\u0000graphite.relationshipMatchState"

internal data class MatchedPathSegment(
    val tail: List<Any>,
    val relationships: List<Edge>
)

private data class RelationshipMatchState(
    val reserved: Set<QualifiedEdge>,
    val used: Set<QualifiedEdge>
)

/** Exact trail and relationship-uniqueness state, kept outside visible query bindings. */
internal class PatternPathState(private val sources: List<CypherGraph>, private val qualified: Boolean) {
    fun containsInternalPathState(bindings: Map<String, Any?>): Boolean =
        INTERNAL_MATCHED_PATH_SEGMENTS_KEY in bindings ||
            INTERNAL_CURRENT_NODE_KEY in bindings ||
            INTERNAL_PATH_START_NODE_KEY in bindings

    fun prepareRelationshipMatchState(
        pattern: CypherPattern,
        bindings: Map<String, Any?>
    ): Map<String, Any?> {
        val relationshipCount = pattern.elements.count { it is PatternElement.RelationshipPattern }
        if (relationshipCount <= 1 && INTERNAL_RELATIONSHIP_MATCH_STATE_KEY !in bindings) return bindings
        val reserved = pattern.elements.asSequence()
            .filterIsInstance<PatternElement.RelationshipPattern>()
            .mapNotNull(PatternElement.RelationshipPattern::variable)
            .flatMap { variable -> relationshipBinding(bindings[variable]).asSequence() }
            .toSet()
        val previous = bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] as? RelationshipMatchState
        return bindings.toMutableMap().apply {
            this[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] = RelationshipMatchState(
                reserved = reserved,
                used = previous?.used.orEmpty()
            )
        }
    }

    fun startRelationshipMatchState(bindings: Map<String, Any?>): Map<String, Any?> =
        bindings.toMutableMap().apply {
            this[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] = RelationshipMatchState(emptySet(), emptySet())
        }

    fun removeRelationshipMatchState(bindings: Map<String, Any?>): Map<String, Any?> {
        if (INTERNAL_RELATIONSHIP_MATCH_STATE_KEY !in bindings) return bindings
        return bindings.toMutableMap().apply { remove(INTERNAL_RELATIONSHIP_MATCH_STATE_KEY) }
    }

    /**
     * Build a path representation as a list of alternating nodes and edges:
     * [startNode, edge1, node2, edge2, ..., endNode]
     *
     * Relationship matches are retained in an internal segment binding so
     * unnamed and variable-length relationships preserve their exact trail.
     */
    fun buildPathRepresentation(
        pattern: CypherPattern,
        bindings: Map<String, Any?>
    ): Any {
        @Suppress("UNCHECKED_CAST")
        val matchedSegments = (bindings[INTERNAL_MATCHED_PATH_SEGMENTS_KEY] as? List<MatchedPathSegment>).orEmpty()
        val firstNode = (pattern.elements.firstOrNull() as? PatternElement.NodePattern)
            ?.variable
            ?.let(bindings::get)
            ?: bindings[INTERNAL_PATH_START_NODE_KEY]
        val path = buildList {
            if (firstNode != null) add(firstNode)
            matchedSegments.forEach { addAll(it.tail) }
        }
        return pathValue(path)
    }

    private fun pathValue(path: List<Any>): Any {
        if (!qualified) return PathFinder.Path(path.filterIsInstance<Node>(), path.filterIsInstance<Edge>())
        val nodes = path.filterIsInstance<QualifiedNode>()
        val edges = path.filterIsInstance<QualifiedEdge>()
        val graphIds = (nodes.map { it.graphId } + edges.map { it.graphId }).distinct()
        return if (graphIds.size == 1) QualifiedPath(graphIds.single(), nodes, edges) else path
    }

    fun unavailableRelationships(
        rel: PatternElement.RelationshipPattern,
        bindings: Map<String, Any?>,
        source: CypherGraph
    ): Set<Edge> {
        val state = bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] as? RelationshipMatchState ?: return emptySet()
        val availableBinding = relationshipBinding(rel.variable?.let(bindings::get))
        return (state.used + (state.reserved - availableBinding))
            .asSequence()
            .filter { it.graphId == source.id }
            .mapTo(linkedSetOf()) { it.edge }
    }

    fun addUsedRelationshipsIfTracked(
        bindings: MutableMap<String, Any?>,
        source: CypherGraph,
        edges: Collection<Edge>
    ) {
        val state = bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] as? RelationshipMatchState ?: return
        val qualifiedEdges = edges.mapTo(linkedSetOf()) { QualifiedEdge(source.id, source.graph, it) }
        bindings[INTERNAL_RELATIONSHIP_MATCH_STATE_KEY] = state.copy(used = state.used + qualifiedEdges)
    }

    fun matchesRelationshipBinding(
        rel: PatternElement.RelationshipPattern,
        candidate: Any,
        bindings: Map<String, Any?>
    ): Boolean = rel.variable == null || !bindings.containsKey(rel.variable) || bindings[rel.variable] == candidate

    private fun relationshipBinding(value: Any?): Set<QualifiedEdge> = when (value) {
        is Edge -> sources.singleOrNull()?.let { source ->
            setOf(QualifiedEdge(source.id, source.graph, value))
        }.orEmpty()
        is QualifiedEdge -> setOf(value)
        is PathFinder.Path -> sources.singleOrNull()?.let { source ->
            value.edges.mapTo(linkedSetOf()) { QualifiedEdge(source.id, source.graph, it) }
        }.orEmpty()
        is QualifiedPath -> value.edges.toSet()
        is List<*> -> value.flatMapTo(linkedSetOf(), ::relationshipBinding)
        else -> emptySet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun addMatchedPathSegment(bindings: MutableMap<String, Any?>, segment: MatchedPathSegment) {
        val segments = (bindings[INTERNAL_MATCHED_PATH_SEGMENTS_KEY] as? List<MatchedPathSegment>).orEmpty()
        bindings[INTERNAL_MATCHED_PATH_SEGMENTS_KEY] = segments + segment
    }

    fun addMatchedPathSegmentIfTracked(
        bindings: MutableMap<String, Any?>,
        segment: MatchedPathSegment
    ) {
        if (INTERNAL_PATH_START_NODE_KEY in bindings) addMatchedPathSegment(bindings, segment)
    }
}
