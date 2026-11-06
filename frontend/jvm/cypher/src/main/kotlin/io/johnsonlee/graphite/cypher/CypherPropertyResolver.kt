package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.graph.Graph

internal class CypherPropertyResolver(private val graph: Graph?) {
    fun resolveProperty(obj: Any?, propertyName: String): Any? = when (obj) {
        is MethodValue -> obj.property(propertyName)
        is Node -> NodePropertyAccessor.getProperty(obj, propertyName, graph)
        is QualifiedNode -> qualifiedNodeProperty(obj, propertyName)
        is Edge -> getEdgeProperty(obj, propertyName)
        is QualifiedEdge -> when (propertyName) {
            GRAPH_ID_PROPERTY -> obj.graphId
            else -> getEdgeProperty(obj.edge, propertyName)
        }
        is Map<*, *> -> obj[propertyName]
        is PathFinder.Path -> when (propertyName) {
            "length" -> obj.edges.size
            else -> null
        }
        is QualifiedPath -> when (propertyName) {
            GRAPH_ID_PROPERTY -> obj.graphId
            "length" -> obj.edges.size
            else -> null
        }
        else -> null
    }

    fun getEdgeProperty(edge: Edge, prop: String): Any? = when (prop) {
        "type" -> CypherFunctions.call("type", listOf(edge))
        else -> when (edge) {
            is DataFlowEdge -> if (prop == "kind") edge.kind.name else null
            is CallEdge -> when (prop) {
                "virtual" -> edge.isVirtual
                "dynamic" -> edge.isDynamic
                else -> null
            }
            is TypeEdge -> if (prop == "kind") edge.kind.name else null
            is ControlFlowEdge -> when (prop) {
                "kind" -> edge.kind.name
                else -> null
            }
            is ResourceEdge -> if (prop == "kind") edge.kind.name else null
        }
    }
    private fun qualifiedNodeProperty(obj: QualifiedNode, propertyName: String): Any? = when (propertyName) {
            GRAPH_ID_PROPERTY -> obj.graphId
            ELEMENT_ID_PROPERTY, QUALIFIED_ID_PROPERTY -> obj.elementId
            else -> NodePropertyAccessor.getProperty(obj.node, propertyName, obj.graph)
        }

}
