package io.johnsonlee.graphite.cypher

private const val MIN_STREAMING_MATCH_CLAUSES = 4
private const val MAX_STREAMING_MATCH_CLAUSES = 6
private const val STREAMING_MATCH_SUFFIX_INDEX = 3
private const val SINGLE_HOP_ELEMENTS = 3

/** Compiles filtered pattern pagination without eager MATCH result materialization. */
internal class StreamingMatchQueryExecutor(
    private val context: PipelineExecutionContext,
    private val graphAccess: PipelineGraphAccess,
    private val rowProjection: CypherProjection,
    private val sourcePlanner: GraphSourcePlanner,
    private val nodeMatcher: NodePatternMatcher,
    private val patternMatcher: PatternMatcher,
    private val matchSeeds: FilteredMatchSeeds,
    private val rowOrdering: FilteredRowOrdering,
    private val indexedProjection: IndexedStringProjection
) {
    private data class Suffix(val orderBy: CypherClause.OrderBy?, val skip: CypherClause.Skip?, val limit: CypherClause.Limit)
    private data class MatchQuery(
        val prefix: FilteredPatternQuery,
        val suffix: Suffix,
        val pattern: CypherPattern,
        val limit: Int,
        val skip: Int,
        val columns: List<String>
    )

    fun tryExecute(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size !in MIN_STREAMING_MATCH_CLAUSES..MAX_STREAMING_MATCH_CLAUSES) return null
        return filteredPatternQuery(clauses)?.let { prefix ->
            suffix(clauses)?.let { compileQuery(prefix, it) }?.let(::executeQuery)
        }
    }

    private fun suffix(clauses: List<CypherClause>): Suffix? {
        var index = STREAMING_MATCH_SUFFIX_INDEX
        val orderBy = (clauses.getOrNull(index) as? CypherClause.OrderBy)?.also { index++ }
        val skip = (clauses.getOrNull(index) as? CypherClause.Skip)?.also { index++ }
        return (clauses.getOrNull(index) as? CypherClause.Limit)?.takeIf { index == clauses.lastIndex }
            ?.let { Suffix(orderBy, skip, it) }
    }

    private fun compileQuery(prefix: FilteredPatternQuery, suffix: Suffix): MatchQuery? {
        val pattern = prefix.match.patterns.singleOrNull()?.takeIf { it.pathVariable == null }
        val outputSupported = prefix.returned.items.none { rowProjection.containsAggregation(it.expression) } &&
            prefix.returned.items.none { (it.expression as? CypherExpr.Variable)?.name == "*" }
        return if (!prefix.match.optional && pattern != null && outputSupported) compilePage(prefix, suffix, pattern) else null
    }

    private fun compilePage(prefix: FilteredPatternQuery, suffix: Suffix, pattern: CypherPattern): MatchQuery? {
        val limit = context.literalLimitCount(suffix.limit.count) ?: return null
        val skip = suffix.skip?.count?.let(context::literalLimitCount) ?: 0
        return if (skip < 0) null else MatchQuery(prefix, suffix, pattern, limit, skip,
            prefix.returned.items.map { it.alias ?: it.expression.toCypherString() })
    }

    private fun executeQuery(query: MatchQuery): CypherResult? {
        if (query.limit <= 0) return CypherResult(query.columns, emptyList())
        val retained = query.skip.toLong() + query.limit
        return if (retained > Int.MAX_VALUE) null else executePage(query,
            FilteredRowPlan(query.prefix.where, query.prefix.returned, query.columns, query.skip, retained.toInt(), query.suffix.orderBy))
    }

    private fun executePage(query: MatchQuery, plan: FilteredRowPlan): CypherResult {
        val firstNode = query.pattern.elements.firstOrNull() as? PatternElement.NodePattern
        val candidates = firstNode?.variable?.let { sourcePlanner.graphIdEquality(plan.where.condition, it) }
            ?.let { id -> graphAccess.sources.filter { it.id == id } } ?: graphAccess.sources
        // Construct direct seeds before choosing output execution, preserving admission and error order.
        val direct = directMatches(query, candidates)
        val optimized = if (plan.orderBy == null && plan.returned.distinct) indexedDistinct(query, plan) else {
            (query.pattern.elements.singleOrNull() as? PatternElement.NodePattern)
                ?.takeIf { plan.orderBy != null }
                ?.let { rowOrdering.projectOrderedDirectStringRows(it, candidates, plan) }
        }
        return optimized ?: project(query, plan, matches(query, plan, candidates, direct))
    }

    private fun directMatches(query: MatchQuery, candidates: List<CypherGraph>): Sequence<Map<String, Any?>>? {
        val direct = nodeMatcher.qualifiedIdBindings(query.pattern, query.prefix.where.condition, candidates)
        return direct ?: (query.pattern.elements.singleOrNull() as? PatternElement.NodePattern)
            ?.let { matchSeeds.directStringBindings(it, query.prefix.where.condition, candidates) }
    }

    private fun indexedDistinct(query: MatchQuery, plan: FilteredRowPlan): CypherResult? =
        (query.pattern.elements.singleOrNull() as? PatternElement.NodePattern)
            ?.takeIf { it.variable != null && it.labels.size <= 1 && it.properties.isEmpty() }
            ?.let { node ->
                val variable = checkNotNull(node.variable)
                val nodeClass = resolveNodeClass(node.labels)
                val filter = DirectStringDisjunction.compile(plan.where.condition, variable, context.parameters)
                if (nodeClass != null && filter != null) {
                    indexedProjection.executeIndexedDistinctStringProjection(
                        StringProjectionSpec(nodeClass, variable, filter, plan.returned.items, plan.columns), plan.retained
                    )?.let { CypherResult(plan.columns, it.rows.drop(plan.skip)) }
                } else null
            }

    private fun matches(
        query: MatchQuery,
        plan: FilteredRowPlan,
        candidates: List<CypherGraph>,
        direct: Sequence<Map<String, Any?>>?
    ): Sequence<Map<String, Any?>> {
        val seed = sourceCondition(query.pattern, plan.where.condition)
        val firstNode = query.pattern.elements.firstOrNull() as? PatternElement.NodePattern
        return direct ?: patternMatcher.matchPatternLazily(query.pattern, emptyMap(), candidates,
            finalResultPredicate = { context.evaluator.evaluate(plan.where.condition, it) == true },
            seedPredicate = seed?.let { predicate -> { context.evaluator.evaluate(predicate, it) == true } },
            seedMatches = if (seed != null) {
                firstNode?.let { matchSeeds.orderedSourceMatches(it, seed, candidates) }
            } else matchSeeds.targetPreflightSourceMatches(query.pattern, plan.where.condition, candidates))
    }

    private fun sourceCondition(pattern: CypherPattern, condition: CypherExpr): CypherExpr? {
        if (pattern.elements.size != SINGLE_HOP_ELEMENTS) return null
        val source = pattern.elements[0] as? PatternElement.NodePattern
        val relationship = pattern.elements[1] as? PatternElement.RelationshipPattern
        val target = pattern.elements[2] as? PatternElement.NodePattern
        return if (source?.variable != null && relationship != null && target != null) {
            val eligibleRelationship = !relationship.variableLength && relationship.properties.isEmpty() &&
                relationship.variable != source.variable
            if (eligibleRelationship && target.properties.isEmpty()) SourcePredicatePushdown.compile(condition, source.variable) else null
        } else null
    }

    private fun project(query: MatchQuery, plan: FilteredRowPlan, matches: Sequence<Map<String, Any?>>): CypherResult = when {
        plan.orderBy != null -> rowOrdering.projectOrderedFilteredRows(matches, plan)
        plan.returned.distinct -> rowOrdering.projectDistinctFilteredRows(matches, plan)
        else -> projectPage(query.limit, plan, matches)
    }

    private fun projectPage(limit: Int, plan: FilteredRowPlan, matches: Sequence<Map<String, Any?>>): CypherResult {
        val rows = mutableListOf<Map<String, Any?>>()
        var matched = 0
        for (bindings in matches) {
            if (context.evaluator.evaluate(plan.where.condition, bindings) == true && matched++ >= plan.skip) {
                rows.add(rowProjection.projectRow(plan.returned.items, plan.columns, bindings))
                if (rows.size >= limit) break
            }
        }
        return CypherResult(plan.columns, rows)
    }
}
