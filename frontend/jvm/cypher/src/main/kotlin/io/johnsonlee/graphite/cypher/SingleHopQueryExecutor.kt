package io.johnsonlee.graphite.cypher

private const val SINGLE_HOP_ELEMENT_COUNT = 3

/** Bounded single-hop projection preserves relationship constraints and target work accounting. */
internal class SingleHopQueryExecutor(
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val rowProjection: CypherProjection,
    private val nodeMatcher: NodePatternMatcher,
    private val relationshipMatcher: RelationshipPatternMatcher
) {
    private data class Query(
        val source: PatternElement.NodePattern,
        val relationship: PatternElement.RelationshipPattern,
        val target: PatternElement.NodePattern,
        val returned: CypherClause.Return,
        val columns: List<String>,
        val limit: Int
    )

    fun tryExecute(clauses: List<CypherClause>): CypherResult? = limitedPatternQuery(clauses)
        ?.takeIf { !it.match.optional && supportedOutput(it.returned) }
        ?.let(::compileQuery)?.let(::execute)

    private fun supportedOutput(ret: CypherClause.Return): Boolean = !ret.distinct &&
        ret.items.none { rowProjection.containsAggregation(it.expression) } &&
        ret.items.none { (it.expression as? CypherExpr.Variable)?.name == "*" }

    private fun compileQuery(shape: LimitedPatternQuery): Query? = shape.match.patterns.singleOrNull()
        ?.takeIf { it.pathVariable == null && it.elements.size == SINGLE_HOP_ELEMENT_COUNT }
        ?.let { pattern ->
            val source = pattern.elements[0] as? PatternElement.NodePattern
            val relationship = pattern.elements[1] as? PatternElement.RelationshipPattern
            val target = pattern.elements[2] as? PatternElement.NodePattern
            if (source?.variable != null && relationship != null && target != null) {
                compilePlan(shape, source, relationship, target)
            } else null
        }

    private fun compilePlan(
        shape: LimitedPatternQuery,
        source: PatternElement.NodePattern,
        relationship: PatternElement.RelationshipPattern,
        target: PatternElement.NodePattern
    ): Query? = if (relationship.variableLength) null else context.literalLimitCount(shape.limit.count)?.let { limit ->
        Query(source, relationship, target, shape.returned,
            shape.returned.items.map { it.alias ?: it.expression.toCypherString() }, limit)
    }

    private fun execute(query: Query): CypherResult {
        val nodeClass = resolveNodeClass(query.source.labels)
        if (query.limit <= 0 || nodeClass == null) return CypherResult(query.columns, emptyList())
        val rows = mutableListOf<Map<String, Any?>>()
        for (value in graphAccess.nodeCandidates(nodeClass)) {
            val cursor = if (nodeMatcher.matchesNodeConstraints(value, query.source, emptyMap())) graphAccess.nodeCursor(value) else null
            if (cursor != null && appendSource(query, cursor, rows)) break
        }
        return CypherResult(query.columns, rows)
    }

    private fun appendSource(query: Query, source: NodeCursor, rows: MutableList<Map<String, Any?>>): Boolean {
        val bindings = mutableMapOf<String, Any?>(checkNotNull(query.source.variable) to source.value)
        rowOperations.addProvenance(bindings, source.value)
        val edgeClass = query.relationship.types.singleOrNull()?.let(NodePropertyAccessor::resolveEdgeType)
        for (edge in graphAccess.edgesForDirection(source, query.relationship.direction, edgeClass)) {
            val target = targetBindings(query, source, bindings, edge)
            if (target != null) {
                rows.add(rowProjection.projectRow(query.returned.items, query.columns, target))
                if (rows.size >= query.limit) return true
            }
        }
        return false
    }

    private fun targetBindings(query: Query, source: NodeCursor, bindings: Map<String, Any?>, edge: EdgeCursor): Map<String, Any?>? {
        val relationship = query.relationship
        if (!relationshipMatcher.matchesRelConstraints(edge.edge, relationship, bindings)) return null
        val targetId = graphAccess.resolveTargetId(edge.edge, source.node.id, relationship.direction)
        return graphAccess.trackedNode(source.source.graph, targetId)?.let { node ->
            nodeMatcher.matchTargetNode(query.target, graphAccess.nodeValue(source.source, node), bindings)?.toMutableMap()?.apply {
                if (relationship.variable != null) {
                    this[relationship.variable] = edge.value
                    rowOperations.addProvenance(this, edge.value)
                }
            }
        }
    }
}
