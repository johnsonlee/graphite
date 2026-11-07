package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform

internal fun <T : Node> MappedGraphContext.nodesByStringProperty(
    type: Class<T>,
    property: String,
    mode: StringMatchMode,
    expected: String,
    limit: Int
): Sequence<T>? = lookupStringProperty(type, property, null, mode, expected, limit, workConsumer = null)

internal fun <T : Node> MappedGraphContext.nodesByStringProperty(
    type: Class<T>,
    property: String,
    mode: StringMatchMode,
    expected: String,
    limit: Int,
    workConsumer: GraphWorkConsumer
): Sequence<T>? = lookupStringProperty(type, property, null, mode, expected, limit, workConsumer)

internal fun <T : Node> MappedGraphContext.nodesByTransformedStringProperty(
    type: Class<T>,
    property: String,
    transform: StringValueTransform,
    mode: StringMatchMode,
    expected: String,
    limit: Int
): Sequence<T>? = lookupStringProperty(type, property, transform, mode, expected, limit, workConsumer = null)

internal fun <T : Node> MappedGraphContext.nodesByTransformedStringProperty(
    type: Class<T>,
    property: String,
    transform: StringValueTransform,
    mode: StringMatchMode,
    expected: String,
    limit: Int,
    workConsumer: GraphWorkConsumer
): Sequence<T>? = lookupStringProperty(type, property, transform, mode, expected, limit, workConsumer)

internal fun <T : Node> MappedGraphContext.lookupStringProperty(
    type: Class<T>,
    property: String,
    transform: StringValueTransform?,
    mode: StringMatchMode,
    expected: String,
    limit: Int,
    workConsumer: GraphWorkConsumer?
): Sequence<T>? {
    return when {
        !supportsRawStringProperty(type, property) -> null
        limit <= 0 -> emptySequence()
        else -> StringPropertyLookupRequest(this, type, property, transform, mode, expected, limit, workConsumer).lookup()
    }
}

private class StringPropertyLookupRequest<T : Node>(
    private val context: MappedGraphContext,
    private val type: Class<T>,
    private val property: String,
    private val transform: StringValueTransform?,
    private val mode: StringMatchMode,
    private val expected: String,
    private val limit: Int,
    private val workConsumer: GraphWorkConsumer?
) {
    private val key = StringPropertyKey(type, property)
    private val admission = StringPropertyAdmissionKey(key, transform, mode, expected, limit)

    fun lookup(): Sequence<T>? =
        retainedCallSiteNodes()
            ?: synchronized(context.stringPropertyIndexLock) { context.stringPropertyIndexes[key] }?.let(::indexed)
            ?: admittedLookup()

    @Suppress("UNCHECKED_CAST")
    private fun retainedCallSiteNodes(): Sequence<T>? =
        if (type == CallSiteNode::class.java) {
            context.callSiteStringIndex?.let { index ->
                val predicate = StringPropertyPredicate(property, transform, mode, expected)
                index.matchingNodeIds(listOf(predicate), workConsumer).take(limit).mapNotNull { context.node(NodeId(it)) as? T }
            }
        } else null

    private fun admittedLookup(): Sequence<T>? {
        val shouldBuild =
            synchronized(context.stringPropertyIndexLock) {
                context.stringPropertyAdmissions.shouldBuild(admission, limit == Int.MAX_VALUE)
            }
        if (!shouldBuild) return rawFallback()
        val index = synchronized(context.stringPropertyIndexLock) { context.stringPropertyIndexes[key] ?: buildIndex() }
        return index?.let(::indexed) ?: rawFallback()
    }

    private fun buildIndex(): MappedStringPropertyIndex? =
        context.buildStringPropertyIndex(type, property, workConsumer)?.also {
            context.stringPropertyIndexes[key] = it
            context.stringPropertyAdmissions.clear(key)
        }
            ?: run {
                context.stringPropertyAdmissions.reject(key)
                null
            }

    private fun rawFallback(): Sequence<T>? =
        if (limit == Int.MAX_VALUE) null else context.rawStringPropertyScan(type, admission, workConsumer)

    private fun indexed(index: MappedStringPropertyIndex): Sequence<T> =
        context.indexedNodes(index, transform, mode, expected, limit, workConsumer)
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Node> MappedGraphContext.indexedNodes(
    index: MappedStringPropertyIndex,
    transform: StringValueTransform?,
    mode: StringMatchMode,
    expected: String,
    limit: Int,
    workConsumer: GraphWorkConsumer?
): Sequence<T> = index.matchingNodeIds(transform, mode, expected, workConsumer, limit).mapNotNull { node(NodeId(it)) as? T }

@Suppress("UNCHECKED_CAST")
internal fun <T : Node> MappedGraphContext.rawStringPropertyScan(
    type: Class<T>,
    admission: StringPropertyAdmissionKey,
    workConsumer: GraphWorkConsumer?
): Sequence<T> = sequence {
    val property = admission.property.property
    val transform = admission.transform
    val mode = admission.mode
    val expected = admission.expected
    val limit = admission.limit
    var inspected = 0
    var yielded = 0
    var admitted = false
    var matchStates: ByteArray? = null
    for (nodeId in nodeTypeIndex.ids(type)) {
        workConsumer?.consume()
        inspected++
        if (!admitted && inspected > MAX_STRING_PROPERTY_ADMISSION_NODES) {
            synchronized(stringPropertyIndexLock) {
                if (admission.property !in stringPropertyIndexes) {
                    stringPropertyAdmissions.admit(admission)
                }
            }
            admitted = true
            matchStates = ByteArray(stringTable.size())
        }
        val stringId = rawStringPropertyIndex(nodeId, type, property)
        val states = matchStates
        val matches =
            if (stringId == null) {
                false
            } else if (states == null) {
                stringMatches(stringTable.get(stringId), transform, mode, expected)
            } else {
                when (states[stringId]) {
                    RAW_STRING_MATCH -> true
                    RAW_STRING_MISS -> false
                    else ->
                        stringMatches(stringTable.get(stringId), transform, mode, expected).also { matched ->
                            states[stringId] = if (matched) RAW_STRING_MATCH else RAW_STRING_MISS
                        }
                }
            }
        if (matches) {
            val matchedNode = node(NodeId(nodeId)) as? T
            if (matchedNode != null) {
                yield(matchedNode)
                yielded++
                if (yielded >= limit) break
            }
        }
    }
}

internal fun MappedGraphContext.buildStringPropertyIndex(
    type: Class<out Node>,
    property: String,
    workConsumer: GraphWorkConsumer?
): MappedStringPropertyIndex? {
    val nodeCount = nodeTypeIndex.count(type)
    if (estimatedStringPropertyIndexBytes(nodeCount) > MAX_STRING_PROPERTY_INDEX_RETAINED_BYTES) return null
    val capacity = nodeCount.toInt()
    val nodeIds = IntArray(capacity)
    val stringIds = IntArray(capacity)
    var size = 0
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    try {
        for (nodeId in nodeTypeIndex.ids(type)) {
            accounting.consume()
            val stringId = rawStringPropertyIndex(nodeId, type, property) ?: continue
            nodeIds[size] = nodeId
            stringIds[size] = stringId
            size++
        }
    } finally {
        accounting.flush()
    }
    val indexedNodeIds = if (size == capacity) nodeIds else nodeIds.copyOf(size)
    val indexedStringIds = if (size == capacity) stringIds else stringIds.copyOf(size)
    val uniqueStringIds = indexedStringIds.copyOf().apply(java.util.Arrays::sort)
    var uniqueSize = 0
    for (stringId in uniqueStringIds) {
        if (uniqueSize == 0 || uniqueStringIds[uniqueSize - 1] != stringId) {
            uniqueStringIds[uniqueSize++] = stringId
        }
    }
    return MappedStringPropertyIndex(indexedNodeIds, indexedStringIds, uniqueStringIds.copyOf(uniqueSize), stringTable)
}
