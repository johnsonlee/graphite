package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies call site disjunction. */
class CallSiteDisjunctionTest : GraphStoreTestFixture() {

    @Test
    fun `mapped broad disjunction preserves stored order while deduplicating property streams`() {
        val nodeIds = listOf(1, 30, 70, 2, 90, 4, 5, 50, 40, 3)
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    nodeIds.forEach { nodeId ->
                        val callerClass = if (nodeId == 4) "example.TargetCaller" else "example.OtherCaller$nodeId"
                        val calleeClass =
                            if (nodeId == 90 || nodeId == 4) {
                                "example.TargetCallee"
                            } else {
                                "example.OtherCallee$nodeId"
                            }
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(TypeDescriptor(callerClass), "call$nodeId", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor(calleeClass), "load$nodeId", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-unordered-string-candidates")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val storedOrder = loaded.nodes(CallSiteNode::class.java).map { it.id.value }.toList()
                assertTrue(storedOrder.indexOf(90) < storedOrder.indexOf(4), "Mapped fixture must be unordered")

                val rows =
                    loaded
                        .query(
                            "MATCH (n:CallSiteNode) WHERE " +
                                "n.caller_class CONTAINS 'Target' OR n.callee_class CONTAINS 'Target' " +
                                "RETURN DISTINCT n.id AS id, rand() AS nonce LIMIT 3")
                        .rows
                val resultIds = rows.map { it["id"] }

                assertEquals(listOf(90, 4), resultIds)
                assertEquals(resultIds.size, resultIds.distinct().size)
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `long missing contains skips the combined index while prefix builds it`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4_096) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller"), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicates =
            listOf(
                StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-not-present"))
        val dir = Files.createTempDirectory("webgraph-missing-disjunction")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertEquals(emptyList(), loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates).orEmpty().toList())
                assertEquals(
                    StringPropertyDisjunctionAggregate(0, emptySet()),
                    loaded.aggregateStringPropertyDisjunction(CallSiteNode::class.java, predicates, distinctProperty = "caller_class"))
                assertEquals(
                    emptyList(),
                    loaded.distinctStringPropertyDisjunction(CallSiteNode::class.java, predicates, listOf("caller_class"), limit = 10))
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(
                    emptyList(),
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, predicates.map { predicate -> predicate.copy(mode = StringMatchMode.STARTS_WITH) })
                        .orEmpty()
                        .toList())
                assertTrue(loaded.isCallSiteStringIndexInitialized())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cold distinct CallSite projection builds the combined index and rejects an invalid type`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(0),
                        MethodDescriptor(TypeDescriptor("example.VoucherCaller"), "create", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        0,
                        null,
                        emptyList()))
                .build()
        val predicates =
            listOf(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "voucher"))
        val dir = Files.createTempDirectory("webgraph-cold-distinct-projection")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertEquals(
                    listOf(listOf("example.VoucherCaller", null)),
                    loaded
                        .distinctStringPropertyDisjunction(
                            CallSiteNode::class.java, predicates, projectedProperties = listOf("caller_class", "graphId"), limit = 1)
                        ?.map { row -> row.values })
                assertTrue(loaded.isCallSiteStringIndexInitialized())
                assertNull(
                    loaded.projectStringPropertyDisjunction(
                        StringConstant::class.java, predicates, projectedProperties = listOf("caller_class"), limit = 1))
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `mapped disjunction builds and clears one combined CallSite string index`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(3) { index ->
                        val marker = if (index == 1) "Voucher" else "Feature"
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.${marker}Caller$index"), "create$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency$index"), "invoke$index", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicates =
            listOf("caller_class", "caller_name", "callee_class", "callee_name").map { property ->
                StringPropertyPredicate(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "voucher")
            }
        val dir = Files.createTempDirectory("webgraph-warm-disjunction-handoff")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val budgetBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                val cold =
                    loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates).orEmpty().map { it.id.value }.toList()

                assertEquals(listOf(1), cold)
                assertTrue(loaded.isCallSiteStringIndexInitialized())
                assertTrue(loaded.isCallSiteTrigramIndexInitialized())
                assertTrue(loaded.callSiteStringIndexBytes() > 0L)
                val aggregate =
                    loaded.aggregateStringPropertyDisjunction(CallSiteNode::class.java, predicates, distinctProperty = "caller_class")
                assertEquals(1L, aggregate?.count)
                assertEquals(setOf("example.VoucherCaller1"), aggregate?.distinctValues)
                var aggregateWork = 0
                val trackedAggregate =
                    loaded.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        distinctProperty = "caller_class",
                        workConsumer = GraphWorkConsumer { aggregateWork++ })
                assertEquals(1L, trackedAggregate?.count)
                assertTrue(aggregateWork > 0)
                val sparseCalleeAggregate =
                    loaded.aggregateStringPropertyDisjunction(CallSiteNode::class.java, predicates, distinctProperty = "callee_name")
                assertEquals(setOf("invoke1"), sparseCalleeAggregate?.distinctValues)
                var denseAggregateWork = 0
                val denseAggregate =
                    loaded.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(
                            StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "example"),
                            StringPropertyPredicate("caller_name", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "create")),
                        distinctProperty = "callee_name",
                        workConsumer = GraphWorkConsumer { denseAggregateWork++ })
                assertEquals(3L, denseAggregate?.count)
                assertEquals(setOf("invoke0", "invoke1", "invoke2"), denseAggregate?.distinctValues)
                assertTrue(denseAggregateWork >= 6)
                assertTrue(loaded.prefersSerialStringPropertyDisjunction(CallSiteNode::class.java, predicates))
                val projectedValues = listOf("example.VoucherCaller1", "create1", null)
                val projected =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java, predicates, listOf("caller_class", "caller_name", "graphId"), limit = 10)
                assertEquals(listOf(projectedValues), projected?.map { it.values })
                val projectedWithMissingCallSiteProperty =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java, predicates, listOf("class", "caller_class", "name"), limit = 10)
                assertEquals(listOf(listOf(null, "example.VoucherCaller1", null)), projectedWithMissingCallSiteProperty?.map { it.values })
                val selected =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        listOf("caller_class", "caller_name", "graphId"),
                        limit = 10,
                        selectedValues = setOf(projectedValues, listOf("missing", "missing", null)))
                assertEquals(listOf(projectedValues), selected?.map { it.values })
                val selectedWithLimit =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        predicates,
                        listOf("caller_class", "caller_name", "graphId"),
                        limit = 1,
                        selectedValues = setOf(projectedValues))
                assertEquals(listOf(projectedValues), selectedWithLimit?.map { it.values })
                val absent =
                    loaded.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java, predicates.map { it.copy(expected = "missing") }, distinctProperty = "caller_class")
                assertEquals(0L, absent?.count)
                assertEquals(emptySet(), absent?.distinctValues)
                assertEquals(
                    listOf(1),
                    loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates).orEmpty().map { it.id.value }.toList())
                assertTrue(loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates, limit = 0).orEmpty().none())
                assertNull(loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, emptyList()))

                loaded.clearStringPropertyIndexes()
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                var consumed = 0
                val reset =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, predicates, Int.MAX_VALUE, GraphWorkConsumer { consumed++ })
                        .orEmpty()
                        .map { it.id.value }
                        .toList()
                assertEquals(listOf(1), reset)
                assertTrue(consumed.toLong() > checkNotNull(graph.nodeCount(CallSiteNode::class.java)))
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
