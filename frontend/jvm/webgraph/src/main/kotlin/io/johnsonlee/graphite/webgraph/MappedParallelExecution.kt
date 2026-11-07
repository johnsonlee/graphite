package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.atomic.AtomicBoolean

/** All submitted workers are drained before any failure or interruption escapes. */
internal class MappedScanCompletion<T>(
    executor: Executor,
    private val abort: AtomicBoolean,
    taskCount: Int,
    private val workerIndex: (T) -> Int
) {
    private val completion = ExecutorCompletionService<T>(executor)
    val results: MutableList<T?> = MutableList(taskCount) { null }
    private var failure: Throwable? = null
    private var interruption: InterruptedException? = null
    val failed: Boolean
        get() = failure != null || interruption != null

    fun executeWave(tasks: List<Callable<T>>, inlineFirst: Boolean, trackBackground: Boolean) {
        val inlineTask = tasks.firstOrNull().takeIf { inlineFirst }
        val background = if (inlineTask == null) tasks else tasks.drop(1)
        background.map { task -> if (trackBackground) trackedSplitCallSiteTask(task) else task }.forEach(completion::submit)
        inlineTask?.let(::executeInline)
        collect(background.size)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeInline(task: Callable<T>) {
        try {
            record(task.call())
        } catch (error: Throwable) {
            recordFailure(error)
        }
    }

    private fun collect(count: Int) {
        var received = 0
        while (received < count) {
            try {
                record(completion.take().get())
                received++
            } catch (error: InterruptedException) {
                abort.set(true)
                if (interruption == null) interruption = error
            } catch (error: ExecutionException) {
                recordFailure(error.cause ?: error)
                received++
            }
        }
    }

    private fun record(result: T) {
        results[workerIndex(result)] = result
    }

    private fun recordFailure(error: Throwable) {
        abort.set(true)
        if (failure == null || failure is CancellationException && error !is CancellationException) failure = error
    }

    fun checkFailure(checkThread: Boolean = true, primaryFailure: Throwable? = null, beforeFailure: () -> Unit = {}) {
        val error =
            interruption?.let { cause -> CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED).apply { initCause(cause) } }
                ?: primaryFailure
                ?: failure
        if (error != null) {
            beforeFailure()
            if (interruption != null) Thread.currentThread().interrupt()
            throw error
        }
        if (checkThread) checkThreadInterrupted { CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED) }
    }
}

/** Per-execution predicate dictionaries; identical predicates share the same memo bytes. */
internal class CallSitePredicateMatcher(
    private val context: MappedGraphContext,
    private val predicates: List<StringPropertyPredicate>,
    exactMatchingStringIds: List<IntArray>?,
    private val propertyIndexes: List<Int> = predicates.map { context.requiredCallSiteStringPropertyIndex(it.property) }
) {
    private val states =
        if (exactMatchingStringIds == null) {
            val shared = mutableMapOf<StringPredicateKey, ByteArray>()
            predicates.map { predicate ->
                shared.getOrPut(StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)) {
                    ByteArray(context.stringTable.size())
                }
            }
        } else emptyList()
    private val exact = exactMatchingStringIds?.map(::IntOpenHashSet)

    fun matches(callerClass: Int, callerName: Int, calleeClass: Int, calleeName: Int): Boolean =
        predicates.indices.any { index ->
            val stringId =
                when (propertyIndexes[index]) {
                    CALLER_CLASS_PROPERTY_INDEX -> callerClass
                    CALLER_NAME_PROPERTY_INDEX -> callerName
                    CALLEE_CLASS_PROPERTY_INDEX -> calleeClass
                    else -> calleeName
                }
            matchesPredicate(index, stringId)
        }

    fun matches(ids: IntArray): Boolean =
        matches(
            ids[CALLER_CLASS_PROPERTY_INDEX],
            ids[CALLER_NAME_PROPERTY_INDEX],
            ids[CALLEE_CLASS_PROPERTY_INDEX],
            ids[CALLEE_NAME_PROPERTY_INDEX])

    private fun matchesPredicate(index: Int, stringId: Int): Boolean {
        val exactIds = exact
        if (exactIds != null) return stringId in exactIds[index]
        val memo = states[index]
        return when (memo[stringId]) {
            RAW_STRING_MATCH -> true
            RAW_STRING_MISS -> false
            else -> {
                val predicate = predicates[index]
                stringMatches(context.stringTable.get(stringId), predicate.transform, predicate.mode, predicate.expected).also {
                    // Stale reads can only repeat deterministic verification, as in the serial matcher.
                    memo[stringId] = if (it) RAW_STRING_MATCH else RAW_STRING_MISS
                }
            }
        }
    }
}
