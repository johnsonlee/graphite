@file:Suppress(
    "ComplexCondition",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooManyFunctions"
)

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.StringValueTransform
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.Closeable
import java.io.DataInput
import java.io.DataOutput
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/** Compact CSR indexes for the four strings searched by broad CallSite queries. */
@Suppress("LargeClass")
internal class MappedCallSiteStringIndex(
    private val properties: Array<PropertyCsr>,
    private val stringTable: StringTable,
    private val nodeOrder: (Int) -> Long,
    private val nodeIdCapacity: Int,
    private val rawStringPropertyId: (Int, Int) -> Int,
    private val contentIdentity: () -> ByteArray,
    private val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
    prepareExactProjectionTupleIndex: Boolean = false
) : Closeable {

    private val persistedContentIdentity: ByteArray by lazy(contentIdentity)

    init {
        require(properties.size == CALL_SITE_STRING_PROPERTY_COUNT)
        require(properties.map(PropertyCsr::postingCount).distinct().size <= 1)
    }

    private val exactProjectionTupleIndexEnabled = prepareExactProjectionTupleIndex
    @Volatile
    private var exactProjectionTupleIndex: ExactCallSiteProjectionTupleIndex? = null

    private val trigramSignatures = LongArray(stringTable.size())
    private var trigramMetadataInitialized = false
    private var trigramPostingCount = 0L
    private var trigramPostingCounts: IntArray? = null
    private var trigramStringIds: IntArray? = null
    private var trigramPostingsInitialized = false
    private var trigramPostings: LongArray? = null
    private val matchingStringIds = LinkedHashMap<CallSitePredicateKey, CachedMatchingStringIds>(
        MAX_CALL_SITE_STRING_MATCH_CACHE_ENTRIES + 1,
        CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR,
        true
    )
    private var matchingStringCacheBytes = 0L
    private val matchingNodeIds = LinkedHashMap<CallSiteNodeMatchKey, CachedMatchingNodeIds>(
        MAX_CALL_SITE_NODE_MATCH_CACHE_ENTRIES + 1,
        CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR,
        true
    )
    private var matchingNodeCacheBytes = 0L
    private val projectedRows = LinkedHashMap<CallSiteProjectionKey, CachedProjectionRows>(
        MAX_CALL_SITE_PROJECTION_CACHE_ENTRIES + 1,
        CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR,
        true
    )
    private var projectedRowCacheBytes = 0L

    val retainedBytes: Long
        get() = reservation.bytes

    internal fun hasExactProjectionTupleIndex(): Boolean = exactProjectionTupleIndex != null

    private fun exactProjectionTupleIndex(
        workConsumer: GraphWorkConsumer?
    ): ExactCallSiteProjectionTupleIndex? {
        exactProjectionTupleIndex?.let { return it }
        if (!exactProjectionTupleIndexEnabled) return null
        return synchronized(this) {
            exactProjectionTupleIndex ?: ExactCallSiteProjectionTupleIndex.tryBuild(
                properties.firstOrNull(),
                stringTable,
                rawStringPropertyId,
                nodeOrder,
                reservation,
                workConsumer
            )?.also { exactProjectionTupleIndex = it }
        }
    }

    val prefersSerialScan: Boolean
        get() = retainedBytes <= MAX_SERIAL_CALL_SITE_STRING_INDEX_SCAN_BYTES

    fun matchingNodeIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?,
        limit: Int = Int.MAX_VALUE
    ): Sequence<Int> {
        if (limit <= 0) return emptySequence()
        val cacheKey = if (limit <= MAX_CALL_SITE_NODE_MATCH_CACHE_LIMIT) {
            CallSiteNodeMatchKey(predicates.toList(), limit)
        } else {
            null
        }
        cacheKey?.let { key ->
            synchronized(this) {
                matchingNodeIds[key]?.let { cached ->
                    consumeGraphWork(workConsumer, cached.nodeIds.size.coerceAtLeast(1).toLong())
                    return cached.nodeIds.asSequence()
                }
            }
        }
        val ranges = matchingRanges(predicates, workConsumer)
        if (ranges.isEmpty()) {
            val empty = IntArray(0)
            cacheKey?.let { key -> cacheMatchingNodeIds(key, empty) }
            return empty.asSequence()
        }
        cacheKey?.let { key ->
            return cacheMatchingNodeIdsOnComplete(
                key,
                ranges.mergedNodeIds(nodeOrder, workConsumer, limit)
            )
        }
        return ranges.mergedNodeIds(nodeOrder, workConsumer, limit)
    }

    private fun cacheMatchingNodeIdsOnComplete(
        key: CallSiteNodeMatchKey,
        source: Sequence<Int>
    ): Sequence<Int> = sequence {
        val consumed = IntArrayList()
        for (nodeId in source) {
            consumed.add(nodeId)
            yield(nodeId)
        }
        cacheMatchingNodeIds(key, consumed.toIntArray())
    }

    fun projectRows(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyProjectionRow> {
        if (limit <= 0) return emptyList()
        val key = CallSiteProjectionKey(predicates.toList(), projectedProperties.toList(), limit)
        synchronized(this) {
            projectedRows[key]?.let { cached ->
                consumeGraphWork(workConsumer, cached.rows.size.coerceAtLeast(1).toLong())
                return cached.rows
            }
        }
        val propertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex).toIntArray()
        require(propertyIndexes.all { it >= 0 })
        val rows = matchingNodeIds(predicates, workConsumer, limit).map { nodeId ->
            StringPropertyProjectionRow(propertyIndexes.map { propertyIndex ->
                stringTable.get(rawStringPropertyId(nodeId, propertyIndex))
            })
        }.toList()
        return cacheProjectedRows(key, rows)
    }

    @Synchronized
    private fun cacheProjectedRows(
        key: CallSiteProjectionKey,
        rows: List<StringPropertyProjectionRow>
    ): List<StringPropertyProjectionRow> {
        projectedRows[key]?.let { return it.rows }
        val retainedRows = Collections.unmodifiableList(rows.map { row ->
            StringPropertyProjectionRow(Collections.unmodifiableList(row.values.toList()))
        })
        val entryBytes = estimatedCallSiteProjectionCacheBytes(key, retainedRows)
        if (entryBytes > MAX_CALL_SITE_PROJECTION_CACHE_BYTES) return retainedRows
        while (projectedRows.isNotEmpty() &&
            (projectedRows.size >= MAX_CALL_SITE_PROJECTION_CACHE_ENTRIES ||
                projectedRowCacheBytes > MAX_CALL_SITE_PROJECTION_CACHE_BYTES - entryBytes)
        ) {
            val eldest = projectedRows.entries.iterator().next()
            projectedRows.remove(eldest.key)
            projectedRowCacheBytes -= eldest.value.retainedBytes
            reservation.shrinkTo(reservation.bytes - eldest.value.retainedBytes)
        }
        val retainedAfter = runCatching { Math.addExact(reservation.bytes, entryBytes) }.getOrNull()
            ?: return retainedRows
        if (!reservation.tryGrowTo(retainedAfter)) return retainedRows
        projectedRows[key] = CachedProjectionRows(retainedRows, entryBytes)
        projectedRowCacheBytes += entryBytes
        return retainedRows
    }

    @Synchronized
    private fun cacheMatchingNodeIds(key: CallSiteNodeMatchKey, nodeIds: IntArray) {
        if (matchingNodeIds.containsKey(key)) return
        val entryBytes = estimatedCallSiteNodeMatchCacheBytes(key, nodeIds)
        if (entryBytes > MAX_CALL_SITE_NODE_MATCH_CACHE_BYTES) return
        while (matchingNodeIds.isNotEmpty() &&
            (matchingNodeIds.size >= MAX_CALL_SITE_NODE_MATCH_CACHE_ENTRIES ||
                matchingNodeCacheBytes > MAX_CALL_SITE_NODE_MATCH_CACHE_BYTES - entryBytes)
        ) {
            val eldest = matchingNodeIds.entries.iterator().next()
            matchingNodeIds.remove(eldest.key)
            matchingNodeCacheBytes -= eldest.value.retainedBytes
            reservation.shrinkTo(reservation.bytes - eldest.value.retainedBytes)
        }
        val retainedAfter = runCatching { Math.addExact(reservation.bytes, entryBytes) }.getOrNull() ?: return
        if (!reservation.tryGrowTo(retainedAfter)) return
        matchingNodeIds[key] = CachedMatchingNodeIds(nodeIds, entryBytes)
        matchingNodeCacheBytes += entryBytes
    }

    fun aggregate(
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?,
        workConsumer: GraphWorkConsumer?
    ): StringPropertyDisjunctionAggregate {
        val ranges = matchingRanges(predicates, workConsumer)
        if (ranges.isEmpty()) {
            return StringPropertyDisjunctionAggregate(0L, distinctProperty?.let { emptySet() })
        }
        val distinctPropertyIndex = distinctProperty?.let(::callSiteStringPropertyIndex)
        require(distinctPropertyIndex == null || distinctPropertyIndex >= 0)
        return ranges.aggregate(
            nodeIdCapacity,
            distinctPropertyIndex,
            rawStringPropertyId,
            stringTable,
            workConsumer
        )
    }

    private fun matchingRanges(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): PostingRanges {
        if (workConsumer is SplitGraphWorkBatchConsumer &&
            workConsumer.segmentWorkerCount > 0 && predicates.size > 1
        ) {
            return matchingRangesInParallel(predicates, workConsumer)
        }
        val ranges = PostingRanges(properties)
        val sharedStates = mutableMapOf<CallSitePredicateKey, ByteArray?>()
        val sharedMatches = mutableMapOf<CallSitePredicateKey, IntArray?>()
        predicates.forEach { predicate ->
            val propertyIndex = callSiteStringPropertyIndex(predicate.property)
            if (propertyIndex < 0) return@forEach
            val key = CallSitePredicateKey(predicate.transform, predicate.mode, predicate.expected)
            val matchedStringIds = if (sharedMatches.containsKey(key)) {
                sharedMatches[key]
            } else {
                matchingStringIds(predicate, workConsumer).also { sharedMatches[key] = it }
            }
            if (matchedStringIds == null && predicate.requiresTrigramSignature()) {
                ensureTrigramMetadata(workConsumer)
            }
            val runtime = PredicateRuntime(predicate, sharedStates, stringTable, trigramSignatures)
            properties[propertyIndex].collectMatchingRanges(
                propertyIndex,
                runtime,
                matchedStringIds,
                candidatesAreKnownMatches = matchedStringIds != null,
                ranges,
                workConsumer
            )
        }
        return ranges
    }

    private fun matchingRangesInParallel(
        predicates: List<StringPropertyPredicate>,
        workConsumer: SplitGraphWorkBatchConsumer
    ): PostingRanges {
        if (predicates.any(StringPropertyPredicate::requiresTrigramSignature)) {
            ensureTrigramMetadata(workConsumer)
        }
        val sharedMatches = mutableMapOf<CallSitePredicateKey, IntArray?>()
        val matches = predicates.map { predicate ->
            val key = CallSitePredicateKey(predicate.transform, predicate.mode, predicate.expected)
            if (sharedMatches.containsKey(key)) {
                sharedMatches[key]
            } else {
                matchingStringIds(predicate, workConsumer).also { sharedMatches[key] = it }
            }
        }
        val workerCount = minOf(predicates.size, workConsumer.segmentWorkerCount + 1)
        val chunkSize = (predicates.size + workerCount - 1) / workerCount
        val tasks = (0 until workerCount).mapNotNull { workerIndex ->
            val start = workerIndex * chunkSize
            val end = minOf(predicates.size, start + chunkSize)
            if (start >= end) return@mapNotNull null
            Callable {
                val local = PostingRanges(properties)
                val states = mutableMapOf<CallSitePredicateKey, ByteArray?>()
                for (predicateIndex in start until end) {
                    val predicate = predicates[predicateIndex]
                    val propertyIndex = callSiteStringPropertyIndex(predicate.property)
                    if (propertyIndex < 0) continue
                    val matchedStringIds = matches[predicateIndex]
                    properties[propertyIndex].collectMatchingRanges(
                        propertyIndex,
                        PredicateRuntime(predicate, states, stringTable, trigramSignatures),
                        matchedStringIds,
                        candidatesAreKnownMatches = matchedStringIds != null,
                        local,
                        workConsumer
                    )
                }
                local
            }
        }
        return PostingRanges(properties).also { combined ->
            executeSplitCallSiteTasks(tasks, workConsumer.segmentWorkerCount).forEach(combined::addAll)
        }
    }

    private fun matchingStringIds(
        predicate: StringPropertyPredicate,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        if (!predicate.canUseLowercaseTrigramPostings() ||
            predicate.mode == StringMatchMode.EQUALS ||
            predicate.expected.length < MIN_CALL_SITE_TRIGRAM_LENGTH
        ) {
            return null
        }
        val key = CallSitePredicateKey(predicate.transform, predicate.mode, predicate.expected)
        synchronized(this) {
            matchingStringIds[key]?.let { return it.stringIds }
        }
        val candidates = candidateStringIds(predicate, workConsumer) ?: return null
        val runtime = PredicateRuntime(predicate, mutableMapOf(), stringTable, trigramSignatures)
        val result = if (workConsumer is SplitGraphWorkBatchConsumer &&
            candidates.size >= MIN_PARALLEL_CALL_SITE_MATCH_CANDIDATES
        ) {
            matchingCandidateStringIdsInParallel(candidates, runtime, workConsumer)
        } else {
            matchingCandidateStringIds(candidates, runtime, workConsumer)
        }
        cacheMatchingStringIds(key, result)
        return result
    }

    private fun matchingCandidateStringIds(
        candidates: IntArray,
        runtime: PredicateRuntime,
        workConsumer: GraphWorkConsumer?,
        start: Int = 0,
        end: Int = candidates.size
    ): IntArray {
        val matched = IntArray(end - start)
        var size = 0
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (index in start until end) {
                if ((index and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                    checkCallSiteIndexInterrupted()
                }
                accounting.consume()
                val stringId = candidates[index]
                if (runtime.matches(stringId)) matched[size++] = stringId
            }
        } finally {
            accounting.flush()
        }
        return matched.copyOf(size)
    }

    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    private fun matchingCandidateStringIdsInParallel(
        candidates: IntArray,
        runtime: PredicateRuntime,
        workConsumer: SplitGraphWorkBatchConsumer
    ): IntArray {
        val workerCount = minOf(
            candidates.size,
            workConsumer.segmentWorkerCount + 1,
            callSiteScanParallelism + 1
        )
        val chunkSize = (candidates.size + workerCount - 1) / workerCount
        val tasks = (0 until workerCount).mapNotNull { workerIndex ->
            val start = workerIndex * chunkSize
            val end = minOf(candidates.size, start + chunkSize)
            if (start >= end) return@mapNotNull null
            Callable {
                matchingCandidateStringIds(candidates, runtime, workConsumer, start, end)
            }
        }
        return executeSplitCallSiteCandidateTasks(tasks, workConsumer.segmentWorkerCount)
    }

    @Synchronized
    private fun cacheMatchingStringIds(key: CallSitePredicateKey, result: IntArray) {
        if (matchingStringIds.containsKey(key)) return
        val entryBytes = estimatedCallSiteStringMatchCacheBytes(key, result)
        if (entryBytes > MAX_CALL_SITE_STRING_MATCH_CACHE_BYTES) return
        while (matchingStringIds.isNotEmpty() &&
            (matchingStringIds.size >= MAX_CALL_SITE_STRING_MATCH_CACHE_ENTRIES ||
                matchingStringCacheBytes > MAX_CALL_SITE_STRING_MATCH_CACHE_BYTES - entryBytes)
        ) {
            val eldest = matchingStringIds.entries.iterator().next()
            matchingStringIds.remove(eldest.key)
            matchingStringCacheBytes -= eldest.value.retainedBytes
            reservation.shrinkTo(reservation.bytes - eldest.value.retainedBytes)
        }
        val retainedAfter = runCatching { Math.addExact(reservation.bytes, entryBytes) }.getOrNull() ?: return
        if (!reservation.tryGrowTo(retainedAfter)) return
        matchingStringIds[key] = CachedMatchingStringIds(result, entryBytes)
        matchingStringCacheBytes += entryBytes
    }

    @Suppress("CyclomaticComplexMethod")
    private fun candidateStringIds(
        predicate: StringPropertyPredicate,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        if (!predicate.canUseLowercaseTrigramPostings() ||
            predicate.mode == StringMatchMode.EQUALS ||
            predicate.expected.length < MIN_CALL_SITE_TRIGRAM_LENGTH
        ) {
            return null
        }
        val postings = callSiteTrigramPostings(workConsumer) ?: return null
        // An exact raw ASCII match remains a match after lowercase conversion, so lowercase
        // postings are a safe candidate filter; PredicateRuntime still verifies original casing.
        // Non-ASCII raw terms are not closed under context-sensitive case mapping (for example,
        // Greek final sigma) and therefore stay on the complete string scan.
        val expected = predicate.expected.lowercase()
        val seen = IntOpenHashSet()
        val ranges = mutableListOf<CallSiteTrigramPostingRange>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            val trigramPositions = when (predicate.mode) {
                StringMatchMode.STARTS_WITH -> 0..0
                StringMatchMode.ENDS_WITH -> {
                    val last = expected.length - MIN_CALL_SITE_TRIGRAM_LENGTH
                    last..last
                }
                else -> 0..expected.length - MIN_CALL_SITE_TRIGRAM_LENGTH
            }
            for (position in trigramPositions) {
                val trigram = callSiteTrigramHash(expected, position)
                if (!seen.add(trigram)) continue
                accounting.consume()
                val start = lowerBound(postings, trigramKey(trigram, 0))
                accounting.consume()
                val end = upperBound(postings, trigramKey(trigram, -1))
                if (start == end) return IntArray(0)
                ranges += CallSiteTrigramPostingRange(trigram, start, end)
            }
            if (ranges.isEmpty()) return null
            ranges.sortBy(CallSiteTrigramPostingRange::size)
            val shortest = ranges.first()
            val candidates = IntArray(shortest.size) { offset ->
                postings[shortest.start + offset].toInt()
            }
            if (workConsumer is SplitGraphWorkBatchConsumer && ranges.size > 1 &&
                candidates.size >= MIN_PARALLEL_CALL_SITE_MATCH_CANDIDATES
            ) {
                return intersectCandidateStringIdsInParallel(postings, ranges, candidates, workConsumer)
            }
            var candidateCount = candidates.size
            for (rangeIndex in 1 until ranges.size) {
                val range = ranges[rangeIndex]
                var retained = 0
                for (candidateIndex in 0 until candidateCount) {
                    if ((candidateIndex and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                        checkCallSiteIndexInterrupted()
                    }
                    accounting.consume()
                    val stringId = candidates[candidateIndex]
                    if (Arrays.binarySearch(
                            postings,
                            range.start,
                            range.end,
                            trigramKey(range.trigram, stringId)
                        ) >= 0
                    ) {
                        candidates[retained++] = stringId
                    }
                }
                candidateCount = retained
                if (candidateCount == 0) break
            }
            return candidates.copyOf(candidateCount)
        } finally {
            accounting.flush()
        }
    }

    private fun intersectCandidateStringIdsInParallel(
        postings: LongArray,
        ranges: List<CallSiteTrigramPostingRange>,
        candidates: IntArray,
        workConsumer: SplitGraphWorkBatchConsumer
    ): IntArray {
        val workerCount = minOf(
            candidates.size,
            workConsumer.segmentWorkerCount + 1,
            callSiteScanParallelism + 1
        )
        val chunkSize = (candidates.size + workerCount - 1) / workerCount
        val tasks = (0 until workerCount).mapNotNull { workerIndex ->
            val start = workerIndex * chunkSize
            val end = minOf(candidates.size, start + chunkSize)
            if (start >= end) return@mapNotNull null
            Callable {
                val retained = IntArray(end - start)
                var retainedCount = 0
                val accounting = BufferedGraphWorkConsumer(workConsumer)
                try {
                    for (candidateIndex in start until end) {
                        if ((candidateIndex and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                            checkCallSiteIndexInterrupted()
                        }
                        val stringId = candidates[candidateIndex]
                        var matched = true
                        for (rangeIndex in 1 until ranges.size) {
                            accounting.consume()
                            val range = ranges[rangeIndex]
                            if (Arrays.binarySearch(
                                    postings,
                                    range.start,
                                    range.end,
                                    trigramKey(range.trigram, stringId)
                                ) < 0
                            ) {
                                matched = false
                                break
                            }
                        }
                        if (matched) retained[retainedCount++] = stringId
                    }
                } finally {
                    accounting.flush()
                }
                retained.copyOf(retainedCount)
            }
        }
        return executeSplitCallSiteCandidateTasks(tasks, workConsumer.segmentWorkerCount)
    }

    @Synchronized
    private fun callSiteTrigramPostings(workConsumer: GraphWorkConsumer?): LongArray? {
        if (trigramPostingsInitialized) return trigramPostings
        trigramPostings = buildCallSiteTrigramPostings(workConsumer)
        trigramPostingsInitialized = true
        return trigramPostings
    }

    private fun buildCallSiteTrigramPostings(workConsumer: GraphWorkConsumer?): LongArray? {
        val postingCount = ensureTrigramMetadata(workConsumer)
        val postingCounts = checkNotNull(trigramPostingCounts)
        val stringIds = checkNotNull(trigramStringIds)
        var buildCompleted = false
        return try {
            if (postingCount == 0L || postingCount > Int.MAX_VALUE) {
                buildCompleted = true
                return null
            }
            val postingBytes = runCatching {
                Math.addExact(
                    PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                    Math.multiplyExact(postingCount, Long.SIZE_BYTES.toLong())
                )
            }.getOrNull() ?: run {
                buildCompleted = true
                return null
            }
            val maximumBytes = System.getProperty(CALL_SITE_TRIGRAM_INDEX_BUDGET_PROPERTY)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: Runtime.getRuntime().maxMemory() / DEFAULT_CALL_SITE_TRIGRAM_HEAP_DIVISOR
            if (postingBytes > maximumBytes) {
                buildCompleted = true
                return null
            }
            val retainedBefore = reservation.bytes
            val retainedAfter = runCatching { Math.addExact(retainedBefore, postingBytes) }.getOrNull() ?: run {
                buildCompleted = true
                return null
            }
            if (!reservation.tryGrowTo(retainedAfter)) {
                buildCompleted = true
                return null
            }

            val result = LongArray(postingCount.toInt())
            var postingsCompleted = false
            try {
                if (workConsumer is ParallelGraphWorkBatchConsumer &&
                    stringTable.size() >= MIN_PARALLEL_CALL_SITE_TRIGRAM_STRINGS &&
                    callSiteScanParallelism > 1
                ) {
                    populateCallSiteTrigramPostingsInParallel(
                        stringIds,
                        result,
                        postingCounts,
                        stringTable,
                        workConsumer
                    )
                } else {
                    populateCallSiteTrigramPostings(
                        stringIds,
                        result,
                        postingCounts,
                        stringTable,
                        workConsumer
                    )
                }
                if (!sortCallSiteTrigramPostings(result, reservation)) {
                    buildCompleted = true
                    return null
                }
                postingsCompleted = true
            } finally {
                if (!postingsCompleted) reservation.shrinkTo(retainedBefore)
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

    @Synchronized
    private fun ensureTrigramMetadata(workConsumer: GraphWorkConsumer?): Long {
        if (trigramMetadataInitialized) return trigramPostingCount
        var completed = false
        return try {
            val stringIds = usedCallSiteTrigramStringIds(properties, stringTable.size())
            val postingCounts = IntArray(stringTable.size())
            val postingCount = if (workConsumer is ParallelGraphWorkBatchConsumer &&
                stringTable.size() >= MIN_PARALLEL_CALL_SITE_TRIGRAM_STRINGS &&
                callSiteScanParallelism > 1
            ) {
                populateCallSiteTrigramMetadataInParallel(
                    stringIds,
                    trigramSignatures,
                    postingCounts,
                    stringTable,
                    workConsumer
                )
            } else {
                populateCallSiteTrigramMetadata(
                    stringIds,
                    trigramSignatures,
                    postingCounts,
                    stringTable,
                    workConsumer
                )
            }
            trigramPostingCount = postingCount
            trigramPostingCounts = postingCounts
            trigramStringIds = stringIds
            trigramMetadataInitialized = true
            completed = true
            postingCount
        } finally {
            if (!completed) {
                Arrays.fill(trigramSignatures, 0L)
                trigramPostingCount = 0L
                trigramPostingCounts = null
                trigramStringIds = null
            }
        }
    }

    internal fun isTrigramPostingsInitialized(): Boolean = trigramPostingsInitialized && trigramPostings != null

    /** Builds the optional lowercase trigram postings without running a query. */
    internal fun prepareTrigramPostings(workConsumer: GraphWorkConsumer? = null): Boolean =
        callSiteTrigramPostings(workConsumer) != null

    @Synchronized
    internal fun writePersistent(output: DataOutput) {
        val postings = checkNotNull(trigramPostings.takeIf { trigramPostingsInitialized }) {
            "CallSite trigram postings must be prepared before persistence"
        }
        val callSiteCount = properties.firstOrNull()?.postingCount ?: 0
        val persistentRetainedBytes = persistentRetainedBytes(callSiteCount, stringTable.size(), properties, postings)
        val graphContentIdentity = persistedContentIdentity
        require(graphContentIdentity.size == CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
        output.writeInt(CALL_SITE_STRING_INDEX_MAGIC)
        output.writeInt(CALL_SITE_STRING_INDEX_VERSION)
        output.writeInt(stringTable.size())
        output.writeInt(callSiteCount)
        output.write(graphContentIdentity)
        properties.forEach { property -> output.writeInt(property.uniqueStringCount) }
        output.writeInt(postings.size)
        output.writeLong(persistentRetainedBytes)
        properties.forEach { property -> property.writePersistent(output) }
        trigramSignatures.forEach(output::writeLong)
        postings.forEach(output::writeLong)
        output.writeLong(
            persistentChecksum(
                stringTable.size(),
                callSiteCount,
                graphContentIdentity,
                persistentRetainedBytes,
                properties,
                trigramSignatures,
                postings
            )
        )
    }

    /**
     * Writes a bounded, checksummed directory over balanced chunks of the trigram postings already
     * stored in the complete CallSite index. The directory contains no string or posting copy.
     */
    @Synchronized
    internal fun writePersistentTrigramDirectory(output: DataOutput, exactIndexBytes: Long) {
        val postings = checkNotNull(trigramPostings.takeIf { trigramPostingsInitialized }) {
            "CallSite trigram postings must be prepared before persistence"
        }
        val callSiteCount = properties.firstOrNull()?.postingCount ?: 0
        val postingsOffset = persistentTrigramPostingsOffset(callSiteCount)
        require(exactIndexBytes == postingsOffset + postings.size.toLong() * Long.SIZE_BYTES + Long.SIZE_BYTES)
        val graphContentIdentity = persistedContentIdentity
        require(graphContentIdentity.size == CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
        val chunkCount = minOf(postings.size, CALL_SITE_TRIGRAM_DIRECTORY_MAX_CHUNKS)
        val chunkSize = ((postings.size.toLong() + chunkCount - 1L) / chunkCount).toInt()

        output.writeInt(CALL_SITE_TRIGRAM_PREFILTER_MAGIC)
        output.writeInt(CALL_SITE_TRIGRAM_PREFILTER_VERSION)
        output.writeInt(stringTable.size())
        output.writeInt(callSiteCount)
        output.writeInt(postings.size)
        output.writeInt(chunkCount)
        output.writeLong(exactIndexBytes)
        output.writeLong(postingsOffset)
        output.write(graphContentIdentity)
        properties.forEach { property -> output.writeInt(property.usedStringIdsChecksum().toInt()) }

        var start = 0
        while (start < postings.size) {
            val end = minOf(postings.size, start + chunkSize)
            val checksum = CRC32()
            for (index in start until end) checksum.updateDirectoryLongBigEndian(postings[index])
            output.writeInt((postings[end - 1] ushr Int.SIZE_BITS).toInt())
            output.writeInt(end)
            output.writeInt(checksum.value.toInt())
            start = end
        }
    }

    private fun persistentTrigramPostingsOffset(callSiteCount: Int): Long = Math.addExact(
        CALL_SITE_STRING_INDEX_HEADER_BYTES.toLong(),
        Math.addExact(
            properties.sumOf { property ->
                property.uniqueStringCount.toLong() * 2L * Int.SIZE_BYTES +
                    callSiteCount.toLong() * Int.SIZE_BYTES
            },
            stringTable.size().toLong() * Long.SIZE_BYTES
        )
    )

    internal fun contentIdentity(): ByteArray = persistedContentIdentity.copyOf()

    fun distinctProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        if (limit <= 0) return emptyList()
        val propertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex).toIntArray()
        if (selectedValues != null) {
            selectedProjectionHitsByTuple(
                predicates,
                propertyIndexes,
                selectedValues,
                limit,
                workConsumer
            )?.let { return it }
        }
        val ranges = matchingRanges(predicates, workConsumer)
        if (ranges.isEmpty()) return emptyList()
        return if (selectedValues == null) {
            distinctProjectionPrefix(ranges, propertyIndexes, limit, workConsumer)
        } else {
            selectedProjectionHits(ranges, propertyIndexes, selectedValues, limit, workConsumer)
        }
    }

    /**
     * Provenance rechecks already know the complete projected tuple. Anchor each tuple on its
     * smallest exact property posting instead of materializing every predicate hit into a graph-wide
     * bit set and then discarding almost all of it.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun selectedProjectionHitsByTuple(
        predicates: List<StringPropertyPredicate>,
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow>? {
        if (propertyIndexes.all { propertyIndex -> propertyIndex < 0 }) return null
        if (predicates.any(StringPropertyPredicate::requiresTrigramSignature)) {
            ensureTrigramMetadata(workConsumer)
        }
        val predicatePropertyIndexes = predicates.map { predicate ->
            callSiteStringPropertyIndex(predicate.property)
        }
        val sharedStates = mutableMapOf<CallSitePredicateKey, ByteArray?>()
        val runtimes = predicates.map { predicate ->
            PredicateRuntime(predicate, sharedStates, stringTable, trigramSignatures)
        }
        val tupleIndex = exactProjectionTupleIndex ?: selectedValues
            .takeIf { values -> values.size >= MIN_SELECTED_VALUES_FOR_EXACT_PROJECTION_INDEX }
            ?.let { exactProjectionTupleIndex(workConsumer) }
        tupleIndex?.selectedProjectionHits(
            propertyIndexes,
            selectedValues,
            limit,
            nodeOrder,
            workConsumer
        ) { nodeId ->
            predicates.indices.any { predicateIndex ->
                predicatePropertyIndexes[predicateIndex].takeIf { it >= 0 }
                    ?.let { propertyIndex ->
                        runtimes[predicateIndex].matches(rawStringPropertyId(nodeId, propertyIndex))
                    } == true
            }
        }?.let { return it }
        val targetSize = minOf(limit, selectedValues.size)
        val hits = ArrayList<StringPropertyDistinctRow>(targetSize)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (values in selectedValues) {
                if (values.size != propertyIndexes.size) continue
                val ids = IntArray(values.size)
                var valid = true
                for (index in values.indices) {
                    val propertyIndex = propertyIndexes[index]
                    val value = values[index]
                    val id = value?.let(stringTable::findId) ?: -1
                    if (propertyIndex < 0 && value != null || propertyIndex >= 0 && id < 0) {
                        valid = false
                        break
                    }
                    ids[index] = if (propertyIndex < 0) -1 else id
                }
                if (!valid) continue
                val anchor = propertyIndexes.indices.asSequence()
                    .filter { index -> propertyIndexes[index] >= 0 }
                    .mapNotNull { index ->
                        properties[propertyIndexes[index]].postingRange(ids[index], accounting)
                            ?.let { range -> propertyIndexes[index] to range }
                    }
                    .minByOrNull { (_, range) -> range.last - range.first }
                    ?: continue
                val (anchorProperty, range) = anchor
                for (position in range) {
                    accounting.consume()
                    val nodeId = properties[anchorProperty].postingNodeId(position)
                    if (!projectionEquals(nodeId, propertyIndexes, ids)) continue
                    val matched = predicates.indices.any { predicateIndex ->
                        predicatePropertyIndexes[predicateIndex].takeIf { it >= 0 }
                            ?.let { propertyIndex ->
                                runtimes[predicateIndex].matches(rawStringPropertyId(nodeId, propertyIndex))
                            } == true
                    }
                    if (!matched) continue
                    hits += StringPropertyDistinctRow(nodeOrder(nodeId), values)
                    break
                }
            }
        } finally {
            accounting.flush()
        }
        return hits.sortedBy(StringPropertyDistinctRow::encounterOrder).take(targetSize)
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
        val matchedNodeIds = ranges.matchedNodeBitSet(nodeIdCapacity, workConsumer)
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

    @Synchronized
    override fun close() {
        exactProjectionTupleIndex = null
        trigramPostingCounts = null
        trigramStringIds = null
        matchingStringIds.clear()
        matchingStringCacheBytes = 0L
        matchingNodeIds.clear()
        matchingNodeCacheBytes = 0L
        projectedRows.clear()
        projectedRowCacheBytes = 0L
        reservation.close()
    }

    internal class PropertyCsr(
        private val postingEnds: IntArray,
        private val usedStringIds: IntArray,
        private val postingNodeIds: IntArray,
        prevalidated: Boolean = false
    ) {
        init {
            require(postingEnds.size == usedStringIds.size)
            if (!prevalidated) {
                require((1 until usedStringIds.size).all { index ->
                    usedStringIds[index - 1] < usedStringIds[index]
                })
            }
            require(postingEnds.lastOrNull() ?: 0 == postingNodeIds.size)
        }

        val postingCount: Int
            get() = postingNodeIds.size

        val uniqueStringCount: Int
            get() = usedStringIds.size

        fun writePersistent(output: DataOutput) {
            usedStringIds.forEach(output::writeInt)
            postingEnds.forEach(output::writeInt)
            postingNodeIds.forEach(output::writeInt)
        }

        fun updatePersistentChecksum(checksum: CRC32) {
            usedStringIds.forEach(checksum::updateInt)
            postingEnds.forEach(checksum::updateInt)
            postingNodeIds.forEach(checksum::updateInt)
        }

        fun usedStringIdsChecksum(): Long = CRC32().also { checksum ->
            usedStringIds.forEach(checksum::updateDirectoryIntBigEndian)
        }.value

        @Suppress("CyclomaticComplexMethod")
        fun collectMatchingRanges(
            propertyIndex: Int,
            runtime: PredicateRuntime,
            candidateStringIds: IntArray?,
            candidatesAreKnownMatches: Boolean,
            target: PostingRanges,
            workConsumer: GraphWorkConsumer?
        ) {
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            try {
                runtime.exactStringId?.let { exactStringId ->
                    if (exactStringId >= 0) {
                        val row = findStringRow(exactStringId, accounting)
                        if (row >= 0) addRange(propertyIndex, row, target)
                    }
                    return
                }
                candidateStringIds?.let { candidates ->
                    if (candidatesAreKnownMatches) {
                        var candidateIndex = 0
                        var row = 0
                        while (candidateIndex < candidates.size && row < usedStringIds.size) {
                            if (((candidateIndex + row) and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                                checkCallSiteIndexInterrupted()
                            }
                            when {
                                candidates[candidateIndex] < usedStringIds[row] -> {
                                    candidateIndex = gallopLowerBound(
                                        candidates,
                                        candidateIndex,
                                        usedStringIds[row],
                                        accounting
                                    )
                                }
                                candidates[candidateIndex] > usedStringIds[row] -> {
                                    row = gallopLowerBound(
                                        usedStringIds,
                                        row,
                                        candidates[candidateIndex],
                                        accounting
                                    )
                                }
                                else -> {
                                    accounting.consume()
                                    addRange(propertyIndex, row, target)
                                    candidateIndex++
                                    row++
                                }
                            }
                        }
                        return
                    }
                    for (stringId in candidates) {
                        accounting.consume()
                        val row = findStringRow(stringId, accounting)
                        if (row >= 0 && (candidatesAreKnownMatches || runtime.matches(stringId))) {
                            addRange(propertyIndex, row, target)
                        }
                    }
                    return
                }
                for (row in usedStringIds.indices) {
                    if ((row and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                        checkCallSiteIndexInterrupted()
                    }
                    accounting.consume()
                    if (runtime.matches(usedStringIds[row])) addRange(propertyIndex, row, target)
                }
            } finally {
                accounting.flush()
            }
        }

        private fun gallopLowerBound(
            values: IntArray,
            start: Int,
            target: Int,
            accounting: BufferedGraphWorkConsumer
        ): Int {
            var bound = 1
            while (bound < values.size - start) {
                accounting.consume()
                if (values[start + bound] >= target) break
                if ((bound and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                    checkCallSiteIndexInterrupted()
                }
                bound = (bound shl 1).coerceAtMost(values.size - start)
            }
            var low = start + (bound ushr 1) + 1
            var high = minOf(values.size, start + bound + 1)
            while (low < high) {
                accounting.consume()
                val middle = (low + high).ushr(1)
                if (values[middle] < target) low = middle + 1 else high = middle
            }
            return low
        }

        private fun findStringRow(stringId: Int, accounting: BufferedGraphWorkConsumer): Int {
            var low = 0
            var high = usedStringIds.lastIndex
            while (low <= high) {
                accounting.consume()
                val middle = (low + high).ushr(1)
                when {
                    usedStringIds[middle] < stringId -> low = middle + 1
                    usedStringIds[middle] > stringId -> high = middle - 1
                    else -> return middle
                }
            }
            return -1
        }

        fun postingRange(stringId: Int, accounting: BufferedGraphWorkConsumer): IntRange? {
            val row = findStringRow(stringId, accounting)
            if (row < 0) return null
            val start = if (row == 0) 0 else postingEnds[row - 1]
            return start until postingEnds[row]
        }

        fun postingNodeId(position: Int): Int = postingNodeIds[position]

        fun forEachNodeId(action: (Int) -> Unit) = postingNodeIds.forEach(action)

        fun forEachUsedStringId(action: (Int) -> Unit) = usedStringIds.forEach(action)

        fun collectDistinctValues(
            matchedNodeIds: LongArray,
            stringTable: StringTable,
            workConsumer: GraphWorkConsumer?
        ): Set<String> {
            val values = linkedSetOf<String>()
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            var start = 0
            try {
                for (row in usedStringIds.indices) {
                    val end = postingEnds[row]
                    var position = start
                    while (position < end) {
                        accounting.consume()
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
            } finally {
                accounting.flush()
            }
            return values
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
            .takeIf { predicate.requiresTrigramSignature() }
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

        fun addAll(other: PostingRanges) {
            for (index in 0 until other.size) {
                add(other.propertyIndexes[index], other.positions[index], other.ends[index])
            }
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
                        accounting.flush()
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
            stringTable: StringTable,
            workConsumer: GraphWorkConsumer?
        ): StringPropertyDisjunctionAggregate {
            val wordCount = ((nodeIdCapacity.toLong() + BITSET_WORD_MASK) ushr BITSET_WORD_SHIFT).toInt()
            val matchedNodeIds = LongArray(wordCount)
            var inspected = 0
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            try {
                for (range in 0 until size) {
                    var position = positions[range]
                    val end = ends[range]
                    val property = properties[propertyIndexes[range]]
                    while (position < end) {
                        if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                            checkCallSiteIndexInterrupted()
                        }
                        accounting.consume()
                        val nodeId = property.postingNodeId(position++)
                        matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] =
                            matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] or
                            (1L shl (nodeId and BITSET_WORD_MASK))
                    }
                }
            } finally {
                accounting.flush()
            }
            val count = matchedNodeIds.sumOf { word -> java.lang.Long.bitCount(word).toLong() }
            val distinctValues = distinctPropertyIndex?.let { propertyIndex ->
                val property = properties[propertyIndex]
                if (count * SPARSE_DISTINCT_RANDOM_READ_FACTOR <= property.postingCount) {
                    collectSparseDistinctValues(
                        matchedNodeIds,
                        propertyIndex,
                        rawStringPropertyId,
                        stringTable,
                        workConsumer
                    )
                } else {
                    property.collectDistinctValues(matchedNodeIds, stringTable, workConsumer)
                }
            }
            return StringPropertyDisjunctionAggregate(count, distinctValues?.takeIf { it.isNotEmpty() })
        }

        fun matchedNodeBitSet(nodeIdCapacity: Int, workConsumer: GraphWorkConsumer?): LongArray {
            val wordCount = ((nodeIdCapacity.toLong() + BITSET_WORD_MASK) ushr BITSET_WORD_SHIFT).toInt()
            val matchedNodeIds = LongArray(wordCount)
            var inspected = 0
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            try {
                for (range in 0 until size) {
                    var position = positions[range]
                    val end = ends[range]
                    val property = properties[propertyIndexes[range]]
                    while (position < end) {
                        if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                            checkCallSiteIndexInterrupted()
                        }
                        accounting.consume()
                        val nodeId = property.postingNodeId(position++)
                        matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] =
                            matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] or
                            (1L shl (nodeId and BITSET_WORD_MASK))
                    }
                }
            } finally {
                accounting.flush()
            }
            return matchedNodeIds
        }

        private fun collectSparseDistinctValues(
            matchedNodeIds: LongArray,
            propertyIndex: Int,
            rawStringPropertyId: (Int, Int) -> Int,
            stringTable: StringTable,
            workConsumer: GraphWorkConsumer?
        ): Set<String> {
            val values = linkedSetOf<String>()
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            try {
                for (wordIndex in matchedNodeIds.indices) {
                    var remaining = matchedNodeIds[wordIndex]
                    while (remaining != 0L) {
                        accounting.consume()
                        val bit = java.lang.Long.numberOfTrailingZeros(remaining)
                        val nodeId = (wordIndex shl BITSET_WORD_SHIFT) + bit
                        values += stringTable.get(rawStringPropertyId(nodeId, propertyIndex))
                        remaining = remaining and (remaining - 1L)
                    }
                }
            } finally {
                accounting.flush()
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

    companion object {
        @Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "TooGenericExceptionCaught")
        internal fun readPersistent(
            input: DataInput,
            expectedStringCount: Int,
            expectedCallSiteCount: Int,
            expectedContentIdentity: ByteArray,
            nodeOrder: (Int) -> Long,
            nodeIdCapacity: Int,
            rawStringPropertyId: (Int, Int) -> Int,
            stringTable: StringTable,
            workConsumer: GraphWorkConsumer? = null
        ): MappedCallSiteStringIndex? {
            val work = PersistentIndexReadWork(workConsumer)
            var reservation: MappedCallSiteStringIndexMemoryBudget.Reservation? = null
            fun readInt(): Int {
                work.consume()
                return input.readInt()
            }
            fun readLong(): Long {
                work.consume()
                return input.readLong()
            }
            try {
                require(readInt() == CALL_SITE_STRING_INDEX_MAGIC)
                require(readInt() == CALL_SITE_STRING_INDEX_VERSION)
                val stringCount = readInt()
                val callSiteCount = readInt()
                require(stringCount == expectedStringCount)
                require(callSiteCount == expectedCallSiteCount)
                require(expectedContentIdentity.size == CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
                val persistedContentIdentity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
                input.readFully(persistedContentIdentity)
                repeat(persistedContentIdentity.size) { work.consume() }
                require(persistedContentIdentity.contentEquals(expectedContentIdentity))
                val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { readInt() }
                require(uniqueCounts.all { count -> count in 0..stringCount })
                val trigramPostingCount = readInt()
                require(trigramPostingCount > 0)
                val persistedRetainedBytes = readLong()
                val baseRetainedBytes = estimatedMappedCallSiteStringIndexRetainedBytes(
                    callSiteCount.toLong(),
                    stringCount,
                    uniqueCounts
                ) ?: return null
                val expectedRetainedBytes = runCatching {
                    Math.addExact(
                        baseRetainedBytes,
                        Math.addExact(
                            PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                            Math.multiplyExact(trigramPostingCount.toLong(), Long.SIZE_BYTES.toLong())
                        )
                    )
                }.getOrNull() ?: return null
                require(persistedRetainedBytes == expectedRetainedBytes)
                reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(expectedRetainedBytes)
                    ?: throw MappedCallSiteStringIndexPersistenceBudgetDeniedException()
                val retainedReservation = reservation
                val checksum = CRC32().apply {
                    updateInt(CALL_SITE_STRING_INDEX_MAGIC)
                    updateInt(CALL_SITE_STRING_INDEX_VERSION)
                    updateInt(stringCount)
                    updateInt(callSiteCount)
                    update(persistedContentIdentity)
                    uniqueCounts.forEach(::updateInt)
                    updateInt(trigramPostingCount)
                    updateLong(persistedRetainedBytes)
                }
                val properties = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                    val uniqueCount = uniqueCounts[propertyIndex]
                    var previousStringId = -1
                    val usedStringIds = IntArray(uniqueCount) {
                        readInt().also { stringId ->
                            checksum.updateInt(stringId)
                            require(stringId in 0 until stringCount && stringId > previousStringId)
                            previousStringId = stringId
                        }
                    }
                    var previousEnd = 0
                    val postingEnds = IntArray(uniqueCount) {
                        readInt().also { end ->
                            checksum.updateInt(end)
                            require(end in (previousEnd + 1)..callSiteCount)
                            previousEnd = end
                        }
                    }
                    require(previousEnd == callSiteCount)
                    var row = 0
                    var previousOrder = Long.MIN_VALUE
                    val postingNodeIds = IntArray(callSiteCount) { position ->
                        readInt().also { nodeId ->
                            checksum.updateInt(nodeId)
                            require(nodeId in 0 until nodeIdCapacity)
                            val order = nodeOrder(nodeId)
                            require(order > previousOrder)
                            previousOrder = order
                            if (position + 1 == postingEnds[row]) {
                                row++
                                previousOrder = Long.MIN_VALUE
                            }
                        }
                    }
                    require(row == uniqueCount)
                    PropertyCsr(postingEnds, usedStringIds, postingNodeIds, prevalidated = true)
                }
                val signatures = LongArray(stringCount) { readLong().also(checksum::updateLong) }
                var previousPosting = Long.MIN_VALUE
                val postings = LongArray(trigramPostingCount) {
                    readLong().also { posting ->
                        checksum.updateLong(posting)
                        require(posting >= previousPosting)
                        require(posting.toInt() in 0 until stringCount)
                        previousPosting = posting
                    }
                }
                require(readLong() == checksum.value)
                work.flush()
                val loaded = MappedCallSiteStringIndex(
                    properties,
                    stringTable,
                    nodeOrder,
                    nodeIdCapacity,
                    rawStringPropertyId,
                    contentIdentity = { expectedContentIdentity.copyOf() },
                    reservation = retainedReservation,
                    prepareExactProjectionTupleIndex = true
                ).also { index ->
                    signatures.copyInto(index.trigramSignatures)
                    index.trigramMetadataInitialized = true
                    index.trigramPostingCount = postings.size.toLong()
                    index.trigramPostingsInitialized = true
                    index.trigramPostings = postings
                }
                reservation = null
                return loaded
            } catch (error: Throwable) {
                reservation?.close()
                throw error
            } finally {
                work.flush()
            }
        }

        private fun persistentChecksum(
            stringCount: Int,
            callSiteCount: Int,
            contentIdentity: ByteArray,
            retainedBytes: Long,
            properties: Array<PropertyCsr>,
            signatures: LongArray,
            postings: LongArray
        ): Long = CRC32().also { checksum ->
            checksum.updateInt(CALL_SITE_STRING_INDEX_MAGIC)
            checksum.updateInt(CALL_SITE_STRING_INDEX_VERSION)
            checksum.updateInt(stringCount)
            checksum.updateInt(callSiteCount)
            checksum.update(contentIdentity)
            properties.forEach { property -> checksum.updateInt(property.uniqueStringCount) }
            checksum.updateInt(postings.size)
            checksum.updateLong(retainedBytes)
            properties.forEach { property -> property.updatePersistentChecksum(checksum) }
            signatures.forEach(checksum::updateLong)
            postings.forEach(checksum::updateLong)
        }.value

        private fun persistentRetainedBytes(
            callSiteCount: Int,
            stringCount: Int,
            properties: Array<PropertyCsr>,
            postings: LongArray
        ): Long {
            val baseRetainedBytes = checkNotNull(
                estimatedMappedCallSiteStringIndexRetainedBytes(
                    callSiteCount.toLong(),
                    stringCount,
                    IntArray(properties.size) { index -> properties[index].uniqueStringCount }
                )
            )
            return Math.addExact(
                baseRetainedBytes,
                Math.addExact(
                    PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                    Math.multiplyExact(postings.size.toLong(), Long.SIZE_BYTES.toLong())
                )
            )
        }
    }
}

internal class MappedCallSiteStringIndexPersistenceBudgetDeniedException : Exception()

@Suppress("TooGenericExceptionCaught")
internal class PersistentIndexReadWork(workConsumer: GraphWorkConsumer?) {
    private val accounting = BufferedGraphWorkConsumer(workConsumer)
    private var inspected = 0

    fun consume() {
        if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
            checkCallSiteIndexInterrupted()
        }
        try {
            accounting.consume()
        } catch (error: Throwable) {
            throw MappedCallSiteStringIndexReadAbortedException(error)
        }
    }

    fun flush() {
        try {
            accounting.flush()
        } catch (error: Throwable) {
            throw MappedCallSiteStringIndexReadAbortedException(error)
        }
    }
}

internal class MappedCallSiteStringIndexReadAbortedException(cause: Throwable) : RuntimeException(cause)

/** Exact four-property tuple lookup used by cross-graph DISTINCT provenance rechecks. */
private class ExactCallSiteProjectionTupleIndex(
    private val hashes: LongArray,
    private val nodeIds: IntArray,
    private val stringTable: StringTable,
    private val rawStringPropertyId: (Int, Int) -> Int
) {
    fun selectedProjectionHits(
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        limit: Int,
        nodeOrder: (Int) -> Long,
        workConsumer: GraphWorkConsumer?,
        matchesPredicates: (Int) -> Boolean
    ): List<StringPropertyDistinctRow>? {
        if (propertyIndexes.size != CALL_SITE_STRING_PROPERTY_COUNT) return null
        val valueIndexByProperty = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { -1 }
        propertyIndexes.forEachIndexed { valueIndex, propertyIndex ->
            if (propertyIndex !in 0 until CALL_SITE_STRING_PROPERTY_COUNT ||
                valueIndexByProperty[propertyIndex] >= 0
            ) {
                return null
            }
            valueIndexByProperty[propertyIndex] = valueIndex
        }
        if (valueIndexByProperty.any { it < 0 }) return null
        val targetSize = minOf(limit, selectedValues.size)
        val hits = ArrayList<StringPropertyDistinctRow>(targetSize)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (values in selectedValues) {
                if (values.size != propertyIndexes.size) continue
                val expected = arrayOfNulls<String>(CALL_SITE_STRING_PROPERTY_COUNT)
                valueIndexByProperty.forEachIndexed { propertyIndex, valueIndex ->
                    expected[propertyIndex] = values[valueIndex]
                }
                if (expected.any { it == null }) continue
                val strings = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                    checkNotNull(expected[propertyIndex])
                }
                val hash = callSiteProjectionTupleHash(
                    strings[CALLER_CLASS_PROPERTY_INDEX].hashCode(),
                    strings[CALLER_NAME_PROPERTY_INDEX].hashCode(),
                    strings[CALLEE_CLASS_PROPERTY_INDEX].hashCode(),
                    strings[CALLEE_NAME_PROPERTY_INDEX].hashCode()
                )
                var slot = callSiteProjectionTupleSlot(hash, nodeIds.size)
                while (nodeIds[slot] >= 0) {
                    accounting.consume()
                    val nodeId = nodeIds[slot]
                    if (hashes[slot] == hash && projectionMatches(nodeId, strings) &&
                        matchesPredicates(nodeId)
                    ) {
                        hits += StringPropertyDistinctRow(nodeOrder(nodeId), values)
                        break
                    }
                    slot = (slot + 1) and (nodeIds.size - 1)
                }
            }
        } finally {
            accounting.flush()
        }
        return hits.sortedBy(StringPropertyDistinctRow::encounterOrder).take(targetSize)
    }

    private fun projectionMatches(nodeId: Int, expected: Array<String>): Boolean {
        for (propertyIndex in 0 until CALL_SITE_STRING_PROPERTY_COUNT) {
            if (stringTable.get(rawStringPropertyId(nodeId, propertyIndex)) != expected[propertyIndex]) {
                return false
            }
        }
        return true
    }

    companion object {
        @Suppress("CyclomaticComplexMethod")
        fun tryBuild(
            nodes: MappedCallSiteStringIndex.PropertyCsr?,
            stringTable: StringTable,
            rawStringPropertyId: (Int, Int) -> Int,
            nodeOrder: (Int) -> Long,
            reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
            workConsumer: GraphWorkConsumer?
        ): ExactCallSiteProjectionTupleIndex? {
            val nodeCount = nodes?.postingCount ?: return null
            if (nodeCount < MIN_EXACT_CALL_SITE_PROJECTION_TUPLES) return null
            val capacity = exactCallSiteProjectionTupleCapacity(nodeCount) ?: return null
            val retainedBytes = exactCallSiteProjectionTupleRetainedBytes(capacity) ?: return null
            val temporaryBytes = exactCallSiteProjectionTupleStringHashBytes(stringTable.size()) ?: return null
            val initialReservation = reservation.bytes
            val buildReservation = runCatching {
                Math.addExact(initialReservation, Math.addExact(retainedBytes, temporaryBytes))
            }.getOrNull() ?: return null
            if (!reservation.tryGrowTo(buildReservation)) return null
            var complete = false
            try {
                val hashes = LongArray(capacity)
                val nodeIds = IntArray(capacity) { -1 }
                val stringHashes = IntArray(stringTable.size())
                fun stringHash(stringId: Int): Int {
                    val cached = stringHashes[stringId]
                    if (cached != 0) return cached
                    return stringTable.get(stringId).hashCode().also { hash -> stringHashes[stringId] = hash }
                }
                val accounting = BufferedGraphWorkConsumer(workConsumer)
                try {
                    var inspected = 0
                    nodes.forEachNodeId { nodeId ->
                        if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                            checkCallSiteIndexInterrupted()
                        }
                        accounting.consume()
                        val callerClass = rawStringPropertyId(nodeId, CALLER_CLASS_PROPERTY_INDEX)
                        val callerName = rawStringPropertyId(nodeId, CALLER_NAME_PROPERTY_INDEX)
                        val calleeClass = rawStringPropertyId(nodeId, CALLEE_CLASS_PROPERTY_INDEX)
                        val calleeName = rawStringPropertyId(nodeId, CALLEE_NAME_PROPERTY_INDEX)
                        val hash = callSiteProjectionTupleHash(
                            stringHash(callerClass),
                            stringHash(callerName),
                            stringHash(calleeClass),
                            stringHash(calleeName)
                        )
                        var slot = callSiteProjectionTupleSlot(hash, capacity)
                        while (nodeIds[slot] >= 0) {
                            val existing = nodeIds[slot]
                            if (hashes[slot] == hash &&
                                rawStringPropertyId(existing, CALLER_CLASS_PROPERTY_INDEX) == callerClass &&
                                rawStringPropertyId(existing, CALLER_NAME_PROPERTY_INDEX) == callerName &&
                                rawStringPropertyId(existing, CALLEE_CLASS_PROPERTY_INDEX) == calleeClass &&
                                rawStringPropertyId(existing, CALLEE_NAME_PROPERTY_INDEX) == calleeName
                            ) {
                                if (nodeOrder(nodeId) < nodeOrder(existing)) nodeIds[slot] = nodeId
                                return@forEachNodeId
                            }
                            slot = (slot + 1) and (capacity - 1)
                        }
                        hashes[slot] = hash
                        nodeIds[slot] = nodeId
                    }
                } finally {
                    accounting.flush()
                }
                reservation.shrinkTo(initialReservation + retainedBytes)
                complete = true
                return ExactCallSiteProjectionTupleIndex(hashes, nodeIds, stringTable, rawStringPropertyId)
            } finally {
                if (!complete) reservation.shrinkTo(initialReservation)
            }
        }
    }
}

private fun exactCallSiteProjectionTupleCapacity(nodeCount: Int): Int? {
    val required = nodeCount.toLong() * 2L
    var capacity = 1
    while (capacity.toLong() < required) {
        if (capacity > Int.MAX_VALUE / 2) return null
        capacity = capacity shl 1
    }
    return capacity
}

private fun exactCallSiteProjectionTupleRetainedBytes(capacity: Int): Long? = runCatching {
    Math.addExact(
        EXACT_CALL_SITE_PROJECTION_TUPLE_INDEX_ESTIMATED_BYTES + 2L * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.multiplyExact(capacity.toLong(), Long.SIZE_BYTES.toLong() + Int.SIZE_BYTES.toLong())
    )
}.getOrNull()

private fun exactCallSiteProjectionTupleStringHashBytes(stringCount: Int): Long? = runCatching {
    Math.addExact(
        PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.multiplyExact(stringCount.toLong(), Int.SIZE_BYTES.toLong())
    )
}.getOrNull()

private fun callSiteProjectionTupleHash(
    callerClass: Int,
    callerName: Int,
    calleeClass: Int,
    calleeName: Int
): Long {
    var hash = CALL_SITE_PROJECTION_TUPLE_HASH_SEED
    hash = (hash xor callerClass.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
    hash = (hash xor callerName.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
    hash = (hash xor calleeClass.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
    return (hash xor calleeName.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
}

private fun callSiteProjectionTupleSlot(hash: Long, capacity: Int): Int {
    var mixed = hash
    mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
    mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
    return (mixed xor (mixed ushr 33)).toInt() and (capacity - 1)
}

private data class CallSiteTrigramPostingRange(
    val trigram: Int,
    val start: Int,
    val end: Int
) {
    val size: Int
        get() = end - start
}

private class SplitCallSiteTask<T>(private val task: Callable<T>) {
    private val state = AtomicInteger(NEW)
    private val exited = CountDownLatch(1)
    lateinit var future: Future<T>

    val callable = Callable {
        if (!state.compareAndSet(NEW, RUNNING)) throw CancellationException()
        try {
            task.call()
        } finally {
            state.set(FINISHED)
            exited.countDown()
        }
    }

    fun cancel() {
        future.cancel(true)
        if (state.compareAndSet(NEW, FINISHED)) exited.countDown()
    }

    fun awaitExit(): InterruptedException? {
        var interruption: InterruptedException? = null
        while (true) {
            try {
                exited.await()
                return interruption
            } catch (error: InterruptedException) {
                if (interruption == null) interruption = error
            }
        }
    }

    private companion object {
        const val NEW = 0
        const val RUNNING = 1
        const val FINISHED = 2
    }
}

private fun <T> cancelAndJoinSplitCallSiteTasks(
    tasks: List<SplitCallSiteTask<T>>
): InterruptedException? {
    tasks.forEach { task -> task.cancel() }
    var interruption: InterruptedException? = null
    tasks.forEach { task ->
        task.awaitExit()?.let { error -> if (interruption == null) interruption = error }
    }
    return interruption
}

@Suppress("ThrowsCount", "TooGenericExceptionCaught")
internal fun executeSplitCallSiteCandidateTasks(
    tasks: List<Callable<IntArray>>,
    backgroundParallelism: Int
): IntArray {
    val results = executeSplitCallSiteTasks(tasks, backgroundParallelism)
    val size = results.sumOf(IntArray::size)
    val combined = IntArray(size)
    var offset = 0
    results.forEach { result ->
        result.copyInto(combined, offset)
        offset += result.size
    }
    return combined
}

private val splitCallSiteWorkerNumber = AtomicInteger()
private val splitCallSiteActiveWorkers = AtomicInteger()
private val splitCallSitePeakActiveWorkers = AtomicInteger()
private val splitCallSiteExecutors = ConcurrentHashMap<Int, ThreadPoolExecutor>()

internal fun splitCallSitePeakActiveWorkers(): Int = splitCallSitePeakActiveWorkers.get()

internal fun resetSplitCallSiteWorkerMetrics() {
    check(splitCallSiteActiveWorkers.get() == 0) { "Cannot reset active split CallSite worker metrics" }
    splitCallSitePeakActiveWorkers.set(0)
}

internal fun splitCallSiteExecutor(backgroundParallelism: Int) =
    splitCallSiteExecutors.computeIfAbsent(backgroundParallelism) { parallelism ->
        ThreadPoolExecutor(
            parallelism,
            parallelism,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { runnable ->
                Thread(
                    runnable,
                    "graphite-callsite-segment-${splitCallSiteWorkerNumber.incrementAndGet()}"
                ).apply { isDaemon = true }
            }
        )
    }

internal fun <T> trackedSplitCallSiteTask(task: Callable<T>): Callable<T> = Callable {
    val activeWorkers = splitCallSiteActiveWorkers.incrementAndGet()
    splitCallSitePeakActiveWorkers.accumulateAndGet(activeWorkers, ::maxOf)
    try {
        task.call()
    } finally {
        // Keep teardown inside the Future's callable. Future.get() must not return while this
        // worker is still counted active; ThreadPoolExecutor.afterExecute runs too late for that.
        splitCallSiteActiveWorkers.decrementAndGet()
    }
}

@Suppress("ThrowsCount", "TooGenericExceptionCaught")
private fun <T> executeSplitCallSiteTasks(
    tasks: List<Callable<T>>,
    backgroundParallelism: Int
): List<T> {
    require(tasks.isNotEmpty())
    require(backgroundParallelism >= 0)
    require(tasks.size <= backgroundParallelism + 1)
    if (tasks.size == 1) return listOf(tasks.single().call())
    val executor = splitCallSiteExecutor(backgroundParallelism)
    val backgroundTasks = tasks.drop(1).map { task -> SplitCallSiteTask(task) }
    backgroundTasks.forEach { task -> task.future = executor.submit(trackedSplitCallSiteTask(task.callable)) }
    val results = arrayOfNulls<Any?>(tasks.size)
    try {
        results[0] = tasks.first().call()
        backgroundTasks.forEachIndexed { index, task -> results[index + 1] = task.future.get() }
    } catch (error: InterruptedException) {
        cancelAndJoinSplitCallSiteTasks(backgroundTasks)
        Thread.currentThread().interrupt()
        throw CancellationException(CALL_SITE_STRING_MATCH_INTERRUPTED).apply { initCause(error) }
    } catch (error: ExecutionException) {
        cancelAndJoinSplitCallSiteTasks(backgroundTasks)?.let { interruption ->
            Thread.currentThread().interrupt()
            throw CancellationException(CALL_SITE_STRING_MATCH_INTERRUPTED).apply { initCause(interruption) }
        }
        throw error.cause ?: error
    } catch (error: Throwable) {
        cancelAndJoinSplitCallSiteTasks(backgroundTasks)?.let { interruption ->
            Thread.currentThread().interrupt()
            throw CancellationException(CALL_SITE_STRING_MATCH_INTERRUPTED).apply { initCause(interruption) }
        }
        throw error
    }
    @Suppress("UNCHECKED_CAST")
    return results.map { result -> result as T }
}

internal data class CallSitePredicateKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

private data class CachedMatchingStringIds(
    val stringIds: IntArray,
    val retainedBytes: Long
)

private data class CallSiteNodeMatchKey(
    val predicates: List<StringPropertyPredicate>,
    val limit: Int
)

private data class CachedMatchingNodeIds(
    val nodeIds: IntArray,
    val retainedBytes: Long
)

private data class CallSiteProjectionKey(
    val predicates: List<StringPropertyPredicate>,
    val projectedProperties: List<String>,
    val limit: Int
)

private data class CachedProjectionRows(
    val rows: List<StringPropertyProjectionRow>,
    val retainedBytes: Long
)

private fun estimatedCallSiteStringMatchCacheBytes(
    key: CallSitePredicateKey,
    stringIds: IntArray
): Long = try {
    Math.addExact(
        CALL_SITE_STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES +
            CALL_SITE_STRING_MATCH_CACHE_STRING_HEADER_ESTIMATED_BYTES +
            PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.addExact(
            Math.multiplyExact(key.expected.length.toLong(), Char.SIZE_BYTES.toLong()),
            Math.multiplyExact(stringIds.size.toLong(), Int.SIZE_BYTES.toLong())
        )
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

private fun estimatedCallSiteNodeMatchCacheBytes(
    key: CallSiteNodeMatchKey,
    nodeIds: IntArray
): Long = try {
    val keyCharacters = key.predicates.sumOf { predicate ->
        predicate.property.length.toLong() + predicate.expected.length
    }
    Math.addExact(
        CALL_SITE_NODE_MATCH_CACHE_ENTRY_ESTIMATED_BYTES + PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.addExact(
            Math.multiplyExact(keyCharacters, Char.SIZE_BYTES.toLong()),
            Math.multiplyExact(nodeIds.size.toLong(), Int.SIZE_BYTES.toLong())
        )
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

private fun estimatedCallSiteProjectionCacheBytes(
    key: CallSiteProjectionKey,
    rows: List<StringPropertyProjectionRow>
): Long = try {
    val keyCharacters = key.predicates.sumOf { predicate ->
        predicate.property.length.toLong() + predicate.expected.length
    } + key.projectedProperties.sumOf { property -> property.length.toLong() }
    val valueReferences = rows.sumOf { row -> row.values.size.toLong() }
    var decodedStringBytes = 0L
    rows.forEach { row ->
        row.values.forEach { value ->
            if (value != null) {
                decodedStringBytes = Math.addExact(
                    decodedStringBytes,
                    Math.addExact(
                        CALL_SITE_PROJECTION_STRING_ESTIMATED_BYTES + PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                        Math.multiplyExact(value.length.toLong(), Char.SIZE_BYTES.toLong())
                    )
                )
            }
        }
    }
    Math.addExact(
        CALL_SITE_PROJECTION_CACHE_ENTRY_ESTIMATED_BYTES,
        Math.addExact(
            Math.multiplyExact(keyCharacters, Char.SIZE_BYTES.toLong()),
            Math.addExact(
                Math.multiplyExact(rows.size.toLong(), CALL_SITE_PROJECTION_ROW_ESTIMATED_BYTES),
                Math.addExact(
                    Math.multiplyExact(valueReferences, REFERENCE_ESTIMATED_BYTES),
                    decodedStringBytes
                )
            )
        )
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

internal object MappedCallSiteStringIndexMemoryBudget {
    private const val BUDGET_PROPERTY = "graphite.webgraph.callSiteStringIndexBudgetBytes"
    private const val DEFAULT_MAX_HEAP_FRACTION = 2L
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

internal fun sortCallSiteTrigramPostings(
    postings: LongArray,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation? = null,
    onParallelWorkerStarted: (() -> Unit)? = null
): Boolean {
    checkCallSiteIndexInterrupted()
    if (postings.size < MIN_PARALLEL_CALL_SITE_TRIGRAM_SORT_SIZE) {
        Arrays.sort(postings)
        checkCallSiteIndexInterrupted()
        return true
    }

    val temporaryBytes = runCatching {
        Math.addExact(
            PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
            Math.multiplyExact(postings.size.toLong(), Long.SIZE_BYTES.toLong())
        )
    }.getOrNull() ?: return false
    val retainedBefore = reservation?.bytes
    if (reservation != null) {
        val retainedWithTemporary = runCatching {
            Math.addExact(checkNotNull(retainedBefore), temporaryBytes)
        }.getOrNull() ?: return false
        if (!reservation.tryGrowTo(retainedWithTemporary)) return false
    }

    try {
        val temporary = LongArray(postings.size)
        parallelCallSiteTrigramRadixSort(postings, temporary, onParallelWorkerStarted)
        return true
    } finally {
        if (reservation != null) reservation.shrinkTo(checkNotNull(retainedBefore))
    }
}

private fun parallelCallSiteTrigramRadixSort(
    postings: LongArray,
    temporary: LongArray,
    onParallelWorkerStarted: (() -> Unit)?
) {
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

private fun runCallSiteTrigramSortPhase(
    workerCount: Int,
    onParallelWorkerStarted: (() -> Unit)?,
    work: (Int, AtomicBoolean) -> Unit
) {
    val abort = AtomicBoolean()
    val tasks = List(workerCount) { workerIndex ->
        Callable {
            onParallelWorkerStarted?.invoke()
            work(workerIndex, abort)
        }
    }
    awaitCallSiteTrigramTasks(tasks, abort)
}

private fun checkCallSiteTrigramSortWorker(index: Int, abort: AtomicBoolean) {
    if ((index and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) != 0) return
    if (abort.get()) throw CancellationException(CALL_SITE_TRIGRAM_SORT_ABORTED)
    checkCallSiteIndexInterrupted()
}

private fun callSiteTrigramRadixBucket(value: Long, shift: Int): Int {
    val bucket = ((value ushr shift) and RADIX_BUCKET_MASK).toInt()
    return if (shift == SIGNED_LONG_RADIX_SHIFT) bucket xor SIGNED_RADIX_BUCKET_FLIP else bucket
}

private fun StringPropertyPredicate.requiresTrigramSignature(): Boolean =
    mode == StringMatchMode.CONTAINS &&
        expected.length >= MIN_CALL_SITE_TRIGRAM_LENGTH &&
        expected.all { character -> character.code < ASCII_LIMIT }

private fun StringPropertyPredicate.canUseLowercaseTrigramPostings(): Boolean =
    transform == StringValueTransform.LOWERCASE ||
        transform == null && expected.all { character -> character.code < ASCII_LIMIT }

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

private fun usedCallSiteTrigramStringIds(
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

private fun populateCallSiteTrigramMetadata(
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

private fun populateCallSiteTrigramPostings(
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

private fun populateCallSiteTrigramMetadataInParallel(
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

private fun populateCallSiteTrigramPostingsInParallel(
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

private fun checkCallSiteTrigramWorker(index: Int, abort: AtomicBoolean) {
    if ((index and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) != 0) return
    if (abort.get()) throw CancellationException(CALL_SITE_TRIGRAM_BUILD_ABORTED)
    checkCallSiteIndexInterrupted()
}

@Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount")
private fun <T> awaitCallSiteTrigramTasks(tasks: List<Callable<T>>, abort: AtomicBoolean): List<T> {
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

private fun populateCallSiteTrigramMetadata(
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
        val mixed = hash xor (hash ushr 11) xor (hash shl 7)
        signature = signature or (1L shl (hash and TRIGRAM_SIGNATURE_MASK))
        signature = signature or (1L shl (mixed and TRIGRAM_SIGNATURE_MASK))
        if (seenTrigrams.add(hash)) postingCount++
    }
    target[stringId] = signature
    postingCounts[stringId] = postingCount
    return postingCount
}

private fun populateCallSiteTrigramPostings(
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

private fun callSiteTrigramHash(value: String, position: Int): Int =
    (value[position].code * STRING_HASH_FACTOR + value[position + 1].code) * STRING_HASH_FACTOR +
        value[position + 2].code

private fun trigramKey(trigram: Int, stringId: Int): Long =
    (trigram.toLong() shl Int.SIZE_BITS) or (stringId.toLong() and INT_UNSIGNED_MASK)

private fun lowerBound(values: LongArray, target: Long): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (values[middle] < target) low = middle + 1 else high = middle
    }
    return low
}

private fun upperBound(values: LongArray, target: Long): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (values[middle] <= target) low = middle + 1 else high = middle
    }
    return low
}

private fun CRC32.updateInt(value: Int) {
    repeat(Int.SIZE_BYTES) { byteIndex ->
        update(value ushr (byteIndex * Byte.SIZE_BITS) and 0xff)
    }
}

private fun CRC32.updateLong(value: Long) {
    repeat(Long.SIZE_BYTES) { byteIndex ->
        update(((value ushr (byteIndex * Byte.SIZE_BITS)) and 0xffL).toInt())
    }
}

private fun CRC32.updateDirectoryLongBigEndian(value: Long) {
    repeat(Long.SIZE_BYTES) { byteIndex ->
        update(((value ushr ((Long.SIZE_BYTES - 1 - byteIndex) * Byte.SIZE_BITS)) and 0xffL).toInt())
    }
}

private fun CRC32.updateDirectoryIntBigEndian(value: Int) {
    repeat(Int.SIZE_BYTES) { byteIndex ->
        update(value ushr ((Int.SIZE_BYTES - 1 - byteIndex) * Byte.SIZE_BITS) and 0xff)
    }
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
internal const val CALL_SITE_STRING_INDEX_MAGIC = 0x47524353
internal const val CALL_SITE_STRING_INDEX_VERSION = 2
internal const val CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES = 32
internal const val CALL_SITE_STRING_INDEX_HEADER_BYTES =
    4 * Int.SIZE_BYTES + CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES +
        CALL_SITE_STRING_PROPERTY_COUNT * Int.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES
private const val CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK = 1_023
private const val MIN_PARALLEL_CALL_SITE_TRIGRAM_SORT_SIZE = 1 shl 20
private const val RADIX_BUCKET_COUNT = 1 shl Byte.SIZE_BITS
private const val RADIX_BUCKET_MASK = RADIX_BUCKET_COUNT - 1L
private const val SIGNED_LONG_RADIX_SHIFT = (Long.SIZE_BYTES - 1) * Byte.SIZE_BITS
private const val SIGNED_RADIX_BUCKET_FLIP = 1 shl (Byte.SIZE_BITS - 1)
private const val MAX_SERIAL_CALL_SITE_STRING_INDEX_SCAN_BYTES = 1024L * 1024
private const val MAX_CALL_SITE_STRING_MATCH_CACHE_ENTRIES = 32
private const val MAX_CALL_SITE_STRING_MATCH_CACHE_BYTES = 2L * 1024 * 1024
private const val CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR = 0.75f
private const val CALL_SITE_STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 64L
private const val CALL_SITE_STRING_MATCH_CACHE_STRING_HEADER_ESTIMATED_BYTES = 24L
private const val MAX_CALL_SITE_NODE_MATCH_CACHE_ENTRIES = 32
private const val MAX_CALL_SITE_NODE_MATCH_CACHE_BYTES = 2L * 1024 * 1024
private const val MAX_CALL_SITE_NODE_MATCH_CACHE_LIMIT = 200
private const val CALL_SITE_NODE_MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 128L
private const val MAX_CALL_SITE_PROJECTION_CACHE_ENTRIES = 32
private const val MAX_CALL_SITE_PROJECTION_CACHE_BYTES = 2L * 1024 * 1024
private const val CALL_SITE_PROJECTION_CACHE_ENTRY_ESTIMATED_BYTES = 128L
private const val CALL_SITE_PROJECTION_ROW_ESTIMATED_BYTES = 64L
private const val CALL_SITE_PROJECTION_STRING_ESTIMATED_BYTES = 24L
private const val REFERENCE_ESTIMATED_BYTES = 8L
private const val INITIAL_POSTING_RANGES = 16
private const val BITSET_WORD_SHIFT = 6
private const val BITSET_WORD_MASK = Long.SIZE_BITS - 1
private const val SPARSE_DISTINCT_RANDOM_READ_FACTOR = 4L
private const val MIN_CALL_SITE_TRIGRAM_LENGTH = 3
private const val MIN_PARALLEL_CALL_SITE_MATCH_CANDIDATES = 4_096
private const val MIN_PARALLEL_CALL_SITE_TRIGRAM_STRINGS = 4_096
private const val MIN_EXACT_CALL_SITE_PROJECTION_TUPLES = 4_096
private const val MIN_SELECTED_VALUES_FOR_EXACT_PROJECTION_INDEX = 256
private const val EXACT_CALL_SITE_PROJECTION_TUPLE_INDEX_ESTIMATED_BYTES = 128L
private const val CALL_SITE_PROJECTION_TUPLE_HASH_SEED = -3750763034362895579L
private const val CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR = 1099511628211L
private const val CALL_SITE_STRING_MATCH_INTERRUPTED = "CallSite string candidate match interrupted"
private const val CALL_SITE_TRIGRAM_BUILD_ABORTED = "CallSite trigram metadata build aborted"
private const val CALL_SITE_TRIGRAM_BUILD_INTERRUPTED = "CallSite trigram metadata build interrupted"
private const val CALL_SITE_TRIGRAM_SORT_ABORTED = "CallSite trigram posting sort aborted"
private const val ASCII_LIMIT = 128
private const val STRING_HASH_FACTOR = 31
private const val TRIGRAM_SIGNATURE_MASK = Long.SIZE_BITS - 1
private const val INT_UNSIGNED_MASK = 0xffff_ffffL
private const val CALL_SITE_TRIGRAM_INDEX_BUDGET_PROPERTY = "graphite.webgraph.callSiteTrigramIndexBudgetBytes"
private const val DEFAULT_CALL_SITE_TRIGRAM_HEAP_DIVISOR = 8L
