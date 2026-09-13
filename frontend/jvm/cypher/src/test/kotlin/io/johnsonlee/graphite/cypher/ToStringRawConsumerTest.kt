package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionLookup
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToStringRawConsumerTest {

    @Test
    fun `only fully coerced predicates request raw lookup for typed and untyped nodes`() {
        val wrapped = "toString(n.caller_class) CONTAINS '321'"
        for (label in listOf("", ":CallSiteNode")) {
            for (condition in listOf(wrapped, "$wrapped OR toString(n.caller_name) CONTAINS 'invoke'")) {
                val graph = spy()
                val result = CypherExecutor(graph).execute(
                    "MATCH (n$label) WHERE $condition RETURN n.id AS id LIMIT 10"
                )

                assertEquals(listOf(mapOf("id" to 2)), result.rows)
                val calls = graph.lookups.filter { it.type == CallSiteNode::class.java }
                assertTrue(calls.isNotEmpty())
                assertTrue(calls.all { it.consumer is PreferredRawGraphWorkBatchConsumer })
                assertTrue(calls.all { it.limit == 2 })
                assertEquals(emptyList(), graph.scans)
            }
        }
    }

    @Test
    fun `plain and mixed predicates retain their existing storage consumer`() {
        for (condition in listOf(
            "n.caller_class CONTAINS '321'",
            "toString(n.caller_class) CONTAINS '321' OR n.caller_name CONTAINS 'invoke'"
        )) {
            val graph = spy()
            val result = CypherExecutor(graph).execute("MATCH (n) WHERE $condition RETURN n.id AS id LIMIT 10")

            assertEquals(listOf(mapOf("id" to 2)), result.rows)
            val calls = graph.lookups.filter { it.type == CallSiteNode::class.java }
            assertTrue(calls.isNotEmpty())
            val expectedConsumerClass = directStringStorageWorkConsumer(sourceCount = 1)::class
            assertTrue(calls.all { it.consumer::class == expectedConsumerClass })
            assertTrue(calls.none { it.consumer is PreferredRawGraphWorkBatchConsumer })
            assertEquals(emptyList(), graph.scans)
        }
    }

    @Test
    fun `raw callsite lookup retains annotation conversion encounter order and work accounting`() {
        val graph = spy(includeAnnotations = true)
        val context = CypherExecutionContext(CypherExecutionBudget(100))
        val result = CypherExecutor(graph, context).execute(
            "MATCH (n) WHERE toString(n.caller_class) CONTAINS '321' " +
                "RETURN n.id AS id, n.caller_class AS caller LIMIT 10"
        )

        assertEquals(
            listOf(
                mapOf("id" to 1, "caller" to 321),
                mapOf("id" to 2, "caller" to "Owner321"),
                mapOf("id" to 4, "caller" to listOf(321))
            ),
            result.rows
        )
        assertTrue(graph.lookups.filter { it.type == CallSiteNode::class.java }.all {
            it.consumer is PreferredRawGraphWorkBatchConsumer
        })
        assertFalse(graph.lookups.any { it.type == AnnotationNode::class.java })
        assertEquals(listOf<Class<out Node>>(AnnotationNode::class.java), graph.scans)
        assertTrue(context.diagnostics.workUnitsConsumed >= 4)
    }

    private fun spy(includeAnnotations: Boolean = false): LookupGraph {
        fun call(id: Int, owner: String, name: String): CallSiteNode {
            val method = MethodDescriptor(TypeDescriptor(owner), name, emptyList(), TypeDescriptor("void"))
            return CallSiteNode(NodeId(id), method, method, id, null, emptyList())
        }
        val nodes = buildList {
            if (includeAnnotations) add(AnnotationNode(NodeId(1), "First", "Owner", "member", mapOf("caller_class" to 321)))
            add(call(2, "Owner321", "invoke"))
            add(call(3, "Other", "other"))
            if (includeAnnotations) {
                add(AnnotationNode(NodeId(4), "Second", "Owner", "member", mapOf("caller_class" to listOf(321))))
            }
        }
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
        return LookupGraph(backing, nodes)
    }

    private data class Lookup(
        val type: Class<out Node>,
        val consumer: GraphWorkConsumer,
        val limit: Int
    )

    private class LookupGraph(backing: Graph, private val orderedNodes: List<Node>) :
        Graph by backing, WorkAwareStringPropertyDisjunctionLookup, StringPropertyLookupOrder {
        val lookups = mutableListOf<Lookup>()
        val scans = mutableListOf<Class<out Node>>()

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            check(type == AnnotationNode::class.java) { "Callsite lookup unexpectedly scanned normal nodes" }
            scans += type
            return orderedNodes.asSequence().filter(type::isInstance).map(type::cast)
        }

        override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

        override fun <T : Node> nodesByStringPropertyDisjunction(
            type: Class<T>,
            predicates: List<StringPropertyPredicate>,
            limit: Int
        ): Sequence<T> = error("Expected storage consumer to be supplied even without an execution budget")

        override fun <T : Node> nodesByStringPropertyDisjunction(
            type: Class<T>,
            predicates: List<StringPropertyPredicate>,
            limit: Int,
            workConsumer: GraphWorkConsumer
        ): Sequence<T> {
            lookups += Lookup(type, workConsumer, limit)
            assertTrue(predicates.all { it.mode == StringMatchMode.CONTAINS && it.transform == null })
            return orderedNodes.asSequence().filter(type::isInstance).map(type::cast).onEach {
                if (workConsumer is GraphWorkBatchConsumer) workConsumer.consume(1) else workConsumer.consume()
            }.filter { node ->
                predicates.any { predicate ->
                    (NodePropertyAccessor.getProperty(node, predicate.property) as? String)?.contains(predicate.expected) == true
                }
            }.take(limit)
        }
    }
}
