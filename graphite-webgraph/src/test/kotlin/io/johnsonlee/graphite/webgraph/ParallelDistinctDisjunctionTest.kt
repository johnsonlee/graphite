package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.PreferredMappedStringIndexViewGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParallelDistinctDisjunctionTest {
    @Test
    fun `four keyword OR keeps every exclusive term and deduplicates overlap in stored order`() {
        for (persistIndex in listOf(false, true)) {
            withGraph(OR_VALUES, persistIndex) { graph ->
                val predicates = TERMS.flatMap { term -> PROPERTIES.map { predicate(it, term) } }
                val masks = graph.nodes(CallSiteNode::class.java).map { node ->
                    val fields = values(node)
                    TERMS.mapIndexed { index, term ->
                        if (fields.any { it.lowercase().contains(term) }) 1 shl index else 0
                    }.sum()
                }.filter { it != 0 }.groupingBy { it }.eachCount()
                assertEquals(mapOf(1 to 2, 2 to 1, 4 to 1, 8 to 1, 15 to 1), masks)
                val expected = reference(graph, predicates)
                assertEquals(OR_VALUES.take(5).toSet(), expected.toSet())
                assertEquals(5, expected.size)

                assertProjectionResult(graph, predicates, expected, expectedRawScans = if (persistIndex) 0 else 1)
                assertProjectionResult(graph, predicates, expected.take(2), limit = 2)
                assertEquals(persistIndex, graph.isMappedCallSiteStringIndexViewInitialized())
            }
        }
    }

    @Test
    fun `selected tuples filter the OR result without changing its physical order`() {
        for (persistIndex in listOf(false, true)) {
            withGraph(OR_VALUES, persistIndex) { graph ->
                val predicates = TERMS.flatMap { term -> PROPERTIES.map { predicate(it, term) } }
                val all = reference(graph, predicates)
                val selected = all.drop(1).take(3).reversed().toCollection(linkedSetOf<List<String?>>())
                selected += listOf("example.Missing", "missing", "example.Missing", "missing")
                val expected = reference(graph, predicates, selected)
                assertEquals(all.drop(1).take(3), expected)

                val expectedRawScans = if (persistIndex) 0L else 1L
                assertProjectionResult(graph, predicates, expected, selected, expectedRawScans = expectedRawScans)
                assertProjectionResult(graph, predicates, expected.take(2), selected, limit = 2,
                    expectedRawScans = expectedRawScans)
                assertProjectionResult(graph, predicates, all, expectedRawScans = expectedRawScans)
            }
        }
    }

    @Test
    fun `sparse initial posting projection preserves reordered duplicate and null columns`() {
        for (persistIndex in listOf(false, true)) {
            withGraph(OR_VALUES, persistIndex) { graph ->
                val predicates = TERMS.flatMap { term -> PROPERTIES.map { predicate(it, term) } }
                val properties = listOf("callee_name", "caller_class", "graphId", "caller_name",
                    "callee_class", "class", "caller_name", "name")
                val expected = reference(graph, predicates).map { row ->
                    listOf(row[3], row[0], null, row[1], row[2], null, row[1], null)
                }
                assertEquals(5, expected.size)
                val rows = assertNotNull(graph.distinctStringPropertyDisjunction(
                    CallSiteNode::class.java, predicates, properties, 20, null, SplitWork
                ))
                assertEquals(expected, rows.map { it.values })
                assertTrue(rows.zipWithNext().all { (left, right) -> left.encounterOrder < right.encounterOrder })
                assertEquals(if (persistIndex) 0L else 1L, graph.callSiteParallelScanCount())
                assertEquals(persistIndex, graph.isMappedCallSiteStringIndexViewInitialized())
                assertFalse(graph.isCallSiteStringIndexInitialized())
                assertEquals(0, graph.callSiteScanActiveWorkers())
            }
        }
    }

    @Test
    fun `raw and lowercase predicates do not share a match state for the same string`() {
        val rows = listOf(
            listOf("MiXeD", "MiXeD", "plain", "plain"),
            listOf("mixed", "plain", "plain", "plain"),
            listOf("MiXeD", "plain", "plain", "plain")
        )
        withGraph(rows) { graph ->
            val predicates = listOf(
                predicate("caller_class", "mixed", transform = null),
                predicate("caller_name", "mixed")
            )
            val expected = reference(graph, predicates)
            assertEquals(rows.take(2).toSet(), expected.toSet())

            assertProjectionResult(graph, predicates, expected)
            assertProjectionResult(graph, predicates.reversed(), expected)
            assertProjectionResult(graph, predicates, listOf(rows.first()), setOf(rows.first()))
        }
    }

    @Test
    fun `different lowercase terms keep separate match states and preserve a complete miss`() {
        val rows = listOf(
            listOf("BY", "BY", "plain", "plain"),
            listOf("AX", "plain", "plain", "plain"),
            listOf("BY", "plain", "plain", "plain")
        )
        withGraph(rows) { graph ->
            val predicates = listOf(predicate("caller_class", "ax"), predicate("caller_name", "by"))
            val expected = reference(graph, predicates)
            assertEquals(rows.take(2).toSet(), expected.toSet())

            assertProjectionResult(graph, predicates, expected)
            assertProjectionResult(graph, predicates.reversed(), expected)
            val misses = PROPERTIES.map { predicate(it, "qz") }
            assertEquals(emptyList(), reference(graph, misses))
            assertProjectionResult(graph, misses, emptyList())
        }
    }

    private fun assertProjectionResult(
        graph: MappedWebGraphBackedGraph,
        predicates: List<StringPropertyPredicate>,
        expected: List<List<String>>,
        selected: Set<List<String?>>? = null,
        limit: Int = 20,
        expectedRawScans: Long = 1
    ) {
        graph.resetCallSiteScanMetrics()
        val actual = assertNotNull(graph.distinctStringPropertyDisjunction(
            CallSiteNode::class.java, predicates, PROPERTIES, limit, selected, SplitWork
        ))
        assertEquals(expected, actual.map { it.values })
        assertTrue(actual.zipWithNext().all { (left, right) -> left.encounterOrder < right.encounterOrder })
        assertEquals(expectedRawScans, graph.callSiteParallelScanCount(), "Expected DISTINCT projection path")
        assertEquals(0, graph.callSiteScanActiveWorkers())
        assertFalse(graph.isCallSiteStringIndexInitialized())
    }

    /** Read actual node properties in persisted traversal order, independently of string IDs and postings. */
    private fun reference(
        graph: MappedWebGraphBackedGraph,
        predicates: List<StringPropertyPredicate>,
        selected: Set<List<String?>>? = null
    ): List<List<String>> = graph.nodes(CallSiteNode::class.java).map(::values).filter { row ->
        predicates.any { predicate ->
            val value = row[PROPERTIES.indexOf(predicate.property)]
            val text = if (predicate.transform == StringValueTransform.LOWERCASE) value.lowercase() else value
            text.contains(predicate.expected)
        }
    }.distinct().filter { selected == null || it in selected }.toList()

    private fun values(node: CallSiteNode): List<String> = listOf(
        node.caller.declaringClass.className, node.caller.name,
        node.callee.declaringClass.className, node.callee.name
    )

    private fun predicate(
        property: String,
        expected: String,
        transform: StringValueTransform? = StringValueTransform.LOWERCASE
    ) = StringPropertyPredicate(property, transform, StringMatchMode.CONTAINS, expected)

    private fun withGraph(
        rows: List<List<String>>,
        persistIndex: Boolean = false,
        action: (MappedWebGraphBackedGraph) -> Unit
    ) {
        val returnType = TypeDescriptor("void")
        val graph = DefaultGraph.Builder().apply {
            repeat(NODE_COUNT) { index ->
                val row = rows.getOrNull(index) ?: listOf("example.Plain", "plain", "example.Other", "plain")
                addNode(CallSiteNode(
                    NodeId(index),
                    MethodDescriptor(TypeDescriptor(row[0]), row[1], emptyList(), returnType),
                    MethodDescriptor(TypeDescriptor(row[2]), row[3], emptyList(), returnType),
                    index, null, emptyList()
                ))
            }
        }.build()
        val directory = Files.createTempDirectory("parallel-distinct-disjunction")
        val preparationProperty = GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY
        val previous = System.getProperty(preparationProperty)
        try {
            System.clearProperty(preparationProperty)
            GraphStore.save(graph, directory, prepareCallSiteStringIndex = persistIndex)
            (GraphStore.loadMapped(directory) as MappedWebGraphBackedGraph).use(action)
        } finally {
            if (previous == null) System.clearProperty(preparationProperty) else System.setProperty(preparationProperty, previous)
            directory.toFile().deleteRecursively()
        }
    }

    private object SplitWork : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
        override val segmentWorkerCount: Int = 1
        override fun consume() = Unit
        override fun consume(workUnits: Long) = Unit
    }

    private companion object {
        const val NODE_COUNT = 4_096
        val PROPERTIES = listOf("caller_class", "caller_name", "callee_class", "callee_name")
        val TERMS = listOf("needlea", "needleb", "needlec", "needled")
        val OR_VALUES = listOf(
            listOf("example.NeedleA", "plainA", "example.Other", "plainA"),
            listOf("example.Plain", "needleB", "example.Other", "plainB"),
            listOf("example.Plain", "plainC", "example.NeedleC", "plainC"),
            listOf("example.Plain", "plainD", "example.Other", "needleD"),
            listOf("example.NeedleA", "needleB", "example.NeedleC", "needleD"),
            listOf("example.NeedleA", "plainA", "example.Other", "plainA")
        )
    }
}
