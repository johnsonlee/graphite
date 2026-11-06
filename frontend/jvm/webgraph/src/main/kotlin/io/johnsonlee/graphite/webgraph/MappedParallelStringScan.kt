package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import it.unimi.dsi.fastutil.ints.IntArrayList
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal fun <T : Node> MappedGraphContext.parallelRawCallSiteStringDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int,
    workConsumer: GraphWorkConsumer?,
    exactMatchingStringIds: List<IntArray>? = null,
    orderedPrefixWaves: Boolean = false
): Sequence<T>? =
    (workConsumer as? ParallelGraphWorkBatchConsumer)?.let { work ->
        parallelCallSiteNodeCount(type, limit, work)?.let { count ->
            ParallelCallSiteScan(this, type, predicates, limit, work, exactMatchingStringIds, orderedPrefixWaves, count).run()
        }
    }

private fun MappedGraphContext.parallelCallSiteNodeCount(
    type: Class<out Node>,
    limit: Int,
    workConsumer: ParallelGraphWorkBatchConsumer
): Long? {
    val unsupported = type != CallSiteNode::class.java || limit == Int.MAX_VALUE
    val noParallelWorkers = callSiteScanParallelism <= 1 && workConsumer !is SplitGraphWorkBatchConsumer
    if (unsupported || noParallelWorkers) return null
    val count = nodeTypeIndex.count(type)
    return count.takeIf { it >= MIN_PARALLEL_CALL_SITE_SCAN_NODES && it <= Int.MAX_VALUE && limit < it }
}

private class ParallelCallSiteScan<T : Node>(
    private val context: MappedGraphContext,
    private val type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    private val limit: Int,
    private val workConsumer: ParallelGraphWorkBatchConsumer,
    exactMatchingStringIds: List<IntArray>?,
    private val orderedPrefixWaves: Boolean,
    private val nodeCount: Long
) {
    private val splitWork = workConsumer as? SplitGraphWorkBatchConsumer
    private val backgroundWorkerCount =
        minOf(
            callSiteScanParallelism,
            splitWork?.segmentWorkerCount ?: callSiteScanParallelism,
            nodeCount.toInt() - if (splitWork == null) 0 else 1)
    private val workerCount = minOf(nodeCount.toInt(), backgroundWorkerCount + if (splitWork == null) 0 else 1)
    private val rangeCount = if (orderedPrefixWaves) workerCount * ORDERED_PREFIX_WAVE_COUNT else workerCount
    private val chunkSize = (nodeCount + rangeCount - 1L) / rangeCount
    private val matcher = CallSitePredicateMatcher(context, predicates, exactMatchingStringIds)
    private val ranges =
        (0 until rangeCount).mapNotNull { index ->
            val start = (index * chunkSize).toInt()
            val end = minOf(nodeCount, (index + 1L) * chunkSize).toInt()
            if (start < end) Triple(index, start, end) else null
        }
    // Split fleet queries pay for only their requested scan, never an index per graph.
    private var reservation =
        if (splitWork == null && context.callSiteStringIndex == null) {
            estimatedMappedCallSiteStringIndexCountBytes(context.stringTable.size())?.let(MappedCallSiteStringIndexMemoryBudget::tryReserve)
        } else null
    private val abort = AtomicBoolean()

    fun run(): Sequence<T> {
        val executor =
            if (splitWork != null && backgroundWorkerCount > 0) splitCallSiteExecutor(splitWork.segmentWorkerCount)
            else callSiteScanExecutor
        val completion = MappedScanCompletion(executor, abort, ranges.size, ParallelCallSiteScanResult::workerIndex)
        val worker = CallSiteScanWorker(context, type, matcher, limit, workConsumer, abort, reservation != null)
        val tasks = ranges.map { (index, start, end) -> Callable { worker.scan(index, start, end) } }
        context.callSiteParallelScanCount.incrementAndGet()
        val executed = executeWaves(tasks, completion)
        try {
            completion.checkFailure()
            publishCompleteIndex(completion.results.filterNotNull(), executed, tasks.size)
            return completion.results
                .asSequence()
                .filterNotNull()
                .flatMap { it.matches.asSequence() }
                .take(limit)
                .mapNotNull { nodeId -> context.node(NodeId(nodeId))?.let(type::cast) }
        } finally {
            reservation?.close()
        }
    }

    private fun executeWaves(
        tasks: List<Callable<ParallelCallSiteScanResult>>,
        completion: MappedScanCompletion<ParallelCallSiteScanResult>
    ): Int {
        var start = 0
        var prefixComplete = false
        while (start < tasks.size && !completion.failed && !prefixComplete) {
            val wave = tasks.subList(start, minOf(tasks.size, start + workerCount))
            completion.executeWave(wave, splitWork != null, splitWork != null)
            start += wave.size
            prefixComplete =
                orderedPrefixWaves && completion.results.asSequence().take(start).filterNotNull().sumOf { it.matches.size } >= limit
        }
        return start
    }

    @Suppress("TooGenericExceptionCaught")
    private fun publishCompleteIndex(results: List<ParallelCallSiteScanResult>, executed: Int, taskCount: Int) {
        val current = reservation
        val complete = executed == taskCount && results.size == taskCount && results.all(ParallelCallSiteScanResult::capturedCompleteIndex)
        if (current != null && complete) {
            reservation = null // Publication takes ownership, including its failure path.
            try {
                context.buildAndPublishCallSiteStringIndex(results, nodeCount, current, workConsumer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                /* An optional cache handoff must not replace a complete query result. */
            }
        }
    }
}

private class CallSiteScanWorker(
    private val context: MappedGraphContext,
    private val type: Class<out Node>,
    private val matcher: CallSitePredicateMatcher,
    private val limit: Int,
    private val workConsumer: GraphWorkConsumer,
    private val abort: AtomicBoolean,
    private val captureIndex: Boolean
) {
    @Suppress("TooGenericExceptionCaught")
    fun scan(index: Int, start: Int, end: Int): ParallelCallSiteScanResult {
        val active = context.callSiteScanActiveWorkers.incrementAndGet()
        context.callSiteScanPeakActiveWorkers.accumulateAndGet(active, ::maxOf)
        try {
            return scanRange(index, start, end)
        } catch (error: Throwable) {
            abort.set(true)
            throw error
        } finally {
            context.callSiteScanActiveWorkers.decrementAndGet()
        }
    }

    private fun scanRange(index: Int, start: Int, end: Int): ParallelCallSiteScanResult {
        val expectedCount = end - start
        val matches = IntArrayList(minOf(limit, expectedCount))
        val nodeIds = if (captureIndex) IntArray(expectedCount) else null
        val properties = if (captureIndex) Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(expectedCount) } else null
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var inspected = 0
        try {
            context.nodeTypeIndex.forEachIdWhile(type, start, end) { nodeId ->
                if ((inspected and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) context.checkWorkerInterrupted(abort, countScanAbort = true)
                accounting.consume()
                var matched = false
                context.withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                    nodeIds?.set(inspected, nodeId)
                    properties?.let {
                        it[CALLER_CLASS_PROPERTY_INDEX][inspected] = callerClass
                        it[CALLER_NAME_PROPERTY_INDEX][inspected] = callerName
                        it[CALLEE_CLASS_PROPERTY_INDEX][inspected] = calleeClass
                        it[CALLEE_NAME_PROPERTY_INDEX][inspected] = calleeName
                    }
                    matched = matcher.matches(callerClass, callerName, calleeClass, calleeName)
                }
                inspected++
                if (matched) matches.add(nodeId)
                matches.size != limit
            }
            return ParallelCallSiteScanResult(index, matches.toIntArray(), nodeIds, properties, inspected, expectedCount)
        } finally {
            accounting.flush()
        }
    }
}
