package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.MethodTypes
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The `graph.declared-presence` sidecar: one bit per node id saying whether the Field, Parameter
 * or Return node with that id has a declaration in `graph.types`, and one byte per method of the
 * method index saying whether its row exists and which presence flags it has. A presence check
 * (`n.generic_type IS NOT NULL`, `m.type_parameters IS NOT NULL`) over every node of 64 graphs
 * resolved each node's row by its string IDs, a hash probe and an erased-signature compare per
 * node (Attempt308), 22% of the request on top of a base that answers a property it does not have
 * by its name alone; the saver already resolves every node while writing them
 * ([DeclaredKeySummaryBuilder]) and knows the method order it writes, so it writes the answers.
 *
 * Layout, big-endian: `int32 header = MAGIC | VERSION`, the 32-byte SHA-256 of `graph.types` that
 * `forward.properties` binds, the 32-byte SHA-256 of `graph.nodedata` that `forward.properties`
 * binds ([ForwardProperties.NODE_LAYOUT_KEY]), `int32 nodeBits`, `nodeBits / 64` rounded-up `int64`
 * words (bit `id` set when node `id` is bound), `int32 methodCount`, `methodCount` bytes (0 for a
 * method without a row, otherwise 1 + its [DeclaredTypeBinding.HAS_PARAMETERS] /
 * [DeclaredTypeBinding.HAS_BOUNDS] flags, in method-index order), then the SHA-256 of every byte
 * before it. The bits are indexed by node id and the bytes by method index, so the file is bound to
 * both the table and the node layout: a file whose table digest or layout digest is not the one
 * `forward.properties` names is another graph's and reads as absent (two graphs with one table and
 * different node ids never accept each other's bits); a file that is bound but no longer hashes, or
 * does not cover exactly this graph's node ids and methods, is corruption after binding and throws
 * rather than answers.
 */
internal class DeclaredPresence private constructor(
    private val words: LongArray,
    private val nodeBits: Int,
    private val shapes: ByteBuffer
) {
    /**
     * [DeclaredTypeBinding.PRESENT] or [DeclaredTypeBinding.ABSENT] for a node id the file covers,
     * else [DeclaredTypeBinding.UNANSWERED].
     */
    fun node(id: Int): Int {
        if (id < 0 || id >= nodeBits) return DeclaredTypeBinding.UNANSWERED
        val set = words[id ushr WORD_SHIFT] and (1L shl (id and WORD_MASK)) != 0L
        return if (set) DeclaredTypeBinding.PRESENT else DeclaredTypeBinding.ABSENT
    }

    /**
     * The shape of the method at [handle] in the method index, [DeclaredTypeBinding.UNBOUND], or
     * [DeclaredTypeBinding.UNANSWERED] off the end.
     */
    fun methodShape(handle: Int): Int {
        if (handle < 0 || handle >= shapes.limit()) return DeclaredTypeBinding.UNANSWERED
        val byte = shapes.get(handle).toInt() and BYTE_MASK
        return if (byte == 0) DeclaredTypeBinding.UNBOUND else byte - 1
    }

    companion object {
        const val FILE_NAME = "graph.declared-presence"
        internal const val MAGIC = 0x47445000 // "GDP"
        internal const val VERSION = 2
        private const val DIGEST_BYTES = 32
        private const val WORD_SHIFT = 6
        private const val WORD_MASK = 63
        private const val BYTE_MASK = 0xFF
        private const val TYPES_DIGEST_AT = Int.SIZE_BYTES
        private const val LAYOUT_DIGEST_AT = TYPES_DIGEST_AT + DIGEST_BYTES
        private const val HEADER_BYTES = LAYOUT_DIGEST_AT + DIGEST_BYTES + Int.SIZE_BYTES
        private const val MIN_BYTES = HEADER_BYTES + Int.SIZE_BYTES + DIGEST_BYTES

        /** The byte the sidecar keeps for a method with [types], or without one. */
        fun shapeByte(types: MethodTypes?): Byte {
            if (types == null) return 0
            var flags = 0
            if (types.parameterTypes.isNotEmpty()) flags = flags or DeclaredTypeBinding.HAS_PARAMETERS
            if (types.typeParameters.any { it.bounds.isNotEmpty() }) flags = flags or DeclaredTypeBinding.HAS_BOUNDS
            return (1 + flags).toByte()
        }

        fun encode(
            typesDigest: ByteArray, layoutDigest: ByteArray, words: LongArray, nodeBits: Int, shapes: ByteArray
        ): ByteArray {
            require(typesDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${typesDigest.size} bytes" }
            require(layoutDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${layoutDigest.size} bytes" }
            require(nodeBits >= 0 && words.size == wordsFor(nodeBits)) { "Presence words do not cover $nodeBits node ids" }
            val body = ByteBuffer.allocate(HEADER_BYTES + words.size * Long.SIZE_BYTES + Int.SIZE_BYTES + shapes.size)
            body.putInt(MAGIC or VERSION).put(typesDigest).put(layoutDigest).putInt(nodeBits)
            words.forEach(body::putLong)
            body.putInt(shapes.size).put(shapes)
            val digest = MessageDigest.getInstance("SHA-256").digest(body.array())
            return body.array() + digest
        }

        fun wordsFor(nodeBits: Int): Int = (nodeBits + WORD_MASK) ushr WORD_SHIFT

        /**
         * The sidecar bound to the table and the node layout `forward.properties` names in [dir],
         * covering exactly [nodeBits] node ids (the node offset index's capacity) and [methodCount]
         * methods (the metadata's), or null when there is none for them.
         */
        fun load(dir: Path, nodeBits: Int, methodCount: Int): DeclaredPresence? {
            val types = DeclaredTypeStore.readBinding(dir)
            val layout = ForwardProperties.read(dir, ForwardProperties.NODE_LAYOUT_KEY)
            if (types == null || layout == null) return null
            return mapped(dir.resolve(FILE_NAME))?.takeIf { bound(it, types, layout) }?.let { parse(it, nodeBits, methodCount) }
        }

        /** The whole file mapped read-only, or null when it is missing or cannot be this format. */
        private fun mapped(path: Path): ByteBuffer? {
            if (!Files.isRegularFile(path)) return null
            return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                if (channel.size() in MIN_BYTES.toLong()..Int.MAX_VALUE.toLong()) {
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                } else {
                    null
                }
            }
        }

        /** Whether [bytes] is this format and names the table and the node layout the bindings (hex SHA-256) do. */
        private fun bound(bytes: ByteBuffer, types: String, layout: String): Boolean {
            if (bytes.getInt(0) != MAGIC or VERSION) return false
            return digestAt(bytes, TYPES_DIGEST_AT).equals(types, ignoreCase = true) &&
                digestAt(bytes, LAYOUT_DIGEST_AT).equals(layout, ignoreCase = true)
        }

        private fun digestAt(bytes: ByteBuffer, position: Int): String =
            HexFormat.of().formatHex(ByteArray(DIGEST_BYTES).also { bytes.get(position, it) })

        private fun parse(bytes: ByteBuffer, nodeBits: Int, methodCount: Int): DeclaredPresence {
            val payload = bytes.limit() - DIGEST_BYTES
            val expected = ByteArray(DIGEST_BYTES).also { bytes.get(payload, it) }
            val actual = MessageDigest.getInstance("SHA-256").also { it.update(bytes.slice(0, payload)) }.digest()
            check(MessageDigest.isEqual(expected, actual)) { "$FILE_NAME does not match its digest" }
            val coveredBits = bytes.getInt(HEADER_BYTES - Int.SIZE_BYTES)
            val wordCount = wordsFor(coveredBits)
            val shapesAt = HEADER_BYTES + wordCount * Long.SIZE_BYTES
            check(coveredBits >= 0 && shapesAt + Int.SIZE_BYTES <= payload) { "$FILE_NAME is truncated" }
            val coveredMethods = bytes.getInt(shapesAt)
            check(coveredMethods >= 0 && shapesAt + Int.SIZE_BYTES + coveredMethods == payload) { "$FILE_NAME is truncated" }
            check(coveredBits == nodeBits && coveredMethods == methodCount) {
                "$FILE_NAME covers $coveredBits node ids and $coveredMethods methods, the graph has $nodeBits and $methodCount"
            }
            val words = LongArray(wordCount) { bytes.getLong(HEADER_BYTES + it * Long.SIZE_BYTES) }
            return DeclaredPresence(words, coveredBits, bytes.slice(shapesAt + Int.SIZE_BYTES, coveredMethods))
        }
    }
}
