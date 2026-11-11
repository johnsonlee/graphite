package io.johnsonlee.graphite.webgraph

import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal fun sortCallSiteTrigramPostings(
    postings: LongArray,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation? = null,
    onParallelWorkerStarted: (() -> Unit)? = null
): Boolean {
    checkCallSiteIndexInterrupted()
    return if (postings.size < MIN_PARALLEL_CALL_SITE_TRIGRAM_SORT_SIZE) {
        Arrays.sort(postings)
        checkCallSiteIndexInterrupted()
        true
    } else {
        sortWithCallSiteTrigramTemporary(
            postings, callSiteTrigramSortTemporaryBytes(postings.size), reservation, onParallelWorkerStarted)
    }
}

// An array length is at most Int.MAX_VALUE: its byte size plus the fixed header cannot overflow Long.
private fun callSiteTrigramSortTemporaryBytes(count: Int): Long =
    PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES + count.toLong() * Long.SIZE_BYTES

private fun sortWithCallSiteTrigramTemporary(
    postings: LongArray,
    temporaryBytes: Long,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation?,
    onParallelWorkerStarted: (() -> Unit)?
): Boolean {
    val retainedBefore = reservation?.bytes
    if (!reserveCallSiteTrigramTemporary(reservation, retainedBefore, temporaryBytes)) return false
    try {
        val temporary = LongArray(postings.size)
        parallelCallSiteTrigramRadixSort(postings, temporary, onParallelWorkerStarted)
        return true
    } finally {
        if (reservation != null) reservation.shrinkTo(checkNotNull(retainedBefore))
    }
}

private fun reserveCallSiteTrigramTemporary(
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation?,
    retainedBefore: Long?,
    temporaryBytes: Long
): Boolean =
    if (reservation == null) true
    else {
        runCatching { Math.addExact(checkNotNull(retainedBefore), temporaryBytes) }.getOrNull()?.let(reservation::tryGrowTo) ?: false
    }

internal fun parallelCallSiteTrigramRadixSort(postings: LongArray, temporary: LongArray, onParallelWorkerStarted: (() -> Unit)?) {
    val workerCount = minOf(callSiteScanParallelism, postings.size)
    val chunkSize = ((postings.size.toLong() + workerCount - 1L) / workerCount).toInt()
    var source = postings
    var target = temporary
    repeat(Long.SIZE_BYTES) { byteIndex ->
        val shift = byteIndex * Byte.SIZE_BITS
        val counts = Array(workerCount) { IntArray(RADIX_BUCKET_COUNT) }
        runCallSiteTrigramSortPhase(workerCount, onParallelWorkerStarted) { workerIndex, abort ->
            val start = workerIndex * chunkSize
            val end = minOf(postings.size, start + chunkSize)
            val workerCounts = counts[workerIndex]
            var index = start
            while (index < end) {
                checkCallSiteTrigramSortWorker(index, abort)
                workerCounts[callSiteTrigramRadixBucket(source[index], shift)]++
                index++
            }
        }

        val offsets = Array(workerCount) { IntArray(RADIX_BUCKET_COUNT) }
        var offset = 0L
        for (bucket in 0 until RADIX_BUCKET_COUNT) {
            for (workerIndex in 0 until workerCount) {
                offsets[workerIndex][bucket] = offset.toInt()
                offset += counts[workerIndex][bucket]
            }
        }
        check(offset == postings.size.toLong())

        runCallSiteTrigramSortPhase(workerCount, onParallelWorkerStarted) { workerIndex, abort ->
            val start = workerIndex * chunkSize
            val end = minOf(postings.size, start + chunkSize)
            val workerOffsets = offsets[workerIndex]
            var index = start
            while (index < end) {
                checkCallSiteTrigramSortWorker(index, abort)
                val value = source[index]
                val bucket = callSiteTrigramRadixBucket(value, shift)
                target[workerOffsets[bucket]++] = value
                index++
            }
        }
        val previousSource = source
        source = target
        target = previousSource
    }
    check(source === postings)
}

internal fun runCallSiteTrigramSortPhase(workerCount: Int, onParallelWorkerStarted: (() -> Unit)?, work: (Int, AtomicBoolean) -> Unit) {
    val abort = AtomicBoolean()
    val tasks =
        List(workerCount) { workerIndex ->
            Callable {
                onParallelWorkerStarted?.invoke()
                work(workerIndex, abort)
            }
        }
    awaitCallSiteTrigramTasks(tasks, abort)
}

internal fun checkCallSiteTrigramSortWorker(index: Int, abort: AtomicBoolean) {
    if ((index and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) != 0) return
    if (abort.get()) throw CancellationException(CALL_SITE_TRIGRAM_SORT_ABORTED)
    checkCallSiteIndexInterrupted()
}

internal fun callSiteTrigramRadixBucket(value: Long, shift: Int): Int {
    val bucket = ((value ushr shift) and RADIX_BUCKET_MASK).toInt()
    return if (shift == SIGNED_LONG_RADIX_SHIFT) bucket xor SIGNED_RADIX_BUCKET_FLIP else bucket
}
