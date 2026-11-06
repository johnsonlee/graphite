package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredPersistedStringIndexGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.nio.file.Files
import java.util.concurrent.CancellationException

internal fun <T : Node> MappedGraphContext.nodesByStringPropertyDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int
): Sequence<T>? = lookupStringPropertyDisjunction(type, predicates, limit, workConsumer = null)

internal fun <T : Node> MappedGraphContext.nodesByStringPropertyDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int,
    workConsumer: GraphWorkConsumer
): Sequence<T>? = lookupStringPropertyDisjunction(type, predicates, limit, workConsumer)

internal fun <T : Node> MappedGraphContext.lookupStringPropertyDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int,
    workConsumer: GraphWorkConsumer?
): Sequence<T>? =
    when {
        predicates.isEmpty() || predicates.any { !supportsRawStringProperty(type, it.property) } -> null
        limit <= 0 -> emptySequence()
        else -> StringDisjunctionRequest(this, type, predicates, limit, workConsumer).lookup()
    }

/** Keeps raw, persisted, mapped and generic fallbacks in their declared preference order. */
private class StringDisjunctionRequest<T : Node>(
    private val context: MappedGraphContext,
    private val type: Class<T>,
    private val predicates: List<StringPropertyPredicate>,
    private val limit: Int,
    private val workConsumer: GraphWorkConsumer?
) {
    fun lookup(): Sequence<T>? = callSiteLookup() ?: fallback()

    private fun callSiteLookup(): Sequence<T>? {
        if (type != CallSiteNode::class.java) return null
        context.callSiteStringLookupEntryCount.incrementAndGet()
        return preferredRaw() ?: preferredPersisted() ?: retainedOrRaw()
    }

    private fun preferredRaw(): Sequence<T>? =
        if (workConsumer is PreferredRawGraphWorkBatchConsumer) {
            context.serialRawCallSiteStringDisjunction(type, predicates, limit, workConsumer)
        } else null

    private fun preferredPersisted(): Sequence<T>? {
        if (workConsumer !is PreferredPersistedStringIndexGraphWorkBatchConsumer) return null
        val index =
            context.callSiteStringIndex
                ?: run {
                    context.retainPersistedCallSiteStringIndex.set(true)
                    context.loadPersistedCallSiteStringIndexIfAvailable(type, checkNotNull(workConsumer))
                }
        return index?.let(::indexed)
    }

    private fun retainedOrRaw(): Sequence<T>? {
        context.retainPersistedCallSiteStringIndexForSplit(workConsumer)
        val index =
            context.callSiteStringIndex
                ?: if (workConsumer is SplitGraphWorkBatchConsumer &&
                    workConsumer !is PreferredMappedStringIndexViewGraphWorkBatchConsumer) {
                    context.loadPersistedCallSiteStringIndexIfAvailable(type, checkNotNull(workConsumer))
                } else null
        val view = if (index == null) context.mappedCallSiteStringIndexView(workConsumer) else null
        val exact =
            if (workConsumer is SplitGraphWorkBatchConsumer) {
                index?.exactMatchingStringIds(predicates, workConsumer) ?: view?.exactMatchingStringIds(predicates, workConsumer)
            } else null
        return if (exact?.all(IntArray::isEmpty) == true) emptySequence()
        else {
            exactLookup(view, exact)
                ?: index?.let(::indexed)
                ?: context.parallelRawCallSiteStringDisjunction(type, predicates, limit, workConsumer)
                ?: context.serialRawCallSiteStringDisjunction(type, predicates, limit, workConsumer)
        }
    }

    private fun exactLookup(view: MappedCallSiteStringIndexView?, exact: List<IntArray>?): Sequence<T>? {
        if (exact == null || workConsumer !is SplitGraphWorkBatchConsumer) return null
        val mapped =
            if (view != null && predicates.shareStringMatcher()) {
                view.matchingNodeIds(predicates, exact, workConsumer)?.take(limit)?.mapNotNull { id ->
                    context.node(NodeId(id))?.let(type::cast)
                }
            } else null
        return mapped
            ?: context.parallelRawCallSiteStringDisjunction(
                type,
                predicates,
                limit,
                workConsumer,
                exact,
                view?.exactMatchesCanFillLimit(predicates, exact, limit, workConsumer) == true)
    }

    private fun indexed(index: MappedCallSiteStringIndex): Sequence<T> {
        context.callSiteStringIndexLookupCount.incrementAndGet()
        return matchingNodes(index)
    }

    private fun matchingNodes(index: MappedCallSiteStringIndex): Sequence<T> =
        index
            .matchingNodeIds(predicates, workConsumer, limit)
            .mapNotNull { id -> context.node(NodeId(id)) as? CallSiteNode }
            .map(type::cast)

    private fun fallback(): Sequence<T>? {
        if (type == CallSiteNode::class.java &&
            context.shouldPreflightCallSitePredicates(predicates) &&
            context.callSitePredicatesCannotMatch(predicates, workConsumer))
            return emptySequence()
        val index = if (workConsumer !is SerialGraphWorkBatchConsumer) context.callSiteStringIndex(type, workConsumer) else null
        return index?.let(::matchingNodes)
            ?: if (context.prefersSerialStringPropertyDisjunction(type, predicates)) null
            else context.scanStringPropertyDisjunction(type, predicates, limit, workConsumer)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Node> MappedGraphContext.scanStringPropertyDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int,
    workConsumer: GraphWorkConsumer?
): Sequence<T> = sequence {
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    val sharedStates = mutableMapOf<StringPredicateKey, ByteArray>()
    val matchStates =
        predicates.map { predicate ->
            val predicateKey = StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)
            sharedStates.getOrPut(predicateKey) { ByteArray(stringTable.size()) }
        }
    var yielded = 0
    var inspected = 0
    try {
        for (nodeId in nodeTypeIndex.ids(type)) {
            if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) {
                checkThreadInterrupted { CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED) }
            }
            accounting.consume()
            val matches =
                predicates.indices.any { index ->
                    val predicate = predicates[index]
                    val stringId = rawStringPropertyIndex(nodeId, type, predicate.property) ?: return@any false
                    val states = matchStates[index]
                    when (states[stringId]) {
                        RAW_STRING_MATCH -> true
                        RAW_STRING_MISS -> false
                        else ->
                            stringMatches(stringTable.get(stringId), predicate.transform, predicate.mode, predicate.expected).also { matched
                                ->
                                states[stringId] = if (matched) RAW_STRING_MATCH else RAW_STRING_MISS
                            }
                    }
                }
            if (matches) {
                val matchedNode = node(NodeId(nodeId)) as? T
                if (matchedNode != null) {
                    yielded++
                    accounting.flush()
                    yield(matchedNode)
                    if (yielded >= limit) break
                }
            }
        }
    } finally {
        accounting.flush()
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Node> MappedGraphContext.serialRawCallSiteStringDisjunction(
    type: Class<T>,
    predicates: List<StringPropertyPredicate>,
    limit: Int,
    workConsumer: GraphWorkConsumer?
): Sequence<T>? {
    if (type != CallSiteNode::class.java || limit == Int.MAX_VALUE || workConsumer !is SerialGraphWorkBatchConsumer) return null
    return sequence {
        val propertyIndexes = predicates.map { predicate -> requiredCallSiteStringPropertyIndex(predicate.property) }
        val sharedMatchers = mutableMapOf<StringPredicateKey, BoundedStringMatcher>()
        val matchers =
            predicates.map { predicate ->
                val key = StringPredicateKey(predicate.transform, predicate.mode, predicate.expected)
                sharedMatchers.getOrPut(key) { BoundedStringMatcher(stringTable, key) }
            }
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val nodeIds = nodeTypeIndex.idIterator(type)
        var inspected = 0
        var yielded = 0
        try {
            while (yielded < limit && nodeIds.hasNext()) {
                val nodeId = nodeIds.nextInt()
                if ((inspected++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) {
                    checkThreadInterrupted { CancellationException(MAPPED_STRING_PROPERTY_SCAN_INTERRUPTED) }
                }
                accounting.consume()
                var matched = false
                withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                    var predicateIndex = 0
                    while (!matched && predicateIndex < predicates.size) {
                        val stringId =
                            when (propertyIndexes[predicateIndex]) {
                                CALLER_CLASS_PROPERTY_INDEX -> callerClass
                                CALLER_NAME_PROPERTY_INDEX -> callerName
                                CALLEE_CLASS_PROPERTY_INDEX -> calleeClass
                                else -> calleeName
                            }
                        matched = matchers[predicateIndex].matches(stringId)
                        predicateIndex++
                    }
                }
                if (matched) {
                    val matchedNode = node(NodeId(nodeId)) as? T
                    if (matchedNode != null) {
                        yielded++
                        accounting.flush()
                        yield(matchedNode)
                    }
                }
            }
        } finally {
            accounting.flush()
        }
    }
}

internal fun MappedGraphContext.prefersSerialStringPropertyDisjunction(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>
): Boolean {
    callSiteStringIndex?.let { index ->
        if (type == CallSiteNode::class.java && predicates.all { supportsRawStringProperty(type, it.property) })
            return index.prefersSerialScan
    }
    return false
}

internal fun MappedGraphContext.hasPreparedStringPropertyDisjunction(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>
): Boolean =
    type == CallSiteNode::class.java &&
        predicates.all { supportsRawStringProperty(type, it.property) } &&
        persistentCallSiteStringIndexEnabled &&
        Files.isRegularFile(callSiteStringIndexFile)

internal fun MappedGraphContext.hasRetainedStringPropertyDisjunction(
    type: Class<out Node>,
    predicates: List<StringPropertyPredicate>
): Boolean =
    type == CallSiteNode::class.java && callSiteStringIndex != null && predicates.all { supportsRawStringProperty(type, it.property) }
