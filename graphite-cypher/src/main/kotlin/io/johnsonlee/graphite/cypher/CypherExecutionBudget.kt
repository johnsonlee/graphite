package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal const val CANCELLATION_POLL_MASK = 1_023

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
class CypherExecutionContext(
    val executionBudget: CypherExecutionBudget,
    val cancellationSignal: CypherCancellationSignal
) {
    constructor(executionBudget: CypherExecutionBudget) : this(executionBudget, CypherCancellationSignal())

    internal val workTracker = CypherWorkTracker(executionBudget, cancellationSignal)
}

/** Request-scoped signal used to cooperatively stop Cypher execution. */
class CypherCancellationSignal(
    private val checkObserver: (() -> Unit)? = null
) {
    private val cancelled = AtomicBoolean()

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel(): Boolean = cancelled.compareAndSet(false, true)

    internal fun throwIfCancelled() {
        checkObserver?.invoke()
        if (isCancelled) throw CypherQueryCancelledException()
    }
}

class CypherQueryCancelledException : CancellationException("Cypher query cancelled")

class CypherBudgetExceededException(
    val maxWorkUnits: Long
) : RuntimeException(
    "Cypher work budget exceeded after $maxWorkUnits graph work units; " +
        "add a selective label/filter or use a metadata-backed query"
)

internal class CypherWorkTracker(
    private val budget: CypherExecutionBudget,
    private val cancellationSignal: CypherCancellationSignal = CypherCancellationSignal()
) : GraphWorkConsumer {
    private var remaining = budget.maxWorkUnits

    override fun consume() = consume(1)

    fun checkCancelled() = cancellationSignal.throwIfCancelled()

    fun consume(workUnits: Long) {
        checkCancelled()
        if (workUnits > remaining) {
            remaining = 0
            throw CypherBudgetExceededException(budget.maxWorkUnits)
        }
        remaining -= workUnits
    }
}
