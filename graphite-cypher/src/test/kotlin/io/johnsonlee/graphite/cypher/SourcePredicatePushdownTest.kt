package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourcePredicatePushdownTest {
    @Test
    fun `source conjunction avoids rejected adjacency and retains target predicate`() {
        val accessed = mutableListOf<NodeId>()
        val result = CypherExecutor(graph(accessed)).execute(query("a.value = 2 AND b.value = 3"))
        assertEquals(listOf(mapOf("source" to 2, "target" to 3)), result.rows)
        assertEquals(listOf(NodeId(2)), accessed)
    }

    @Test
    fun `right only predicate cannot prune sources`() {
        val accessed = mutableListOf<NodeId>()
        val result = CypherExecutor(graph(accessed)).execute(query("b.value = 3"))
        assertEquals(listOf(mapOf("source" to 1, "target" to 3), mapOf("source" to 2, "target" to 3)), result.rows)
        assertEquals(listOf(NodeId(1), NodeId(2), NodeId(3)), accessed)
    }

    @Test
    fun `mixed variable disjunction retains either branch`() {
        val accessed = mutableListOf<NodeId>()
        val result = CypherExecutor(graph(accessed)).execute(query("a.value = 2 OR b.value = 3"))
        assertEquals(listOf(mapOf("source" to 1, "target" to 3), mapOf("source" to 2, "target" to 3)), result.rows)
        assertEquals(listOf(NodeId(1), NodeId(2), NodeId(3)), accessed)
    }

    @Test
    fun `source filter preserves encounter order and limit`() {
        val accessed = mutableListOf<NodeId>()
        val result = CypherExecutor(graph(accessed)).execute(query("a.value > 0", "LIMIT 1"))
        assertEquals(listOf(mapOf("source" to 1, "target" to 3)), result.rows)
        assertEquals(listOf(NodeId(1)), accessed)
    }

    @Test
    fun `source filter preserves skip distinct and ordering`() {
        val result = CypherExecutor(graph()).execute(
            "MATCH (a)-[:DATAFLOW]->(b) WHERE a.value > 0 " +
                "RETURN DISTINCT a.value AS source ORDER BY source DESC SKIP 1 LIMIT 1"
        )
        assertEquals(listOf(mapOf("source" to 1)), result.rows)
    }

    @Test
    fun `cross graph source filtering keeps provenance for identical node ids`() {
        val result = CrossGraphCypherExecutor(listOf(CypherGraph("first", graph()), CypherGraph("second", graph())))
            .execute("MATCH (a)-[:DATAFLOW]->(b) WHERE a.value = 2 RETURN a.graphId AS graph, a.value AS value LIMIT 10")
        assertEquals(listOf(
            mapOf("graph" to "first", "value" to 2, "\$metadata" to mapOf("graphIds" to listOf("first"))),
            mapOf("graph" to "second", "value" to 2, "\$metadata" to mapOf("graphIds" to listOf("second")))
        ), result.rows)
    }

    @Test
    fun `missing source property remains null and rejects rows before adjacency`() {
        val accessed = mutableListOf<NodeId>()
        assertEquals(emptyList(), CypherExecutor(graph(accessed)).execute(query("a.missing = 2")).rows)
        assertEquals(emptyList(), accessed)
    }

    @Test
    fun `volatile and possibly throwing residuals disable pushdown`() {
        val source = CypherExpr.Comparison("=", CypherExpr.Property(CypherExpr.Variable("a"), "value"), CypherExpr.Literal(2))
        for (function in listOf("rand", "timestamp", "unknown", "range")) {
            assertNull(SourcePredicatePushdown.compile(CypherExpr.And(source, CypherExpr.FunctionCall(function, emptyList())), "a"))
        }
    }

    @Test
    fun `rejected source scans still consume the work budget`() {
        val accessed = mutableListOf<NodeId>()
        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph(accessed), CypherExecutionBudget(1)).execute(query("a.value = 99"))
        }
        assertEquals(emptyList(), accessed)
    }

    @Test
    fun `strict NOT type errors are not suppressed by source pruning`() {
        val accessed = mutableListOf<NodeId>()
        assertFailsWith<ClassCastException> {
            CypherExecutor(graph(accessed)).execute(query("a.value = 99 AND NOT b.value"))
        }
        assertEquals(listOf(NodeId(1)), accessed)
    }

    @Test
    fun `NOT with known boolean result still permits source pruning`() {
        val accessed = mutableListOf<NodeId>()
        val result = CypherExecutor(graph(accessed)).execute(query("NOT (a.value = 1)"))
        assertEquals(listOf(mapOf("source" to 2, "target" to 3)), result.rows)
        assertEquals(listOf(NodeId(2), NodeId(3)), accessed)
    }

    @Test
    fun `ordered source lookup avoids untyped scan and does not truncate dead seeds`() {
        val nodes = listOf(
            StringConstant(NodeId(8), "needle-dead"),
            StringConstant(NodeId(4), "needle-first"),
            StringConstant(NodeId(2), "needle-second"),
            IntConstant(NodeId(9), 9)
        )
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }
            .addEdge(DataFlowEdge(NodeId(4), NodeId(9), DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(NodeId(2), NodeId(9), DataFlowKind.ASSIGN))
            .build()
        val accessed = mutableListOf<NodeId>()
        val lookupLimits = mutableListOf<Int>()
        val graph = object : Graph by backing, StringPropertyLookup, StringPropertyLookupOrder {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                error("A lookup-capable graph must not scan nodes for this predicate: $type")

            override fun stringPropertyNodeOrder(node: Node): Long = nodes.indexOf(node).toLong()

            override fun <T : Node> nodesByStringProperty(
                type: Class<T>, property: String, mode: StringMatchMode, expected: String, limit: Int
            ): Sequence<T> {
                assertEquals("value", property)
                assertEquals(StringMatchMode.CONTAINS, mode)
                assertEquals("needle", expected)
                if (type == StringConstant::class.java) lookupLimits += limit
                return nodes.asSequence().filter(type::isInstance)
                    .filter { it is StringConstant && it.value.contains(expected) }.map(type::cast).take(limit)
            }

            override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> {
                accessed += id
                return backing.outgoing(id, type)
            }
        }
        val result = CypherExecutor(graph).execute(
            "MATCH (a)-[:DATAFLOW]->(b) WHERE a.value CONTAINS 'needle' RETURN a.value AS value LIMIT 1"
        )
        assertEquals(listOf(mapOf("value" to "needle-first")), result.rows)
        assertEquals(listOf(NodeId(8), NodeId(4)), accessed)
        kotlin.test.assertTrue(lookupLimits.isNotEmpty())
        kotlin.test.assertTrue(lookupLimits.all { it > 1 })
    }

    @Test
    fun `dense typed relationship source requests lazy raw storage and reads only first seed`() {
        val method = MethodDescriptor(TypeDescriptor("android.Service"), "call", emptyList(), TypeDescriptor("void"))
        val sources = (1..3).map { id -> CallSiteNode(NodeId(id), method, method, null, null, emptyList()) }
        val targets = (0 until 50).map { index -> IntConstant(NodeId(100 + index), index) }
        val backing = DefaultGraph.Builder().apply {
            sources.forEach(::addNode)
            targets.forEach(::addNode)
            targets.forEach { target -> addEdge(DataFlowEdge(sources.first().id, target.id, DataFlowKind.ASSIGN)) }
        }.build()
        val consumedSources = mutableListOf<NodeId>()
        val expandedSources = mutableListOf<NodeId>()
        val graph = object : Graph by backing, WorkAwareStringPropertyDisjunctionLookup, StringPropertyLookupOrder {
            override fun stringPropertyNodeOrder(node: Node) = node.id.value.toLong()
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("Unexpected ordinary source scan")
            override fun outgoing(id: NodeId): Sequence<Edge> {
                expandedSources += id
                return backing.outgoing(id)
            }
            override fun <T : Node> nodesByStringPropertyDisjunction(
                type: Class<T>, predicates: List<StringPropertyPredicate>, limit: Int
            ): Sequence<T>? = error("Storage consumer must accompany candidate selection")
            override fun <T : Node> nodesByStringPropertyDisjunction(
                type: Class<T>, predicates: List<StringPropertyPredicate>, limit: Int, workConsumer: GraphWorkConsumer
            ): Sequence<T> {
                // A parallel consumer plus limit == nodeCount makes mapped storage fall through
                // to eager index construction. Raw serial preference must reach storage explicitly.
                assertTrue(workConsumer is PreferredRawGraphWorkBatchConsumer)
                assertTrue(workConsumer is SerialGraphWorkBatchConsumer)
                assertEquals(sources.size, limit)
                assertEquals<Class<*>>(CallSiteNode::class.java, type)
                assertEquals(listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "android")), predicates)
                return sources.asSequence().onEach { source ->
                    consumedSources += source.id
                    workConsumer.consume()
                }.map(type::cast)
            }
        }
        val result = CypherExecutor(graph, CypherExecutionBudget(1_000)).execute(
            "MATCH (c:CallSiteNode)-[:DATAFLOW]->(n) WHERE c.caller_class CONTAINS 'android' " +
                "RETURN n.value AS value LIMIT 50"
        )
        assertEquals(targets.map { mapOf("value" to it.value) }, result.rows)
        assertEquals(listOf(sources.first().id), consumedSources)
        assertEquals(listOf(sources.first().id), expandedSources)
    }

    private fun query(predicate: String, suffix: String = "LIMIT 10"): String =
        "MATCH (a)-[:DATAFLOW]->(b) WHERE $predicate RETURN a.value AS source, b.value AS target $suffix"

    private fun graph(accessed: MutableList<NodeId> = mutableListOf()): Graph {
        val nodes = listOf(IntConstant(NodeId(1), 1), IntConstant(NodeId(2), 2), IntConstant(NodeId(3), 3))
        val base = DefaultGraph.Builder()
            .addNode(nodes[0])
            .addNode(nodes[1])
            .addNode(nodes[2])
            .addEdge(DataFlowEdge(NodeId(1), NodeId(3), DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(NodeId(2), NodeId(3), DataFlowKind.ASSIGN))
            .build()
        return object : Graph by base {
            // Make the fixture's encounter order explicit instead of relying on hash-map iteration.
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                nodes.asSequence().filter(type::isInstance).map(type::cast)

            override fun outgoing(id: NodeId): Sequence<Edge> {
                accessed += id
                return base.outgoing(id)
            }

            override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> {
                accessed += id
                return base.outgoing(id, type)
            }
        }
    }
}
