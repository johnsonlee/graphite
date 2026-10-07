package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.Test
import kotlin.test.assertEquals

class DistinctInlineWhereTest {
    private val graph = orderedGraph(
        call(0, "drop", "rejected"), call(1, "keep", "shared"),
        call(2, "keep", "shared"), call(3, "keep", "other")
    )

    @Test
    fun `inline where filters before distinct and limit just like an explicit where clause`() {
        for (limit in listOf(0, 1, 2, 10)) {
            val expected = listOf(mapOf("callee" to "shared"), mapOf("callee" to "other")).take(limit)
            val inline = QueryPipeline(graph).execute(query(predicate("keep"), limit))
            val explicit = QueryPipeline(graph).execute(explicitQuery(predicate("keep"), limit))
            assertEquals(listOf("callee"), inline.columns)
            assertEquals(expected, inline.rows)
            assertEquals(listOf("callee"), explicit.columns)
            assertEquals(expected, explicit.rows)
        }
    }

    @Test
    fun `inline predicate with no matches cannot leak an unfiltered distinct value`() {
        for (clauses in listOf(query(predicate("missing")), explicitQuery(predicate("missing")))) {
            val result = QueryPipeline(graph).execute(clauses)
            assertEquals(listOf("callee"), result.columns)
            assertEquals(emptyList(), result.rows)
        }
    }

    @Test
    fun `qualified distinct provenance contains only graphs with matching contributors`() {
        val sources = listOf(
            CypherGraph("one", graph),
            CypherGraph("excluded", orderedGraph(call(0, "drop", "shared"))),
            CypherGraph("three", orderedGraph(call(0, "keep", "shared")))
        )
        val expected = listOf(mapOf("callee" to "shared", INTERNAL_PROVENANCE_KEY to setOf("one", "three")))
        for (clauses in listOf(query(predicate("keep")), explicitQuery(predicate("keep")))) {
            val result = QueryPipeline(sources).execute(clauses)
            assertEquals(listOf("callee"), result.columns)
            assertEquals(expected, result.rows)
        }
    }

    @Test
    fun `unfiltered distinct keeps encounter order and removes repeated values`() {
        val result = QueryPipeline(graph).execute(query(limit = 10))
        assertEquals(listOf("callee"), result.columns)
        assertEquals(listOf("rejected", "shared", "other").map { mapOf("callee" to it) }, result.rows)
        assertEquals(listOf(mapOf("callee" to "rejected")), QueryPipeline(graph).execute(query()).rows)
    }

    @Test
    fun `optional inline where retains matched values and null fills an unmatched pattern`() {
        val matched = QueryPipeline(graph).execute(query(predicate("keep"), optional = true))
        assertEquals(listOf("callee"), matched.columns)
        assertEquals(listOf(mapOf("callee" to "shared")), matched.rows)
        val unmatched = QueryPipeline(graph).execute(query(predicate("missing"), optional = true))
        assertEquals(listOf("callee"), unmatched.columns)
        assertEquals(listOf(mapOf("callee" to null)), unmatched.rows)
    }

    private fun query(where: CypherExpr? = null, limit: Int = 1, optional: Boolean = false): List<CypherClause> = listOf(
        CypherClause.Match(
            listOf(CypherPattern(listOf(PatternElement.NodePattern("n", listOf("CallSiteNode"))))),
            optional = optional,
            where = where
        ),
        CypherClause.Return(listOf(ReturnItem(property("callee_name"), "callee")), distinct = true),
        CypherClause.Limit(CypherExpr.Literal(limit))
    )

    private fun explicitQuery(where: CypherExpr, limit: Int = 1): List<CypherClause> =
        query(limit = limit).toMutableList().apply { add(1, CypherClause.Where(where)) }

    private fun predicate(caller: String) = CypherExpr.Comparison("=", property("caller_name"), CypherExpr.Literal(caller))

    private fun property(name: String) = CypherExpr.Property(CypherExpr.Variable("n"), name)

    private fun call(id: Int, caller: String, callee: String) = CallSiteNode(
        NodeId(id), MethodDescriptor(TypeDescriptor("Caller"), caller, emptyList(), TypeDescriptor("void")),
        MethodDescriptor(TypeDescriptor("Callee"), callee, emptyList(), TypeDescriptor("void")), id, null, emptyList()
    )

    private fun orderedGraph(vararg calls: CallSiteNode): Graph {
        val delegate = DefaultGraph.Builder().apply { calls.forEach { addNode(it) } }.build()
        return object : Graph by delegate {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                calls.asSequence().filter(type::isInstance).map(type::cast)
        }
    }
}
