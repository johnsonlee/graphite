package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import java.io.DataOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Verifies graph comparison store. */
class GraphComparisonStoreTest : GraphStoreTestFixture() {

    @Test
    fun `round-trip preserves ControlFlowEdge with BranchComparison`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val n3 = IntConstant(NodeId.next(), 0)
        builder.addNode(n1)
        builder.addNode(n2)
        builder.addNode(n3)

        val comparison = BranchComparison(ComparisonOp.EQ, n3.id)
        val cfEdge = ControlFlowEdge(n1.id, n2.id, ControlFlowKind.BRANCH_TRUE, comparison)
        val seqEdge = ControlFlowEdge(n2.id, n3.id, ControlFlowKind.SEQUENTIAL)
        builder.addEdge(cfEdge)
        builder.addEdge(seqEdge)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-cf-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedCfEdges = loaded.outgoing(n1.id, ControlFlowEdge::class.java).toList()
            assertEquals(1, loadedCfEdges.size)
            assertEquals(ControlFlowKind.BRANCH_TRUE, loadedCfEdges[0].kind)
            assertNotNull(loadedCfEdges[0].comparison)
            assertEquals(ComparisonOp.EQ, loadedCfEdges[0].comparison!!.operator)
            assertEquals(n3.id, loadedCfEdges[0].comparison!!.comparandNodeId)

            val loadedSeqEdges = loaded.outgoing(n2.id, ControlFlowEdge::class.java).toList()
            assertEquals(1, loadedSeqEdges.size)
            assertEquals(ControlFlowKind.SEQUENTIAL, loadedSeqEdges[0].kind)
            assertNull(loadedSeqEdges[0].comparison)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `comparison lookup is skipped for non-control-flow edge labels`() {
        val label = NodeSerializer.encodeEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.ASSIGN))
        val lookup = BranchComparisonLookup { error("comparison lookup should not run for non-control-flow edges") }

        assertNull(comparisonForEdge(label, 1, 2, NodeSerializer.FORMAT_VERSION, lookup))
    }

    @Test
    fun `mapped comparison lookup reads persisted comparisons by edge key`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val n3 = IntConstant(NodeId.next(), 3)
        val n4 = IntConstant(NodeId.next(), 4)
        builder.addNode(n1)
        builder.addNode(n2)
        builder.addNode(n3)
        builder.addNode(n4)

        val first = BranchComparison(ComparisonOp.EQ, n3.id)
        val second = BranchComparison(ComparisonOp.NE, n4.id)
        builder.addEdge(ControlFlowEdge(n1.id, n2.id, ControlFlowKind.BRANCH_TRUE, first))
        builder.addEdge(ControlFlowEdge(n2.id, n4.id, ControlFlowKind.BRANCH_FALSE, second))

        val dir = Files.createTempDirectory("webgraph-mapped-comparison-test")
        try {
            GraphStore.save(builder.build(), dir)
            val lookup = MappedBranchComparisonLookup.load(dir.resolve("graph.comparisons"))

            assertEquals(first, lookup.find(edgeKey(n1.id, n2.id)))
            assertEquals(second, lookup.find(edgeKey(n2.id, n4.id)))
            assertNull(lookup.find(edgeKey(n3.id, n4.id)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped comparison lookup binary search covers lower and upper misses`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId(10), 1)
        val n2 = IntConstant(NodeId(20), 2)
        val n3 = IntConstant(NodeId(30), 3)
        builder.addNode(n1).addNode(n2).addNode(n3)
        builder.addEdge(ControlFlowEdge(n2.id, n3.id, ControlFlowKind.BRANCH_TRUE, BranchComparison(ComparisonOp.EQ, n1.id)))

        val dir = Files.createTempDirectory("webgraph-mapped-comparison-miss-test")
        try {
            GraphStore.save(builder.build(), dir)
            val lookup = MappedBranchComparisonLookup.load(dir.resolve("graph.comparisons"))
            assertNull(lookup.find(edgeKey(NodeId(0), NodeId(0))))
            assertNull(lookup.find(edgeKey(NodeId(99), NodeId(99))))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped comparison lookup handles empty and corrupt comparison files`() {
        val emptyFile = Files.createTempFile("webgraph-empty-comparisons", ".bin")
        val corruptFile = Files.createTempFile("webgraph-corrupt-comparisons", ".bin")
        try {
            DataOutputStream(emptyFile.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_COMPARISONS)
                dos.writeInt(0)
            }
            DataOutputStream(corruptFile.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_COMPARISONS)
                dos.writeInt(-1)
            }

            assertNull(MappedBranchComparisonLookup.load(emptyFile).find(1L))
            assertFailsWith<IllegalArgumentException> { MappedBranchComparisonLookup.load(corruptFile) }
        } finally {
            Files.deleteIfExists(emptyFile)
            Files.deleteIfExists(corruptFile)
        }
    }
}
