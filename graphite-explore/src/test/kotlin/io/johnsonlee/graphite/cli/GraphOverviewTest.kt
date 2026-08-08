package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphOverviewTest {

    private val sourceMethod = method("com.example.orders.OrderService", "submit")
    private val targetMethod = method("com.example.payments.PaymentService", "charge")

    @Test
    fun `aggregates calls between graphs`() {
        val source = graph(
            methods = listOf(sourceMethod),
            calls = listOf(sourceMethod to targetMethod, sourceMethod to targetMethod)
        )
        val target = graph(methods = listOf(targetMethod))

        val overview = buildGraphOverview(listOf(input("orders", source), input("payments", target)))

        @Suppress("UNCHECKED_CAST")
        val nodes = overview[API_FIELD_NODES] as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val edges = overview[API_FIELD_EDGES] as List<Map<String, Any?>>
        assertEquals(listOf("orders", "payments"), nodes.map { it[API_FIELD_ID] })
        assertEquals(1, edges.size)
        assertEquals("orders", edges.single()["from"])
        assertEquals("payments", edges.single()["to"])
        assertEquals("GraphCall", edges.single()[API_FIELD_TYPE])
        assertEquals(2, edges.single()["weight"])
        assertEquals(2, overview["graphCount"])
        assertEquals(1, overview["relationCount"])
        assertEquals(2, overview["crossGraphCallSites"])
        assertFalse(overview["truncated"] as Boolean)
    }

    @Test
    fun `does not infer ambiguous or internal calls as cross graph calls`() {
        val sourceOwningTarget = graph(
            methods = listOf(sourceMethod, targetMethod),
            calls = listOf(sourceMethod to targetMethod)
        )
        val duplicateOwner = graph(methods = listOf(targetMethod))

        val internal = buildGraphOverview(
            listOf(input("orders", sourceOwningTarget), input("payments", duplicateOwner))
        )
        @Suppress("UNCHECKED_CAST")
        assertTrue((internal[API_FIELD_EDGES] as List<Any>).isEmpty())

        val externalSource = graph(
            methods = listOf(sourceMethod),
            calls = listOf(sourceMethod to targetMethod)
        )
        val ambiguous = buildGraphOverview(
            listOf(
                input("orders", externalSource),
                input("payments-primary", graph(methods = listOf(targetMethod))),
                input("payments-copy", graph(methods = listOf(targetMethod)))
            )
        )
        @Suppress("UNCHECKED_CAST")
        assertTrue((ambiguous[API_FIELD_EDGES] as List<Any>).isEmpty())
    }

    private fun input(id: String, graph: Graph) = GraphOverviewInput(id, graph, graphStats(graph))

    private fun graph(
        methods: List<MethodDescriptor>,
        calls: List<Pair<MethodDescriptor, MethodDescriptor>> = emptyList()
    ): Graph {
        val builder = DefaultGraph.Builder()
        methods.forEach(builder::addMethod)
        calls.forEach { (caller, callee) ->
            builder.addNode(CallSiteNode(NodeId.next(), caller, callee, 1, null, emptyList()))
        }
        return builder.build()
    }

    private fun method(className: String, name: String) = MethodDescriptor(
        TypeDescriptor(className),
        name,
        emptyList(),
        TypeDescriptor("void")
    )
}
