package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal const val DEFAULT_MAX_CONCURRENT_CYPHER = 4
internal const val DEFAULT_CYPHER_WORK_BUDGET = 1_000_000L
private const val CYPHER_GUARD_CLOSED = "Cypher query guard is closed"

internal class CypherConcurrencyLimitException(maxConcurrent: Int) : RuntimeException(
    "Cypher concurrency limit reached ($maxConcurrent active queries); retry later"
)

internal class CypherQueryGuard(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT_CYPHER,
    maxWorkUnits: Long = DEFAULT_CYPHER_WORK_BUDGET,
    private val performance: CypherPerformanceRecorder = NoOpCypherPerformanceRecorder
) : Closeable {
    private val permits: Semaphore
    private val executionBudget = CypherExecutionBudget(maxWorkUnits)
    private val closed = AtomicBoolean()
    private val executor: ThreadPoolExecutor
    private val active = ConcurrentHashMap.newKeySet<CypherQueryWork<*>>()

    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
        permits = Semaphore(maxConcurrent)
        executor = ThreadPoolExecutor(
            maxConcurrent,
            maxConcurrent,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(maxConcurrent),
            CypherThreadFactory()
        )
    }

    @Suppress("TooGenericExceptionCaught")
    fun <T> execute(block: (CypherExecutionContext) -> T): T {
        check(!closed.get()) { CYPHER_GUARD_CLOSED }
        if (!permits.tryAcquire()) {
            performance.reject()
            throw CypherConcurrencyLimitException(maxConcurrent)
        }
        val startedAtNanos = performance.start()
        var outcome = CypherQueryOutcome.FAILED
        return try {
            block(CypherExecutionContext(executionBudget)).also {
                outcome = CypherQueryOutcome.SUCCESS
            }
        } catch (error: RuntimeException) {
            outcome = error.toQueryOutcome()
            throw error
        } finally {
            performance.stop(startedAtNanos, outcome)
            permits.release()
        }
    }

    fun <T> submit(
        cancellationSignal: CypherCancellationSignal,
        continuation: (Result<T>) -> Unit = {},
        block: (CypherExecutionContext) -> T
    ): CypherQueryTask<T> {
        check(!closed.get()) { CYPHER_GUARD_CLOSED }
        if (!permits.tryAcquire()) {
            performance.reject()
            throw CypherConcurrencyLimitException(maxConcurrent)
        }

        val work = CypherQueryWork(cancellationSignal, block, continuation, performance.start())
        active.add(work)
        try {
            executor.execute(work)
        } catch (error: RejectedExecutionException) {
            performance.reject()
            work.reject(error)
        }
        return CypherQueryTask(work.completion, work::cancel)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.forEach { it.cancel() }
        executor.shutdownNow().filterIsInstance<CypherQueryWork<*>>().forEach {
            it.reject(RejectedExecutionException(CYPHER_GUARD_CLOSED))
        }
    }

    private inner class CypherQueryWork<T>(
        private val cancellationSignal: CypherCancellationSignal,
        private val block: (CypherExecutionContext) -> T,
        private val continuation: (Result<T>) -> Unit,
        private val startedAtNanos: Long
    ) : Runnable {
        val completion = CompletableFuture<T>()
        private val lifecycleLock = Any()
        private val runner = AtomicReference<Thread?>()
        private val finishStarted = AtomicBoolean()
        private val finished = AtomicBoolean()

        override fun run() {
            runner.set(Thread.currentThread())
            val outcome = runCatching {
                if (cancellationSignal.isCancelled) throw CypherQueryCancelledException()
                block(CypherExecutionContext(executionBudget, cancellationSignal)).also {
                    if (cancellationSignal.isCancelled) throw CypherQueryCancelledException()
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { error ->
                    Result.failure(if (cancellationSignal.isCancelled) CypherQueryCancelledException() else error)
                }
            )
            try {
                finish(outcome)
            } finally {
                runner.set(null)
                if (cancellationSignal.isCancelled) Thread.interrupted()
            }
        }

        fun cancel() {
            val shouldInterrupt = synchronized(lifecycleLock) {
                if (finished.get()) {
                    false
                } else {
                    cancellationSignal.cancel()
                    true
                }
            }
            if (shouldInterrupt) runner.get()?.interrupt()
        }

        fun reject(error: Throwable) {
            cancellationSignal.cancel()
            finish(Result.failure(error))
        }

        private fun finish(outcome: Result<T>) {
            if (!finishStarted.compareAndSet(false, true)) return
            val continuationOutcome = synchronized(lifecycleLock) {
                if (cancellationSignal.isCancelled && outcome.isSuccess) {
                    Result.failure(CypherQueryCancelledException())
                } else {
                    outcome
                }
            }
            val continuationResult = runCatching { continuation(continuationOutcome) }.fold(
                onSuccess = { continuationOutcome },
                onFailure = { Result.failure(CypherContinuationException(it)) }
            )
            val (publishedOutcome, queryOutcome) = synchronized(lifecycleLock) {
                val cancellationWon = cancellationSignal.isCancelled && (
                    outcome.isSuccess ||
                        outcome.exceptionOrNull() is CypherQueryCancelledException ||
                        continuationResult.exceptionOrNull().isCancellationFailure()
                    )
                val finalOutcome = if (cancellationWon) {
                    Result.failure(CypherQueryCancelledException())
                } else {
                    continuationResult
                }
                finished.set(true)
                finalOutcome to (finalOutcome.exceptionOrNull()?.toQueryOutcome() ?: CypherQueryOutcome.SUCCESS)
            }
            try {
                active.remove(this)
                performance.stop(startedAtNanos, queryOutcome)
            } finally {
                permits.release()
            }
            publishedOutcome.fold(completion::complete, completion::completeExceptionally)
        }
    }
}

internal class CypherContinuationException(cause: Throwable) : RuntimeException(cause)

internal class CypherQueryTask<T>(
    val completion: CompletableFuture<T>,
    private val cancelAction: () -> Unit
) {
    fun cancel() = cancelAction()
}

private class CypherThreadFactory : ThreadFactory {
    override fun newThread(task: Runnable): Thread = Thread(
        task,
        "graphite-cypher-${NEXT_CYPHER_THREAD.incrementAndGet()}"
    ).apply {
        isDaemon = true
    }
}

private val NEXT_CYPHER_THREAD = AtomicInteger()

private fun Throwable?.isCancellationFailure(): Boolean {
    var current = this
    while (current != null) {
        if (current is CypherQueryCancelledException || current is InterruptedException) return true
        current = current.cause
    }
    return false
}

private fun Throwable.toQueryOutcome(): CypherQueryOutcome = when (this) {
    is CypherQueryCancelledException -> CypherQueryOutcome.CANCELLED
    is CypherBudgetExceededException -> CypherQueryOutcome.BUDGET_EXCEEDED
    else -> CypherQueryOutcome.FAILED
}
