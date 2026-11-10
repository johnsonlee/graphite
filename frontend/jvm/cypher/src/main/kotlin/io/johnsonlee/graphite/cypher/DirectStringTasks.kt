package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.GraphScanParallelismPlan
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredPersistedStringIndexGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val DIRECT_STRING_PARALLELISM_PROPERTY = "graphite.cypher.directStringParallelism"

private const val BALANCED_STRING_SCAN_MIN_SOURCE_COUNT = 40

private const val LEGACY_DIRECT_STRING_GRAPH_PARALLELISM = 8

private val directStringWorkerNumber = AtomicInteger()

private val directStringWorkerActive = ThreadLocal.withInitial { false }

private val directStringActiveWorkers = AtomicInteger()

private val directStringPeakActiveWorkers = AtomicInteger()

internal fun directStringGraphPeakActiveWorkers(): Int = directStringPeakActiveWorkers.get()

internal fun resetDirectStringGraphWorkerMetrics() {
    check(directStringActiveWorkers.get() == 0) { "Cannot reset active direct-string graph worker metrics" }
    directStringPeakActiveWorkers.set(0)
}

internal fun resolveDirectStringParallelismPlan(
    processors: Int = Runtime.getRuntime().availableProcessors(),
    configuredGraphWorkers: String? = System.getProperty(DIRECT_STRING_PARALLELISM_PROPERTY)
): GraphScanParallelismPlan {
    val available = processors.coerceAtLeast(1)
    val override = configuredGraphWorkers?.toIntOrNull()?.coerceIn(1, available)
    return override?.let { GraphScanParallelismPlan.withGraphWorkers(available, it) } ?: GraphScanParallelismPlan.balanced(available)
}

internal fun resolveDirectStringGraphParallelism(
    sourceCount: Int,
    processors: Int = Runtime.getRuntime().availableProcessors(),
    configuredGraphWorkers: String? = System.getProperty(DIRECT_STRING_PARALLELISM_PROPERTY),
    graphScoped: Boolean = false
): Int {
    val available = processors.coerceAtLeast(1)
    val candidates = sourceCount.coerceAtLeast(1)
    if (configuredGraphWorkers == null && (graphScoped || sourceCount < BALANCED_STRING_SCAN_MIN_SOURCE_COUNT)) {
        return minOf(candidates, available, LEGACY_DIRECT_STRING_GRAPH_PARALLELISM)
    }
    val plan = resolveDirectStringParallelismPlan(processors, configuredGraphWorkers)
    return minOf(candidates, plan.graphWorkerCount)
}

internal fun resolveDirectStringExecutorParallelism(
    processors: Int = Runtime.getRuntime().availableProcessors(),
    configuredGraphWorkers: String? = System.getProperty(DIRECT_STRING_PARALLELISM_PROPERTY)
): Int {
    val available = processors.coerceAtLeast(1)
    return maxOf(
        minOf(available, LEGACY_DIRECT_STRING_GRAPH_PARALLELISM),
        resolveDirectStringParallelismPlan(available, configuredGraphWorkers).graphWorkerCount)
}

internal val configuredDirectStringParallelism by lazy { System.getProperty(DIRECT_STRING_PARALLELISM_PROPERTY) }

private val directStringExecutorParallelism: Int by lazy {
    resolveDirectStringExecutorParallelism(configuredGraphWorkers = configuredDirectStringParallelism)
}

/**
 * Cancels a direct-string worker that has not started yet and releases its completion latch, so a query that stops early or fails does not
 * wait on a worker the executor never ran. A worker that has already started (its [started] flag is set) keeps running to its own
 * `finally`, and a future that can no longer be cancelled is left alone. Extracted so the not-started branch is covered deterministically
 * rather than only when cancellation happens to win the race against worker start.
 */
internal fun cancelDirectStringWorkerBeforeStart(future: Future<*>, started: AtomicBoolean, completion: CountDownLatch): Boolean {
    if (future.cancel(true) && started.compareAndSet(false, true)) {
        completion.countDown()
        return true
    }
    return false
}

private val directStringExecutor by lazy {
    Executors.newFixedThreadPool(directStringExecutorParallelism) { runnable ->
        Thread(runnable, "graphite-cypher-scan-${directStringWorkerNumber.incrementAndGet()}").apply { isDaemon = true }
    }
}

internal fun directStringGraphParallelism(sourceCount: Int, graphScoped: Boolean = false): Int =
    resolveDirectStringGraphParallelism(sourceCount, configuredGraphWorkers = configuredDirectStringParallelism, graphScoped = graphScoped)

internal fun usesBalancedStringSplit(sourceCount: Int): Boolean =
    configuredDirectStringParallelism != null || sourceCount >= BALANCED_STRING_SCAN_MIN_SOURCE_COUNT

private class SplitStringGraphWorkConsumer(override val segmentWorkerCount: Int, private val consumeBatch: (Long) -> Unit) :
    SplitGraphWorkBatchConsumer {
    override fun consume(workUnits: Long) = consumeBatch(workUnits)
}

private class PreferredMappedStringIndexViewGraphWorkConsumer(
    override val segmentWorkerCount: Int,
    private val consumeBatch: (Long) -> Unit
) : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
    override fun consume(workUnits: Long) = consumeBatch(workUnits)
}

private class SerialStringGraphWorkConsumer(private val consumeBatch: (Long) -> Unit) : SerialGraphWorkBatchConsumer {
    override fun consume(workUnits: Long) = consumeBatch(workUnits)
}

private class PreferredPersistedStringIndexGraphWorkConsumer(private val consumeBatch: (Long) -> Unit) :
    PreferredPersistedStringIndexGraphWorkBatchConsumer {
    override fun consume(workUnits: Long) = consumeBatch(workUnits)
}

private class PreferredRawStringGraphWorkConsumer(private val consumeBatch: (Long) -> Unit) : PreferredRawGraphWorkBatchConsumer {
    override fun consume(workUnits: Long) = consumeBatch(workUnits)
}

private val noOpSerialStringGraphWorkConsumer = SerialStringGraphWorkConsumer {}

private val noOpPreferredPersistedStringIndexGraphWorkConsumer = PreferredPersistedStringIndexGraphWorkConsumer {}

private val noOpPreferredRawStringGraphWorkConsumer = PreferredRawStringGraphWorkConsumer {}

internal fun directStringStorageWorkConsumer(
    sourceCount: Int,
    processors: Int = Runtime.getRuntime().availableProcessors(),
    configuredGraphWorkers: String? = System.getProperty(DIRECT_STRING_PARALLELISM_PROPERTY),
    forceSerial: Boolean = false,
    preferRaw: Boolean = false,
    preferMappedView: Boolean = false,
    consumeBatch: ((Long) -> Unit)? = null
): GraphWorkConsumer =
    if (preferRaw) {
        consumeBatch?.let(::PreferredRawStringGraphWorkConsumer) ?: noOpPreferredRawStringGraphWorkConsumer
    } else if (forceSerial) {
        consumeBatch?.let(::PreferredPersistedStringIndexGraphWorkConsumer) ?: noOpPreferredPersistedStringIndexGraphWorkConsumer
    } else if (sourceCount == 1) {
        consumeBatch?.let { consume -> ParallelGraphWorkBatchConsumer(consume) } ?: noOpParallelGraphWorkConsumer
    } else if (configuredGraphWorkers == null && sourceCount < BALANCED_STRING_SCAN_MIN_SOURCE_COUNT) {
        consumeBatch?.let(::SerialStringGraphWorkConsumer) ?: noOpSerialStringGraphWorkConsumer
    } else {
        val segmentWorkers = resolveDirectStringParallelismPlan(processors, configuredGraphWorkers).segmentWorkerCount
        if (preferMappedView) {
            PreferredMappedStringIndexViewGraphWorkConsumer(segmentWorkers, consumeBatch ?: { _ -> })
        } else {
            SplitStringGraphWorkConsumer(segmentWorkers, consumeBatch ?: { _ -> })
        }
    }

private val noOpParallelGraphWorkConsumer = ParallelGraphWorkBatchConsumer {}

internal val isDirectStringWorker: Boolean
    get() = directStringWorkerActive.get()

@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun <T> runDirectStringTasks(
    tasks: List<() -> T>,
    parallelism: Int,
    executor: ExecutorService? = null
): List<T> {
    if (tasks.size == 1) return listOf(tasks.single().invoke())
    val completionService = ExecutorCompletionService<IndexedValue<T>>(executor ?: directStringExecutor)
    val futures = arrayOfNulls<Future<IndexedValue<T>>>(tasks.size)
    val completions = arrayOfNulls<CountDownLatch>(tasks.size)
    val started = arrayOfNulls<AtomicBoolean>(tasks.size)
    fun submit(index: Int) {
        val completion = CountDownLatch(1)
        val taskStarted = AtomicBoolean()
        completions[index] = completion
        started[index] = taskStarted
        futures[index] =
            completionService.submit(
                Callable {
                    if (!taskStarted.compareAndSet(false, true)) {
                        throw java.util.concurrent.CancellationException()
                    }
                    withWorkerContext(completion) { IndexedValue(index, tasks[index]()) }
                })
    }
    return try {
        var nextTask = 0
        repeat(minOf(tasks.size, parallelism.coerceAtLeast(1))) { submit(nextTask++) }
        val results = arrayOfNulls<Any?>(tasks.size)
        repeat(tasks.size) {
            val result = completionService.take().get()
            results[result.index] = result.value
            if (nextTask < tasks.size) submit(nextTask++)
        }
        @Suppress("UNCHECKED_CAST") results.map { it as T }
    } catch (error: Throwable) {
        cancelAndJoin(futures, started, completions)
        rethrowWorkerFailure(error)
    }
}

/**
 * Runs a source-ordered rolling window without waiting for every task in the current window. At most [parallelism] tasks are submitted
 * ahead of the next result to merge. Returning true from [stopAfter] cancels and joins that bounded speculative suffix.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun <T> runDirectStringTasksInOrderUntil(
    tasks: List<() -> T>,
    parallelism: Int,
    stopAfter: (T) -> Boolean,
    executor: ExecutorService? = null
) {
    if (tasks.isEmpty()) return

    val completionService = ExecutorCompletionService<TaskOutcome<T>>(executor ?: directStringExecutor)
    val futures = arrayOfNulls<Future<TaskOutcome<T>>>(tasks.size)
    val completions = arrayOfNulls<CountDownLatch>(tasks.size)
    val started = arrayOfNulls<AtomicBoolean>(tasks.size)
    fun submit(index: Int) {
        val completion = CountDownLatch(1)
        val taskStarted = AtomicBoolean()
        completions[index] = completion
        started[index] = taskStarted
        futures[index] =
            completionService.submit(
                Callable {
                    if (!taskStarted.compareAndSet(false, true)) {
                        throw java.util.concurrent.CancellationException()
                    }
                    withWorkerContext(completion) { captureTask(index, tasks[index]) }
                })
    }
    fun cancelAndJoin() {
        cancelAndJoin(futures, started, completions)
    }

    var nextTask = 0
    repeat(minOf(tasks.size, parallelism.coerceAtLeast(1))) { submit(nextTask++) }
    try {
        val ordered =
            OrderedTaskResults(tasks.size, stopAfter) {
                // Replenish only as the contiguous prefix advances, keeping speculation bounded.
                if (nextTask < tasks.size) submit(nextTask++)
            }
        while (!ordered.complete) {
            if (ordered.accept(completionService.take().get())) {
                cancelAndJoin()
                return
            }
        }
    } catch (error: Throwable) {
        cancelAndJoin()
        rethrowWorkerFailure(error)
    }
}

/**
 * Reuses a fixed set of graph workers for a source-ordered rolling window. Prepared index lookups are too small to justify one Future per
 * graph, but still benefit from independent graph progress. Only the fixed worker count may be ahead of the merged source prefix.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
internal fun <T> runDirectStringTasksWithFixedWorkersInOrderUntil(tasks: List<() -> T>, parallelism: Int, stopAfter: (T) -> Boolean) {
    if (tasks.isEmpty()) return

    val workerCount = minOf(tasks.size, parallelism.coerceAtLeast(1))
    val taskIndexes = LinkedBlockingQueue<Int>()
    val outcomes = LinkedBlockingQueue<TaskOutcome<T>>()
    val completions = List(workerCount) { CountDownLatch(1) }
    val started = List(workerCount) { AtomicBoolean() }
    val futures =
        completions.mapIndexed { index, completion ->
            val taskStarted = started[index]
            directStringExecutor.submit {
                if (!taskStarted.compareAndSet(false, true)) return@submit
                withWorkerContext(completion) { consumeTaskQueue(tasks, taskIndexes, outcomes) }
            }
        }
    fun stopWorkers(cancel: Boolean) {
        if (cancel) {
            futures.forEachIndexed { index, future -> cancelDirectStringWorkerBeforeStart(future, started[index], completions[index]) }
        } else {
            repeat(workerCount) { taskIndexes.add(-1) }
        }
        awaitDirectStringTasks(completions)
    }

    var nextTask = 0
    repeat(workerCount) { taskIndexes.add(nextTask++) }
    try {
        val ordered = OrderedTaskResults(tasks.size, stopAfter) { if (nextTask < tasks.size) taskIndexes.add(nextTask++) }
        while (!ordered.complete) {
            if (ordered.accept(outcomes.take())) {
                stopWorkers(cancel = true)
                return
            }
        }
        stopWorkers(cancel = false)
    } catch (error: Throwable) {
        stopWorkers(cancel = true)
        rethrowWorkerFailure(error)
    }
}

/** Buffers completion order while exposing only the contiguous source prefix. */
private class OrderedTaskResults<T>(size: Int, private val stopAfter: (T) -> Boolean, private val advance: () -> Unit) {
    private val completed = arrayOfNulls<TaskOutcome<T>>(size)
    private var next = 0
    val complete: Boolean
        get() = next == completed.size

    @Suppress("UNCHECKED_CAST")
    fun accept(outcome: TaskOutcome<T>): Boolean {
        completed[outcome.index] = outcome
        var ordered = completed.getOrNull(next)
        var stop = false
        while (ordered != null && !stop) {
            ordered.error?.let { throw it }
            stop = stopAfter(ordered.value as T)
            if (!stop) {
                next++
                advance()
                ordered = completed.getOrNull(next)
            }
        }
        return stop
    }
}

private data class TaskOutcome<T>(val index: Int, val value: T? = null, val error: Throwable? = null)

// Capture failures as ordered outcomes: a speculative suffix must not fail an already satisfied LIMIT.
@Suppress("TooGenericExceptionCaught")
private fun <T> captureTask(index: Int, task: () -> T): TaskOutcome<T> =
    try {
        TaskOutcome(index, value = task())
    } catch (error: Throwable) {
        TaskOutcome(index, error = error)
    }

private fun <T> consumeTaskQueue(tasks: List<() -> T>, indexes: LinkedBlockingQueue<Int>, outcomes: LinkedBlockingQueue<TaskOutcome<T>>) {
    var index = indexes.take()
    while (index >= 0) {
        val outcome = captureTask(index, tasks[index])
        // Publishing is non-interruptible even when storage preserves interrupted status.
        outcomes.add(outcome)
        if (outcome.error != null) return
        index = indexes.take()
    }
}

private inline fun <T> withWorkerContext(completion: CountDownLatch, block: () -> T): T {
    val previouslyActive = directStringWorkerActive.get()
    directStringWorkerActive.set(true)
    val activeWorkers = directStringActiveWorkers.incrementAndGet()
    directStringPeakActiveWorkers.accumulateAndGet(activeWorkers, ::maxOf)
    return try {
        block()
    } finally {
        directStringActiveWorkers.decrementAndGet()
        if (previouslyActive) directStringWorkerActive.set(true) else directStringWorkerActive.remove()
        completion.countDown()
    }
}

private fun cancelAndJoin(futures: Array<out Future<*>?>, started: Array<AtomicBoolean?>, completions: Array<CountDownLatch?>) {
    futures.forEachIndexed { index, future ->
        val taskStarted = started[index]
        val completion = completions[index]
        if (future != null && taskStarted != null && completion != null) {
            cancelDirectStringWorkerBeforeStart(future, taskStarted, completion)
        }
    }
    awaitDirectStringTasks(completions.filterNotNull())
}

@Suppress("ThrowsCount")
private fun rethrowWorkerFailure(error: Throwable): Nothing {
    val cause = (error as? ExecutionException)?.cause ?: error
    when (cause) {
        is RuntimeException -> throw cause
        is Error -> throw cause
        else -> throw IllegalStateException("Parallel graph scan failed", cause)
    }
}

private fun awaitDirectStringTasks(completions: List<CountDownLatch>) {
    var interrupted = false
    completions.forEach { completion ->
        while (true) {
            try {
                completion.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}
