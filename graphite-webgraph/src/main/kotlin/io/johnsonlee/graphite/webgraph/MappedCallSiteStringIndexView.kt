@file:Suppress(
    "CyclomaticComplexMethod",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions"
)

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.StringPropertyTupleSet
import io.johnsonlee.graphite.graph.StringValueTransform
import it.unimi.dsi.fastutil.ints.IntArrayList
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
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/** Reads the four CallSite string ids of one node from raw mapped node data into [target]. */
internal fun interface CallSiteRawStringIds {
    fun read(nodeId: Int, target: IntArray)
}

/**
 * Integrity-checked, read-only view over the existing `graph.callsite-string-index` format.
 *
 * The complete retained index materializes every posting into heap arrays. Cold queries only need
 * the already-persisted property directories, node postings, and trigram postings, so this view
 * validates the existing file once on the requesting thread and then serves bounded ordered
 * projections, distinct projections, and selected-tuple provenance lookups directly from the
 * mapped regions. No thread pool participates: every lookup runs on the caller.
 */
internal class MappedCallSiteStringIndexView private constructor(
    private val propertyStringIds: Array<IntBuffer>,
    private val propertyPostingEnds: Array<IntBuffer>,
    private val propertyPostingNodeIds: Array<IntBuffer>,
    private val trigramPostings: LongBuffer,
    private val callSiteCount: Int,
    private val stringCount: Int,
    private val stringTable: StringTable,
    private val nodeOrder: (Int) -> Long,
    private val rawStringIds: CallSiteRawStringIds,
    private val trigramDirectory: TrigramDirectory?
) : Closeable {
    private val validatedPostingRanges = BoundedPostingRangeValidationCache.create()
    private val matchCache = BoundedMatchingStringIdCache.create()
    private val postingCounts = PlanPostingCountCache()

    override fun close() {
        validatedPostingRanges?.close()
        matchCache?.close()
        postingCounts.clear()
        trigramDirectory?.close()
    }

    internal fun validatedPostingRangeCount(): Int = validatedPostingRanges?.size() ?: 0

    internal fun validatedPostingRangeBytes(): Long = validatedPostingRanges?.retainedBytes ?: 0L

    // ------------------------------------------------------------------ predicate matching

    /**
     * Plans every predicate against the persisted trigram postings, string table, and property
     * directories. Planning is cheap and lazy: an absent trigram proves a predicate matches nothing
     * without decoding any string, a rare trigram bounds the candidates that later verification
     * decodes, and a term whose rarest probed trigram is still common is reported as likely dense so
     * the caller can answer from a bounded raw prefix before paying for verification. Returns null
     * when a predicate targets an unsupported property.
     */
    fun matchPlan(predicates: List<StringPropertyPredicate>, workConsumer: GraphWorkConsumer?): MatchPlan? {
        if (predicates.any { predicate -> callSiteStringPropertyIndex(predicate.property) < 0 }) return null
        val probes = LinkedHashMap<MappedPredicateKey, PredicateProbe>(predicates.size * 2)
        predicates.forEach { predicate ->
            val key = MappedPredicateKey(predicate.transform, predicate.mode, predicate.expected)
            if (key !in probes) {
                val probe = probe(predicate, workConsumer)
                // A proven-absent term is remembered like a verified one so repeated shapes of the
                // same request skip the trigram probe.
                if (probe is PredicateProbe.Absent) matchCache?.put(key, EMPTY_INTS)
                probes[key] = probe
            }
        }
        return MatchPlan(predicates, probes, workConsumer)
    }

    internal sealed class PredicateProbe {
        /** A dictionary or trigram lookup proved the predicate matches no string. */
        object Absent : PredicateProbe()

        /** Matching string ids resolved exactly by the string table or a cached verification. */
        class Exact(val stringIds: IntArray) : PredicateProbe()

        /** Candidates bounded by the rarest probed trigram; verification decodes only that span. */
        class Trigram(val anchor: IntRange) : PredicateProbe() {
            val spanSize: Int
                get() = anchor.last - anchor.first + 1
        }

        /** Short or non-ASCII terms scan the property dictionaries. */
        object Scan : PredicateProbe()
    }

    private fun probe(predicate: StringPropertyPredicate, workConsumer: GraphWorkConsumer?): PredicateProbe {
        val key = MappedPredicateKey(predicate.transform, predicate.mode, predicate.expected)
        matchCache?.get(key)?.let { cached ->
            return if (cached.isEmpty()) PredicateProbe.Absent else PredicateProbe.Exact(cached)
        }
        val lowercase = predicate.transform == StringValueTransform.LOWERCASE
        if (!lowercase && predicate.mode == StringMatchMode.EQUALS) {
            consumeGraphWork(workConsumer, 1L)
            val stringId = stringTable.findId(predicate.expected)
            return if (stringId < 0) PredicateProbe.Absent else PredicateProbe.Exact(intArrayOf(stringId))
        }
        if (!lowercase && predicate.mode == StringMatchMode.STARTS_WITH) {
            val range = stringTable.prefixRange(predicate.expected, workConsumer)
            if (range.isEmpty()) return PredicateProbe.Absent
            return PredicateProbe.Exact(IntArray(range.last - range.first + 1) { offset -> range.first + offset })
        }
        if (!predicate.canUseTrigramPostings()) return PredicateProbe.Scan
        val expected = predicate.expected.lowercase()
        val positions = when (predicate.mode) {
            StringMatchMode.STARTS_WITH -> 0..0
            StringMatchMode.ENDS_WITH -> expected.length - TRIGRAM_LENGTH..expected.length - TRIGRAM_LENGTH
            else -> 0..expected.length - TRIGRAM_LENGTH
        }
        var anchor: IntRange? = null
        var anchorSize = Int.MAX_VALUE
        val seen = HashSet<Int>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (position in positions) {
                val trigram = mappedTrigramHash(expected, position)
                if (!seen.add(trigram)) continue
                accounting.consume()
                val span = trigramPostingRange(trigram) ?: return PredicateProbe.Absent
                val size = span.last - span.first + 1
                if (size < anchorSize) {
                    anchor = span
                    anchorSize = size
                }
                // A rare trigram already bounds verification; probing the remaining positions only
                // costs mapped binary searches without shrinking the decoded candidate set further.
                if (anchorSize <= SMALL_TRIGRAM_SPAN) break
            }
        } finally {
            accounting.flush()
        }
        return anchor?.let { PredicateProbe.Trigram(it) } ?: PredicateProbe.Scan
    }

    inner class MatchPlan internal constructor(
        private val predicates: List<StringPropertyPredicate>,
        private val probes: Map<MappedPredicateKey, PredicateProbe>,
        private val workConsumer: GraphWorkConsumer?
    ) {
        private var rowsByProperty: Array<IntArray?>? = null

        /** Every predicate proved zero matches without decoding a single string. */
        val knownEmpty: Boolean = probes.values.all { probe -> probe is PredicateProbe.Absent }

        /** Some predicate's rarest probed trigram or exact range still covers many strings. */
        val likelyDense: Boolean = probes.values.any { probe ->
            when (probe) {
                is PredicateProbe.Trigram -> probe.spanSize >= DENSE_TRIGRAM_SPAN
                is PredicateProbe.Exact -> probe.stringIds.size >= DENSE_TRIGRAM_SPAN
                else -> false
            }
        }

        val isEmpty: Boolean
            get() = knownEmpty || resolve().all { rows -> rows == null || rows.isEmpty() }

        /**
         * Total posting entries selected by the plan; an upper bound of matching nodes. The count
         * is remembered per predicate set so a repeated dense plan decides on the raw prefix
         * without resolving its directory rows again.
         */
        val postingCount: Long by lazy {
            val key = predicates.map { predicate ->
                PlanPredicateKey(
                    callSiteStringPropertyIndex(predicate.property),
                    MappedPredicateKey(predicate.transform, predicate.mode, predicate.expected)
                )
            }
            postingCounts.get(key) ?: run {
                var total = 0L
                resolve().forEachIndexed { propertyIndex, rows ->
                    rows?.forEach { row -> total += postingSize(propertyIndex, row) }
                }
                postingCounts.put(key, total)
                total
            }
        }

        internal fun rows(propertyIndex: Int): IntArray = resolve()[propertyIndex] ?: EMPTY_INTS

        /** True when the raw string ids of one node satisfy at least one planned predicate. */
        internal fun matchesNode(stringIds: IntArray): Boolean {
            val resolved = resolve()
            repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                val rows = resolved[propertyIndex]
                if (rows != null && rows.isNotEmpty()) {
                    val directory = propertyStringIds[propertyIndex]
                    val row = binarySearch(directory, stringIds[propertyIndex], 0, directory.limit() - 1)
                    if (row >= 0 && java.util.Arrays.binarySearch(rows, row) >= 0) return true
                }
            }
            return false
        }

        private fun resolve(): Array<IntArray?> {
            rowsByProperty?.let { return it }
            val resolved = arrayOfNulls<IntArray>(CALL_SITE_STRING_PROPERTY_COUNT)
            val stringIdsByKey = HashMap<MappedPredicateKey, IntArray?>(probes.size * 2)
            predicates.forEach { predicate ->
                val propertyIndex = callSiteStringPropertyIndex(predicate.property)
                val key = MappedPredicateKey(predicate.transform, predicate.mode, predicate.expected)
                val stringIds = if (stringIdsByKey.containsKey(key)) {
                    stringIdsByKey[key]
                } else {
                    resolveStringIds(key, checkNotNull(probes[key]), predicate).also { stringIdsByKey[key] = it }
                }
                val rows = stringIds?.let { directoryRows(propertyIndex, it, workConsumer) }
                    ?: scanDirectoryRows(propertyIndex, predicate, workConsumer)
                resolved[propertyIndex] = resolved[propertyIndex]?.let { existing -> unionRows(existing, rows) } ?: rows
            }
            rowsByProperty = resolved
            return resolved
        }

        private fun resolveStringIds(
            key: MappedPredicateKey,
            probe: PredicateProbe,
            predicate: StringPropertyPredicate
        ): IntArray? = when (probe) {
            is PredicateProbe.Absent -> EMPTY_INTS
            is PredicateProbe.Exact -> probe.stringIds
            is PredicateProbe.Trigram -> verifyAnchor(probe.anchor, predicate, workConsumer).also { verified ->
                matchCache?.put(key, verified)
            }
            is PredicateProbe.Scan -> null
        }
    }

    /** Decodes every string of the anchor span and keeps the ids matching [predicate], ascending. */
    private fun verifyAnchor(
        anchor: IntRange,
        predicate: StringPropertyPredicate,
        workConsumer: GraphWorkConsumer?
    ): IntArray {
        val actual = MutableString()
        val matched = IntArray(anchor.last - anchor.first + 1)
        var size = 0
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (postingIndex in anchor) {
                if ((postingIndex and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                accounting.consume()
                val stringId = trigramPostings.get(postingIndex).toInt()
                if (stringId !in 0 until stringCount) continue
                stringTable.get(stringId, actual)
                if (reusableMatches(actual, predicate)) matched[size++] = stringId
            }
        } finally {
            accounting.flush()
        }
        return matched.copyOf(size)
    }

    /** Directory rows of [propertyIndex] whose string ids are members of the sorted [stringIds]. */
    private fun directoryRows(propertyIndex: Int, stringIds: IntArray, workConsumer: GraphWorkConsumer?): IntArray {
        if (stringIds.isEmpty()) return EMPTY_INTS
        val directory = propertyStringIds[propertyIndex]
        val directorySize = directory.limit()
        if (directorySize == 0) return EMPTY_INTS
        val rows = IntArray(minOf(stringIds.size, directorySize))
        var size = 0
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            if (stringIds.size.toLong() * DIRECTORY_SCAN_RATIO >= directorySize) {
                // Dense candidate set: one linear merge over the directory is cheaper than a search per id.
                var candidateIndex = 0
                var row = 0
                while (candidateIndex < stringIds.size && row < directorySize) {
                    if (((candidateIndex + row) and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                    accounting.consume()
                    val candidate = stringIds[candidateIndex]
                    val current = directory.get(row)
                    when {
                        candidate < current -> candidateIndex++
                        candidate > current -> row++
                        else -> {
                            rows[size++] = row
                            candidateIndex++
                            row++
                        }
                    }
                }
            } else {
                var row = 0
                stringIds.forEach { stringId ->
                    accounting.consume()
                    val found = binarySearch(directory, stringId, row, directorySize - 1)
                    if (found >= 0) {
                        rows[size++] = found
                        row = found + 1
                    } else {
                        row = -found - 1
                    }
                }
            }
        } finally {
            accounting.flush()
        }
        return rows.copyOf(size)
    }

    /** Decodes every string used by [propertyIndex] and keeps the rows matching [predicate]. */
    private fun scanDirectoryRows(
        propertyIndex: Int,
        predicate: StringPropertyPredicate,
        workConsumer: GraphWorkConsumer?
    ): IntArray {
        val directory = propertyStringIds[propertyIndex]
        val rows = IntArrayList()
        val actual = MutableString()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (row in 0 until directory.limit()) {
                if ((row and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                accounting.consume()
                stringTable.get(directory.get(row), actual)
                if (reusableMatches(actual, predicate)) rows.add(row)
            }
        } finally {
            accounting.flush()
        }
        return rows.toIntArray()
    }

    // ------------------------------------------------------------------ ordered node access

    private fun postingSize(propertyIndex: Int, row: Int): Int {
        val ends = propertyPostingEnds[propertyIndex]
        val end = ends.get(row)
        val start = if (row == 0) 0 else ends.get(row - 1)
        return end - start
    }

    private fun postingStart(propertyIndex: Int, row: Int): Int =
        if (row == 0) 0 else propertyPostingEnds[propertyIndex].get(row - 1)

    /**
     * Matching node ids in canonical encounter order, at most [limit], or null when a selected
     * posting range fails its semantic order validation and the caller must use raw storage.
     */
    fun orderedMatchingNodeIds(plan: MatchPlan, limit: Int, workConsumer: GraphWorkConsumer?): IntArray? {
        if (limit <= 0 || plan.isEmpty) return EMPTY_INTS
        val cursors = ArrayList<MappedPostingCursor>()
        repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
            plan.rows(propertyIndex).forEach { row ->
                val start = postingStart(propertyIndex, row)
                val end = propertyPostingEnds[propertyIndex].get(row)
                if (start < end) {
                    cursors += validatedPostingCursor(propertyIndex, row, start until end, workConsumer) ?: return null
                }
            }
        }
        val result = IntArrayList(minOf(limit, MAX_INITIAL_RESULT_CAPACITY))
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            val heap = PostingCursorHeap(cursors)
            var previousNodeId = -1
            var visited = 0
            while (heap.isNotEmpty()) {
                if ((visited++ and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                val cursor = heap.peek()
                val nodeId = cursor.nodeId
                accounting.consume()
                if (nodeId != previousNodeId) {
                    result.add(nodeId)
                    previousNodeId = nodeId
                    if (result.size >= limit) break
                }
                if (cursor.advance()) heap.siftDownRoot() else heap.removeRoot()
            }
        } finally {
            accounting.flush()
        }
        return result.toIntArray()
    }

    private fun validatedPostingCursor(
        propertyIndex: Int,
        row: Int,
        range: IntRange,
        workConsumer: GraphWorkConsumer?
    ): MappedPostingCursor? {
        val postings = propertyPostingNodeIds[propertyIndex]
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

    /** Number of distinct nodes selected by [plan]; every selected posting is charged as work. */
    fun countMatchingNodes(plan: MatchPlan, nodeIdCapacity: Int, workConsumer: GraphWorkConsumer?): Long {
        if (plan.isEmpty) return 0L
        val wordCount = ((nodeIdCapacity.toLong() + BITSET_WORD_MASK) ushr BITSET_WORD_SHIFT).toInt()
        val matched = LongArray(wordCount)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                val postings = propertyPostingNodeIds[propertyIndex]
                plan.rows(propertyIndex).forEach { row ->
                    val end = propertyPostingEnds[propertyIndex].get(row)
                    for (position in postingStart(propertyIndex, row) until end) {
                        if ((position and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                        accounting.consume()
                        val nodeId = postings.get(position)
                        if (nodeId !in 0 until nodeIdCapacity) continue
                        matched[nodeId ushr BITSET_WORD_SHIFT] =
                            matched[nodeId ushr BITSET_WORD_SHIFT] or (1L shl (nodeId and BITSET_WORD_MASK))
                    }
                }
            }
        } finally {
            accounting.flush()
        }
        return matched.sumOf { word -> java.lang.Long.bitCount(word).toLong() }
    }

    // ------------------------------------------------------------------ projections

    /** Bounded duplicate-preserving projection in encounter order; null when a range is invalid. */
    fun projectRows(
        plan: MatchPlan,
        projectedPropertyIndexes: IntArray,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyProjectionRow>? {
        val nodeIds = orderedMatchingNodeIds(plan, limit, workConsumer) ?: return null
        if (nodeIds.isEmpty()) return emptyList()
        val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        val rows = ArrayList<StringPropertyProjectionRow>(nodeIds.size)
        nodeIds.forEach { nodeId ->
            rawStringIds.read(nodeId, stringIds)
            rows += StringPropertyProjectionRow(projectValues(stringIds, projectedPropertyIndexes))
        }
        return rows
    }

    /**
     * Bounded distinct projection in encounter order. A result shorter than [limit] proves the
     * complete match set was enumerated. Returns null when a range is invalid.
     */
    fun distinctRows(
        plan: MatchPlan,
        projectedPropertyIndexes: IntArray,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow>? {
        if (limit <= 0 || plan.isEmpty) return emptyList()
        val cursors = ArrayList<MappedPostingCursor>()
        repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
            plan.rows(propertyIndex).forEach { row ->
                val start = postingStart(propertyIndex, row)
                val end = propertyPostingEnds[propertyIndex].get(row)
                if (start < end) {
                    cursors += validatedPostingCursor(propertyIndex, row, start until end, workConsumer) ?: return null
                }
            }
        }
        val rows = ArrayList<StringPropertyDistinctRow>(minOf(limit, MAX_INITIAL_RESULT_CAPACITY))
        val seen = HashSet<IntTupleKey>()
        val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            val heap = PostingCursorHeap(cursors)
            var previousNodeId = -1
            var visited = 0
            while (heap.isNotEmpty()) {
                if ((visited++ and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                val cursor = heap.peek()
                val nodeId = cursor.nodeId
                accounting.consume()
                if (nodeId != previousNodeId) {
                    previousNodeId = nodeId
                    rawStringIds.read(nodeId, stringIds)
                    val key = IntTupleKey(IntArray(projectedPropertyIndexes.size) { index ->
                        projectedPropertyIndexes[index].let { propertyIndex ->
                            if (propertyIndex < 0) -1 else stringIds[propertyIndex]
                        }
                    })
                    if (seen.add(key)) {
                        rows += StringPropertyDistinctRow(cursor.order, projectValues(stringIds, projectedPropertyIndexes))
                        if (rows.size >= limit) break
                    }
                }
                if (cursor.advance()) heap.siftDownRoot() else heap.removeRoot()
            }
        } finally {
            accounting.flush()
        }
        return rows
    }

    /**
     * Returns the [selectedValues] tuples that occur in this graph's match set. Each tuple is
     * anchored on its smallest exact property posting so absent tuples reject on the first missing
     * dictionary string, and dictionary lookups run before any predicate evaluation because a
     * foreign tuple almost always fails its first lookup. A tuple whose projected values already
     * satisfy a predicate needs no per-node check; predicates on non-projected properties are
     * verified per candidate node.
     */
    fun selectedTupleHits(
        predicates: List<StringPropertyPredicate>,
        projectedPropertyIndexes: IntArray,
        selectedValues: Collection<List<String?>>,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        if (selectedValues.isEmpty()) return emptyList()
        val lookup = tupleLookup(predicates, projectedPropertyIndexes, workConsumer) ?: return emptyList()
        if (lookup.lookupOrder.isEmpty()) return lookup.nullTupleHits(selectedValues)
        val hits = ArrayList<StringPropertyDistinctRow>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var visited = 0
        try {
            if (selectedValues is StringPropertyTupleSet) {
                // Tuples sharing an absent leading value are rejected together by one lookup.
                for ((value, tuples) in selectedValues.groupedBy(lookup.lookupOrder[0])) {
                    checkViewInterrupted()
                    if (value == null || !lookup.leadingValuePresent(value, accounting)) continue
                    for (values in tuples) {
                        val order = lookup.hit(values, accounting)
                        if (order >= 0L) hits += StringPropertyDistinctRow(order, values)
                    }
                }
            } else {
                for (values in selectedValues) {
                    if ((visited++ and TUPLE_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                    val order = lookup.hit(values, accounting)
                    if (order >= 0L) hits += StringPropertyDistinctRow(order, values)
                }
            }
        } finally {
            accounting.flush()
        }
        return hits
    }

    /** Per-request state of [selectedTupleHits]: predicate split, lookup order, and dictionary caches. */
    private inner class TupleLookup(
        private val projectedPropertyIndexes: IntArray,
        val lookupOrder: IntArray,
        private val projectedPredicates: List<StringPropertyPredicate>,
        private val residualPlan: MatchPlan?,
        private val workConsumer: GraphWorkConsumer?
    ) {
        private val ids = IntArray(projectedPropertyIndexes.size)
        private val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
        private val rowCache = HashMap<String, Int>()
        private val decoded = MutableString()

        /**
         * Directory row of [value] in [propertyIndex], or -1 when no CallSite carries it there.
         * Every string used by a CallSite property has all of its lowercase trigrams indexed, so
         * a value with an absent trigram is rejected from a cache-resident bit set before any
         * string is decoded; the remaining values binary search the property's own sorted
         * directory, which is far smaller than the whole dictionary.
         */
        private fun directoryRow(propertyIndex: Int, value: String, accounting: BufferedGraphWorkConsumer): Int {
            val cacheKey = if (projectedPropertyIndexes.size == 1) value else "$propertyIndex\u0000$value"
            rowCache[cacheKey]?.let { return it }
            accounting.consume()
            val row = if (mayContain(value)) searchDirectory(propertyIndex, value, accounting) else -1
            rowCache[cacheKey] = row
            return row
        }

        private fun mayContain(value: String): Boolean {
            val directory = trigramDirectory ?: return true
            if (value.length < TRIGRAM_LENGTH) return true
            val lowercase = value.lowercase()
            for (position in lowercase.length - TRIGRAM_LENGTH downTo 0) {
                if (!directory.contains(mappedTrigramHash(lowercase, position))) return false
            }
            return true
        }

        private fun searchDirectory(propertyIndex: Int, value: String, accounting: BufferedGraphWorkConsumer): Int {
            val directory = propertyStringIds[propertyIndex]
            var low = 0
            var high = directory.limit() - 1
            while (low <= high) {
                accounting.consume()
                val middle = (low + high).ushr(1)
                stringTable.get(directory.get(middle), decoded)
                val comparison = decoded.toString().compareTo(value)
                when {
                    comparison < 0 -> low = middle + 1
                    comparison > 0 -> high = middle - 1
                    else -> return middle
                }
            }
            return -1
        }

        /** True when [value] is a dictionary string used by the leading lookup property. */
        fun leadingValuePresent(value: String, accounting: BufferedGraphWorkConsumer): Boolean =
            directoryRow(projectedPropertyIndexes[lookupOrder[0]], value, accounting) >= 0

        /** Encounter order of the first node carrying [values], or -1 when the graph has none. */
        @Suppress("ReturnCount")
        fun hit(values: List<String?>, accounting: BufferedGraphWorkConsumer): Long {
            if (values.size != projectedPropertyIndexes.size) return -1L
            for (index in projectedPropertyIndexes.indices) {
                if (projectedPropertyIndexes[index] < 0 && values[index] != null) return -1L
            }
            var anchorProperty = -1
            var anchorRow = -1
            var anchorSize = Int.MAX_VALUE
            for (index in lookupOrder) {
                val propertyIndex = projectedPropertyIndexes[index]
                val value = values[index] ?: return -1L
                val row = directoryRow(propertyIndex, value, accounting)
                if (row < 0) return -1L
                ids[index] = propertyStringIds[propertyIndex].get(row)
                val size = postingSize(propertyIndex, row)
                if (size < anchorSize) {
                    anchorSize = size
                    anchorProperty = propertyIndex
                    anchorRow = row
                }
            }
            val projectedMatch = projectedPredicates.any { predicate ->
                val valueIndex = projectedPropertyIndexes.indexOf(callSiteStringPropertyIndex(predicate.property))
                val value = values[valueIndex]
                value != null && stringMatches(value, predicate.transform, predicate.mode, predicate.expected)
            }
            if (!projectedMatch && residualPlan == null) return -1L
            return firstNodeOrder(anchorProperty, anchorRow, projectedMatch, accounting)
        }

        private fun firstNodeOrder(
            anchorProperty: Int,
            anchorRow: Int,
            projectedMatch: Boolean,
            accounting: BufferedGraphWorkConsumer
        ): Long {
            val end = propertyPostingEnds[anchorProperty].get(anchorRow)
            val postings = propertyPostingNodeIds[anchorProperty]
            for (position in postingStart(anchorProperty, anchorRow) until end) {
                if ((position and VIEW_INTERRUPTION_POLL_MASK) == 0) checkViewInterrupted()
                accounting.consume()
                val nodeId = postings.get(position)
                rawStringIds.read(nodeId, stringIds)
                if (!tupleEquals(stringIds)) continue
                if (!projectedMatch && !checkNotNull(residualPlan).matchesNode(stringIds)) continue
                return nodeOrder(nodeId)
            }
            return -1L
        }

        private fun tupleEquals(stringIds: IntArray): Boolean {
            for (index in projectedPropertyIndexes.indices) {
                val propertyIndex = projectedPropertyIndexes[index]
                if (propertyIndex >= 0 && stringIds[propertyIndex] != ids[index]) return false
            }
            return true
        }

        /**
         * A projection of only null-valued columns hits when any node matches, mirroring the
         * retained index: the tuple of nulls is reported once with the first matching node's order.
         */
        fun nullTupleHits(selectedValues: Collection<List<String?>>): List<StringPropertyDistinctRow> {
            val nullTuple = selectedValues.firstOrNull { values ->
                values.size == projectedPropertyIndexes.size && values.all { value -> value == null }
            } ?: return emptyList()
            val plan = residualPlan ?: return emptyList()
            val nodeIds = orderedMatchingNodeIds(plan, 1, workConsumer) ?: return emptyList()
            if (nodeIds.isEmpty()) return emptyList()
            return listOf(StringPropertyDistinctRow(nodeOrder(nodeIds[0]), nullTuple))
        }
    }

    private fun tupleLookup(
        predicates: List<StringPropertyPredicate>,
        projectedPropertyIndexes: IntArray,
        workConsumer: GraphWorkConsumer?
    ): TupleLookup? {
        // Caller classes are the most graph-specific tuple component, so an absent caller class
        // rejects a foreign tuple after a single dictionary lookup shared by all of its call sites.
        val lookupOrder = projectedPropertyIndexes.indices
            .filter { index -> projectedPropertyIndexes[index] >= 0 }
            .sortedBy { index -> TUPLE_LOOKUP_PREFERENCE.indexOf(projectedPropertyIndexes[index]) }
            .toIntArray()
        val projectedPredicates = ArrayList<StringPropertyPredicate>()
        val residualPredicates = ArrayList<StringPropertyPredicate>()
        predicates.forEach { predicate ->
            val propertyIndex = callSiteStringPropertyIndex(predicate.property)
            if (propertyIndex >= 0 && projectedPropertyIndexes.contains(propertyIndex)) {
                projectedPredicates += predicate
            } else {
                residualPredicates += predicate
            }
        }
        val residualPlan = if (residualPredicates.isEmpty()) {
            null
        } else {
            matchPlan(residualPredicates, workConsumer) ?: return null
        }
        return TupleLookup(projectedPropertyIndexes, lookupOrder, projectedPredicates, residualPlan, workConsumer)
    }

    private fun projectValues(stringIds: IntArray, projectedPropertyIndexes: IntArray): List<String?> =
        List(projectedPropertyIndexes.size) { index ->
            val propertyIndex = projectedPropertyIndexes[index]
            if (propertyIndex < 0) null else stringTable.get(stringIds[propertyIndex])
        }

    private fun trigramPostingRange(trigram: Int): IntRange? {
        trigramDirectory?.let { directory -> return directory.range(trigram) }
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
            rawStringIds: CallSiteRawStringIds,
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
                    val retainedBytes = header.long
                    require(retainedBytes > 0L)

                    var offset = CALL_SITE_STRING_INDEX_HEADER_BYTES.toLong()
                    val propertyStrings = Array(CALL_SITE_STRING_PROPERTY_COUNT) { EMPTY_INT_BUFFER }
                    val propertyEnds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { EMPTY_INT_BUFFER }
                    val propertyPostings = Array(CALL_SITE_STRING_PROPERTY_COUNT) { EMPTY_INT_BUFFER }
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
                    val signatures = mappedLongs(mapped, offset, stringCount)
                    offset += stringCount.toLong() * Long.SIZE_BYTES
                    val expectedBytes = offset + postingCount.toLong() * Long.SIZE_BYTES + Long.SIZE_BYTES
                    require(expectedBytes == fileBytes)
                    val postings = mappedLongs(mapped, offset, postingCount)
                    val expectedChecksum = mapped.getLong(Math.toIntExact(offset + postingCount.toLong() * Long.SIZE_BYTES))
                    val scratch = ViewLoadScratch.borrow()
                    val directoryTrigrams = scratch.directoryTrigrams
                    val directoryStarts = scratch.directoryStarts
                    require(
                        validatePersistentIndex(
                            PersistentIndexViewValidator(workConsumer, scratch),
                            directoryTrigrams,
                            directoryStarts,
                            propertyStrings,
                            propertyEnds,
                            propertyPostings,
                            signatures,
                            postings,
                            uniqueCounts,
                            stringCount,
                            callSiteCount,
                            nodeIdCapacity,
                            postingCount,
                            retainedBytes,
                            expectedContentIdentity,
                            expectedChecksum
                        )
                    )
                    val directory = TrigramDirectory.create(directoryTrigrams, directoryStarts, postingCount)
                    ViewLoadScratch.release(scratch)
                    MappedCallSiteStringIndexView(
                        propertyStrings,
                        propertyEnds,
                        propertyPostings,
                        postings,
                        callSiteCount,
                        stringCount,
                        stringTable,
                        nodeOrder,
                        rawStringIds,
                        directory
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
            validator: PersistentIndexViewValidator,
            directoryTrigrams: IntArrayList,
            directoryStarts: IntArrayList,
            propertyStrings: Array<IntBuffer>,
            propertyEnds: Array<IntBuffer>,
            propertyPostings: Array<IntBuffer>,
            signatures: LongBuffer,
            postings: LongBuffer,
            uniqueCounts: IntArray,
            stringCount: Int,
            callSiteCount: Int,
            nodeIdCapacity: Int,
            postingCount: Int,
            retainedBytes: Long,
            contentIdentity: ByteArray,
            expectedChecksum: Long
        ): Boolean {
            validator.updateInt(CALL_SITE_STRING_INDEX_MAGIC)
            validator.updateInt(CALL_SITE_STRING_INDEX_VERSION)
            validator.updateInt(stringCount)
            validator.updateInt(callSiteCount)
            validator.updateBytes(contentIdentity)
            uniqueCounts.forEach(validator::updateInt)
            validator.updateInt(postingCount)
            validator.updateLong(retainedBytes)

            repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                var previousStringId = -1
                if (!validator.updateInts(propertyStrings[propertyIndex]) { stringId ->
                        val valid = stringId in 0 until stringCount && stringId > previousStringId
                        previousStringId = stringId
                        valid
                    }
                ) return false
                var previousEnd = 0
                if (!validator.updateInts(propertyEnds[propertyIndex]) { end ->
                        val valid = end > previousEnd && end <= callSiteCount
                        previousEnd = end
                        valid
                    }
                ) return false
                if (previousEnd != callSiteCount) return false
                if (!validator.updateInts(propertyPostings[propertyIndex]) { nodeId ->
                        nodeId in 0 until nodeIdCapacity
                    }
                ) return false
            }
            if (!validator.updateLongs(signatures) { true }) return false
            var previousPosting = Long.MIN_VALUE
            var previousTrigram = 0
            var postingIndex = 0
            if (!validator.updateLongs(postings) { posting ->
                    val valid = posting >= previousPosting && posting.toInt() in 0 until stringCount
                    val trigram = (posting ushr Int.SIZE_BITS).toInt()
                    if (postingIndex == 0 || trigram != previousTrigram) {
                        directoryTrigrams.add(trigram)
                        directoryStarts.add(postingIndex)
                        previousTrigram = trigram
                    }
                    postingIndex++
                    previousPosting = posting
                    valid
                }
            ) return false
            return validator.value == expectedChecksum
        }

        private val EMPTY_INT_BUFFER: IntBuffer = IntBuffer.allocate(0).asReadOnlyBuffer()

        private fun mappedInts(mapped: ByteBuffer, offset: Long, count: Int): IntBuffer {
            if (count == 0) return EMPTY_INT_BUFFER
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

/**
 * Streams the persisted little-endian value checksum through primitive chunks. Values are pulled
 * from the mapped big-endian regions with one bulk copy, validated as a plain array loop, and
 * re-encoded through a little-endian view of the same scratch with a second bulk copy, so the
 * per-value cost stays at a few nanoseconds during a cold multi-graph request.
 */
private class PersistentIndexViewValidator(
    val workConsumer: GraphWorkConsumer?,
    loadScratch: ViewLoadScratch
) {
    val checksum = CRC32()
    val intChunk = loadScratch.intChunk
    val longChunk = loadScratch.longChunk
    val scratch = loadScratch.bytes
    val scratchInts: IntBuffer = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    val scratchLongs: LongBuffer = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()

    val value: Long
        get() = checksum.value

    fun updateInt(value: Int) {
        scratchInts.put(0, value)
        checksum.update(scratch, 0, Int.SIZE_BYTES)
        consumeGraphWork(workConsumer, 1L)
    }

    fun updateLong(value: Long) {
        scratchLongs.put(0, value)
        checksum.update(scratch, 0, Long.SIZE_BYTES)
        consumeGraphWork(workConsumer, 1L)
    }

    fun updateBytes(values: ByteArray) {
        checksum.update(values)
        consumeGraphWork(workConsumer, values.size.toLong())
    }

    inline fun updateInts(values: IntBuffer, validate: (Int) -> Boolean): Boolean {
        var index = 0
        val limit = values.limit()
        while (index < limit) {
            checkViewInterrupted()
            val count = minOf(CHECKSUM_CHUNK_VALUES, limit - index)
            values.get(index, intChunk, 0, count)
            for (chunkIndex in 0 until count) {
                if (!validate(intChunk[chunkIndex])) return false
            }
            scratchInts.clear()
            scratchInts.put(intChunk, 0, count)
            checksum.update(scratch, 0, count * Int.SIZE_BYTES)
            consumeGraphWork(workConsumer, count.toLong())
            index += count
        }
        return true
    }

    inline fun updateLongs(values: LongBuffer, validate: (Long) -> Boolean): Boolean {
        var index = 0
        val limit = values.limit()
        while (index < limit) {
            checkViewInterrupted()
            val count = minOf(CHECKSUM_CHUNK_VALUES, limit - index)
            values.get(index, longChunk, 0, count)
            for (chunkIndex in 0 until count) {
                if (!validate(longChunk[chunkIndex])) return false
            }
            scratchLongs.clear()
            scratchLongs.put(longChunk, 0, count)
            checksum.update(scratch, 0, count * Long.SIZE_BYTES)
            consumeGraphWork(workConsumer, count.toLong())
            index += count
        }
        return true
    }
}

/**
 * Transient buffers of one sidecar load: the checksum chunks and the growable directory lists.
 * A cold multi-graph request loads every sidecar back to back on one thread, so the buffers are
 * pooled softly instead of allocated per graph, which keeps the young generation quiet while
 * the following queries run.
 */
private class ViewLoadScratch {
    val intChunk = IntArray(CHECKSUM_CHUNK_VALUES)
    val longChunk = LongArray(CHECKSUM_CHUNK_VALUES)
    val bytes = ByteArray(CHECKSUM_CHUNK_VALUES * Long.SIZE_BYTES)
    val directoryTrigrams = IntArrayList(INITIAL_DIRECTORY_CAPACITY)
    val directoryStarts = IntArrayList(INITIAL_DIRECTORY_CAPACITY)

    companion object {
        private val pool = ArrayDeque<java.lang.ref.SoftReference<ViewLoadScratch>>()

        fun borrow(): ViewLoadScratch {
            synchronized(pool) {
                while (pool.isNotEmpty()) {
                    pool.removeLast().get()?.let { return it }
                }
            }
            return ViewLoadScratch()
        }

        fun release(scratch: ViewLoadScratch) {
            scratch.directoryTrigrams.clear()
            scratch.directoryStarts.clear()
            synchronized(pool) {
                if (pool.size < MAX_POOLED_LOAD_SCRATCH) pool.addLast(java.lang.ref.SoftReference(scratch))
            }
        }
    }
}

/**
 * Heap directory of the distinct trigrams in the persisted postings: ascending trigram hashes,
 * the posting index where each run begins, and a direct-address table for the ASCII hash range
 * so a probe resolves a trigram with one array read. It is collected for free by the single
 * validation pass and replaces two binary searches over the mapped postings per probed trigram,
 * so a cold multi-graph request never faults posting pages in only to learn that a term is
 * absent. The arrays are charged to the shared index budget; a graph that cannot reserve them
 * searches the mapped postings directly.
 */
private class TrigramDirectory private constructor(
    private val trigrams: IntArray,
    private val starts: IntArray,
    private val asciiBits: LongArray,
    private val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
) : Closeable {
    @Volatile
    private var closed = false


    fun range(trigram: Int): IntRange? {
        if (!contains(trigram)) return null
        val index = java.util.Arrays.binarySearch(trigrams, trigram)
        if (index < 0) return null
        return starts[index] until starts[index + 1]
    }

    /**
     * Presence test through a bit set over the ASCII hash range that stays cache-resident, so
     * rejecting a value whose trigrams are not all indexed touches almost no memory.
     */
    fun contains(trigram: Int): Boolean {
        if (trigram < 0 || trigram >= (asciiBits.size shl BITSET_WORD_SHIFT)) {
            return java.util.Arrays.binarySearch(trigrams, trigram) >= 0
        }
        return asciiBits[trigram ushr BITSET_WORD_SHIFT] and (1L shl (trigram and BITSET_WORD_MASK)) != 0L
    }

    override fun close() {
        if (closed) return
        closed = true
        reservation.close()
    }

    companion object {
        fun create(trigrams: IntArrayList, starts: IntArrayList, postingCount: Int): TrigramDirectory? {
            val wordCount = (ASCII_TRIGRAM_HASH_LIMIT + BITSET_WORD_MASK) ushr BITSET_WORD_SHIFT
            val bytes = (trigrams.size.toLong() * 2 + 1) * Int.SIZE_BYTES +
                wordCount.toLong() * Long.SIZE_BYTES + TRIGRAM_DIRECTORY_HEADER_BYTES
            val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(bytes) ?: return null
            val ends = IntArray(starts.size + 1)
            starts.getElements(0, ends, 0, starts.size)
            ends[starts.size] = postingCount
            val keys = trigrams.toIntArray()
            val bits = LongArray(wordCount)
            keys.forEach { trigram ->
                if (trigram in 0 until ASCII_TRIGRAM_HASH_LIMIT) {
                    bits[trigram ushr BITSET_WORD_SHIFT] = bits[trigram ushr BITSET_WORD_SHIFT] or (1L shl (trigram and BITSET_WORD_MASK))
                }
            }
            return TrigramDirectory(keys, ends, bits, reservation)
        }
    }
}

private fun StringPropertyPredicate.canUseTrigramPostings(): Boolean =
    expected.length >= TRIGRAM_LENGTH &&
        (transform == StringValueTransform.LOWERCASE || transform == null && expected.all { it.code <= ASCII_MAX })

/** Matches one decoded string; lowercase transforms reuse the buffer for ASCII values. */
internal fun reusableMatches(actual: MutableString, predicate: StringPropertyPredicate): Boolean {
    if (predicate.transform == StringValueTransform.LOWERCASE) {
        var index = 0
        while (index < actual.length) {
            if (actual[index].code > ASCII_MAX) {
                return stringMatches(actual.toString(), predicate.transform, predicate.mode, predicate.expected)
            }
            index++
        }
        actual.toLowerCase()
    }
    return when (predicate.mode) {
        StringMatchMode.EQUALS -> actual.equals(predicate.expected)
        StringMatchMode.STARTS_WITH -> actual.startsWith(predicate.expected)
        StringMatchMode.ENDS_WITH -> actual.endsWith(predicate.expected)
        StringMatchMode.CONTAINS -> actual.indexOf(predicate.expected) >= 0
    }
}

private fun mappedTrigramHash(value: String, position: Int): Int =
    (value[position].code * STRING_HASH_FACTOR + value[position + 1].code) * STRING_HASH_FACTOR +
        value[position + 2].code

/** Returns the index of [target] or `-(insertion point) - 1` like [java.util.Arrays.binarySearch]. */
private fun binarySearch(values: IntBuffer, target: Int, fromIndex: Int, toIndex: Int): Int {
    var low = fromIndex
    var high = toIndex
    while (low <= high) {
        val middle = (low + high).ushr(1)
        val value = values.get(middle)
        when {
            value < target -> low = middle + 1
            value > target -> high = middle - 1
            else -> return middle
        }
    }
    return -(low + 1)
}

/** Merges two ascending row arrays into one ascending array without duplicates. */
private fun unionRows(left: IntArray, right: IntArray): IntArray {
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left
    val merged = IntArray(left.size + right.size)
    var leftIndex = 0
    var rightIndex = 0
    var size = 0
    while (leftIndex < left.size && rightIndex < right.size) {
        val leftValue = left[leftIndex]
        val rightValue = right[rightIndex]
        when {
            leftValue < rightValue -> {
                merged[size++] = leftValue
                leftIndex++
            }
            leftValue > rightValue -> {
                merged[size++] = rightValue
                rightIndex++
            }
            else -> {
                merged[size++] = leftValue
                leftIndex++
                rightIndex++
            }
        }
    }
    while (leftIndex < left.size) merged[size++] = left[leftIndex++]
    while (rightIndex < right.size) merged[size++] = right[rightIndex++]
    return merged.copyOf(size)
}

private fun checkViewInterrupted() {
    if (Thread.currentThread().isInterrupted) {
        throw CancellationException("Mapped CallSite string index view interrupted")
    }
}

internal data class MappedPredicateKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

private class IntTupleKey(private val values: IntArray) {
    private val hash = values.contentHashCode()

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean = other is IntTupleKey && values.contentEquals(other.values)
}

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

/**
 * Small LRU of verified matching string ids per predicate, charged to the shared index budget.
 * Repeated broad terms on one graph skip the trigram candidate walk; entries larger than the
 * fixed reservation are served without being retained.
 */
private class BoundedMatchingStringIdCache private constructor(
    private val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
) : Closeable {
    private val entries = LinkedHashMap<MappedPredicateKey, IntArray>(MATCH_CACHE_ENTRIES + 1, 0.75f, true)
    private var usedBytes = 0L
    private var closed = false

    @Synchronized
    fun get(key: MappedPredicateKey): IntArray? = if (closed) null else entries[key]

    @Synchronized
    fun put(key: MappedPredicateKey, value: IntArray) {
        if (closed || entries.containsKey(key)) return
        val bytes = MATCH_CACHE_ENTRY_ESTIMATED_BYTES + value.size.toLong() * Int.SIZE_BYTES
        if (bytes > MATCH_CACHE_RETAINED_BYTES) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && (entries.size >= MATCH_CACHE_ENTRIES || usedBytes + bytes > MATCH_CACHE_RETAINED_BYTES)) {
            val eldest = iterator.next()
            usedBytes -= MATCH_CACHE_ENTRY_ESTIMATED_BYTES + eldest.value.size.toLong() * Int.SIZE_BYTES
            iterator.remove()
        }
        entries[key] = value
        usedBytes += bytes
    }


    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        entries.clear()
        usedBytes = 0L
        reservation.close()
    }

    companion object {
        fun create(): BoundedMatchingStringIdCache? {
            val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(MATCH_CACHE_RETAINED_BYTES)
                ?: return null
            return BoundedMatchingStringIdCache(reservation)
        }
    }
}

private data class PlanPredicateKey(val propertyIndex: Int, val predicate: MappedPredicateKey)

/** Access-ordered posting counts of recently planned predicate sets; a few dozen longs at most. */
private class PlanPostingCountCache {
    private val entries = LinkedHashMap<List<PlanPredicateKey>, Long>(PLAN_POSTING_COUNT_ENTRIES + 1, 0.75f, true)

    @Synchronized
    fun get(key: List<PlanPredicateKey>): Long? = entries[key]

    @Synchronized
    fun put(key: List<PlanPredicateKey>, postingCount: Long) {
        if (entries.containsKey(key)) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && entries.size >= PLAN_POSTING_COUNT_ENTRIES) {
            iterator.next()
            iterator.remove()
        }
        entries[key] = postingCount
    }

    @Synchronized
    fun clear() = entries.clear()
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

    fun advance(): Boolean {
        if (++position > lastPosition) return false
        nodeId = postings.get(position)
        order = orderAt(position)
        return true
    }

    private fun orderAt(index: Int): Long = validatedOrders?.get(index - firstPosition) ?: nodeOrder(nodeId)
}

/** Binary min-heap of cursors keyed by encounter order, then node id. */
private class PostingCursorHeap(cursors: List<MappedPostingCursor>) {
    private val heap = cursors.toTypedArray()
    private var size = heap.size

    init {
        for (index in size / 2 - 1 downTo 0) siftDown(index)
    }

    fun isNotEmpty(): Boolean = size > 0

    fun peek(): MappedPostingCursor = heap[0]

    fun siftDownRoot() = siftDown(0)

    fun removeRoot() {
        size--
        if (size > 0) {
            heap[0] = heap[size]
            siftDown(0)
        }
    }

    private fun less(left: MappedPostingCursor, right: MappedPostingCursor): Boolean =
        left.order < right.order || left.order == right.order && left.nodeId < right.nodeId

    private fun siftDown(start: Int) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size && less(heap[right], heap[left])) right else left
            if (!less(heap[child], heap[parent])) return
            val swapped = heap[parent]
            heap[parent] = heap[child]
            heap[child] = swapped
            parent = child
        }
    }
}

private val EMPTY_INTS = IntArray(0)

internal const val MAPPED_POSTING_RANGE_VALIDATION_CACHE_CAPACITY = 1 shl 10
internal const val MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES = 16L * 1024
internal const val MAPPED_MATCHING_STRING_ID_CACHE_RETAINED_BYTES = 64L * 1024
private const val VALIDATED_POSTING_RANGE_CACHE_CAPACITY = MAPPED_POSTING_RANGE_VALIDATION_CACHE_CAPACITY
private const val VALIDATED_POSTING_RANGE_CACHE_RETAINED_BYTES =
    MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES
private const val MATCH_CACHE_RETAINED_BYTES = MAPPED_MATCHING_STRING_ID_CACHE_RETAINED_BYTES
private const val MATCH_CACHE_ENTRIES = 8
private const val PLAN_POSTING_COUNT_ENTRIES = 32
private const val MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 128L
private const val VALIDATION_CACHE_HASH_SHIFT = 16
private const val VALIDATION_EMPTY: Byte = 0
private const val VALIDATION_VALID: Byte = 1
private const val VALIDATION_INVALID: Byte = 2
private const val TRIGRAM_LENGTH = 3
private const val STRING_HASH_FACTOR = 31
private const val ASCII_MAX = 0x7f
private const val UINT_MASK = 0xffff_ffffL
private const val VIEW_INTERRUPTION_POLL_MASK = 1_023
private const val TUPLE_INTERRUPTION_POLL_MASK = 63
private const val CHECKSUM_CHUNK_VALUES = 1 shl 13
private const val TRIGRAM_DIRECTORY_HEADER_BYTES = 64L

/** Every trigram of ASCII text hashes below this bound, so those hashes index a direct table. */
private const val ASCII_TRIGRAM_HASH_LIMIT = (ASCII_MAX * STRING_HASH_FACTOR + ASCII_MAX) * STRING_HASH_FACTOR + ASCII_MAX + 1
private const val INITIAL_DIRECTORY_CAPACITY = 1 shl 15
private const val MAX_POOLED_LOAD_SCRATCH = 4
private const val DIRECTORY_SCAN_RATIO = 8
private const val SMALL_TRIGRAM_SPAN = 32
private const val DENSE_TRIGRAM_SPAN = 1_024
private val TUPLE_LOOKUP_PREFERENCE = intArrayOf(
    CALLER_CLASS_PROPERTY_INDEX,
    CALLEE_CLASS_PROPERTY_INDEX,
    CALLER_NAME_PROPERTY_INDEX,
    CALLEE_NAME_PROPERTY_INDEX
)
private const val BITSET_WORD_SHIFT = 6
private const val BITSET_WORD_MASK = Long.SIZE_BITS - 1
private const val MAX_INITIAL_RESULT_CAPACITY = 1_024
private const val MIN_INDEX_VIEW_BYTES = CALL_SITE_STRING_INDEX_HEADER_BYTES + Long.SIZE_BYTES
