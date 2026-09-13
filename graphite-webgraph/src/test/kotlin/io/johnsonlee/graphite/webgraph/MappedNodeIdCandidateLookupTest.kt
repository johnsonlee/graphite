package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.NodeIdCandidateLookup
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.function.IntPredicate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Synthetic persistence fixtures establish correctness/source access only, never performance. */
class MappedNodeIdCandidateLookupTest {
    @Test
    fun `ID candidates preserve canonical traversal order and charge every examined ID once`() = withGraph { graph ->
        val all = graph.nodes(Node::class.java).toList()
        val inspected = mutableListOf<Int>()
        var work = 0
        val candidates = assertNotNull((graph as NodeIdCandidateLookup).nodesMatchingId(
            Node::class.java, IntPredicate { id -> inspected += id; id % 2 != 0 }, GraphWorkConsumer { work++ }
        ))
        assertEquals(0, work)
        assertEquals(emptyList(), inspected)
        assertEquals(all.filter { it.id.value % 2 != 0 }, candidates.toList())
        assertEquals(all.map { it.id.value }, inspected)
        assertEquals(all.size, work)
    }

    @Test
    fun `typed ID lookup excludes other node types and stops on consumer demand`() = withGraph { graph ->
        val expected = graph.nodes(StringConstant::class.java).toList()
        var work = 0
        val candidates = assertNotNull((graph as NodeIdCandidateLookup).nodesMatchingId(
            StringConstant::class.java, IntPredicate { true }, GraphWorkConsumer { work++ }
        ))
        assertEquals(expected.take(1), candidates.take(1).toList())
        assertEquals(1, work)
    }

    @Test
    fun `rejecting ID predicate never decodes the node payload`() {
        val poisoned = listOf(StringConstant(NodeId(7), "payload must not be decoded"))
        withGraph(poisoned, beforeLoad = { directory ->
            // Two header ints, then node ID and tag precede the StringConstant's string-table ID.
            // Keep the persisted type/ID indexes valid while making node materialization fail.
            RandomAccessFile(directory.resolve("graph.nodedata").toFile(), "rw").use { file ->
                file.seek(2L * Int.SIZE_BYTES + Int.SIZE_BYTES + 1)
                file.writeInt(Int.MAX_VALUE)
            }
        }) { graph ->
            var work = 0
            val candidates = assertNotNull((graph as NodeIdCandidateLookup).nodesMatchingId(
                Node::class.java, IntPredicate { false }, GraphWorkConsumer { work++ }
            ))
            assertEquals(emptyList(), candidates.toList())
            assertEquals(1, work)
            assertFailsWith<IndexOutOfBoundsException> { graph.node(NodeId(7)) }
        }
    }

    @Test
    fun `work cancellation propagates before predicate and materialization`() = withGraph { graph ->
        val marker = StopLookup()
        var tested = 0
        val actual = assertFailsWith<StopLookup> {
            (graph as NodeIdCandidateLookup).nodesMatchingId(
                Node::class.java, IntPredicate { tested++; true }, GraphWorkConsumer { throw marker }
            )?.toList()
        }
        assertSame(marker, actual)
        assertEquals(0, tested)
    }

    @Test
    fun `thread interruption cancels ID scanning without an explicit work consumer`() = withGraph { graph ->
        val previouslyInterrupted = Thread.interrupted()
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<CancellationException> {
                (graph as NodeIdCandidateLookup).nodesMatchingId(Node::class.java, IntPredicate { false }, null)?.toList()
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    private class StopLookup : RuntimeException()

    private fun withGraph(
        nodes: List<Node> = listOf(
            IntConstant(NodeId(7), 7), StringConstant(NodeId(19), "nineteen"),
            IntConstant(NodeId(2), 2), StringConstant(NodeId(3), "three"), BooleanConstant(NodeId(41), true)
        ),
        beforeLoad: (Path) -> Unit = {},
        block: (Graph) -> Unit
    ) {
        val directory = Files.createTempDirectory("mapped-node-id-candidates")
        try {
            GraphStore.save(DefaultGraph.Builder().apply { nodes.forEach(::addNode) }.build(), directory)
            beforeLoad(directory)
            val graph = GraphStore.loadMapped(directory)
            try {
                block(graph)
            } finally {
                (graph as? AutoCloseable)?.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
