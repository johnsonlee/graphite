package io.johnsonlee.graphite.cypher

private const val STREAMING_NODE_CLAUSE_COUNT = 3

/** Streams an unbounded single-node MATCH through its final predicate and projection. */
internal class StreamingNodeQueryExecutor(
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val rowProjection: CypherProjection,
    private val nodeMatcher: NodePatternMatcher,
    private val sourcePlanner: GraphSourcePlanner
) {
    fun tryExecute(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != STREAMING_NODE_CLAUSE_COUNT) return null
        return filteredNodeQuery(clauses)?.takeIf(::supportedShape)?.let(::execute)
    }

    private fun supportedShape(shape: FilteredNodeQuery): Boolean {
        val matchSupported = !shape.match.optional && shape.match.where == null
        val outputSupported = !shape.returned.distinct &&
            shape.returned.items.none { rowProjection.containsAggregation(it.expression) } &&
            shape.returned.items.none { (it.expression as? CypherExpr.Variable)?.name == "*" }
        return matchSupported && outputSupported
    }

    private fun execute(shape: FilteredNodeQuery): CypherResult? {
        val variable = shape.node.variable ?: return null
        return if (nodeMatcher.elementIdEquality(shape.where.condition, variable) == null) project(shape, variable) else null
    }

    private fun project(shape: FilteredNodeQuery, variable: String): CypherResult {
        val columns = shape.returned.items.map { it.alias ?: it.expression.toCypherString() }
        val rows = mutableListOf<Map<String, Any?>>()
        val sources = sourcePlanner.graphIdEquality(shape.where.condition, variable)
            ?.let { id -> graphAccess.sources.filter { it.id == id } } ?: graphAccess.sources
        val direct = nodeMatcher.qualifiedIdBindings(shape.match.patterns.single(), shape.where.condition, sources)
        if (direct != null) {
            for (bindings in direct) {
                if (context.evaluator.evaluate(shape.where.condition, bindings) == true) {
                    rows.add(rowProjection.projectRow(shape.returned.items, columns, bindings))
                }
            }
        } else {
            val bindings = mutableMapOf<String, Any?>(variable to null)
            for (candidate in nodeMatcher.nodeElementCandidates(shape.node, emptyMap(), sources)) {
                projectCandidate(shape, variable, columns, candidate, bindings)?.let(rows::add)
            }
        }
        context.checkCancelled()
        return CypherResult(columns, rows)
    }

    private fun projectCandidate(
        shape: FilteredNodeQuery,
        variable: String,
        columns: List<String>,
        candidate: Any,
        predicateBindings: MutableMap<String, Any?>
    ): Map<String, Any?>? {
        if (!nodeMatcher.matchesNodeConstraints(candidate, shape.node, emptyMap())) return null
        predicateBindings[variable] = candidate
        return if (context.evaluator.evaluate(shape.where.condition, predicateBindings) == true) {
            val bindings = mutableMapOf<String, Any?>(variable to candidate)
            rowOperations.addProvenance(bindings, candidate)
            rowProjection.projectRow(shape.returned.items, columns, bindings)
        } else null
    }
}
