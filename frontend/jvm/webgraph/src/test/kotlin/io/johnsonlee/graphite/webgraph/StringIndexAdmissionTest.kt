package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringProperty
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import io.johnsonlee.graphite.graph.nodesByTransformedStringProperty
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies string index admission. */
class StringIndexAdmissionTest : GraphStoreTestFixture() {

    @Test
    fun `mapped string lookup admits an index only after a long raw scan`() {
        val graph =
            DefaultGraph.Builder().apply { repeat(300) { index -> addNode(StringConstant(NodeId(index), "symbol-$index")) } }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-admission")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertTrue(
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.ENDS_WITH, "missing", limit = 1)
                        .orEmpty()
                        .none())
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertEquals(
                    listOf("symbol-0"),
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.STARTS_WITH, "symbol-0", limit = 1)
                        .orEmpty()
                        .map { it.value }
                        .toList())
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertTrue(
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.ENDS_WITH, "missing", limit = 1)
                        .orEmpty()
                        .none())
                assertEquals(1, loaded.stringPropertyIndexCount())
                assertEquals(
                    listOf("symbol-0"),
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.STARTS_WITH, "symbol-0", limit = 1)
                        .orEmpty()
                        .map { it.value }
                        .toList())
                assertTrue(
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, "symbol", limit = 0)
                        .orEmpty()
                        .none())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped raw string scan keeps match states query local and accounts budgeted work`() {
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(261) { index ->
                        val value =
                            when (index) {
                                0 -> "TÄRGET"
                                256,
                                257,
                                260 -> "TaRgEt"
                                258,
                                259 -> "other"
                                else -> "prefix-$index"
                            }
                        addNode(StringConstant(NodeId(index), value))
                    }
                }
                .build()
        val orderedGraph =
            object : Graph by graph {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = graph.nodes(type).sortedBy { it.id.value }
            }
        val dir = Files.createTempDirectory("webgraph-raw-string-match-states")
        try {
            GraphStore.save(orderedGraph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val unbudgeted =
                    loaded
                        .nodesByTransformedStringProperty(
                            StringConstant::class.java,
                            "value",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "target",
                            limit = 3)
                        .orEmpty()
                        .map { it.id.value }
                        .toList()

                assertEquals(listOf(256, 257, 260), unbudgeted)
                assertEquals(0, loaded.rawStringMatchStateCount())
                assertEquals(0L, loaded.rawStringMatchStateBytes())

                assertEquals(
                    listOf(0),
                    loaded
                        .nodesByTransformedStringProperty(
                            StringConstant::class.java,
                            "value",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.EQUALS,
                            "tärget",
                            limit = 1)
                        .orEmpty()
                        .map { it.id.value }
                        .toList())
                assertEquals(
                    listOf(256),
                    loaded
                        .nodesByTransformedStringProperty(
                            StringConstant::class.java,
                            "value",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.EQUALS,
                            "target",
                            limit = 1)
                        .orEmpty()
                        .map { it.id.value }
                        .toList())

                loaded.clearStringPropertyIndexes()
                var consumed = 0
                val budgeted =
                    loaded
                        .nodesByTransformedStringProperty(
                            StringConstant::class.java,
                            "value",
                            StringValueTransform.LOWERCASE,
                            StringMatchMode.CONTAINS,
                            "target",
                            limit = 3,
                            workConsumer = GraphWorkConsumer { consumed++ })
                        .orEmpty()
                        .map { it.id.value }
                        .toList()

                assertEquals(listOf(256, 257, 260), budgeted)
                assertEquals(261, consumed)
                assertEquals(0, loaded.rawStringMatchStateCount())
                assertEquals(0L, loaded.rawStringMatchStateBytes())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `raw string match states evict least recently used entries within graph memory bound`() {
        val states = RawStringMatchStates(maxRetainedBytes = 512, maxEntries = 8)
        val property = StringPropertyKey(StringConstant::class.java, "value")
        val firstKey = RawStringMatchKey(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "first")
        val secondKey = RawStringMatchKey(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "second")
        val thirdKey = RawStringMatchKey(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "third")

        val first = states.stateFor(firstKey, stringCount = 40)
        val second = states.stateFor(secondKey, stringCount = 40)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(states.stateFor(firstKey, stringCount = 40) === first)
        val third = states.stateFor(thirdKey, stringCount = 40)
        assertNotNull(third)
        assertEquals(2, states.size())
        assertEquals(440L, states.retainedBytes())
        fun predicate(expected: String) =
            StringPropertyPredicate("value", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, expected)
        assertTrue(states.contains(StringConstant::class.java, predicate("first")))
        assertTrue(!states.contains(StringConstant::class.java, predicate("second")))
        assertTrue(states.contains(StringConstant::class.java, predicate("third")))

        val oversizedKeyStates = RawStringMatchStates(maxRetainedBytes = 256, maxEntries = 8)
        assertNull(
            oversizedKeyStates.stateFor(
                RawStringMatchKey(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "x".repeat(128)), stringCount = 1))
        assertEquals(0, oversizedKeyStates.size())
        assertEquals(0L, oversizedKeyStates.retainedBytes())
    }

    @Test
    fun `combined CallSite index estimates CSR retention and rejects budget overflow`() {
        assertEquals(384L, estimatedMappedCallSiteStringIndexCountBytes(stringCount = 20))
        assertEquals(
            896L, estimatedMappedCallSiteStringIndexRetainedBytes(nodeCount = 10, stringCount = 20, uniqueCounts = intArrayOf(2, 3, 4, 5)))

        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val unavailable = MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore + 1L
        assertNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(unavailable))
        assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        assertNull(estimatedMappedCallSiteStringIndexRetainedBytes(nodeCount = -1, stringCount = 20, uniqueCounts = intArrayOf(2, 3, 4, 5)))
        assertNull(estimatedMappedCallSiteStringIndexRetainedBytes(nodeCount = 10, stringCount = 20, uniqueCounts = intArrayOf(2, 3, 4)))
        val emptyReservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(0)
        assertNotNull(emptyReservation)
        emptyReservation.shrinkTo(0)
        emptyReservation.close()
        emptyReservation.close()
    }

    @Test
    fun `mapped CallSite disjunction falls back to bounded raw scan when index budget is occupied`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(3) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(TypeDescriptor("example.Caller$index"), "call$index", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke$index", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val predicates =
            listOf(
                StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Caller1"),
                StringPropertyPredicate("callee_name", null, StringMatchMode.EQUALS, "missing"))
        val dir = Files.createTempDirectory("webgraph-callsite-budget-fallback")
        val retainedBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(MappedCallSiteStringIndexMemoryBudget.maxBytes - retainedBefore)
        assertNotNull(reservation)
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                var work = 0
                val ids =
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates, Int.MAX_VALUE, GraphWorkConsumer { work++ })
                        .orEmpty()
                        .map { it.id.value }
                        .toList()

                assertEquals(listOf(1), ids)
                assertEquals(3, work)
                assertFalse(loaded.isCallSiteStringIndexInitialized())
                assertNull(
                    loaded.aggregateStringPropertyDisjunction(CallSiteNode::class.java, predicates, distinctProperty = "caller_class"))
                assertNull(
                    loaded.distinctStringPropertyDisjunction(CallSiteNode::class.java, predicates, listOf("caller_class"), limit = 10))
                assertNull(loaded.aggregateStringPropertyDisjunction(StringConstant::class.java, predicates, distinctProperty = null))
            } finally {
                loaded.close()
            }
        } finally {
            reservation.close()
            dir.toFile().deleteRecursively()
        }
        assertEquals(retainedBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }

    @Test
    fun `mapped string lookup keeps admission specific to the query limit`() {
        val graph =
            DefaultGraph.Builder().apply { repeat(300) { index -> addNode(StringConstant(NodeId(index), "symbol-$index")) } }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-limit-admission")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertEquals(
                    300,
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, "symbol-", limit = 300)
                        .orEmpty()
                        .count())
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertEquals(
                    listOf("symbol-0"),
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, "symbol-", limit = 1)
                        .orEmpty()
                        .map { it.value }
                        .toList())
                assertEquals(0, loaded.stringPropertyIndexCount())

                assertEquals(
                    300,
                    loaded
                        .nodesByStringProperty(StringConstant::class.java, "value", StringMatchMode.CONTAINS, "symbol-", limit = 300)
                        .orEmpty()
                        .count())
                assertEquals(1, loaded.stringPropertyIndexCount())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string lookup resets predicate admission after LRU eviction`() {
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(300) { index ->
                        addNode(ResourceFileNode(NodeId(index), "path-$index", "source-$index", "format-$index"))
                        addNode(
                            FieldNode(
                                NodeId(300 + index),
                                FieldDescriptor(
                                    TypeDescriptor("example.Owner$index"), "field-$index", TypeDescriptor("example.Type$index")),
                                false))
                    }
                }
                .build()
        val dir = Files.createTempDirectory("webgraph-string-property-eviction")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                listOf(
                        Triple(ResourceFileNode::class.java, "path", "missing-path"),
                        Triple(ResourceFileNode::class.java, "source", "missing-source"),
                        Triple(ResourceFileNode::class.java, "format", "missing-format"),
                        Triple(FieldNode::class.java, "class", "missing-class"),
                        Triple(FieldNode::class.java, "name", "missing-name"))
                    .forEach { (type, property, expected) ->
                        repeat(2) {
                            assertTrue(
                                loaded
                                    .nodesByStringProperty(type, property, StringMatchMode.CONTAINS, expected, limit = 1)
                                    .orEmpty()
                                    .none())
                        }
                    }

                assertEquals(4, loaded.stringPropertyIndexCount())
                assertEquals(0, loaded.stringPropertyIndexCount(ResourceFileNode::class.java, "path"))
                assertTrue(
                    loaded
                        .nodesByStringProperty(ResourceFileNode::class.java, "path", StringMatchMode.CONTAINS, "missing-path", limit = 1)
                        .orEmpty()
                        .none())
                assertEquals(0, loaded.stringPropertyIndexCount(ResourceFileNode::class.java, "path"))
                assertEquals(
                    listOf("path-0"),
                    loaded
                        .nodesByStringProperty(ResourceFileNode::class.java, "path", StringMatchMode.STARTS_WITH, "path-0", limit = 1)
                        .orEmpty()
                        .map { it.path }
                        .toList())
                assertEquals(0, loaded.stringPropertyIndexCount(ResourceFileNode::class.java, "path"))
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped string lookup bounds predicate admission state`() {
        val graph =
            DefaultGraph.Builder().apply { repeat(300) { index -> addNode(StringConstant(NodeId(index), "symbol-$index")) } }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-admission-bounds")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                repeat(33) { index -> assertTrue(loaded.stringConstantsMissing("short-missing-$index")) }
                assertTrue(loaded.stringConstantsMissing("short-missing-0"))
                assertEquals(0, loaded.stringPropertyIndexCount())
                assertTrue(loaded.stringConstantsMissing("short-missing-32"))
                assertEquals(1, loaded.stringPropertyIndexCount())

                loaded.clearStringPropertyIndexes()
                val largePredicates = List(24) { index -> "large-missing-$index-${"x".repeat(1_500)}" }
                largePredicates.forEach { expected -> assertTrue(loaded.stringConstantsMissing(expected)) }
                assertTrue(loaded.stringConstantsMissing(largePredicates.first()))
                assertEquals(0, loaded.stringPropertyIndexCount())
                assertTrue(loaded.stringConstantsMissing(largePredicates.last()))
                assertEquals(1, loaded.stringPropertyIndexCount())

                loaded.clearStringPropertyIndexes()
                val oversized = "oversized-${"x".repeat(32_768)}"
                repeat(2) { assertTrue(loaded.stringConstantsMissing(oversized)) }
                assertEquals(0, loaded.stringPropertyIndexCount())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clearing mapped string indexes resets unbounded admission`() {
        val graph =
            DefaultGraph.Builder().apply { repeat(300) { index -> addNode(StringConstant(NodeId(index), "symbol-$index")) } }.build()
        val dir = Files.createTempDirectory("webgraph-string-property-admission-clear")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertNull(loaded.unboundedStringConstants("symbol"))
                assertEquals(300, loaded.unboundedStringConstants("symbol")?.count())
                assertEquals(1, loaded.stringPropertyIndexCount())

                loaded.clearStringPropertyIndexes()

                assertEquals(0, loaded.stringPropertyIndexCount())
                assertNull(loaded.unboundedStringConstants("symbol"))
                assertEquals(300, loaded.unboundedStringConstants("symbol")?.count())
                assertEquals(1, loaded.stringPropertyIndexCount())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
