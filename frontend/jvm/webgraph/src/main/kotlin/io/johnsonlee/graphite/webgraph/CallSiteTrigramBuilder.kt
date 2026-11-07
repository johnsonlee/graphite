package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer

internal class CallSiteTrigramBuilder(private val context: MappedCallSiteStringIndex) {
    private var trigramPostingCounts
        get() = context.trigramPostingCounts
        set(value) { context.trigramPostingCounts = value }
    private var trigramStringIds
        get() = context.trigramStringIds
        set(value) { context.trigramStringIds = value }
    private val stringTable get() = context.stringTable
    private val reservation get() = context.reservation

    fun buildCallSiteTrigramPostings(workConsumer: GraphWorkConsumer?): LongArray? {
        val postingCount = context.ensureTrigramMetadata(workConsumer)
        val postingCounts = checkNotNull(trigramPostingCounts)
        val stringIds = checkNotNull(trigramStringIds)
        var buildCompleted = false
        return try {
            val initialBytes = reservePostings(postingCount)
            val result = if (initialBytes == null) null else {
                buildAndSort(postingCount.toInt(), postingCounts, stringIds, workConsumer, initialBytes)
            }
            buildCompleted = true
            result
        } finally {
            if (buildCompleted) {
                trigramPostingCounts = null
                trigramStringIds = null
            }
        }
    }

    private fun postingBytes(postingCount: Long): Long? =
        if (postingCount == 0L || postingCount > Int.MAX_VALUE) null else try {
            Math.addExact(PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                Math.multiplyExact(postingCount, Long.SIZE_BYTES.toLong()))
        } catch (_: ArithmeticException) { null }

    private fun reservePostings(postingCount: Long): Long? {
        val bytes = postingBytes(postingCount) ?: return null
        val maximum = System.getProperty(CALL_SITE_TRIGRAM_INDEX_BUDGET_PROPERTY)?.toLongOrNull()?.coerceAtLeast(0L)
            ?: Runtime.getRuntime().maxMemory() / DEFAULT_CALL_SITE_TRIGRAM_HEAP_DIVISOR
        return if (bytes <= maximum) {
            val before = reservation.bytes
            val after = try { Math.addExact(before, bytes) } catch (_: ArithmeticException) { null }
            if (after != null && reservation.tryGrowTo(after)) before else null
        } else null
    }

    private fun buildAndSort(
        postingCount: Int,
        postingCounts: IntArray,
        stringIds: IntArray,
        workConsumer: GraphWorkConsumer?,
        initialBytes: Long
    ): LongArray? {
        var completed = false
        return try {
            val result = LongArray(postingCount)
            if (workConsumer is ParallelGraphWorkBatchConsumer &&
                stringTable.size() >= MIN_PARALLEL_CALL_SITE_TRIGRAM_STRINGS && callSiteScanParallelism > 1
            ) {
                populateCallSiteTrigramPostingsInParallel(stringIds, result, postingCounts, stringTable, workConsumer)
            } else populateCallSiteTrigramPostings(stringIds, result, postingCounts, stringTable, workConsumer)
            if (sortCallSiteTrigramPostings(result, reservation)) {
                completed = true
                result
            } else null
        } finally {
            // Allocation and population failures release the same reservation as an unsuccessful sort.
            if (!completed) reservation.shrinkTo(initialBytes)
        }
    }
}
