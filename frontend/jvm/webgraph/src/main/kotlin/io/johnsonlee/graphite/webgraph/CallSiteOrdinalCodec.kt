package io.johnsonlee.graphite.webgraph

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Encodes and verifies the persisted call-site ordinal sidecar and its metadata binding. */
internal object CallSiteOrdinalCodec {
    /** The `graph.callsite-ordinals` sidecar's bytes and the digest `graph.metadata` binds to it. */
    class EncodedCallSiteOrdinals(val bytes: ByteArray, val digest: ByteArray)

    /**
     * Encode the `graph.callsite-ordinals` sidecar: `int32 header = NodeSerializer.MAGIC_CALL_SITE_ORDINALS |
     * NodeSerializer.CALL_SITE_ORDINALS_VERSION`, a copy of the binding digest, then the index the binding covers (`int32 count`, `int32
     * originCount`, the node id heading each block of [NodeSerializer.CALL_SITE_ORDINAL_BLOCK_ENTRIES] ordinal entries, and the SHA-256 of
     * each [NodeSerializer.CALL_SITE_ORDINAL_BLOCK_BYTES]-byte block of the entries), then the entries: per call site `int32 nodeId, int32
     * ordinal`, ascending by node id, then per derived call site resolved from another `int32 nodeId, int32 origin`
     * (`CallSiteNode.origin`), ascending by node id. The origins sit apart from the ordinals so that decoding a call site, which reads its
     * ordinal, searches no more than it did before origins existed. The binding is the SHA-256 of the index, so a reader proves the index
     * on open at a cost of one block digest per block of entries, and each block of entries on the first touch: a fresh mapping of a graph
     * with a million call sites hashed eight megabytes before it could read one ordinal, which the cold rows of the slow-query-shapes gate
     * paid on every query (15–20%), while the index is a sixty-fourth of that. A file of its own rather than a field of the CallSite record
     * or a metadata section, so a reader that predates it, the base revision of a benchmark comparison included, reads the graph exactly as
     * before and sees no ordinal. The digest is also the last section of `graph.metadata` ([writeCallSiteOrdinalBinding]): a writer that
     * does not know the sidecar rewrites the metadata without it, and the sidecar it left behind, whose ordinals would attach to the reused
     * node ids of another graph, no longer binds.
     */
    fun encodeCallSiteOrdinals(ids: IntArray, ordinals: IntArray, origins: IntArray): EncodedCallSiteOrdinals {
        require(ids.size == ordinals.size && ids.size == origins.size)
        val derived = ids.indices.filter { origins[it] != NO_ORIGIN }
        val entries = ByteBuffer.allocate((ids.size + derived.size) * NodeSerializer.CALL_SITE_ORDINAL_ENTRY_BYTES)
        for (index in ids.indices) {
            entries.putInt(ids[index])
            entries.putInt(ordinals[index])
        }
        for (index in derived) {
            entries.putInt(ids[index])
            entries.putInt(origins[index])
        }
        val heads = callSiteOrdinalBlocks(ids.size.toLong(), NodeSerializer.CALL_SITE_ORDINAL_BLOCK_ENTRIES)
        val blocks = callSiteOrdinalBlocks(entries.capacity().toLong(), NodeSerializer.CALL_SITE_ORDINAL_BLOCK_BYTES)
        val index = ByteBuffer.allocate(2 * Int.SIZE_BYTES + heads * Int.SIZE_BYTES + blocks * NodeSerializer.DIGEST_BYTES)
        index.putInt(ids.size)
        index.putInt(derived.size)
        for (block in 0 until heads) index.putInt(ids[block * NodeSerializer.CALL_SITE_ORDINAL_BLOCK_ENTRIES])
        val blockDigest = MessageDigest.getInstance(NodeSerializer.DIGEST_ALGORITHM)
        for (block in 0 until blocks) {
            val from = block * NodeSerializer.CALL_SITE_ORDINAL_BLOCK_BYTES
            blockDigest.update(entries.array(), from, minOf(from + NodeSerializer.CALL_SITE_ORDINAL_BLOCK_BYTES, entries.capacity()) - from)
            index.put(blockDigest.digest())
        }
        val digest = MessageDigest.getInstance(NodeSerializer.DIGEST_ALGORITHM).digest(index.array())
        val bytes = ByteBuffer.allocate(NodeSerializer.CALL_SITE_ORDINALS_PREAMBLE_BYTES + index.capacity() + entries.capacity())
        bytes.putInt(NodeSerializer.MAGIC_CALL_SITE_ORDINALS or NodeSerializer.CALL_SITE_ORDINALS_VERSION)
        bytes.put(digest)
        bytes.put(index.array())
        bytes.put(entries.array())
        return EncodedCallSiteOrdinals(bytes.array(), digest)
    }

    /** The number of blocks of [blockSize] that cover [length] units. */
    internal fun callSiteOrdinalBlocks(length: Long, blockSize: Int): Int = ((length + blockSize - 1) / blockSize).toInt()

    /**
     * Append the binding of the `graph.callsite-ordinals` sidecar to `graph.metadata`: the section's header and the sidecar's digest,
     * [NodeSerializer.CALL_SITE_ORDINALS_BINDING_BYTES] in all. It is written last, after the trailer and the synthetic identities, so a
     * reader finds it by looking at the file's tail ([readCallSiteOrdinalBinding]) without parsing the metadata, and a reader that predates
     * it stops at its header.
     */
    fun writeCallSiteOrdinalBinding(dos: DataOutputStream, digest: ByteArray) {
        require(digest.size == NodeSerializer.DIGEST_BYTES) { "Expected a SHA-256 digest, got ${digest.size} bytes" }
        dos.writeInt(NodeSerializer.MAGIC_CALL_SITE_ORDINALS_BINDING or NodeSerializer.CALL_SITE_ORDINALS_BINDING_VERSION)
        dos.write(digest)
    }

    /**
     * The sidecar digest `graph.metadata` ends with, or `null` when the file does not end with the binding section: a graph saved without
     * ordinals, or re-saved by a writer that predates them, whose sidecar, if one is left, must not be attached.
     */
    fun readCallSiteOrdinalBinding(metadata: Path): ByteArray? {
        val tail = metadataTail(metadata) ?: return null
        val header = tail.getInt()
        val bound =
            header and HEADER_MAGIC_MASK == NodeSerializer.MAGIC_CALL_SITE_ORDINALS_BINDING &&
                header and BYTE_MASK == NodeSerializer.CALL_SITE_ORDINALS_BINDING_VERSION
        return if (bound) ByteArray(NodeSerializer.DIGEST_BYTES).also { tail.get(it) } else null
    }

    /** The last [NodeSerializer.CALL_SITE_ORDINALS_BINDING_BYTES] of [metadata], or `null` when the file is missing or shorter. */
    private fun metadataTail(metadata: Path): ByteBuffer? {
        if (!Files.isRegularFile(metadata) || Files.size(metadata) < NodeSerializer.CALL_SITE_ORDINALS_BINDING_BYTES) return null
        val tail = ByteBuffer.allocate(NodeSerializer.CALL_SITE_ORDINALS_BINDING_BYTES)
        FileChannel.open(metadata, StandardOpenOption.READ).use { channel ->
            channel.position(channel.size() - NodeSerializer.CALL_SITE_ORDINALS_BINDING_BYTES)
            while (tail.hasRemaining() && channel.read(tail) >= 0) Unit
        }
        return if (tail.hasRemaining()) null else tail.flip()
    }

    /**
     * The sidecar in [bytes], the whole file, or `null` when the header is not the sidecar's, the length is not what its counts say (cut
     * short, or trailing bytes), or the index does not hash to [binding], the digest `graph.metadata` ends with: the sidecar then describes
     * another graph, or no metadata binds it. The digest copied into the header is not trusted for that; the index is hashed, and the index
     * holds the digest of every block of entries, which the sidecar checks on the first touch of that block ([CallSiteOrdinals]), since the
     * entries are what the binding claims to cover and what a `select` folds by.
     */
    fun callSiteOrdinalsOf(bytes: ByteBuffer, binding: ByteArray?): CallSiteOrdinals? {
        if (binding == null || bytes.limit() < NodeSerializer.CALL_SITE_ORDINALS_HEADER_BYTES) return null
        val header = bytes.getInt(0)
        val isSidecar =
            header and HEADER_MAGIC_MASK == NodeSerializer.MAGIC_CALL_SITE_ORDINALS &&
                header and BYTE_MASK == NodeSerializer.CALL_SITE_ORDINALS_VERSION
        val digest = ByteArray(NodeSerializer.DIGEST_BYTES).also { bytes.duplicate().position(Int.SIZE_BYTES).get(it) }
        return if (isSidecar && digest.contentEquals(binding)) CallSiteOrdinals.entries(bytes, binding) else null
    }

    /** SHA-256 of [bytes] from its position to its limit, read through the buffer, so a mapped file is not copied. */
    internal fun digestOf(bytes: ByteBuffer): ByteArray =
        MessageDigest.getInstance(NodeSerializer.DIGEST_ALGORITHM).also { it.update(bytes) }.digest()
}
