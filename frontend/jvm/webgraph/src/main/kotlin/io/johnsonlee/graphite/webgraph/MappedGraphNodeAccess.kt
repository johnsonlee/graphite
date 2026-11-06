package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.checkThreadInterrupted
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.propertyTextFragment
import java.util.concurrent.CancellationException
import java.util.function.IntPredicate

internal fun MappedGraphContext.node(id: NodeId): Node? {
    val nodeId = id.value
    if (nodeId < 0 || nodeId >= nodeOffsets.size) return null
    return nodeOffsets.offset(nodeId).takeUnless { it == -1L }?.let(::readNodeAt)
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Node> MappedGraphContext.nodes(type: Class<T>): Sequence<T> {
    return nodeTypeIndex.ids(type).mapNotNull { node(NodeId(it)) as? T }
}

internal fun MappedGraphContext.nodeCount(type: Class<out Node>): Long = nodeTypeIndex.count(type)

internal fun <T : Node> MappedGraphContext.nodesMatchingId(
    type: Class<T>,
    predicate: IntPredicate,
    workConsumer: GraphWorkConsumer?
): Sequence<T> = sequence {
    val ids = nodeTypeIndex.idIterator(type)
    var inspected = 0
    while (ids.hasNext()) {
        if ((inspected++ and NODE_ID_SCAN_CANCELLATION_MASK) == 0) {
            checkThreadInterrupted { CancellationException("Node ID candidate scan interrupted") }
        }
        val id = ids.nextInt()
        workConsumer?.consume()
        if (predicate.test(id)) {
            node(NodeId(id))?.takeIf(type::isInstance)?.let { yield(type.cast(it)) }
        }
    }
}

internal fun <T : Node> MappedGraphContext.propertyTextCandidates(
    type: Class<T>,
    fragment: String,
    workConsumer: GraphWorkConsumer?
): Sequence<T>? = propertyTextCandidates(type, listOf(fragment), workConsumer)

internal fun <T : Node> MappedGraphContext.propertyTextCandidates(
    type: Class<T>,
    fragments: List<String>,
    workConsumer: GraphWorkConsumer?
): Sequence<T>? {
    val invalidShape = fragments.isEmpty() || fragments.size > 2 || fragments.distinct().size != fragments.size
    if (invalidShape || fragments.any { propertyTextFragment(it) != it }) return null
    return MappedPropertyTextCandidates(mappedNodeData, nodeOffsets, nodeTypeIndex, stringTable, declaredTypeTable.value)
        .ids(type, fragments, workConsumer)
        .mapNotNull { nodeId -> node(NodeId(nodeId))?.takeIf(type::isInstance)?.let(type::cast) }
}

internal inline fun MappedGraphContext.forEachRawCallSiteStringIds(
    workConsumer: GraphWorkConsumer?,
    action: (Int, Int, Int, Int, Int) -> Unit
) {
    val accounting = BufferedGraphWorkConsumer(workConsumer)
    var index = 0
    try {
        for (nodeId in nodeTypeIndex.ids(CallSiteNode::class.java)) {
            if ((index++ and RAW_SCAN_INTERRUPTION_POLL_MASK) == 0) {
                checkThreadInterrupted { CancellationException("Mapped CallSite string index build interrupted") }
            }
            accounting.consume()
            withRawCallSiteStringIds(nodeId) { callerClass, callerName, calleeClass, calleeName ->
                action(nodeId, callerClass, callerName, calleeClass, calleeName)
            }
        }
    } finally {
        accounting.flush()
    }
}

internal inline fun MappedGraphContext.withRawCallSiteStringIds(nodeId: Int, action: (Int, Int, Int, Int) -> Unit) {
    val fields = nodeOffsets.offset(nodeId).toInt() + NODE_HEADER_BYTES
    val callerParameters = mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
    val calleeFields = fields + (METHOD_DESCRIPTOR_FIXED_INTS + callerParameters) * Int.SIZE_BYTES
    action(
        mappedNodeData.getInt(fields),
        mappedNodeData.getInt(fields + Int.SIZE_BYTES),
        mappedNodeData.getInt(calleeFields),
        mappedNodeData.getInt(calleeFields + Int.SIZE_BYTES))
}

internal fun MappedGraphContext.rawStringPropertyIndex(nodeId: Int, type: Class<out Node>, property: String): Int? {
    val offset = nodeOffsets.offset(nodeId)
    if (offset < 0L) return null
    val fields = offset.toInt() + NODE_HEADER_BYTES
    return if (type == CallSiteNode::class.java) rawCallSiteStringProperty(fields, property)
    else {
        rawFixedStringOffset(type, property)?.let { mappedNodeData.getInt(fields + it * Int.SIZE_BYTES) }
    }
}

private fun rawFixedStringOffset(type: Class<out Node>, property: String): Int? =
    when (type) {
        StringConstant::class.java -> 0
        EnumConstant::class.java -> namedStringOffset(property, "enum_type", "name")
        LocalVariable::class.java -> namedStringOffset(property, "name", "type")
        FieldNode::class.java -> namedStringOffset(property, "class", "name", "type")
        ParameterNode::class.java -> if (property == "type") 1 else null
        ResourceFileNode::class.java -> namedStringOffset(property, "path", "source", "format")
        else -> null
    }

private fun namedStringOffset(property: String, first: String, second: String, third: String? = null): Int? =
    when (property) {
        first -> 0
        second -> 1
        third -> 2
        else -> null
    }

internal fun MappedGraphContext.rawCallSiteStringProperty(fields: Int, property: String): Int? {
    val callerParameters = mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
    val calleeFields = fields + (METHOD_DESCRIPTOR_FIXED_INTS + callerParameters) * Int.SIZE_BYTES
    return when (property) {
        "caller_class" -> mappedNodeData.getInt(fields)
        "caller_name" -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
        "callee_class" -> mappedNodeData.getInt(calleeFields)
        "callee_name" -> mappedNodeData.getInt(calleeFields + Int.SIZE_BYTES)
        else -> null
    }
}

internal fun MappedGraphContext.requiredCallSiteStringPropertyIndex(property: String): Int =
    callSiteStringPropertyIndex(property).also { propertyIndex ->
        check(propertyIndex >= 0) { "Unsupported CallSite string property: $property" }
    }

internal fun MappedGraphContext.rawCallSiteStringPropertyId(nodeId: Int, propertyIndex: Int): Int {
    val fields = nodeOffsets.offset(nodeId).toInt() + NODE_HEADER_BYTES
    return when (propertyIndex) {
        CALLER_CLASS_PROPERTY_INDEX -> mappedNodeData.getInt(fields)
        CALLER_NAME_PROPERTY_INDEX -> mappedNodeData.getInt(fields + Int.SIZE_BYTES)
        CALLEE_CLASS_PROPERTY_INDEX,
        CALLEE_NAME_PROPERTY_INDEX -> {
            val callerParameters = mappedNodeData.getInt(fields + 2 * Int.SIZE_BYTES)
            val calleeFields = fields + (METHOD_DESCRIPTOR_FIXED_INTS + callerParameters) * Int.SIZE_BYTES
            mappedNodeData.getInt(calleeFields + if (propertyIndex == CALLEE_NAME_PROPERTY_INDEX) Int.SIZE_BYTES else 0)
        }
        else -> error("Unknown CallSite string property index: $propertyIndex")
    }
}

internal fun MappedGraphContext.readNodeAt(offset: Long): Node {
    val position = offset.toInt()
    // A malformed header still goes through the serializer's EOF/unknown-tag validation.
    val isCallSite =
        position >= 0 &&
            position <= mappedNodeData.limit() - NODE_HEADER_BYTES &&
            mappedNodeData.get(position + Int.SIZE_BYTES).toInt() == NodeSerializer.TAG_CALL_SITE_NODE
    val ordinals = if (isCallSite) callSiteOrdinals.value else CallSiteOrdinals.EMPTY
    val input = ByteBufferDataInput(mappedNodeData, position)
    return NodeRecordCodec.readNode(input, stringTable, nodeDataVersion, ordinals)
}
