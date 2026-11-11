package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallSiteSelectionContractTest : GraphStoreTestFixture() {
    @Test
    fun `parallel property matching shares trigram metadata and preserves the complete union`() = withIndex { index ->
        val consumed = AtomicLong()
        val work = splitWork(consumed)
        val predicates = listOf(
            StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Alpha"),
            StringPropertyPredicate("callee_class", null, StringMatchMode.CONTAINS, "Other")
        )
        val ranges = index.matches.matchingRangesInParallel(predicates, work)
        assertEquals(listOf(0, 1), ranges.mergedNodeIds(index.nodeOrder, work).toList())
        assertTrue(index.trigramMetadataInitialized)
        assertTrue(consumed.get() > 0, "parallel dictionary and posting work remains budgeted")
    }

    @Test
    fun `parallel trigram intersection rejects a candidate missing any posting`() = withIndex { index ->
        val firstTrigram = 1
        val secondTrigram = 2
        val postings = longArrayOf(trigramKey(firstTrigram, 0), trigramKey(firstTrigram, 1), trigramKey(secondTrigram, 1))
        val ranges = listOf(CallSiteTrigramPostingRange(firstTrigram, 0, 2), CallSiteTrigramPostingRange(secondTrigram, 2, 3))
        val consumed = AtomicLong()
        val matches = index.matches.intersectCandidateStringIdsInParallel(postings, ranges, intArrayOf(0, 1), splitWork(consumed))
        assertContentEquals(intArrayOf(1), matches)
        assertEquals(2L, consumed.get(), "both candidate intersections are charged, including the rejected one")
    }

    @Test
    fun `selected tuples do not combine properties from different calls or bypass their predicate`() = withIndex { index ->
        val predicates = listOf(StringPropertyPredicate("callee_name", null, StringMatchMode.EQUALS, "read"))
        val projected = listOf("caller_class", "callee_class")
        val selected = linkedSetOf<List<String?>>(
            listOf("sample.Alpha", "sample.Other"), // Both values exist, but never on the same node.
            listOf("sample.Beta", "sample.Other"), // A real tuple whose call does not match the predicate.
            listOf("sample.Alpha", "sample.Target")
        )
        val rows = index.distinct.distinctProjection(predicates, projected, 10, selected, null)
        assertEquals(listOf(listOf("sample.Alpha", "sample.Target")), rows.map { it.values })
        assertEquals(listOf(index.nodeOrder(0)), rows.map { it.encounterOrder })
    }

    private fun splitWork(consumed: AtomicLong) = object : SplitGraphWorkBatchConsumer {
        override val segmentWorkerCount = 1
        override fun consume(workUnits: Long) { consumed.addAndGet(workUnits) }
    }

    private fun withIndex(action: (MappedCallSiteStringIndex) -> Unit) {
        fun method(owner: String, name: String) = MethodDescriptor(TypeDescriptor(owner), name, emptyList(), TypeDescriptor("void"))
        val graph = DefaultGraph.Builder()
            .addNode(CallSiteNode(NodeId(0), method("sample.Alpha", "run"), method("sample.Target", "read"), null, null, emptyList()))
            .addNode(CallSiteNode(NodeId(1), method("sample.Beta", "run"), method("sample.Other", "write"), null, null, emptyList()))
            .build()
        val dir = Files.createTempDirectory("callsite-selection-contract")
        try {
            GraphStore.save(graph, dir)
            Files.deleteIfExists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE))
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                assertTrue(mapped.context.prepareCallSiteStringIndex())
                action(assertNotNull(mapped.context.callSiteStringIndex))
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
