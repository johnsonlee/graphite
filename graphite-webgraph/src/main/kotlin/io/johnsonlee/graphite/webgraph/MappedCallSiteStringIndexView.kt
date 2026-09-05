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

internal interface MappedCallSiteNodeAccessor {
    fun encounterOrder(nodeId: Int): Long

    fun tupleMatches(nodeId: Int, propertyIndexes: IntArray, stringIds: IntArray): Boolean
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
    private val nodeAccessor: MappedCallSiteNodeAccessor,
    private val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
) : CallSiteStringIdMembership, Closeable {
    private val cacheLock = Any()
    private val matchingNodeIds = LinkedHashMap<MappedNodeMatchKey, CachedMappedNodeIds>(
        MAX_MAPPED_NODE_MATCH_CACHE_ENTRIES + 1,
        MAPPED_NODE_MATCH_CACHE_LOAD_FACTOR,
        true
    )
    private var matchingNodeCacheBytes = 0L
    private var closed = false

    override fun close() {
        val releaseReservation = synchronized(cacheLock) {
            if (closed) {
                false
            } else {
                closed = true
                matchingNodeIds.clear()
                matchingNodeCacheBytes = 0L
                true
            }
        }
        if (releaseReservation) reservation.close()
    }

    internal fun retainedQueryCacheBytes(): Long = synchronized(cacheLock) { matchingNodeCacheBytes }

    internal fun retainedQueryCacheEntries(): Int = synchronized(cacheLock) { matchingNodeIds.size }

    override fun containsPropertyStringId(
        propertyIndex: Int,
        stringId: Int,
        workConsumer: GraphWorkConsumer?
    ): Boolean {
        val values = propertyStringIds.getOrNull(propertyIndex) ?: return false
        return binarySearch(values, stringId, workConsumer) >= 0
    }

    fun supportsPredicates(predicates: List<StringPropertyPredicate>): Boolean =
        predicates.all { predicate ->
            callSiteStringPropertyIndex(predicate.property) >= 0 && predicate.canUseMappedCallSiteIndexView()
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

    /**
     * Returns a fully validated prefix for the routed single-graph path and admits it only after
     * the caller consumes that complete bounded prefix. The cached array is never exposed.
     */
    fun matchingNodeIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?,
        limit: Int
    ): Sequence<Int>? {
        if (limit <= 0) return emptySequence()
        if (predicates.any { predicate -> !predicate.canUseMappedCallSiteIndexView() }) return null
        val cacheKey = if (limit <= MAX_MAPPED_NODE_MATCH_CACHE_LIMIT) {
            MappedNodeMatchKey(predicates.toList(), limit)
        } else {
            null
        }
        val cachedNodeIds = cacheKey?.let { key ->
            synchronized(cacheLock) {
                if (closed) null else matchingNodeIds[key]?.nodeIds
            }
        }
        if (cachedNodeIds != null) {
            checkViewInterrupted()
            consumeGraphWork(workConsumer, cachedNodeIds.size.coerceAtLeast(1).toLong())
            return cachedNodeIds.asSequence()
        }

        checkViewInterrupted()
        val exactMatches = exactMatchingStringIds(predicates, workConsumer) ?: return null
        val source = matchingNodeIds(predicates, exactMatches, workConsumer) ?: return null
        if (cacheKey == null) return source.take(limit)
        return cacheMatchingNodeIdsOnComplete(cacheKey, source.take(limit))
    }

    private fun cacheMatchingNodeIdsOnComplete(
        key: MappedNodeMatchKey,
        source: Sequence<Int>
    ): Sequence<Int> = sequence {
        val consumed = IntArray(key.limit)
        var size = 0
        for (nodeId in source) {
            consumed[size++] = nodeId
            if (size == key.limit) cacheMatchingNodeIds(key, consumed)
            yield(nodeId)
        }
        if (size < key.limit) cacheMatchingNodeIds(key, consumed.copyOf(size))
    }

    private fun cacheMatchingNodeIds(key: MappedNodeMatchKey, nodeIds: IntArray) {
        val entryBytes = estimatedMappedNodeMatchCacheBytes(key, nodeIds)
        if (entryBytes > MAX_MAPPED_NODE_MATCH_CACHE_BYTES) return
        synchronized(cacheLock) {
            if (closed || matchingNodeIds.containsKey(key)) return
            while (matchingNodeIds.isNotEmpty() &&
                (matchingNodeIds.size >= MAX_MAPPED_NODE_MATCH_CACHE_ENTRIES ||
                    matchingNodeCacheBytes > MAX_MAPPED_NODE_MATCH_CACHE_BYTES - entryBytes)
            ) {
                val eldest = matchingNodeIds.entries.iterator().next()
                matchingNodeIds.remove(eldest.key)
                matchingNodeCacheBytes -= eldest.value.retainedBytes
                reservation.shrinkTo(reservation.bytes - eldest.value.retainedBytes)
            }
            val retainedAfter = runCatching {
                Math.addExact(matchingNodeCacheBytes, entryBytes)
            }.getOrNull() ?: return
            if (!reservation.tryGrowTo(retainedAfter)) return
            matchingNodeIds[key] = CachedMappedNodeIds(nodeIds, entryBytes)
            matchingNodeCacheBytes = retainedAfter
        }
    }

    fun matchingNodeIds(
        predicates: List<StringPropertyPredicate>,
        exactMatchingStringIds: List<IntArray>,
        workConsumer: GraphWorkConsumer?
    ): Sequence<Int>? {
        if (predicates.size != exactMatchingStringIds.size) return null
        val sources = mutableListOf<MappedPostingSource>()
        predicates.indices.forEach { predicateIndex ->
            val propertyIndex = callSiteStringPropertyIndex(predicates[predicateIndex].property)
            if (propertyIndex < 0) return null
            exactMatchingStringIds[predicateIndex].forEach { stringId ->
                val range = postingRange(propertyIndex, stringId, workConsumer) ?: return@forEach
                val postings = propertyPostingNodeIds[propertyIndex]
                if (!validatePostingRange(
                    postings,
                    range.positions,
                    workConsumer
                )) return null
                sources += MappedPostingSource(postings, range.positions)
            }
        }
        return sequence {
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            val pending = PriorityQueue<MappedPostingCursor>(
                compareBy<MappedPostingCursor> { cursor -> cursor.order }
                    .thenBy { cursor -> cursor.nodeId }
            )
            sources.asSequence()
                .map { source -> MappedPostingCursor(source.postings, source.positions, nodeAccessor) }
                .filterTo(pending, MappedPostingCursor::hasCurrent)
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

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "ReturnCount")
    fun firstNodesMatchingTuples(
        propertyIndexes: IntArray,
        tuples: Array<IntArray>,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        if (tuples.any { stringIds -> stringIds.size != propertyIndexes.size }) return null
        val matchedNodeIds = IntArray(tuples.size) { -1 }
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var inspected = 0
        try {
            for (tupleIndex in tuples.indices) {
                val stringIds = tuples[tupleIndex]
                var anchorProperty = -1
                var anchorStart = 0
                var anchorEnd = Int.MAX_VALUE
                var missing = false
                for (index in propertyIndexes.indices) {
                    val propertyIndex = propertyIndexes[index]
                    if (propertyIndex < 0) continue
                    if (propertyIndex >= propertyPostingNodeIds.size || stringIds[index] < 0) return null
                    val row = binarySearch(propertyStringIds[propertyIndex], stringIds[index], accounting)
                    if (row < 0) {
                        missing = true
                        break
                    }
                    val end = propertyPostingEnds[propertyIndex].get(row)
                    val start = if (row == 0) 0 else propertyPostingEnds[propertyIndex].get(row - 1)
                    if (start !in 0..end || end > callSiteCount) return null
                    if (end - start < anchorEnd - anchorStart) {
                        anchorProperty = propertyIndex
                        anchorStart = start
                        anchorEnd = end
                    }
                }
                if (missing) {
                    continue
                }
                if (anchorProperty < 0 || anchorEnd == Int.MAX_VALUE) return null
                val postings = propertyPostingNodeIds[anchorProperty]
                var previousOrder = Long.MIN_VALUE
                var matchedNodeId = -1
                for (position in anchorStart until anchorEnd) {
                    if ((inspected++ and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                    accounting.consume()
                    val nodeId = postings.get(position)
                    val order = nodeAccessor.encounterOrder(nodeId)
                    if (order < 0L || order <= previousOrder) return null
                    previousOrder = order
                    if (matchedNodeId < 0 && nodeAccessor.tupleMatches(nodeId, propertyIndexes, stringIds)) {
                        matchedNodeId = nodeId
                    }
                }
                matchedNodeIds[tupleIndex] = matchedNodeId
            }
        } finally {
            accounting.flush()
        }
        return matchedNodeIds
    }

    private fun validatePostingRange(
        postings: IntBuffer,
        range: IntRange,
        workConsumer: GraphWorkConsumer?
    ): Boolean {
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var previousOrder = Long.MIN_VALUE
        var valid = true
        try {
            for (position in range) {
                if ((position and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                accounting.consume()
                val order = nodeAccessor.encounterOrder(postings.get(position))
                if (order < 0L || order <= previousOrder) valid = false
                previousOrder = order
            }
        } finally {
            accounting.flush()
        }
        return valid
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
        return IndexedPostingRange(start until end)
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
        @Suppress("LongParameterList", "ThrowsCount")
        fun load(
            path: Path,
            expectedStringCount: Int,
            expectedCallSiteCount: Int,
            expectedContentIdentity: ByteArray,
            stringTable: StringTable,
            nodeIdCapacity: Int,
            nodeAccessor: MappedCallSiteNodeAccessor,
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
                    val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(0L) ?: return null
                    MappedCallSiteStringIndexView(
                        propertyStrings,
                        propertyEnds,
                        propertyPostings,
                        postings,
                        callSiteCount,
                        stringCount,
                        stringTable,
                        nodeAccessor,
                        reservation
                    )
                }
            } catch (error: Exception) {
                when (error) {
                    is MappedViewGraphWorkException -> throw error.failure
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
        consumeViewGraphWork(workConsumer, 1L)
    }

    fun updateLong(value: Long) {
        scratch.clear()
        scratch.putLong(java.lang.Long.reverseBytes(value))
        checksum.update(scratch.array(), 0, Long.SIZE_BYTES)
        consumeViewGraphWork(workConsumer, 1L)
    }

    fun updateBytes(values: ByteArray) {
        checksum.update(values)
        consumeViewGraphWork(workConsumer, values.size.toLong())
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
            consumeViewGraphWork(workConsumer, (end - start).toLong())
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
            consumeViewGraphWork(workConsumer, (end - start).toLong())
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

private class MappedViewGraphWorkException(val failure: Exception) : RuntimeException(failure)

private fun consumeViewGraphWork(workConsumer: GraphWorkConsumer?, units: Long) {
    try {
        consumeGraphWork(workConsumer, units)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw MappedViewGraphWorkException(error)
    }
}

internal fun StringPropertyPredicate.canUseMappedCallSiteIndexView(): Boolean =
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

private fun binarySearch(
    values: IntBuffer,
    target: Int,
    accounting: BufferedGraphWorkConsumer
): Int {
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

private data class MappedNodeMatchKey(
    val predicates: List<StringPropertyPredicate>,
    val limit: Int
)

private data class CachedMappedNodeIds(
    val nodeIds: IntArray,
    val retainedBytes: Long
)

private fun estimatedMappedNodeMatchCacheBytes(
    key: MappedNodeMatchKey,
    nodeIds: IntArray
): Long = try {
    var keyCharacters = 0L
    key.predicates.forEach { predicate ->
        keyCharacters = Math.addExact(keyCharacters, predicate.property.length.toLong())
        keyCharacters = Math.addExact(keyCharacters, predicate.expected.length.toLong())
    }
    Math.addExact(
        MAPPED_NODE_MATCH_CACHE_ENTRY_ESTIMATED_BYTES + MAPPED_PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.addExact(
            Math.multiplyExact(
                key.predicates.size.toLong(),
                MAPPED_NODE_MATCH_CACHE_PREDICATE_ESTIMATED_BYTES
            ),
            Math.addExact(
                Math.multiplyExact(keyCharacters, Char.SIZE_BYTES.toLong()),
                Math.multiplyExact(nodeIds.size.toLong(), Int.SIZE_BYTES.toLong())
            )
        )
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

private data class IndexedPostingRange(val positions: IntRange)

private data class MappedPostingSource(val postings: IntBuffer, val positions: IntRange)

private class MappedPostingCursor(
    private val postings: IntBuffer,
    range: IntRange,
    private val nodeAccessor: MappedCallSiteNodeAccessor
) {
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

    private fun orderAt(@Suppress("UNUSED_PARAMETER") index: Int): Long = nodeAccessor.encounterOrder(nodeId)
}

private const val TRIGRAM_LENGTH = 3
private const val STRING_HASH_FACTOR = 31
private const val ASCII_MAX = 0x7f
private const val VIEW_INTERRUPTION_POLL_MASK = 1_023
private const val CHECKSUM_CHUNK_BYTES = 1 shl 20
private const val MIN_INDEX_VIEW_BYTES = CALL_SITE_STRING_INDEX_HEADER_BYTES + Long.SIZE_BYTES
private const val MAX_MAPPED_NODE_MATCH_CACHE_ENTRIES = 16
private const val MAX_MAPPED_NODE_MATCH_CACHE_BYTES = 256L * 1024
private const val MAX_MAPPED_NODE_MATCH_CACHE_LIMIT = 200
private const val MAPPED_NODE_MATCH_CACHE_LOAD_FACTOR = 0.75f
private const val MAPPED_NODE_MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 192L
private const val MAPPED_NODE_MATCH_CACHE_PREDICATE_ESTIMATED_BYTES = 72L
private const val MAPPED_PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES = 24L
