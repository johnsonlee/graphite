package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.Graph

/** Per-pipeline execution state; nested calls restore the caller's parameters and budget. */
internal class PipelineExecutionContext(graph: Graph?, val workTrackingEnabled: Boolean) {
    private val activeWorkTracker = ThreadLocal<CypherWorkTracker?>()
    private val activeParameters = ThreadLocal<Map<String, Any?>>()
    val workTracker: CypherWorkTracker? get() = activeWorkTracker.get()
    val parameters: Map<String, Any?> get() = activeParameters.get().orEmpty()
    val evaluator = ExpressionEvaluator(
        checkCancelled = if (workTrackingEnabled) ::checkCancelled else null,
        parameterResolver = { name -> activeParameters.get()?.get(name) },
        graph = graph
    )

    fun <T> execute(parameters: Map<String, Any?>, tracker: CypherWorkTracker?, block: () -> T): T {
        if (tracker == null && parameters.isEmpty() && activeParameters.get() == null) return block()
        val previousTracker = activeWorkTracker.get()
        val previousParameters = activeParameters.get()
        if (tracker == null) activeWorkTracker.remove() else activeWorkTracker.set(tracker)
        activeParameters.set(parameters.toMap())
        return try {
            block()
        } finally {
            if (previousTracker == null) activeWorkTracker.remove() else activeWorkTracker.set(previousTracker)
            if (previousParameters == null) activeParameters.remove() else activeParameters.set(previousParameters)
        }
    }

    fun stringValue(expression: CypherExpr): String? = when (expression) {
        is CypherExpr.Literal -> expression.value as? String
        is CypherExpr.Parameter -> parameters[expression.name] as? String
        else -> null
    }

    fun pollCancellation(index: Int) {
        if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
    }

    fun checkCancelled() {
        if (workTrackingEnabled) activeWorkTracker.get()?.checkCancelled()
    }

    fun literalLimitCount(expr: CypherExpr): Int? {
        if (expr !is CypherExpr.Literal) return null
        return integerValue(expr, emptyMap())
    }


    fun integerValue(expr: CypherExpr, bindings: Map<String, Any?>): Int {
        val value = evaluator.evaluate(expr, bindings)
        return when (value) {
            is Number -> value.toCypherInt()
            is String -> value.toLongOrNull()
                ?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0
            else -> 0
        }
    }

}
