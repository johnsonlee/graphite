package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.NodeId
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLongArray
import java.util.logging.Logger

/**
 * The `graph.callsite-ordinals` sidecar: `CallSiteNode.ordinal` per call site and
 * `CallSiteNode.origin` per derived call site, by node id.
 *
 * [entries] holds the file's `(nodeId, ordinal)` pairs, ascending by id, then its
 * `(nodeId, origin)` pairs, one per derived call site resolved from another, ascending by id;
 * [heads] the id heading each block of [BLOCK] ordinal pairs and [blockDigests] the SHA-256 of
 * each [BLOCK_BYTES]-byte block of the entries, both from the index the binding in
 * `graph.metadata` covers. A persisted graph maps the file rather than reading it: a fresh
 * mapping of a graph with a million call sites paid about a tenth of a second to read the eight
 * megabytes through a stream, once per query in a cold benchmark, and the mapping pays nothing
 * until a call site is decoded. Nor does it hash the entries up front: that cost the cold rows
 * of the slow-query-shapes gate 15–20% per query on a 14-megabyte sidecar. The index is hashed
 * when the sidecar is opened and a block of entries on its first touch, against the digest the
 * index holds for it, so a lookup proves exactly the bytes it reads. A lookup is on the decode
 * path of every call site, so it first finds its block in [heads], which stays in cache, then
 * searches that block; an origin is looked up only for a derived call site, apart from the
 * ordinals so that the common decode pays for nothing it does not read.
 */
class CallSiteOrdinals internal constructor(
    private val entries: ByteBuffer,
    val size: Int,
    private val originCount: Int,
    private val heads: IntBuffer,
    private val blockDigests: ByteBuffer
) {

    private val verified = AtomicLongArray((blockDigests.limit() / DIGEST_BYTES + Long.SIZE_BITS - 1) / Long.SIZE_BITS)

    /** The ordinal recorded for node [id], `null` when the sidecar has none. */
    operator fun get(id: Int): Int? {
        val index = indexOf(id)
        return if (index < 0) null else entries.getInt(index * ENTRY_BYTES + Int.SIZE_BYTES)
    }

    /** The call site node [id] was derived from, `null` when it was not derived from one or the sidecar has no entry for it. */
    fun origin(id: Int): NodeId? {
        val originsAt = size.toLong() * ENTRY_BYTES
        var low = 0
        var high = originCount - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val at = (originsAt + middle.toLong() * ENTRY_BYTES).toInt()
            verify(at / BLOCK_BYTES)
            val candidate = entries.getInt(at)
            when {
                candidate < id -> low = middle + 1
                candidate > id -> high = middle - 1
                else -> return NodeId(entries.getInt(at + Int.SIZE_BYTES))
            }
        }
        return null
    }

    /** The entry of [id], or `-1`: the block whose head is the greatest id not above it, verified and searched. */
    private fun indexOf(id: Int): Int {
        val block = blockOf(id)
        if (block < 0) return -1
        verify(block)
        var low = block * BLOCK
        var high = minOf(low + BLOCK, size) - 1
        var index = -1
        while (index < 0 && low <= high) {
            val middle = (low + high) ushr 1
            val candidate = entries.getInt(middle * ENTRY_BYTES)
            when {
                candidate < id -> low = middle + 1
                candidate > id -> high = middle - 1
                else -> index = middle
            }
        }
        return index
    }

    /** The block of ordinal pairs whose head is the greatest id not above [id], or `-1` below the first head. */
    private fun blockOf(id: Int): Int {
        var low = 0
        var high = heads.limit() - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (heads.get(middle) <= id) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    /**
     * Check [block] of the entries against the digest the index holds for it, once: the index
     * is what `graph.metadata` binds, so a block that does not hash to its entry has changed
     * since the sidecar was bound and is corrupt, not another graph's.
     */
    private fun verify(block: Int) {
        val word = block / Long.SIZE_BITS
        val bit = 1L shl (block % Long.SIZE_BITS)
        if (verified.get(word) and bit != 0L) return
        val from = block * BLOCK_BYTES
        val digest = NodeSerializer.digestOf(entries.duplicate().position(from).limit(minOf(from + BLOCK_BYTES, entries.limit())))
        val expected = ByteArray(DIGEST_BYTES).also { blockDigests.duplicate().position(block * DIGEST_BYTES).get(it) }
        check(digest.contentEquals(expected)) {
            "graph.callsite-ordinals block $block does not hash to the digest its index holds: " +
                "the entries changed after graph.metadata bound them"
        }
        verified.updateAndGet(word) { it or bit }
    }

    companion object {
        val EMPTY = CallSiteOrdinals(ByteBuffer.allocate(0), 0, 0, IntBuffer.allocate(0), ByteBuffer.allocate(0))
        private val logger by lazy { Logger.getLogger(CallSiteOrdinals::class.java.name) }
        private const val PREAMBLE_BYTES = NodeSerializer.CALL_SITE_ORDINALS_PREAMBLE_BYTES
        private const val HEADER_BYTES = NodeSerializer.CALL_SITE_ORDINALS_HEADER_BYTES
        private const val ENTRY_BYTES = NodeSerializer.CALL_SITE_ORDINAL_ENTRY_BYTES
        private const val BLOCK = NodeSerializer.CALL_SITE_ORDINAL_BLOCK_ENTRIES
        private const val BLOCK_BYTES = NodeSerializer.CALL_SITE_ORDINAL_BLOCK_BYTES
        private const val DIGEST_BYTES = NodeSerializer.DIGEST_BYTES

        /**
         * The sidecar in [file], mapped read-only, or [EMPTY] when there is none or [binding] is
         * `null`: a graph written before the sidecar existed has no ordinals and no binding. A
         * sidecar that is not one (wrong header, cut short, longer than its counts), or whose
         * index does not hash to the digest `graph.metadata` ends with (left behind by a writer
         * that did not know it, so it describes another graph), is reported once and treated the
         * same, since the graph is complete without it. Every mapping hashes the index, and every
         * block of entries on its first touch: a path, a size and a modification time are not a
         * content identity, and nothing short of the hash is.
         */
        fun load(file: Path, binding: ByteArray?): CallSiteOrdinals {
            if (binding == null || !Files.isRegularFile(file)) return EMPTY
            return try {
                FileChannel.open(file, StandardOpenOption.READ).use { channel ->
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    NodeSerializer.callSiteOrdinalsOf(mapped, binding)
                } ?: EMPTY.also { logger.warning("Ignoring $file: not the call-site ordinal sidecar graph.metadata binds") }
            } catch (e: IOException) {
                logger.warning("Ignoring $file: ${e.message}")
                EMPTY
            }
        }

        /**
         * The sidecar whose header passed, in [bytes]: the counts at [PREAMBLE_BYTES], the block
         * heads, the block digests, then the entries, which must end the file; `null` when the
         * counts and the length disagree or the index (counts, heads and block digests) does not
         * hash to [binding].
         */
        @Suppress("ReturnCount")
        internal fun entries(bytes: ByteBuffer, binding: ByteArray): CallSiteOrdinals? {
            val count = bytes.getInt(PREAMBLE_BYTES)
            val originCount = bytes.getInt(PREAMBLE_BYTES + Int.SIZE_BYTES)
            if (count < 0 || originCount < 0) return null
            val heads = NodeSerializer.callSiteOrdinalBlocks(count.toLong(), BLOCK)
            val entryBytes = (count.toLong() + originCount) * ENTRY_BYTES
            val blocks = NodeSerializer.callSiteOrdinalBlocks(entryBytes, BLOCK_BYTES)
            val digestsAt = HEADER_BYTES + heads.toLong() * Int.SIZE_BYTES
            val entriesAt = digestsAt + blocks.toLong() * DIGEST_BYTES
            if (entriesAt + entryBytes != bytes.limit().toLong()) return null
            val index = bytes.duplicate().position(PREAMBLE_BYTES).limit(entriesAt.toInt())
            if (!NodeSerializer.digestOf(index).contentEquals(binding)) return null
            return CallSiteOrdinals(
                bytes.duplicate().position(entriesAt.toInt()).slice(),
                count,
                originCount,
                bytes.duplicate().position(HEADER_BYTES).limit(digestsAt.toInt()).slice().asIntBuffer(),
                bytes.duplicate().position(digestsAt.toInt()).limit(entriesAt.toInt()).slice()
            )
        }
    }
}

/** The `origin` the writer is handed for a call site not derived from another. */
internal const val NO_ORIGIN = -1
