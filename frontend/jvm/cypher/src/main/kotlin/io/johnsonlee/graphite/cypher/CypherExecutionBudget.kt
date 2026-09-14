package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val CANCELLATION_POLL_MASK = 1_023

/**
 * Bounds graph work performed by one Cypher execution.
 *
 * A work unit is one graph or metadata candidate inspected, or one path element
 * materialized by the query pipeline.
 */
data class CypherExecutionBudget(val maxWorkUnits: Long) {
    init {
        require(maxWorkUnits > 0) { "maxWorkUnits must be positive" }
    }
}

/**
 * Shares one work counter across sequential Cypher executions in a logical request.
 * A context is request-scoped: callers must not start separate executions concurrently,
 * while one execution may safely share its atomic tracker with internal scan workers.
 */
class CypherExecutionContext(
    val executionBudget: CypherExecutionBudget,
    val cancellationSignal: CypherCancellationSignal
) {
    constructor(executionBudget: CypherExecutionBudget) : this(executionBudget, CypherCancellationSignal())

    internal val workTracker = CypherWorkTracker(executionBudget, cancellationSignal)

    /**
     * Return cumulative planner and work counters for the executions sharing this context.
     *
     * A recognized graphId predicate is deliberately separate from an actual source prune:
     * selecting the sole source of an API-scoped request does not count as a routing gain.
     */
    val diagnostics: CypherExecutionDiagnostics
        get() = workTracker.diagnostics()
}

/** Cumulative, request-scoped Cypher planner and graph-work counters. */
data class CypherExecutionDiagnostics(
    val graphIdSourceSelections: Long,
    val graphIdSourcePruningExecutions: Long,
    val graphIdSourcesPruned: Long,
    val graphIdSourceConflicts: Long,
    val fastPathExecutions: Long,
    val filteredNodeLimitFastPathExecutions: Long,
    val generalFallbackExecutions: Long,
    val workUnitsConsumed: Long
)

/** Request-scoped signal used to cooperatively stop Cypher execution. */
class CypherCancellationSignal(
    private val checkObserver: (() -> Unit)? = null
) {
    private val cancelled = AtomicBoolean()
    private val cancellation = AtomicReference<CypherQueryCancelledException?>()

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel(): Boolean = cancel(CypherQueryCancelledException())

    fun cancel(reason: CypherQueryCancelledException): Boolean {
        if (!cancellation.compareAndSet(null, reason)) return false
        cancelled.set(true)
        return true
    }

    fun cancellationException(): CypherQueryCancelledException =
        cancellation.get() ?: CypherQueryCancelledException()

    fun throwIfCancelled() {
        checkObserver?.invoke()
        if (isCancelled) throw cancellationException()
    }
}

open class CypherQueryCancelledException(message: String = "Cypher query cancelled") : CancellationException(message)

class CypherQueryTimeoutException(val timeoutMillis: Long) :
    CypherQueryCancelledException("Cypher query timed out after $timeoutMillis ms")

class CypherBudgetExceededException(
    val maxWorkUnits: Long
) : RuntimeException(
    "Cypher work budget exceeded after $maxWorkUnits graph work units; " +
        "add a selective label/filter or use a metadata-backed query"
)

internal class CypherWorkTracker(
    private val budget: CypherExecutionBudget,
    private val cancellationSignal: CypherCancellationSignal = CypherCancellationSignal()
) : GraphWorkBatchConsumer {
    private val remaining = AtomicLong(budget.maxWorkUnits)
    private val graphIdSourceSelections = AtomicLong()
    private val graphIdSourcePruningExecutions = AtomicLong()
    private val graphIdSourcesPruned = AtomicLong()
    private val graphIdSourceConflicts = AtomicLong()
    private val fastPathExecutions = AtomicLong()
    private val filteredNodeLimitFastPathExecutions = AtomicLong()
    private val generalFallbackExecutions = AtomicLong()

    override fun consume() = consume(1)

    fun checkCancelled() = cancellationSignal.throwIfCancelled()

    override fun consume(workUnits: Long) {
        require(workUnits >= 0) { "workUnits must be non-negative" }
        checkCancelled()
        while (true) {
            val available = remaining.get()
            if (workUnits > available) {
                if (remaining.compareAndSet(available, 0)) {
                    throw CypherBudgetExceededException(budget.maxWorkUnits)
                }
                continue
            }
            if (remaining.compareAndSet(available, available - workUnits)) return
        }
    }

    fun recordGraphIdSourceSelection(initialSourceCount: Int, selectedSourceCount: Int, conflicting: Boolean) {
        require(initialSourceCount >= 0 && selectedSourceCount in 0..initialSourceCount)
        graphIdSourceSelections.incrementAndGet()
        if (selectedSourceCount < initialSourceCount) {
            graphIdSourcePruningExecutions.incrementAndGet()
            graphIdSourcesPruned.addAndGet((initialSourceCount - selectedSourceCount).toLong())
        }
        if (conflicting) graphIdSourceConflicts.incrementAndGet()
    }

    fun recordFastPath() {
        fastPathExecutions.incrementAndGet()
    }

    fun recordFilteredNodeLimitFastPath() {
        fastPathExecutions.incrementAndGet()
        filteredNodeLimitFastPathExecutions.incrementAndGet()
    }

    fun recordGeneralFallback() {
        generalFallbackExecutions.incrementAndGet()
    }

    fun diagnostics(): CypherExecutionDiagnostics = CypherExecutionDiagnostics(
        graphIdSourceSelections = graphIdSourceSelections.get(),
        graphIdSourcePruningExecutions = graphIdSourcePruningExecutions.get(),
        graphIdSourcesPruned = graphIdSourcesPruned.get(),
        graphIdSourceConflicts = graphIdSourceConflicts.get(),
        fastPathExecutions = fastPathExecutions.get(),
        filteredNodeLimitFastPathExecutions = filteredNodeLimitFastPathExecutions.get(),
        generalFallbackExecutions = generalFallbackExecutions.get(),
        workUnitsConsumed = budget.maxWorkUnits - remaining.get()
    )
}
