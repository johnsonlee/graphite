package io.johnsonlee.graphite.cypher

/**
 * Bounds graph work performed by one Cypher execution.
 *
 * A work unit is one node candidate or relationship candidate read by the
 * generic query pipeline. Metadata fast paths do not consume work units.
 */
data class CypherExecutionBudget(val maxWorkUnits: Long) {
    init {
        require(maxWorkUnits > 0) { "maxWorkUnits must be positive" }
    }
}

class CypherBudgetExceededException(
    val maxWorkUnits: Long
) : RuntimeException(
    "Cypher work budget exceeded after $maxWorkUnits node or relationship visits; " +
        "add a selective label/filter or use a metadata-backed query"
)

internal class CypherWorkTracker(private val budget: CypherExecutionBudget) {
    private var consumed = 0L

    fun consume() {
        if (consumed >= budget.maxWorkUnits) {
            throw CypherBudgetExceededException(budget.maxWorkUnits)
        }
        consumed++
    }
}
