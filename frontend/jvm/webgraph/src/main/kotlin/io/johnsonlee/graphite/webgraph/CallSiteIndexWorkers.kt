package io.johnsonlee.graphite.webgraph

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class CallSiteTrigramPostingRange(
    val trigram: Int,
    val start: Int,
    val end: Int
) {
    val size: Int
        get() = end - start
}

internal class SplitCallSiteTask<T>(private val task: Callable<T>) {
    private val state = AtomicInteger(NEW)
    private val exited = CountDownLatch(1)
    lateinit var future: Future<T>

    val callable = Callable {
        if (!state.compareAndSet(NEW, RUNNING)) throw CancellationException()
        try {
            task.call()
        } finally {
            state.set(FINISHED)
            exited.countDown()
        }
    }

    fun cancel() {
        future.cancel(true)
        if (state.compareAndSet(NEW, FINISHED)) exited.countDown()
    }

    fun awaitExit(): InterruptedException? {
        var interruption: InterruptedException? = null
        while (true) {
            try {
                exited.await()
                return interruption
            } catch (error: InterruptedException) {
                if (interruption == null) interruption = error
            }
        }
    }

    private companion object {
        const val NEW = 0
        const val RUNNING = 1
        const val FINISHED = 2
    }
}

internal fun <T> cancelAndJoinSplitCallSiteTasks(
    tasks: List<SplitCallSiteTask<T>>
): InterruptedException? {
    tasks.forEach { task -> task.cancel() }
    var interruption: InterruptedException? = null
    tasks.forEach { task ->
        task.awaitExit()?.let { error -> if (interruption == null) interruption = error }
    }
    return interruption
}

@Suppress("ThrowsCount", "TooGenericExceptionCaught")
internal fun executeSplitCallSiteCandidateTasks(
    tasks: List<Callable<IntArray>>,
    backgroundParallelism: Int
): IntArray {
    val results = executeSplitCallSiteTasks(tasks, backgroundParallelism)
    val size = results.sumOf(IntArray::size)
    val combined = IntArray(size)
    var offset = 0
    results.forEach { result ->
        result.copyInto(combined, offset)
        offset += result.size
    }
    return combined
}

internal val splitCallSiteWorkerNumber = AtomicInteger()

internal val splitCallSiteActiveWorkers = AtomicInteger()

internal val splitCallSitePeakActiveWorkers = AtomicInteger()

internal val splitCallSiteExecutors = ConcurrentHashMap<Int, ThreadPoolExecutor>()

internal fun splitCallSitePeakActiveWorkers(): Int = splitCallSitePeakActiveWorkers.get()

internal fun resetSplitCallSiteWorkerMetrics() {
    check(splitCallSiteActiveWorkers.get() == 0) { "Cannot reset active split CallSite worker metrics" }
    splitCallSitePeakActiveWorkers.set(0)
}

internal fun splitCallSiteExecutor(backgroundParallelism: Int) =
    splitCallSiteExecutors.computeIfAbsent(backgroundParallelism) { parallelism ->
        ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { runnable ->
                Thread(
                    runnable,
                    "graphite-callsite-segment-${splitCallSiteWorkerNumber.incrementAndGet()}"
                ).apply { isDaemon = true }
            }
        )
    }

internal fun <T> trackedSplitCallSiteTask(task: Callable<T>): Callable<T> = Callable {
    val activeWorkers = splitCallSiteActiveWorkers.incrementAndGet()
    splitCallSitePeakActiveWorkers.accumulateAndGet(activeWorkers, ::maxOf)
    try {
        task.call()
    } finally {
        // Keep teardown inside the Future's callable. Future.get() must not return while this
        // worker is still counted active; ThreadPoolExecutor.afterExecute runs too late for that.
        splitCallSiteActiveWorkers.decrementAndGet()
    }
}

@Suppress("ThrowsCount", "TooGenericExceptionCaught")
internal fun <T> executeSplitCallSiteTasks(
    tasks: List<Callable<T>>,
    backgroundParallelism: Int
): List<T> {
    require(tasks.isNotEmpty())
    require(backgroundParallelism >= 0)
    require(tasks.size <= backgroundParallelism + 1)
    if (tasks.size == 1) return listOf(tasks.single().call())
    val executor = splitCallSiteExecutor(backgroundParallelism)
    val backgroundTasks = tasks.drop(1).map { task -> SplitCallSiteTask(task) }
    backgroundTasks.forEach { task -> task.future = executor.submit(trackedSplitCallSiteTask(task.callable)) }
    val results = arrayOfNulls<Any?>(tasks.size)
    try {
        results[0] = tasks.first().call()
        backgroundTasks.forEachIndexed { index, task -> results[index + 1] = task.future.get() }
    } catch (error: InterruptedException) {
        cancelAndJoinSplitCallSiteTasks(backgroundTasks)
        Thread.currentThread().interrupt()
        throw CancellationException(CALL_SITE_STRING_MATCH_INTERRUPTED).apply { initCause(error) }
    } catch (error: ExecutionException) {
        cancelAndJoinSplitCallSiteTasks(backgroundTasks)?.let { interruption ->
            Thread.currentThread().interrupt()
            throw CancellationException(CALL_SITE_STRING_MATCH_INTERRUPTED).apply { initCause(interruption) }
        }
        throw error.cause ?: error
    } catch (error: Throwable) {
        cancelAndJoinSplitCallSiteTasks(backgroundTasks)?.let { interruption ->
            Thread.currentThread().interrupt()
            throw CancellationException(CALL_SITE_STRING_MATCH_INTERRUPTED).apply { initCause(interruption) }
        }
        throw error
    }
    @Suppress("UNCHECKED_CAST")
    return results.map { result -> result as T }
}
