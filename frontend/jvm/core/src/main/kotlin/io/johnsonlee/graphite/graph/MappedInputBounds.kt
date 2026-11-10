package io.johnsonlee.graphite.graph

import java.io.DataInputStream
import java.io.EOFException

/** Check before narrowing the edge count or allocating adjacency indexes. */
internal fun validateMappedEdgeCount(edgeCount: Long) {
    require(edgeCount <= Int.MAX_VALUE.toLong()) {
        "MmapGraphBuilder supports at most ${Int.MAX_VALUE} edges in memory-mapped indexes, got $edgeCount"
    }
}

/** InputStream.skip is allowed to make no progress even before EOF. */
internal fun skipFully(input: DataInputStream, bytes: Int) {
    require(bytes >= 0) { "Cannot skip a negative byte count: $bytes" }
    var remaining = bytes
    while (remaining > 0) {
        val skipped = input.skipBytes(remaining)
        if (skipped <= 0) {
            if (input.read() == -1) {
                throw EOFException("Unexpected EOF while skipping node payload")
            }
            remaining--
        } else {
            remaining -= skipped
        }
    }
}
