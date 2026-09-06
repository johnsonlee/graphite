package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import io.johnsonlee.graphite.cypher.CypherQueryTimeoutException
import io.johnsonlee.graphite.graph.GraphTask
import io.johnsonlee.graphite.graph.GraphTaskGroup
import io.johnsonlee.graphite.graph.GraphTaskContext
import io.johnsonlee.graphite.graph.GraphTaskScheduler
import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val DEFAULT_MAX_CONCURRENT_CYPHER = 4
internal const val DEFAULT_CYPHER_WORK_BUDGET = 1_000_000L
internal const val DEFAULT_CYPHER_MAX_TIMEOUT_MILLIS = 60_000L
private const val CYPHER_GUARD_CLOSED = "Cypher query guard is closed"

internal class CypherConcurrencyLimitException(maxConcurrent: Int) : RuntimeException(
    "Cypher concurrency limit reached ($maxConcurrent active queries); retry later"
)

internal class CypherQueryGuard(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT_CYPHER,
    maxWorkUnits: Long = DEFAULT_CYPHER_WORK_BUDGET,
    private val performance: CypherPerformanceRecorder = NoOpCypherPerformanceRecorder,
    private val maxTimeoutMillis: Long = DEFAULT_CYPHER_MAX_TIMEOUT_MILLIS
) : Closeable {
    private val permits: Semaphore
    private val executionBudget = CypherExecutionBudget(maxWorkUnits)
    private val closed = AtomicBoolean()
    private val timeoutExecutor: ScheduledThreadPoolExecutor
    private val active = ConcurrentHashMap.newKeySet<CypherQueryWork<*>>()

    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
        require(maxTimeoutMillis > 0) { "maxTimeoutMillis must be positive" }
        permits = Semaphore(maxConcurrent)
        timeoutExecutor = ScheduledThreadPoolExecutor(1, CypherTimeoutThreadFactory()).apply {
            removeOnCancelPolicy = true
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun <T> execute(block: (CypherExecutionContext) -> T): T {
        check(!closed.get()) { CYPHER_GUARD_CLOSED }
        if (!permits.tryAcquire()) {
            performance.reject()
            throw CypherConcurrencyLimitException(maxConcurrent)
        }
        val startedAtNanos = startWithPermit()
        var outcome = CypherQueryOutcome.FAILED
        var failure: Throwable? = null
        return try {
            executeOnSharedScheduler(block).also { outcome = CypherQueryOutcome.SUCCESS }
        } catch (error: Throwable) {
            failure = error
            outcome = error.toQueryOutcome()
            throw error
        } finally {
            try {
                val primaryFailure = failure
                if (primaryFailure == null) performance.stop(startedAtNanos, outcome)
                else preserveQueryFailure(primaryFailure) { performance.stop(startedAtNanos, outcome) }
            } finally {
                permits.release()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startWithPermit(): Long = try {
        performance.start()
    } catch (error: Throwable) {
        permits.release()
        throw error
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount", "ReturnCount")
    private fun <T> executeOnSharedScheduler(block: (CypherExecutionContext) -> T): T {
        val context = CypherExecutionContext(executionBudget)
        val current = GraphTaskContext.current
        if (current != null) {
            if (current.isAcceptingChildren) return block(context)
            // CompletableFuture dependents run on the publishing worker after its request scope
            // closes. Give a new query a fresh scope without acquiring another physical worker.
            val inlineGroup = GraphTaskScheduler.shared.newGroup<T>(
                backgroundParallelism = 0,
                parentContext = null,
                role = current.role
            )
            try {
                return inlineGroup.runInline(Callable { block(context) })
            } finally {
                inlineGroup.close()
            }
        }
        val group = GraphTaskScheduler.shared.newRequestGroup<T>()
        try {
            return group.submit(Callable { block(context) }).get()
        } catch (_: InterruptedException) {
            context.cancellationSignal.cancel()
            group.cancelAndJoin()
            Thread.currentThread().interrupt()
            throw context.cancellationSignal.cancellationException()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } finally {
            group.close()
        }
    }

    @Suppress("TooGenericExceptionCaught", "ThrowsCount", "InstanceOfCheckForException")
    fun <T> submit(
        cancellationSignal: CypherCancellationSignal,
        clientTimeoutMillis: Long? = null,
        continuation: (Result<T>) -> Unit = {},
        block: (CypherExecutionContext) -> T
    ): CypherQueryTask<T> {
        check(!closed.get()) { CYPHER_GUARD_CLOSED }
        require(clientTimeoutMillis == null || clientTimeoutMillis > 0) { "clientTimeoutMillis must be positive" }
        val effectiveTimeoutMillis = minOf(clientTimeoutMillis ?: maxTimeoutMillis, maxTimeoutMillis)
        if (!permits.tryAcquire()) {
            performance.reject()
            throw CypherConcurrencyLimitException(maxConcurrent)
        }

        val startedAtNanos = startWithPermit()
        val work = try {
            CypherQueryWork(cancellationSignal, block, continuation, startedAtNanos)
        } catch (error: Throwable) {
            try {
                preserveQueryFailure(error) { performance.stop(startedAtNanos, CypherQueryOutcome.FAILED) }
            } finally {
                permits.release()
            }
            throw error
        }
        var group: GraphTaskGroup<Unit>? = null
        try {
            active.add(work)
            if (closed.get()) throw RejectedExecutionException(CYPHER_GUARD_CLOSED)
            work.bindTimeout(
                timeoutExecutor.schedule(
                    { work.timeout(effectiveTimeoutMillis) },
                    effectiveTimeoutMillis,
                    TimeUnit.MILLISECONDS
                )
            )
            // Each request owns one short-lived group; a guard must not retain historical tasks.
            val requestGroup = GraphTaskScheduler.shared.newRequestGroup<Unit>()
            group = requestGroup
            work.bindTask(requestGroup.submit(Callable { work.run() }))
        } catch (error: Throwable) {
            preserveQueryFailure(error) { group?.cancelAndJoin() }
            preserveQueryFailure(error) { group?.close() }
            if (error is RejectedExecutionException) {
                preserveQueryFailure(error) { performance.reject() }
            }
            work.reject(error)
        }
        return CypherQueryTask(work.completion, work::cancel)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.forEach { it.close() }
        timeoutExecutor.shutdownNow()
    }

    private inner class CypherQueryWork<T>(
        private val cancellationSignal: CypherCancellationSignal,
        private val block: (CypherExecutionContext) -> T,
        private val continuation: (Result<T>) -> Unit,
        private val startedAtNanos: Long
    ) : Runnable {
        val completion = CompletableFuture<T>()
        private val lifecycleLock = Any()
        private var scheduledTask: GraphTask<Unit>? = null
        private var runStarted = false
        private var requestContext: GraphTaskContext? = null
        private var rejectedBeforeStart = false
        private val timeoutFuture = AtomicReference<ScheduledFuture<*>?>()
        private val finishStarted = AtomicBoolean()
        private val finished = AtomicBoolean()

        override fun run() {
            synchronized(lifecycleLock) {
                if (rejectedBeforeStart) return
                runStarted = true
                requestContext = GraphTaskContext.current
            }
            val outcome = runCatching {
                if (cancellationSignal.isCancelled) throw cancellationException()
                block(CypherExecutionContext(executionBudget, cancellationSignal)).also {
                    if (cancellationSignal.isCancelled) throw cancellationException()
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { error ->
                    Result.failure(if (cancellationSignal.isCancelled) cancellationException() else error)
                }
            )
            try {
                finish(outcome)
            } finally {
                if (cancellationSignal.isCancelled) Thread.interrupted()
            }
        }

        fun bindTask(task: GraphTask<Unit>) = synchronized(lifecycleLock) {
            scheduledTask = task
            if (rejectedBeforeStart) {
                task.cancel(false)
            } else if (!finished.get() && runStarted && cancellationSignal.isCancelled) {
                task.cancel(true)
            }
        }

        fun bindTimeout(future: ScheduledFuture<*>) {
            timeoutFuture.set(future)
            if (finished.get()) timeoutFuture.getAndSet(null)?.cancel(false)
        }

        fun cancel() = cancel(CypherQueryCancelledException())

        fun timeout(timeoutMillis: Long) = cancel(CypherQueryTimeoutException(timeoutMillis))

        private fun cancel(error: CypherQueryCancelledException) = synchronized(lifecycleLock) {
            if (!finished.get()) {
                cancellationSignal.cancel(error)
                // Queued cancellation still runs the wrapper that owns continuation/permit teardown.
                // A running task is interrupted through its scheduler lease, never a bare Thread.
                if (runStarted) scheduledTask?.cancel(true)
            }
        }

        fun close() {
            val rejectQueued = synchronized(lifecycleLock) {
                if (finished.get()) return
                cancellationSignal.cancel()
                if (runStarted) {
                    scheduledTask?.cancel(true)
                    false
                } else {
                    rejectedBeforeStart = true
                    scheduledTask?.cancel(false)
                    true
                }
            }
            if (rejectQueued) finish(Result.failure(RejectedExecutionException(CYPHER_GUARD_CLOSED)))
        }

        fun reject(error: Throwable) {
            synchronized(lifecycleLock) {
                rejectedBeforeStart = true
                cancellationSignal.cancel()
                scheduledTask?.cancel(false)
            }
            finish(Result.failure(error))
        }

        @Suppress("TooGenericExceptionCaught")
        private fun finish(outcome: Result<T>) {
            if (!finishStarted.compareAndSet(false, true)) return
            val continuationOutcome = synchronized(lifecycleLock) {
                if (cancellationSignal.isCancelled && outcome.isSuccess) {
                    Result.failure(cancellationException())
                } else {
                    outcome
                }
            }
            var continuationResult = runCatching { continuation(continuationOutcome) }.fold(
                onSuccess = { continuationOutcome },
                onFailure = { Result.failure(CypherContinuationException(it)) }
            )
            // Completion and admission release follow actual descendant exit, including callbacks.
            requestContext?.finishChildren(continuationResult.exceptionOrNull())?.let { childFailure ->
                continuationResult = Result.failure(childFailure)
            }
            val (publishedOutcome, queryOutcome) = synchronized(lifecycleLock) {
                val cancellationWon = cancellationSignal.isCancelled && (
                    outcome.isSuccess ||
                        outcome.exceptionOrNull() is CypherQueryCancelledException ||
                        continuationResult.exceptionOrNull().isCancellationFailure()
                    )
                val finalOutcome = if (cancellationWon) {
                    Result.failure(cancellationException())
                } else {
                    continuationResult
                }
                finished.set(true)
                finalOutcome to (finalOutcome.exceptionOrNull()?.toQueryOutcome() ?: CypherQueryOutcome.SUCCESS)
            }
            var publication = publishedOutcome
            try {
                timeoutFuture.getAndSet(null)?.cancel(false)
                performance.stop(startedAtNanos, queryOutcome)
            } catch (cleanupFailure: Throwable) {
                val primaryFailure = publication.exceptionOrNull()
                if (primaryFailure == null) publication = Result.failure(cleanupFailure)
                else if (primaryFailure !== cleanupFailure) primaryFailure.addSuppressed(cleanupFailure)
            } finally {
                active.remove(this)
                permits.release()
            }
            publication.fold(completion::complete, completion::completeExceptionally)
        }

        private fun cancellationException(): CypherQueryCancelledException =
            cancellationSignal.cancellationException()
    }
}

internal class CypherContinuationException(cause: Throwable) : RuntimeException(cause)

internal class CypherQueryTask<T>(
    val completion: CompletableFuture<T>,
    private val cancelAction: () -> Unit
) {
    fun cancel() = cancelAction()
}

@Suppress("TooGenericExceptionCaught")
private inline fun preserveQueryFailure(failure: Throwable, cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (cleanupFailure: Throwable) {
        if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
    }
}

private class CypherTimeoutThreadFactory : ThreadFactory {
    override fun newThread(task: Runnable): Thread = Thread(task, "graphite-cypher-timeout").apply {
        isDaemon = true
    }
}

private fun Throwable?.isCancellationFailure(): Boolean {
    var current = this
    while (current != null) {
        if (current is CypherQueryCancelledException || current is CypherQueryTimeoutException ||
            current is InterruptedException
        ) return true
        current = current.cause
    }
    return false
}

private fun Throwable.toQueryOutcome(): CypherQueryOutcome = when (this) {
    is CypherQueryTimeoutException -> CypherQueryOutcome.TIMEOUT
    is CypherQueryCancelledException -> CypherQueryOutcome.CANCELLED
    is CypherBudgetExceededException -> CypherQueryOutcome.BUDGET_EXCEEDED
    else -> CypherQueryOutcome.FAILED
}
