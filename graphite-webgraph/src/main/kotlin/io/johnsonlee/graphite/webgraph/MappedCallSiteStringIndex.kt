@file:Suppress("ComplexCondition", "LoopWithTooManyJumpStatements", "MagicNumber", "ReturnCount")

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import java.io.Closeable
import java.util.concurrent.CancellationException

/** Compact CSR indexes for the four strings searched by broad CallSite queries. */
internal class MappedCallSiteStringIndex(
    private val properties: Array<PropertyCsr>,
    private val stringTable: StringTable,
    private val nodeOrder: (Int) -> Long,
    private val nodeIdCapacity: Int,
    private val rawStringPropertyId: (Int, Int) -> Int,
    private val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
) : Closeable {

    init {
        require(properties.size == CALL_SITE_STRING_PROPERTY_COUNT)
        require(properties.map(PropertyCsr::postingCount).distinct().size <= 1)
    }

    private val trigramSignatures = LongArray(stringTable.size()).also { signatures ->
        properties.forEach { property -> property.populateTrigramSignatures(signatures, stringTable) }
    }

    val retainedBytes: Long
        get() = reservation.bytes

    val prefersSerialScan: Boolean
        get() = retainedBytes <= MAX_SERIAL_CALL_SITE_STRING_INDEX_SCAN_BYTES

    fun matchingNodeIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?,
        limit: Int = Int.MAX_VALUE
    ): Sequence<Int> {
        if (limit <= 0) return emptySequence()
        val ranges = PostingRanges(properties)
        val sharedStates = mutableMapOf<CallSitePredicateKey, ByteArray?>()
        predicates.forEach { predicate ->
            val propertyIndex = callSiteStringPropertyIndex(predicate.property)
            if (propertyIndex < 0) return@forEach
            val runtime = PredicateRuntime(predicate, sharedStates, stringTable, trigramSignatures)
            properties[propertyIndex].collectMatchingRanges(propertyIndex, runtime, ranges)
        }
        if (ranges.isEmpty()) return emptySequence()
        return ranges.mergedNodeIds(nodeOrder, workConsumer, limit)
    }

    fun aggregate(
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?
    ): StringPropertyDisjunctionAggregate {
        val ranges = matchingRanges(predicates)
        if (ranges.isEmpty()) {
            return StringPropertyDisjunctionAggregate(0L, distinctProperty?.let { emptySet() })
        }
        val distinctPropertyIndex = distinctProperty?.let(::callSiteStringPropertyIndex)
        require(distinctPropertyIndex == null || distinctPropertyIndex >= 0)
        return ranges.aggregate(nodeIdCapacity, distinctPropertyIndex, rawStringPropertyId, stringTable)
    }

    private fun matchingRanges(predicates: List<StringPropertyPredicate>): PostingRanges {
        val ranges = PostingRanges(properties)
        val sharedStates = mutableMapOf<CallSitePredicateKey, ByteArray?>()
        predicates.forEach { predicate ->
            val propertyIndex = callSiteStringPropertyIndex(predicate.property)
            if (propertyIndex < 0) return@forEach
            val runtime = PredicateRuntime(predicate, sharedStates, stringTable, trigramSignatures)
            properties[propertyIndex].collectMatchingRanges(propertyIndex, runtime, ranges)
        }
        return ranges
    }

    fun distinctProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        if (limit <= 0) return emptyList()
        val propertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex).toIntArray()
        val ranges = matchingRanges(predicates)
        if (ranges.isEmpty()) return emptyList()
        return if (selectedValues == null) {
            distinctProjectionPrefix(ranges, propertyIndexes, limit, workConsumer)
        } else {
            selectedProjectionHits(ranges, propertyIndexes, selectedValues, limit, workConsumer)
        }
    }

    private fun distinctProjectionPrefix(
        ranges: PostingRanges,
        propertyIndexes: IntArray,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        val rows = mutableListOf<StringPropertyDistinctRow>()
        val idsByHash = mutableMapOf<Int, MutableList<IntArray>>()
        var inspected = 0L
        for (nodeId in ranges.mergedNodeIds(nodeOrder, workConsumer = null)) {
            inspected++
            val hash = projectionHash(nodeId, propertyIndexes)
            val bucket = idsByHash.getOrPut(hash, ::mutableListOf)
            if (bucket.any { ids -> projectionEquals(nodeId, propertyIndexes, ids) }) continue
            val ids = projectionIds(nodeId, propertyIndexes)
            bucket += ids
            rows += StringPropertyDistinctRow(nodeOrder(nodeId), projectionValues(ids))
            if (rows.size >= limit) break
        }
        consumeGraphWork(workConsumer, inspected)
        return rows
    }

    private fun selectedProjectionHits(
        ranges: PostingRanges,
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        val selectedByHash = mutableMapOf<Int, MutableList<Pair<IntArray, List<String?>>>>()
        selectedValues.forEach { values ->
            if (values.size != propertyIndexes.size) return@forEach
            val ids = IntArray(values.size)
            for (index in values.indices) {
                val propertyIndex = propertyIndexes[index]
                val value = values[index]
                val id = value?.let(stringTable::findId) ?: -1
                if (propertyIndex < 0 && value != null || propertyIndex >= 0 && id < 0) return@forEach
                ids[index] = if (propertyIndex < 0) -1 else id
            }
            selectedByHash.getOrPut(java.util.Arrays.hashCode(ids), ::mutableListOf) += ids to values
        }
        if (selectedByHash.isEmpty()) return emptyList()

        val hits = mutableListOf<StringPropertyDistinctRow>()
        val hitValues = mutableSetOf<List<String?>>()
        val matchedNodeIds = ranges.matchedNodeBitSet(nodeIdCapacity)
        var inspected = 0L
        for (wordIndex in matchedNodeIds.indices) {
            var remaining = matchedNodeIds[wordIndex]
            while (remaining != 0L) {
                val bit = java.lang.Long.numberOfTrailingZeros(remaining)
                val nodeId = (wordIndex shl BITSET_WORD_SHIFT) + bit
                inspected++
                val candidates = selectedByHash[projectionHash(nodeId, propertyIndexes)]
                candidates?.firstOrNull { (ids, values) ->
                    values !in hitValues && projectionEquals(nodeId, propertyIndexes, ids)
                }?.second?.let { values ->
                    hitValues += values
                    hits += StringPropertyDistinctRow(nodeOrder(nodeId), values)
                }
                if (hits.size >= minOf(limit, selectedValues.size)) {
                    consumeGraphWork(workConsumer, inspected)
                    return hits
                }
                remaining = remaining and (remaining - 1L)
            }
        }
        consumeGraphWork(workConsumer, inspected)
        return hits
    }

    private fun projectionHash(nodeId: Int, propertyIndexes: IntArray): Int {
        var hash = 1
        propertyIndexes.forEach { propertyIndex ->
            hash = 31 * hash + if (propertyIndex < 0) -1 else rawStringPropertyId(nodeId, propertyIndex)
        }
        return hash
    }

    private fun projectionEquals(nodeId: Int, propertyIndexes: IntArray, expected: IntArray): Boolean =
        propertyIndexes.indices.all { index ->
            val propertyIndex = propertyIndexes[index]
            expected[index] == if (propertyIndex < 0) -1 else rawStringPropertyId(nodeId, propertyIndex)
        }

    private fun projectionIds(nodeId: Int, propertyIndexes: IntArray): IntArray =
        IntArray(propertyIndexes.size) { index ->
            propertyIndexes[index].takeIf { it >= 0 }?.let { rawStringPropertyId(nodeId, it) } ?: -1
        }

    private fun projectionValues(ids: IntArray): List<String?> = ids.map { id ->
        id.takeIf { it >= 0 }?.let(stringTable::get)
    }

    override fun close() = reservation.close()

    internal class PropertyCsr(
        private val postingEnds: IntArray,
        private val usedStringIds: IntArray,
        private val postingNodeIds: IntArray
    ) {
        init {
            require(postingEnds.size == usedStringIds.size)
            require((1 until usedStringIds.size).all { index ->
                usedStringIds[index - 1] < usedStringIds[index]
            })
            require(postingEnds.lastOrNull() ?: 0 == postingNodeIds.size)
        }

        val postingCount: Int
            get() = postingNodeIds.size

        fun collectMatchingRanges(
            propertyIndex: Int,
            runtime: PredicateRuntime,
            target: PostingRanges
        ) {
            runtime.exactStringId?.let { exactStringId ->
                if (exactStringId >= 0) {
                    val row = java.util.Arrays.binarySearch(usedStringIds, exactStringId)
                    if (row >= 0) addRange(propertyIndex, row, target)
                }
                return
            }
            for (row in usedStringIds.indices) {
                if ((row and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) checkCallSiteIndexInterrupted()
                if (runtime.matches(usedStringIds[row])) addRange(propertyIndex, row, target)
            }
        }

        fun postingNodeId(position: Int): Int = postingNodeIds[position]

        fun collectDistinctValues(matchedNodeIds: LongArray, stringTable: StringTable): Set<String> {
            val values = linkedSetOf<String>()
            var start = 0
            for (row in usedStringIds.indices) {
                val end = postingEnds[row]
                var position = start
                while (position < end) {
                    val nodeId = postingNodeIds[position++]
                    if (matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] and
                        (1L shl (nodeId and BITSET_WORD_MASK)) != 0L
                    ) {
                        values += stringTable.get(usedStringIds[row])
                        break
                    }
                }
                start = end
            }
            return values
        }

        fun populateTrigramSignatures(target: LongArray, stringTable: StringTable) {
            for (index in usedStringIds.indices) {
                if ((index and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                    checkCallSiteIndexInterrupted()
                }
                val stringId = usedStringIds[index]
                if (target[stringId] == 0L) {
                    target[stringId] = callSiteTrigramSignature(stringTable.get(stringId))
                }
            }
        }

        private fun addRange(propertyIndex: Int, row: Int, target: PostingRanges) {
            val start = if (row == 0) 0 else postingEnds[row - 1]
            target.add(propertyIndex, start, postingEnds[row])
        }
    }

    internal class PredicateRuntime(
        private val predicate: StringPropertyPredicate,
        sharedStates: MutableMap<CallSitePredicateKey, ByteArray?>,
        private val stringTable: StringTable,
        private val trigramSignatures: LongArray
    ) {
        val exactStringId: Int? = if (
            predicate.transform == null && predicate.mode == StringMatchMode.EQUALS
        ) {
            stringTable.findId(predicate.expected)
        } else {
            null
        }

        private val requiredTrigramSignature: Long? = predicate.expected
            .takeIf { predicate.mode == StringMatchMode.CONTAINS && it.length >= MIN_CALL_SITE_TRIGRAM_LENGTH }
            ?.takeIf { expected -> expected.all { character -> character.code < ASCII_LIMIT } }
            ?.let(::callSiteTrigramSignature)

        private val states: ByteArray? = if (exactStringId == null && requiredTrigramSignature == null) {
            val stateKey = CallSitePredicateKey(predicate.transform, predicate.mode, predicate.expected)
            sharedStates.getOrPut(stateKey) { ByteArray(stringTable.size()) }
        } else {
            null
        }

        fun matches(stringId: Int): Boolean {
            requiredTrigramSignature?.let { required ->
                if (trigramSignatures[stringId] and required != required) return false
            }
            val state = states ?: return stringMatches(
                stringTable.get(stringId),
                predicate.transform,
                predicate.mode,
                predicate.expected
            )
            return when (state[stringId]) {
                RAW_STRING_MATCH -> true
                RAW_STRING_MISS -> false
                else -> stringMatches(
                    stringTable.get(stringId),
                    predicate.transform,
                    predicate.mode,
                    predicate.expected
                ).also { matched ->
                    state[stringId] = if (matched) RAW_STRING_MATCH else RAW_STRING_MISS
                }
            }
        }
    }

    internal class PostingRanges(private val properties: Array<PropertyCsr>) {
        private var propertyIndexes = IntArray(INITIAL_POSTING_RANGES)
        private var positions = IntArray(INITIAL_POSTING_RANGES)
        private var ends = IntArray(INITIAL_POSTING_RANGES)
        private var size = 0

        fun isEmpty(): Boolean = size == 0

        fun add(propertyIndex: Int, start: Int, end: Int) {
            if (start >= end) return
            ensureCapacity(size + 1)
            propertyIndexes[size] = propertyIndex
            positions[size] = start
            ends[size] = end
            size++
        }

        fun mergedNodeIds(
            nodeOrder: (Int) -> Long,
            workConsumer: GraphWorkConsumer?,
            limit: Int = Int.MAX_VALUE
        ): Sequence<Int> = sequence {
            if (limit <= 0) return@sequence
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            val heap = IntArray(size) { it }
            for (index in size / 2 - 1 downTo 0) siftDown(heap, index, size, nodeOrder)
            var heapSize = size
            var previousNodeId = -1
            var inspected = 0
            var yielded = 0
            try {
                while (heapSize > 0) {
                    if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                        checkCallSiteIndexInterrupted()
                    }
                    val range = heap[0]
                    val nodeId = currentNodeId(range)
                    if (nodeId != previousNodeId) {
                        accounting.consume()
                        yielded++
                        previousNodeId = nodeId
                        if (yielded >= limit) accounting.flush()
                        yield(nodeId)
                        if (yielded >= limit) break
                    }
                    positions[range]++
                    if (positions[range] >= ends[range]) {
                        heapSize--
                        if (heapSize > 0) heap[0] = heap[heapSize]
                    }
                    if (heapSize > 0) siftDown(heap, 0, heapSize, nodeOrder)
                }
            } finally {
                accounting.flush()
            }
        }

        fun aggregate(
            nodeIdCapacity: Int,
            distinctPropertyIndex: Int?,
            rawStringPropertyId: (Int, Int) -> Int,
            stringTable: StringTable
        ): StringPropertyDisjunctionAggregate {
            val wordCount = ((nodeIdCapacity.toLong() + BITSET_WORD_MASK) ushr BITSET_WORD_SHIFT).toInt()
            val matchedNodeIds = LongArray(wordCount)
            var inspected = 0
            for (range in 0 until size) {
                var position = positions[range]
                val end = ends[range]
                val property = properties[propertyIndexes[range]]
                while (position < end) {
                    if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                        checkCallSiteIndexInterrupted()
                    }
                    val nodeId = property.postingNodeId(position++)
                    matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] =
                        matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] or
                        (1L shl (nodeId and BITSET_WORD_MASK))
                }
            }
            val count = matchedNodeIds.sumOf { word -> java.lang.Long.bitCount(word).toLong() }
            val distinctValues = distinctPropertyIndex?.let { propertyIndex ->
                val property = properties[propertyIndex]
                if (count * SPARSE_DISTINCT_RANDOM_READ_FACTOR <= property.postingCount) {
                    collectSparseDistinctValues(
                        matchedNodeIds,
                        propertyIndex,
                        rawStringPropertyId,
                        stringTable
                    )
                } else {
                    property.collectDistinctValues(matchedNodeIds, stringTable)
                }
            }
            return StringPropertyDisjunctionAggregate(count, distinctValues?.takeIf { it.isNotEmpty() })
        }

        fun matchedNodeBitSet(nodeIdCapacity: Int): LongArray {
            val wordCount = ((nodeIdCapacity.toLong() + BITSET_WORD_MASK) ushr BITSET_WORD_SHIFT).toInt()
            val matchedNodeIds = LongArray(wordCount)
            var inspected = 0
            for (range in 0 until size) {
                var position = positions[range]
                val end = ends[range]
                val property = properties[propertyIndexes[range]]
                while (position < end) {
                    if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                        checkCallSiteIndexInterrupted()
                    }
                    val nodeId = property.postingNodeId(position++)
                    matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] =
                        matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] or
                        (1L shl (nodeId and BITSET_WORD_MASK))
                }
            }
            return matchedNodeIds
        }

        private fun collectSparseDistinctValues(
            matchedNodeIds: LongArray,
            propertyIndex: Int,
            rawStringPropertyId: (Int, Int) -> Int,
            stringTable: StringTable
        ): Set<String> {
            val values = linkedSetOf<String>()
            for (wordIndex in matchedNodeIds.indices) {
                var remaining = matchedNodeIds[wordIndex]
                while (remaining != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(remaining)
                    val nodeId = (wordIndex shl BITSET_WORD_SHIFT) + bit
                    values += stringTable.get(rawStringPropertyId(nodeId, propertyIndex))
                    remaining = remaining and (remaining - 1L)
                }
            }
            return values
        }

        private fun siftDown(heap: IntArray, start: Int, heapSize: Int, nodeOrder: (Int) -> Long) {
            var parent = start
            while (true) {
                val left = parent * 2 + 1
                if (left >= heapSize) return
                val right = left + 1
                val child = if (right < heapSize &&
                    nodeOrder(currentNodeId(heap[right])) < nodeOrder(currentNodeId(heap[left]))) {
                    right
                } else {
                    left
                }
                if (nodeOrder(currentNodeId(heap[parent])) <= nodeOrder(currentNodeId(heap[child]))) return
                val swapped = heap[parent]
                heap[parent] = heap[child]
                heap[child] = swapped
                parent = child
            }
        }

        private fun currentNodeId(range: Int): Int =
            properties[propertyIndexes[range]].postingNodeId(positions[range])

        private fun ensureCapacity(required: Int) {
            if (required <= propertyIndexes.size) return
            val capacity = maxOf(required, propertyIndexes.size * 2)
            propertyIndexes = propertyIndexes.copyOf(capacity)
            positions = positions.copyOf(capacity)
            ends = ends.copyOf(capacity)
        }
    }
}

internal data class CallSitePredicateKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

internal object MappedCallSiteStringIndexMemoryBudget {
    private const val BUDGET_PROPERTY = "graphite.webgraph.callSiteStringIndexBudgetBytes"
    private const val DEFAULT_MAX_HEAP_FRACTION = 4L
    private var retainedBytes = 0L

    val maxBytes: Long by lazy {
        System.getProperty(BUDGET_PROPERTY)?.toLongOrNull()?.coerceAtLeast(0L)
            ?: (Runtime.getRuntime().maxMemory() / DEFAULT_MAX_HEAP_FRACTION)
    }

    @Synchronized
    fun tryReserve(bytes: Long): Reservation? {
        if (bytes < 0L || bytes > maxBytes - retainedBytes) return null
        retainedBytes += bytes
        return Reservation(bytes)
    }

    @Synchronized
    internal fun retainedBytes(): Long = retainedBytes

    internal class Reservation internal constructor(initialBytes: Long) : Closeable {
        var bytes: Long = initialBytes
            private set
        private var closed = false

        fun tryGrowTo(requiredBytes: Long): Boolean = synchronized(MappedCallSiteStringIndexMemoryBudget) {
            check(!closed) { "Cannot grow a closed mapped CallSite string-index reservation" }
            if (requiredBytes <= bytes) return@synchronized true
            val added = requiredBytes - bytes
            if (added > maxBytes - retainedBytes) return@synchronized false
            retainedBytes += added
            bytes = requiredBytes
            true
        }

        fun shrinkTo(retained: Long) = synchronized(MappedCallSiteStringIndexMemoryBudget) {
            check(!closed && retained in 0L..bytes)
            retainedBytes -= bytes - retained
            bytes = retained
        }

        override fun close() {
            synchronized(MappedCallSiteStringIndexMemoryBudget) {
                if (closed) return
                closed = true
                retainedBytes -= bytes
                check(retainedBytes >= 0L) { "Mapped CallSite string-index budget underflow" }
            }
        }
    }
}

internal fun estimatedMappedCallSiteStringIndexCountBytes(stringCount: Int): Long? =
    estimatedPrimitiveArraysBytes(CALL_SITE_STRING_PROPERTY_COUNT, stringCount.toLong())

@Suppress("ComplexCondition")
internal fun estimatedMappedCallSiteStringIndexRetainedBytes(
    nodeCount: Long,
    stringCount: Int,
    uniqueCounts: IntArray
): Long? {
    if (nodeCount < 0L || nodeCount > Int.MAX_VALUE || stringCount < 0 ||
        uniqueCounts.size != CALL_SITE_STRING_PROPERTY_COUNT
    ) {
        return null
    }
    return try {
        val postingBytes = Math.multiplyExact(
            nodeCount,
            CALL_SITE_STRING_PROPERTY_COUNT.toLong() * Int.SIZE_BYTES
        )
        val compactCsrBytes = Math.multiplyExact(
            uniqueCounts.sumOf(Int::toLong),
            2L * Int.SIZE_BYTES
        )
        val trigramSignatureBytes = Math.multiplyExact(stringCount.toLong(), Long.SIZE_BYTES.toLong())
        Math.addExact(
            MAPPED_CALL_SITE_STRING_INDEX_OBJECT_ESTIMATED_BYTES +
                MAPPED_CALL_SITE_STRING_INDEX_RETAINED_ARRAYS * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
            Math.addExact(
                Math.addExact(postingBytes, compactCsrBytes),
                trigramSignatureBytes
            )
        )
    } catch (_: ArithmeticException) {
        null
    }
}

private fun estimatedPrimitiveArraysBytes(arrayCount: Int, entriesPerArray: Long): Long? = try {
    Math.addExact(
        arrayCount * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.multiplyExact(arrayCount.toLong() * entriesPerArray, Int.SIZE_BYTES.toLong())
    )
} catch (_: ArithmeticException) {
    null
}

internal fun callSiteStringPropertyIndex(property: String): Int = when (property) {
    CALLER_CLASS_PROPERTY -> CALLER_CLASS_PROPERTY_INDEX
    CALLER_NAME_PROPERTY -> CALLER_NAME_PROPERTY_INDEX
    CALLEE_CLASS_PROPERTY -> CALLEE_CLASS_PROPERTY_INDEX
    CALLEE_NAME_PROPERTY -> CALLEE_NAME_PROPERTY_INDEX
    else -> -1
}

private fun checkCallSiteIndexInterrupted() {
    if (Thread.currentThread().isInterrupted) {
        throw CancellationException("Mapped CallSite string index work interrupted")
    }
}

private fun callSiteTrigramSignature(value: String): Long {
    var signature = 0L
    for (position in 0..value.length - MIN_CALL_SITE_TRIGRAM_LENGTH) {
        val first = value[position].lowercaseChar().code
        val second = value[position + 1].lowercaseChar().code
        val third = value[position + 2].lowercaseChar().code
        val hash = (first * STRING_HASH_FACTOR + second) * STRING_HASH_FACTOR + third
        val mixed = hash xor (hash ushr 11) xor (hash shl 7)
        signature = signature or (1L shl (hash and TRIGRAM_SIGNATURE_MASK))
        signature = signature or (1L shl (mixed and TRIGRAM_SIGNATURE_MASK))
    }
    return signature
}

internal const val CALLER_CLASS_PROPERTY = "caller_class"
internal const val CALLER_NAME_PROPERTY = "caller_name"
internal const val CALLEE_CLASS_PROPERTY = "callee_class"
internal const val CALLEE_NAME_PROPERTY = "callee_name"
internal const val CALL_SITE_STRING_PROPERTY_COUNT = 4
internal const val CALLER_CLASS_PROPERTY_INDEX = 0
internal const val CALLER_NAME_PROPERTY_INDEX = 1
internal const val CALLEE_CLASS_PROPERTY_INDEX = 2
internal const val CALLEE_NAME_PROPERTY_INDEX = 3

private const val MAPPED_CALL_SITE_STRING_INDEX_RETAINED_ARRAYS = 3L * CALL_SITE_STRING_PROPERTY_COUNT + 1L
private const val MAPPED_CALL_SITE_STRING_INDEX_OBJECT_ESTIMATED_BYTES = 256L
private const val CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK = 1_023
private const val MAX_SERIAL_CALL_SITE_STRING_INDEX_SCAN_BYTES = 1024L * 1024
private const val INITIAL_POSTING_RANGES = 16
private const val BITSET_WORD_SHIFT = 6
private const val BITSET_WORD_MASK = Long.SIZE_BITS - 1
private const val SPARSE_DISTINCT_RANDOM_READ_FACTOR = 1L
private const val MIN_CALL_SITE_TRIGRAM_LENGTH = 3
private const val ASCII_LIMIT = 128
private const val STRING_HASH_FACTOR = 31
private const val TRIGRAM_SIGNATURE_MASK = Long.SIZE_BITS - 1
