package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import java.nio.ByteBuffer

/** Byte-level edge decoding and offset traversal for the mapped backing file. */
internal class MmapEdgeReader(private val edgeMmap: ByteBuffer) {
    fun read(offset: Long): Edge {
        val buf = edgeMmap.duplicate()
        return read(buf, offset)
    }

    fun read(buf: ByteBuffer, offset: Long): Edge {
        buf.position(offset.toInt())
        val from = NodeId(buf.int)
        val to = NodeId(buf.int)
        return when (val tag = buf.get().toInt()) {
            MmapGraphBuilder.TAG_EDGE_DATAFLOW -> DataFlowEdge(from, to, DataFlowKind.entries[buf.get().toInt()])
            MmapGraphBuilder.TAG_EDGE_CALL -> {
                val flags = buf.get().toInt()
                CallEdge(from, to, isVirtual = (flags and 1) != 0, isDynamic = (flags and 2) != 0)
            }
            MmapGraphBuilder.TAG_EDGE_TYPE -> TypeEdge(from, to, TypeRelation.entries[buf.get().toInt()])
            MmapGraphBuilder.TAG_EDGE_CONTROL_FLOW -> {
                val kind = ControlFlowKind.entries[buf.get().toInt()]
                val hasComparison = buf.get().toInt() == 1
                val comparison =
                    if (hasComparison) {
                        BranchComparison(ComparisonOp.entries[buf.get().toInt()], NodeId(buf.int))
                    } else {
                        null
                    }
                ControlFlowEdge(from, to, kind, comparison)
            }
            MmapGraphBuilder.TAG_EDGE_RESOURCE -> ResourceEdge(from, to, ResourceRelation.entries[buf.get().toInt()])
            else -> error("Unknown edge type tag: $tag")
        }
    }

    fun skipPayload(buf: ByteBuffer) {
        when (buf.get().toInt()) {
            MmapGraphBuilder.TAG_EDGE_DATAFLOW,
            MmapGraphBuilder.TAG_EDGE_CALL,
            MmapGraphBuilder.TAG_EDGE_TYPE,
            MmapGraphBuilder.TAG_EDGE_RESOURCE -> buf.get()
            MmapGraphBuilder.TAG_EDGE_CONTROL_FLOW -> {
                buf.get()
                if (buf.get().toInt() == 1) {
                    buf.get()
                    buf.int
                }
            }
            else -> error("Unknown edge type tag")
        }
    }

    fun prefixStarts(counts: IntArray): IntArray {
        val starts = IntArray(counts.size + 1)
        for (i in counts.indices) {
            starts[i + 1] = starts[i] + counts[i]
        }
        return starts
    }
}
