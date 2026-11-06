package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node

/** Bounded distinct property values retain first-seen order and complete graph provenance. */
internal class DistinctPropertyQueryExecutor(
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations
) {
    private data class Query(
        val nodeClass: Class<out Node>?,
        val variable: String,
        val property: String,
        val column: String,
        val limit: Int
    )

    fun tryExecute(clauses: List<CypherClause>): CypherResult? = limitedPatternQuery(clauses)
        ?.takeIf { !it.match.optional && it.match.where == null && it.returned.distinct }
        ?.let(::compileQuery)?.let(::execute)

    private fun compileQuery(shape: LimitedPatternQuery): Query? {
        val node = singleNodePattern(shape.match)?.takeIf { it.variable != null && it.labels.size <= 1 && it.properties.isEmpty() }
        val item = shape.returned.items.singleOrNull()
        return if (node != null && item != null) compileProjection(node, item, shape.limit) else null
    }

    private fun compileProjection(node: PatternElement.NodePattern, item: ReturnItem, limit: CypherClause.Limit): Query? {
        val variable = checkNotNull(node.variable)
        val property = (item.expression as? CypherExpr.Property)?.takeIf { it.expression == CypherExpr.Variable(variable) }
        val count = context.literalLimitCount(limit.count)
        return if (property != null && count != null) Query(resolveNodeClass(node.labels), variable, property.propertyName,
            item.alias ?: item.expression.toCypherString(), count) else null
    }

    private fun execute(query: Query): CypherResult {
        val nodeClass = query.nodeClass
        if (query.limit <= 0 || nodeClass == null) return CypherResult(listOf(query.column), emptyList())
        val seen = LinkedHashMap<Any, Pair<Any?, Set<String>>>()
        for (candidate in graphAccess.nodeCandidates(nodeClass)) {
            val value = context.evaluator.evaluate(CypherExpr.Property(CypherExpr.Variable(query.variable), query.property),
                mapOf(query.variable to candidate))
            val provenance = rowOperations.provenanceOf(candidate)
            val key = cypherValueKey(value)
            if (key in seen) {
                val (first, previous) = seen.getValue(key)
                seen[key] = first to (previous + provenance)
            } else if (seen.size < query.limit) seen[key] = value to provenance
            if (!graphAccess.qualified && seen.size >= query.limit) break
        }
        return CypherResult(listOf(query.column), seen.values.map { (value, provenance) ->
            mutableMapOf<String, Any?>(query.column to value).apply {
                if (provenance.isNotEmpty()) put(INTERNAL_PROVENANCE_KEY, provenance)
            }
        })
    }
}
