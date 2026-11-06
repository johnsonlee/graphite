package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndex.PropertyCsr
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.atomic.AtomicBoolean

internal fun StringPropertyPredicate.requiresTrigramSignature(): Boolean =
    mode == StringMatchMode.CONTAINS &&
        expected.length >= MIN_CALL_SITE_TRIGRAM_LENGTH &&
        expected.all { character -> character.code < ASCII_LIMIT }

internal fun StringPropertyPredicate.canUseLowercaseTrigramPostings(): Boolean =
    transform == StringValueTransform.LOWERCASE ||
        transform == null && expected.all { character -> character.code < ASCII_LIMIT }

internal fun callSiteTrigramSignature(value: String): Long {
    var signature = 0L
    for (position in 0..value.length - MIN_CALL_SITE_TRIGRAM_LENGTH) {
        val first = value[position].lowercaseChar().code
        val second = value[position + 1].lowercaseChar().code
        val third = value[position + 2].lowercaseChar().code
        val hash = (first * CALL_SITE_STRING_HASH_FACTOR + second) * CALL_SITE_STRING_HASH_FACTOR + third
        val mixed = hash xor (hash ushr CALL_SITE_TRIGRAM_MIX_RIGHT_SHIFT) xor (hash shl CALL_SITE_TRIGRAM_MIX_LEFT_SHIFT)
        signature = signature or (1L shl (hash and TRIGRAM_SIGNATURE_MASK))
        signature = signature or (1L shl (mixed and TRIGRAM_SIGNATURE_MASK))
    }
    return signature
}

internal fun usedCallSiteTrigramStringIds(
    properties: Array<MappedCallSiteStringIndex.PropertyCsr>,
    stringCount: Int
): IntArray {
    val used = BooleanArray(stringCount)
    val stringIds = IntArray(stringCount)
    var usedCount = 0
    properties.forEach { property ->
        property.forEachUsedStringId { stringId ->
            if (!used[stringId]) {
                used[stringId] = true
                stringIds[usedCount++] = stringId
            }
        }
    }
    return stringIds.copyOf(usedCount)
}

internal fun populateCallSiteTrigramMetadata(
    stringIds: IntArray,
    signatures: LongArray,
    postingCounts: IntArray,
    stringTable: StringTable,
    workConsumer: GraphWorkConsumer?
): Long {
    val seenTrigrams = IntOpenHashSet()
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    var postingCount = 0L
    try {
        stringIds.forEachIndexed { index, stringId ->
            if ((index and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                checkCallSiteIndexInterrupted()
            }
            accounting.consume()
            postingCount += populateCallSiteTrigramMetadata(
                signatures,
                postingCounts,
                stringId,
                stringTable,
                seenTrigrams
            )
        }
    } finally {
        accounting.flush()
    }
    return postingCount
}

internal fun populateCallSiteTrigramPostings(
    stringIds: IntArray,
    target: LongArray,
    postingCounts: IntArray,
    stringTable: StringTable,
    workConsumer: GraphWorkConsumer?
) {
    val seenTrigrams = IntOpenHashSet()
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    var resultIndex = 0
    try {
        stringIds.forEach { stringId ->
            accounting.consume()
            resultIndex = populateCallSiteTrigramPostings(
                target,
                resultIndex,
                postingCounts[stringId],
                stringId,
                stringTable,
                seenTrigrams
            )
        }
    } finally {
        accounting.flush()
    }
    check(resultIndex == target.size)
}

internal fun populateCallSiteTrigramMetadataInParallel(
    stringIds: IntArray,
    signatures: LongArray,
    postingCounts: IntArray,
    stringTable: StringTable,
    workConsumer: ParallelGraphWorkBatchConsumer
): Long {
    if (stringIds.isEmpty()) return 0L
    val abort = AtomicBoolean()
    val workerCount = minOf(callSiteScanParallelism, stringIds.size)
    val chunkSize = (stringIds.size + workerCount - 1) / workerCount
    val tasks = (0 until workerCount).mapNotNull { workerIndex ->
        val start = workerIndex * chunkSize
        val end = minOf(stringIds.size, start + chunkSize)
        if (start >= end) return@mapNotNull null
        Callable {
            val seenTrigrams = IntOpenHashSet()
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            var postingCount = 0L
            try {
                for (index in start until end) {
                    checkCallSiteTrigramWorker(index, abort)
                    accounting.consume()
                    postingCount += populateCallSiteTrigramMetadata(
                        signatures,
                        postingCounts,
                        stringIds[index],
                        stringTable,
                        seenTrigrams
                    )
                }
                postingCount
            } finally {
                accounting.flush()
            }
        }
    }
    return awaitCallSiteTrigramTasks(tasks, abort).sum()
}

internal fun populateCallSiteTrigramPostingsInParallel(
    stringIds: IntArray,
    target: LongArray,
    postingCounts: IntArray,
    stringTable: StringTable,
    workConsumer: ParallelGraphWorkBatchConsumer
) {
    if (stringIds.isEmpty()) return
    val offsets = IntArray(stringIds.size)
    var postingOffset = 0
    for (index in stringIds.indices) {
        offsets[index] = postingOffset
        postingOffset += postingCounts[stringIds[index]]
    }
    check(postingOffset == target.size)

    val abort = AtomicBoolean()
    val workerCount = minOf(callSiteScanParallelism, stringIds.size)
    val chunkSize = (stringIds.size + workerCount - 1) / workerCount
    val tasks = (0 until workerCount).mapNotNull { workerIndex ->
        val start = workerIndex * chunkSize
        val end = minOf(stringIds.size, start + chunkSize)
        if (start >= end) return@mapNotNull null
        Callable {
            val seenTrigrams = IntOpenHashSet()
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            var written = 0
            try {
                for (index in start until end) {
                    checkCallSiteTrigramWorker(index, abort)
                    accounting.consume()
                    val stringId = stringIds[index]
                    val nextOffset = populateCallSiteTrigramPostings(
                        target,
                        offsets[index],
                        postingCounts[stringId],
                        stringId,
                        stringTable,
                        seenTrigrams
                    )
                    written += nextOffset - offsets[index]
                }
                written
            } finally {
                accounting.flush()
            }
        }
    }
    check(awaitCallSiteTrigramTasks(tasks, abort).sum() == target.size)
}

internal fun checkCallSiteTrigramWorker(index: Int, abort: AtomicBoolean) {
    if ((index and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) != 0) return
    if (abort.get()) throw CancellationException(CALL_SITE_TRIGRAM_BUILD_ABORTED)
    checkCallSiteIndexInterrupted()
}

@Suppress("ThrowsCount")
internal fun <T> awaitCallSiteTrigramTasks(tasks: List<Callable<T>>, abort: AtomicBoolean): List<T> {
    val completion = ExecutorCompletionService<T>(callSiteScanExecutor)
    tasks.forEach(completion::submit)
    val results = ArrayList<T>(tasks.size)
    var received = 0
    var failure: Throwable? = null
    var interruption: InterruptedException? = null
    while (received < tasks.size) {
        try {
            results += completion.take().get()
            received++
        } catch (error: InterruptedException) {
            abort.set(true)
            if (interruption == null) interruption = error
        } catch (error: ExecutionException) {
            abort.set(true)
            val cause = error.cause ?: error
            if (failure == null || failure is CancellationException && cause !is CancellationException) {
                failure = cause
            }
            received++
        }
    }
    interruption?.let { error ->
        Thread.currentThread().interrupt()
        throw CancellationException(CALL_SITE_TRIGRAM_BUILD_INTERRUPTED).apply { initCause(error) }
    }
    failure?.let { throw it }
    return results
}

internal fun populateCallSiteTrigramMetadata(
    target: LongArray,
    postingCounts: IntArray,
    stringId: Int,
    stringTable: StringTable,
    seenTrigrams: IntOpenHashSet
): Int {
    val value = stringTable.get(stringId).lowercase()
    var signature = 0L
    var postingCount = 0
    seenTrigrams.clear()
    for (position in 0..value.length - MIN_CALL_SITE_TRIGRAM_LENGTH) {
        val hash = callSiteTrigramHash(value, position)
        val mixed = hash xor (hash ushr CALL_SITE_TRIGRAM_MIX_RIGHT_SHIFT) xor (hash shl CALL_SITE_TRIGRAM_MIX_LEFT_SHIFT)
        signature = signature or (1L shl (hash and TRIGRAM_SIGNATURE_MASK))
        signature = signature or (1L shl (mixed and TRIGRAM_SIGNATURE_MASK))
        if (seenTrigrams.add(hash)) postingCount++
    }
    target[stringId] = signature
    postingCounts[stringId] = postingCount
    return postingCount
}

internal fun populateCallSiteTrigramPostings(
    target: LongArray,
    start: Int,
    expectedCount: Int,
    stringId: Int,
    stringTable: StringTable,
    seenTrigrams: IntOpenHashSet
): Int {
    val value = stringTable.get(stringId).lowercase()
    var resultIndex = start
    seenTrigrams.clear()
    for (position in 0..value.length - MIN_CALL_SITE_TRIGRAM_LENGTH) {
        val trigram = callSiteTrigramHash(value, position)
        if (seenTrigrams.add(trigram)) target[resultIndex++] = trigramKey(trigram, stringId)
    }
    check(resultIndex - start == expectedCount)
    return resultIndex
}

internal fun callSiteTrigramHash(value: String, position: Int): Int =
    (value[position].code * CALL_SITE_STRING_HASH_FACTOR + value[position + 1].code) * CALL_SITE_STRING_HASH_FACTOR +
        value[position + 2].code

internal fun trigramKey(trigram: Int, stringId: Int): Long =
    (trigram.toLong() shl Int.SIZE_BITS) or (stringId.toLong() and INT_UNSIGNED_MASK)
