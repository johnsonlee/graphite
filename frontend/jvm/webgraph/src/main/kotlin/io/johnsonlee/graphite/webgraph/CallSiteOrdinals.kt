package io.johnsonlee.graphite.webgraph

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.logging.Logger

/**
 * The `graph.callsite-ordinals` sidecar: `CallSiteNode.ordinal` per call site, by node id.
 *
 * [pairs] holds the file's `(nodeId, ordinal)` entries, ascending by id, as the file lays them
 * out. A persisted graph maps the file rather than reading it: a fresh mapping of a graph with a
 * million call sites paid about a tenth of a second to read the eight megabytes through a stream,
 * once per query in a cold benchmark, and the mapping pays nothing until a call site is decoded.
 * A lookup is on the decode path of every call site, so it first finds the block of [BLOCK]
 * entries in a small table of block heads that stays in cache, then searches that block.
 */
class CallSiteOrdinals internal constructor(private val pairs: IntBuffer) {

    val size: Int get() = pairs.limit() / 2

    private val blockHeads = IntArray((size + BLOCK - 1) / BLOCK) { idAt(it * BLOCK) }

    private fun idAt(index: Int): Int = pairs.get(index * 2)

    /** The ordinal recorded for node [id], `null` when the sidecar has none. */
    operator fun get(id: Int): Int? {
        val index = indexOf(id)
        return if (index < 0) null else pairs.get(index * 2 + 1)
    }

    /** The entry of [id], or `-1`: the block whose head is the greatest id not above it, searched. */
    private fun indexOf(id: Int): Int {
        val found = blockHeads.binarySearch(id)
        val block = if (found >= 0) found else -found - 2
        var low = if (block < 0) 0 else block * BLOCK
        var high = if (block < 0) -1 else minOf(low + BLOCK, size) - 1
        var index = -1
        while (index < 0 && low <= high) {
            val middle = (low + high) ushr 1
            val candidate = idAt(middle)
            when {
                candidate < id -> low = middle + 1
                candidate > id -> high = middle - 1
                else -> index = middle
            }
        }
        return index
    }

    companion object {
        val EMPTY = CallSiteOrdinals(IntBuffer.allocate(0))
        private val logger = Logger.getLogger(CallSiteOrdinals::class.java.name)
        private const val HEADER_BYTES = NodeSerializer.CALL_SITE_ORDINALS_HEADER_BYTES
        private const val ENTRY_BYTES = NodeSerializer.CALL_SITE_ORDINAL_ENTRY_BYTES

        /**
         * The sidecar in [file], mapped read-only, or [EMPTY] when there is none or [binding] is
         * `null`: a graph written before the sidecar existed has no ordinals and no binding. A
         * sidecar that is not one (wrong header, cut short, longer than its count), or whose
         * digest is not the one `graph.metadata` ends with (left behind by a writer that did not
         * know it, so it describes another graph), is reported once and treated the same, since
         * the graph is complete without it. Every mapping hashes the entries: a path, a size and
         * a modification time are not a content identity, and nothing short of the hash is.
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

        /** The entries of a sidecar whose header passed, [count] pairs starting at [HEADER_BYTES]. */
        internal fun entries(bytes: ByteBuffer, count: Int): CallSiteOrdinals? {
            if (count < 0 || bytes.limit().toLong() != HEADER_BYTES + count.toLong() * ENTRY_BYTES) return null
            return CallSiteOrdinals(bytes.duplicate().position(HEADER_BYTES).slice().asIntBuffer())
        }
    }
}

private const val BLOCK = 256
