package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies persisted call site loading. */
class PersistedCallSiteLoadingTest : GraphStoreTestFixture() {

    @Test
    fun `mapped load restores persisted CallSite index lazily and can opt in to eager preparation`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(3) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(
                                    TypeDescriptor(if (index == 1) "example.TargetCaller" else "example.Caller$index"),
                                    "call$index",
                                    emptyList(),
                                    returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicate = StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "target")
        val dir = Files.createTempDirectory("webgraph-eager-callsite-index")
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        try {
            System.clearProperty(property)
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = true)
            val indexFile = dir.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            assertTrue(Files.isRegularFile(indexFile))
            val persistedModificationTime = Files.getLastModifiedTime(indexFile)
            val projectionRestored = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(projectionRestored.isCallSiteStringIndexInitialized())
                val projection =
                    projectionRestored.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(predicate.copy(mode = StringMatchMode.EQUALS, expected = "example.targetcaller")),
                        projectedProperties = listOf("caller_class", "graphId"),
                        limit = 2,
                        workConsumer =
                            object : SplitGraphWorkBatchConsumer {
                                override val segmentWorkerCount: Int = 1

                                override fun consume(workUnits: Long) = Unit
                            })
                assertEquals(listOf(listOf("example.TargetCaller", null)), projection?.map { it.values })
                assertTrue(projectionRestored.isCallSiteStringIndexInitialized())
                assertTrue(projectionRestored.isCallSiteStringIndexLoadedFromPersistence())
                assertEquals(0L, projectionRestored.callSiteParallelScanCount())
                assertEquals(0L, projectionRestored.callSiteStringIndexLookupCount())
                assertEquals(1L, projectionRestored.callSiteStringProjectionLookupCount())
                projectionRestored.releaseStringPropertyDisjunctionCache()
                assertTrue(projectionRestored.isCallSiteStringIndexInitialized())
            } finally {
                projectionRestored.close()
            }
            System.clearProperty(property)
            val lazyRestored = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(lazyRestored.isCallSiteStringIndexInitialized())
                assertEquals(3, lazyRestored.nodes(CallSiteNode::class.java).count())
                assertFalse(lazyRestored.isCallSiteStringIndexInitialized())
                assertEquals(
                    listOf(1),
                    lazyRestored
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertSerialProjectionRestoresPersistedSidecar(lazyRestored)
                assertFalse(lazyRestored.isCallSiteStringIndexInitialized())
                lazyRestored.clearStringPropertyIndexes()
                assertFalse(lazyRestored.isCallSiteStringIndexInitialized())
                assertEquals(
                    listOf(1),
                    lazyRestored
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertTrue(lazyRestored.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                lazyRestored.close()
            }
            assertEquals(persistedModificationTime, Files.getLastModifiedTime(indexFile))
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())

            System.setProperty(property, "true")
            val eager = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(eager.isCallSiteStringIndexInitialized())
                assertTrue(eager.isCallSiteTrigramIndexInitialized())
                assertTrue(eager.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                eager.close()
            }

            System.setProperty(property, "false")
            val lazy = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertFalse(lazy.isCallSiteStringIndexInitialized())
                assertFalse(lazy.isCallSiteTrigramIndexInitialized())
                assertEquals(
                    listOf(1),
                    lazy
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, listOf(predicate))
                        .orEmpty()
                        .map { node -> node.id.value }
                        .toList())
                assertTrue(lazy.isCallSiteStringIndexInitialized())
                assertTrue(lazy.isCallSiteTrigramIndexInitialized())
                assertFalse(lazy.isCallSiteStringIndexLoadedFromPersistence())
            } finally {
                lazy.close()
            }
            assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @Suppress("LongMethod")
    fun `persisted CallSite index resolves selected four-property tuples with a bounded exact lookup`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(4_096) { index ->
                        val duplicateFirstTuple = index == 4_094
                        val tupleIndex = if (duplicateFirstTuple) 0 else index
                        val callerClass =
                            when {
                                index == 4_095 -> "example.XA\u039F\u03A3Y"
                                else -> "example.TargetCaller${tupleIndex % 256}"
                            }
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor(callerClass), "call$tupleIndex", emptyList(), returnType),
                                MethodDescriptor(
                                    TypeDescriptor("example.Dependency${tupleIndex % 512}"), "invoke$tupleIndex", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val selectedValues =
            (0 until 200).mapTo(linkedSetOf()) { index ->
                listOf("example.TargetCaller${index % 256}", "call$index", "example.Dependency${index % 512}", "invoke$index")
            }
        val dir = Files.createTempDirectory("webgraph-exact-callsite-projection-tuples")
        try {
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = true)
            System.clearProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(loaded.prepareCallSiteStringIndex())
                assertFalse(loaded.hasExactCallSiteProjectionTupleIndex())
                val work = AtomicLong()
                val selected =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "TargetCaller")),
                        projectedProperties = listOf("caller_class", "caller_name", "callee_class", "callee_name"),
                        limit = 50,
                        selectedValues = selectedValues,
                        workConsumer = GraphWorkConsumer { work.incrementAndGet() })

                val selectedRows = assertNotNull(selected)
                assertEquals(50, selectedRows.size)
                assertTrue(selectedRows.all { row -> row.values in selectedValues })
                assertTrue(selectedRows.zipWithNext().all { (left, right) -> left.encounterOrder <= right.encounterOrder })
                assertTrue(work.get() in 50L..10_000L, "selected tuple lookup work=${work.get()}")
                assertFalse(loaded.hasExactCallSiteProjectionTupleIndex())

                val largeSelection =
                    (0 until 300).mapTo(linkedSetOf()) { index ->
                        listOf("example.TargetCaller${index % 256}", "call$index", "example.Dependency${index % 512}", "invoke$index")
                    }
                val indexed =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "TargetCaller")),
                        projectedProperties = listOf("caller_class", "caller_name", "callee_class", "callee_name"),
                        limit = 50,
                        selectedValues = largeSelection,
                        workConsumer = GraphWorkConsumer {})
                assertEquals(50, indexed?.size)
                assertTrue(loaded.hasExactCallSiteProjectionTupleIndex())

                val splitWork = AtomicLong()
                val selectedGraphId =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_name", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "call")),
                        projectedProperties = listOf("graphId"),
                        limit = 1,
                        selectedValues = setOf(listOf(null)),
                        workConsumer =
                            object : SplitGraphWorkBatchConsumer {
                                override val segmentWorkerCount: Int = 1

                                override fun consume(workUnits: Long) {
                                    splitWork.addAndGet(workUnits)
                                }
                            })
                assertEquals(listOf(listOf(null)), selectedGraphId?.map { row -> row.values })
                assertTrue(splitWork.get() >= 4_096L)
                assertTrue(loaded.callSiteSegmentPeakActiveWorkers() >= 1)
                assertTrue(loaded.hasExactCallSiteProjectionTupleIndex())

                val nonMatchingTuple = setOf(selectedValues.first())
                val filtered =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_name", null, StringMatchMode.CONTAINS, "call1")),
                        projectedProperties = listOf("caller_class", "caller_name", "callee_class", "callee_name"),
                        limit = 1,
                        selectedValues = nonMatchingTuple,
                        workConsumer = GraphWorkConsumer {})
                assertTrue(filtered.orEmpty().isEmpty())

                val reversedValues = selectedValues.reversed().toCollection(linkedSetOf())
                val canonical =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "TargetCaller")),
                        projectedProperties = listOf("caller_class", "caller_name", "callee_class", "callee_name"),
                        limit = 50,
                        selectedValues = reversedValues,
                        workConsumer = GraphWorkConsumer {})
                assertEquals(selectedRows, canonical)

                val unicode =
                    loaded.distinctStringPropertyDisjunction(
                        CallSiteNode::class.java,
                        listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "A\u039F\u03A3")),
                        projectedProperties = listOf("caller_class"),
                        limit = 1,
                        workConsumer = GraphWorkConsumer {})
                assertEquals(listOf(listOf("example.XA\u039F\u03A3Y")), unicode?.map { row -> row.values })
            } finally {
                loaded.close()
            }

            val budgeted = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(budgeted.prepareCallSiteStringIndex())
                assertFalse(budgeted.hasExactCallSiteProjectionTupleIndex())
                val consumed = AtomicLong()
                val failure =
                    assertFailsWith<IllegalStateException> {
                        budgeted.distinctStringPropertyDisjunction(
                            CallSiteNode::class.java,
                            listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "TargetCaller")),
                            projectedProperties = listOf("caller_class", "caller_name", "callee_class", "callee_name"),
                            limit = 50,
                            selectedValues = selectedValues,
                            workConsumer = GraphWorkConsumer { check(consumed.incrementAndGet() < 100) { "tuple build budget exceeded" } })
                    }
                assertEquals("tuple build budget exceeded", failure.message)
                assertFalse(budgeted.hasExactCallSiteProjectionTupleIndex())
            } finally {
                budgeted.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
