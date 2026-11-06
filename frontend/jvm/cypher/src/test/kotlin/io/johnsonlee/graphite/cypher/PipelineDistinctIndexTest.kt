package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.PreparedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.ReleasableStringPropertyDisjunctionCache
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PipelineDistinctIndexTest : QueryPipelineTestFixture() {

    @Test
    fun `empty parallel distinct waves release rebuildable storage caches`() {
        class EmptyIndexedGraph(private val delegate: Graph) :
            Graph by delegate,
            StringPropertyDisjunctionDistinctProjection,
            StringPropertyLookupOrder,
            ReleasableStringPropertyDisjunctionCache {
            var releases = 0

            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> = emptyList()

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

            override fun releaseStringPropertyDisjunctionCache() {
                releases++
            }
        }

        val first = EmptyIndexedGraph(graph)
        val second = EmptyIndexedGraph(graph)
        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'not-present' OR " +
                        "n.callee_class CONTAINS 'not-present' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 10")

        assertTrue(result.rows.isEmpty())
        assertEquals(1, first.releases)
        assertEquals(1, second.releases)
    }

    @Test
    fun `targeted parallel distinct waves release only zero hit source caches`() {
        class IndexedGraph(private val delegate: Graph, private val values: List<StringPropertyDistinctRow>) :
            Graph by delegate,
            StringPropertyDisjunctionDistinctProjection,
            StringPropertyLookupOrder,
            ReleasableStringPropertyDisjunctionCache {
            var releases = 0

            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> = values.take(limit)

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

            override fun releaseStringPropertyDisjunctionCache() {
                releases++
            }
        }

        val hit = IndexedGraph(graph, listOf(StringPropertyDistinctRow(0, listOf("com.example.Service"))))
        val miss = IndexedGraph(graph, emptyList())
        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("hit", hit), CypherGraph("miss", miss)))
                .execute(
                    "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'example' " + "RETURN DISTINCT n.caller_class AS caller LIMIT 10")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
        assertEquals(0, hit.releases)
        assertEquals(1, miss.releases)
    }

    @Test
    fun `selected provenance pass does not rebuild a released zero hit cache`() {
        class RebuildTrackingIndexedGraph(private val delegate: Graph, private val values: List<StringPropertyDistinctRow>) :
            Graph by delegate,
            StringPropertyDisjunctionDistinctProjection,
            StringPropertyLookupOrder,
            ReleasableStringPropertyDisjunctionCache {
            var cacheBuilds = 0
            var projectionCalls = 0
            var releases = 0
            private var cacheRetained = false

            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> {
                projectionCalls++
                if (!cacheRetained) {
                    cacheBuilds++
                    cacheRetained = true
                }
                return values.filter { row -> selectedValues == null || row.values in selectedValues }.take(limit)
            }

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()

            override fun releaseStringPropertyDisjunctionCache() {
                releases++
                cacheRetained = false
            }
        }

        val hit = RebuildTrackingIndexedGraph(graph, listOf(StringPropertyDistinctRow(0, listOf("com.example.Service"))))
        val miss = RebuildTrackingIndexedGraph(graph, emptyList())
        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("hit", hit), CypherGraph("miss", miss)))
                .execute(
                    "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'example' OR " +
                        "n.callee_class CONTAINS 'example' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
        assertEquals(1, hit.projectionCalls)
        assertEquals(1, hit.cacheBuilds)
        assertEquals(0, hit.releases)
        assertEquals(1, miss.projectionCalls)
        assertEquals(1, miss.cacheBuilds)
        assertEquals(1, miss.releases)
    }

    @Test
    fun `complete indexed projection below limit does not fall back to a second graph scan`() {
        class PartialIndexedGraph(private val delegate: Graph) :
            Graph by delegate, StringPropertyDisjunctionDistinctProjection, StringPropertyLookupOrder {
            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> = listOf(StringPropertyDistinctRow(0, listOf("index-only"))).take(limit)

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
        }

        val result =
            CrossGraphCypherExecutor(
                    listOf(CypherGraph("first", PartialIndexedGraph(graph)), CypherGraph("second", PartialIndexedGraph(graph))))
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'example' OR " +
                        "n.callee_class CONTAINS 'example' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 10")

        assertEquals(listOf("index-only"), result.rows.map { it["caller"] })
    }

    @Test
    fun `indexed projection merges matching dynamic properties from non CallSite nodes`() {
        val returnType = TypeDescriptor("void")
        val mixed =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(100),
                        MethodDescriptor(TypeDescriptor("example.Other"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        1,
                        null,
                        emptyList()))
                .addNode(
                    AnnotationNode(NodeId(101), "example.DynamicCall", "example.Owner", "call", mapOf("caller_class" to "example.Target")))
                .build()
        val indexed =
            object :
                Graph by mixed, StringPropertyDisjunctionLookup, StringPropertyDisjunctionDistinctProjection, StringPropertyLookupOrder {
                override fun <T : Node> nodesByStringPropertyDisjunction(
                    type: Class<T>,
                    predicates: List<StringPropertyPredicate>,
                    limit: Int
                ): Sequence<T>? = if (type == CallSiteNode::class.java) emptySequence() else null

                override fun distinctStringPropertyDisjunction(
                    type: Class<out Node>,
                    predicates: List<StringPropertyPredicate>,
                    projectedProperties: List<String>,
                    limit: Int,
                    selectedValues: Set<List<String?>>?,
                    workConsumer: GraphWorkConsumer?
                ): List<StringPropertyDistinctRow> = emptyList()

                override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
            }

        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("first", indexed), CypherGraph("second", indexed)))
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS \$term " + "RETURN DISTINCT n.caller_class AS caller LIMIT 10",
                    mapOf("term" to "Target"))

        assertEquals(listOf("example.Target"), result.rows.map { it["caller"] })
    }

    @Test
    fun `parallel indexed distinct projection reuses known rows from every graph`() {
        class SelectedIndexedGraph(private val delegate: Graph) :
            Graph by delegate, StringPropertyDisjunctionDistinctProjection, StringPropertyLookupOrder {
            var selectedLookups = 0

            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> {
                val values = listOf("com.example.Service")
                if (selectedValues != null) {
                    selectedLookups++
                    return listOf(StringPropertyDistinctRow(0, values)).takeIf { values in selectedValues }.orEmpty()
                }
                return listOf(StringPropertyDistinctRow(0, values))
            }

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
        }

        val first = SelectedIndexedGraph(graph)
        val second = SelectedIndexedGraph(graph)
        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'example' OR " +
                        "n.callee_class CONTAINS 'example' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
        assertEquals(0, first.selectedLookups)
        assertEquals(0, second.selectedLookups)
    }

    @Test
    fun `parallel indexed distinct projection injects and verifies graph ids`() {
        class GraphIdIndexedGraph(private val delegate: Graph) :
            Graph by delegate, StringPropertyDisjunctionDistinctProjection, StringPropertyLookupOrder {
            var selectedLookups = 0

            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> {
                assertEquals(listOf("graphId"), projectedProperties)
                if (selectedValues != null) {
                    selectedLookups++
                    assertEquals(setOf(listOf(null)), selectedValues)
                }
                return listOf(StringPropertyDistinctRow(0, listOf(null))).take(limit)
            }

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
        }

        val first = GraphIdIndexedGraph(graph)
        val second = GraphIdIndexedGraph(graph)
        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'example' OR " +
                        "n.callee_class CONTAINS 'example' " +
                        "RETURN DISTINCT n.graphId AS graphId LIMIT 2")

        assertEquals(listOf("first", "second"), result.rows.map { it["graphId"] })
        assertEquals(1, first.selectedLookups)
        assertEquals(1, second.selectedLookups)

        val skipped =
            CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
                .execute(
                    "MATCH (n) WHERE n.caller_class CONTAINS 'example' OR " +
                        "n.callee_class CONTAINS 'example' " +
                        "RETURN DISTINCT n.graphId AS graphId SKIP 1 LIMIT 1")

        assertEquals(listOf("second"), skipped.rows.map { it["graphId"] })
        assertEquals(2, first.selectedLookups)
        assertEquals(2, second.selectedLookups)
    }

    @Test
    fun `prepared indexes retain the indexed distinct projection path`() {
        class PreparedIndexedGraph(private val delegate: Graph) :
            Graph by delegate,
            PreparedStringPropertyDisjunctionLookup,
            StringPropertyDisjunctionDistinctProjection,
            StringPropertyLookupOrder {
            var projectionCalls = 0

            override fun nodeCount(type: Class<out Node>): Long? =
                if (type == CallSiteNode::class.java) 10_000L else delegate.nodeCount(type)

            override fun hasPreparedStringPropertyDisjunction(type: Class<out Node>, predicates: List<StringPropertyPredicate>): Boolean =
                type == CallSiteNode::class.java && predicates.isNotEmpty()

            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ): List<StringPropertyDistinctRow> {
                projectionCalls++
                val values = listOf("com.example.Service")
                return listOf(StringPropertyDistinctRow(0, values))
                    .takeIf { selectedValues == null || values in selectedValues }
                    .orEmpty()
                    .take(limit)
            }

            override fun stringPropertyNodeOrder(node: Node): Long = node.id.value.toLong()
        }

        val first = PreparedIndexedGraph(DefaultGraph.Builder().build())
        val second = PreparedIndexedGraph(DefaultGraph.Builder().build())
        val result =
            CrossGraphCypherExecutor(listOf(CypherGraph("first", first), CypherGraph("second", second)))
                .execute(
                    "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'example' " + "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("com.example.Service"), result.rows.map { it["caller"] })
        assertTrue(first.projectionCalls > 0)
        assertTrue(second.projectionCalls > 0)
    }
}
