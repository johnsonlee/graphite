package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The node-id contract of the JVM writer: sparse ids are accepted and read back exactly, and the per-node offset index is sized by
 * the largest id, eight bytes per id below it. A build numbers its nodes from 1 (`NodeId.numbered`), which is what keeps the index
 * proportional to the node count; a graph that does carry a high id pays for it, and this pins the cost rather than hiding it.
 */
class SparseNodeIdTest {
    @Test
    fun `sparse ids read back exactly and the offset index is sized by the largest id`() {
        val high = 1_000_000
        val builder = DefaultGraph.Builder()
        val nodes = listOf(1, 7, high).map { IntConstant(NodeId(it), it) }
        nodes.forEach(builder::addNode)
        builder.addEdge(DataFlowEdge(NodeId(1), NodeId(high), DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(NodeId(high), NodeId(7), DataFlowKind.ASSIGN))
        val dir = Files.createTempDirectory("sparse-node-ids")
        try {
            GraphStore.save(builder.build(), dir)
            assertEquals(GraphManifest.verify(dir), emptyList())
            val offsets = Files.size(dir.resolve("graph.nodeoffsets"))
            assertEquals(GraphStoreTestSizes.offsetIndexBytes(high), offsets)
            for (mode in listOf(GraphStore.LoadMode.EAGER, GraphStore.LoadMode.MAPPED)) {
                val graph = GraphStore.load(dir, mode)
                try {
                    assertEquals(setOf(1, 7, high), graph.nodes(Node::class.java).map { it.id.value }.toSet(), "$mode")
                    assertEquals(high, (graph.node(NodeId(high)) as IntConstant).value, "$mode")
                    assertNull(graph.node(NodeId(500)), "$mode: an id below the largest that no node has")
                    assertEquals(listOf(high), graph.outgoing(NodeId(1)).map { it.to.value }.toList(), "$mode")
                    assertEquals(listOf(high), graph.incoming(NodeId(7)).map { it.from.value }.toList(), "$mode")
                } finally {
                    (graph as? Closeable)?.close()
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

/** The size of `graph.nodeoffsets` for a graph whose largest id is [maxId]: a header and one 8-byte offset per id up to it. */
private object GraphStoreTestSizes {
    private const val HEADER_BYTES = 8L

    fun offsetIndexBytes(maxId: Int): Long = HEADER_BYTES + 8L * (maxId + 1)
}
