package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.Graph

private const val SINGLE_GRAPH_ID = "single"

/**
 * Executes a sequence of [CypherClause] elements against a [Graph],
 * maintaining a result set (list of binding maps) that flows through each clause.
 *
 * Supported clauses:
 * - `MATCH` / `OPTIONAL MATCH` -- pattern matching against the graph
 * - `WHERE` -- row filtering via boolean expressions
 * - `RETURN` / `WITH` -- projection and aggregation
 * - `UNWIND` -- list expansion
 * - `ORDER BY` / `SKIP` / `LIMIT` -- result ordering and pagination
 * - `UNION` / `UNION ALL` -- result set combination
 *
 * Write clauses (`CREATE`, `DELETE`, `SET`, `REMOVE`) are rejected because Graphite
 * graphs are immutable. `UNION` is combined by the enclosing [CypherExecutor].
 */
class QueryPipeline private constructor(
    private val sources: List<CypherGraph>,
    private val qualified: Boolean,
    private val workTrackingEnabled: Boolean,
    private val graphSourceScopeApplied: Boolean
) {

    constructor(graph: Graph) : this(listOf(CypherGraph(SINGLE_GRAPH_ID, graph)), false, false, false)

    internal constructor(graph: Graph, workTrackingEnabled: Boolean) :
        this(listOf(CypherGraph(SINGLE_GRAPH_ID, graph)), false, workTrackingEnabled, false)

    internal constructor(graphs: List<CypherGraph>) : this(graphs, true, false, false)

    internal constructor(graphs: List<CypherGraph>, workTrackingEnabled: Boolean) :
        this(graphs, true, workTrackingEnabled, false)

    internal constructor(
        graphs: List<CypherGraph>,
        workTrackingEnabled: Boolean,
        graphSourceScopeApplied: Boolean
    ) : this(graphs, true, workTrackingEnabled, graphSourceScopeApplied)

    init {
        val graphIds = HashSet<String>(sources.size)
        require(sources.all { source -> graphIds.add(source.id) }) { "Graph ids must be unique" }
    }

    internal val defaultGraph: Graph? get() = sources.singleOrNull()?.graph

    private val context = PipelineExecutionContext(defaultGraph, workTrackingEnabled)
    private val rowOperations = CypherRowOperations(context, qualified)
    private val rowProjection = CypherProjection(context, rowOperations)
    private val sourcePlanner = GraphSourcePlanner(sources, context)
    private val graphAccess = PipelineGraphAccess(sources, qualified, context)
    private val pathState = PatternPathState(sources, qualified)
    private val nodeMatcher = NodePatternMatcher(sources, qualified, context, graphAccess, rowOperations)
    private val relationshipMatcher = RelationshipPatternMatcher(context, graphAccess, rowOperations, pathState, nodeMatcher)
    private val patternMatcher = PatternMatcher(sources, context, rowOperations, pathState, nodeMatcher, relationshipMatcher)
    private val stringScanner = DirectStringScanner(sources, context, graphAccess)
    private val stringScanPolicy = StringScanPolicy(sources, qualified)
    private val indexedStringProjection = IndexedStringProjection(sources, context, graphAccess, rowOperations, stringScanner)
    private val directStringExecutor = DirectStringExecutor(
        sources, qualified, context, graphAccess, rowOperations, rowProjection, stringScanner, stringScanPolicy, indexedStringProjection
    )
    private val metadataQueries = MetadataQueryExecutor(sources, qualified)
    private val distinctProperties = DistinctPropertyQueryExecutor(context, graphAccess, rowOperations)
    private val singleHopQueries = SingleHopQueryExecutor(
        context, graphAccess, rowOperations, rowProjection, nodeMatcher, relationshipMatcher
    )
    private val matchSeeds = FilteredMatchSeeds(context, graphAccess, rowOperations, nodeMatcher, stringScanner)
    private val filteredRowOrdering = FilteredRowOrdering(
        context, graphAccess, rowOperations, rowProjection, stringScanner, stringScanPolicy
    )
    private val filteredStringCounts = FilteredStringCountExecutor(sources, qualified, context, sourcePlanner, graphAccess, stringScanner)
    private val filteredNodeLimits = FilteredNodeLimitExecutor(
        context, sourcePlanner, graphAccess, rowOperations, rowProjection, nodeMatcher,
        stringScanPolicy, directStringExecutor, graphSourceScopeApplied
    )
    private val orderedPropertyQueries = OrderedPropertyQueryExecutor(sources, qualified, context, graphAccess, rowOperations)
    private val streamingNodes = StreamingNodeQueryExecutor(context, graphAccess, rowOperations, rowProjection, nodeMatcher, sourcePlanner)
    private val streamingMatches = StreamingMatchQueryExecutor(context, graphAccess, rowProjection, sourcePlanner,
        nodeMatcher, patternMatcher, matchSeeds, filteredRowOrdering, indexedStringProjection)

    /**
     * Execute a list of clauses and return the final result.
     */
    fun execute(clauses: List<CypherClause>): CypherResult = execute(clauses, emptyMap(), null)

    internal fun execute(
        clauses: List<CypherClause>,
        workTracker: CypherWorkTracker?
    ): CypherResult = execute(clauses, emptyMap(), workTracker)

    internal fun execute(
        clauses: List<CypherClause>,
        parameters: Map<String, Any?>,
        workTracker: CypherWorkTracker?
    ): CypherResult {
        return context.execute(parameters, workTracker) { executeWithActiveBudget(clauses) }
    }

    private fun executeWithActiveBudget(clauses: List<CypherClause>): CypherResult {
        context.checkCancelled()
        if (sourcePlanner.hasUnknownNodeLabel(clauses)) return executeGeneralFallback(clauses)
        val scope = if (graphSourceScopeApplied) null else sourcePlanner.graphSourceScope(clauses)
        scope?.let(sourcePlanner::recordGraphSourceScope)
        return if (MethodQueryExecutor.referencesMethod(clauses)) executeMethodQuery(clauses, scope) else executeNodeQuery(clauses, scope)
    }

    private fun executeMethodQuery(clauses: List<CypherClause>, scope: GraphSourcePlanner.GraphSourceScope?): CypherResult {
        val methodResult = if (context.parameters.isEmpty()) MethodQueryExecutor.tryExecute(
            clauses, scope?.selectedSources ?: sources, qualified, context::checkCancelled, context.workTracker
        ) else null
        return methodResult?.also { context.workTracker?.recordFastPath() }
            ?: executeScopedQuery(clauses, scope) ?: executeGeneralFallback(clauses)
    }

    private fun executeNodeQuery(clauses: List<CypherClause>, scope: GraphSourcePlanner.GraphSourceScope?): CypherResult {
        // The bounded string plan consumes source selection directly instead of constructing a throwaway pipeline.
        val filtered = filteredNodeLimits.tryExecute(clauses, scope?.selectedSources)
        return if (filtered != null) {
            context.workTracker?.recordFilteredNodeLimitFastPath()
            filtered
        } else executeScopedQuery(clauses, scope)
            ?: executeFastNodeQuery(clauses)?.also { context.workTracker?.recordFastPath() }
            ?: executeGeneralFallback(clauses)
    }

    private fun executeFastNodeQuery(clauses: List<CypherClause>): CypherResult? = metadataQueries.tryFastNodeCount(clauses)
        ?: metadataQueries.tryFastLabelHistogram(clauses)
        ?: orderedPropertyQueries.tryFastOrderedPropertyLimit(clauses)
        ?: distinctProperties.tryExecute(clauses)
        ?: filteredStringCounts.tryFastFilteredStringCount(clauses)
        ?: streamingNodes.tryExecute(clauses)
        ?: streamingMatches.tryExecute(clauses)
        ?: singleHopQueries.tryExecute(clauses)

    private fun executeScopedQuery(clauses: List<CypherClause>, scope: GraphSourcePlanner.GraphSourceScope?): CypherResult? {
        if (scope == null || scope.selectedSources == sources) return null
        return QueryPipeline(scope.selectedSources, qualified = true, workTrackingEnabled = workTrackingEnabled,
            graphSourceScopeApplied = true).execute(clauses, context.parameters, context.workTracker)
    }

    private fun executeGeneralFallback(clauses: List<CypherClause>): CypherResult {
        context.workTracker?.recordGeneralFallback()
        return ClauseExecution(clauses).execute()
    }

    /** Owns one general execution's row set, projection columns and consumed WHERE cursor. */
    private inner class ClauseExecution(private val clauses: List<CypherClause>) {
        private var rows: List<Map<String, Any?>> = listOf(emptyMap())
        private var columns: List<String> = emptyList()
        private val earlyLimit = computeEarlyLimit(clauses)
        private var consumedWhereIndex = -1

        fun execute(): CypherResult {
            for (index in clauses.indices) {
                context.checkCancelled()
                executeClause(index)
                context.checkCancelled()
            }
            if (columns.isEmpty() && rows.isNotEmpty()) columns = rows.first().keys.toList()
            context.checkCancelled()
            return CypherResult(columns, rows)
        }

        private fun executeClause(index: Int) {
            when (val clause = clauses[index]) {
                is CypherClause.Match -> executeMatch(clause, index)
                is CypherClause.Where -> if (index != consumedWhereIndex) rows = rowOperations.executeWhere(clause, rows)
                is CypherClause.Return -> project(clause.items, clause.distinct, index)
                is CypherClause.With -> projectWith(clause, index)
                is CypherClause.Unwind -> rows = rowOperations.executeUnwind(clause, rows)
                is CypherClause.OrderBy -> rows = rowOperations.executeOrderBy(clause, rows)
                is CypherClause.Skip -> rows = rows.drop(context.integerValue(clause.count, rows.firstOrNull() ?: emptyMap()))
                is CypherClause.Limit -> rows = rows.take(context.integerValue(clause.count, rows.firstOrNull() ?: emptyMap()))
                is CypherClause.Union -> TODO("UNION is handled by CypherExecutor, not QueryPipeline")
                is CypherClause.Create -> TODO("CREATE is not supported — graph is immutable")
                is CypherClause.Delete -> TODO("DELETE is not supported — graph is immutable")
                is CypherClause.Set -> TODO("SET is not supported — graph is immutable")
                is CypherClause.Remove -> TODO("REMOVE is not supported — graph is immutable")
            }
        }

        private fun projectWith(clause: CypherClause.With, index: Int) {
            project(clause.items, clause.distinct, index)
            if (clause.where != null) rows = rowOperations.executeInlineWhere(clause.where, rows)
        }

        private fun executeMatch(clause: CypherClause.Match, index: Int) {
            val where = clauses.getOrNull(index + 1) as? CypherClause.Where
            val sought = where?.takeIf { it.condition is CypherExpr.Comparison }?.let { nodeMatcher.tryElementIdSeek(clause, it, rows) }
            val qualifiedRows = qualifiedRootMatch(clause, index, where)
            rows = when {
                qualifiedRows != null -> qualifiedRows
                sought != null -> {
                    consumedWhereIndex = index + 1
                    sought
                }
                clause.optional -> patternMatcher.executeOptionalMatch(clause.patterns, clause.where, rows)
                else -> patternMatcher.executeMatch(clause.patterns, rows, earlyLimit)
            }
            if (!clause.optional && clause.where != null) rows = rowOperations.executeInlineWhere(clause.where, rows)
        }

        private fun qualifiedRootMatch(clause: CypherClause.Match, index: Int, where: CypherClause.Where?): List<Map<String, Any?>>? {
            val rootRow = index == 0 && rows.size == 1 && rows.single().isEmpty()
            val requiredPattern = !clause.optional && clause.where == null && clause.patterns.size == 1
            return if (rootRow && requiredPattern) {
                where?.let { nodeMatcher.qualifiedIdBindings(clause.patterns.single(), it.condition, sources)?.toList() }
            } else null
        }

        private fun project(items: List<ReturnItem>, distinct: Boolean, index: Int) {
            val orderBy = clauses.getOrNull(index + 1) as? CypherClause.OrderBy
            val (projected, projectedColumns) = rowProjection.projectAndAggregate(items, rows, distinct, orderBy)
            rows = projected
            columns = projectedColumns
        }
    }

    /**
     * Determine whether LIMIT can be pushed down into pattern matching.
     *
     * This is safe only when the clause sequence between MATCH and LIMIT
     * contains nothing that could filter, reorder, or aggregate rows
     * (i.e. no WHERE, WITH, ORDER BY, UNWIND, SKIP, or aggregation in RETURN).
     * When those intermediate clauses are absent the final row count is
     * bounded by the number of pattern matches, so we can stop scanning early.
     */
    private fun computeEarlyLimit(clauses: List<CypherClause>): Int? {
        val matchIndex = clauses.indexOfFirst { it is CypherClause.Match && !it.optional }
        if (matchIndex < 0) return null
        val match = clauses[matchIndex] as CypherClause.Match
        return if (match.patterns.size == 1 && match.where == null) earlyLimitAfterMatch(clauses, matchIndex) else null
    }

    private fun earlyLimitAfterMatch(clauses: List<CypherClause>, matchIndex: Int): Int? {
        val limitIndex = clauses.indexOfFirst { it is CypherClause.Limit }
        if (limitIndex <= matchIndex) return null
        val safe = clauses.subList(matchIndex + 1, limitIndex).all(::preservesEarlyLimit)
        return if (safe) context.integerValue((clauses[limitIndex] as CypherClause.Limit).count, emptyMap()).takeIf { it > 0 } else null
    }

    private fun preservesEarlyLimit(clause: CypherClause): Boolean = when (clause) {
        is CypherClause.Return -> !clause.items.any { rowProjection.containsAggregation(it.expression) } && !clause.distinct
        is CypherClause.Where, is CypherClause.Match, is CypherClause.With, is CypherClause.OrderBy,
        is CypherClause.Skip, is CypherClause.Unwind -> false
        else -> true
    }

}
