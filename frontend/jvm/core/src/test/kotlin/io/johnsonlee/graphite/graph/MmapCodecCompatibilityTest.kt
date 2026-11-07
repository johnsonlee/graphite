package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

internal class MmapCodecCompatibilityTest : MmapGraphBuilderTestSupport() {
    @Test
    fun `setResources is propagated to built graph`() {
        val graph = MmapGraphBuilder().setResources(io.johnsonlee.graphite.input.EmptyResourceAccessor).build()
        assertNotNull(graph.resources)
    }

    @Test
    fun `deserializeNode supports legacy string type descriptors`() {
        val method = makeMethod("com.example.Owner", "method")
        val bytes = bytes {
            writeInt(7)
            writeByte(MmapGraphBuilder.TAG_LOCAL_VARIABLE)
            MmapGraphBuilder.writeString(this, "value")
            MmapGraphBuilder.writeString(this, "java.lang.String")
            writeInt(0)
        }

        assertEquals(
            LocalVariable(NodeId(7), "value", TypeDescriptor("java.lang.String"), method),
            MmapGraphBuilder.deserializeNode(bytes, listOf(method)))
    }

    @Test
    fun `deserializeEdge supports every compact edge tag`() {
        assertEquals(
            DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.RETURN_VALUE),
            MmapGraphBuilder.deserializeEdge(
                edgeInput(1, 2) {
                    writeByte(MmapGraphBuilder.TAG_EDGE_DATAFLOW)
                    writeByte(DataFlowKind.RETURN_VALUE.ordinal)
                }))
        assertEquals(
            CallEdge(NodeId(3), NodeId(4), isVirtual = true, isDynamic = true),
            MmapGraphBuilder.deserializeEdge(
                edgeInput(3, 4) {
                    writeByte(MmapGraphBuilder.TAG_EDGE_CALL)
                    writeByte(3)
                }))
        assertEquals(
            TypeEdge(NodeId(5), NodeId(6), TypeRelation.IMPLEMENTS),
            MmapGraphBuilder.deserializeEdge(
                edgeInput(5, 6) {
                    writeByte(MmapGraphBuilder.TAG_EDGE_TYPE)
                    writeByte(TypeRelation.IMPLEMENTS.ordinal)
                }))
        assertEquals(
            ControlFlowEdge(NodeId(7), NodeId(8), ControlFlowKind.SEQUENTIAL),
            MmapGraphBuilder.deserializeEdge(
                edgeInput(7, 8) {
                    writeByte(MmapGraphBuilder.TAG_EDGE_CONTROL_FLOW)
                    writeByte(ControlFlowKind.SEQUENTIAL.ordinal)
                    writeByte(0)
                }))
        assertEquals(
            ControlFlowEdge(NodeId(9), NodeId(10), ControlFlowKind.BRANCH_TRUE, BranchComparison(ComparisonOp.GT, NodeId(11))),
            MmapGraphBuilder.deserializeEdge(
                edgeInput(9, 10) {
                    writeByte(MmapGraphBuilder.TAG_EDGE_CONTROL_FLOW)
                    writeByte(ControlFlowKind.BRANCH_TRUE.ordinal)
                    writeByte(1)
                    writeByte(ComparisonOp.GT.ordinal)
                    writeInt(11)
                }))
        assertEquals(
            ResourceEdge(NodeId(12), NodeId(13), ResourceRelation.LOADS),
            MmapGraphBuilder.deserializeEdge(
                edgeInput(12, 13) {
                    writeByte(MmapGraphBuilder.TAG_EDGE_RESOURCE)
                    writeByte(ResourceRelation.LOADS.ordinal)
                }))
    }

    @Test
    fun `deserialize helpers reject unknown tags and preserve fallback values`() {
        val fallback = dataInput {
            writeByte(99)
            MmapGraphBuilder.writeString(this, "fallback")
        }
        assertEquals("fallback", MmapGraphBuilder.readAnyValue(fallback, emptyList()))

        assertFailsWith<IllegalStateException> {
            MmapGraphBuilder.deserializeNode(
                bytes {
                    writeInt(1)
                    writeByte(99)
                },
                emptyList())
        }
        assertFailsWith<IllegalStateException> { MmapGraphBuilder.deserializeEdge(edgeInput(1, 2) { writeByte(99) }) }
    }
}
