package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

internal fun MappedGraphContext.parallelRawDistinctCallSiteStringProjection(
    predicates: List<StringPropertyPredicate>,
    projectedProperties: List<String>,
    limit: Int,
    selectedValues: Set<List<String?>>?,
    workConsumer: SplitGraphWorkBatchConsumer,
    exactMatchingStringIds: List<IntArray>? = null,
    propertyStringFilter: CallSiteStringIdMembership? = null
): List<StringPropertyDistinctRow>? {
    if (limit <= 0 || selectedValues?.isEmpty() == true) return emptyList()
    val count = nodeTypeIndex.count(CallSiteNode::class.java)
    return if (count < MIN_PARALLEL_CALL_SITE_SCAN_NODES || count > Int.MAX_VALUE || limit >= count) null
    else {
        ParallelDistinctProjection(
                this,
                predicates,
                projectedProperties,
                limit,
                selectedValues,
                workConsumer,
                exactMatchingStringIds,
                propertyStringFilter,
                count)
            .run()
    }
}

private class ParallelDistinctProjection(
    private val context: MappedGraphContext,
    predicates: List<StringPropertyPredicate>,
    projectedProperties: List<String>,
    limit: Int,
    selectedValues: Set<List<String?>>?,
    private val workConsumer: SplitGraphWorkBatchConsumer,
    exactMatchingStringIds: List<IntArray>?,
    propertyStringFilter: CallSiteStringIdMembership?,
    private val nodeCount: Long
) {
    private val backgroundWorkerCount = minOf(callSiteScanParallelism, workConsumer.segmentWorkerCount, nodeCount.toInt() - 1)
    private val workerCount = minOf(nodeCount.toInt(), backgroundWorkerCount + 1)
    private val chunkSize = (nodeCount + workerCount - 1L) / workerCount
    private val predicatePropertyIndexes = predicates.map { context.requiredCallSiteStringPropertyIndex(it.property) }
    private val propertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex)
    private val selectedIds = SelectedProjectionIds(context, propertyIndexes, selectedValues, propertyStringFilter, workConsumer).resolve()
    // Empty selected IDs return before allocating predicate memo bytes, as on the serial path.
    private val matcher by lazy { CallSitePredicateMatcher(context, predicates, exactMatchingStringIds, predicatePropertyIndexes) }
    private val targetSize = minOf(limit, selectedValues?.size ?: limit)
    private val abort = AtomicBoolean()

    fun run(): List<StringPropertyDistinctRow> {
        if (selectedIds?.isEmpty() == true) return emptyList()
        val executor = if (backgroundWorkerCount > 0) splitCallSiteExecutor(workConsumer.segmentWorkerCount) else callSiteScanExecutor
        val worker = DistinctProjectionWorker(context, matcher, propertyIndexes, selectedIds, targetSize, workConsumer, abort)
        val tasks =
            (0 until workerCount).mapNotNull { index ->
                val start = (index * chunkSize).toInt()
                val end = minOf(nodeCount, (index + 1L) * chunkSize).toInt()
                if (start < end) Callable { worker.scan(index, start, end) } else null
            }
        context.callSiteParallelScanCount.incrementAndGet()
        val completion = MappedScanCompletion(executor, abort, tasks.size, ParallelCallSiteProjectionResult::workerIndex)
        completion.executeWave(tasks, inlineFirst = true, trackBackground = true)
        completion.checkFailure()
        return merge(completion.results.filterNotNull())
    }

    private fun merge(results: List<ParallelCallSiteProjectionResult>): List<StringPropertyDistinctRow> {
        val rows = ArrayList<StringPropertyDistinctRow>(targetSize)
        val seen = HashSet<List<String?>>()
        results.sortedBy(ParallelCallSiteProjectionResult::workerIndex).forEach { result ->
            result.rows.forEach { row ->
                if (seen.add(row.values)) rows += row
                if (rows.size >= targetSize) return rows
            }
        }
        return rows
    }
}

private class SelectedProjectionIds(
    private val context: MappedGraphContext,
    private val propertyIndexes: List<Int>,
    private val selectedValues: Set<List<String?>>?,
    private val filter: CallSiteStringIdMembership?,
    private val workConsumer: SplitGraphWorkBatchConsumer
) {
    private val stringIds = HashMap<String, Int>()
    private val membership = HashMap<Long, Boolean>()

    fun resolve(): Set<List<Int>>? = selectedValues?.mapNotNullTo(hashSetOf(), ::resolveTuple)

    private fun resolveTuple(values: List<String?>): List<Int>? = if (values.size != propertyIndexes.size) null else buildTuple(values)

    private fun buildTuple(values: List<String?>): List<Int>? {
        val ids = ArrayList<Int>(propertyIndexes.size)
        for (index in propertyIndexes.indices) {
            val id = resolveValue(propertyIndexes[index], values[index]) ?: return null
            ids += id
        }
        return ids
    }

    private fun resolveValue(property: Int, value: String?): Int? =
        when {
            property < 0 && value == null -> -1
            property < 0 || value == null -> null
            else ->
                stringIds
                    .getOrPut(value) { context.stringTable.findId(value) }
                    .takeIf { id ->
                        id >= 0 &&
                            (filter == null ||
                                membership.getOrPut(property.toLong() shl Int.SIZE_BITS or (id.toLong() and UNSIGNED_INT_MASK)) {
                                    filter.containsPropertyStringId(property, id, workConsumer)
                                })
                    }
        }
}

private class DistinctProjectionWorker(
    private val context: MappedGraphContext,
    private val matcher: CallSitePredicateMatcher,
    private val propertyIndexes: List<Int>,
    private val selectedIds: Set<List<Int>>?,
    private val targetSize: Int,
    private val workConsumer: SplitGraphWorkBatchConsumer,
    private val abort: AtomicBoolean
) {
    @Suppress("TooGenericExceptionCaught")
    fun scan(index: Int, start: Int, end: Int): ParallelCallSiteProjectionResult {
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

    private fun scanRange(index: Int, start: Int, end: Int): ParallelCallSiteProjectionResult {
        val rows = ArrayList<StringPropertyDistinctRow>(targetSize)
        val seen = HashSet<List<String?>>()
        val ids = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var inspected = 0
        try {
            context.nodeTypeIndex.forEachIdWhile(CallSiteNode::class.java, start, end) { nodeId ->
                if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) context.checkWorkerInterrupted(abort, countScanAbort = true)
                accounting.consume()
                val matched = readMatch(nodeId, ids)
                if (matched && selected(ids)) {
                    val values =
                        propertyIndexes.map { property -> if (property < 0) null else context.stringTable.get(ids[property]).toString() }
                    if (seen.add(values)) rows += StringPropertyDistinctRow(context.nodeOffsets.offset(nodeId), values)
                }
                rows.size < targetSize
            }
        } finally {
            accounting.flush()
        }
        return ParallelCallSiteProjectionResult(index, rows)
    }

    private fun readMatch(nodeId: Int, ids: IntArray): Boolean {
        var matched = false
        context.withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
            ids[CALLER_CLASS_PROPERTY_INDEX] = callerClass
            ids[CALLER_NAME_PROPERTY_INDEX] = callerName
            ids[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
            ids[CALLEE_NAME_PROPERTY_INDEX] = calleeName
            matched = matcher.matches(ids)
        }
        return matched
    }

    private fun selected(ids: IntArray): Boolean = selectedIds == null || propertyIndexes.map { if (it < 0) -1 else ids[it] } in selectedIds
}
