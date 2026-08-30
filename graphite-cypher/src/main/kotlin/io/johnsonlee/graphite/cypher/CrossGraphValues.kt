package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph

internal const val INTERNAL_PROVENANCE_KEY = "\u0000graphite.graphIds"
const val RESULT_METADATA_KEY = "\$metadata"
const val RESULT_GRAPH_IDS_KEY = "graphIds"
internal const val GRAPH_ID_PROPERTY = "graphId"
internal const val ELEMENT_ID_PROPERTY = "elementId"
internal const val QUALIFIED_ID_PROPERTY = "qualifiedId"
internal const val METHOD_LABEL = "Method"
internal const val METHOD_SIGNATURE_PROPERTY = "signature"
internal const val METHOD_CLASS_PROPERTY = "class"
internal const val METHOD_NAME_PROPERTY = "name"
internal const val METHOD_PARAMETER_TYPES_PROPERTY = "parameter_types"
internal const val METHOD_RETURN_TYPE_PROPERTY = "return_type"

/**
 * A graph participating in a cross-graph query.
 *
 * [id] is the stable namespace for every graph-local node and edge identity.
 */
data class CypherGraph(
    val id: String,
    val graph: Graph
)

/** Virtual node backed by the method metadata index rather than the graph node store. */
internal data class MethodValue(
    val graphId: String?,
    val method: MethodDescriptor
) {
    fun property(name: String): Any? = when (name) {
        METHOD_SIGNATURE_PROPERTY -> method.signature
        METHOD_CLASS_PROPERTY -> method.declaringClass.className
        METHOD_NAME_PROPERTY -> method.name
        METHOD_PARAMETER_TYPES_PROPERTY -> method.parameterTypes.map { it.className }
        METHOD_RETURN_TYPE_PROPERTY -> method.returnType.className
        GRAPH_ID_PROPERTY -> graphId
        else -> null
    }

    fun properties(): Map<String, Any?> = linkedMapOf(
        METHOD_SIGNATURE_PROPERTY to method.signature,
        METHOD_CLASS_PROPERTY to method.declaringClass.className,
        METHOD_NAME_PROPERTY to method.name,
        METHOD_PARAMETER_TYPES_PROPERTY to method.parameterTypes.map { it.className },
        METHOD_RETURN_TYPE_PROPERTY to method.returnType.className
    ).apply {
        if (graphId != null) put(GRAPH_ID_PROPERTY, graphId)
    }
}

/** A node value whose identity is qualified by its owning graph. */
internal data class QualifiedNode(
    val graphId: String,
    val graph: Graph,
    val node: Node
) {
    val elementId: String get() = "$graphId:${node.id.value}"

    override fun equals(other: Any?): Boolean =
        other is QualifiedNode && graphId == other.graphId && node.id == other.node.id

    override fun hashCode(): Int = 31 * graphId.hashCode() + node.id.hashCode()
}

/** An edge value whose endpoints live in the same named graph. */
internal data class QualifiedEdge(
    val graphId: String,
    val graph: Graph,
    val edge: Edge
) {
    override fun equals(other: Any?): Boolean =
        other is QualifiedEdge && graphId == other.graphId && edge == other.edge

    override fun hashCode(): Int = 31 * graphId.hashCode() + edge.hashCode()
}

/** A variable-length path that retains the graph namespace of its elements. */
internal data class QualifiedPath(
    val graphId: String,
    val nodes: List<QualifiedNode>,
    val edges: List<QualifiedEdge>
)

internal fun nodeValue(value: Any?): Node? = when (value) {
    is Node -> value
    is QualifiedNode -> value.node
    else -> null
}

internal fun edgeValue(value: Any?): Edge? = when (value) {
    is Edge -> value
    is QualifiedEdge -> value.edge
    else -> null
}
