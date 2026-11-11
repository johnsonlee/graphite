package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.PreparedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionProjection
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexedProjectionBoundaryTest {
    private val method = MethodDescriptor(TypeDescriptor("needle.Owner"), "read", emptyList(), TypeDescriptor("void"))
    private val call = CallSiteNode(NodeId(1), method, method, null, null, emptyList())
    private val text = StringConstant(NodeId(2), "needle")
    private val backing = DefaultGraph.Builder().addNode(call).addNode(text).build()

    private fun spec(type: Class<out Node> = Node::class.java): StringProjectionSpec {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.caller_class CONTAINS 'needle' OR n.value CONTAINS 'needle' " +
            "RETURN n.caller_class AS owner")
        val condition = (clauses[1] as CypherClause.Where).condition
        val items = (clauses[2] as CypherClause.Return).items
        return StringProjectionSpec(type, "n", requireNotNull(DirectStringDisjunction.compile(condition, "n")), items, listOf("owner"))
    }

    private fun indexedSources(count: Int): List<CypherGraph> = (0 until count).map { index ->
        val graph = object : Graph by backing, StringPropertyDisjunctionDistinctProjection, StringPropertyLookupOrder,
            PreparedStringPropertyDisjunctionLookup {
            override fun hasPreparedStringPropertyDisjunction(type: Class<out Node>, predicates: List<StringPropertyPredicate>) = true
            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
            override fun distinctStringPropertyDisjunction(type: Class<out Node>, predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>, limit: Int, selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?): List<StringPropertyDistinctRow> =
                listOf(StringPropertyDistinctRow(1, listOf("needle.Owner")))
                    .filter { selectedValues == null || it.values in selectedValues }.take(limit)
        }
        CypherGraph("scope$index", graph)
    }

    private fun projection(sources: List<CypherGraph>): IndexedStringProjection {
        val context = PipelineExecutionContext(null, false)
        val access = PipelineGraphAccess(sources, true, context)
        return IndexedStringProjection(sources, context, access, CypherRowOperations(context, true),
            DirectStringScanner(sources, context, access))
    }

    @Test
    fun `mixed value and callsite projection retains null row and all source provenance`() {
        val sources = indexedSources(40)
        val result = requireNotNull(projection(sources).executeIndexedDistinctStringProjection(spec(), 3))
        val provenance = sources.map { it.id }.toSet()
        assertEquals(listOf(mapOf("owner" to "needle.Owner", INTERNAL_PROVENANCE_KEY to provenance),
            mapOf("owner" to null, INTERNAL_PROVENANCE_KEY to provenance)), result.rows)
    }

    @Test
    fun `limited distinct projection completes provenance without adding unselected generic values`() {
        val sources = indexedSources(40)
        val result = requireNotNull(projection(sources).executeIndexedDistinctStringProjection(spec(), 1))
        assertEquals(listOf(mapOf("owner" to "needle.Owner", INTERNAL_PROVENANCE_KEY to sources.map { it.id }.toSet())), result.rows)
    }

    @Test
    fun `parallel residual list predicates preserve count and ordered projection`() {
        val sources = listOf(CypherGraph("a", backing), CypherGraph("b", backing))
        val executor = CrossGraphCypherExecutor(sources)
        val prefix = "MATCH (n) WHERE n.caller_class CONTAINS 'needle' AND all(x IN range(1, 1025) WHERE x > 0) "
        assertEquals(listOf(2L), executor.execute(prefix + "RETURN count(*) AS count").rows.map { it["count"] })
        assertEquals(listOf("needle.Owner", "needle.Owner"), executor.execute(
            prefix + "RETURN n.caller_class AS owner ORDER BY owner LIMIT 10").rows.map { it["owner"] })
    }

    @Test
    fun `single prepared source retains selected row and source provenance`() {
        val sources = indexedSources(1)
        val result = requireNotNull(projection(sources).executeIndexedDistinctStringProjection(spec(), 1))
        assertEquals(listOf(mapOf("owner" to "needle.Owner", INTERNAL_PROVENANCE_KEY to setOf("scope0"))), result.rows)
    }

    @Test
    fun `regular projection capability refusal falls back and unsupported leading type is rejected`() {
        val refusing = object : Graph by backing, StringPropertyDisjunctionProjection {
            override fun projectStringPropertyDisjunction(type: Class<out Node>, predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>, limit: Int, workConsumer: GraphWorkConsumer?): List<StringPropertyProjectionRow>? = null
        }
        val sources = listOf(CypherGraph("scope", refusing))
        val projection = projection(sources)
        val onlyCaller = spec(CallSiteNode::class.java).let { it.copy(filter = DirectStringDisjunction(listOf(it.filter.filters.first()))) }
        assertNull(projection.executeIndexedStringProjectionRows(onlyCaller, 10, null, sources, true))
        assertNull(projection.projectLeadingRows(spec(StringConstant::class.java), 10, sources, null, false))
    }
}
