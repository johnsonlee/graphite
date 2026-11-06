package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies persisted call site validation. */
class PersistedCallSiteValidationTest : GraphStoreTestFixture() {

    @Test
    fun `mapped posting merge falls back before limit when a checksum-valid range is out of order`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(3) { nodeId ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(TypeDescriptor("example.TargetCaller"), "call$nodeId", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate(CALLER_CLASS_PROPERTY, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")
        val mappedSplit =
            object : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
                override val segmentWorkerCount: Int = 1

                override fun consume(workUnits: Long) = Unit
            }
        val dir = Files.createTempDirectory("webgraph-out-of-order-mapped-posting")
        try {
            System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = true)
            val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            val bytes = Files.readAllBytes(indexFile)
            val mapped = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val callerUniqueCount = mapped.getInt(4 * Int.SIZE_BYTES + CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
            assertEquals(1, callerUniqueCount)
            val callerPostingsOffset = CALL_SITE_STRING_INDEX_HEADER_BYTES + callerUniqueCount * Int.SIZE_BYTES * 2
            assertEquals(0, mapped.getInt(callerPostingsOffset))
            assertEquals(1, mapped.getInt(callerPostingsOffset + Int.SIZE_BYTES))
            mapped.putInt(callerPostingsOffset, 1)
            mapped.putInt(callerPostingsOffset + Int.SIZE_BYTES, 0)
            rewriteCallSiteStringIndexChecksum(bytes)
            Files.write(indexFile, bytes)

            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = mappedSplit)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertEquals(
                    listOf(listOf("example.TargetCaller")),
                    loaded
                        .projectStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            projectedProperties = listOf(CALLER_CLASS_PROPERTY),
                            limit = 1,
                            workConsumer = mappedSplit)
                        ?.map { row -> row.values })
                assertTrue(loaded.isCallSiteStringIndexInitialized())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `mapped posting validation stays bounded when complete index memory is unavailable`() {
        val returnType = TypeDescriptor("void")
        val distinctRows = MAPPED_POSTING_RANGE_VALIDATION_CACHE_CAPACITY + 64
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(distinctRows) { nodeId ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(
                                    TypeDescriptor("example.Caller"),
                                    "unique${nodeId.toString().padStart(4, '0')}needle",
                                    emptyList(),
                                    returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val mappedSplit =
            object : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
                override val segmentWorkerCount: Int = 1

                override fun consume(workUnits: Long) = Unit
            }
        val dir = Files.createTempDirectory("webgraph-bounded-mapped-posting-validation")
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        var blocker: Closeable? = null
        try {
            System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = true)
            val available = MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore
            blocker = MappedCallSiteStringIndexMemoryBudget.tryReserve(available - MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES)
            assertNotNull(blocker)

            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(loaded.prepareCallSiteStringIndex())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(
                    MappedCallSiteStringIndexMemoryBudget.maxBytes - MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES,
                    MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                repeat(2) {
                    repeat(distinctRows) { nodeId ->
                        val expected = "unique${nodeId.toString().padStart(4, '0')}needle"
                        assertEquals(
                            listOf(nodeId),
                            loaded
                                .nodesByStringPropertyDisjunction(
                                    CallSiteNode::class.java,
                                    listOf(
                                        StringPropertyPredicate(
                                            CALLER_NAME_PROPERTY, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, expected)),
                                    limit = 1,
                                    workConsumer = mappedSplit)
                                .orEmpty()
                                .map { node -> node.id.value }
                                .toList())
                    }
                }
                assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertTrue(loaded.mappedPostingRangeValidationCount() in 1..MAPPED_POSTING_RANGE_VALIDATION_CACHE_CAPACITY)
                assertEquals(MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES, loaded.mappedPostingRangeValidationBytes())
                assertEquals(MappedCallSiteStringIndexMemoryBudget.maxBytes, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                loaded.clearStringPropertyIndexes()
                assertEquals(0, loaded.mappedPostingRangeValidationCount())
                assertEquals(0L, loaded.mappedPostingRangeValidationBytes())
                assertEquals(
                    MappedCallSiteStringIndexMemoryBudget.maxBytes - MAPPED_POSTING_RANGE_VALIDATION_CACHE_RETAINED_BYTES,
                    MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            } finally {
                loaded.close()
            }

            blocker?.close()
            blocker = MappedCallSiteStringIndexMemoryBudget.tryReserve(available)
            assertNotNull(blocker)
            val uncached = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(uncached.prepareCallSiteStringIndex())
                assertFalse(uncached.isCallSiteStringIndexInitialized())
                repeat(64) { nodeId ->
                    val expected = "unique${nodeId.toString().padStart(4, '0')}needle"
                    assertEquals(
                        listOf(nodeId),
                        uncached
                            .nodesByStringPropertyDisjunction(
                                CallSiteNode::class.java,
                                listOf(
                                    StringPropertyPredicate(
                                        CALLER_NAME_PROPERTY, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, expected)),
                                limit = 1,
                                workConsumer = mappedSplit)
                            .orEmpty()
                            .map { node -> node.id.value }
                            .toList())
                }
                assertTrue(uncached.isMappedCallSiteStringIndexViewInitialized())
                assertEquals(0, uncached.mappedPostingRangeValidationCount())
                assertEquals(0L, uncached.mappedPostingRangeValidationBytes())
                assertEquals(MappedCallSiteStringIndexMemoryBudget.maxBytes, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            } finally {
                uncached.close()
            }
            assertEquals(MappedCallSiteStringIndexMemoryBudget.maxBytes, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally {
            blocker?.close()
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `dense mapped matches scan ordered prefix waves and corrupt counts fall back`() {
        val returnType = TypeDescriptor("void")
        val firstMatch = 11_000
        val matchCount = 200
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(32_768) { nodeId ->
                        val callerClass =
                            if (nodeId in firstMatch until firstMatch + matchCount) {
                                "example.LocalizedNeedleCaller"
                            } else {
                                "example.OtherCaller"
                            }
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(TypeDescriptor(callerClass), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val orderedGraph =
            object : Graph by graph {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = graph.nodes(type).sortedBy { node -> node.id.value }
            }
        val predicate =
            StringPropertyPredicate(CALLER_CLASS_PROPERTY, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "localizedneedle")
        val dir = Files.createTempDirectory("webgraph-dense-prefix-waves")
        try {
            System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
            GraphStore.save(orderedGraph, dir, prepareCallSiteStringIndex = true)

            fun queryWork(expectPersistedIndex: Boolean): Pair<List<Int>, Long> {
                val work = AtomicLong()
                val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
                return loaded.use {
                    val scanThreads = ConcurrentHashMap.newKeySet<String>()
                    val scanWorkersStarted = CountDownLatch(3)
                    val ids =
                        loaded
                            .nodesByStringPropertyDisjunction(
                                CallSiteNode::class.java,
                                listOf(predicate),
                                limit = matchCount,
                                workConsumer =
                                    object : SplitGraphWorkBatchConsumer {
                                        override val segmentWorkerCount: Int = 2

                                        override fun consume(workUnits: Long) {
                                            work.addAndGet(workUnits)
                                            if (loaded.callSiteScanActiveWorkers() > 0 && scanThreads.add(Thread.currentThread().name)) {
                                                scanWorkersStarted.countDown()
                                                assertTrue(scanWorkersStarted.await(5, TimeUnit.SECONDS))
                                            }
                                        }
                                    })
                            .orEmpty()
                            .map { node -> node.id.value }
                            .toList()
                    assertEquals(1L, loaded.callSiteParallelScanCount())
                    assertEquals(3, loaded.callSiteScanPeakActiveWorkers())
                    assertEquals(3, scanThreads.size)
                    assertEquals(0, loaded.callSiteScanActiveWorkers())
                    assertEquals(expectPersistedIndex, loaded.isCallSiteStringIndexLoadedFromPersistence())
                    ids to work.get()
                }
            }

            val expected = (firstMatch until firstMatch + matchCount).toSet()
            val (prefixIds, prefixWork) = queryWork(expectPersistedIndex = true)
            assertEquals(expected, prefixIds.toSet())
            assertEquals(matchCount, prefixIds.size)

            val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            val corruptIndex = Files.readAllBytes(indexFile)
            val header = ByteBuffer.wrap(corruptIndex).order(ByteOrder.BIG_ENDIAN)
            val callerUniqueCount = header.getInt(4 * Int.SIZE_BYTES + CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
            val postingEndsOffset = CALL_SITE_STRING_INDEX_HEADER_BYTES + callerUniqueCount * Int.SIZE_BYTES
            repeat(callerUniqueCount) { row -> header.putInt(postingEndsOffset + row * Int.SIZE_BYTES, 0) }
            Files.write(indexFile, corruptIndex)

            val (fallbackIds, fallbackWork) = queryWork(expectPersistedIndex = false)
            assertEquals(prefixIds, fallbackIds)
            assertTrue(prefixWork > 0L)
            assertTrue(fallbackWork > 0L)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
