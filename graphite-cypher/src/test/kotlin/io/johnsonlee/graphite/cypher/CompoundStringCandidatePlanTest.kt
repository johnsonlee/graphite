package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionLookup
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompoundStringCandidatePlanTest {

    @Test
    fun `single graph storage work consumer accepts individual and batched charges`() {
        directStringStorageWorkConsumer(sourceCount = 1).consume()
        var charged = 0L
        val consumer = directStringStorageWorkConsumer(sourceCount = 1, consumeBatch = { charged += it })

        consumer.consume()
        (consumer as GraphWorkBatchConsumer).consume(3L)

        assertEquals(4L, charged)
    }

    @Test
    fun `compound union keeps both branches and rejects candidates before limit`() {
        val graph = candidates()
        val result = CypherExecutor(graph).execute(query(COMPOUND, "LIMIT 3"))

        assertEquals(listOf("left", "right", "overlap"), result.rows.map { it["value"] })
        assertEquals(0, graph.fullScans)
        val terms = graph.lookups.flatten().map { it.expected }.toSet()
        assertTrue(terms.any { it in setOf("alpha", "bravo") })
        assertTrue(terms.any { it in setOf("charlie", "delta") })
        assertTrue(graph.offeredValues.any { it.startsWith("false-") })
    }

    @Test
    fun `reversing compound branches preserves encounter order and overlap count`() {
        val graph = candidates()
        val executor = CypherExecutor(graph)
        val rows = executor.execute(query("($RIGHT) OR ($LEFT)"))
        val count = executor.execute("MATCH (n:CallSiteNode) WHERE $COMPOUND RETURN count(*) AS matches")

        assertEquals(listOf("left", "right", "overlap"), rows.rows.map { it["value"] })
        assertEquals(3L, count.rows.single()["matches"])
        assertEquals(0, graph.fullScans)
    }

    @Test
    fun `pure four way OR retains matches from every individual term`() {
        val graph = candidates()
        val condition = "n.caller_name CONTAINS 'alpha' OR n.callee_name CONTAINS 'bravo' OR " +
            "n.caller_name CONTAINS 'charlie' OR n.callee_name CONTAINS 'delta'"

        val result = CypherExecutor(graph).execute(query(condition))

        assertEquals(
            listOf("false-alpha", "false-bravo", "false-charlie", "false-delta", "left", "right", "overlap"),
            result.rows.map { it["value"] }
        )
        assertEquals(0, graph.fullScans)
    }

    @Test
    fun `unsupported OR branch retains numeric only and null matches through fallback`() {
        val graph = LookupGraph(backing(
            call(0, "left", "alpha", "bravo"),
            call(1, "bravo-only", "none", "bravo"),
            call(2, "delta-only", "none", "delta"),
            call(3, "rejected", "none", "none"),
            NullConstant(NodeId(4))
        ))
        val result = CypherExecutor(graph).execute(
            "MATCH (n) WHERE ($LEFT) OR n.line IN [1, 2] OR n.line IS NULL " +
                "RETURN n.caller_class AS value ORDER BY n.id LIMIT 10"
        )

        assertEquals(listOf("left", "bravo-only", "delta-only", null), result.rows.map { it["value"] })
        assertTrue(graph.fullScans > 0)
    }

    @Test
    fun `compound DISTINCT gathers later graph provenance after the result limit`() {
        val first = LookupGraph(backing(call(0, "selected", "alpha", "bravo")))
        val second = LookupGraph(backing(
            call(0, "unselected", "alpha", "bravo"),
            call(1, "selected", "charlie", "delta"),
            call(2, "selected", "alpha-charlie", "bravo-delta")
        ))
        val result = CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
            .execute("MATCH (n:CallSiteNode) WHERE $COMPOUND RETURN DISTINCT n.caller_class AS value LIMIT 1")

        assertEquals(listOf("selected"), result.rows.map { it["value"] })
        val metadata = result.rows.single()[RESULT_METADATA_KEY] as Map<*, *>
        assertEquals(listOf("first", "second"), metadata[RESULT_GRAPH_IDS_KEY])
        assertEquals(0, first.fullScans + second.fullScans)
        assertTrue(second.lookups.isNotEmpty())
    }

    @Test
    fun `unlabeled compound query retains Annotation dynamic properties`() {
        val graph = LookupGraph(backing(
            call(0, "left", "alpha", "bravo"),
            AnnotationNode(NodeId(1), "example.Dynamic", "example.Owner", "method", mapOf(
                "caller_class" to "annotation-right", "caller_name" to "charlie", "callee_name" to "delta"
            )),
            AnnotationNode(NodeId(2), "example.Dynamic", "example.Owner", "method", mapOf(
                "caller_class" to "annotation-rejected", "caller_name" to "charlie"
            ))
        ))
        val result = CypherExecutor(graph).execute(
            "MATCH (n) WHERE $COMPOUND RETURN n.caller_class AS value LIMIT 10"
        )

        assertEquals(listOf("left", "annotation-right"), result.rows.map { it["value"] })
        assertTrue(graph.lookups.isNotEmpty())
    }

    @Test
    fun `compound candidate inspection consumes the query work budget`() {
        val graph = candidates()
        val failure = assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(2)).execute(query(COMPOUND))
        }

        assertEquals(2L, failure.maxWorkUnits)
        assertTrue(graph.lookups.isNotEmpty())
        assertEquals(0, graph.fullScans)
        assertEquals(listOf("left", "right", "overlap"),
            CypherExecutor(graph, CypherExecutionBudget(100)).execute(query(COMPOUND)).rows.map { it["value"] })
    }

    @Test
    fun `cancellation while inspecting compound candidates prevents partial results`() {
        val signal = CypherCancellationSignal()
        val graph = candidates()
        graph.beforeInspect = { signal.cancel() }
        val context = CypherExecutionContext(CypherExecutionBudget(100), signal)

        assertFailsWith<CypherQueryCancelledException> {
            CypherExecutor(graph, context).execute(query(COMPOUND))
        }
        assertTrue(graph.lookups.isNotEmpty())
        assertEquals(0, graph.fullScans)
        assertTrue(graph.offeredValues.isEmpty())
    }

    private fun query(condition: String, suffix: String = "LIMIT 10"): String =
        "MATCH (n:CallSiteNode) WHERE $condition RETURN n.caller_class AS value $suffix"

    private fun candidates(): LookupGraph = LookupGraph(backing(
        call(0, "false-alpha", "alpha", "none"),
        call(1, "false-bravo", "none", "bravo"),
        call(2, "false-charlie", "charlie", "none"),
        call(3, "false-delta", "none", "delta"),
        call(4, "left", "alpha", "bravo"),
        call(5, "right", "charlie", "delta"),
        call(6, "overlap", "alpha-charlie", "bravo-delta"),
        call(7, "unrelated", "none", "none")
    ))

    private fun backing(vararg nodes: Node): Graph =
        DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()

    private fun call(id: Int, value: String, callerName: String, calleeName: String): CallSiteNode = CallSiteNode(
        NodeId(id),
        MethodDescriptor(TypeDescriptor(value), callerName, emptyList(), TypeDescriptor("void")),
        MethodDescriptor(TypeDescriptor("example.Target"), calleeName, emptyList(), TypeDescriptor("void")),
        id, null, emptyList()
    )

    /** Exact deterministic storage capability; the query still owns residual evaluation. */
    private class LookupGraph(private val backing: Graph) : Graph by backing,
        WorkAwareStringPropertyDisjunctionLookup, StringPropertyLookupOrder {
        val lookups = mutableListOf<List<StringPropertyPredicate>>()
        val offeredValues = mutableListOf<String>()
        var fullScans = 0
        var beforeInspect: () -> Unit = {}

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            if (type != AnnotationNode::class.java) fullScans++
            return backing.nodes(type).sortedBy { it.id.value }
        }

        override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

        override fun <T : Node> nodesByStringPropertyDisjunction(
            type: Class<T>, predicates: List<StringPropertyPredicate>, limit: Int
        ): Sequence<T>? = nodesByStringPropertyDisjunction(type, predicates, limit, GraphWorkConsumer {})

        override fun <T : Node> nodesByStringPropertyDisjunction(
            type: Class<T>, predicates: List<StringPropertyPredicate>, limit: Int, workConsumer: GraphWorkConsumer
        ): Sequence<T>? {
            if (type != CallSiteNode::class.java) return null
            lookups += predicates
            return backing.nodes<T>(type).sortedBy { it.id.value }.onEach {
                beforeInspect()
                workConsumer.consume()
            }.filter { node -> predicates.any { matches(node, it) } }.take(limit).onEach {
                offeredValues += (it as CallSiteNode).caller.declaringClass.className
            }
        }

        private fun matches(node: Node, predicate: StringPropertyPredicate): Boolean {
            val property = NodePropertyAccessor.getProperty(node, predicate.property) as? String ?: return false
            val actual = if (predicate.transform == StringValueTransform.LOWERCASE) property.lowercase() else property
            return when (predicate.mode) {
                StringMatchMode.CONTAINS -> actual.contains(predicate.expected)
                StringMatchMode.EQUALS -> actual == predicate.expected
                StringMatchMode.STARTS_WITH -> actual.startsWith(predicate.expected)
                StringMatchMode.ENDS_WITH -> actual.endsWith(predicate.expected)
            }
        }
    }

    private companion object {
        const val LEFT = "n.caller_name CONTAINS 'alpha' AND n.callee_name CONTAINS 'bravo'"
        const val RIGHT = "n.caller_name CONTAINS 'charlie' AND n.callee_name CONTAINS 'delta'"
        const val COMPOUND = "($LEFT) OR ($RIGHT)"
    }
}
