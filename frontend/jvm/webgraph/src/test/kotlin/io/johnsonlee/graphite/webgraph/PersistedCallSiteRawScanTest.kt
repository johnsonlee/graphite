package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.PreferredRawGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SerialGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Verifies persisted call site raw scan. */
class PersistedCallSiteRawScanTest : GraphStoreTestFixture() {

    @Test
    @Suppress("LongMethod")
    fun `split raw scans reuse exact matches from the existing persisted index`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4_096) { nodeId ->
                        val callerClass =
                            if (nodeId == 0 || nodeId == 2) {
                                "example.TargetCaller"
                            } else {
                                "example.OtherCaller$nodeId"
                            }
                        val callerName = if (nodeId == 1) "targetOnlyInCallerName" else "call$nodeId"
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(TypeDescriptor(callerClass), callerName, emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate(CALLER_CLASS_PROPERTY, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")
        val split =
            object : SplitGraphWorkBatchConsumer {
                override val segmentWorkerCount: Int = 1

                override fun consume(workUnits: Long) = Unit
            }
        val mappedSplit =
            object : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
                override val segmentWorkerCount: Int = 1

                override fun consume(workUnits: Long) = Unit
            }
        val dir = Files.createTempDirectory("webgraph-existing-callsite-index-split")
        try {
            System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = true)
            val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            assertTrue(Files.isRegularFile(indexFile))

            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertNull(
                    loaded.projectStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate),
                        projectedProperties = listOf(CALLER_CLASS_PROPERTY),
                        limit = 1,
                        workConsumer = mappedSplit))
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = mappedSplit)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(0L, loaded.callSiteParallelScanCount())
                assertEquals(
                    listOf(0, 2),
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate, predicate.copy(property = CALLER_NAME_PROPERTY)),
                            limit = 2,
                            workConsumer = mappedSplit)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertEquals(0L, loaded.callSiteParallelScanCount())
                assertEquals(
                    listOf(
                        listOf("example.TargetCaller", "call0"),
                        listOf("example.TargetCaller", "call2"),
                        listOf("example.OtherCaller1", "targetOnlyInCallerName")),
                    loaded
                        .projectStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate, predicate.copy(property = CALLER_NAME_PROPERTY)),
                            projectedProperties = listOf(CALLER_CLASS_PROPERTY, CALLER_NAME_PROPERTY),
                            limit = 3,
                            workConsumer = mappedSplit)
                        ?.map { row -> row.values })
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                val mixedPredicates = listOf(predicate, predicate.copy(property = CALLER_NAME_PROPERTY, expected = "targetonly"))
                assertEquals(
                    listOf(0, 2),
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, mixedPredicates, limit = 2, workConsumer = mappedSplit)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertEquals(
                    listOf(0, 2, 1),
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, mixedPredicates, limit = 4, workConsumer = mappedSplit)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertEquals(2L, loaded.callSiteParallelScanCount())
                assertEquals(
                    listOf(listOf("example.TargetCaller")),
                    loaded
                        .distinctStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            projectedProperties = listOf(CALLER_CLASS_PROPERTY),
                            limit = 1,
                            selectedValues = setOf(listOf("example.TargetCaller")),
                            workConsumer = mappedSplit)
                        ?.map { row -> row.values })
                assertEquals(
                    emptyList(),
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate),
                        projectedProperties = listOf(CALLER_CLASS_PROPERTY),
                        limit = 1,
                        selectedValues = setOf(listOf("targetOnlyInCallerName")),
                        workConsumer = mappedSplit))
                loaded.releaseStringPropertyDisjunctionCache()
                assertTrue(loaded.isMappedCallSiteStringIndexViewInitialized())
                loaded.clearStringPropertyIndexes()
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
            }

            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = split)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertTrue(loaded.isCallSiteStringIndexLoadedFromPersistence())
                assertEquals(
                    listOf(listOf("example.TargetCaller")),
                    loaded
                        .distinctStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            projectedProperties = listOf(CALLER_CLASS_PROPERTY),
                            limit = 1,
                            workConsumer = split)
                        ?.map { row -> row.values })
                assertEquals(
                    listOf(listOf("example.TargetCaller")),
                    loaded
                        .distinctStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            projectedProperties = listOf(CALLER_CLASS_PROPERTY),
                            limit = 1,
                            selectedValues = setOf(listOf("example.TargetCaller")),
                            workConsumer = split)
                        ?.map { row -> row.values })
                assertEquals(
                    emptyList(),
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate),
                        projectedProperties = listOf(CALLER_CLASS_PROPERTY),
                        limit = 1,
                        // The value exists in the dictionary but not in caller_class postings.
                        selectedValues = setOf(listOf("targetOnlyInCallerName")),
                        workConsumer = split))
                assertEquals(
                    listOf(listOf(null)),
                    loaded
                        .distinctStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            projectedProperties = listOf("graphId"),
                            limit = 3,
                            selectedValues = linkedSetOf(emptyList(), listOf("invalid"), listOf(null)),
                            workConsumer = SerialGraphWorkBatchConsumer {})
                        ?.map { row -> row.values })
                assertEquals(
                    listOf(listOf(null)),
                    loaded
                        .distinctStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            projectedProperties = listOf("graphId"),
                            limit = 1,
                            selectedValues = setOf(listOf(null)),
                            workConsumer = SerialGraphWorkBatchConsumer {})
                        ?.map { row -> row.values })
                assertTrue(
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate.copy(expected = "definitely-not-present")),
                            limit = 1,
                            workConsumer = split)
                        .orEmpty()
                        .none())
                assertTrue(
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate.copy(property = CALLEE_NAME_PROPERTY)),
                            limit = 1,
                            workConsumer = split)
                        .orEmpty()
                        .none())
            }

            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { rejected ->
                val rejection = IllegalStateException("persisted index work rejected")
                val failure =
                    assertFailsWith<IllegalStateException> {
                        rejected
                            .nodesByStringPropertyDisjunction(
                                CallSiteNode::class.java,
                                listOf(predicate),
                                limit = 1,
                                workConsumer =
                                    object : SplitGraphWorkBatchConsumer {
                                        override val segmentWorkerCount: Int = 1

                                        override fun consume(workUnits: Long) = throw rejection
                                    })
                            ?.toList()
                    }
                assertSame(rejection, failure)
                assertFalse(rejected.isCallSiteStringIndexInitialized())
            }

            val originalIndex = Files.readAllBytes(indexFile)
            val corruptIndex = originalIndex.copyOf()
            corruptIndex[corruptIndex.size / 2] = (corruptIndex[corruptIndex.size / 2].toInt() xor 1).toByte()
            Files.write(indexFile, corruptIndex)
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = mappedSplit)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertFalse(loaded.isMappedCallSiteStringIndexViewInitialized())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(1L, loaded.callSiteParallelScanCount())
            }
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = split)
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertFalse(loaded.isCallSiteStringIndexLoadedFromPersistence())
                assertEquals(1L, loaded.callSiteParallelScanCount())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bounded raw projection reuses matches even when the persisted index is loaded`() {
        val dir = Files.createTempDirectory("graph-store-raw-projection-cache")
        try {
            val returnType = TypeDescriptor("void")
            val source =
                DefaultGraph.Builder()
                    .apply {
                        repeat(2_048) { index ->
                            addNode(
                                CallSiteNode(
                                    NodeId(index),
                                    MethodDescriptor(TypeDescriptor("example.NeedleCaller$index"), "call$index", emptyList(), returnType),
                                    MethodDescriptor(TypeDescriptor("example.Target$index"), "invoke", emptyList(), returnType),
                                    index,
                                    null,
                                    emptyList()))
                        }
                    }
                    .build()
            GraphStore.save(source, dir)
            val graph = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            val predicates = listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Needle"))
            val firstWork = AtomicLong()
            val first =
                graph.projectStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    listOf("caller_class"),
                    2,
                    PreferredRawGraphWorkBatchConsumer(firstWork::addAndGet))
            assertEquals(2, first?.size)
            assertTrue(first.orEmpty().all { row -> row.values.single()?.startsWith("example.NeedleCaller") == true })
            assertEquals(2L, firstWork.get())
            assertEquals(1, graph.rawProjectionMatchCount())

            val cachedWork = AtomicLong()
            val cached =
                graph.projectStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    predicates,
                    listOf("callee_class"),
                    2,
                    PreferredRawGraphWorkBatchConsumer(cachedWork::addAndGet))
            assertEquals(2, cached?.size)
            assertTrue(cached.orEmpty().all { row -> row.values.single()?.startsWith("example.Target") == true })
            assertEquals(2L, cachedWork.get())
            assertEquals(1, graph.rawProjectionMatchCount())
            assertFalse(graph.isCallSiteStringIndexInitialized())

            val missWork = AtomicLong()
            val inconclusive =
                graph.projectStringPropertyDisjunction(
                    CallSiteNode::class.java,
                    listOf(StringPropertyPredicate("caller_name", null, StringMatchMode.CONTAINS, "absent")),
                    listOf("caller_class"),
                    200,
                    PreferredRawGraphWorkBatchConsumer(missWork::addAndGet))
            assertNull(inconclusive)
            assertEquals(800L, missWork.get())
            assertFalse(graph.isCallSiteStringIndexInitialized())

            assertTrue(graph.prepareCallSiteStringIndex())
            graph.resetCallSiteScanMetrics()
            val retainedRaw =
                graph.projectStringPropertyDisjunction(
                    CallSiteNode::class.java, predicates, listOf("caller_class"), 2, PreferredRawGraphWorkBatchConsumer {})
            assertEquals(first, retainedRaw)
            assertEquals(0L, graph.callSiteStringIndexLookupCount())
            assertTrue(graph.isCallSiteStringIndexInitialized())
            graph.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persisted raw disjunction scans properties through the shared segment pool`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(3) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller$index"), "call$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency$index"), "invoke$index", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-split-raw-index")
        try {
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = true)
            assertTrue(Files.isRegularFile(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                val restoredIds =
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.EQUALS, "example.Caller1")),
                            limit = 1,
                            workConsumer =
                                object : SplitGraphWorkBatchConsumer {
                                    override val segmentWorkerCount: Int = 1

                                    override fun consume(workUnits: Long) = Unit
                                })
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList()
                assertEquals(listOf(1), restoredIds)
                assertTrue(loaded.isCallSiteStringIndexLoadedFromPersistence())
                assertTrue(loaded.prepareCallSiteStringIndex())
                assertTrue(loaded.isCallSiteStringIndexLoadedFromPersistence())
                val threads = ConcurrentHashMap.newKeySet<String>()
                val workersStarted = CountDownLatch(4)
                val result =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf("caller_class", "caller_name", "callee_class", "callee_name").map { property ->
                            StringPropertyPredicate(property, null, StringMatchMode.CONTAINS, "qz")
                        },
                        projectedProperties = listOf("caller_class"),
                        limit = 1,
                        workConsumer =
                            object : SplitGraphWorkBatchConsumer {
                                override val segmentWorkerCount: Int = 3

                                override fun consume(workUnits: Long) {
                                    if (threads.add(Thread.currentThread().name)) workersStarted.countDown()
                                    check(workersStarted.await(5, TimeUnit.SECONDS))
                                }
                            })

                assertTrue(result.orEmpty().isEmpty())
                assertEquals(4, threads.size)
                assertTrue(threads.count { thread -> thread.startsWith("graphite-callsite-segment-") } == 3)
                assertTrue(loaded.isCallSiteStringIndexLoadedFromPersistence())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
