package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionAggregation

private const val STRING_COUNT_CLAUSE_COUNT = 3

/** Streams count aggregation through exact storage or residual predicate evaluation. */
internal class FilteredStringCountExecutor(
    private val sources: List<CypherGraph>,
    private val qualified: Boolean,
    private val context: PipelineExecutionContext,
    private val sourcePlanner: GraphSourcePlanner,
    private val graphAccess: PipelineGraphAccess,
    private val stringScanner: DirectStringScanner
) {
    fun tryFastFilteredStringCount(clauses: List<CypherClause>): CypherResult? {
        if (clauses.size != STRING_COUNT_CLAUSE_COUNT) return null
        return filteredNodeQuery(clauses)?.takeIf(::supportsCountShape)?.let(::executeCount)
    }

    private fun supportsCountShape(shape: FilteredNodeQuery): Boolean {
        val matchSupported = !shape.match.optional && shape.node.variable != null
        val outputSupported = !shape.returned.distinct && shape.returned.items.size == 1
        val nodeSupported = shape.node.labels.size <= 1 && shape.node.properties.isEmpty()
        return matchSupported && outputSupported && nodeSupported
    }

    private fun executeCount(shape: FilteredNodeQuery): CypherResult? {
        val item = shape.returned.items.single()
        val column = item.alias ?: item.expression.toCypherString()
        val nodeClass = resolveNodeClass(shape.node.labels) ?: return CypherResult(listOf(column), listOf(mapOf(column to 0L)))
        return compilePlan(shape, nodeClass)?.let { plan -> executePlan(plan, column) }
    }

    private data class CountOperand(val expression: CypherExpr?, val distinct: Boolean)

    private data class CountPlan(
        val nodeClass: Class<out Node>,
        val variable: String,
        val candidates: DirectStringCandidatePlan,
        val condition: CypherExpr,
        val operand: CountOperand,
        val rawNodes: Boolean,
        val parameters: Map<String, Any?>
    )

    private fun countOperand(expression: CypherExpr): CountOperand? =
        when (expression) {
            CypherExpr.CountStar -> CountOperand(null, distinct = false)
            is CypherExpr.FunctionCall ->
                expression.args
                    .singleOrNull()
                    ?.takeIf { expression.name.equals("count", ignoreCase = true) }
                    ?.let { CountOperand(it, expression.distinct) }
            else -> null
        }

    private fun compilePlan(shape: FilteredNodeQuery, nodeClass: Class<out Node>): CountPlan? {
        val operand = countOperand(shape.returned.items.single().expression) ?: return null
        val variable = checkNotNull(shape.node.variable)
        val parameters = context.parameters
        val condition = shape.where.condition
        return DirectStringCandidatePlan.compile(condition, variable, parameters)?.let { candidates ->
            val exactPredicate =
                DirectStringFilter.compile(condition, variable, parameters) != null ||
                    DirectStringDisjunction.compile(condition, variable, parameters) != null
            CountPlan(
                nodeClass,
                variable,
                candidates,
                condition,
                operand,
                exactPredicate && rawCountOperand(operand.expression, variable),
                parameters)
        }
    }

    private fun rawCountOperand(expression: CypherExpr?, variable: String): Boolean =
        when (expression) {
            null -> true
            is CypherExpr.Property ->
                expression.expression == CypherExpr.Variable(variable) && expression.propertyName !in QUALIFIED_NODE_PROPERTIES
            else -> false
        }

    private fun executePlan(plan: CountPlan, column: String): CypherResult {
        val candidates =
            sourcePlanner.graphIdEquality(plan.condition, plan.variable)?.let { id -> sources.filter { it.id == id } } ?: sources
        val tracker = if (context.workTrackingEnabled) context.workTracker else null
        val parallelism = directStringGraphParallelism(candidates.size)
        val parallelSources = qualified && candidates.size > 1 && parallelism > 1
        val partials =
            if (parallelSources && !isDirectStringWorker) {
                runDirectStringTasks(candidates.map { source -> { countPartial(source, plan, tracker) } }, parallelism)
            } else {
                candidates.map { countPartial(it, plan, tracker) }
            }
        val count =
            if (plan.operand.distinct) {
                val values = HashSet<Any>()
                partials.forEach { values.addAll(it.distinctValues.orEmpty()) }
                values.size.toLong()
            } else partials.sumOf(CountPartial::count)
        val row = mutableMapOf<String, Any?>(column to count)
        if (qualified) {
            partials
                .mapNotNullTo(linkedSetOf()) { partial -> partial.sourceId.takeIf { partial.matchedWhere } }
                .takeIf { it.isNotEmpty() }
                ?.let { row[INTERNAL_PROVENANCE_KEY] = it }
        }
        return CypherResult(listOf(column), listOf(row))
    }

    private fun countPartial(source: CypherGraph, plan: CountPlan, tracker: CypherWorkTracker?): CountPartial {
        if (plan.rawNodes) return exactCountPartial(source, plan, tracker)
        val evaluator =
            if (isDirectStringWorker) {
                ExpressionEvaluator(
                    parameterResolver = plan.parameters::get,
                    graph = source.graph,
                    checkCancelled = { checkThreadInterrupted { CypherQueryCancelledException() } })
            } else context.evaluator
        val bindings = mutableMapOf<String, Any?>(plan.variable to null)
        val count = CountAccumulator(plan.operand)
        var inspected = 0
        for (node in stringScanner.directStringCandidates(source.graph, plan.nodeClass, plan.candidates.candidates, tracker)) {
            bindings[plan.variable] = graphAccess.nodeValue(source, node)
            if (evaluator.evaluate(plan.condition, bindings) == true) {
                val value = plan.operand.expression?.let { evaluator.evaluate(it, bindings) }
                if (count.add(value) && (inspected++ and CANCELLATION_POLL_MASK) == 0) {
                    checkThreadInterrupted { CypherQueryCancelledException() }
                }
            }
        }
        return count.result(source.id)
    }

    private fun exactCountPartial(source: CypherGraph, plan: CountPlan, tracker: CypherWorkTracker?): CountPartial {
        val count = CountAccumulator(plan.operand)
        for ((type, properties) in DIRECT_STRING_NODE_PROPERTIES) {
            if (plan.nodeClass.isAssignableFrom(type)) {
                val filters = plan.candidates.candidates.filters.filter { it.property in properties }
                if (filters.isNotEmpty()) countTypedCandidates(source, plan, type, properties, filters, tracker, count)
            }
        }
        return count.result(source.id)
    }

    private fun countTypedCandidates(
        source: CypherGraph,
        plan: CountPlan,
        type: Class<out Node>,
        properties: Set<String>,
        filters: List<DirectStringFilter>,
        tracker: CypherWorkTracker?,
        count: CountAccumulator
    ) {
        val aggregate =
            if (canAggregateProperty(plan.operand, type, properties, filters)) {
                storageAggregate(source, plan.operand, type, filters, tracker)
            } else null
        if (aggregate != null) {
            count.addStorage(aggregate)
        } else {
            val property = (plan.operand.expression as? CypherExpr.Property)?.propertyName
            for (node in stringScanner.directStringCandidates(source.graph, type, DirectStringDisjunction(filters), tracker)) {
                val value = property?.let { NodePropertyAccessor.getProperty(node, it, source.graph) }
                count.add(value)
            }
        }
    }

    private fun canAggregateProperty(
        operand: CountOperand,
        type: Class<out Node>,
        properties: Set<String>,
        filters: List<DirectStringFilter>
    ): Boolean {
        val property = (operand.expression as? CypherExpr.Property)?.propertyName
        val supported = operand.expression == null || property in properties
        val scalarValue = property != NODE_VALUE_PROPERTY || type == StringConstant::class.java
        if (!supported || !scalarValue) return false
        val annotationValue =
            when {
                type != AnnotationNode::class.java -> true
                operand.expression == null -> true
                else -> property == NODE_CLASS_PROPERTY || property == "name"
            }
        val coercesAnnotation = type == AnnotationNode::class.java && filters.any { it.coercesToString }
        return annotationValue && !coercesAnnotation
    }

    private fun storageAggregate(
        source: CypherGraph,
        operand: CountOperand,
        type: Class<out Node>,
        filters: List<DirectStringFilter>,
        tracker: CypherWorkTracker?
    ): StringPropertyDisjunctionAggregate? {
        val storage = source.graph as? StringPropertyDisjunctionAggregation
        val property = (operand.expression as? CypherExpr.Property)?.propertyName?.takeIf { operand.distinct }
        val predicates = filters.map { StringPropertyPredicate(it.property, it.transform, it.mode, it.expected) }
        return if (tracker == null) storage?.aggregateStringPropertyDisjunction(type, predicates, property)
        else
            (storage as? WorkAwareStringPropertyDisjunctionAggregation)?.aggregateStringPropertyDisjunction(
                type, predicates, property, tracker)
    }

    private class CountAccumulator(private val operand: CountOperand) {
        private var count = 0L
        private var matchedWhere = false
        private val values = if (operand.distinct) HashSet<Any>() else null

        /** False means a null counted value, which must retain the original polling boundary. */
        fun add(value: Any?): Boolean {
            matchedWhere = true
            if (operand.expression != null && value == null) return false
            if (operand.expression == null || values == null || values.add(cypherValueKey(value))) count++
            return true
        }

        fun addStorage(aggregate: StringPropertyDisjunctionAggregate) {
            if (aggregate.count > 0L) matchedWhere = true
            if (values == null) count += aggregate.count else aggregate.distinctValues.orEmpty().mapTo(values, ::cypherValueKey)
        }

        fun result(source: String): CountPartial = CountPartial(source, values?.size?.toLong() ?: count, values, matchedWhere)
    }

    private data class CountPartial(val sourceId: String, val count: Long, val distinctValues: Set<Any>?, val matchedWhere: Boolean)
}
