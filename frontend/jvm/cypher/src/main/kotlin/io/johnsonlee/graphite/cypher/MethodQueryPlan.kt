package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodPattern

internal fun methodColumns(ret: CypherClause.Return): List<String> =
    ret.items.map { it.alias ?: it.expression.toCypherString() }

internal fun saturatedMethodAdd(left: Int, right: Int): Int =
    (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun containsMethodAggregation(expression: CypherExpr): Boolean = when (expression) {
    is CypherExpr.CountStar -> true
    is CypherExpr.FunctionCall -> CypherFunctions.isAggregation(expression.name) ||
        expression.args.any(::containsMethodAggregation)
    is CypherExpr.Property -> containsMethodAggregation(expression.expression)
    is CypherExpr.BinaryOp -> containsMethodAggregation(expression.left) || containsMethodAggregation(expression.right)
    is CypherExpr.Comparison -> containsMethodAggregation(expression.left) || containsMethodAggregation(expression.right)
    is CypherExpr.Distinct -> containsMethodAggregation(expression.expression)
    else -> false
}

internal data class MethodScanPlan(
    val pattern: MethodPattern,
    val fullyPushed: Boolean,
    val sourceGraphId: String?
)

internal data class MethodExecution(
    val ret: CypherClause.Return,
    val variable: String,
    val where: CypherClause.Where?,
    val scan: MethodScanPlan,
    val sources: List<CypherGraph>,
    val qualified: Boolean,
    val checkCancellation: () -> Unit,
    val workTracker: CypherWorkTracker?
) {
    val columns = ret.items.map { it.alias ?: it.expression.toCypherString() }

    fun checkCancelled() {
        if (workTracker == null) checkCancellation() else workTracker.checkCancelled()
    }

    fun newEvaluator(): ExpressionEvaluator = ExpressionEvaluator(::checkCancelled)

    fun binding(source: CypherGraph, method: MethodDescriptor): MutableMap<String, Any?> =
        mutableMapOf<String, Any?>(
            variable to MethodValue(source.id.takeIf { qualified }, method, source.graph)
        ).apply {
            if (qualified) put(INTERNAL_PROVENANCE_KEY, setOf(source.id))
        }

    fun matches(binding: Map<String, Any?>, evaluator: ExpressionEvaluator): Boolean =
        scan.fullyPushed || where == null || evaluator.evaluate(where.condition, binding) == true

    fun project(
        binding: Map<String, Any?>,
        evaluator: ExpressionEvaluator
    ): MutableMap<String, Any?> =
        mutableMapOf<String, Any?>().apply {
            ret.items.forEachIndexed { index, item ->
                put(columns[index], evaluator.evaluate(item.expression, binding))
            }
            binding[INTERNAL_PROVENANCE_KEY]?.let { put(INTERNAL_PROVENANCE_KEY, it) }
        }
}


internal data class CompiledMethodQuery(
    val returned: CypherClause.Return,
    val variable: String,
    val where: CypherClause.Where?,
    val scan: MethodScanPlan,
    val skip: Int,
    val limit: Int,
    val orderBy: CypherClause.OrderBy?
)
