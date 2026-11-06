package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.checkThreadInterrupted

private const val FILTERED_LIMIT_CLAUSE_COUNT = 4

/** Plans bounded single-node predicates, preserving residual checks and DISTINCT provenance. */
internal class FilteredNodeLimitExecutor(
    private val context: PipelineExecutionContext,
    private val sourcePlanner: GraphSourcePlanner,
    private val graphAccess: PipelineGraphAccess,
    private val rowOperations: CypherRowOperations,
    private val rowProjection: CypherProjection,
    private val nodeMatcher: NodePatternMatcher,
    private val stringScanPolicy: StringScanPolicy,
    private val directStrings: DirectStringExecutor,
    private val graphSourceScopeApplied: Boolean
) {
    private val sources get() = graphAccess.sources

    private data class LimitQuery(
        val shape: FilteredNodeQuery,
        val variable: String,
        val limit: Int,
        val columns: List<String>,
        val nodeClass: Class<out Node>?
    )

    private data class RoutedQuery(
        val sources: List<CypherGraph>,
        val condition: CypherExpr,
        val persisted: Boolean,
        val mapped: Boolean
    )

    fun tryExecute(clauses: List<CypherClause>, preselectedSources: List<CypherGraph>? = null): CypherResult? {
        if (clauses.size != FILTERED_LIMIT_CLAUSE_COUNT) return null
        val limit = clauses.last() as? CypherClause.Limit
        return filteredNodeQuery(clauses)?.takeIf(::supportedShape)?.let { shape ->
            limit?.let { compileQuery(shape, it) }?.let { executeQuery(it, preselectedSources) }
        }
    }

    private fun supportedShape(shape: FilteredNodeQuery): Boolean = !shape.match.optional &&
        shape.returned.items.none { rowProjection.containsAggregation(it.expression) } &&
        shape.returned.items.none { (it.expression as? CypherExpr.Variable)?.name == "*" }

    private fun compileQuery(shape: FilteredNodeQuery, limit: CypherClause.Limit): LimitQuery? {
        val variable = shape.node.variable ?: return null
        return context.literalLimitCount(limit.count)?.let {
            LimitQuery(shape, variable, it, shape.returned.items.map { item -> item.alias ?: item.expression.toCypherString() },
                resolveNodeClass(shape.node.labels))
        }
    }

    private fun executeQuery(query: LimitQuery, preselected: List<CypherGraph>?): CypherResult? {
        if (query.limit <= 0 || query.nodeClass == null) return CypherResult(query.columns, emptyList())
        val route = route(query, preselected)
        return if (requiresNormalizedAnnotationValues(query, route)) null else {
            directStringResult(query, route) ?: scan(query, route)
        }
    }

    private fun route(query: LimitQuery, preselected: List<CypherGraph>?): RoutedQuery {
        val graphRoute = sourcePlanner.conjunctiveGraphIdRoute(query.shape.where.condition, query.variable)
        val graphIds = graphRoute.graphIds
        val candidates = preselected ?: graphIds?.let { ids -> sources.filter { it.id in ids } } ?: sources
        val condition = graphIds?.takeUnless { graphRoute.conflicting }?.let { graphRoute.residual } ?: query.shape.where.condition
        val scoped = graphSourceScopeApplied || preselected != null || graphIds != null
        return RoutedQuery(candidates, condition, scoped, usesBalancedStringSplit(candidates.size))
    }

    private fun requiresNormalizedAnnotationValues(query: LimitQuery, route: RoutedQuery): Boolean {
        if (!query.shape.returned.distinct || !checkNotNull(query.nodeClass).isAssignableFrom(AnnotationNode::class.java)) return false
        return route.sources.any { it.graph.nodeCount(AnnotationNode::class.java) != 0L } &&
            DirectStringCandidatePlan.compile(route.condition, query.variable, context.parameters) != null
    }

    private fun directStringResult(query: LimitQuery, route: RoutedQuery): CypherResult? {
        if (query.shape.node.labels.size > 1 || query.shape.node.properties.isNotEmpty()) return null
        val filter = DirectStringFilter.compile(route.condition, query.variable, context.parameters)
        return when {
            filter != null && !query.shape.returned.distinct -> directStrings.executeDirectStringFilter(
                spec(query, DirectStringDisjunction(listOf(filter))), query.limit, route.sources, route.persisted)
            filter != null && stringScanPolicy.hasTypedDirectStringCandidate(checkNotNull(query.nodeClass), filter) ->
                executeStringPlan(query, route, DirectStringDisjunction(listOf(filter)))
            else -> disjunctiveResult(query, route) ?: conjunctiveResult(query, route) ?: residualResult(query, route)
        }
    }

    private fun disjunctiveResult(query: LimitQuery, route: RoutedQuery): CypherResult? =
        DirectStringDisjunction.compile(route.condition, query.variable, context.parameters)
            ?.let { executeStringPlan(query, route, it) }

    private fun conjunctiveResult(query: LimitQuery, route: RoutedQuery): CypherResult? =
        DirectStringConjunction.compile(route.condition, query.variable, context.parameters)?.let { conjunction ->
            val factory: DirectNodePredicateFactory = { source -> { node -> conjunction.matches(node, source.graph) } }
            executeStringPlan(query, route, DirectStringDisjunction(listOf(conjunction.required)), factory)
        }

    private fun residualResult(query: LimitQuery, route: RoutedQuery): CypherResult? =
        DirectStringCandidatePlan.compile(route.condition, query.variable, context.parameters)?.let { plan ->
            executeStringPlan(query, route, plan.candidates, residualPredicate(query.variable, route.condition))
        }

    private fun residualPredicate(variable: String, condition: CypherExpr): DirectNodePredicateFactory {
        val parameters = context.parameters
        val tracker = if (context.workTrackingEnabled) context.workTracker else null
        return { source ->
            val bindings = mutableMapOf<String, Any?>(variable to null)
            val evaluator = ExpressionEvaluator(parameterResolver = parameters::get, graph = source.graph, checkCancelled = {
                tracker?.checkCancelled()
                checkThreadInterrupted { CypherQueryCancelledException() }
            })
            val predicate: (Node) -> Boolean = { node ->
                bindings[variable] = graphAccess.nodeValue(source, node)
                evaluator.evaluate(condition, bindings) == true
            }
            predicate
        }
    }

    private fun spec(query: LimitQuery, filter: DirectStringDisjunction): StringProjectionSpec =
        StringProjectionSpec(checkNotNull(query.nodeClass), query.variable, filter, query.shape.returned.items, query.columns)

    private fun executeStringPlan(
        query: LimitQuery,
        route: RoutedQuery,
        filter: DirectStringDisjunction,
        predicate: DirectNodePredicateFactory? = null
    ): CypherResult = if (query.shape.returned.distinct) {
        directStrings.executeDirectStringDisjunction(spec(query, filter), query.limit, predicate, route.sources, route.mapped)
    } else {
        directStrings.executeDirectStringDisjunctionRows(spec(query, filter), query.limit, predicate, route.sources, route.persisted)
    }

    private fun scan(query: LimitQuery, route: RoutedQuery): CypherResult {
        val rows = mutableListOf<Map<String, Any?>>()
        val distinct = if (query.shape.returned.distinct) LinkedHashMap<Any, MutableMap<String, Any?>>() else null
        val bindings = mutableMapOf<String, Any?>(query.variable to null)
        val nodeClass = checkNotNull(query.nodeClass)
        val propertyCandidates = if (query.shape.node.properties.isEmpty()) {
            graphAccess.qualifiedIdCandidates(nodeClass, route.condition, query.variable, route.sources)
                ?: graphAccess.dynamicPropertyCandidates(nodeClass, route.condition, query.variable, route.sources)
        } else null
        for (candidate in propertyCandidates ?: graphAccess.nodeCandidates(nodeClass, route.sources)) {
            val projected = projectCandidate(query, candidate, bindings)
            if (projected != null) {
                val complete = if (distinct == null) {
                    rows.add(projected)
                    rows.size >= query.limit
                } else {
                    CypherDistinctRows.addCypherDistinctRow(distinct, projected, query.limit)
                    !graphAccess.qualified && distinct.size >= query.limit
                }
                if (complete) break
            }
        }
        return CypherResult(query.columns, distinct?.values?.toList() ?: rows)
    }

    private fun projectCandidate(query: LimitQuery, candidate: Any, predicateBindings: MutableMap<String, Any?>): Map<String, Any?>? {
        if (!nodeMatcher.matchesNodeConstraints(candidate, query.shape.node, emptyMap())) return null
        predicateBindings[query.variable] = candidate
        return if (context.evaluator.evaluate(query.shape.where.condition, predicateBindings) == true) {
            val bindings = mutableMapOf<String, Any?>(query.variable to candidate)
            rowOperations.addProvenance(bindings, candidate)
            rowProjection.projectRow(query.shape.returned.items, query.columns, bindings)
        } else null
    }
}
