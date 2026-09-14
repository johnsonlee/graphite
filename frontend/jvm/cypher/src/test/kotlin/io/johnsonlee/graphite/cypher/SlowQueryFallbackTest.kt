package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyLookup
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.WorkAwareTransformedStringPropertyLookup
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SlowQueryFallbackTest {

    @Test
    fun `concrete value labels retain their existing single property lookup capability`() {
        val nodes = listOf(
            StringConstant(NodeId(1), "needle"),
            EnumConstant(NodeId(2), TypeDescriptor("Example"), "ENTRY", listOf("needle")),
            ResourceValueNode(NodeId(3), "file", "key", "needle", "text"),
            AnnotationNode(NodeId(4), "Example", "Owner", "member", mapOf("value" to "needle"))
        )
        for (node in nodes) {
            val backing = DefaultGraph.Builder().addNode(node).build()
            var lookups = 0
            val graph = object : Graph by backing, StringPropertyLookup, StringPropertyDisjunctionLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("Expected existing property lookup")

                override fun <T : Node> nodesByStringProperty(
                    type: Class<T>, property: String, mode: StringMatchMode, expected: String, limit: Int
                ): Sequence<T> {
                    assertEquals<Class<*>>(node.javaClass, type)
                    assertEquals("value", property)
                    assertEquals(StringMatchMode.CONTAINS, mode)
                    assertEquals("needle", expected)
                    assertEquals(1, limit)
                    lookups++
                    return sequenceOf(type.cast(node))
                }

                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>, predicates: List<StringPropertyPredicate>, limit: Int
                ): Sequence<T> = error("Concrete value lookup must preserve existing index admission policy")
            }
            val executor = CypherExecutor(graph)
            repeat(2) {
                val result = executor.execute(
                    "MATCH (n:${node.javaClass.simpleName}) WHERE n.value CONTAINS 'needle' " +
                        "RETURN n.id AS id, n.value AS value LIMIT 1"
                )
                assertEquals(listOf(mapOf("id" to node.id.value, "value" to "needle")), result.rows)
            }
            assertEquals(2, lookups)
        }
    }

    @Test
    fun `transformed concrete value lookup forwards work while projecting the original string`() {
        val node = StringConstant(NodeId(1), "Needle")
        val backing = DefaultGraph.Builder().addNode(node).build()
        var lookups = 0
        val graph = object : Graph by backing, WorkAwareTransformedStringPropertyLookup {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("Expected transformed property lookup")

            override fun <T : Node> nodesByTransformedStringProperty(
                type: Class<T>, property: String, transform: StringValueTransform,
                mode: StringMatchMode, expected: String, limit: Int
            ): Sequence<T> = error("Budgeted lookup must carry its work consumer")

            override fun <T : Node> nodesByTransformedStringProperty(
                type: Class<T>, property: String, transform: StringValueTransform,
                mode: StringMatchMode, expected: String, limit: Int, workConsumer: GraphWorkConsumer
            ): Sequence<T> {
                assertEquals<Class<*>>(StringConstant::class.java, type)
                assertEquals("value", property)
                assertEquals(StringValueTransform.LOWERCASE, transform)
                assertEquals(StringMatchMode.CONTAINS, mode)
                assertEquals("needle", expected)
                assertEquals(1, limit)
                lookups++
                return sequence {
                    workConsumer.consume()
                    yield(type.cast(node))
                }
            }
        }
        val context = CypherExecutionContext(CypherExecutionBudget(10))
        val result = CypherExecutor(graph, context).execute(
            "MATCH (n:StringConstant) WHERE toLower(n.value) CONTAINS 'needle' RETURN n.value AS value LIMIT 1"
        )

        assertEquals(listOf(mapOf("value" to "Needle")), result.rows)
        assertEquals(1, lookups)
        assertEquals(1L, context.diagnostics.workUnitsConsumed)
    }

    @Test
    fun `unsupported residual operators cannot have their errors suppressed by source pruning`() {
        val target = CypherExpr.Property(CypherExpr.Variable("b"), "value")
        val invalid = listOf(
            CypherExpr.Comparison("INVALID", target, CypherExpr.Literal("Target")) to "Unknown comparison",
            CypherExpr.StringOp("INVALID", target, CypherExpr.Literal("Target")) to "Unknown string op"
        )
        for ((residual, message) in invalid) {
            val accessed = mutableListOf<NodeId>()
            val clauses = CypherDslAdapter.parse(
                "MATCH (a)-[:DATAFLOW]->(b) WHERE a.value = 'absent' RETURN b.value LIMIT 1"
            ).map { clause ->
                if (clause is CypherClause.Where) clause.copy(condition = CypherExpr.And(clause.condition, residual)) else clause
            }
            val failure = assertFailsWith<CypherException> { QueryPipeline(relationshipGraph(accessed)).execute(clauses) }
            assertTrue(failure.message.orEmpty().contains(message))
            assertEquals(listOf(NodeId(1)), accessed)
        }
    }

    @Test
    fun `nested deterministic string transforms prune rejected source adjacency`() {
        val accessed = mutableListOf<NodeId>()
        val result = CypherExecutor(relationshipGraph(accessed)).execute(
            "MATCH (a)-[:DATAFLOW]->(b) WHERE toLower(toString(a.value)) CONTAINS 'needle' " +
                "RETURN a.value AS source, b.value AS target LIMIT 10"
        )

        assertEquals(listOf(mapOf("source" to "Needle", "target" to "Target")), result.rows)
        assertEquals(listOf(NodeId(1)), accessed)
    }

    @Test
    fun `unsupported predicate functions keep their original evaluation error`() {
        val expression = CypherExpr.PredicateFunction(
            "unsupported", "item", CypherExpr.ListLiteral(listOf(CypherExpr.Literal(true))), null
        )
        val failure = assertFailsWith<CypherException> { ExpressionEvaluator().evaluate(expression, emptyMap()) }
        assertEquals("Unknown predicate function: unsupported", failure.message)
    }

    @Test
    fun `interruption while reading fallback candidates cancels before evaluating the node`() {
        val node = StringConstant(NodeId(1), "needle")
        val backing = DefaultGraph.Builder().addNode(node).build()
        val graph = object : Graph by backing {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> = sequence {
                Thread.currentThread().interrupt()
                yield(type.cast(node))
                error("Cancellation must prevent reading another candidate")
            }
        }
        try {
            assertFailsWith<CypherQueryCancelledException> {
                CypherExecutor(graph).execute("MATCH (n) WHERE n.value CONTAINS 'needle' RETURN n.id LIMIT 10")
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `resource dynamic access preserves missing values and global type fallback`() {
        val graph = DefaultGraph.Builder()
            .addNode(ResourceFileNode(NodeId(1), "file", "source", "text"))
            .addNode(ResourceValueNode(NodeId(2), "file", "key", "value", "text"))
            .build()
        val result = CypherExecutor(graph).execute(
            "MATCH (n) RETURN n.id AS id, n['missing'] AS missing, n['type'] AS type ORDER BY id"
        )

        assertEquals(
            listOf(
                mapOf("id" to 1, "missing" to null, "type" to "ResourceFileNode"),
                mapOf("id" to 2, "missing" to null, "type" to "ResourceValueNode")
            ),
            result.rows
        )
    }

    private fun relationshipGraph(accessed: MutableList<NodeId>): Graph {
        val nodes = listOf(
            StringConstant(NodeId(1), "Needle"), StringConstant(NodeId(2), "Other"), StringConstant(NodeId(3), "Target")
        )
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }
            .addEdge(DataFlowEdge(NodeId(1), NodeId(3), DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(NodeId(2), NodeId(3), DataFlowKind.ASSIGN))
            .build()
        return object : Graph by backing {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                nodes.asSequence().filter(type::isInstance).map(type::cast)

            override fun outgoing(id: NodeId): Sequence<Edge> {
                accessed += id
                return backing.outgoing(id)
            }

            override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> {
                accessed += id
                return backing.outgoing(id, type)
            }
        }
    }
}
