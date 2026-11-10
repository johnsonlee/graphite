package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class GraphFallbackContractTest {
    @Test
    fun `missing statistics and failed overview index retain exact scan results`() {
        val caller = MethodDescriptor(TypeDescriptor("app.Caller"), "run", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(TypeDescriptor("app.Target"), "accept", emptyList(), TypeDescriptor("void"))
        val first = CallSiteNode(NodeId.next(), caller, callee, 1, null, emptyList())
        val second = CallSiteNode(NodeId.next(), caller, caller, 2, null, emptyList())
        val original = DefaultGraph.Builder().addNode(first).addNode(second).addEdge(CallEdge(first.id, second.id, false)).build()
        val graph = object : Graph by original {
            override fun nodeCount(type: Class<out Node>): Long? = null
            override fun edgeCount(): Long? = null
            override fun methodCount(): Long? = null
            override fun classOverview(limit: Int): ClassOverview = error("index unavailable")
        }
        assertEquals(graphStats(original), graphStats(graph))
        val routes = ExploreRoutes()
        try {
            val overview = routes.buildClassOverview(graph, 10)
            assertEquals(listOf("app.Caller", "app.Target"), overview.getValue("nodes").map { it["id"] })
            assertEquals(listOf(3, 1), overview.getValue("nodes").map { it["callSites"] })
            assertEquals(listOf(mapOf("from" to "app.Caller", "to" to "app.Target", "type" to "Call", "weight" to 1)), overview["edges"])
            val bounded = mutableMapOf("known" to 1)
            routes.incrementBounded(bounded, "known", 1)
            routes.incrementBounded(bounded, "new", 1)
            assertEquals(mapOf("known" to 2), bounded)
        } finally {
            routes.cypherGuard.close()
        }
    }

    @Test
    fun `unknown multi graph mode is rejected with supported choices`() {
        assertEquals(
            "Invalid query mode 'parallel'. Expected 'cross-graph' or 'fanout'",
            assertFailsWith<IllegalStateException> { MultiGraphQueryMode.parse("parallel") }.message
        )
    }
}
