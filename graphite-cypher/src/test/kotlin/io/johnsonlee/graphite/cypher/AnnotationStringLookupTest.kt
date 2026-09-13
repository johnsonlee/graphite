package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationStringLookupTest {

    @Test
    fun `toString matches annotation numeric and list attributes in typed and untyped scans`() {
        for (ordered in listOf(false, true)) {
            for (label in listOf("", ":AnnotationNode")) {
                val spy = lookupGraph()
                val graph = if (ordered) ordered(spy) else spy
                val result = CypherExecutor(graph).execute(
                    "MATCH (n$label) WHERE toString(n.caller_class) CONTAINS '123' RETURN n.id AS id LIMIT 10"
                )

                assertEquals(if (label.isEmpty()) listOf(1, 2, 3, 4) else listOf(1, 3), result.rows.map { it["id"] })
                assertFalse(spy.lookupTypes.contains(AnnotationNode::class.java))
                if (ordered && label.isEmpty()) {
                    assertTrue(spy.lookupTypes.contains(CallSiteNode::class.java))
                    assertFalse(spy.scannedTypes.contains(Node::class.java))
                }
            }
        }
    }

    @Test
    fun `unknown annotation counts retain original traversal and conversion`() {
        val spy = lookupGraph()
        val graph = object : Graph by spy, StringPropertyDisjunctionLookup by spy {
            override fun nodeCount(type: Class<out Node>): Long? = null
        }
        val result = CypherExecutor(graph).execute(
            "MATCH (n) WHERE toString(n.caller_class) CONTAINS '123' RETURN n.id AS id LIMIT 3"
        )

        assertEquals(listOf(1, 2, 3), result.rows.map { it["id"] })
        assertTrue(spy.scannedTypes.contains(Node::class.java))
    }

    @Test
    fun `mixed callsite and annotation results retain encounter order under limit and distinct`() {
        for (ordered in listOf(false, true)) {
            val spy = lookupGraph()
            val graph = if (ordered) ordered(spy) else spy
            val executor = CypherExecutor(graph)
            val expected = spy.sourceNodes.filter { node ->
                NodePropertyAccessor.getProperty(node, "caller_class")?.toString()?.contains("123") == true
            }.map { it.id.value }.take(3)

            assertEquals(listOf(1, 2, 3), expected)
            for (distinct in listOf("", "DISTINCT ")) {
                val result = executor.execute(
                    "MATCH (n) WHERE toString(n.caller_class) CONTAINS '123' " +
                        "RETURN ${distinct}n.id AS id LIMIT 3"
                )
                assertEquals(expected, result.rows.map { it["id"] })
            }
            assertEquals(
                listOf(3, 2),
                executor.execute(
                    "MATCH (n) WHERE toString(n.caller_class) CONTAINS '123' " +
                        "RETURN n.id AS id ORDER BY id DESC SKIP 1 LIMIT 2"
                ).rows.map { it["id"] }
            )
            if (!ordered) assertTrue(spy.scannedTypes.contains(Node::class.java))
        }
    }

    @Test
    fun `count does not send annotation coercion to string only storage aggregation`() {
        val spy = lookupGraph()
        val executor = CypherExecutor(ordered(spy))

        assertEquals(
            listOf(mapOf("total" to 4L)),
            executor.execute(
                "MATCH (n) WHERE toString(n.caller_class) CONTAINS '123' RETURN count(*) AS total"
            ).rows
        )
        assertEquals(
            listOf(mapOf("total" to 2L)),
            executor.execute(
                "MATCH (n:AnnotationNode) WHERE toString(n.caller_class) CONTAINS '123' RETURN count(*) AS total"
            ).rows
        )
        assertFalse(spy.aggregationTypes.contains(AnnotationNode::class.java))
    }

    @Test
    fun `annotation string value participates in value lookup while numeric values remain nonstrings`() {
        for (ordered in listOf(false, true)) {
            val spy = lookupGraph()
            val executor = CypherExecutor(if (ordered) ordered(spy) else spy)
            assertEquals(
                listOf(1, 6),
                executor.execute(
                    "MATCH (n) WHERE n.value CONTAINS 'needle' RETURN n.id AS id LIMIT 10"
                ).rows.map { it["id"] }
            )
            assertEquals(
                listOf(1),
                executor.execute(
                    "MATCH (n:AnnotationNode) WHERE n.value CONTAINS 'needle' RETURN n.id AS id LIMIT 10"
                ).rows.map { it["id"] }
            )
            assertEquals(
                emptyList(),
                executor.execute(
                    "MATCH (n:AnnotationNode) WHERE n.value CONTAINS '123' RETURN n.id AS id LIMIT 10"
                ).rows
            )
            assertEquals(
                emptyList(),
                executor.execute(
                    "MATCH (n:AnnotationNode) WHERE n.caller_class CONTAINS '123' RETURN n.id AS id LIMIT 10"
                ).rows
            )
        }
    }

    private fun ordered(spy: LookupGraph): Graph = object :
        Graph by spy,
        StringPropertyDisjunctionLookup by spy,
        StringPropertyDisjunctionAggregation by spy,
        StringPropertyLookupOrder {
        override fun stringPropertyNodeOrder(node: Node): Long = spy.sourceNodes.indexOf(node).toLong()
    }

    private fun lookupGraph(): LookupGraph {
        fun call(id: Int, owner: String): CallSiteNode {
            val method = MethodDescriptor(TypeDescriptor(owner), "call", emptyList(), TypeDescriptor("void"))
            return CallSiteNode(NodeId(id), method, method, id, null, emptyList())
        }
        val nodes = listOf(
            AnnotationNode(NodeId(1), "First", "Owner", "member", mapOf("caller_class" to 123, "value" to "needle-annotation")),
            call(2, "Call123A"),
            AnnotationNode(NodeId(3), "Second", "Owner", "member", mapOf("caller_class" to listOf(123, 456), "value" to 123)),
            call(4, "Call123B"),
            AnnotationNode(NodeId(5), "Missing", "Owner", "member", mapOf("caller_class" to null)),
            StringConstant(NodeId(6), "needle-constant")
        )
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
        return LookupGraph(backing, nodes)
    }

    private class LookupGraph(
        private val backing: Graph,
        val sourceNodes: List<Node>
    ) : Graph by backing, StringPropertyDisjunctionLookup, StringPropertyDisjunctionAggregation {
        val lookupTypes = mutableListOf<Class<out Node>>()
        val scannedTypes = mutableListOf<Class<out Node>>()
        val aggregationTypes = mutableListOf<Class<out Node>>()

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            scannedTypes += type
            return sourceNodes.asSequence().filter(type::isInstance).map(type::cast)
        }

        override fun <T : Node> nodesByStringPropertyDisjunction(
            type: Class<T>,
            predicates: List<StringPropertyPredicate>,
            limit: Int
        ): Sequence<T> {
            lookupTypes += type
            assertTrue(predicates.all { it.mode == StringMatchMode.CONTAINS && it.transform == null })
            return sourceNodes.asSequence().filter(type::isInstance).map(type::cast).filter { node ->
                predicates.any { predicate ->
                    (NodePropertyAccessor.getProperty(node, predicate.property) as? String)?.contains(predicate.expected) == true
                }
            }.take(limit)
        }

        override fun aggregateStringPropertyDisjunction(
            type: Class<out Node>,
            predicates: List<StringPropertyPredicate>,
            distinctProperty: String?
        ): StringPropertyDisjunctionAggregate? {
            aggregationTypes += type
            check(type != AnnotationNode::class.java) { "Annotation coercion reached string-only aggregation" }
            return null
        }
    }
}
