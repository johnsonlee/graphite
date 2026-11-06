package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import java.util.concurrent.CancellationException

internal fun MappedGraphContext.aggregateStringPropertyDisjunction(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>,
    distinctProperty: String?
): StringPropertyDisjunctionAggregate? = aggregateStringPropertyDisjunctionInternal(type, predicates, distinctProperty, workConsumer = null)

internal fun MappedGraphContext.aggregateStringPropertyDisjunction(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>,
    distinctProperty: String?,
    workConsumer: GraphWorkConsumer
): StringPropertyDisjunctionAggregate? =
    aggregateStringPropertyDisjunctionInternal(type, predicates, distinctProperty, workConsumer as GraphWorkConsumer?)

internal fun MappedGraphContext.aggregateStringPropertyDisjunctionInternal(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>,
    distinctProperty: String?,
    workConsumer: GraphWorkConsumer?
): StringPropertyDisjunctionAggregate? {
    if (!supportsCallSitePredicates(type, predicates) || distinctProperty?.let { !supportsRawStringProperty(type, it) } == true) return null
    return if (shouldPreflightCallSitePredicates(predicates) && callSitePredicatesCannotMatch(predicates, workConsumer)) {
        StringPropertyDisjunctionAggregate(count = 0, distinctValues = if (distinctProperty == null) null else emptySet())
    } else callSiteStringIndex(type, workConsumer)?.aggregate(predicates, distinctProperty, workConsumer)
}

internal fun MappedGraphContext.distinctStringPropertyDisjunction(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>,
    projectedProperties: List<String>,
    limit: Int,
    selectedValues: Set<List<String?>>?,
    workConsumer: GraphWorkConsumer?
): List<StringPropertyDistinctRow>? {
    if (!supportsCallSitePredicates(type, predicates) || projectedProperties.any { !supportsDistinctProjection(type, it) }) return null
    return DistinctProjectionRequest(this, type, predicates, projectedProperties, limit, selectedValues, workConsumer).lookup()
}

private fun supportsDistinctProjection(type: Class<out Node>, property: String): Boolean =
    property == GRAPH_ID_PROJECTION_PROPERTY || property in CALL_SITE_NULL_STRING_PROPERTIES || supportsRawStringProperty(type, property)

internal fun supportsCallSitePredicates(type: Class<out Node>, predicates: List<StringPropertyPredicate>): Boolean =
    type == CallSiteNode::class.java && predicates.isNotEmpty() && predicates.all { supportsRawStringProperty(type, it.property) }

private class DistinctProjectionRequest(
    private val context: MappedGraphContext,
    private val type: Class<out Node>,
    private val predicates: List<StringPropertyPredicate>,
    private val properties: List<String>,
    private val limit: Int,
    private val selectedValues: Set<List<String?>>?,
    private val workConsumer: GraphWorkConsumer?
) {
    fun lookup(): List<StringPropertyDistinctRow>? {
        context.callSiteStringLookupEntryCount.incrementAndGet()
        context.retainPersistedCallSiteStringIndexForSplit(workConsumer)
        val index = retainedIndex()
        val view = if (index == null) context.mappedCallSiteStringIndexView(workConsumer) else null
        val exact =
            if (workConsumer is SplitGraphWorkBatchConsumer) {
                index?.exactMatchingStringIds(predicates, workConsumer) ?: view?.exactMatchingStringIds(predicates, workConsumer)
            } else null
        if (exact?.all(IntArray::isEmpty) == true) return emptyList()
        val parallel =
            if (exact != null && workConsumer is SplitGraphWorkBatchConsumer) {
                context.parallelRawDistinctCallSiteStringProjection(
                    predicates, properties, limit, selectedValues, workConsumer, exact, index ?: view)
            } else null
        return parallel
            ?: if (index != null) {
                context.callSiteStringProjectionLookupCount.incrementAndGet()
                project(index)
            } else fallback()
    }

    private fun retainedIndex(): MappedCallSiteStringIndex? =
        context.callSiteStringIndex
            ?: if (workConsumer is SerialGraphWorkBatchConsumer ||
                workConsumer is SplitGraphWorkBatchConsumer && workConsumer !is PreferredMappedStringIndexViewGraphWorkBatchConsumer)
                context.loadPersistedCallSiteStringIndexIfAvailable(type, checkNotNull(workConsumer))
            else null

    private fun project(index: MappedCallSiteStringIndex): List<StringPropertyDistinctRow>? =
        index.distinctProjection(predicates, properties, limit, selectedValues, workConsumer)

    private fun fallback(): List<StringPropertyDistinctRow>? {
        if (context.serialCallSitePredicatesCannotMatch(predicates, workConsumer) ||
            context.nonSerialCallSitePredicatesCannotMatch(predicates, workConsumer))
            return emptyList()
        return when (workConsumer) {
            is SerialGraphWorkBatchConsumer ->
                context.rawDistinctCallSiteStringProjection(predicates, properties, limit, selectedValues, workConsumer)
            is SplitGraphWorkBatchConsumer ->
                context.parallelRawDistinctCallSiteStringProjection(predicates, properties, limit, selectedValues, workConsumer)
                    ?: context.callSiteStringIndex(type, workConsumer)?.let(::project)
            else -> context.callSiteStringIndex(type, workConsumer)?.let(::project)
        }
    }
}

internal fun MappedGraphContext.serialCallSitePredicatesCannotMatch(
    predicates: List<StringPropertyPredicate>,
    workConsumer: GraphWorkConsumer?
): Boolean =
    workConsumer is SerialGraphWorkBatchConsumer &&
        shouldPreflightCallSitePredicates(predicates) &&
        callSitePredicatesCannotMatch(predicates, workConsumer)

internal fun MappedGraphContext.nonSerialCallSitePredicatesCannotMatch(
    predicates: List<StringPropertyPredicate>,
    workConsumer: GraphWorkConsumer?
): Boolean =
    workConsumer !is SerialGraphWorkBatchConsumer &&
        shouldPreflightCallSitePredicates(predicates) &&
        callSitePredicatesCannotMatch(predicates, workConsumer)

internal fun MappedGraphContext.rawDistinctCallSiteStringProjection(
    predicates: List<StringPropertyPredicate>,
    projectedProperties: List<String>,
    limit: Int,
    selectedValues: Set<List<String?>>?,
    workConsumer: GraphWorkConsumer
): List<StringPropertyDistinctRow> {
    if (limit <= 0 || selectedValues?.isEmpty() == true) return emptyList()
    val matcher = CallSitePredicateMatcher(this, predicates, null)
    val projectedPropertyIndexes = projectedProperties.map(::callSiteStringPropertyIndex)
    val rows = mutableListOf<StringPropertyDistinctRow>()
    val seenValues = HashSet<List<String?>>()
    val targetSize = minOf(limit, selectedValues?.size ?: limit)
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
    var inspected = 0
    try {
        for (nodeId in nodeTypeIndex.ids(CallSiteNode::class.java)) {
            if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) {
                checkThreadInterrupted { CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED) }
            }
            accounting.consume()
            var matched = false
            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
                stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
                stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
                stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
                matched = matcher.matches(stringIds)
            }
            if (matched) {
                addDistinctCallSiteRow(nodeId, stringIds, projectedPropertyIndexes, selectedValues, seenValues, rows)
            }
            if (rows.size >= targetSize) break
        }
        return rows
    } finally {
        accounting.flush()
    }
}

private fun MappedGraphContext.addDistinctCallSiteRow(
    nodeId: Int,
    stringIds: IntArray,
    properties: List<Int>,
    selectedValues: Set<List<String?>>?,
    seenValues: MutableSet<List<String?>>,
    rows: MutableList<StringPropertyDistinctRow>
) {
    val values = properties.map { if (it < 0) null else stringTable.get(stringIds[it]).toString() }
    if ((selectedValues == null || values in selectedValues) && seenValues.add(values)) {
        rows += StringPropertyDistinctRow(nodeOffsets.offset(nodeId), values)
    }
}

internal fun MappedGraphContext.forEachStringPropertyProjection(
    type: Class<out Node>,
    projectedProperties: List<String>,
    checkCancelled: () -> Unit,
    consumer: (StringPropertyProjectionRow) -> Unit
): Boolean = forEachStringPropertyProjection(type, projectedProperties, null, checkCancelled, consumer)

internal fun MappedGraphContext.forEachStringPropertyProjection(
    type: Class<out Node>,
    projectedProperties: List<String>,
    preflightWorkConsumer: GraphWorkConsumer?,
    checkCancelled: () -> Unit,
    consumer: (StringPropertyProjectionRow) -> Unit
): Boolean {
    val unsupported =
        type != CallSiteNode::class.java ||
            projectedProperties.isEmpty() ||
            projectedProperties.any { !supportsRawStringProperty(type, it) }
    if (unsupported || !canStreamCallSiteProjection(preflightWorkConsumer)) return false
    val propertyIndexes = projectedProperties.map(::requiredCallSiteStringPropertyIndex)
    fun checkProjectionCancellation() {
        checkCancelled()
        checkThreadInterrupted { CancellationException("Mapped CallSite projection interrupted") }
    }
    checkProjectionCancellation()
    forEachRawCallSiteStringIds(null) { _, callerClass, callerName, calleeClass, calleeName ->
        checkProjectionCancellation()
        consumer(
            StringPropertyProjectionRow(
                propertyIndexes.map { propertyIndex ->
                    val stringId =
                        when (propertyIndex) {
                            CALLER_CLASS_PROPERTY_INDEX -> callerClass
                            CALLER_NAME_PROPERTY_INDEX -> callerName
                            CALLEE_CLASS_PROPERTY_INDEX -> calleeClass
                            else -> calleeName
                        }
                    stringTable.get(stringId)
                }))
    }
    checkProjectionCancellation()
    return true
}

internal fun MappedGraphContext.canStreamCallSiteProjection(workConsumer: GraphWorkConsumer?): Boolean {
    checkCallSiteProjectionPreflightInterrupted()
    return callSiteProjectionSupported ?: validateCallSiteProjection(workConsumer).also { callSiteProjectionSupported = it }
}

internal fun MappedGraphContext.checkCallSiteProjectionPreflightInterrupted() {
    // Empty scans retain the projection callback's original precedence over the thread flag.
    if (nodeTypeIndex.count(CallSiteNode::class.java) > 0L) {
        checkThreadInterrupted { CancellationException("Mapped CallSite projection preflight interrupted") }
    }
}

internal fun MappedGraphContext.validateCallSiteProjection(workConsumer: GraphWorkConsumer?): Boolean {
    var index = 0
    // Reused IDs may leave a typed entry pointing at a different current record. Refuse
    // before row/cancellation callbacks so fallback never receives partial rows.
    // Inspected structural candidates are charged separately.
    for (nodeId in nodeTypeIndex.ids(CallSiteNode::class.java)) {
        if ((index++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) {
            checkThreadInterrupted { CancellationException("Mapped CallSite projection preflight interrupted") }
        }
        workConsumer?.consume()
        if (!isCurrentCallSiteRecord(nodeId)) return false
    }
    return true
}

private fun MappedGraphContext.isCurrentCallSiteRecord(nodeId: Int): Boolean {
    if (nodeId < 0 || nodeId >= nodeOffsets.size) return false
    val offset = nodeOffsets.offset(nodeId)
    return offset >= 0L &&
        offset <= mappedNodeData.limit() - NODE_HEADER_BYTES &&
        mappedNodeData.get(offset.toInt() + Int.SIZE_BYTES).toInt() == NodeSerializer.TAG_CALL_SITE_NODE
}

internal fun MappedGraphContext.projectStringPropertyDisjunction(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>,
    projectedProperties: List<String>,
    limit: Int,
    workConsumer: GraphWorkConsumer?
): List<StringPropertyProjectionRow>? {
    val unsupported = type != CallSiteNode::class.java || predicates.isEmpty() || limit < 0
    if (unsupported || !validProjectionProperties(type, predicates, projectedProperties)) return null
    val preferred =
        when (workConsumer) {
            is PreferredRawGraphWorkBatchConsumer -> rawCallSiteStringProjection(predicates, projectedProperties, limit, workConsumer)
            else -> null
        }
            ?: when (workConsumer) {
                is PreferredMappedStringIndexViewGraphWorkBatchConsumer ->
                    initializedMappedCallSiteStringProjection(predicates, projectedProperties, limit, workConsumer)
                else -> null
            }
    return preferred
        ?: callSiteStringIndex?.let { index ->
            callSiteStringIndexLookupCount.incrementAndGet()
            index.projectRows(predicates, projectedProperties, limit, workConsumer)
        }
}

internal fun MappedGraphContext.initializedMappedCallSiteStringProjection(
    predicates: List<StringPropertyPredicate>,
    projectedProperties: List<String>,
    limit: Int,
    workConsumer: GraphWorkConsumer
): List<StringPropertyProjectionRow>? {
    if (!predicates.shareStringMatcher()) return null
    return mappedCallSiteStringIndexView
        ?.let { mappedView ->
            callSiteStringLookupEntryCount.incrementAndGet()
            mappedView.exactMatchingStringIds(predicates, workConsumer)?.let { exactMatches ->
                mappedView.matchingNodeIds(predicates, exactMatches, workConsumer)
            }
        }
        ?.let { nodeIds -> projectMappedCallSiteRows(nodeIds, projectedProperties, limit) }
}

private fun MappedGraphContext.projectMappedCallSiteRows(
    nodeIds: Sequence<Int>,
    projectedProperties: List<String>,
    limit: Int
): List<StringPropertyProjectionRow> {
    val projectedPropertyIndexes = projectedProperties.map(::requiredCallSiteStringPropertyIndex)
    val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
    return nodeIds
        .take(limit)
        .map { nodeId ->
            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
                stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
                stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
                stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
            }
            StringPropertyProjectionRow(
                projectedPropertyIndexes.map { propertyIndex -> stringTable.get(stringIds[propertyIndex]).toString() })
        }
        .toList()
}

internal fun MappedGraphContext.rawCallSiteStringProjection(
    predicates: List<StringPropertyPredicate>,
    projectedProperties: List<String>,
    limit: Int,
    workConsumer: GraphWorkConsumer
): List<StringPropertyProjectionRow>? {
    if (limit <= 0) return emptyList()
    callSiteStringLookupEntryCount.incrementAndGet()
    val cacheKey = RawProjectionMatchKey(predicates.toList(), limit)
    val predicatePropertyIndexes = predicates.map { predicate -> requiredCallSiteStringPropertyIndex(predicate.property) }
    val projectedPropertyIndexes = projectedProperties.map(::requiredCallSiteStringPropertyIndex)
    return rawProjectionMatches[cacheKey]?.let { cachedNodeIds ->
        consumeGraphWork(workConsumer, cachedNodeIds.size.coerceAtLeast(1).toLong())
        projectRawCallSiteRows(cachedNodeIds, projectedPropertyIndexes)
    } ?: probeRawCallSiteProjection(predicates, projectedPropertyIndexes, predicatePropertyIndexes, limit, cacheKey, workConsumer)
}

private fun MappedGraphContext.probeRawCallSiteProjection(
    predicates: List<StringPropertyPredicate>,
    projectedPropertyIndexes: List<Int>,
    predicatePropertyIndexes: List<Int>,
    limit: Int,
    cacheKey: RawProjectionMatchKey,
    workConsumer: GraphWorkConsumer
): List<StringPropertyProjectionRow>? {
    val sharedStates = mutableMapOf<StringPredicateKey, BoundedStringMatcher>()
    val matchStates =
        predicates.map { predicate ->
            sharedStates.getOrPut(StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)) {
                BoundedStringMatcher(
                    stringTable,
                    StringPredicateKey(predicate.transform, predicate.mode, predicate.expected),
                    RAW_PROJECTION_STRING_MATCH_CACHE_CAPACITY)
            }
        }
    val rows = ArrayList<StringPropertyProjectionRow>(limit)
    val matchedNodeIds = IntArray(limit)
    val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    val nodeIds = nodeTypeIndex.idIterator(CallSiteNode::class.java)
    val scaledProbe = (limit.toLong() * RAW_PROJECTION_PROBE_FACTOR).coerceAtMost(RAW_PROJECTION_MAX_PROBE_NODES.toLong()).toInt()
    val maxInspected = minOf(RAW_PROJECTION_MAX_PROBE_NODES, maxOf(RAW_PROJECTION_MIN_PROBE_NODES, scaledProbe))
    var inspected = 0
    try {
        while (nodeIds.hasNext() && inspected < maxInspected) {
            val nodeId = nodeIds.nextInt()
            if ((inspected and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) {
                checkThreadInterrupted { CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED) }
            }
            inspected++
            accounting.consume()
            var matched = false
            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
                stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
                stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
                stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
                matched = boundedPredicatesMatch(stringIds, predicatePropertyIndexes, matchStates)
            }
            if (matched) {
                matchedNodeIds[rows.size] = nodeId
                rows +=
                    StringPropertyProjectionRow(
                        projectedPropertyIndexes.map { propertyIndex -> stringTable.get(stringIds[propertyIndex]).toString() })
            }
            if (rows.size >= limit) break
        }
        if (rows.size < limit && nodeIds.hasNext()) return null
        rawProjectionMatches.put(cacheKey, matchedNodeIds.copyOf(rows.size))
        return rows
    } finally {
        accounting.flush()
    }
}

private fun boundedPredicatesMatch(ids: IntArray, properties: List<Int>, states: List<BoundedStringMatcher>): Boolean {
    var predicateIndex = 0
    var matched = false
    while (!matched && predicateIndex < properties.size) {
        matched = states[predicateIndex].matches(ids[properties[predicateIndex]])
        predicateIndex++
    }
    return matched
}

internal fun MappedGraphContext.projectRawCallSiteRows(
    nodeIds: IntArray,
    projectedPropertyIndexes: List<Int>
): List<StringPropertyProjectionRow> {
    val stringIds = IntArray(CALL_SITE_STRING_PROPERTY_COUNT)
    return nodeIds.map { nodeId ->
        withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
            stringIds[CALLER_CLASS_PROPERTY_INDEX] = callerClass
            stringIds[CALLER_NAME_PROPERTY_INDEX] = callerName
            stringIds[CALLEE_CLASS_PROPERTY_INDEX] = calleeClass
            stringIds[CALLEE_NAME_PROPERTY_INDEX] = calleeName
        }
        StringPropertyProjectionRow(projectedPropertyIndexes.map { propertyIndex -> stringTable.get(stringIds[propertyIndex]).toString() })
    }
}

private fun validProjectionProperties(type: Class<out Node>, predicates: List<StringPropertyPredicate>, properties: List<String>): Boolean =
    predicates.all { supportsRawStringProperty(type, it.property) } && properties.all { supportsRawStringProperty(type, it) }
