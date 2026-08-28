package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.GraphWorkConsumer

/**
 * Bounds graph work performed by one Cypher execution.
 *
 * A work unit is one graph candidate inspected or one path element materialized
 * by the query pipeline. Metadata fast paths do not consume work units.
 */
data class CypherExecutionBudget(val maxWorkUnits: Long) {
    init {
        require(maxWorkUnits > 0) { "maxWorkUnits must be positive" }
    }
}

/**
 * Shares one work counter across sequential Cypher executions in a logical request.
 * A context is request-scoped and must not be used concurrently.
 */
class CypherExecutionContext(val executionBudget: CypherExecutionBudget) {
    internal val workTracker = CypherWorkTracker(executionBudget)
}

class CypherBudgetExceededException(
    val maxWorkUnits: Long
) : RuntimeException(
    "Cypher work budget exceeded after $maxWorkUnits graph work units; " +
        "add a selective label/filter or use a metadata-backed query"
)

internal class CypherWorkTracker(
    private val budget: CypherExecutionBudget
) : GraphWorkConsumer {
    private var remaining = budget.maxWorkUnits

    override fun consume() = consume(1)

    fun consume(workUnits: Long) {
        if (workUnits > remaining) {
            remaining = 0
            throw CypherBudgetExceededException(budget.maxWorkUnits)
        }
        remaining -= workUnits
    }
}
