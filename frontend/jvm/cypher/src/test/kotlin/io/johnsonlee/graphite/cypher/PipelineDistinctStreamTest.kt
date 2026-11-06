package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class PipelineDistinctStreamTest : QueryPipelineTestFixture() {

    @Test
    fun `filtered distinct string disjunction consumes candidates lazily`() {
        val caller = MethodDescriptor(TypeDescriptor("com.example.Service"), "call", emptyList(), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repository"), "load", emptyList(), stringType)
        val backing =
            DefaultGraph.Builder()
                .apply { repeat(1_000) { index -> addNode(CallSiteNode(NodeId(index), caller, callee, index, null, emptyList())) } }
                .build()
        var consumed = 0
        val lookupGraph =
            object : Graph by backing, StringPropertyLookup {
                override fun <T : Node> nodesByStringProperty(
                    type: Class<T>,
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> = backing.nodes(type).onEach { consumed++ }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n:CallSiteNode) WHERE " +
                        "n.caller_class CONTAINS 'example' OR n.callee_class CONTAINS 'example' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
        assertEquals(1, consumed)
    }

    @Test
    fun `filtered distinct string disjunction deduplicates unordered candidate streams`() {
        val targetCaller = MethodDescriptor(TypeDescriptor("com.example.TargetService"), "call", emptyList(), stringType)
        val otherCaller = MethodDescriptor(TypeDescriptor("com.example.OtherService"), "call", emptyList(), stringType)
        val targetCallee = MethodDescriptor(TypeDescriptor("com.example.TargetRepository"), "load", emptyList(), stringType)
        val backing =
            DefaultGraph.Builder()
                .apply {
                    addNode(CallSiteNode(NodeId(0), otherCaller, targetCallee, 0, null, emptyList()))
                    addNode(CallSiteNode(NodeId(1), targetCaller, targetCallee, 1, null, emptyList()))
                    addNode(CallSiteNode(NodeId(2), otherCaller, targetCallee, 2, null, emptyList()))
                }
                .build()
        val lookupGraph =
            object : Graph by backing, StringPropertyLookup {
                override fun <T : Node> nodesByStringProperty(
                    type: Class<T>,
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> {
                    val nodes = backing.nodes(type).associateBy { it.id.value }
                    return when (property) {
                        "caller_class" -> sequenceOf(nodes.getValue(1))
                        "callee_class" -> sequenceOf(nodes.getValue(2), nodes.getValue(1), nodes.getValue(0))
                        else -> emptySequence()
                    }
                }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n:CallSiteNode) WHERE " +
                        "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                        "RETURN DISTINCT n.id AS id LIMIT 4")

        assertEquals(listOf(1, 2, 0), result.rows.map { it["id"] })
    }

    @Test
    fun `filtered distinct string disjunction merges lookup streams in graph order`() {
        val targetCaller = MethodDescriptor(TypeDescriptor("com.example.TargetService"), "call", emptyList(), stringType)
        val otherCaller = MethodDescriptor(TypeDescriptor("com.example.OtherService"), "call", emptyList(), stringType)
        val targetCallee = MethodDescriptor(TypeDescriptor("com.example.TargetRepository"), "load", emptyList(), stringType)
        val backing =
            DefaultGraph.Builder()
                .apply {
                    addNode(CallSiteNode(NodeId(0), otherCaller, targetCallee, 0, null, emptyList()))
                    addNode(CallSiteNode(NodeId(1), targetCaller, targetCallee, 1, null, emptyList()))
                    addNode(CallSiteNode(NodeId(2), otherCaller, targetCallee, 2, null, emptyList()))
                }
                .build()
        val lookupGraph =
            object : Graph by backing, StringPropertyLookup, StringPropertyLookupOrder {
                override fun <T : Node> nodesByStringProperty(
                    type: Class<T>,
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> {
                    val nodes = backing.nodes(type).associateBy { it.id.value }
                    return when (property) {
                        "caller_class" -> sequenceOf(nodes.getValue(1))
                        "callee_class" -> sequenceOf(nodes.getValue(2), nodes.getValue(1), nodes.getValue(0))
                        else -> emptySequence()
                    }
                }

                override fun stringPropertyNodeOrder(node: Node): Long =
                    when (node.id.value) {
                        2 -> 0
                        1 -> 1
                        else -> 2
                    }
            }

        val result =
            CypherExecutor(lookupGraph)
                .execute(
                    "MATCH (n:CallSiteNode) WHERE " +
                        "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                        "RETURN DISTINCT n.id AS id LIMIT 4")

        assertEquals(listOf(2, 1, 0), result.rows.map { it["id"] })
    }

    @Test
    fun `ordered string lookup rejects a sequence that violates its monotonic contract`() {
        val target = MethodDescriptor(TypeDescriptor("com.example.TargetService"), "call", emptyList(), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Dependency"), "load", emptyList(), stringType)
        val backing =
            DefaultGraph.Builder()
                .apply {
                    addNode(CallSiteNode(NodeId(0), target, callee, 0, null, emptyList()))
                    addNode(CallSiteNode(NodeId(1), target, callee, 1, null, emptyList()))
                }
                .build()
        val lookupGraph =
            object : Graph by backing, StringPropertyLookup, StringPropertyLookupOrder {
                override fun <T : Node> nodesByStringProperty(
                    type: Class<T>,
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> {
                    val nodes = backing.nodes(type).associateBy { it.id.value }
                    return if (property == "caller_class") {
                        sequenceOf(nodes.getValue(1), nodes.getValue(0))
                    } else {
                        emptySequence()
                    }
                }

                override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
            }

        val error =
            assertFailsWith<IllegalArgumentException> {
                CypherExecutor(lookupGraph)
                    .execute(
                        "MATCH (n:CallSiteNode) WHERE " +
                            "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                            "RETURN DISTINCT n.id AS id LIMIT 4")
            }

        assertTrue(error.message.orEmpty().contains("not monotonic"))
    }

    @Test
    fun `filtered distinct disjunction keeps unbounded storage lookup semantics`() {
        val unboundedGraph =
            object : Graph by graph, StringPropertyLookup {
                override fun nodeCount(type: Class<out Node>): Long = Int.MAX_VALUE.toLong()

                override fun <T : Node> nodesByStringProperty(
                    type: Class<T>,
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T>? {
                    assertEquals(Int.MAX_VALUE, limit)
                    return null
                }
            }
        val result =
            CypherExecutor(unboundedGraph)
                .execute(
                    "MATCH (n:CallSiteNode) WHERE " +
                        "n.caller_class CONTAINS 'Service' OR n.callee_class CONTAINS 'Service' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
    }

    @Test
    fun `filtered distinct disjunction with unsupported properties uses generic evaluation`() {
        val result =
            CypherExecutor(graph)
                .execute(
                    "MATCH (n:StringConstant) WHERE " +
                        "(exists(n.value) AND n.value CONTAINS 'hello') OR " +
                        "(exists(n.unknown) AND n.unknown CONTAINS 'hello') " +
                        "RETURN DISTINCT n.value AS value LIMIT 1")

        assertEquals(listOf("hello"), result.rows.map { it["value"] })
    }

    @Test
    fun `filtered distinct disjunction with mismatched existence guard uses generic evaluation`() {
        val result =
            CypherExecutor(graph)
                .execute(
                    "MATCH (n:StringConstant) WHERE " +
                        "(exists(n.unknown) AND n.value CONTAINS 'hello') OR " +
                        "(exists(n.name) AND n.name CONTAINS 'missing') " +
                        "RETURN DISTINCT n.value AS value LIMIT 1")

        assertTrue(result.rows.isEmpty())
    }
}
