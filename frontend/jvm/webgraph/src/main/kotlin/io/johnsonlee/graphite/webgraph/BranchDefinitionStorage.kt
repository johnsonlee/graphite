package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Read the optional `graph.branchdefs` sidecar for the graph in [dir], or [PersistedBranchDefinitions.EMPTY] with one warning when it
 * is missing, is not the one `graph.metadata` was written with (for example left behind when an older writer re-saved this directory,
 * whose metadata then carries no trailer), or is corrupt. The preamble is validated before the payload is read, and the payload is read
 * only up to its validated length, its local table may not exceed [nodeCount] entries, and its content is checked against the persisted
 * nodes through [nodeTags] (see [decodePersistedBranchDefinitionPayload]). The sidecar is derived data: rebuilding the
 * graph restores it. Called lazily on the first branch-scope access.
 */
internal fun loadPersistedBranchDefinitions(
    dir: Path,
    metadata: GraphMetadata,
    nodeCount: Int,
    nodeTags: BranchDefinitionNodeTags
): PersistedBranchDefinitions {
    if (metadata.branchScopes.isEmpty()) return PersistedBranchDefinitions.EMPTY
    val file = dir.resolve(GraphStore.BRANCH_DEFINITIONS_FILE)
    val decoded =
        when {
            !Files.isRegularFile(file) -> DecodedBranchDefinitions.rejected("is missing")
            else ->
                try {
                    readPersistedBranchDefinitions(file, metadata, nodeCount, nodeTags)
                } catch (e: IOException) {
                    DecodedBranchDefinitions.rejected("could not be read (${e.message})")
                }
        }
    if (decoded.definitions == null) {
        System.err.println("Warning: $file ${decoded.rejection}; branch scopes are loaded without local definitions")
    }
    return decoded.definitions ?: PersistedBranchDefinitions.EMPTY
}

internal fun eagerBranchDefinitionNodeTags(nodesById: Map<Int, Node>) =
    BranchDefinitionNodeTags { nodeId -> nodesById[nodeId]?.let(::graphNodeTagOf) ?: NodeSerializer.NO_NODE }

/** Reads the tag byte that follows the id in a mapped node record, without deserialising the node. */
internal fun mappedBranchDefinitionNodeTags(offsets: NodeOffsetIndex, nodeData: ByteBuffer) =
    BranchDefinitionNodeTags { nodeId ->
        if (nodeId < 0 || nodeId >= offsets.size) {
            NodeSerializer.NO_NODE
        } else {
            val offset = offsets.offset(nodeId)
            if (offset < 0) NodeSerializer.NO_NODE else nodeData.get((offset + Int.SIZE_BYTES).toInt()).toInt()
        }
    }

internal fun readPersistedBranchDefinitions(
    file: Path,
    metadata: GraphMetadata,
    nodeCount: Int,
    nodeTags: BranchDefinitionNodeTags
): DecodedBranchDefinitions =
    DataInputStream(BufferedInputStream(file.toFile().inputStream())).use { dis ->
        val preambleBytes = ByteArray(NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES)
        val read = dis.readNBytes(preambleBytes, 0, preambleBytes.size)
        val preamble =
            decodePersistedBranchDefinitionPreamble(
                preambleBytes.copyOf(read), Files.size(file) - read, metadata.branchDefinitionDigest)
        val digest = preamble.payloadDigest
        if (digest == null) {
            DecodedBranchDefinitions.rejected(checkNotNull(preamble.rejection))
        } else {
            val payload = ByteArray(preamble.payloadLength)
            dis.readFully(payload)
            decodePersistedBranchDefinitionPayload(payload, metadata.branchScopes.size, digest, nodeCount, nodeTags)
        }
    }
