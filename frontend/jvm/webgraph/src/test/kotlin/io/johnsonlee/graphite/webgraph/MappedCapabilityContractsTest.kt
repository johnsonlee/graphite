package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MappedCapabilityContractsTest : GraphStoreTestFixture() {
    private fun assertPersistentRoundTrip(
        context: MappedGraphContext,
        index: MappedCallSiteStringIndex,
        predicate: StringPropertyPredicate,
        dir: java.nio.file.Path
    ) {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use(index::writePersistent)
        val identity = context.callSiteStringIndexContentIdentity(null)
        val readContext = CallSiteIndexReadContext(context.stringTable.size(), 1, identity,
            { id -> context.nodeOffsets.offset(id) }, context.nodeOffsets.size,
            context::rawCallSiteStringPropertyId, context.stringTable)
        java.io.DataInputStream(bytes.toByteArray().inputStream()).use { input ->
            kotlin.test.assertNotNull(MappedCallSiteStringIndex.readPersistent(input, readContext)).use { restored ->
                assertEquals(listOf(0), restored.queries.matchingNodeIds(listOf(predicate), null).toList())
                kotlin.test.assertContentEquals(identity, restored.contentIdentity())
                restored.contentIdentity()[0] = (identity[0].toInt() xor 1).toByte()
                kotlin.test.assertContentEquals(identity, restored.contentIdentity(), "callers must not mutate retained identity")
            }
        }
        val viewContext = CallSiteIndexViewContext(context.stringTable.size(), 1, identity,
            context.stringTable, context.nodeOffsets.size) { id -> context.nodeOffsets.offset(id) }
        kotlin.test.assertNull(MappedCallSiteStringIndexView.load(dir.resolve("missing-index"), viewContext, null))
        val file = dir.resolve("read-index")
        Files.write(file, bytes.toByteArray())
        kotlin.test.assertNull(MappedCallSiteStringIndexView.load(
            file, viewContext.copy(expectedContentIdentity = byteArrayOf()), null))
    }

    @Test
    fun `competing captured index publication retains the first complete index and releases the loser`() {
        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "run", emptyList(), TypeDescriptor("void"))
        val dir = Files.createTempDirectory("captured-index-publication")
        try {
            GraphStore.save(DefaultGraph.Builder().addNode(CallSiteNode(NodeId(0), method, method, null, null, emptyList())).build(), dir)
            Files.deleteIfExists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE))
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { graph ->
                val context = graph.context
                val captured = ParallelCallSiteScanResult(0, intArrayOf(), intArrayOf(0),
                    Array(CALL_SITE_STRING_PROPERTY_COUNT) { property ->
                        intArrayOf(context.rawCallSiteStringPropertyId(0, property)) }, 1, 1)
                fun publish(count: Long) {
                    val scratch = kotlin.test.assertNotNull(estimatedMappedCallSiteStringIndexCountBytes(context.stringTable.size()))
                    val reservation = kotlin.test.assertNotNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(scratch))
                    context.buildAndPublishCallSiteStringIndex(listOf(captured), count, reservation, null)
                }
                publish(1)
                val first = kotlin.test.assertNotNull(context.callSiteStringIndex)
                val identity = context.callSiteStringIndexContentIdentity(null)
                kotlin.test.assertContentEquals(identity, first.contentIdentity())
                first.contentIdentity()[0] = (identity[0].toInt() xor 1).toByte()
                kotlin.test.assertContentEquals(identity, first.contentIdentity())
                val retained = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                publish(1)
                assertSame(first, context.callSiteStringIndex)
                assertEquals(retained, MappedCallSiteStringIndexMemoryBudget.retainedBytes(),
                    "a losing publication releases its reservation")
                publish(-1)
                assertSame(first, context.callSiteStringIndex)
                assertEquals(retained, MappedCallSiteStringIndexMemoryBudget.retainedBytes(),
                    "invalid captured counts release scratch storage")
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `denied retained index growth releases scratch reservation and remains retryable`() {
        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "run", emptyList(), TypeDescriptor("void"))
        val dir = Files.createTempDirectory("index-growth-contract")
        try {
            GraphStore.save(DefaultGraph.Builder().addNode(CallSiteNode(NodeId(0), method, method, null, null, emptyList())).build(), dir)
            Files.deleteIfExists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE))
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { graph ->
                val context = graph.context
                val before = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                val scratch = kotlin.test.assertNotNull(estimatedMappedCallSiteStringIndexCountBytes(context.stringTable.size()))
                val remaining = MappedCallSiteStringIndexMemoryBudget.maxBytes - before - scratch
                kotlin.test.assertNotNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(remaining)).use {
                    kotlin.test.assertNull(context.callSiteStringIndex(CallSiteNode::class.java))
                    assertEquals(before + remaining, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                }
                assertEquals(before, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                assertTrue(context.prepareCallSiteStringIndex())
                val index = kotlin.test.assertNotNull(context.callSiteStringIndex(CallSiteNode::class.java))
                val predicate = StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Owner")
                assertEquals(listOf(0), index.queries.matchingNodeIds(listOf(predicate), null).toList())
                assertPersistentRoundTrip(context, index, predicate, dir)
                val visited = mutableListOf<Int>()
                context.forEachCallSiteStringProperty(null) { property, _ -> visited += property }
                assertEquals(listOf(0, 1, 2, 3), visited)
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `raw property access declines unknown fields and rejects invalid callsite property ordinals`() {
        val method = MethodDescriptor(TypeDescriptor("sample.Owner"), "run", emptyList(), TypeDescriptor("void"))
        val call = CallSiteNode(NodeId(0), method, method, null, null, emptyList())
        val enumeration = io.johnsonlee.graphite.core.EnumConstant(NodeId(1), TypeDescriptor("sample.State"), "READY")
        val dir = Files.createTempDirectory("raw-property-contract")
        try {
            GraphStore.save(DefaultGraph.Builder().addNode(call).addNode(enumeration).build(), dir)
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { graph ->
                val context = graph.context
                kotlin.test.assertNull(context.rawStringPropertyIndex(0, CallSiteNode::class.java, "unknown"))
                kotlin.test.assertNull(context.rawStringPropertyIndex(1, enumeration.javaClass, "unknown"))
                kotlin.test.assertNull(context.rawStringPropertyIndex(0, Node::class.java, "unknown"))
                assertEquals("sample.Owner", context.stringTable.get(context.rawCallSiteStringPropertyId(0, 0)))
                assertEquals("Unknown CallSite string property index: -1", assertFailsWith<IllegalStateException> {
                    context.rawCallSiteStringPropertyId(0, -1)
                }.message)
                assertEquals(0, context.lookupForwardLabel(0, 1))
                val abortedBefore = context.callSiteScanAbortedWorkers.get()
                Thread.currentThread().interrupt()
                try {
                    assertFailsWith<java.util.concurrent.CancellationException> {
                        context.checkWorkerInterrupted(java.util.concurrent.atomic.AtomicBoolean(false), countScanAbort = true)
                    }
                } finally { Thread.interrupted() }
                assertEquals(abortedBefore, context.callSiteScanAbortedWorkers.get(),
                    "caller interruption without a shared abort must not count a cooperatively aborted worker")
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun `capability views share graph metadata and count each inspected method`() {
        val first = MethodDescriptor(TypeDescriptor("sample.Alpha"), "read", emptyList(), TypeDescriptor("void"))
        val second = first.copy(name = "write")
        val graph = DefaultGraph.Builder().apply {
            addMethod(first)
            addMethod(second)
            addNode(StringConstant(NodeId(0), "content"))
            addMemberAnnotation("sample.Alpha", "read", "sample.Mark", mapOf("value" to "checked"))
        }.build()
        val dir = Files.createTempDirectory("mapped-capability-contract")
        try {
            GraphStore.save(graph, dir)
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { mapped ->
                val core = mapped.context.graphCapabilities
                assertEquals(1L, core.nodeCount(StringConstant::class.java))
                assertEquals(0L, core.edgeCount())
                assertEquals(2L, core.methodCount())
                assertEquals(listOf(first, second), core.methodSlice(MethodPattern(), 10))
                assertEquals(graph.memberAnnotationIndex(), core.memberAnnotationIndex())
                var inspected = 0
                assertEquals(listOf(first), mapped.context.methodCapabilities.methodSlice(
                    MethodPattern(), 1, MethodMetadataScanConsumer { inspected++ }))
                assertEquals(1, inspected, "a bounded slice must not inspect the second method")
                assertEquals(emptyList(), mapped.context.methodCapabilities.methodSlice(
                    MethodPattern(), 0, MethodMetadataScanConsumer { error("zero-limit slice scanned") }))
                assertFalse(mapped.hasRetainedStringPropertyDisjunction(CallSiteNode::class.java,
                    listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "sample"))))
                assertFalse(mapped.hasRetainedStringPropertyDisjunction(Node::class.java, emptyList()))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persistent index read keeps a work rejection as its cause`() {
        val rejection = IllegalStateException("query work exhausted")
        val work = PersistentIndexReadWork(GraphWorkConsumer { throw rejection })
        val failed = assertFailsWith<MappedCallSiteStringIndexReadAbortedException> {
            repeat(100_000) { work.consume() }
        }
        assertSame(rejection, failed.cause)
    }

    @Test
    fun `posting directory rejects unsorted string identities before accepting postings`() {
        assertFailsWith<IllegalArgumentException> {
            CallSitePostingDirectory(intArrayOf(1, 2), intArrayOf(7, 3), intArrayOf(0, 1))
        }
        val valid = CallSitePostingDirectory(intArrayOf(2, 3), intArrayOf(3, 7), intArrayOf(0, 1, 2))
        assertEquals(2, valid.uniqueStringCount)
        assertEquals(3, valid.postingCount)
    }
}
