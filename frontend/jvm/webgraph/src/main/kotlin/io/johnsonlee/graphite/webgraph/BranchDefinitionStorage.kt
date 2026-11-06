package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/** Loads and validates optional branch definitions against the persisted nodes. */
internal object BranchDefinitionStorage {

    /**
     * Read the optional `graph.branchdefs` sidecar for the graph in [dir], or [PersistedBranchDefinitions.EMPTY] with one warning when it
     * is missing, is not the one `graph.metadata` was written with (for example left behind when an older writer re-saved this directory,
     * whose metadata then carries no trailer), or is corrupt. The preamble is validated before the payload is read, and the payload is read
     * only up to its validated length, its local table may not exceed [nodeCount] entries, and its content is checked against the persisted
     * nodes through [nodeTags] (see [BranchDefinitionCodec.decodeBranchDefinitionPayload]). The sidecar is derived data: rebuilding the
     * graph restores it. Called lazily on the first branch-scope access.
     */
    internal fun loadBranchDefinitions(
        dir: Path,
        metadata: GraphMetadata,
        nodeCount: Int,
        nodeTags: BranchDefinitionCodec.NodeTagLookup
    ): PersistedBranchDefinitions {
        if (metadata.branchScopes.isEmpty()) return PersistedBranchDefinitions.EMPTY
        val file = dir.resolve(GraphStore.BRANCH_DEFINITIONS_FILE)
        val decoded =
            when {
                !Files.isRegularFile(file) -> DecodedBranchDefinitions.rejected("is missing")
                else ->
                    try {
                        readBranchDefinitions(file, metadata, nodeCount, nodeTags)
                    } catch (e: IOException) {
                        DecodedBranchDefinitions.rejected("could not be read (${e.message})")
                    }
            }
        if (decoded.definitions == null) {
            System.err.println("Warning: $file ${decoded.rejection}; branch scopes are loaded without local definitions")
        }
        return decoded.definitions ?: PersistedBranchDefinitions.EMPTY
    }

    fun eagerNodeTagLookup(nodesById: Map<Int, Node>) =
        BranchDefinitionCodec.NodeTagLookup { nodeId -> nodesById[nodeId]?.let(NodeRecordCodec::tagOf) ?: NodeSerializer.NO_NODE }

    /** Reads the tag byte that follows the id in a mapped node record, without deserialising the node. */
    fun mappedNodeTagLookup(offsets: NodeOffsetIndex, nodeData: ByteBuffer) =
        BranchDefinitionCodec.NodeTagLookup { nodeId ->
            if (nodeId < 0 || nodeId >= offsets.size) {
                NodeSerializer.NO_NODE
            } else {
                val offset = offsets.offset(nodeId)
                if (offset < 0) NodeSerializer.NO_NODE else nodeData.get((offset + Int.SIZE_BYTES).toInt()).toInt()
            }
        }

    fun readBranchDefinitions(
        file: Path,
        metadata: GraphMetadata,
        nodeCount: Int,
        nodeTags: BranchDefinitionCodec.NodeTagLookup
    ): DecodedBranchDefinitions =
        DataInputStream(BufferedInputStream(file.toFile().inputStream())).use { dis ->
            val preambleBytes = ByteArray(NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES)
            val read = dis.readNBytes(preambleBytes, 0, preambleBytes.size)
            val preamble =
                BranchDefinitionCodec.decodeBranchDefinitionPreamble(
                    preambleBytes.copyOf(read), Files.size(file) - read, metadata.branchDefinitionDigest)
            val digest = preamble.payloadDigest
            if (digest == null) {
                DecodedBranchDefinitions.rejected(checkNotNull(preamble.rejection))
            } else {
                val payload = ByteArray(preamble.payloadLength)
                dis.readFully(payload)
                BranchDefinitionCodec.decodeBranchDefinitionPayload(payload, metadata.branchScopes.size, digest, nodeCount, nodeTags)
            }
        }
}
