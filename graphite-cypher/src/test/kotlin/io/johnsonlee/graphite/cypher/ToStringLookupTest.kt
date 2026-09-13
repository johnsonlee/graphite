package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToStringLookupTest {

    @Test
    fun `string properties wrapped in toString use lookup and preserve source order`() {
        for (property in listOf("caller_class", "caller_name", "callee_class", "callee_name")) {
            for (label in listOf("", ":CallSiteNode")) {
                val backing = fixture()
                val graph = LookupSpy(backing, allowScan = false)
                val result = CypherExecutor(graph).execute(
                    "MATCH (n$label) WHERE toString(n.$property) CONTAINS 'Target' " +
                        "RETURN n.line AS line LIMIT 10"
                )

                val expected = referenceLines(backing, property, "Target")
                assertEquals(setOf(10, 20, 30), expected.toSet())
                assertEquals(expected, result.rows.map { it["line"] }, "$label $property")
                assertEquals(
                    setOf(StringPropertyPredicate(property, null, StringMatchMode.CONTAINS, "Target")),
                    graph.predicates.toSet()
                )
            }
        }
    }

    @Test
    fun `parameterized toString lookup excludes missing properties even for empty terms`() {
        for ((term, lines) in listOf("Target" to setOf(10, 20, 30), "" to setOf(10, 20, 30, 40))) {
            val backing = fixture()
            val graph = LookupSpy(backing, allowScan = false)
            val result = CypherExecutor(graph).execute(
                "MATCH (n) WHERE toString(n.caller_class) CONTAINS \$term " +
                    "RETURN n.line AS line LIMIT 10",
                mapOf("term" to term)
            )

            val expected = referenceLines(backing, "caller_class", term)
            assertEquals(lines, expected.toSet())
            assertEquals(expected, result.rows.map { it["line"] })
            assertEquals(
                setOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, term)),
                graph.predicates.toSet()
            )
        }
    }

    @Test
    fun `missing and null string operands do not become the text null`() {
        val graph = LookupSpy(fixture())
        val executor = CypherExecutor(graph)

        assertEquals(
            emptyList(),
            executor.execute(
                "MATCH (n:IntConstant) WHERE toString(n.caller_class) CONTAINS '' RETURN n.value AS value"
            ).rows
        )
        assertEquals(
            emptyList(),
            executor.execute(
                "MATCH (n) WHERE toString(n.caller_class) CONTAINS \$term RETURN n.line AS line LIMIT 10",
                mapOf("term" to null)
            ).rows
        )
    }

    @Test
    fun `toString lookup preserves distinct ordering and pagination`() {
        val backing = fixture()
        val graph = LookupSpy(backing, allowScan = false)
        val query = "MATCH (n) WHERE toString(n.caller_class) CONTAINS 'Target' " +
            "RETURN DISTINCT n.caller_class AS caller ORDER BY caller DESC SKIP 1 LIMIT 1"
        val result = CypherExecutor(graph).execute(query)

        assertEquals(listOf(mapOf("caller" to "AlphaTarget")), result.rows)
        assertEquals(CypherExecutor(backing).execute(query).rows, result.rows)
        assertTrue(graph.predicates.isNotEmpty())
    }

    @Test
    fun `toString lookup preserves graph provenance when local node ids overlap`() {
        val first = LookupSpy(fixture(), allowScan = false)
        val second = LookupSpy(fixture(), allowScan = false)
        val result = CrossGraphCypherExecutor(
            listOf(CypherGraph("first", first), CypherGraph("second", second))
        ).execute(
            "MATCH (n) WHERE toString(n.caller_class) CONTAINS 'Alpha' " +
                "RETURN n.graphId AS graph, n.qualifiedId AS id, n.line AS line LIMIT 10"
        )

        assertEquals(
            listOf(
                mapOf(
                    "graph" to "first", "id" to "first:12", "line" to 20,
                    RESULT_METADATA_KEY to mapOf(RESULT_GRAPH_IDS_KEY to listOf("first"))
                ),
                mapOf(
                    "graph" to "second", "id" to "second:12", "line" to 20,
                    RESULT_METADATA_KEY to mapOf(RESULT_GRAPH_IDS_KEY to listOf("second"))
                )
            ),
            result.rows
        )
        assertTrue(first.predicates.isNotEmpty())
        assertTrue(second.predicates.isNotEmpty())
    }

    @Test
    fun `numeric values retain toString conversion through fallback`() {
        val graph = LookupSpy(fixture())
        val result = CypherExecutor(graph).execute(
            "MATCH (n) WHERE toString(n.value) CONTAINS '587' RETURN n.value AS value LIMIT 10"
        )

        assertEquals(listOf(mapOf("value" to 105873)), result.rows)
        assertEquals(emptyList(), graph.predicates)
        assertTrue(graph.scans > 0)
    }

    @Test
    fun `wrong variable arity and distinct modifier cannot enter the string lookup`() {
        val backing = fixture()
        val matchingLines = referenceLines(backing, "caller_class", "Target")
        val property = CypherExpr.Property(CypherExpr.Variable("n"), "caller_class")
        val cases = listOf(
            CypherExpr.FunctionCall(
                "toString", listOf(CypherExpr.Property(CypherExpr.Variable("other"), "caller_class"))
            ) to emptyList(),
            CypherExpr.FunctionCall("toString", listOf(property, CypherExpr.Literal("ignored"))) to matchingLines,
            CypherExpr.FunctionCall("toString", listOf(property), distinct = true) to matchingLines
        )
        for ((operand, expected) in cases) {
            val graph = LookupSpy(backing)
            val result = QueryPipeline(graph).execute(
                listOf(
                    CypherClause.Match(listOf(CypherPattern(listOf(PatternElement.NodePattern("n", emptyList()))))),
                    CypherClause.Where(CypherExpr.StringOp("CONTAINS", operand, CypherExpr.Literal("Target"))),
                    CypherClause.Return(
                        listOf(ReturnItem(CypherExpr.Property(CypherExpr.Variable("n"), "line"), "line"))
                    ),
                    CypherClause.Limit(CypherExpr.Literal(10))
                )
            )

            assertEquals(expected, result.rows.map { it["line"] }, operand.toString())
            assertEquals(emptyList(), graph.predicates, operand.toString())
            assertTrue(graph.scans > 0)
        }
    }

    private fun referenceLines(graph: Graph, property: String, term: String): List<Int?> =
        graph.nodes(CallSiteNode::class.java).filter { node ->
            val value = when (property) {
                "caller_class" -> node.caller.declaringClass.className
                "caller_name" -> node.caller.name
                "callee_class" -> node.callee.declaringClass.className
                "callee_name" -> node.callee.name
                else -> error("Unsupported reference property: $property")
            }
            value.contains(term)
        }.map { it.lineNumber }.toList()

    private fun fixture(): Graph {
        val builder = DefaultGraph.Builder()
        builder.addNode(IntConstant(NodeId(1), 105873))
        builder.addNode(StringConstant(NodeId(2), "unrelated"))
        for ((index, entry) in listOf("ZetaTarget" to 30, "ZetaTarget" to 10, "AlphaTarget" to 20, "Other" to 40).withIndex()) {
            val (name, line) = entry
            val method = MethodDescriptor(TypeDescriptor(name), name, emptyList(), TypeDescriptor("void"))
            builder.addNode(CallSiteNode(NodeId(10 + index), method, method, line, null, emptyList()))
        }
        return builder.build()
    }

    private class LookupSpy(
        private val backing: Graph,
        private val allowScan: Boolean = true
    ) : Graph by backing, StringPropertyDisjunctionLookup {
        val predicates = mutableListOf<StringPropertyPredicate>()
        var scans = 0

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            check(allowScan) { "toString string predicate unexpectedly scanned nodes" }
            scans++
            return backing.nodes(type)
        }

        override fun <T : Node> nodesByStringPropertyDisjunction(
            type: Class<T>,
            predicates: List<StringPropertyPredicate>,
            limit: Int
        ): Sequence<T> {
            this.predicates += predicates
            assertTrue(predicates.all { it.mode == StringMatchMode.CONTAINS && it.transform == null })
            return backing.nodes(type).filter { node ->
                predicates.any { predicate ->
                    val value = NodePropertyAccessor.getProperty(node, predicate.property) as? String
                    value != null && value.contains(predicate.expected)
                }
            }.take(limit)
        }
    }
}
