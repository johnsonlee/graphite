package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.NodeId
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal fun interface BranchComparisonLookup {
    fun find(key: Long): BranchComparison?
}

internal object EmptyBranchComparisonLookup : BranchComparisonLookup {
    override fun find(key: Long): BranchComparison? = null
}

internal class MapBranchComparisonLookup(
    private val comparisons: Map<Long, BranchComparison>
) : BranchComparisonLookup {
    override fun find(key: Long): BranchComparison? = comparisons[key]
}

internal class LazyMappedBranchComparisonLookup(
    private val path: Path
) : BranchComparisonLookup {

    private val delegate: BranchComparisonLookup by lazy(LazyThreadSafetyMode.PUBLICATION) {
        MappedBranchComparisonLookup.load(path)
    }

    override fun find(key: Long): BranchComparison? = delegate.find(key)
}

internal class MappedBranchComparisonLookup private constructor(
    private val buffer: MappedByteBuffer,
    private val count: Int
) : BranchComparisonLookup {

    override fun find(key: Long): BranchComparison? {
        var low = 0
        var high = count - 1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val offset = ENTRY_START + mid * ENTRY_BYTES
            val candidate = buffer.getLong(offset)
            when {
                candidate < key -> low = mid + 1
                candidate > key -> high = mid - 1
                else -> {
                    val op = ComparisonOp.entries[buffer.getInt(offset + Long.SIZE_BYTES)]
                    val comparandId = NodeId(buffer.getInt(offset + Long.SIZE_BYTES + Int.SIZE_BYTES))
                    return BranchComparison(op, comparandId)
                }
            }
        }
        return null
    }

    companion object {
        private const val ENTRY_START = Int.SIZE_BYTES + Int.SIZE_BYTES
        private const val ENTRY_BYTES = Long.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES

        fun load(path: Path): BranchComparisonLookup {
            val count = DataInputStream(BufferedInputStream(path.toFile().inputStream())).use { dis ->
                NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_COMPARISONS)
                dis.readInt()
            }
            require(count >= 0) { "Invalid branch comparison count: $count" }
            if (count <= 0) return EmptyBranchComparisonLookup

            val channel = FileChannel.open(path, StandardOpenOption.READ)
            val mapped = try {
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            } finally {
                channel.close()
            }
            return MappedBranchComparisonLookup(mapped, count)
        }
    }
}

internal fun comparisonForEdge(
    label: Int,
    from: Int,
    to: Int,
    version: Int,
    lookup: BranchComparisonLookup
): BranchComparison? {
    val familyMask = if (version >= NodeSerializer.FORMAT_VERSION) V3_EDGE_FAMILY_MASK else V2_EDGE_FAMILY_MASK
    if ((label and familyMask) != EDGE_FAMILY_CONTROL_FLOW) return null
    val key = from.toLong() shl INT_BITS or (to.toLong() and UNSIGNED_INT_MASK)
    return lookup.find(key)
}
