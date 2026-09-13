package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.NodePropertyTextCandidates
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicPropertyCandidatesTest {

    @Test
    fun `literal and parameter candidates preserve fields and recheck false positives`() {
        for (label in listOf("", ":StringConstant")) {
            for (term in listOf("'needle-tail'", "\$term")) {
                val graph = spy(allowScan = false)
                val result = CypherExecutor(graph).execute(
                    "MATCH (n$label) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS $term) " +
                        "RETURN n.id AS id, n.value AS value LIMIT 2",
                    mapOf("term" to "needle-tail")
                )

                assertEquals(
                    listOf(mapOf("id" to 3, "value" to "needle-tail"), mapOf("id" to 4, "value" to "needle-tail")),
                    result.rows
                )
                assertEquals(listOf("needle"), graph.fragments)
                assertEquals(listOf(1, 3, 4), graph.yieldedIds)
                assertEquals(listOf(if (label.isEmpty()) Node::class.java else StringConstant::class.java), graph.requestedTypes)
                assertEquals(0, graph.scans)
            }
        }
    }

    @Test
    fun `candidate order limit distinct and provenance span graphs correctly`() {
        val first = spy(allowScan = false)
        val second = spy(allowScan = false)
        val executor = CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
        val result = executor.execute(
            "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle-tail') " +
                "RETURN n.graphId AS graph, n.id AS id, n.value AS value LIMIT 4"
        )

        assertEquals(
            listOf(
                row("first", 3, "needle-tail"), row("first", 4, "needle-tail"),
                row("first", 5, "after needle-tail"), row("second", 3, "needle-tail")
            ),
            result.rows
        )
        val distinct = executor.execute(
            "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle-tail') " +
                "RETURN DISTINCT n.value AS value LIMIT 1"
        )
        assertEquals(
            listOf(mapOf("value" to "needle-tail", RESULT_METADATA_KEY to mapOf(RESULT_GRAPH_IDS_KEY to listOf("first", "second")))),
            distinct.rows
        )
        assertEquals(0, first.scans)
        assertEquals(0, second.scans)
    }

    @Test
    fun `graph and qualified id matches bypass storage filtering only for matching source`() {
        for ((term, expectedIds) in listOf("GraphNeedle" to listOf(1, 2), "GraphNeedle:3" to listOf(3))) {
            val metadataSource = spy()
            val otherSource = spy(allowScan = false)
            val result = CrossGraphCypherExecutor(
                listOf(CypherGraph("prefixGraphNeedle", metadataSource), CypherGraph("other", otherSource))
            ).execute(
                "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS \$term) " +
                    "RETURN n.graphId AS graph, n.id AS id LIMIT 2",
                mapOf("term" to term)
            )

            assertEquals(expectedIds, result.rows.map { it["id"] })
            assertTrue(result.rows.all { it["graph"] == "prefixGraphNeedle" })
            assertTrue(result.rows.all {
                it[RESULT_METADATA_KEY] == mapOf(RESULT_GRAPH_IDS_KEY to listOf("prefixGraphNeedle"))
            })
            assertEquals(emptyList(), metadataSource.fragments)
            assertEquals(1, metadataSource.scans)
            assertEquals(0, otherSource.scans)
            if (term.endsWith(":3")) assertEquals(listOf("GraphNeedle"), otherSource.fragments)
        }
    }

    @Test
    fun `numeric scientific unicode and nonstring terms keep normal evaluation`() {
        for ((term, expectedIds) in listOf(
            "105873" to listOf(2), "1.0E7" to listOf(7), "中文" to listOf(6),
            null to emptyList(), 123 to emptyList()
        )) {
            val graph = spy()
            val result = CypherExecutor(graph).execute(
                "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS \$term) RETURN n.id AS id LIMIT 10",
                mapOf("term" to term)
            )

            assertEquals(expectedIds, result.rows.map { it["id"] }, term.toString())
            assertEquals(emptyList(), graph.fragments)
            assertTrue(graph.scans > 0)
        }
    }

    @Test
    fun `wrong owners and incoming row terms retain scope and bypass storage`() {
        val graph = spy()
        val executor = CypherExecutor(graph)
        assertEquals(
            emptyList(),
            executor.execute(
                "MATCH (n) WHERE any(k IN keys(other) WHERE toString(other[k]) CONTAINS 'needle-tail') " +
                    "RETURN n.id AS id LIMIT 2"
            ).rows
        )
        assertEquals(
            listOf(mapOf("term" to "needle-tail", "id" to 3), mapOf("term" to "needle-tail", "id" to 4)),
            executor.execute(
                "WITH 'needle-tail' AS term MATCH (n) " +
                    "WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS term) RETURN term, n.id AS id LIMIT 2"
            ).rows
        )
        assertEquals(emptyList(), graph.fragments)
        assertTrue(graph.scans > 0)
    }

    @Test
    fun `inline node properties retain filtering and errors before any pruning`() {
        val graph = spy(candidateIds = emptySet())
        val executor = CypherExecutor(graph)
        assertEquals(
            listOf(mapOf("id" to 5)),
            executor.execute(
                "MATCH (n {value: 'after needle-tail'}) " +
                    "WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle-tail') RETURN n.id AS id LIMIT 2"
            ).rows
        )
        val failure = assertFailsWith<CypherException> {
            executor.execute(
                "MATCH (n {value: missingFunction()}) " +
                    "WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle-tail') RETURN n.id AS id LIMIT 2"
            )
        }
        assertTrue(failure.message.orEmpty().contains("Unknown function"))
        assertEquals(emptyList(), graph.fragments)
        assertTrue(graph.scans > 0)
    }

    @Test
    fun `null capability result falls back to the complete node scan`() {
        val graph = spy(returnNull = true)
        val result = CypherExecutor(graph).execute(
            "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle-tail') RETURN n.id AS id LIMIT 2"
        )

        assertEquals(listOf(3, 4), result.rows.map { it["id"] })
        assertEquals(listOf("needle"), graph.fragments)
        assertEquals(1, graph.scans)
        assertEquals(emptyList(), graph.yieldedIds)
    }

    @Test
    fun `storage candidates receive cancellation and work accounting`() {
        val signal = CypherCancellationSignal()
        val graph = spy(allowScan = false, onCandidate = { signal.cancel() })
        val executor = CypherExecutor(graph, CypherExecutionContext(CypherExecutionBudget(100), signal))
        assertFailsWith<CypherQueryCancelledException> {
            executor.execute(
                "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle-tail') RETURN n.id AS id LIMIT 2"
            )
        }
        assertNotNull(graph.workConsumer)
        assertEquals(listOf("needle"), graph.fragments)
        assertEquals(0, graph.scans)

        val budgetGraph = spy(allowScan = false)
        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(budgetGraph, CypherExecutionBudget(1)).execute(
                "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle-tail') RETURN n.id AS id LIMIT 2"
            )
        }
        assertNotNull(budgetGraph.workConsumer)
        assertEquals(0, budgetGraph.scans)
    }

    private fun row(source: String, id: Int, value: String): Map<String, Any> = mapOf(
        "graph" to source, "id" to id, "value" to value,
        RESULT_METADATA_KEY to mapOf(RESULT_GRAPH_IDS_KEY to listOf(source))
    )

    private fun spy(
        allowScan: Boolean = true,
        returnNull: Boolean = false,
        candidateIds: Set<Int> = setOf(1, 3, 4, 5),
        onCandidate: () -> Unit = {}
    ): CandidateGraph {
        val nodes = listOf(
            StringConstant(NodeId(1), "needle-other"), IntConstant(NodeId(2), 105873),
            StringConstant(NodeId(3), "needle-tail"), StringConstant(NodeId(4), "needle-tail"),
            StringConstant(NodeId(5), "after needle-tail"), StringConstant(NodeId(6), "仅中文"),
            DoubleConstant(NodeId(7), 1.0E7)
        )
        val backing = DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build()
        return CandidateGraph(backing, nodes, allowScan, returnNull, candidateIds, onCandidate)
    }

    private class CandidateGraph(
        backing: Graph,
        val sourceNodes: List<Node>,
        private val allowScan: Boolean,
        private val returnNull: Boolean,
        private val candidateIds: Set<Int>,
        private val onCandidate: () -> Unit
    ) : Graph by backing, NodePropertyTextCandidates {
        val fragments = mutableListOf<String>()
        val requestedTypes = mutableListOf<Class<out Node>>()
        val yieldedIds = mutableListOf<Int>()
        var scans = 0
        var workConsumer: GraphWorkConsumer? = null

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
            check(allowScan) { "Dynamic property candidates unexpectedly scanned ordinary nodes" }
            scans++
            return sourceNodes.asSequence().filter(type::isInstance).map(type::cast)
        }

        override fun <T : Node> propertyTextCandidates(
            type: Class<T>,
            fragment: String,
            workConsumer: GraphWorkConsumer?
        ): Sequence<T>? {
            fragments += fragment
            requestedTypes += type
            this.workConsumer = workConsumer
            return if (returnNull) null else sequence {
                for (node in sourceNodes) {
                    if (node.id.value !in candidateIds || !type.isInstance(node)) continue
                    onCandidate()
                    workConsumer?.consume()
                    yieldedIds += node.id.value
                    yield(type.cast(node))
                }
            }
        }
    }
}
