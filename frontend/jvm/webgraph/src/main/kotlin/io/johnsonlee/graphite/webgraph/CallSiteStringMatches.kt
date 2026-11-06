package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.util.Arrays
import java.util.concurrent.Callable

internal class CallSiteStringMatches(private val context: MappedCallSiteStringIndex) {
    private val trigramSignatures get() = context.trigramSignatures
    private val matchingStringIds get() = context.matchingStringIds
    private val properties get() = context.properties
    private val stringTable get() = context.stringTable

    fun matchingRanges(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): CallSitePostingRanges {
        if (workConsumer is SplitGraphWorkBatchConsumer &&
            workConsumer.segmentWorkerCount > 0 && predicates.size > 1
        ) {
            return matchingRangesInParallel(predicates, workConsumer)
        }
        val ranges = CallSitePostingRanges(properties)
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
                context.ensureTrigramMetadata(workConsumer)
            }
            val runtime = CallSitePredicateRuntime(predicate, sharedStates, stringTable, trigramSignatures)
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

    fun matchingRangesInParallel(
        predicates: List<StringPropertyPredicate>,
        workConsumer: SplitGraphWorkBatchConsumer
    ): CallSitePostingRanges {
        if (predicates.any(StringPropertyPredicate::requiresTrigramSignature)) {
            context.ensureTrigramMetadata(workConsumer)
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
                val local = CallSitePostingRanges(properties)
                val states = mutableMapOf<CallSitePredicateKey, ByteArray?>()
                for (predicateIndex in start until end) {
                    val predicate = predicates[predicateIndex]
                    val propertyIndex = callSiteStringPropertyIndex(predicate.property)
                    if (propertyIndex < 0) continue
                    val matchedStringIds = matches[predicateIndex]
                    properties[propertyIndex].collectMatchingRanges(
                        propertyIndex,
                        CallSitePredicateRuntime(predicate, states, stringTable, trigramSignatures),
                        matchedStringIds,
                        candidatesAreKnownMatches = matchedStringIds != null,
                        local,
                        workConsumer
                    )
                }
                local
            }
        }
        return CallSitePostingRanges(properties).also { combined ->
            executeSplitCallSiteTasks(tasks, workConsumer.segmentWorkerCount).forEach(combined::addAll)
        }
    }

    fun matchingStringIds(
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
        val cached = synchronized(context) { matchingStringIds[key]?.stringIds }
        return cached ?: uncachedStringIds(predicate, key, workConsumer)
    }

    private fun uncachedStringIds(
        predicate: StringPropertyPredicate,
        key: CallSitePredicateKey,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        val candidates = candidateStringIds(predicate, workConsumer) ?: return null
        val runtime = CallSitePredicateRuntime(predicate, mutableMapOf(), stringTable, trigramSignatures)
        val result = if (workConsumer is SplitGraphWorkBatchConsumer &&
            candidates.size >= MIN_PARALLEL_CALL_SITE_MATCH_CANDIDATES
        ) {
            matchingCandidateStringIdsInParallel(candidates, runtime, workConsumer)
        } else {
            matchingCandidateStringIds(candidates, runtime, workConsumer)
        }
        context.cacheMatchingStringIds(key, result)
        return result
    }

    fun exactMatchingStringIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): List<IntArray>? {
        if (predicates.any { predicate ->
                !predicate.canUseLowercaseTrigramPostings() ||
                    predicate.mode == StringMatchMode.EQUALS ||
                    predicate.expected.length < MIN_CALL_SITE_TRIGRAM_LENGTH
            }
        ) return null
        return collectExactMatches(predicates, workConsumer)
    }

    private fun collectExactMatches(predicates: List<StringPropertyPredicate>, workConsumer: GraphWorkConsumer?): List<IntArray>? {
        val matches = ArrayList<IntArray>(predicates.size)
        for (predicate in predicates) {
            val stringIds = matchingStringIds(predicate, workConsumer) ?: return null
            matches += stringIds
        }
        return matches
    }

    fun containsPropertyStringId(
        propertyIndex: Int,
        stringId: Int,
        workConsumer: GraphWorkConsumer?
    ): Boolean = properties.getOrNull(propertyIndex)?.containsStringId(stringId, workConsumer) == true

    fun matchingCandidateStringIds(
        candidates: IntArray,
        runtime: CallSitePredicateRuntime,
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

    fun matchingCandidateStringIdsInParallel(
        candidates: IntArray,
        runtime: CallSitePredicateRuntime,
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

    fun candidateStringIds(predicate: StringPropertyPredicate, workConsumer: GraphWorkConsumer?): IntArray? {
        if (!supportsTrigramCandidates(predicate)) return null
        return context.callSiteTrigramPostings(workConsumer)?.let { postings ->
            collectCandidateStringIds(predicate, postings, workConsumer)
        }
    }

    private fun supportsTrigramCandidates(predicate: StringPropertyPredicate): Boolean =
        predicate.canUseLowercaseTrigramPostings() && predicate.mode != StringMatchMode.EQUALS &&
            predicate.expected.length >= MIN_CALL_SITE_TRIGRAM_LENGTH

    private fun collectCandidateStringIds(
        predicate: StringPropertyPredicate,
        postings: LongArray,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        return try {
            val ranges = trigramRanges(predicate, postings, accounting)
            when {
                ranges == null -> IntArray(0)
                ranges.isEmpty() -> null
                else -> intersectCandidateRanges(postings, ranges, workConsumer, accounting)
            }
        } finally { accounting.flush() }
    }

    private fun trigramRanges(
        predicate: StringPropertyPredicate,
        postings: LongArray,
        accounting: BufferedGraphWorkConsumer
    ): MutableList<CallSiteTrigramPostingRange>? {
        // The runtime verifies original casing after this safe lowercase candidate filter.
        val expected = predicate.expected.lowercase()
        val seen = IntOpenHashSet()
        val ranges = mutableListOf<CallSiteTrigramPostingRange>()
        val positions = when (predicate.mode) {
            StringMatchMode.STARTS_WITH -> 0..0
            StringMatchMode.ENDS_WITH -> (expected.length - MIN_CALL_SITE_TRIGRAM_LENGTH).let { it..it }
            else -> 0..expected.length - MIN_CALL_SITE_TRIGRAM_LENGTH
        }
        for (position in positions) {
            val trigram = callSiteTrigramHash(expected, position)
            if (!seen.add(trigram)) continue
            accounting.consume()
            val start = lowerBound(postings, trigramKey(trigram, 0))
            accounting.consume()
            val end = upperBound(postings, trigramKey(trigram, -1))
            if (start == end) return null
            ranges += CallSiteTrigramPostingRange(trigram, start, end)
        }
        return ranges
    }

    private fun intersectCandidateRanges(
        postings: LongArray,
        ranges: MutableList<CallSiteTrigramPostingRange>,
        workConsumer: GraphWorkConsumer?,
        accounting: BufferedGraphWorkConsumer
    ): IntArray {
        ranges.sortBy(CallSiteTrigramPostingRange::size)
        val shortest = ranges.first()
        val candidates = IntArray(shortest.size) { offset -> postings[shortest.start + offset].toInt() }
        return if (workConsumer is SplitGraphWorkBatchConsumer && ranges.size > 1 &&
            candidates.size >= MIN_PARALLEL_CALL_SITE_MATCH_CANDIDATES
        ) {
            intersectCandidateStringIdsInParallel(postings, ranges, candidates, workConsumer)
        } else intersectCandidatesSequentially(postings, ranges, candidates, accounting)
    }

    private fun intersectCandidatesSequentially(
        postings: LongArray,
        ranges: List<CallSiteTrigramPostingRange>,
        candidates: IntArray,
        accounting: BufferedGraphWorkConsumer
    ): IntArray {
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
    }

    fun intersectCandidateStringIdsInParallel(
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
}
