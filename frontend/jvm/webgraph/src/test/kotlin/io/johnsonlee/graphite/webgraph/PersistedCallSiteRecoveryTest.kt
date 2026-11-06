package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies persisted call site recovery. */
class PersistedCallSiteRecoveryTest : GraphStoreTestFixture() {

    @Test
    fun `corrupt and missing persisted CallSite indexes rebuild atomically then restore`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(0),
                        MethodDescriptor(TypeDescriptor("example.TargetCaller"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        1,
                        null,
                        emptyList()))
                .build()
        val predicate = StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")
        val dir = Files.createTempDirectory("webgraph-persisted-callsite-index-rebuild")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "true")
            GraphStore.save(graph, dir)
            val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            val validIndex = Files.readAllBytes(indexFile)
            Files.write(indexFile, validIndex + byteArrayOf(1))
            val rebuiltAfterTrailingData = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(rebuiltAfterTrailingData.isCallSiteStringIndexLoadedFromPersistence())
                assertEquals(
                    listOf(0),
                    rebuiltAfterTrailingData
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertEquals(validIndex.size.toLong(), Files.size(indexFile))
            } finally {
                rebuiltAfterTrailingData.close()
            }
            val restoredAfterTrailingData = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(restoredAfterTrailingData.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                restoredAfterTrailingData.close()
            }
            val corrupted = Files.readAllBytes(indexFile)
            corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 1).toByte()
            Files.write(indexFile, corrupted)

            val rebuilt = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(rebuilt.isCallSiteStringIndexLoadedFromPersistence())
                assertTrue(rebuilt.isCallSiteTrigramIndexInitialized())
                assertEquals(
                    listOf(0),
                    rebuilt
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertEquals(corrupted.size.toLong(), Files.size(indexFile))
            } finally {
                rebuilt.close()
            }
            val restoredAfterCorruption = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(restoredAfterCorruption.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                restoredAfterCorruption.close()
            }

            Files.delete(indexFile)
            val migratedLegacyGraph = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(migratedLegacyGraph.isCallSiteStringIndexLoadedFromPersistence())
                assertTrue(Files.isRegularFile(indexFile))
            } finally {
                migratedLegacyGraph.close()
            }
            val restoredAfterMigration = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(restoredAfterMigration.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                restoredAfterMigration.close()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `persisted CallSite index is rejected when it belongs to another graph`() {
        val returnType = TypeDescriptor("void")
        fun graphWithTargetAt(targetNodeId: Int): Graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(2) { nodeId ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(
                                    TypeDescriptor(if (nodeId == targetNodeId) "example.TargetCaller" else "example.OtherCaller"),
                                    "call",
                                    emptyList(),
                                    returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")
        val graphADir = Files.createTempDirectory("webgraph-callsite-index-owner-a")
        val graphBDir = Files.createTempDirectory("webgraph-callsite-index-owner-b")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, "true")
            GraphStore.save(graphWithTargetAt(0), graphADir)
            GraphStore.save(graphWithTargetAt(1), graphBDir)
            Files.copy(
                graphADir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE),
                graphBDir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)

            val rebuilt = GraphStore.loadMapped(graphBDir) as MappedWebGraphBackedGraph
            try {
                assertFalse(rebuilt.isCallSiteStringIndexLoadedFromPersistence())
                assertEquals(
                    listOf(1),
                    rebuilt
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
            } finally {
                rebuilt.close()
            }

            System.clearProperty(property)
            val lazilyRestored = GraphStore.loadMapped(graphBDir) as MappedWebGraphBackedGraph
            try {
                assertFalse(lazilyRestored.isCallSiteStringIndexInitialized())
                assertEquals(
                    listOf(1),
                    lazilyRestored
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertTrue(lazilyRestored.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                lazilyRestored.close()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            graphADir.toFile().deleteRecursively()
            graphBDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `first lazy persisted CallSite restore charges graph identity validation to query work`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4_096) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller$index"), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.EQUALS, "example.caller1")
        val dir = Files.createTempDirectory("webgraph-callsite-index-identity-budget")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            GraphStore.save(graph, dir, compressionThreads = 2, prepareCallSiteStringIndex = true)
            assertTrue(Files.isRegularFile(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            Files.delete(dir.resolve(StringTable.CONTENT_IDENTITY_FILE_NAME))
            Files.delete(dir.resolve(CALL_SITE_STRING_CONTENT_IDENTITY_FILE))
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                val deniedIdentityBatches = AtomicLong()
                repeat(2) {
                    assertFailsWith<IllegalStateException> {
                        loaded.aggregateStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            distinctProperty = null,
                            workConsumer =
                                GraphWorkConsumer {
                                    deniedIdentityBatches.incrementAndGet()
                                    error("query work denied")
                                })
                    }
                    assertFalse(loaded.isCallSiteStringIndexInitialized())
                    assertFalse(loaded.isCallSiteStringIndexLoadedFromPersistence())
                }
                assertEquals(2L, deniedIdentityBatches.get())

                assertEquals(
                    1L,
                    loaded.aggregateStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), distinctProperty = null)?.count)
                assertTrue(loaded.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                loaded.close()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `lazy persisted CallSite restore charges sidecar read validation and checksum work`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4_096) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller$index"), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.EQUALS, "example.caller1")
        val dir = Files.createTempDirectory("webgraph-callsite-index-sidecar-budget")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        fun measuredLookup(eager: Boolean): Long {
            if (eager) System.setProperty(property, "true") else System.clearProperty(property)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            return try {
                val work = AtomicLong()
                val aggregate =
                    loaded.aggregateStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate),
                        distinctProperty = null,
                        workConsumer =
                            object : GraphWorkBatchConsumer {
                                override fun consume(workUnits: Long) {
                                    work.addAndGet(workUnits)
                                }
                            })
                assertEquals(1L, aggregate?.count)
                work.get()
            } finally {
                loaded.close()
            }
        }
        try {
            System.clearProperty(property)
            GraphStore.save(graph, dir, compressionThreads = 2, prepareCallSiteStringIndex = true)
            val preparedWork = measuredLookup(eager = true)
            val lazyWork = measuredLookup(eager = false)
            assertTrue(lazyWork > preparedWork + 4_096L)

            System.clearProperty(property)
            val restoreBatches = mutableListOf<Long>()
            val measuredRestore = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(
                    measuredRestore.prepareCallSiteStringIndex(
                        object : GraphWorkBatchConsumer {
                            override fun consume(workUnits: Long) {
                                restoreBatches += workUnits
                            }
                        }))
                assertEquals(1L, restoreBatches.last(), "restore batches=$restoreBatches")
            } finally {
                measuredRestore.close()
            }

            val retainedBeforeRejectedRestore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
            val rejectedRestore = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                var batchIndex = 0
                assertFailsWith<IllegalStateException> {
                    rejectedRestore.prepareCallSiteStringIndex(
                        object : GraphWorkBatchConsumer {
                            override fun consume(workUnits: Long) {
                                assertEquals(restoreBatches[batchIndex], workUnits)
                                if (batchIndex++ == restoreBatches.lastIndex) error("EOF work denied")
                            }
                        })
                }
                assertEquals(restoreBatches.size, batchIndex)
                assertFalse(rejectedRestore.isCallSiteStringIndexInitialized())
                assertFalse(rejectedRestore.isCallSiteStringIndexLoadedFromPersistence())
                assertEquals(retainedBeforeRejectedRestore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            } finally {
                rejectedRestore.close()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interrupted lazy persisted CallSite restore escapes without publishing the sidecar`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4_096) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller$index"), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "never-present")
        val dir = Files.createTempDirectory("webgraph-callsite-index-sidecar-interruption")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            GraphStore.save(graph, dir, compressionThreads = 2, prepareCallSiteStringIndex = true)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val sidecarBatchReached = CountDownLatch(1)
                val failure = AtomicReference<Throwable>()
                val interruptedFlag = AtomicBoolean()
                val request = Thread {
                    try {
                        loaded.aggregateStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(predicate),
                            distinctProperty = null,
                            workConsumer =
                                object : GraphWorkBatchConsumer {
                                    override fun consume(workUnits: Long) {
                                        if (workUnits >= 1_024L && sidecarBatchReached.count > 0L) {
                                            sidecarBatchReached.countDown()
                                            while (!Thread.currentThread().isInterrupted) Thread.onSpinWait()
                                        }
                                    }
                                })
                        failure.set(AssertionError("Interrupted sidecar restore completed normally"))
                    } catch (error: Throwable) {
                        failure.set(error)
                        interruptedFlag.set(Thread.currentThread().isInterrupted)
                    }
                }
                request.start()
                assertTrue(sidecarBatchReached.await(5, TimeUnit.SECONDS))
                request.interrupt()
                request.join(5_000)
                assertFalse(request.isAlive)
                assertTrue(failure.get() is CancellationException, failure.get()?.stackTraceToString() ?: "No failure was captured")
                assertTrue(interruptedFlag.get())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertFalse(loaded.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                loaded.close()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped load preparation budget denial preserves CallSite query correctness`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(0),
                        MethodDescriptor(TypeDescriptor("example.TargetCaller"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        1,
                        null,
                        emptyList()))
                .build()
        val dir = Files.createTempDirectory("webgraph-eager-callsite-index-budget")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        var blocker: Closeable? = null
        try {
            System.setProperty(property, "true")
            GraphStore.save(graph, dir)
            assertTrue(Files.isRegularFile(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            blocker = MappedCallSiteStringIndexMemoryBudget.tryReserve(MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore)
            assertNotNull(blocker)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(
                                StringPropertyPredicate(
                                    "caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertFalse(loaded.isCallSiteStringIndexInitialized())
            } finally {
                loaded.close()
            }
            assertEquals(MappedCallSiteStringIndexMemoryBudget.maxBytes, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally {
            blocker?.close()
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
            dir.toFile().deleteRecursively()
        }
    }
}
