package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies combined call site index. */
class CombinedCallSiteIndexTest : GraphStoreTestFixture() {

    @Test
    fun `combined CallSite string index preserves modes order limits and conjunction residuals`() {
        val returnType = TypeDescriptor("void")
        fun callSite(id: Int, callerClass: String, callerName: String, calleeClass: String, calleeName: String) =
            CallSiteNode(
                NodeId(id),
                MethodDescriptor(TypeDescriptor(callerClass), callerName, emptyList(), returnType),
                MethodDescriptor(TypeDescriptor(calleeClass), calleeName, emptyList(), returnType),
                id,
                null,
                emptyList())
        val graph =
            DefaultGraph.Builder()
                .addNode(callSite(0, "Example.Alpha", "loadFirst", "deps.AKeyStore", "fetch"))
                .addNode(callSite(1, "example.Beta", "makeSecond", "deps.Repository", "provideTail"))
                .addNode(callSite(2, "Example.Alpha", "finishThird", "deps.OtherAKey", "close"))
                .addNode(callSite(3, "example.Other", "skip", "deps.Repository", "ignore"))
                .build()
        val dir = Files.createTempDirectory("webgraph-combined-callsite-index")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val smallSplitProjection =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Example")),
                        projectedProperties = listOf("caller_class"),
                        limit = 4,
                        workConsumer =
                            object : SplitGraphWorkBatchConsumer {
                                override val segmentWorkerCount: Int = 1

                                override fun consume(workUnits: Long) = Unit
                            })
                assertEquals(listOf(listOf("Example.Alpha")), smallSplitProjection?.map { row -> row.values })

                fun ids(
                    property: String,
                    mode: StringMatchMode,
                    expected: String,
                    transform: StringValueTransform? = null,
                    limit: Int = Int.MAX_VALUE,
                    consume: (() -> Unit)? = null
                ): List<Int> {
                    val predicate = StringPropertyPredicate(property, transform, mode, expected)
                    val sequence =
                        if (consume == null) {
                            loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), limit)
                        } else {
                            loaded.nodesByStringPropertyDisjunction(
                                CallSiteNode::class.java, listOf(predicate), limit, GraphWorkConsumer { consume() })
                        }
                    return sequence.orEmpty().map { it.id.value }.toList()
                }

                assertEquals(listOf(0, 2), ids("caller_class", StringMatchMode.EQUALS, "Example.Alpha"))
                assertEquals(listOf(0, 2), ids("caller_class", StringMatchMode.EQUALS, "example.alpha", StringValueTransform.LOWERCASE))
                assertEquals(listOf(0, 2), ids("callee_class", StringMatchMode.CONTAINS, "AKey"))
                assertEquals(listOf(0), ids("caller_name", StringMatchMode.STARTS_WITH, "load"))
                assertEquals(listOf(1), ids("callee_name", StringMatchMode.ENDS_WITH, "Tail"))
                assertTrue(ids("caller_class", StringMatchMode.EQUALS, "missing").isEmpty())

                val overlapping =
                    listOf(
                        StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "Example.Alpha"),
                        StringPropertyPredicate("callee_class", null, StringMatchMode.CONTAINS, "AKey"))
                assertEquals(
                    listOf(0, 2),
                    loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, overlapping).orEmpty().map { it.id.value }.toList())

                var consumed = 0
                val exactOr =
                    listOf(
                        StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "Example.Alpha"),
                        StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "example.Beta"))
                assertEquals(
                    listOf(0, 1),
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, exactOr, 2, GraphWorkConsumer { consumed++ })
                        .orEmpty()
                        .map { it.id.value }
                        .toList())
                assertTrue(consumed > exactOr.size)

                loaded.clearStringPropertyIndexes()
                val exactOrRows =
                    loaded
                        .query(
                            "MATCH (n) WHERE n.caller_class = 'Example.Alpha' OR n.caller_class = 'example.Beta' " +
                                "RETURN DISTINCT n.caller_class AS caller, n.caller_name AS method LIMIT 10")
                        .rows
                assertEquals(listOf("loadFirst", "makeSecond", "finishThird"), exactOrRows.map { it["method"] })
                assertTrue(loaded.isCallSiteStringIndexInitialized())

                val exactAndContains =
                    loaded
                        .query(
                            "MATCH (n) WHERE n.caller_class = 'Example.Alpha' AND n.callee_class CONTAINS 'AKey' " +
                                "RETURN DISTINCT n.caller_name AS method LIMIT 10")
                        .rows
                assertEquals(listOf("loadFirst", "finishThird"), exactAndContains.map { it["method"] })

                val exactAndDisjunction =
                    loaded
                        .query(
                            "MATCH (n) WHERE n.caller_class = 'Example.Alpha' AND " +
                                "(n.callee_class CONTAINS 'Store' OR n.callee_name ENDS WITH 'close') " +
                                "RETURN DISTINCT n.caller_name AS method LIMIT 10")
                        .rows
                assertEquals(listOf("loadFirst", "finishThird"), exactAndDisjunction.map { it["method"] })
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped disjunction tolerates disabled match cache and observes interruption`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(0),
                        MethodDescriptor(TypeDescriptor("example.VoucherCaller"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Repository"), "load", emptyList(), returnType),
                        0,
                        null,
                        emptyList()))
                .build()
        val predicate = StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "voucher")
        val dir = Files.createTempDirectory("webgraph-disjunction-no-match-cache")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                loaded.context.javaClass.getDeclaredField("rawStringMatchStates").apply {
                    isAccessible = true
                    set(loaded.context, RawStringMatchStates(maxRetainedBytes = 0, maxEntries = 0))
                }
                val budgetBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                Thread.currentThread().interrupt()
                try {
                    assertFailsWith<CancellationException> {
                        loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate)).orEmpty().toList()
                    }
                } finally {
                    Thread.interrupted()
                }
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { it.id.value }
                        .toList())

                Thread.currentThread().interrupt()
                try {
                    assertFailsWith<CancellationException> {
                        loaded.nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate)).orEmpty().toList()
                    }
                } finally {
                    Thread.interrupted()
                }
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `trigram budget exhaustion falls back to dictionary scan`() {
        val dir = Files.createTempDirectory("webgraph-trigram-budget")
        try {
            val values = listOf("alpha-feature", "beta-feature")
            val strings = StringTable.build(values, dir)
            val index =
                MappedStringPropertyIndex(
                    nodeIds = intArrayOf(10, 11),
                    stringIds = values.map(strings::indexOf).toIntArray(),
                    uniqueStringIds = values.map(strings::indexOf).sorted().toIntArray(),
                    stringTable = strings,
                    maxTrigramPostings = 0,
                    maxTrigramBytes = 0,
                    maxMatchingStringCacheEntries = 1,
                    maxMatchingStringCacheBytes = 128)

            assertEquals(listOf(10), index.matchingNodeIds(StringMatchMode.CONTAINS, "alpha").toList())
            assertEquals(1, index.matchingStringCacheSize())
            assertTrue(index.matchingNodeIds(StringMatchMode.CONTAINS, "missing").none())
            assertEquals(1, index.matchingStringCacheSize())

            val uncached =
                MappedStringPropertyIndex(
                    nodeIds = intArrayOf(10, 11),
                    stringIds = values.map(strings::indexOf).toIntArray(),
                    uniqueStringIds = values.map(strings::indexOf).sorted().toIntArray(),
                    stringTable = strings,
                    maxMatchingStringCacheBytes = 0)
            assertEquals(listOf(11), uncached.matchingNodeIds(StringMatchMode.STARTS_WITH, "beta").toList())
            assertEquals(0, uncached.matchingStringCacheSize())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string index isolates raw and exact lowercase match caches`() {
        val dir = Files.createTempDirectory("webgraph-lowercase-string-index")
        try {
            val values = listOf("Voucher", "voucher", "İVOUCHER")
            val strings = StringTable.build(values, dir)
            val index =
                MappedStringPropertyIndex(
                    nodeIds = intArrayOf(10, 11, 12),
                    stringIds = values.map(strings::indexOf).toIntArray(),
                    uniqueStringIds = values.map(strings::indexOf).sorted().toIntArray(),
                    stringTable = strings)

            assertEquals(listOf(11), index.matchingNodeIds(StringMatchMode.CONTAINS, "voucher").toList())
            assertEquals(
                listOf(10, 11, 12),
                index.matchingNodeIds(StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "voucher", workConsumer = null).toList())
            assertEquals(2, index.matchingStringCacheSize())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped fused lowercase scan preserves ASCII and Unicode string semantics`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    listOf("Example.VoucherService", "Example.İVOUCHERService").forEachIndexed { index, owner ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor(owner), "Create", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("Example.Repository"), "Load", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-fused-lowercase-semantics")
        try {
            GraphStore.save(graph, dir)
            val mapped = GraphStore.loadMapped(dir)
            try {
                fun owners(expected: String): List<Any?> =
                    mapped
                        .query(
                            "MATCH (n:CallSiteNode) WHERE " +
                                "toLower(coalesce(n.caller_class, '')) CONTAINS '$expected' OR " +
                                "toLower(coalesce(n.callee_class, '')) CONTAINS '$expected' " +
                                "RETURN DISTINCT n.caller_class AS owner LIMIT 10")
                        .rows
                        .map { it["owner"] }

                assertEquals(listOf("Example.VoucherService", "Example.İVOUCHERService"), owners("voucher"))
                assertTrue(owners("Voucher").isEmpty(), "The expected literal must not be normalized")
            } finally {
                (mapped as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string index retains two argument JVM lookup method`() {
        val dir = Files.createTempDirectory("webgraph-string-index-abi")
        try {
            val strings = StringTable.build(listOf("alpha"), dir)
            val index =
                MappedStringPropertyIndex(
                    nodeIds = intArrayOf(10),
                    stringIds = intArrayOf(strings.indexOf("alpha")),
                    uniqueStringIds = intArrayOf(strings.indexOf("alpha")),
                    stringTable = strings)
            val method =
                assertNotNull(
                    MappedStringPropertyIndex::class.java.getMethod("matchingNodeIds", StringMatchMode::class.java, String::class.java))

            @Suppress("UNCHECKED_CAST") val result = method.invoke(index, StringMatchMode.STARTS_WITH, "alpha") as Sequence<Int>
            assertEquals(listOf(10), result.toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
