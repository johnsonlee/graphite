package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MmapGraphOffsetIndexTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    @Test
    fun `lazy incoming index scans every edge payload variant`() {
        val source = NodeId(0)
        val dataTarget = NodeId(1)
        val callTarget = NodeId(2)
        val typeTarget = NodeId(3)
        val controlTarget = NodeId(4)
        val comparedControlTarget = NodeId(5)
        val resourceTarget = NodeId(6)
        val comparisonValue = NodeId(7)
        val dataEdge = DataFlowEdge(source, dataTarget, DataFlowKind.ASSIGN)
        val callEdge = CallEdge(source, callTarget, isVirtual = true, isDynamic = true)
        val typeEdge = TypeEdge(source, typeTarget, TypeRelation.IMPLEMENTS)
        val controlEdge = ControlFlowEdge(source, controlTarget, ControlFlowKind.SEQUENTIAL)
        val comparedControlEdge = ControlFlowEdge(
            source,
            comparedControlTarget,
            ControlFlowKind.BRANCH_TRUE,
            BranchComparison(ComparisonOp.GT, comparisonValue)
        )
        val resourceEdge = ResourceEdge(source, resourceTarget, ResourceRelation.LOADS)
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(source, 0))
            .addNode(IntConstant(dataTarget, 1))
            .addNode(IntConstant(callTarget, 2))
            .addNode(IntConstant(typeTarget, 3))
            .addNode(IntConstant(controlTarget, 4))
            .addNode(IntConstant(comparedControlTarget, 5))
            .addNode(IntConstant(resourceTarget, 6))
            .addNode(IntConstant(comparisonValue, 7))
            .addEdge(dataEdge)
            .addEdge(callEdge)
            .addEdge(typeEdge)
            .addEdge(controlEdge)
            .addEdge(comparedControlEdge)
            .addEdge(resourceEdge)
            .build()

        assertEquals(listOf(dataEdge), graph.incoming(dataTarget).toList())
        assertEquals(listOf(callEdge), graph.incoming(callTarget).toList())
        assertEquals(listOf(typeEdge), graph.incoming(typeTarget).toList())
        assertEquals(listOf(controlEdge), graph.incoming(controlTarget).toList())
        assertEquals(listOf(comparedControlEdge), graph.incoming(comparedControlTarget).toList())
        assertEquals(listOf(resourceEdge), graph.incoming(resourceTarget).toList())
    }

    @Test
    fun `long offset indexes read heap and mapped offsets`() {
        val heapIndex = MmapGraph.HeapLongOffsetIndex(longArrayOf(17L, 23L))
        assertEquals(17L, heapIndex[0])
        assertEquals(23L, heapIndex[1])

        val offsetsFile = Files.createTempFile("graphite-offset-index", ".dat")
        try {
            MmapGraph.createMappedLongOffsetIndex(offsetsFile, 2).use { offsets ->
                offsets.putLong(0, 123L)
                offsets.putLong(1, 456L)
            }

            val index = MmapGraph.MappedLongOffsetIndex(offsetsFile)
            assertEquals(123L, index[0])
            assertEquals(456L, index[1])

            assertFailsWith<IllegalArgumentException> {
                MmapGraph.createMappedLongOffsetIndex(offsetsFile, Int.MAX_VALUE / Long.SIZE_BYTES + 1)
            }
        } finally {
            Files.deleteIfExists(offsetsFile)
        }
    }
}
