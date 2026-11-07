package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.util.Arrays

internal class CallSiteDistinctProjection(private val context: MappedCallSiteStringIndex) {
    private var exactProjectionTupleIndex
        get() = context.exactProjectionTupleIndex
        set(value) { context.exactProjectionTupleIndex = value }
    private val trigramSignatures get() = context.trigramSignatures
    private val properties get() = context.properties
    private val stringTable get() = context.stringTable
    private val nodeOrder get() = context.nodeOrder
    private val nodeIdCapacity get() = context.nodeIdCapacity
    private val rawStringPropertyId get() = context.rawStringPropertyId
    private val matches get() = context.matches

    fun distinctProjection(
        predicates: List<StringPropertyPredicate>,
        projectedProperties: List<String>,
        limit: Int,
        selectedValues: Set<List<String?>>?,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        if (limit <= 0) return emptyList()
        val propertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex).toIntArray()
        val tupleHits = selectedValues?.let {
            selectedProjectionHitsByTuple(predicates, propertyIndexes, it, limit, workConsumer)
        }
        return tupleHits ?: scanProjection(predicates, propertyIndexes, selectedValues, limit, workConsumer)
    }

    private fun scanProjection(
        predicates: List<StringPropertyPredicate>,
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>?,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        val ranges = matches.matchingRanges(predicates, workConsumer)
        return when {
            ranges.isEmpty() -> emptyList()
            selectedValues == null -> distinctProjectionPrefix(ranges, propertyIndexes, limit, workConsumer)
            else -> selectedProjectionHits(ranges, propertyIndexes, selectedValues, limit, workConsumer)
        }
    }

    /**
     * Provenance rechecks already know the complete projected tuple. Anchor each tuple on its
     * smallest exact property posting instead of materializing every predicate hit into a graph-wide
     * bit set and then discarding almost all of it.
     */
    fun selectedProjectionHitsByTuple(
        predicates: List<StringPropertyPredicate>,
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow>? {
        if (propertyIndexes.all { it < 0 }) return null
        val predicatePlan = SelectedPredicates(predicates, workConsumer)
        val tupleIndex = exactProjectionTupleIndex ?: selectedValues
            .takeIf { it.size >= MIN_SELECTED_VALUES_FOR_EXACT_PROJECTION_INDEX }
            ?.let { context.exactProjectionTupleIndex(workConsumer) }
        val tupleHits = tupleIndex?.selectedProjectionHits(
            propertyIndexes, selectedValues, limit, nodeOrder, workConsumer, predicatePlan::matches
        )
        return tupleHits ?: scanSelectedTuples(propertyIndexes, selectedValues, limit, workConsumer, predicatePlan)
    }

    private inner class SelectedPredicates(predicates: List<StringPropertyPredicate>, workConsumer: GraphWorkConsumer?) {
        init {
            if (predicates.any(StringPropertyPredicate::requiresTrigramSignature)) context.ensureTrigramMetadata(workConsumer)
        }

        private val propertyIndexes = predicates.map { callSiteStringPropertyIndex(it.property) }
        private val sharedStates = mutableMapOf<CallSitePredicateKey, ByteArray?>()
        private val runtimes = predicates.map { CallSitePredicateRuntime(it, sharedStates, stringTable, trigramSignatures) }

        fun matches(nodeId: Int): Boolean = propertyIndexes.indices.any { predicateIndex ->
            propertyIndexes[predicateIndex].takeIf { it >= 0 }?.let { propertyIndex ->
                runtimes[predicateIndex].matches(rawStringPropertyId(nodeId, propertyIndex))
            } == true
        }
    }

    private fun scanSelectedTuples(
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        limit: Int,
        workConsumer: GraphWorkConsumer?,
        predicates: SelectedPredicates
    ): List<StringPropertyDistinctRow> {
        val targetSize = minOf(limit, selectedValues.size)
        val hits = ArrayList<StringPropertyDistinctRow>(targetSize)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val lookupOrder = propertyIndexes.indices.sortedByDescending { valueIndex ->
            propertyIndexes[valueIndex].takeIf { it >= 0 }?.let { properties[it].uniqueStringCount } ?: Int.MIN_VALUE
        }.toIntArray()
        try {
            for (values in selectedValues) {
                val ids = selectedIds(values, propertyIndexes, lookupOrder)
                if (ids != null) firstTupleHit(propertyIndexes, ids, values, predicates, accounting)?.let(hits::add)
            }
        } finally { accounting.flush() }
        // Later selected values can precede earlier ones in encounter order; consume every tuple before taking the prefix.
        return hits.sortedBy(StringPropertyDistinctRow::encounterOrder).take(targetSize)
    }

    private fun selectedIds(values: List<String?>, propertyIndexes: IntArray, lookupOrder: IntArray? = null): IntArray? {
        return if (values.size != propertyIndexes.size) null else {
            val ids = IntArray(values.size)
            for (position in propertyIndexes.indices) {
                val index = lookupOrder?.get(position) ?: position
                ids[index] = selectedValueId(propertyIndexes[index], values[index]) ?: return null
            }
            ids
        }
    }

    private fun selectedValueId(propertyIndex: Int, value: String?): Int? {
        val id = value?.let(stringTable::findId) ?: -1
        return when {
            propertyIndex < 0 && value == null -> -1
            propertyIndex >= 0 && id >= 0 -> id
            else -> null
        }
    }

    private fun firstTupleHit(
        propertyIndexes: IntArray,
        ids: IntArray,
        values: List<String?>,
        predicates: SelectedPredicates,
        accounting: BufferedGraphWorkConsumer
    ): StringPropertyDistinctRow? {
        val anchor = propertyIndexes.indices.asSequence().filter { propertyIndexes[it] >= 0 }.mapNotNull { index ->
            properties[propertyIndexes[index]].postingRange(ids[index], accounting)?.let { propertyIndexes[index] to it }
        }.minByOrNull { (_, range) -> range.last - range.first }
        return anchor?.let { (propertyIndex, range) ->
            for (position in range) {
                accounting.consume()
                val nodeId = properties[propertyIndex].postingNodeId(position)
                if (projectionEquals(nodeId, propertyIndexes, ids) && predicates.matches(nodeId)) {
                    return StringPropertyDistinctRow(nodeOrder(nodeId), values)
                }
            }
            null
        }
    }

    fun distinctProjectionPrefix(
        ranges: CallSitePostingRanges,
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
            if (bucket.none { ids -> projectionEquals(nodeId, propertyIndexes, ids) }) {
                val ids = projectionIds(nodeId, propertyIndexes)
                bucket += ids
                rows += StringPropertyDistinctRow(nodeOrder(nodeId), projectionValues(ids))
                if (rows.size >= limit) break
            }
        }
        consumeGraphWork(workConsumer, inspected)
        return rows
    }

    fun selectedProjectionHits(
        ranges: CallSitePostingRanges,
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
        val selectedByHash = mutableMapOf<Int, MutableList<Pair<IntArray, List<String?>>>>()
        selectedValues.forEach { values ->
            val ids = selectedIds(values, propertyIndexes) ?: return@forEach
            selectedByHash.getOrPut(java.util.Arrays.hashCode(ids), ::mutableListOf) += ids to values
        }
        return if (selectedByHash.isEmpty()) emptyList() else {
            collectSelectedHits(ranges, propertyIndexes, selectedValues, selectedByHash, limit, workConsumer)
        }
    }

    private fun collectSelectedHits(
        ranges: CallSitePostingRanges,
        propertyIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        selectedByHash: Map<Int, List<Pair<IntArray, List<String?>>>>,
        limit: Int,
        workConsumer: GraphWorkConsumer?
    ): List<StringPropertyDistinctRow> {
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

    fun projectionHash(nodeId: Int, propertyIndexes: IntArray): Int {
        var hash = 1
        propertyIndexes.forEach { propertyIndex ->
            hash = CALL_SITE_STRING_HASH_FACTOR * hash + if (propertyIndex < 0) -1 else rawStringPropertyId(nodeId, propertyIndex)
        }
        return hash
    }

    fun projectionEquals(nodeId: Int, propertyIndexes: IntArray, expected: IntArray): Boolean =
        propertyIndexes.indices.all { index ->
            val propertyIndex = propertyIndexes[index]
            expected[index] == if (propertyIndex < 0) -1 else rawStringPropertyId(nodeId, propertyIndex)
        }

    fun projectionIds(nodeId: Int, propertyIndexes: IntArray): IntArray =
        IntArray(propertyIndexes.size) { index ->
            propertyIndexes[index].takeIf { it >= 0 }?.let { rawStringPropertyId(nodeId, it) } ?: -1
        }

    fun projectionValues(ids: IntArray): List<String?> = ids.map { id ->
        id.takeIf { it >= 0 }?.let(stringTable::get)
    }
}
