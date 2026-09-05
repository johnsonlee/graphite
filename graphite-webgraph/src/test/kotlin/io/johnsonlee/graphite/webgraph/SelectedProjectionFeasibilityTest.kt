package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyDistinctRow
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectedProjectionFeasibilityTest {

    @Test
    fun `missing corresponding property rejects selected tuples before broad predicate work`() {
        for (retained in listOf(false, true)) {
            withMappedGraph { graph ->
                warmMappedView(graph)
                if (retained) assertTrue(graph.prepareCallSiteStringIndex())
                val work = WorkProbe(limit = 64)

                assertEquals(emptyList(), project(graph, setOf(MISSING_PROPERTY), work))

                assertTrue(work.units in 1L..64L)
                assertEquals(0L, graph.callSiteParallelScanCount())
                assertEquals(retained, graph.isCallSiteStringIndexInitialized())
                assertTrue(graph.isMappedCallSiteStringIndexViewInitialized())
            }
        }
    }

    @Test
    fun `independent property membership does not prove that a selected tuple exists`() {
        withMappedGraph { graph ->
            warmMappedView(graph)
            val recombined = listOf("example.Left", "get0000", "example.Target", "invokeRight")
            val work = WorkProbe()

            assertEquals(emptyList(), project(graph, setOf(recombined), work, limit = 1))

            assertTrue(work.units >= LARGE_NODE_COUNT)
            assertEquals(1L, graph.callSiteParallelScanCount())
            assertFalse(graph.isCallSiteStringIndexInitialized())
            val selection = linkedSetOf(recombined, RIGHT, LEFT)
            val expected = referenceValues(graph, selection)
            assertEquals(setOf(LEFT, RIGHT), expected.toSet())
            val actual = project(graph, selection, WorkProbe())
            assertEquals(expected, actual.map { it.values })
            assertTrue(actual[0].encounterOrder < actual[1].encounterOrder)
        }
    }

    @Test
    fun `selected null columns remain null while missing physical values cannot match`() {
        withMappedGraph { graph ->
            warmMappedView(graph)
            val properties = listOf("caller_class", "class", "caller_name", "callee_name", "callee_name")
            val selected = listOf("example.Left", null, "get0000", "invokeLeft", "invokeLeft")
            val result = graph.distinctStringPropertyDisjunction(
                CallSiteNode::class.java, listOf(PREDICATE), properties, 1,
                setOf(selected, listOf("example.Left", null, null, "invokeLeft", "invokeLeft")), WorkProbe()
            )

            assertEquals(listOf(selected), assertNotNull(result).map { it.values })
            assertFalse(graph.isCallSiteStringIndexInitialized())
        }
    }

    @Test
    fun `empty selection does no predicate discovery after the mapped view is validated`() {
        withMappedGraph { graph ->
            warmMappedView(graph)
            val work = WorkProbe(limit = 0)

            assertEquals(emptyList(), project(graph, emptySet(), work))

            assertEquals(0L, work.units)
            assertEquals(0L, graph.callSiteParallelScanCount())
            assertFalse(graph.isCallSiteStringIndexInitialized())
        }
    }

    @Test
    fun `feasibility work still propagates budget exhaustion and cancellation`() {
        withMappedGraph { graph ->
            warmMappedView(graph)
            val failure = assertFailsWith<CypherBudgetExceededException> {
                project(graph, setOf(MISSING_PROPERTY), WorkProbe(limit = 0))
            }
            assertEquals(0L, failure.maxWorkUnits)
            val cancelled = CancellationException("cancel selected feasibility")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                project(graph, setOf(MISSING_PROPERTY), WorkProbe(rejection = cancelled))
            })

            assertEquals(0L, graph.callSiteParallelScanCount())
            assertEquals(emptyList(), project(graph, setOf(MISSING_PROPERTY), WorkProbe(limit = 64)))
            assertEquals(listOf(LEFT), project(graph, setOf(LEFT), WorkProbe(), limit = 1).map { it.values })
        }
    }

    @Test
    fun `even an empty selection validates the persisted index before an early return`() {
        withMappedGraph { graph ->
            assertFalse(graph.isMappedCallSiteStringIndexViewInitialized())
            val cancelled = CancellationException("cancel index validation")
            val failure = assertFails { project(graph, emptySet(), WorkProbe(rejection = cancelled)) }
            assertTrue(generateSequence(failure) { it.cause }.any { it === cancelled })
            assertFalse(graph.isMappedCallSiteStringIndexViewInitialized())

            warmMappedView(graph)
            assertEquals(emptyList(), project(graph, emptySet(), WorkProbe(limit = 0)))
        }
    }

    @Test
    fun `small graphs and unbounded limits retain fallback results and predicate filtering`() {
        for (nodeCount in listOf(3, LARGE_NODE_COUNT)) {
            withMappedGraph(nodeCount) { graph ->
                warmMappedView(graph)
                val limit = if (nodeCount == 3) 2 else nodeCount
                val selection = linkedSetOf(RIGHT, LEFT)
                val expected = referenceValues(graph, selection, limit)
                assertEquals(setOf(LEFT, RIGHT), expected.toSet())
                assertEquals(expected, project(graph, selection, WorkProbe(), limit).map { it.values })
                assertEquals(emptyList(), project(graph, setOf(MISSING_PROPERTY), WorkProbe(), limit))
                val absent = PREDICATE.copy(expected = "definitely-absent-keyword")
                assertEquals(emptyList(), graph.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java, listOf(absent), PROPERTIES, limit, setOf(LEFT), WorkProbe()
                ))
                assertEquals(0L, graph.callSiteParallelScanCount())
            }
        }
    }

    @Test
    fun `no selection retains predicate discovery and the first distinct rows`() {
        withMappedGraph { graph ->
            warmMappedView(graph)
            val expected = referenceValues(graph)
            assertEquals(2, expected.size)
            val work = WorkProbe()
            val result = project(graph, null, work)

            assertEquals(expected, result.map { it.values })
            assertTrue(result[0].encounterOrder < result[1].encounterOrder)
            assertTrue(work.units >= LARGE_NODE_COUNT)
            assertEquals(1L, graph.callSiteParallelScanCount())
            assertFalse(graph.isCallSiteStringIndexInitialized())
        }
    }

    @Test
    fun `unsupported exact predicate discovery retains selected tuple fallback filtering`() {
        withMappedGraph { graph ->
            warmMappedView(graph)
            val predicates = listOf(
                PREDICATE.copy(mode = StringMatchMode.EQUALS, expected = "get0001"),
                PREDICATE.copy(expected = "1")
            )
            for (predicate in predicates) {
                val work = WorkProbe()
                val result = graph.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java, listOf(predicate), PROPERTIES, 2,
                    linkedSetOf(LEFT, RIGHT), work
                )

                assertEquals(listOf(RIGHT), assertNotNull(result).map { it.values })
                assertTrue(work.units > 0)
            }
        }
    }

    private fun project(
        graph: MappedWebGraphBackedGraph,
        selected: Set<List<String?>>?,
        work: WorkProbe,
        limit: Int = 2
    ): List<StringPropertyDistinctRow> = assertNotNull(graph.distinctStringPropertyDisjunction(
        CallSiteNode::class.java, listOf(PREDICATE), PROPERTIES, limit, selected, work
    ))

    /** Persisted traversal follows physical storage order rather than numeric node IDs. */
    private fun referenceValues(
        graph: MappedWebGraphBackedGraph,
        selected: Set<List<String?>>? = null,
        limit: Int = 2
    ): List<List<String>> = graph.nodes(CallSiteNode::class.java)
        .filter { it.caller.name.lowercase().contains("get") }
        .map { node ->
            listOf(node.caller.declaringClass.className, node.caller.name,
                node.callee.declaringClass.className, node.callee.name)
        }
        .distinct()
        .filter { selected == null || it in selected }
        .take(limit)
        .toList()

    private fun warmMappedView(graph: MappedWebGraphBackedGraph) {
        assertEquals(emptyList(), graph.nodesByStringPropertyDisjunction(
            CallSiteNode::class.java, listOf(PREDICATE.copy(expected = "definitely-absent-keyword")), 1, WorkProbe()
        ).orEmpty().toList())
        assertTrue(graph.isMappedCallSiteStringIndexViewInitialized())
        assertFalse(graph.isCallSiteStringIndexInitialized())
        graph.resetCallSiteScanMetrics()
    }

    private fun withMappedGraph(
        nodeCount: Int = LARGE_NODE_COUNT,
        action: (MappedWebGraphBackedGraph) -> Unit
    ) {
        val property = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(property)
        val directory = Files.createTempDirectory("selected-projection-feasibility")
        try {
            System.clearProperty(property)
            val graph = DefaultGraph.Builder().apply {
                repeat(nodeCount) { id ->
                    val side = if (id % 2 == 0) "Left" else "Right"
                    addNode(CallSiteNode(
                        NodeId(id),
                        MethodDescriptor(TypeDescriptor("example.$side"), "get${id.toString().padStart(4, '0')}",
                            emptyList(), TypeDescriptor("void")),
                        MethodDescriptor(TypeDescriptor("example.Target"), "invoke$side", emptyList(),
                            TypeDescriptor("void")),
                        id, null, emptyList()
                    ))
                }
            }.build()
            GraphStore.save(graph, directory, prepareCallSiteStringIndex = true)
            (GraphStore.loadMapped(directory) as MappedWebGraphBackedGraph).use(action)
        } finally {
            directory.toFile().deleteRecursively()
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private class WorkProbe(
        private val limit: Long = Long.MAX_VALUE,
        private val rejection: RuntimeException? = null
    ) : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
        override val segmentWorkerCount: Int = 0
        var units = 0L
            private set

        override fun consume() = consume(1L)

        override fun consume(workUnits: Long) {
            rejection?.let { throw it }
            units += workUnits
            if (units > limit) throw CypherBudgetExceededException(limit)
        }
    }

    private companion object {
        const val LARGE_NODE_COUNT = 4_096
        val PROPERTIES = listOf("caller_class", "caller_name", "callee_class", "callee_name")
        val PREDICATE = StringPropertyPredicate("caller_name", StringValueTransform.LOWERCASE,
            StringMatchMode.CONTAINS, "get")
        val LEFT = listOf("example.Left", "get0000", "example.Target", "invokeLeft")
        val RIGHT = listOf("example.Right", "get0001", "example.Target", "invokeRight")
        // Target exists in the shared string table, but only as callee_class, never caller_class.
        val MISSING_PROPERTY = listOf("example.Target", "get0000", "example.Target", "invokeLeft")
    }
}
