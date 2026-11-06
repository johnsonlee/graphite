package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

/** Verifies projection cache. */
class ProjectionCacheTest : GraphStoreTestFixture() {

    @Test
    fun `projection cache evicts its least recently used entry at the entry cap`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .addNode(
                    CallSiteNode(
                        NodeId(1),
                        MethodDescriptor(TypeDescriptor("example.FeatureCaller"), "call", emptyList(), returnType),
                        MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), returnType),
                        1,
                        null,
                        emptyList()))
                .build()
        val predicates =
            listOf(StringPropertyPredicate("caller_class", StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "feature"))
        val properties = listOf("caller_class", "caller_name", "callee_class", "callee_name")
        val dir = Files.createTempDirectory("webgraph-projection-lru")
        val budgetBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                assertEquals(
                    1,
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates, 1, GraphWorkConsumer {})
                        .orEmpty()
                        .count())
                val first = assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, predicates, properties, 1))
                for (limit in 2..33) {
                    assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, predicates, properties, limit))
                }
                val admittedAgain =
                    assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, predicates, properties, 1))

                assertEquals(first, admittedAgain)
                assertNotSame(first, admittedAgain)
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }

    @Test
    fun `projection cache rejects decoded string payload beyond its retained byte cap`() {
        val returnType = TypeDescriptor("void")
        val padding = "x".repeat(1_400)
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(257) { index ->
                        addNode(
                            CallSiteNode(
                                NodeId(index),
                                MethodDescriptor(
                                    TypeDescriptor("example.NeedleCaller$index$padding"), "call$index$padding", emptyList(), returnType),
                                MethodDescriptor(
                                    TypeDescriptor("example.Dependency$index$padding"), "invoke$index$padding", emptyList(), returnType),
                                index,
                                null,
                                emptyList()))
                    }
                }
                .build()
        val properties = listOf("caller_class", "caller_name", "callee_class", "callee_name")
        val predicates =
            properties.map { property ->
                StringPropertyPredicate(property, StringValueTransform.LOWERCASE, StringMatchMode.CONTAINS, "needle")
            }
        val dir = Files.createTempDirectory("webgraph-long-projection-cache")
        val budgetBefore = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph
            try {
                val matches =
                    loaded
                        .nodesByStringPropertyDisjunction(CallSiteNode::class.java, predicates, 200, GraphWorkConsumer {})
                        .orEmpty()
                        .toList()
                assertEquals(200, matches.size)
                val retainedBeforeProjection = loaded.callSiteStringIndexBytes()

                val first = assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, predicates, properties, 200))
                val second = assertNotNull(loaded.projectStringPropertyDisjunction(CallSiteNode::class.java, predicates, properties, 200))

                assertEquals(200, first.size)
                assertEquals(first, second)
                assertNotSame(first, second)
                assertEquals(retainedBeforeProjection, loaded.callSiteStringIndexBytes())
            } finally {
                loaded.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        assertEquals(budgetBefore, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
    }
}
