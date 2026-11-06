package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndex.PropertyCsr

/** Exact four-property tuple lookup used by cross-graph DISTINCT provenance rechecks. */
internal class ExactCallSiteProjectionTupleIndex(
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
    ): List<StringPropertyDistinctRow>? =
        if (propertyIndexes.size != CALL_SITE_STRING_PROPERTY_COUNT) null
        else {
            valueIndexByProperty(propertyIndexes)?.let { valueIndexes ->
                selectedHits(valueIndexes, selectedValues, limit, nodeOrder, workConsumer, matchesPredicates)
            }
        }

    private fun valueIndexByProperty(propertyIndexes: IntArray): IntArray? {
        val indexes = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { -1 }
        propertyIndexes.forEachIndexed { valueIndex, propertyIndex ->
            if (propertyIndex !in 0 until CALL_SITE_STRING_PROPERTY_COUNT || indexes[propertyIndex] >= 0) return null
            indexes[propertyIndex] = valueIndex
        }
        return indexes.takeUnless { it.any { valueIndex -> valueIndex < 0 } }
    }

    private fun selectedHits(
        valueIndexes: IntArray,
        selectedValues: Set<List<String?>>,
        limit: Int,
        nodeOrder: (Int) -> Long,
        workConsumer: GraphWorkConsumer?,
        matchesPredicates: (Int) -> Boolean
    ): List<StringPropertyDistinctRow> {
        val targetSize = minOf(limit, selectedValues.size)
        val hits = ArrayList<StringPropertyDistinctRow>(targetSize)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (values in selectedValues) {
                selectedHit(values, valueIndexes, accounting, nodeOrder, matchesPredicates)?.let(hits::add)
            }
        } finally {
            accounting.flush()
        }
        // Selection iteration is independent of encounter order: inspect every tuple before LIMIT.
        return hits.sortedBy(StringPropertyDistinctRow::encounterOrder).take(targetSize)
    }

    private fun selectedHit(
        values: List<String?>,
        valueIndexes: IntArray,
        accounting: BufferedGraphWorkConsumer,
        nodeOrder: (Int) -> Long,
        matchesPredicates: (Int) -> Boolean
    ): StringPropertyDistinctRow? =
        expectedStrings(values, valueIndexes)?.let { strings ->
            matchingNode(strings, accounting, matchesPredicates)?.let { nodeId -> StringPropertyDistinctRow(nodeOrder(nodeId), values) }
        }

    private fun expectedStrings(values: List<String?>, valueIndexes: IntArray): Array<String>? {
        if (values.size != valueIndexes.size) return null
        val expected = Array(CALL_SITE_STRING_PROPERTY_COUNT) { property -> values[valueIndexes[property]] }
        return if (expected.any { it == null }) null else Array(CALL_SITE_STRING_PROPERTY_COUNT) { checkNotNull(expected[it]) }
    }

    private fun matchingNode(strings: Array<String>, accounting: BufferedGraphWorkConsumer, matchesPredicates: (Int) -> Boolean): Int? {
        val hash =
            callSiteProjectionTupleHash(
                strings[CALLER_CLASS_PROPERTY_INDEX].hashCode(),
                strings[CALLER_NAME_PROPERTY_INDEX].hashCode(),
                strings[CALLEE_CLASS_PROPERTY_INDEX].hashCode(),
                strings[CALLEE_NAME_PROPERTY_INDEX].hashCode())
        var slot = callSiteProjectionTupleSlot(hash, nodeIds.size)
        while (nodeIds[slot] >= 0) {
            accounting.consume()
            val nodeId = nodeIds[slot]
            if (hashes[slot] == hash && projectionMatches(nodeId, strings) && matchesPredicates(nodeId)) return nodeId
            slot = (slot + 1) and (nodeIds.size - 1)
        }
        return null
    }

    private fun projectionMatches(nodeId: Int, expected: Array<String>): Boolean {
        for (propertyIndex in 0 until CALL_SITE_STRING_PROPERTY_COUNT) {
            if (stringTable.get(rawStringPropertyId(nodeId, propertyIndex)) != expected[propertyIndex]) return false
        }
        return true
    }

    companion object {
        fun tryBuild(
            nodes: PropertyCsr?,
            stringTable: StringTable,
            rawStringPropertyId: (Int, Int) -> Int,
            nodeOrder: (Int) -> Long,
            reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
            workConsumer: GraphWorkConsumer?
        ): ExactCallSiteProjectionTupleIndex? {
            val candidates = nodes?.takeIf { it.postingCount >= MIN_EXACT_CALL_SITE_PROJECTION_TUPLES } ?: return null
            return exactTupleBuildMemory(candidates.postingCount, stringTable.size(), reservation)?.let { memory ->
                buildReserved(candidates, stringTable, rawStringPropertyId, nodeOrder, reservation, workConsumer, memory)
            }
        }

        private fun buildReserved(
            nodes: PropertyCsr,
            stringTable: StringTable,
            rawStringPropertyId: (Int, Int) -> Int,
            nodeOrder: (Int) -> Long,
            reservation: MappedCallSiteStringIndexMemoryBudget.Reservation,
            workConsumer: GraphWorkConsumer?,
            memory: ExactTupleBuildMemory
        ): ExactCallSiteProjectionTupleIndex {
            var complete = false
            try {
                val built = ExactTupleBuilder(nodes, stringTable, rawStringPropertyId, nodeOrder, workConsumer, memory.capacity).build()
                reservation.shrinkTo(memory.initialBytes + memory.retainedBytes)
                complete = true
                return built
            } finally {
                // Failed/cancelled builds release both retained arrays and the temporary hash memo.
                if (!complete) reservation.shrinkTo(memory.initialBytes)
            }
        }
    }
}

private class ExactTupleBuilder(
    private val nodes: PropertyCsr,
    private val stringTable: StringTable,
    private val rawStringPropertyId: (Int, Int) -> Int,
    private val nodeOrder: (Int) -> Long,
    private val workConsumer: GraphWorkConsumer?,
    capacity: Int
) {
    private val hashes = LongArray(capacity)
    private val nodeIds = IntArray(capacity) { -1 }
    private val stringHashes = IntArray(stringTable.size())

    fun build(): ExactCallSiteProjectionTupleIndex {
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            var inspected = 0
            nodes.forEachNodeId { nodeId ->
                if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) checkCallSiteIndexInterrupted()
                accounting.consume()
                addNode(
                    nodeId,
                    rawStringPropertyId(nodeId, CALLER_CLASS_PROPERTY_INDEX),
                    rawStringPropertyId(nodeId, CALLER_NAME_PROPERTY_INDEX),
                    rawStringPropertyId(nodeId, CALLEE_CLASS_PROPERTY_INDEX),
                    rawStringPropertyId(nodeId, CALLEE_NAME_PROPERTY_INDEX))
            }
        } finally {
            accounting.flush()
        }
        return ExactCallSiteProjectionTupleIndex(hashes, nodeIds, stringTable, rawStringPropertyId)
    }

    private fun addNode(nodeId: Int, callerClass: Int, callerName: Int, calleeClass: Int, calleeName: Int) {
        val hash =
            callSiteProjectionTupleHash(stringHash(callerClass), stringHash(callerName), stringHash(calleeClass), stringHash(calleeName))
        var slot = callSiteProjectionTupleSlot(hash, nodeIds.size)
        while (nodeIds[slot] >= 0) {
            val existing = nodeIds[slot]
            if (hashes[slot] == hash && sameTuple(existing, callerClass, callerName, calleeClass, calleeName)) {
                if (nodeOrder(nodeId) < nodeOrder(existing)) nodeIds[slot] = nodeId
                return
            }
            slot = (slot + 1) and (nodeIds.size - 1)
        }
        hashes[slot] = hash
        nodeIds[slot] = nodeId
    }

    private fun sameTuple(nodeId: Int, callerClass: Int, callerName: Int, calleeClass: Int, calleeName: Int): Boolean {
        val callerMatches =
            rawStringPropertyId(nodeId, CALLER_CLASS_PROPERTY_INDEX) == callerClass &&
                rawStringPropertyId(nodeId, CALLER_NAME_PROPERTY_INDEX) == callerName
        return callerMatches &&
            rawStringPropertyId(nodeId, CALLEE_CLASS_PROPERTY_INDEX) == calleeClass &&
            rawStringPropertyId(nodeId, CALLEE_NAME_PROPERTY_INDEX) == calleeName
    }

    private fun stringHash(stringId: Int): Int {
        val cached = stringHashes[stringId]
        if (cached != 0) return cached
        return stringTable.get(stringId).hashCode().also { stringHashes[stringId] = it }
    }
}

private class ExactTupleBuildMemory(val capacity: Int, val initialBytes: Long, val retainedBytes: Long)

private fun exactTupleBuildMemory(
    nodeCount: Int,
    stringCount: Int,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
): ExactTupleBuildMemory? =
    exactCallSiteProjectionTupleCapacity(nodeCount)?.let { capacity ->
        exactCallSiteProjectionTupleRetainedBytes(capacity)?.let { retainedBytes ->
            exactCallSiteProjectionTupleStringHashBytes(stringCount)?.let { temporaryBytes ->
                reserveExactTupleBuild(capacity, retainedBytes, temporaryBytes, reservation)
            }
        }
    }

private fun reserveExactTupleBuild(
    capacity: Int,
    retainedBytes: Long,
    temporaryBytes: Long,
    reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
): ExactTupleBuildMemory? {
    val initialBytes = reservation.bytes
    val buildBytes = runCatching { Math.addExact(initialBytes, Math.addExact(retainedBytes, temporaryBytes)) }.getOrNull()
    return buildBytes?.takeIf(reservation::tryGrowTo)?.let { ExactTupleBuildMemory(capacity, initialBytes, retainedBytes) }
}

internal fun exactCallSiteProjectionTupleCapacity(nodeCount: Int): Int? {
    val required = nodeCount.toLong() * 2L
    var capacity = 1
    while (capacity.toLong() < required) {
        if (capacity > Int.MAX_VALUE / 2) return null
        capacity = capacity shl 1
    }
    return capacity
}

internal fun exactCallSiteProjectionTupleRetainedBytes(capacity: Int): Long? =
    runCatching {
            Math.addExact(
                EXACT_CALL_SITE_PROJECTION_TUPLE_INDEX_ESTIMATED_BYTES + 2L * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                Math.multiplyExact(capacity.toLong(), Long.SIZE_BYTES.toLong() + Int.SIZE_BYTES.toLong()))
        }
        .getOrNull()

internal fun exactCallSiteProjectionTupleStringHashBytes(stringCount: Int): Long? =
    runCatching { Math.addExact(PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES, Math.multiplyExact(stringCount.toLong(), Int.SIZE_BYTES.toLong())) }
        .getOrNull()

internal fun callSiteProjectionTupleHash(callerClass: Int, callerName: Int, calleeClass: Int, calleeName: Int): Long {
    var hash = CALL_SITE_PROJECTION_TUPLE_HASH_SEED
    hash = (hash xor callerClass.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
    hash = (hash xor callerName.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
    hash = (hash xor calleeClass.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
    return (hash xor calleeName.toLong()) * CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR
}

internal fun callSiteProjectionTupleSlot(hash: Long, capacity: Int): Int {
    var mixed = hash
    mixed = (mixed xor (mixed ushr CALL_SITE_TUPLE_MIX_SHIFT)) * CALL_SITE_TUPLE_MIX_FIRST_FACTOR
    mixed = (mixed xor (mixed ushr CALL_SITE_TUPLE_MIX_SHIFT)) * CALL_SITE_TUPLE_MIX_SECOND_FACTOR
    return (mixed xor (mixed ushr CALL_SITE_TUPLE_MIX_SHIFT)).toInt() and (capacity - 1)
}
