package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import it.unimi.dsi.lang.MutableString
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun MappedGraphContext.callSiteStringIndexContentIdentity(workConsumer: GraphWorkConsumer?): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(stringTable.contentIdentity(workConsumer))
    digest.updateIdentityInt(nodeTypeIndex.count(CallSiteNode::class.java).toInt())
    forEachRawCallSiteStringIds(workConsumer) { nodeId, callerClass, callerName, calleeClass, calleeName ->
        digest.updateIdentityInt(nodeId)
        digest.updateIdentityLong(nodeOffsets.offset(nodeId))
        digest.updateIdentityInt(callerClass)
        digest.updateIdentityInt(callerName)
        digest.updateIdentityInt(calleeClass)
        digest.updateIdentityInt(calleeName)
    }
    return digest.digest()
}

@Suppress("TooGenericExceptionCaught")
internal fun MappedGraphContext.buildAndPublishCallSiteStringIndex(
    scanResults: List<ParallelCallSiteScanResult>,
    nodeCount: Long,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
    workConsumer: GraphWorkConsumer?
) {
    try {
        val stringCount = stringTable.size()
        val endsByStringId = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(stringCount) }
        val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        forEachCallSiteStringProperty(workConsumer) { propertyIndex, abort ->
            val countAccounting = BufferedGraphWorkConsumer(workConsumer.takeIf { propertyIndex == 0 })
            val ends = endsByStringId[propertyIndex]
            try {
                scanResults.forEach { result ->
                    val stringIds = checkNotNull(result.propertyStringIds)[propertyIndex]
                    repeat(result.scannedCount) { index ->
                        checkCallSiteIndexBuildWorker(index, abort)
                        countAccounting.consume()
                        ends[stringIds[index]]++
                    }
                }
            } finally {
                countAccounting.flush()
            }
            uniqueCounts[propertyIndex] = ends.count { count -> count > 0 }
        }
        val retainedBytes =
            estimatedMappedCallSiteStringIndexRetainedBytes(nodeCount, stringCount, uniqueCounts)
                ?: run {
                    reservation.close()
                    return
                }
        if (!reservation.tryGrowTo(retainedBytes)) {
            reservation.close()
            return
        }
        val capacity = nodeCount.toInt()
        val usedStringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> IntArray(uniqueCounts[propertyIndex]) }
        val postingEnds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> IntArray(uniqueCounts[propertyIndex]) }
        val postings = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(capacity) }
        forEachCallSiteStringProperty(workConsumer) { propertyIndex, abort ->
            val ends = endsByStringId[propertyIndex]
            val used = usedStringIds[propertyIndex]
            var usedIndex = 0
            var postingOffset = 0
            for (stringId in ends.indices) {
                checkCallSiteIndexBuildWorker(stringId, abort)
                val count = ends[stringId]
                if (count == 0) continue
                used[usedIndex++] = stringId
                ends[stringId] = postingOffset
                postingOffset += count
            }
            check(usedIndex == used.size && postingOffset == capacity)
            val postingAccounting = BufferedGraphWorkConsumer(workConsumer.takeIf { propertyIndex == 0 })
            try {
                scanResults.forEach { result ->
                    val nodeIds = checkNotNull(result.nodeIds)
                    val stringIds = checkNotNull(result.propertyStringIds)[propertyIndex]
                    repeat(result.scannedCount) { index ->
                        checkCallSiteIndexBuildWorker(index, abort)
                        postingAccounting.consume()
                        postings[propertyIndex][ends[stringIds[index]]++] = nodeIds[index]
                    }
                }
            } finally {
                postingAccounting.flush()
            }
            val compactEnds = postingEnds[propertyIndex]
            compactEnds.indices.forEach { row ->
                checkCallSiteIndexBuildWorker(row, abort)
                compactEnds[row] = ends[used[row]]
            }
        }
        reservation.shrinkTo(retainedBytes)
        val contentIdentity = callSiteStringIndexContentIdentity(scanResults, nodeCount.toInt())
        val built =
            MappedCallSiteStringIndex(
                Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                    MappedCallSiteStringIndex.PropertyCsr(postingEnds[propertyIndex], usedStringIds[propertyIndex], postings[propertyIndex])
                },
                stringTable,
                nodeOrder = { nodeId -> nodeOffsets.offset(nodeId) },
                nodeIdCapacity = nodeOffsets.size,
                rawStringPropertyId = ::rawCallSiteStringPropertyId,
                contentIdentity = { contentIdentity.copyOf() },
                reservation = reservation)
        val published =
            synchronized(callSiteStringIndexLock) {
                if (callSiteStringIndex == null) {
                    callSiteStringIndex = built
                    callSiteStringIndexLoadedFromPersistence = false
                    true
                } else {
                    false
                }
            }
        if (!published) built.close()
    } catch (error: Throwable) {
        reservation.close()
        throw error
    }
}

internal fun MappedGraphContext.callSiteStringIndexContentIdentity(
    scanResults: List<ParallelCallSiteScanResult>,
    callSiteCount: Int
): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(stringTable.contentIdentity())
    digest.updateIdentityInt(callSiteCount)
    scanResults.forEach { result ->
        val nodeIds = checkNotNull(result.nodeIds)
        val stringIds = checkNotNull(result.propertyStringIds)
        repeat(result.scannedCount) { index ->
            val nodeId = nodeIds[index]
            digest.updateIdentityInt(nodeId)
            digest.updateIdentityLong(nodeOffsets.offset(nodeId))
            repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> digest.updateIdentityInt(stringIds[propertyIndex][index]) }
        }
    }
    return digest.digest()
}

internal fun MappedGraphContext.prepareCallSiteStringIndexInParallel(workConsumer: GraphWorkConsumer?): Boolean {
    if (callSiteScanParallelism <= 1 || workConsumer !is ParallelGraphWorkBatchConsumer) return false
    val nodeCount = nodeTypeIndex.count(CallSiteNode::class.java)
    return if (nodeCount < MIN_PARALLEL_CALL_SITE_SCAN_NODES || nodeCount > Int.MAX_VALUE) false
    else
        estimatedMappedCallSiteStringIndexCountBytes(stringTable.size())?.let(MappedCallSiteStringIndexMemoryBudget::tryReserve)?.let {
            reservation ->
            captureAndBuildCallSiteStringIndex(nodeCount, reservation, workConsumer)
        } ?: false
}

private fun MappedGraphContext.captureAndBuildCallSiteStringIndex(
    nodeCount: Long,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
    workConsumer: GraphWorkConsumer
): Boolean {
    val workerCount = minOf(callSiteScanParallelism, nodeCount.toInt())
    val chunkSize = (nodeCount + workerCount - 1L) / workerCount
    val abort = AtomicBoolean()
    val tasks =
        (0 until workerCount).mapNotNull { workerIndex ->
            val start = (workerIndex * chunkSize).toInt()
            val end = minOf(nodeCount, (workerIndex + 1L) * chunkSize).toInt()
            if (start >= end) null else Callable { CallSiteIndexCaptureWorker(this, workConsumer, abort).capture(workerIndex, start, end) }
        }
    val completion = MappedScanCompletion<ParallelCallSiteScanResult>(callSiteScanExecutor, abort, tasks.size) { it.workerIndex }
    callSiteParallelScanCount.incrementAndGet()
    completion.executeWave(tasks, inlineFirst = false, trackBackground = false)
    completion.checkFailure(checkThread = false, beforeFailure = reservation::close)
    val completed = completion.results.filterNotNull()
    if (completed.size != tasks.size || completed.any { !it.capturedCompleteIndex }) {
        reservation.close()
        return false
    }
    buildAndPublishCallSiteStringIndex(completed, nodeCount, reservation, workConsumer)
    return callSiteStringIndex != null
}

private class CallSiteIndexCaptureWorker(
    private val context: MappedGraphContext,
    private val workConsumer: GraphWorkConsumer,
    private val abort: AtomicBoolean
) {
    @Suppress("TooGenericExceptionCaught")
    fun capture(workerIndex: Int, start: Int, end: Int): ParallelCallSiteScanResult {
        val activeWorkers = context.callSiteScanActiveWorkers.incrementAndGet()
        context.callSiteScanPeakActiveWorkers.accumulateAndGet(activeWorkers, ::maxOf)
        try {
            return scan(workerIndex, start, end)
        } catch (error: Throwable) {
            abort.set(true)
            throw error
        } finally {
            context.callSiteScanActiveWorkers.decrementAndGet()
        }
    }

    private fun scan(workerIndex: Int, start: Int, end: Int): ParallelCallSiteScanResult {
        val expectedCount = end - start
        val nodeIds = IntArray(expectedCount)
        val propertyStringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(expectedCount) }
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var inspected = 0
        try {
            context.nodeTypeIndex.forEachIdWhile(CallSiteNode::class.java, start, end) { nodeId ->
                context.checkCallSiteIndexBuildWorker(inspected, abort)
                accounting.consume()
                nodeIds[inspected] = nodeId
                context.withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                    propertyStringIds[CALLER_CLASS_PROPERTY_INDEX][inspected] = callerClass
                    propertyStringIds[CALLER_NAME_PROPERTY_INDEX][inspected] = callerName
                    propertyStringIds[CALLEE_CLASS_PROPERTY_INDEX][inspected] = calleeClass
                    propertyStringIds[CALLEE_NAME_PROPERTY_INDEX][inspected] = calleeName
                }
                inspected++
                true
            }
        } finally {
            accounting.flush()
        }
        return ParallelCallSiteScanResult(workerIndex, IntArray(0), nodeIds, propertyStringIds, inspected, expectedCount)
    }
}

internal fun MappedGraphContext.forEachCallSiteStringProperty(
    workConsumer: GraphWorkConsumer?,
    action: (propertyIndex: Int, abort: AtomicBoolean) -> Unit
) {
    val abort = AtomicBoolean()
    if (workConsumer !is ParallelGraphWorkBatchConsumer || callSiteScanParallelism <= 1) {
        repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> action(propertyIndex, abort) }
    } else {
        executeCallSitePropertyTasks(abort, action)
    }
}

private fun executeCallSitePropertyTasks(abort: AtomicBoolean, action: (Int, AtomicBoolean) -> Unit) {
    val primaryFailure = AtomicReference<Throwable>()
    val tasks =
        (0 until CALL_SITE_STRING_PROPERTY_COUNT).map { propertyIndex ->
            callSitePropertyTask(propertyIndex, abort, primaryFailure, action)
        }
    val completion = MappedScanCompletion<Int>(callSiteScanExecutor, abort, tasks.size) { it }
    completion.executeWave(tasks, inlineFirst = false, trackBackground = false)
    completion.checkFailure(checkThread = false, primaryFailure = primaryFailure.get())
}

@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
private fun callSitePropertyTask(
    propertyIndex: Int,
    abort: AtomicBoolean,
    primaryFailure: AtomicReference<Throwable>,
    action: (Int, AtomicBoolean) -> Unit
): Callable<Int> = Callable {
    try {
        action(propertyIndex, abort)
        propertyIndex
    } catch (error: Throwable) {
        if (abort.compareAndSet(false, true) || error !is CancellationException) {
            primaryFailure.compareAndSet(null, error)
        }
        throw error
    }
}

internal fun MappedGraphContext.checkCallSiteIndexBuildWorker(index: Int, abort: AtomicBoolean) {
    if ((index and RAW_SCAN_INTERRUPTION_POLL_MASK) != 0) return
    checkWorkerInterrupted(abort)
}

internal fun MappedGraphContext.checkWorkerInterrupted(abort: AtomicBoolean, countScanAbort: Boolean = false) {
    if (abort.get()) {
        if (countScanAbort) callSiteScanAbortedWorkers.incrementAndGet()
        throw CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
    }
    checkThreadInterrupted {
        if (countScanAbort && abort.get()) callSiteScanAbortedWorkers.incrementAndGet()
        CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED)
    }
}

internal fun MappedGraphContext.callSitePredicatesCannotMatch(
    predicates: List<StringPropertyPredicate>,
    workConsumer: GraphWorkConsumer?
): Boolean {
    callSiteStringPreflightCount.incrementAndGet()
    val predicateKeys =
        predicates.mapTo(linkedSetOf()) { predicate -> StringPredicateKey(predicate.transform, predicate.mode, predicate.expected) }
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    try {
        for (predicate in predicateKeys) {
            checkThreadInterrupted { CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED) }
            if (dictionaryContains(predicate, accounting)) return false
        }
        return true
    } finally {
        accounting.flush()
    }
}

private fun MappedGraphContext.dictionaryContains(predicate: StringPredicateKey, accounting: BufferedGraphWorkConsumer): Boolean {
    val actual = MutableString()
    for (stringId in 0 until stringTable.size()) {
        if ((stringId and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) {
            checkThreadInterrupted { CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED) }
        }
        accounting.consume()
        stringTable.get(stringId, actual)
        if (reusableContains(actual, predicate.transform, predicate.expected)) return true
    }
    return false
}

internal fun MappedGraphContext.shouldPreflightCallSitePredicates(predicates: List<StringPropertyPredicate>): Boolean =
    callSiteStringIndex == null &&
        nodeTypeIndex.count(CallSiteNode::class.java) >= MIN_CALL_SITE_STRING_PREFLIGHT_NODES &&
        predicates.all { predicate ->
            predicate.mode == StringMatchMode.CONTAINS && predicate.expected.length >= MIN_CALL_SITE_STRING_PREFLIGHT_TERM_LENGTH
        }
