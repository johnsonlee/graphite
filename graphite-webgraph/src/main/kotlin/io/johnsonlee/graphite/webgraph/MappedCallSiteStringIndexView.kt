@file:Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught")

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import it.unimi.dsi.lang.MutableString
import java.io.Closeable
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

internal interface CallSiteStringIdMembership {
    fun containsPropertyStringId(
        propertyIndex: Int,
        stringId: Int,
        workConsumer: GraphWorkConsumer?
    ): Boolean
}

/**
 * Integrity-checked, read-only view over the existing `graph.callsite-string-index` format.
 *
 * The complete retained index materializes every posting into heap arrays. Broad cold scans only
 * need the already-persisted property directories, node postings, and trigram postings, so this
 * view validates the existing file once and maps those regions without defining another format.
 */
internal class MappedCallSiteStringIndexView private constructor(
    private val propertyStringIds: Array<IntBuffer>,
    private val propertyPostingEnds: Array<IntBuffer>,
    private val propertyPostingNodeIds: Array<IntBuffer>,
    private val trigramPostings: LongBuffer,
    private val callSiteCount: Int,
    private val stringCount: Int,
    private val stringTable: StringTable,
    private val nodeOrder: (Int) -> Long
) : CallSiteStringIdMembership, Closeable {
    private val validatedPostingRanges = BoundedPostingRangeValidationCache.create()

    override fun close() {
        validatedPostingRanges?.close()
    }

    internal fun validatedPostingRangeCount(): Int = validatedPostingRanges?.size() ?: 0

    internal fun validatedPostingRangeBytes(): Long = validatedPostingRanges?.retainedBytes ?: 0L

    override fun containsPropertyStringId(
        propertyIndex: Int,
        stringId: Int,
        workConsumer: GraphWorkConsumer?
    ): Boolean {
        val values = propertyStringIds.getOrNull(propertyIndex) ?: return false
        return binarySearch(values, stringId, workConsumer) >= 0
    }

    fun exactMatchingStringIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): List<IntArray>? {
        if (predicates.any { predicate -> !predicate.canUseMappedCallSiteIndexView() }) return null
        val matchesByPredicate = mutableMapOf<MappedPredicateKey, IntArray>()
        return predicates.map { predicate ->
            if (callSiteStringPropertyIndex(predicate.property) < 0) return null
            val key = MappedPredicateKey(predicate.transform, predicate.mode, predicate.expected)
            matchesByPredicate[key] ?: exactMatchingStringIds(predicate, workConsumer)
                ?.also { matches -> matchesByPredicate[key] = matches }
                ?: return null
        }
    }

    fun exactMatchesCanFillLimit(
        predicates: List<StringPropertyPredicate>,
        exactMatchingStringIds: List<IntArray>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): Boolean {
        if (limit <= 0) return true
        if (predicates.size != exactMatchingStringIds.size) return false
        var occurrences = 0L
        predicates.indices.forEach { predicateIndex ->
            val propertyIndex = callSiteStringPropertyIndex(predicates[predicateIndex].property)
            if (propertyIndex < 0) return false
            val stringIds = propertyStringIds[propertyIndex]
            val postingEnds = propertyPostingEnds[propertyIndex]
            exactMatchingStringIds[predicateIndex].forEach { stringId ->
                val row = binarySearch(stringIds, stringId, workConsumer)
                if (row < 0) return@forEach
                val end = postingEnds.get(row)
                val start = if (row == 0) 0 else postingEnds.get(row - 1)
                if (start !in 0..end || end > callSiteCount) return false
                occurrences += end - start
                if (occurrences >= limit) return true
            }
        }
        return false
    }

    fun matchingNodeIds(
        predicates: List<StringPropertyPredicate>,
        exactMatchingStringIds: List<IntArray>,
        workConsumer: GraphWorkConsumer?
    ): Sequence<Int>? {
        if (predicates.size != exactMatchingStringIds.size) return null
        val ranges = mutableListOf<MappedPostingCursor>()
        predicates.indices.forEach { predicateIndex ->
            val propertyIndex = callSiteStringPropertyIndex(predicates[predicateIndex].property)
            if (propertyIndex < 0) return null
            exactMatchingStringIds[predicateIndex].forEach { stringId ->
                val range = postingRange(propertyIndex, stringId, workConsumer) ?: return@forEach
                val cursor = validatedPostingCursor(
                    propertyIndex,
                    range.row,
                    propertyPostingNodeIds[propertyIndex],
                    range.positions,
                    workConsumer
                ) ?: return null
                ranges += cursor
            }
        }
        return sequence {
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            val pending = PriorityQueue<MappedPostingCursor>(
                compareBy<MappedPostingCursor> { cursor -> cursor.order }
                    .thenBy { cursor -> cursor.nodeId }
            )
            ranges.filterTo(pending, MappedPostingCursor::hasCurrent)
            var previousNodeId = -1
            var visited = 0
            try {
                while (pending.isNotEmpty()) {
                    if ((visited++ and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                    val cursor = pending.remove()
                    accounting.consume()
                    val nodeId = cursor.nodeId
                    if (nodeId != previousNodeId) {
                        accounting.flush()
                        yield(nodeId)
                        previousNodeId = nodeId
                    }
                    if (cursor.advance()) pending.add(cursor)
                }
            } finally {
                accounting.flush()
            }
        }
    }

    private fun validatedPostingCursor(
        propertyIndex: Int,
        row: Int,
        postings: IntBuffer,
        range: IntRange,
        workConsumer: GraphWorkConsumer?
    ): MappedPostingCursor? {
        val key = propertyIndex.toLong() shl Int.SIZE_BITS or (row.toLong() and UINT_MASK)
        validatedPostingRanges?.get(key)?.let { valid ->
            return if (valid) MappedPostingCursor(postings, range, nodeOrder) else null
        }
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val orders = LongArray(range.last - range.first + 1)
        var previousOrder = Long.MIN_VALUE
        var valid = true
        try {
            for (position in range) {
                if ((position and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                accounting.consume()
                val order = nodeOrder(postings.get(position))
                orders[position - range.first] = order
                if (order < 0L || order <= previousOrder) valid = false
                previousOrder = order
            }
        } finally {
            accounting.flush()
        }
        val accepted = validatedPostingRanges?.putIfAbsent(key, valid) ?: valid
        if (!accepted) return null
        return MappedPostingCursor(postings, range, nodeOrder, orders)
    }

    private fun postingRange(
        propertyIndex: Int,
        stringId: Int,
        workConsumer: GraphWorkConsumer?
    ): IndexedPostingRange? {
        val row = binarySearch(propertyStringIds[propertyIndex], stringId, workConsumer)
        if (row < 0) return null
        val end = propertyPostingEnds[propertyIndex].get(row)
        val start = if (row == 0) 0 else propertyPostingEnds[propertyIndex].get(row - 1)
        return IndexedPostingRange(row, start until end)
    }

    private fun exactMatchingStringIds(
        predicate: StringPropertyPredicate,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        val expected = if (predicate.transform == null) predicate.expected.lowercase() else predicate.expected
        val spans = mutableListOf<IntRange>()
        val seen = HashSet<Int>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (position in 0..expected.length - TRIGRAM_LENGTH) {
                val trigram = mappedTrigramHash(expected, position)
                if (!seen.add(trigram)) continue
                accounting.consume()
                val span = trigramPostingRange(trigram) ?: return IntArray(0)
                spans += span
            }
            if (spans.isEmpty()) return null
            val anchor = spans.minBy { range -> range.last - range.first }
            val actual = MutableString()
            val matches = IntArray(anchor.last - anchor.first + 1)
            var size = 0
            for (postingIndex in anchor) {
                if ((postingIndex and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                accounting.consume()
                val stringId = trigramPostings.get(postingIndex).toInt()
                if (stringId !in 0 until stringCount) return null
                stringTable.get(stringId, actual)
                if (reusableContains(actual, predicate.transform, predicate.expected)) {
                    matches[size++] = stringId
                }
            }
            return matches.copyOf(size)
        } finally {
            accounting.flush()
        }
    }

    private fun trigramPostingRange(trigram: Int): IntRange? {
        var low = 0
        var high = trigramPostings.limit()
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (postingTrigram(middle) < trigram) low = middle + 1 else high = middle
        }
        val start = low
        if (start >= trigramPostings.limit() || postingTrigram(start) != trigram) return null
        high = trigramPostings.limit()
        while (low < high) {
            val middle = (low + high).ushr(1)
            if (postingTrigram(middle) <= trigram) low = middle + 1 else high = middle
        }
        return start until low
    }

    private fun postingTrigram(index: Int): Int = (trigramPostings.get(index) ushr Int.SIZE_BITS).toInt()

    companion object {
        @Suppress("LongParameterList")
        fun load(
            path: Path,
            expectedStringCount: Int,
            expectedCallSiteCount: Int,
            expectedContentIdentity: ByteArray,
            stringTable: StringTable,
            nodeIdCapacity: Int,
            nodeOrder: (Int) -> Long,
            workConsumer: GraphWorkConsumer?
        ): MappedCallSiteStringIndexView? {
            if (!Files.isRegularFile(path) ||
                expectedContentIdentity.size != CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES
            ) return null
            checkViewInterrupted()
            return try {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val fileBytes = channel.size()
                    require(fileBytes in MIN_INDEX_VIEW_BYTES..Int.MAX_VALUE.toLong())
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, fileBytes)
                        .order(ByteOrder.BIG_ENDIAN)
                    val header = mapped.duplicate().order(ByteOrder.BIG_ENDIAN)
                    require(header.int == CALL_SITE_STRING_INDEX_MAGIC)
                    require(header.int == CALL_SITE_STRING_INDEX_VERSION)
                    val stringCount = header.int
                    val callSiteCount = header.int
                    require(stringCount == expectedStringCount && callSiteCount == expectedCallSiteCount)
                    val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
                    header.get(identity)
                    require(identity.contentEquals(expectedContentIdentity))
                    val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { header.int }
                    require(uniqueCounts.all { count -> count in 0..stringCount })
                    val postingCount = header.int
                    require(postingCount > 0)
                    require(header.long > 0L)

                    var offset = CALL_SITE_STRING_INDEX_HEADER_BYTES.toLong()
                    val propertyStrings = Array(CALL_SITE_STRING_PROPERTY_COUNT) {
                        IntBuffer.allocate(0).asReadOnlyBuffer()
                    }
                    val propertyEnds = Array(CALL_SITE_STRING_PROPERTY_COUNT) {
                        IntBuffer.allocate(0).asReadOnlyBuffer()
                    }
                    val propertyPostings = Array(CALL_SITE_STRING_PROPERTY_COUNT) {
                        IntBuffer.allocate(0).asReadOnlyBuffer()
                    }
                    repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                        val count = uniqueCounts[propertyIndex]
                        val directoryBytes = count.toLong() * Int.SIZE_BYTES
                        propertyStrings[propertyIndex] = mappedInts(mapped, offset, count)
                        offset += directoryBytes
                        propertyEnds[propertyIndex] = mappedInts(mapped, offset, count)
                        offset += directoryBytes
                        propertyPostings[propertyIndex] = mappedInts(mapped, offset, callSiteCount)
                        offset += callSiteCount.toLong() * Int.SIZE_BYTES
                    }
                    val signaturesOffset = offset
                    offset += stringCount.toLong() * Long.SIZE_BYTES
                    val expectedBytes = offset + postingCount.toLong() * Long.SIZE_BYTES + Long.SIZE_BYTES
                    require(expectedBytes == fileBytes)
                    val postings = mappedLongs(mapped, offset, postingCount)
                    require(
                        validatePersistentIndex(
                            mapped,
                            propertyStrings,
                            propertyEnds,
                            propertyPostings,
                            signaturesOffset,
                            offset,
                            uniqueCounts,
                            stringCount,
                            callSiteCount,
                            nodeIdCapacity,
                            postingCount,
                            expectedContentIdentity,
                            workConsumer
                        )
                    )
                    MappedCallSiteStringIndexView(
                        propertyStrings,
                        propertyEnds,
                        propertyPostings,
                        postings,
                        callSiteCount,
                        stringCount,
                        stringTable,
                        nodeOrder
                    )
                }
            } catch (error: Exception) {
                when (error) {
                    is CancellationException -> throw error
                    is IOException, is IllegalArgumentException, is ArithmeticException,
                    is BufferUnderflowException, is IndexOutOfBoundsException -> null
                    else -> throw error
                }
            }
        }

        @Suppress("LongParameterList")
        private fun validatePersistentIndex(
            mapped: ByteBuffer,
            propertyStrings: Array<IntBuffer>,
            propertyEnds: Array<IntBuffer>,
            propertyPostings: Array<IntBuffer>,
            signaturesOffset: Long,
            postingsOffset: Long,
            uniqueCounts: IntArray,
            stringCount: Int,
            callSiteCount: Int,
            nodeIdCapacity: Int,
            postingCount: Int,
            contentIdentity: ByteArray,
            workConsumer: GraphWorkConsumer?
        ): Boolean {
            val validator = PersistentIndexViewValidator(mapped, workConsumer)
            validator.updateInt(CALL_SITE_STRING_INDEX_MAGIC)
            validator.updateInt(CALL_SITE_STRING_INDEX_VERSION)
            validator.updateInt(stringCount)
            validator.updateInt(callSiteCount)
            validator.updateBytes(contentIdentity)
            uniqueCounts.forEach(validator::updateInt)
            validator.updateInt(postingCount)
            validator.updateLong(mapped.getLong(CALL_SITE_STRING_INDEX_HEADER_BYTES - Long.SIZE_BYTES))

            repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                var previousStringId = -1
                validator.updateInts(propertyStrings[propertyIndex]) { stringId ->
                    require(stringId in 0 until stringCount && stringId > previousStringId)
                    previousStringId = stringId
                }
                var previousEnd = 0
                validator.updateInts(propertyEnds[propertyIndex]) { end ->
                    require(end > previousEnd && end <= callSiteCount)
                    previousEnd = end
                }
                require(previousEnd == callSiteCount)
                validator.updateInts(propertyPostings[propertyIndex]) { nodeId ->
                    require(nodeId in 0 until nodeIdCapacity)
                }
            }
            validator.updateLongs(signaturesOffset, stringCount)
            var previousPosting = Long.MIN_VALUE
            validator.updateLongs(postingsOffset, postingCount) { posting ->
                require(posting >= previousPosting && posting.toInt() in 0 until stringCount)
                previousPosting = posting
            }
            val expectedChecksum = mapped.getLong(Math.toIntExact(postingsOffset + postingCount.toLong() * Long.SIZE_BYTES))
            return validator.value == expectedChecksum
        }

        private fun mappedInts(mapped: ByteBuffer, offset: Long, count: Int): IntBuffer {
            if (count == 0) return IntBuffer.allocate(0).asReadOnlyBuffer()
            val bytes = Math.multiplyExact(count, Int.SIZE_BYTES)
            return mappedSlice(mapped, offset, bytes).asIntBuffer().asReadOnlyBuffer()
        }

        private fun mappedLongs(mapped: ByteBuffer, offset: Long, count: Int): LongBuffer {
            val bytes = Math.multiplyExact(count, Long.SIZE_BYTES)
            return mappedSlice(mapped, offset, bytes).asLongBuffer().asReadOnlyBuffer()
        }

        private fun mappedSlice(mapped: ByteBuffer, offset: Long, bytes: Int): ByteBuffer {
            val start = Math.toIntExact(offset)
            val end = Math.addExact(start, bytes)
            require(end <= mapped.limit())
            return mapped.duplicate().order(ByteOrder.BIG_ENDIAN).apply {
                position(start)
                limit(end)
            }.slice().order(ByteOrder.BIG_ENDIAN)
        }
    }
}

private class PersistentIndexViewValidator(
    private val mapped: ByteBuffer,
    private val workConsumer: GraphWorkConsumer?
) {
    private val checksum = CRC32()
    private val scratch = ByteBuffer.allocate(CHECKSUM_CHUNK_BYTES).order(ByteOrder.BIG_ENDIAN)

    val value: Long
        get() = checksum.value

    fun updateInt(value: Int) {
        scratch.clear()
        scratch.putInt(Integer.reverseBytes(value))
        checksum.update(scratch.array(), 0, Int.SIZE_BYTES)
        consumeGraphWork(workConsumer, 1L)
    }

    fun updateLong(value: Long) {
        scratch.clear()
        scratch.putLong(java.lang.Long.reverseBytes(value))
        checksum.update(scratch.array(), 0, Long.SIZE_BYTES)
        consumeGraphWork(workConsumer, 1L)
    }

    fun updateBytes(values: ByteArray) {
        checksum.update(values)
        consumeGraphWork(workConsumer, values.size.toLong())
    }

    fun updateInts(values: IntBuffer, validate: (Int) -> Unit = {}) {
        var index = 0
        while (index < values.limit()) {
            checkViewInterrupted()
            scratch.clear()
            val start = index
            val end = minOf(values.limit(), index + scratch.capacity() / Int.SIZE_BYTES)
            while (index < end) {
                val value = values.get(index++)
                validate(value)
                scratch.putInt(Integer.reverseBytes(value))
            }
            checksum.update(scratch.array(), 0, scratch.position())
            consumeGraphWork(workConsumer, (end - start).toLong())
        }
    }

    fun updateInts(offset: Long, count: Int) {
        updateInts(mappedSlice(offset, count, Int.SIZE_BYTES).asIntBuffer())
    }

    fun updateLongs(offset: Long, count: Int, validate: (Long) -> Unit = {}) {
        val values = mappedSlice(offset, count, Long.SIZE_BYTES).asLongBuffer()
        var index = 0
        while (index < values.limit()) {
            checkViewInterrupted()
            scratch.clear()
            val start = index
            val end = minOf(values.limit(), index + scratch.capacity() / Long.SIZE_BYTES)
            while (index < end) {
                val value = values.get(index++)
                validate(value)
                scratch.putLong(java.lang.Long.reverseBytes(value))
            }
            checksum.update(scratch.array(), 0, scratch.position())
            consumeGraphWork(workConsumer, (end - start).toLong())
        }
    }

    private fun mappedSlice(offset: Long, count: Int, width: Int): ByteBuffer {
        val start = Math.toIntExact(offset)
        val bytes = Math.multiplyExact(count, width)
        val end = Math.addExact(start, bytes)
        require(end <= mapped.limit())
        return mapped.duplicate().order(ByteOrder.BIG_ENDIAN).apply {
            position(start)
            limit(end)
        }.slice().order(ByteOrder.BIG_ENDIAN)
    }
}

private fun StringPropertyPredicate.canUseMappedCallSiteIndexView(): Boolean =
    mode == StringMatchMode.CONTAINS && expected.length >= TRIGRAM_LENGTH &&
        (transform == StringValueTransform.LOWERCASE || transform == null && expected.all { it.code <= ASCII_MAX })

private fun mappedTrigramHash(value: String, position: Int): Int =
    (value[position].code * STRING_HASH_FACTOR + value[position + 1].code) * STRING_HASH_FACTOR +
        value[position + 2].code

private fun binarySearch(
    values: IntBuffer,
    target: Int,
    workConsumer: GraphWorkConsumer?
): Int {
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    try {
        var low = 0
        var high = values.limit() - 1
        while (low <= high) {
            accounting.consume()
            val middle = (low + high).ushr(1)
            val value = values.get(middle)
            when {
                value < target -> low = middle + 1
                value > target -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    } finally {
        accounting.flush()
    }
}

private fun checkViewInterrupted() {
    if (Thread.currentThread().isInterrupted) {
        throw CancellationException("Mapped CallSite string index view interrupted")
    }
}

private data class MappedPredicateKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

private data class IndexedPostingRange(val row: Int, val positions: IntRange)

/**
 * Fixed-size direct-mapped cache for immutable posting-range validation results.
 *
 * A collision only repeats validation on a later lookup. The primitive arrays avoid per-query
 * boxed keys and entries, and the shared reservation makes the retained heap visible to the same
 * budget as the complete CallSite index. If the reservation is unavailable, callers validate
 * every selected range without retaining query-dependent state.
 */
private class BoundedPostingRangeValidationCache private constructor(
    private val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
) : Closeable {
    private val keys = LongArray(VALIDATED_POSTING_RANGE_CACHE_CAPACITY)
    private val states = ByteArray(VALIDATED_POSTING_RANGE_CACHE_CAPACITY)
    private var entries = 0
    private var closed = false

    val retainedBytes: Long
        @Synchronized get() = if (closed) 0L else reservation.bytes

    @Synchronized
    operator fun get(key: Long): Boolean? {
        if (closed) return null
        val slot = slot(key)
        if (states[slot] == VALIDATION_EMPTY || keys[slot] != key) return null
        return states[slot] == VALIDATION_VALID
    }

    @Synchronized
    fun putIfAbsent(key: Long, valid: Boolean): Boolean {
        if (closed) return valid
        val slot = slot(key)
        if (states[slot] != VALIDATION_EMPTY && keys[slot] == key) {
            return states[slot] == VALIDATION_VALID
        }
        if (states[slot] == VALIDATION_EMPTY) entries++
        keys[slot] = key
        states[slot] = if (valid) VALIDATION_VALID else VALIDATION_INVALID
        return valid
    }

    @Synchronized
    fun size(): Int = if (closed) 0 else entries

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entries = 0
        reservation.close()
    }

    private fun slot(key: Long): Int {
        val folded = key xor (key ushr Int.SIZE_BITS)
        val spread = folded xor (folded ushr VALIDATION_CACHE_HASH_SHIFT)
        return spread.toInt() and (VALIDATED_POSTING_RANGE_CACHE_CAPACITY - 1)
    }

    companion object {
        fun create(): BoundedPostingRangeValidationCache? {
            val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(
                VALIDATED_POSTING_RANGE_CACHE_RETAINED_BYTES
            ) ?: return null
            return try {
                BoundedPostingRangeValidationCache(reservation)
            } catch (error: Throwable) {
                reservation.close()
                throw error
            }
        }
    }
}

private class MappedPostingCursor(
    private val postings: IntBuffer,
    range: IntRange,
    private val nodeOrder: (Int) -> Long,
    private val validatedOrders: LongArray? = null
) {
    private val firstPosition = range.first
    private var position = range.first
    private val lastPosition = range.last

    var nodeId: Int = postings.get(position)
        private set
    var order: Long = orderAt(position)
        private set

    fun hasCurrent(): Boolean = position <= lastPosition

    fun advance(): Boolean {
        if (++position > lastPosition) return false
        nodeId = postings.get(position)
        order = orderAt(position)
        return true
    }

    private fun orderAt(index: Int): Long = validatedOrders?.get(index - firstPosition) ?: nodeOrder(nodeId)
}

internal const val MAPPED_POSTING_RANGE_VALIDATION_CACHE_CAPACITY = 1 shl 10
internal const val MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES = 16L * 1024
private const val VALIDATED_POSTING_RANGE_CACHE_CAPACITY = MAPPED_POSTING_RANGE_VALIDATION_CACHE_CAPACITY
private const val VALIDATED_POSTING_RANGE_CACHE_RETAINED_BYTES =
    MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES
private const val VALIDATION_CACHE_HASH_SHIFT = 16
private const val VALIDATION_EMPTY: Byte = 0
private const val VALIDATION_VALID: Byte = 1
private const val VALIDATION_INVALID: Byte = 2
private const val TRIGRAM_LENGTH = 3
private const val STRING_HASH_FACTOR = 31
private const val ASCII_MAX = 0x7f
private const val UINT_MASK = 0xffff_ffffL
private const val VIEW_INTERRUPTION_POLL_MASK = 1_023
private const val CHECKSUM_CHUNK_BYTES = 1 shl 20
private const val MIN_INDEX_VIEW_BYTES = CALL_SITE_STRING_INDEX_HEADER_BYTES + Long.SIZE_BYTES
