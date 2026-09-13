package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.NodeIdCandidateLookup
import java.util.function.IntPredicate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QualifiedIdContainsTest {

    @Test
    fun `ID candidates preserve source order labels projections and literal parameter semantics`() {
        val operands = listOf(
            "qualifiedId" to "'12'", "qualifiedId" to "\$term", "elementId" to "'12'", "elementId" to "\$term"
        )
        val cases = operands.flatMap { (property, term) ->
            listOf("", ":StringConstant").map { label -> Triple(property, term, label) }
        }
        for ((property, term, label) in cases) {
            val first = CandidateGraph(fixture(), allowScan = false)
            val second = CandidateGraph(fixture(), allowScan = false)
            val sources = listOf("alpha" to first, "beta" to second)
            val query = "MATCH (n$label) WHERE n.$property CONTAINS $term " +
                "RETURN n.id AS id, n.value AS value, n.qualifiedId AS qualified LIMIT 4"
            val result = cross(sources).execute(query, mapOf("term" to "12"))

            assertEquals(reference(sources).execute(query, mapOf("term" to "12")).rows, result.rows)
            assertEquals(listOf(312, 12, 121, 312), result.rows.map { it["id"] })
            assertEquals(listOf("alpha:312", "alpha:12", "alpha:121", "beta:312"), result.rows.map { it["qualified"] })
            assertEquals(0, first.scans + second.scans)
            assertEquals(listOf(312, 12, 121), first.materialized)
            assertEquals(listOf(312), second.materialized)
            val expectedType = if (label.isEmpty()) Node::class.java else StringConstant::class.java
            assertEquals(listOf(expectedType), first.requestedTypes)
        }
    }

    @Test
    fun `namespace boundary and sparse signed IDs use exact qualified strings`() {
        val nodes = listOf(0, Int.MAX_VALUE, -1, 12, Int.MIN_VALUE).map { StringConstant(NodeId(it), "value") }
        val sources = listOf("zone:x:" to CandidateGraph(nodes), "" to CandidateGraph(nodes))
        for (term in listOf("x::12", ":-", "2147483648", "2147483647", ":0", "missing", "")) {
            val query = "MATCH (n) WHERE n.qualifiedId CONTAINS \$term RETURN n.qualifiedId AS id LIMIT 50"
            val parameters = mapOf("term" to term)
            assertEquals(reference(sources).execute(query, parameters).rows, cross(sources).execute(query, parameters).rows, term)
        }
        val result = cross(sources).execute(
            "MATCH (n) WHERE n.qualifiedId CONTAINS 'x::12' RETURN n.qualifiedId AS id LIMIT 1"
        )
        assertEquals(listOf("zone:x::12"), result.rows.map { it["id"] })
    }

    @Test
    fun `namespace hits bypass ID lookup and missing capabilities retain the ordinary scan`() {
        val prefix = CandidateGraph(fixture())
        val prefixQuery = "MATCH (n) WHERE n.qualifiedId CONTAINS 'alpha:' RETURN n.id AS id LIMIT 2"
        val prefixSources = listOf("alpha" to prefix)
        assertEquals(reference(prefixSources).execute(prefixQuery).rows, cross(prefixSources).execute(prefixQuery).rows)
        assertTrue(prefix.scans > 0)
        assertTrue(prefix.requestedTypes.isEmpty())

        val unsupported = CandidateGraph(fixture(), returnNull = true)
        val sources = listOf("alpha" to unsupported)
        val query = "MATCH (n) WHERE n.qualifiedId CONTAINS '12' RETURN n.id AS id LIMIT 2"
        assertEquals(reference(sources).execute(query).rows, cross(sources).execute(query).rows)
        assertEquals<List<Class<out Node>>>(listOf(Node::class.java), unsupported.requestedTypes)
        assertTrue(unsupported.scans > 0)
    }

    @Test
    fun `plain annotation metadata and qualified collisions retain their different accessor semantics`() {
        val nodes = listOf(
            StringConstant(NodeId(12), "ordinary"),
            AnnotationNode(NodeId(42), "Example", "Owner", "member", mapOf("qualifiedId" to "custom-12")),
            AnnotationNode(NodeId(43), "Example", "Owner", "member", mapOf("qualifiedId" to 12)),
            AnnotationNode(NodeId(44), "Example", "Owner", "member", mapOf("qualifiedId" to null))
        )
        val graph = CandidateGraph(nodes)
        val query = "MATCH (n) WHERE n.qualifiedId CONTAINS '12' RETURN n.id AS id LIMIT 10"
        assertEquals(listOf(mapOf("id" to 42)), CypherExecutor(graph).execute(query).rows)
        assertTrue(graph.requestedTypes.isEmpty())
        assertEquals(listOf(12), cross(listOf("alpha" to graph)).execute(query).rows.map { it["id"] })
        val collision = cross(listOf("alpha" to graph)).execute(
            "MATCH (n:AnnotationNode) WHERE n.qualifiedId CONTAINS ':42' " +
                "RETURN n.qualifiedId AS direct, n['qualifiedId'] AS dynamic, properties(n).qualifiedId AS mapped LIMIT 1"
        )
        assertEquals(listOf("alpha:42"), collision.rows.map { it["direct"] })
        assertEquals(collision.rows.map { it["direct"] }, collision.rows.map { it["dynamic"] })
        assertEquals(collision.rows.map { it["direct"] }, collision.rows.map { it["mapped"] })
    }

    @Test
    fun `distinct ordering counts and short limits agree with independent ordinary execution`() {
        val sources = listOf("alpha" to CandidateGraph(fixture()), "beta" to CandidateGraph(fixture()))
        val prefix = "MATCH (n) WHERE n.qualifiedId CONTAINS '12' "
        val suffixes = listOf(
            "RETURN DISTINCT n.value AS value LIMIT 2",
            "RETURN n.qualifiedId AS id ORDER BY id SKIP 1 LIMIT 3",
            "RETURN count(*) AS total, count(n.qualifiedId) AS ids, count(DISTINCT n.qualifiedId) AS distinctIds",
            "RETURN n.qualifiedId AS id LIMIT 0"
        )
        for (suffix in suffixes) {
            assertEquals(reference(sources).execute(prefix + suffix).rows, cross(sources).execute(prefix + suffix).rows, suffix)
        }
        val distinct = cross(sources).execute(prefix + suffixes.first())
        assertEquals(listOf("duplicate", "later"), distinct.rows.map { it["value"] })
        assertEquals(
            listOf(listOf("alpha", "beta"), listOf("alpha", "beta")),
            distinct.rows.map { (it[RESULT_METADATA_KEY] as Map<*, *>)[RESULT_GRAPH_IDS_KEY] }
        )
    }

    @Test
    fun `unbounded ordered and aggregate queries inspect IDs without scanning ordinary nodes`() {
        val prefix = "MATCH (n) WHERE n.qualifiedId CONTAINS '12' "
        val suffixes = listOf(
            "RETURN n.qualifiedId AS id",
            "RETURN n.qualifiedId AS id ORDER BY id",
            "RETURN n.qualifiedId AS id ORDER BY id SKIP 1 LIMIT 3",
            "RETURN count(*) AS total, count(n.qualifiedId) AS ids, count(DISTINCT n.qualifiedId) AS distinctIds",
            "RETURN n.value AS value, count(*) AS total ORDER BY value"
        )
        val expectedValues = listOf(
            listOf("alpha:312", "alpha:12", "alpha:121", "beta:312", "beta:12", "beta:121"),
            listOf("alpha:12", "alpha:121", "alpha:312", "beta:12", "beta:121", "beta:312"),
            listOf("alpha:121", "alpha:312", "beta:12"),
            listOf(6L),
            listOf(4L, 2L)
        )
        for ((index, suffix) in suffixes.withIndex()) {
            val first = CandidateGraph(fixture(), allowScan = false)
            val second = CandidateGraph(fixture(), allowScan = false)
            val sources = listOf("alpha" to first, "beta" to second)
            val result = cross(sources).execute(prefix + suffix)
            assertEquals(reference(sources).execute(prefix + suffix).rows, result.rows, suffix)
            assertEquals(expectedValues[index], result.rows.map { it[if (index < 3) "id" else "total"] }, suffix)
            assertEquals(0, first.scans + second.scans)
            assertEquals(listOf(312, 12, 121), first.materialized)
            assertEquals(listOf(312, 12, 121), second.materialized)
            assertEquals<List<Class<out Node>>>(listOf(Node::class.java), first.requestedTypes)
            assertEquals<List<Class<out Node>>>(listOf(Node::class.java), second.requestedTypes)
        }
    }

    @Test
    fun `later matches inline properties and complex predicates retain ordinary candidate semantics`() {
        val queries = listOf(
            "WITH 'seed' AS seed MATCH (n) WHERE n.qualifiedId CONTAINS '12' " +
                "RETURN seed, n.id AS id ORDER BY id",
            "MATCH (n {value: 'duplicate'}) WHERE n.qualifiedId CONTAINS '12' RETURN n.id AS id ORDER BY id",
            "MATCH (n) WHERE n.qualifiedId CONTAINS '12' OR n.value = 7 RETURN n.id AS id ORDER BY id"
        )
        val expectedIds = listOf(listOf(12, 121, 312), listOf(12, 312), listOf(9, 12, 121, 312))
        for ((index, query) in queries.withIndex()) {
            val graph = CandidateGraph(fixture())
            val sources = listOf("alpha" to graph)
            val result = cross(sources).execute(query)
            assertEquals(reference(sources).execute(query).rows, result.rows)
            assertEquals(expectedIds[index], result.rows.map { it["id"] })
            assertTrue(graph.requestedTypes.isEmpty())
            assertTrue(graph.scans > 0)
        }
    }

    @Test
    fun `unsupported terms owners and inline constraint errors keep ordinary evaluation`() {
        for (term in listOf(null, 12, true)) {
            val graph = CandidateGraph(fixture())
            val result = cross(listOf("alpha" to graph)).execute(
                "MATCH (n) WHERE n.qualifiedId CONTAINS \$term RETURN n.id AS id LIMIT 1", mapOf("term" to term)
            )
            assertTrue(result.rows.isEmpty())
            assertTrue(graph.requestedTypes.isEmpty())
        }
        val graph = CandidateGraph(fixture())
        assertTrue(cross(listOf("alpha" to graph)).execute(
            "MATCH (n) WHERE other.qualifiedId CONTAINS '12' RETURN n.id AS id LIMIT 1"
        ).rows.isEmpty())
        assertTrue(graph.requestedTypes.isEmpty())
        assertFailsWith<CypherException> {
            cross(listOf("alpha" to graph)).execute(
                "MATCH (n {value: unknownFunction()}) WHERE n.qualifiedId CONTAINS 'missing' RETURN n.id LIMIT 1"
            )
        }
        assertTrue(graph.requestedTypes.isEmpty())
    }

    @Test
    fun `method identity functions do not make qualifiedId a method property`() {
        val method = MethodDescriptor(TypeDescriptor("Example"), "run", emptyList(), TypeDescriptor("void"))
        val backing = DefaultGraph.Builder().addMethod(method).build()
        val graph = object : Graph by backing, NodeIdCandidateLookup {
            override fun <T : Node> nodesMatchingId(
                type: Class<T>, predicate: IntPredicate, workConsumer: GraphWorkConsumer?
            ): Sequence<T>? = error("Method metadata must not use physical node IDs")
        }
        val executor = CrossGraphCypherExecutor(listOf(CypherGraph("alpha", graph)))
        assertTrue(executor.execute(
            "MATCH (n:Method) WHERE n.qualifiedId CONTAINS 'alpha' RETURN n.name LIMIT 1"
        ).rows.isEmpty())
        val result = executor.execute(
            "MATCH (n:Method) RETURN n.qualifiedId AS property, qualifiedId(n) AS identity LIMIT 1"
        )
        assertEquals(listOf(null), result.rows.map { it["property"] })
        assertEquals(listOf("alpha:Method:${method.signature}"), result.rows.map { it["identity"] })
    }

    @Test
    fun `ID rejection remains budgeted and cancellable before materialization`() {
        val rejected = listOf(StringConstant(NodeId(8), "a"), StringConstant(NodeId(9), "b"))
        val graph = CandidateGraph(rejected, allowScan = false)
        assertFailsWith<CypherBudgetExceededException> {
            CrossGraphCypherExecutor(listOf(CypherGraph("alpha", graph)), CypherExecutionBudget(1)).execute(
                "MATCH (n) WHERE n.qualifiedId CONTAINS '12' RETURN n.id LIMIT 1"
            )
        }
        assertNotNull(graph.consumer)
        assertTrue(graph.materialized.isEmpty())
        val signal = CypherCancellationSignal()
        val cancelled = CandidateGraph(rejected, allowScan = false, onInspect = { signal.cancel() })
        assertFailsWith<CypherQueryCancelledException> {
            CrossGraphCypherExecutor(
                listOf(CypherGraph("alpha", cancelled)), CypherExecutionContext(CypherExecutionBudget(100), signal)
            ).execute("MATCH (n) WHERE n.qualifiedId CONTAINS '12' RETURN n.id LIMIT 1")
        }
        assertNotNull(cancelled.consumer)
        assertTrue(cancelled.materialized.isEmpty())
    }

    private fun fixture(): List<Node> = listOf(
        StringConstant(NodeId(312), "duplicate"), IntConstant(NodeId(9), 7),
        StringConstant(NodeId(12), "duplicate"), StringConstant(NodeId(121), "later")
    )

    private fun cross(sources: List<Pair<String, CandidateGraph>>) =
        CrossGraphCypherExecutor(sources.map { (name, graph) -> CypherGraph(name, graph) })

    private fun reference(sources: List<Pair<String, CandidateGraph>>) =
        CrossGraphCypherExecutor(sources.map { (name, graph) -> CypherGraph(name, ordinary(graph.sourceNodes)) })

    private fun ordinary(nodes: List<Node>): Graph {
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
        return object : Graph by backing {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                nodes.asSequence().filter(type::isInstance).map(type::cast)
        }
    }

    private inner class CandidateGraph(
        val sourceNodes: List<Node>,
        private val allowScan: Boolean = true,
        private val returnNull: Boolean = false,
        private val onInspect: () -> Unit = {}
    ) : Graph by ordinary(sourceNodes), NodeIdCandidateLookup {
        var scans = 0
        val requestedTypes = mutableListOf<Class<out Node>>()
        val materialized = mutableListOf<Int>()
        var consumer: GraphWorkConsumer? = null

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            check(allowScan) { "Qualified ID candidates unexpectedly scanned ordinary nodes" }
            scans++
            return sourceNodes.asSequence().filter(type::isInstance).map(type::cast)
        }

        override fun <T : Node> nodesMatchingId(
            type: Class<T>, predicate: IntPredicate, workConsumer: GraphWorkConsumer?
        ): Sequence<T>? {
            requestedTypes += type
            consumer = workConsumer
            return if (returnNull) null else sequence {
                for (node in sourceNodes) {
                    if (!type.isInstance(node)) continue
                    onInspect()
                    workConsumer?.consume()
                    if (predicate.test(node.id.value)) {
                        materialized += node.id.value
                        yield(type.cast(node))
                    }
                }
            }
        }
    }
}
