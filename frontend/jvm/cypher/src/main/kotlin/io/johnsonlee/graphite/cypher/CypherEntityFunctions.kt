package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeEdge

internal object CypherEntityFunctions {
    fun id(value: Any?): Any? = when (value) {
        is Node -> value.id.value
        is QualifiedNode -> value.node.id.value
        else -> null
    }

    fun elementId(value: Any?): String? = when (value) {
        // The complete identity: a bridge and its covariant override share a signature, never an element id.
        is MethodValue -> listOfNotNull(value.graphId, METHOD_LABEL, value.method.identity).joinToString(":")
        is QualifiedNode -> value.elementId
        is Node -> value.id.value.toString()
        else -> null
    }

    fun graphId(value: Any?): String? = when (value) {
        is MethodValue -> value.graphId
        is QualifiedNode -> value.graphId
        is QualifiedEdge -> value.graphId
        is QualifiedPath -> value.graphId
        else -> null
    }

    fun properties(value: Any?): Map<String, Any?>? = when (value) {
        is MethodValue -> value.properties()
        is Node -> NodePropertyAccessor.getAllProperties(value, null)
        is QualifiedNode -> NodePropertyAccessor.getAllProperties(value.node, value.graph) + mapOf(
            GRAPH_ID_PROPERTY to value.graphId,
            ELEMENT_ID_PROPERTY to value.elementId,
            QUALIFIED_ID_PROPERTY to value.elementId
        )
        else -> null
    }

    fun keys(value: Any?): List<String>? = dynamicPropertyKeys(value)

    private val labelsByType = nodeLabelDescriptors.associate { it.type to it.labels }

    fun labels(value: Any?): List<String> = when (value) {
        is MethodValue -> listOf(METHOD_LABEL)
        is QualifiedNode -> labels(value.node)
        is Node -> labelsByType[value.javaClass]?.toList().orEmpty()
        else -> emptyList()
    }

    fun type(value: Any?): String? = when (value) {
        is QualifiedEdge -> type(value.edge)
        is DataFlowEdge -> "DATAFLOW"
        is CallEdge -> "CALL"
        is TypeEdge -> "TYPE"
        is ControlFlowEdge -> "CONTROL_FLOW"
        is ResourceEdge -> when (value.kind) {
            ResourceRelation.OPENS -> "RESOURCE_OPEN"
            ResourceRelation.LOADS -> "RESOURCE_LOAD"
            ResourceRelation.BUNDLE_CANDIDATE -> "RESOURCE_BUNDLE_CANDIDATE"
            ResourceRelation.LOOKUP -> "RESOURCE_LOOKUP"
            ResourceRelation.ENUMERATES -> "RESOURCE_KEYS"
        }
        else -> null
    }
}
