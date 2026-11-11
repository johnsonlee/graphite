package io.johnsonlee.graphite.cypher

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal val METHOD_GRAPH_SCAN_PARALLELISM: Int =
    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private val methodGraphScanPool = ForkJoinPool(METHOD_GRAPH_SCAN_PARALLELISM)

@Suppress("ThrowsCount", "TooGenericExceptionCaught")
internal fun <T> runMethodGraphTasks(
    tasks: List<(() -> Unit) -> T>,
    executor: ExecutorService = methodGraphScanPool
): List<T> {
    if (tasks.size == 1) return listOf(tasks.single().invoke({}))
    val cancellation = MethodTaskCancellation()
    val remaining = CountDownLatch(tasks.size)
    val handles = tasks.map { task ->
        val state = AtomicInteger(METHOD_TASK_PENDING)
        val future = executor.submit(Callable {
            if (!state.compareAndSet(METHOD_TASK_PENDING, METHOD_TASK_RUNNING)) {
                throw ParallelMethodTaskCancelledException()
            }
            try {
                cancellation.check()
                task(cancellation::check)
            } catch (error: Throwable) {
                cancellation.fail(error)
                throw error
            } finally {
                state.set(METHOD_TASK_FINISHED)
                remaining.countDown()
            }
        })
        MethodTaskHandle(state, future)
    }
    return try {
        handles.map { it.future.get() }
    } catch (_: InterruptedException) {
        cancellation.fail(CypherQueryCancelledException())
        cancelPendingAndAwait(handles, remaining)
        Thread.currentThread().interrupt()
        throw CypherQueryCancelledException()
    } catch (error: ExecutionException) {
        cancellation.fail(error.cause ?: error)
        cancelPendingAndAwait(handles, remaining)
        val outer = cancellation.failure() ?: error.cause ?: error
        val nested = outer.cause
        val cause = if (nested != null && nested.javaClass == outer.javaClass && outer.message == nested.toString()) {
            nested
        } else {
            outer
        }
        when (cause) {
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw IllegalStateException("Parallel Method scan failed", cause)
        }
    }
}

private fun <T> cancelPendingAndAwait(handles: List<MethodTaskHandle<T>>, remaining: CountDownLatch) {
    handles.forEach { handle ->
        if (handle.state.compareAndSet(METHOD_TASK_PENDING, METHOD_TASK_CANCELLED)) {
            handle.future.cancel(false)
            remaining.countDown()
        }
    }
    var interrupted = false
    while (remaining.count > 0L) {
        try {
            remaining.await()
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private class MethodTaskCancellation {
    private val cause = AtomicReference<Throwable?>()

    fun fail(error: Throwable) {
        if (error !is ParallelMethodTaskCancelledException) cause.compareAndSet(null, error)
    }

    fun check() {
        if (cause.get() != null) throw ParallelMethodTaskCancelledException()
    }

    fun failure(): Throwable? = cause.get()
}

private data class MethodTaskHandle<T>(
    val state: AtomicInteger,
    val future: Future<T>
)

private class ParallelMethodTaskCancelledException : RuntimeException()

private const val METHOD_TASK_PENDING = 0

private const val METHOD_TASK_RUNNING = 1

private const val METHOD_TASK_FINISHED = 2

private const val METHOD_TASK_CANCELLED = 3
