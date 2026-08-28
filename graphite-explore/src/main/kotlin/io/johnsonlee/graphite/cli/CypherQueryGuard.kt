package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import java.util.concurrent.Semaphore

internal const val DEFAULT_MAX_CONCURRENT_CYPHER = 2
internal const val DEFAULT_CYPHER_WORK_BUDGET = 250_000L

internal class CypherConcurrencyLimitException(maxConcurrent: Int) : RuntimeException(
    "Cypher concurrency limit reached ($maxConcurrent active queries); retry later"
)

internal class CypherQueryGuard(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT_CYPHER,
    maxWorkUnits: Long = DEFAULT_CYPHER_WORK_BUDGET
) {
    private val permits: Semaphore
    val executionBudget = CypherExecutionBudget(maxWorkUnits)

    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
        permits = Semaphore(maxConcurrent)
    }

    fun <T> execute(block: (CypherExecutionBudget) -> T): T {
        if (!permits.tryAcquire()) throw CypherConcurrencyLimitException(maxConcurrent)
        return try {
            block(executionBudget)
        } finally {
            permits.release()
        }
    }
}
