package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyLookup
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TargetPreflightTest {
    @Test
    fun `absent target probes after first source and skips remaining adjacency`() {
        val fixture = fixture()
        val result = CypherExecutor(fixture.graph).execute(query("missing"))
        assertEquals(emptyList(), result.rows)
        assertTrue(fixture.events.any { it == "lookup" })
        assertEquals(1, fixture.events.count { it == "nodes" })
        assertEquals(1, fixture.events.count { it == "edges" })
        assertTrue(fixture.events.indexOf("edges") < fixture.events.indexOf("lookup"))
    }

    @Test
    fun `target existence does not truncate actual target results`() {
        val fixture = fixture(leadingIsolated = true)
        val result = CypherExecutor(fixture.graph).execute(query("needle"))
        assertEquals(listOf(mapOf("value" to "needle-first"), mapOf("value" to "needle-second")), result.rows)
        assertTrue(fixture.events.contains("lookup"))
        assertTrue(fixture.events.contains("edges"))
    }

    @Test
    fun `per graph target preflight preserves overlapping ids and provenance`() {
        val first = fixture("other")
        val second = fixture()
        val result = CrossGraphCypherExecutor(listOf(CypherGraph("first", first.graph), CypherGraph("second", second.graph)))
            .execute(query("needle"))
        assertEquals(listOf(
            mapOf("value" to "needle-first", "\$metadata" to mapOf("graphIds" to listOf("second"))),
            mapOf("value" to "needle-second", "\$metadata" to mapOf("graphIds" to listOf("second")))
        ), result.rows)
        assertEquals(1, first.events.count { it == "edges" })
        assertTrue(first.events.contains("lookup"))
        assertTrue(second.events.contains("nodes"))
    }

    @Test
    fun `limit never preflights later graphs`() {
        val first = fixture()
        val later = fixture()
        val result = CrossGraphCypherExecutor(listOf(CypherGraph("first", first.graph), CypherGraph("later", later.graph)))
            .execute(query("needle", limit = 1))
        assertEquals(listOf(mapOf("value" to "needle-first", "\$metadata" to mapOf("graphIds" to listOf("first")))), result.rows)
        assertEquals(emptyList(), later.events)
        assertFalse(first.events.contains("lookup"))
    }

    @Test
    fun `unsupported graph keeps original traversal`() {
        val fixture = fixture()
        val unsupported = object : Graph by fixture.graph {}
        assertEquals(emptyList(), CypherExecutor(unsupported).execute(query("missing")).rows)
        assertFalse(fixture.events.contains("lookup"))
        assertTrue(fixture.events.contains("nodes"))
        assertTrue(fixture.events.contains("edges"))
    }

    @Test
    fun `volatile condition and inline properties retain original traversal`() {
        val queries = listOf(
            "MATCH (a)-[:DATAFLOW]->(b) WHERE b.value CONTAINS 'missing' AND timestamp() > 0 RETURN b.value LIMIT 10",
            "MATCH (a {value: 0})-[:DATAFLOW]->(b) WHERE b.value CONTAINS 'missing' RETURN b.value LIMIT 10",
            "MATCH (a)-[:DATAFLOW]->(b {value: 'needle-first'}) WHERE b.value CONTAINS 'missing' RETURN b.value LIMIT 10"
        )
        for (query in queries) {
            val fixture = fixture()
            assertEquals(emptyList(), CypherExecutor(fixture.graph).execute(query).rows)
            assertFalse(fixture.events.contains("lookup"))
            assertTrue(fixture.events.contains("nodes"))
            assertTrue(fixture.events.contains("edges"))
        }
    }

    @Test
    fun `completed single source scan never probes targets`() {
        val fixture = fixture(onlyFirstSource = true)
        assertEquals(emptyList(), CypherExecutor(fixture.graph).execute(query("missing")).rows)
        assertEquals(1, fixture.events.count { it == "edges" })
        assertFalse(fixture.events.contains("lookup"))
    }

    @Test
    fun `target probe inspections are charged in addition to streamed sources`() {
        val fixture = workAwareFixture()
        val context = CypherExecutionContext(CypherExecutionBudget(10))
        assertEquals(emptyList(), CypherExecutor(fixture.graph, context).execute(query("missing")).rows)
        assertEquals(2, fixture.events.count { it == "source" })
        assertEquals(2, fixture.events.count { it == "probe" })
        assertEquals(4L, context.diagnostics.workUnitsConsumed)
    }

    @Test
    fun `target probe propagates budget exhaustion after the normal source prefix`() {
        val original = workAwareFixture()
        val normalScan = object : Graph by original.graph {}
        assertEquals(emptyList(), CypherExecutor(normalScan, CypherExecutionBudget(2)).execute(query("missing")).rows)
        assertEquals(2, original.events.count { it == "source" })
        assertEquals(0, original.events.count { it == "probe" })

        val fixture = workAwareFixture()
        val exception = assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(fixture.graph, CypherExecutionBudget(2)).execute(query("missing"))
        }
        assertEquals(2L, exception.maxWorkUnits)
        assertEquals(2, fixture.events.count { it == "source" })
        assertEquals(1, fixture.events.count { it == "probe" })
    }

    @Test
    fun `target probe propagates the original cancellation signal exception`() {
        val signal = CypherCancellationSignal()
        val marker = CypherQueryCancelledException("cancel during target probe")
        val fixture = workAwareFixture { signal.cancel(marker) }
        val context = CypherExecutionContext(CypherExecutionBudget(10), signal)
        val actual = assertFailsWith<CypherQueryCancelledException> {
            CypherExecutor(fixture.graph, context).execute(query("missing"))
        }
        assertSame(marker, actual)
        assertEquals(2, fixture.events.count { it == "source" })
        assertEquals(1, fixture.events.count { it == "probe" })
        assertEquals(2L, context.diagnostics.workUnitsConsumed)
    }

    private fun workAwareFixture(onProbe: () -> Unit = {}): Fixture {
        val events = mutableListOf<String>()
        val nodes = listOf(StringConstant(NodeId(1), "first"), StringConstant(NodeId(2), "second"))
        val base = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
        val graph = object : Graph by base, WorkAwareStringPropertyLookup, StringPropertyLookupOrder {
            override fun stringPropertyNodeOrder(node: Node) = nodes.indexOf(node).toLong()

            override fun <T : Node> nodes(type: Class<T>): Sequence<T> = nodes.asSequence()
                .filter(type::isInstance).map(type::cast).onEach { events += "source" }

            override fun <T : Node> nodesByStringProperty(
                type: Class<T>, property: String, mode: StringMatchMode, expected: String, limit: Int
            ): Sequence<T>? = error("Budgeted preflight must use the work-aware lookup")

            override fun <T : Node> nodesByStringProperty(
                type: Class<T>, property: String, mode: StringMatchMode, expected: String, limit: Int,
                workConsumer: GraphWorkConsumer
            ): Sequence<T> = nodes.asSequence().filter(type::isInstance).map(type::cast).onEach {
                events += "probe"
                onProbe()
                workConsumer.consume()
            }.filter { it is StringConstant && it.value.contains(expected) }.take(limit)
        }
        return Fixture(graph, events)
    }

    private fun query(expected: String, limit: Int = 10) =
        "MATCH (a)-[:DATAFLOW]->(b) WHERE b.value CONTAINS '$expected' RETURN b.value AS value LIMIT $limit"

    private data class Fixture(val graph: Graph, val events: MutableList<String>)

    private fun fixture(
        prefix: String = "needle",
        leadingIsolated: Boolean = false,
        onlyFirstSource: Boolean = false
    ): Fixture {
        val events = mutableListOf<String>()
        val nodes = (if (leadingIsolated) listOf(IntConstant(NodeId(1), 1)) else emptyList()) + listOf(
            IntConstant(NodeId(0), 0),
            StringConstant(NodeId(2), "$prefix-first"),
            StringConstant(NodeId(3), "$prefix-second")
        )
        val base = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }
            .addEdge(DataFlowEdge(NodeId(0), NodeId(2), DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(NodeId(0), NodeId(3), DataFlowKind.ASSIGN)).build()
        val graph = object : Graph by base, StringPropertyLookup, StringPropertyLookupOrder {
            override fun stringPropertyNodeOrder(node: Node) = nodes.indexOf(node).toLong()
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
                events += "nodes"
                val seeds = if (onlyFirstSource) nodes.take(1) else nodes
                return seeds.asSequence().filter(type::isInstance).map(type::cast)
            }
            override fun node(id: NodeId): Node? {
                events += "node"
                return base.node(id)
            }
            override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> {
                events += "edges"
                return base.outgoing(id, type)
            }
            override fun <T : Node> nodesByStringProperty(
                type: Class<T>, property: String, mode: StringMatchMode, expected: String, limit: Int
            ): Sequence<T> {
                events += "lookup"
                assertEquals("value", property)
                assertEquals(StringMatchMode.CONTAINS, mode)
                assertTrue(limit in 0..1)
                return nodes.asSequence().filter(type::isInstance)
                    .filter { it is StringConstant && it.value.contains(expected) }.map(type::cast).take(limit)
            }
        }
        return Fixture(graph, events)
    }
}
