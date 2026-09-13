package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
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
    fun `nullable and numeric values do not use string aggregation for count or distinct`() {
        val nodes = listOf(
            EnumConstant(NodeId(1), TypeDescriptor("Example"), "MatchNumber", listOf(42)),
            EnumConstant(NodeId(2), TypeDescriptor("Example"), "MatchNull"),
            AnnotationNode(NodeId(3), "MatchText", "Owner", "member", mapOf("value" to "42")),
            AnnotationNode(NodeId(4), "MatchNull", "Owner", "member", mapOf("value" to null)),
            AnnotationNode(NodeId(5), "MatchNumber", "Owner", "member", mapOf("value" to 42))
        )
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
        val graph = object : Graph by backing, StringPropertyDisjunctionAggregation {
            override fun aggregateStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                distinctProperty: String?
            ): StringPropertyDisjunctionAggregate = error("Nullable or numeric values reached string-only aggregation")
        }
        val executor = CypherExecutor(graph)

        for ((label, expected) in listOf("" to (3L to 2L), ":EnumConstant" to (1L to 1L), ":AnnotationNode" to (2L to 2L))) {
            assertEquals(
                listOf(mapOf("total" to expected.first)),
                executor.execute(
                    "MATCH (n$label) WHERE n.name CONTAINS 'Match' RETURN count(n.value) AS total"
                ).rows
            )
            assertEquals(
                listOf(mapOf("total" to expected.second)),
                executor.execute(
                    "MATCH (n$label) WHERE n.name CONTAINS 'Match' RETURN count(DISTINCT n.value) AS total"
                ).rows
            )
        }
        assertEquals(
            listOf(42, null, "42", null, 42),
            executor.execute(
                "MATCH (n) WHERE n.name CONTAINS 'Match' RETURN n.id AS id, n.value AS value ORDER BY id LIMIT 10"
            ).rows.map { it["value"] }
        )
    }

    @Test
    fun `custom annotation conjuncts remain residual filters in either order`() {
        val graph = DefaultGraph.Builder().apply {
            addNode(AnnotationNode(
                NodeId(1), "First", "Owner", "member",
                mapOf("custom" to "needle", "detail" to "present", "caller_class" to 123)
            ))
            addNode(AnnotationNode(
                NodeId(2), "Second", "Owner", "member", mapOf("custom" to "absent", "caller_class" to 123)
            ))
        }.build()
        val executor = CypherExecutor(graph)
        val custom = "n.custom CONTAINS 'needle'"
        val supported = "toString(n.caller_class) CONTAINS '123'"

        for (condition in listOf("$custom AND $supported", "$supported AND $custom", "$custom AND n.detail = 'present'")) {
            for (distinct in listOf("", "DISTINCT ")) {
                assertEquals(
                    listOf(mapOf("id" to 1)),
                    executor.execute("MATCH (n) WHERE $condition RETURN ${distinct}n.id AS id LIMIT 10").rows
                )
            }
            assertEquals(
                listOf(mapOf("total" to 1L)),
                executor.execute("MATCH (n) WHERE $condition RETURN count(*) AS total").rows
            )
            assertEquals(
                listOf(mapOf("total" to 1L)),
                executor.execute("MATCH (n) WHERE $condition RETURN count(DISTINCT n.id) AS total").rows
            )
        }
    }

    @Test
    fun `dynamic annotation counted fields remain nullable and preserve numeric distinct values`() {
        val backing = DefaultGraph.Builder().apply {
            listOf(null, 123, "123").forEachIndexed { index, value ->
                addNode(AnnotationNode(
                    NodeId(index + 1), "Annotation", "Owner", "member", mapOf("value" to "needle", "caller_class" to value)
                ))
            }
        }.build()
        val graph = object : Graph by backing, StringPropertyDisjunctionAggregation {
            override fun aggregateStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                distinctProperty: String?
            ): StringPropertyDisjunctionAggregate = error("Dynamic annotation property reached string-only aggregation")
        }
        val executor = CypherExecutor(graph)
        for (distinct in listOf("", "DISTINCT ")) {
            assertEquals(
                listOf(mapOf("total" to 2L)),
                executor.execute(
                    "MATCH (n) WHERE n.value CONTAINS 'needle' RETURN count(${distinct}n.caller_class) AS total"
                ).rows
            )
        }
    }

    @Test
    fun `annotation distinct normalizes numeric types and nested values before applying limits`() {
        val executor = CypherExecutor(annotationProjectionGraph(listOf(123, 123L, listOf(123), listOf(123L), "123")))
        val predicate = "toString(n.caller_class) CONTAINS '123'"
        assertEquals(
            listOf(123, listOf(123), "123"),
            executor.execute("MATCH (n) WHERE $predicate RETURN DISTINCT n.caller_class AS value LIMIT 10").rows.map { it["value"] }
        )
        assertEquals(
            listOf(listOf(123), "123"),
            executor.execute(
                "MATCH (n) WHERE $predicate RETURN DISTINCT n.caller_class AS value SKIP 1 LIMIT 2"
            ).rows.map { it["value"] }
        )
        assertEquals(
            listOf(123, listOf(123), "123"),
            executor.execute(
                "MATCH (n) WHERE n.value CONTAINS 'needle' AND $predicate " +
                    "RETURN DISTINCT n.caller_class AS value LIMIT 10"
            ).rows.map { it["value"] }
        )
    }

    @Test
    fun `annotation numeric distinct combines provenance across graphs`() {
        val result = CrossGraphCypherExecutor(
            listOf(
                CypherGraph("first", annotationProjectionGraph(listOf(123))),
                CypherGraph("second", annotationProjectionGraph(listOf(123L)))
            )
        ).execute(
            "MATCH (n) WHERE toString(n.caller_class) CONTAINS '123' RETURN DISTINCT n.caller_class AS value LIMIT 1"
        )

        assertEquals(
            listOf(mapOf("value" to 123, RESULT_METADATA_KEY to mapOf(RESULT_GRAPH_IDS_KEY to listOf("first", "second")))),
            result.rows
        )
    }

    private fun annotationProjectionGraph(values: List<Any>): Graph {
        val nodes = values.mapIndexed { index, value ->
            AnnotationNode(NodeId(index + 1), "Annotation", "Owner", "member", mapOf("caller_class" to value, "value" to "needle"))
        }
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
        return object : Graph by backing, StringPropertyLookupOrder, StringPropertyDisjunctionDistinctProjection {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                nodes.asSequence().filter(type::isInstance).map(type::cast)

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> = error("Annotation projection reached string-only DISTINCT storage")
        }
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
