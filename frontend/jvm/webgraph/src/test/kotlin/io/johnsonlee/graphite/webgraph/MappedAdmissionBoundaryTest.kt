package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.ParallelGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MappedAdmissionBoundaryTest : GraphStoreTestFixture() {
    @Test
    fun `oversized property index is rejected once while bounded raw lookup remains available`() = withContext { original ->
        var countReads = 0
        val oversized = object : NodeTypeIndex by original.nodeTypeIndex {
            override fun count(type: Class<out Node>): Long = if (type == ResourceFileNode::class.java) {
                countReads++
                Int.MAX_VALUE.toLong()
            } else original.nodeTypeIndex.count(type)
        }
        val context = contextWithNodes(original, oversized)
        repeat(3) {
            assertNull(context.lookupStringProperty(ResourceFileNode::class.java, "path", null,
                StringMatchMode.CONTAINS, "target", Int.MAX_VALUE, null))
        }
        assertEquals(1, countReads, "a rejected property must not retry the oversized allocation on every query")
        assertEquals(listOf(NodeId(1)), assertNotNull(context.lookupStringProperty(ResourceFileNode::class.java,
            "path", null, StringMatchMode.CONTAINS, "target", 1, null)).map { it.id }.toList())
        assertEquals(1, countReads)
        context.close()
    }

    @Test
    fun `a missing record offset cannot become a raw property match`() = withContext { original ->
        val missing = object : NodeOffsetIndex by original.nodeOffsets {
            override fun offset(nodeId: Int): Long = if (nodeId == 1) -1L else original.nodeOffsets.offset(nodeId)
        }
        val context = contextWithNodes(original, original.nodeTypeIndex, missing)
        assertEquals(emptyList(), assertNotNull(context.lookupStringProperty(ResourceFileNode::class.java,
            "path", null, StringMatchMode.CONTAINS, "target", 1, null)).toList())
        context.close()
    }

    @Test
    fun `incomplete captured node range is not published and releases its reservation`() = withContext { context ->
        val before = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val scratch = assertNotNull(estimatedMappedCallSiteStringIndexCountBytes(context.stringTable.size()))
        val reservation = assertNotNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(scratch))
        assertFalse(context.captureAndBuildCallSiteStringIndex(2, reservation, GraphWorkConsumer {}),
            "the capture has only one CallSite while its expected range contains two")
        assertNull(context.callSiteStringIndex)
        assertEquals(before, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }

    @Test
    fun `parallel preparation under exhausted budget leaves the graph queryable`() = withContext { original ->
        val types = object : NodeTypeIndex by original.nodeTypeIndex {
            override fun count(type: Class<out Node>): Long = if (type == CallSiteNode::class.java) {
                MIN_PARALLEL_CALL_SITE_SCAN_NODES
            } else original.nodeTypeIndex.count(type)
        }
        val context = contextWithNodes(original, types)
        val before = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val remaining = MappedCallSiteStringIndexMemoryBudget.maxBytes - before
        assertNotNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(remaining)).use {
            assertFalse(context.prepareCallSiteStringIndexInParallel(ParallelGraphWorkBatchConsumer {
                error("denied preparation must not scan graph records")
            }))
            assertNull(context.callSiteStringIndex)
            assertEquals(MappedCallSiteStringIndexMemoryBudget.maxBytes,
                MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            assertEquals(listOf(NodeId(1)), assertNotNull(context.lookupStringProperty(ResourceFileNode::class.java,
                "path", null, StringMatchMode.CONTAINS, "target", 1, null)).map { it.id }.toList())
        }
        context.close()
        assertEquals(before, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }

    @Test
    fun `mapped sidecar is permanently rejected for an empty callsite domain before opening it`() = withContext { original ->
        var countReads = 0
        val types = object : NodeTypeIndex by original.nodeTypeIndex {
            override fun count(type: Class<out Node>): Long = if (type == CallSiteNode::class.java) {
                countReads++
                0L
            } else original.nodeTypeIndex.count(type)
        }
        Files.write(original.callSiteStringIndexFile, byteArrayOf(1, 2, 3))
        val context = contextWithNodes(original, types, persistent = true)
        val work = object : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
            override val segmentWorkerCount = 1
            override fun consume(workUnits: Long) = error("empty domains must not read the sidecar")
        }
        repeat(2) { assertNull(context.mappedCallSiteStringIndexView(work)) }
        kotlin.test.assertTrue(context.mappedCallSiteStringIndexViewUnavailable)
        assertEquals(1, countReads, "a rejected sidecar must not be opened again by later queries")
        context.close()
    }

    private fun withContext(action: (MappedGraphContext) -> Unit) {
        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "run", emptyList(), TypeDescriptor("void"))
        val graph = DefaultGraph.Builder()
            .addNode(CallSiteNode(NodeId(0), method, method, null, null, emptyList()))
            .addNode(ResourceFileNode(NodeId(1), "target-path", "source", "properties"))
            .build()
        val dir = Files.createTempDirectory("mapped-admission-boundaries")
        try {
            GraphStore.save(graph, dir)
            Files.deleteIfExists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE))
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { action(it.context) }
        } finally { dir.toFile().deleteRecursively() }
    }

    private fun contextWithNodes(
        source: MappedGraphContext,
        types: NodeTypeIndex,
        offsets: NodeOffsetIndex = source.nodeOffsets,
        persistent: Boolean = false
    ) = MappedGraphContext(
        MappedNodeStorage(source.mappedNodeData, source.nodeDataVersion, source.stringTable, offsets, types),
        MappedEdgeStorage(source.forward, source.backward, source.forwardLabels, source.cumulativeOutdeg,
            source.edgeCount, source.comparisonLookup),
        MappedGraphSidecars(source.metadataFile, source.metadata, source.methodCount, source.classOverviewProvider,
            source.resourceAccessor, source.branchDefinitions, source.callSiteOrdinals,
            MappedGraphDeclarations(source.declaredTypeTable, source.declaredPresence, source.analysisCompleteness)),
        MappedIndexFiles(source.callSiteStringIndexFile, persistent)
    )
}
