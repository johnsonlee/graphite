package io.johnsonlee.graphite.query

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertTrue

class ResourceReturnTypeTest {
    @Test
    fun `nested resource values do not invent scalar return types`() {
        for (value in listOf(null, listOf("nested"), mapOf("value" to 7))) {
            val method = MethodDescriptor(TypeDescriptor("Config"), "load", emptyList(), TypeDescriptor("java.lang.Object"))
            val resource = ResourceValueNode(NodeId(1), "config.json", "item", value, "json")
            val returned = ReturnNode(NodeId(2), method)
            val graph = DefaultGraph.Builder().addMethod(method).addNode(resource).addNode(returned)
                .addEdge(DataFlowEdge(resource.id, returned.id, DataFlowKind.ASSIGN)).build()
            assertTrue(GraphiteQuery(graph).findActualReturnTypes {}.isEmpty(), "$value")
        }
    }
}
