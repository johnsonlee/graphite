package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.PreferredPersistedStringIndexGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies persisted call site publication. */
class PersistedCallSitePublicationTest : GraphStoreTestFixture() {

    @Test
    fun `default save does not materialize the optional CallSite index`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(0),
                        MethodDescriptor(TypeDescriptor("example.Caller"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        1,
                        null,
                        emptyList()))
                .build()
        val dir = Files.createTempDirectory("webgraph-default-save-no-callsite-index")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(loaded.persistPreparedCallSiteStringIndex())
            } finally {
                loaded.close()
            }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default lazy CallSite query persists a complete index for the next mapped process`() {
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
        val dir = Files.createTempDirectory("webgraph-lazy-callsite-index-migration")
        val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(indexFile))

            val migrated = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(migrated.isCallSiteStringIndexInitialized())
                assertEquals(1L, migrated.aggregateStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), null)?.count)
                assertTrue(migrated.isCallSiteTrigramIndexInitialized())
                assertFalse(migrated.isCallSiteStringIndexLoadedFromPersistence())
                assertFalse(Files.exists(indexFile))
            } finally {
                migrated.close()
            }
            assertTrue(Files.isRegularFile(indexFile))
            val persistedModificationTime = Files.getLastModifiedTime(indexFile)

            val restored = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(restored.isCallSiteStringIndexInitialized())
                assertTrue(restored.hasPreparedStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate)))
                assertEquals(
                    1,
                    restored
                        .nodesByStringPropertyDisjunction(
                            CallSiteNode::class.java, listOf(predicate), 1, PreferredPersistedStringIndexGraphWorkBatchConsumer {})
                        .orEmpty()
                        .count())
                assertTrue(restored.isCallSiteStringIndexLoadedFromPersistence())
                assertEquals(0L, restored.callSiteParallelScanCount())
                assertEquals(1L, restored.callSiteStringIndexLookupCount())
            } finally {
                restored.close()
            }
            assertEquals(persistedModificationTime, Files.getLastModifiedTime(indexFile))
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preferred persisted lookup builds once in parallel then reuses the retained index`() {
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
        val predicate =
            StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "definitely-absent")
        val dir = Files.createTempDirectory("webgraph-preferred-persisted-callsite-index")
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        try {
            GraphStore.save(graph, dir)
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                val consumer = PreferredPersistedStringIndexGraphWorkBatchConsumer {}

                assertTrue(
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = consumer)
                        .orEmpty()
                        .toList()
                        .isEmpty())
                assertEquals(1L, loaded.callSiteParallelScanCount())
                assertEquals(0L, loaded.callSiteStringIndexLookupCount())
                assertTrue(loaded.isCallSiteStringIndexInitialized())

                assertTrue(
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate), limit = 1, workConsumer = consumer)
                        .orEmpty()
                        .toList()
                        .isEmpty())
                assertEquals(1L, loaded.callSiteParallelScanCount())
                assertEquals(1L, loaded.callSiteStringIndexLookupCount())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }

    @Test
    fun `zero hit query reuses a bounded index and repairs legacy sidecars on graph close`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(8_192) { nodeId ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(TypeDescriptor("example.Caller$nodeId"), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val query =
            """
            MATCH (n:CallSiteNode)
            WHERE toLower(n.caller_class) CONTAINS 'zzz'
            RETURN DISTINCT n.caller_class
            LIMIT 8192
        """
                .trimIndent()
        val dir = Files.createTempDirectory("webgraph-zero-hit-callsite-repair")
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.clearProperty(property)
            GraphStore.save(graph, dir)
            // Forty sources select the balanced graph/segment planner. LIMIT equals the graph size,
            // forcing the query-built structural index lifecycle instead of the bounded raw scan.
            val loadedGraphs = List(40) { GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph }
            try {
                val executor = CrossGraphCypherExecutor(loadedGraphs.mapIndexed { index, loaded -> CypherGraph("legacy-$index", loaded) })
                assertTrue(executor.execute(query).rows.isEmpty())
                loadedGraphs.forEach { loaded ->
                    assertTrue(loaded.isCallSiteStringIndexInitialized())
                    assertTrue(loaded.isCallSiteTrigramIndexInitialized())
                }
                val retainedAfterFirstQuery = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
                assertTrue(retainedAfterFirstQuery > retainedBefore)
                val structuralBytes = loadedGraphs.map { loaded -> loaded.callSiteStringIndexBytes() }
                assertTrue(structuralBytes.all { bytes -> bytes > 1024L * 1024L })
                assertFalse(Files.exists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))

                repeat(3) {
                    assertTrue(executor.execute(query).rows.isEmpty())
                    assertEquals(structuralBytes, loadedGraphs.map { loaded -> loaded.callSiteStringIndexBytes() })
                    assertEquals(retainedAfterFirstQuery, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
                }
            } finally {
                loadedGraphs.forEach(MappedWebGraphBackedGraph::close)
            }
            assertTrue(Files.isRegularFile(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { repaired ->
                val executor = CrossGraphCypherExecutor(listOf(CypherGraph("legacy", repaired)))
                assertTrue(executor.execute(query).rows.isEmpty())
                assertTrue(repaired.isCallSiteStringIndexLoadedFromPersistence())
            }
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interrupted request cache release does not persist a query-built CallSite index`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4_096) { nodeId ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(TypeDescriptor("example.Caller$nodeId"), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-interrupted-callsite-release")
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        try {
            GraphStore.save(graph, dir)
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use { loaded ->
                assertTrue(loaded.prepareCallSiteStringIndex())
                assertFalse(Files.exists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
                Thread.currentThread().interrupt()
                try {
                    assertFailsWith<CancellationException> { loaded.releaseStringPropertyDisjunctionCache() }
                    assertTrue(Thread.currentThread().isInterrupted)
                } finally {
                    Thread.interrupted()
                }
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertFalse(Files.exists(dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)))
            }
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally {
            Thread.interrupted()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `best effort CallSite index persistence failure leaves the prepared query path usable`() {
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
        val dir = Files.createTempDirectory("webgraph-callsite-index-write-failure")
        val movedDir = dir.resolveSibling("${dir.fileName}-moved")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        var loaded: MappedWebGraphBackedGraph? = null
        try {
            System.setProperty(property, "false")
            GraphStore.save(graph, dir)
            loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            assertTrue(loaded.prepareCallSiteStringIndex())
            Files.move(dir, movedDir)

            assertFalse(loaded.persistPreparedCallSiteStringIndex())
            assertEquals(
                listOf(0),
                loaded
                    .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                    .orEmpty()
                    .map { node -> node.id.value }
                    .toList())
        } finally {
            loaded?.close()
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
            movedDir.toFile().deleteRecursively()
        }
    }
}
