package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import java.io.Closeable
import java.io.DataInput
import java.io.DataOutput
import java.util.Arrays

/** Compact CSR indexes for the four strings searched by broad CallSite queries. */
internal class MappedCallSiteStringIndex(
    internal val properties: Array<PropertyCsr>,
    internal val stringTable: StringTable,
    internal val nodeOrder: (Int) -> Long,
    internal val nodeIdCapacity: Int,
    internal val rawStringPropertyId: (Int, Int) -> Int,
    internal val contentIdentity: () -> ByteArray,
    internal val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
    prepareExactProjectionTupleIndex: Boolean = false
) : Closeable, CallSiteStringIdMembership {

    internal val persistedContentIdentity: ByteArray by lazy(contentIdentity)

    init {
        require(properties.size == CALL_SITE_STRING_PROPERTY_COUNT)
        require(properties.map(PropertyCsr::postingCount).distinct().size <= 1)
    }

    internal val exactProjectionTupleIndexEnabled = prepareExactProjectionTupleIndex
    @Volatile
    internal var exactProjectionTupleIndex: ExactCallSiteProjectionTupleIndex? = null

    internal val trigramSignatures = LongArray(stringTable.size())
    internal var trigramMetadataInitialized = false
    internal var trigramPostingCount = 0L
    internal var trigramPostingCounts: IntArray? = null
    internal var trigramStringIds: IntArray? = null
    internal var trigramPostingsInitialized = false
    internal var trigramPostings: LongArray? = null
    internal val matchingStringIds = LinkedHashMap<CallSitePredicateKey, CachedMatchingStringIds>(
        MAX_CALL_SITE_STRING_MATCH_CACHE_ENTRIES + 1,
        CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR,
        true
    )
    internal var matchingStringCacheBytes = 0L
    internal val matchingNodeIds = LinkedHashMap<CallSiteNodeMatchKey, CachedMatchingNodeIds>(
        MAX_CALL_SITE_NODE_MATCH_CACHE_ENTRIES + 1,
        CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR,
        true
    )
    internal var matchingNodeCacheBytes = 0L
    internal val projectedRows = LinkedHashMap<CallSiteProjectionKey, CachedProjectionRows>(
        MAX_CALL_SITE_PROJECTION_CACHE_ENTRIES + 1,
        CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR,
        true
    )
    internal var projectedRowCacheBytes = 0L

    internal val queries = CallSiteIndexQueries(this)
    internal val matches = CallSiteStringMatches(this)
    internal val distinct = CallSiteDistinctProjection(this)
    internal val buildTrigrams = CallSiteTrigramBuilder(this)

    val retainedBytes: Long
        get() = reservation.bytes

    internal fun hasExactProjectionTupleIndex(): Boolean = exactProjectionTupleIndex != null

    internal fun exactProjectionTupleIndex(
        workConsumer: GraphWorkConsumer?
    ): ExactCallSiteProjectionTupleIndex? {
        val cached = exactProjectionTupleIndex
        if (cached != null || !exactProjectionTupleIndexEnabled) return cached
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
    ): Sequence<Int> = queries.matchingNodeIds(predicates, workConsumer, limit)

    fun projectRows(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyProjectionRow> = queries.projectRows(predicates, projectedProperties, limit, workConsumer)

    @Synchronized
    internal fun cacheProjectedRows(
        key: CallSiteProjectionKey,
        rows: List<StringPropertyProjectionRow>
    ): List<StringPropertyProjectionRow> = CallSiteIndexCacheWriter.cacheProjectedRows(this, key, rows)

    @Synchronized
    internal fun cacheMatchingNodeIds(key: CallSiteNodeMatchKey, nodeIds: IntArray) =
        CallSiteIndexCacheWriter.cacheMatchingNodeIds(this, key, nodeIds)

    fun aggregate(
        predicates: List<StringPropertyPredicate>,
        distinctProperty: String?,
        workConsumer: GraphWorkConsumer?
    ): StringPropertyDisjunctionAggregate = queries.aggregate(predicates, distinctProperty, workConsumer)

    internal fun exactMatchingStringIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): List<IntArray>? = matches.exactMatchingStringIds(predicates, workConsumer)

    override fun containsPropertyStringId(
        propertyIndex: Int,
        stringId: Int,
        workConsumer: GraphWorkConsumer?
    ): Boolean = matches.containsPropertyStringId(propertyIndex, stringId, workConsumer)

    @Synchronized
    internal fun cacheMatchingStringIds(key: CallSitePredicateKey, result: IntArray) =
        CallSiteIndexCacheWriter.cacheMatchingStringIds(this, key, result)

    @Synchronized
    internal fun callSiteTrigramPostings(workConsumer: GraphWorkConsumer?): LongArray? {
        if (trigramPostingsInitialized) return trigramPostings
        trigramPostings = buildTrigrams.buildCallSiteTrigramPostings(workConsumer)
        trigramPostingsInitialized = true
        return trigramPostings
    }

    @Synchronized
    internal fun ensureTrigramMetadata(workConsumer: GraphWorkConsumer?): Long {
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
        checkCallSiteIndexPersistenceInterrupted()
        val postings = checkNotNull(trigramPostings.takeIf { trigramPostingsInitialized }) {
            "CallSite trigram postings must be prepared before persistence"
        }
        val callSiteCount = properties.firstOrNull()?.postingCount ?: 0
        val persistentRetainedBytes = callSiteIndexPersistentRetainedBytes(callSiteCount, stringTable.size(), properties, postings)
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
        trigramSignatures.forEachIndexed { index, value ->
            if ((index and CALL_SITE_INDEX_PERSISTENCE_POLL_MASK) == 0) {
                checkCallSiteIndexPersistenceInterrupted()
            }
            output.writeLong(value)
        }
        postings.forEachIndexed { index, value ->
            if ((index and CALL_SITE_INDEX_PERSISTENCE_POLL_MASK) == 0) {
                checkCallSiteIndexPersistenceInterrupted()
            }
            output.writeLong(value)
        }
        output.writeLong(
            callSiteIndexPersistentChecksum(
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

    internal fun contentIdentity(): ByteArray = persistedContentIdentity.copyOf()

    fun distinctProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> = distinct.distinctProjection(predicates, projectedProperties, limit, selectedValues, workConsumer)

    @Synchronized
    fun clearQueryCaches() {
        val cacheBytes = matchingStringCacheBytes + matchingNodeCacheBytes + projectedRowCacheBytes
        if (cacheBytes > 0L) reservation.shrinkTo(reservation.bytes - cacheBytes)
        matchingStringIds.clear()
        matchingStringCacheBytes = 0L
        matchingNodeIds.clear()
        matchingNodeCacheBytes = 0L
        projectedRows.clear()
        projectedRowCacheBytes = 0L
    }

    @Synchronized
    override fun close() {
        exactProjectionTupleIndex = null
        trigramPostingCounts = null
        trigramStringIds = null
        clearQueryCaches()
        reservation.close()
    }

    internal class PropertyCsr(
        postingEnds: IntArray,
        usedStringIds: IntArray,
        postingNodeIds: IntArray,
        prevalidated: Boolean = false
    ) : CallSitePostingDirectory(postingEnds, usedStringIds, postingNodeIds, prevalidated)

    companion object {
        internal fun readPersistent(
            input: DataInput,
            context: CallSiteIndexReadContext,
            workConsumer: GraphWorkConsumer? = null
        ): MappedCallSiteStringIndex? = CallSiteStringIndexReader(input, context, workConsumer).read()
    }
}
