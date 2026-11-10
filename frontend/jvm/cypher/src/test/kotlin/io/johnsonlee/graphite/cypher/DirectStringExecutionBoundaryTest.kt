package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DirectStringExecutionBoundaryTest {
    private val node = StringConstant(NodeId(1), "needle")
    private val graph = DefaultGraph.Builder().addNode(node).build()
    private val sources = listOf(CypherGraph("a", graph), CypherGraph("b", graph))
    private val context = PipelineExecutionContext(null, false)
    private val access = PipelineGraphAccess(sources, true, context)
    private val rows = CypherRowOperations(context, true)
    private val projection = CypherProjection(context, rows)
    private val scanner = DirectStringScanner(sources, context, access)
    private val policy = StringScanPolicy(sources, true)
    private val indexed = IndexedStringProjection(sources, context, access, rows, scanner)
    private val direct = DirectStringExecutor(sources, true, context, access, rows, projection, scanner, policy, indexed)
    private val filter = requireNotNull(DirectStringDisjunction.compile(
        CypherExpr.StringOp("CONTAINS", CypherExpr.Property(CypherExpr.Variable("n"), "value"), CypherExpr.Literal("needle")), "n"))
    private val spec = StringProjectionSpec(Node::class.java, "n", filter,
        listOf(ReturnItem(CypherExpr.Property(CypherExpr.Variable("n"), "value"), "value")), listOf("value"))

    @Test
    fun `default disjunction execution distinguishes distinct provenance from ordinary row multiplicity`() {
        assertEquals(listOf(mapOf("value" to "needle", INTERNAL_PROVENANCE_KEY to setOf("a", "b"))),
            direct.executeDirectStringDisjunction(spec, 10).rows)
        assertEquals(listOf(mapOf("value" to "needle", INTERNAL_PROVENANCE_KEY to setOf("a")),
            mapOf("value" to "needle", INTERNAL_PROVENANCE_KEY to setOf("b"))),
            direct.executeDirectStringDisjunctionRows(spec, 10).rows)
    }

    @Test
    fun `scanner default storage resumes after zero sized batch and reports exhaustion`() {
        val cursor = scanner.DirectStringSourceScanner(sources.first(), spec, null)
        assertEquals(DirectStringScanner.DirectStringScanBatch(emptyList(), false), cursor.nextDistinctRows(0))
        val batch = cursor.nextDistinctRows(2)
        assertEquals(listOf(mapOf("value" to "needle", INTERNAL_PROVENANCE_KEY to setOf("a"))), batch.rows)
        assertEquals(true, batch.exhausted)
        assertEquals(emptyList(), cursor.nextDistinctRows(2).rows)
    }

    @Test
    fun `parallel projection refuses expressions requiring general evaluation`() {
        assertFalse(policy.canExecuteDirectStringDisjunctionInParallel(Node::class.java, "n", filter,
            listOf(ReturnItem(CypherExpr.Variable("n"))), sources))
    }

    @Test
    fun `unrouted bounded filter keeps full result semantics through default source selection`() {
        val matcher = NodePatternMatcher(sources, true, context, access, rows)
        val bounded = FilteredNodeLimitExecutor(context, GraphSourcePlanner(sources, context), access, rows, projection,
            matcher, policy, direct, false)
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value CONTAINS 'needle' RETURN n.value AS value LIMIT 10")
        assertEquals(direct.executeDirectStringDisjunctionRows(spec, 10).rows, assertNotNull(bounded.tryExecute(clauses)).rows)
    }
}
