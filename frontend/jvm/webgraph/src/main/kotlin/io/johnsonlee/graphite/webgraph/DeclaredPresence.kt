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
 * Layout, big-endian: `int32 header = MAGIC | VERSION`, the 32-byte SHA-256 of `graph.types`
 * that `forward.properties` binds, `int32 nodeBits`, `nodeBits / 64` rounded-up `int64` words (bit
 * `id` set when node `id` is bound), `int32 methodCount`, `methodCount` bytes (0 for a method
 * without a row, otherwise 1 + its [DeclaredTypeBinding.HAS_PARAMETERS] / [DeclaredTypeBinding.HAS_BOUNDS]
 * flags, in method-index order), then the SHA-256 of every byte before it. A file whose table
 * digest is not the one `forward.properties` names is another table's and reads as absent; a file
 * that no longer hashes is corruption after binding and throws rather than answers.
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
        internal const val VERSION = 1
        private const val DIGEST_BYTES = 32
        private const val WORD_SHIFT = 6
        private const val WORD_MASK = 63
        private const val BYTE_MASK = 0xFF
        private const val HEADER_BYTES = Int.SIZE_BYTES + DIGEST_BYTES + Int.SIZE_BYTES
        private const val MIN_BYTES = HEADER_BYTES + Int.SIZE_BYTES + DIGEST_BYTES

        /** The byte the sidecar keeps for a method with [types], or without one. */
        fun shapeByte(types: MethodTypes?): Byte {
            if (types == null) return 0
            var flags = 0
            if (types.parameterTypes.isNotEmpty()) flags = flags or DeclaredTypeBinding.HAS_PARAMETERS
            if (types.typeParameters.any { it.bounds.isNotEmpty() }) flags = flags or DeclaredTypeBinding.HAS_BOUNDS
            return (1 + flags).toByte()
        }

        fun encode(typesDigest: ByteArray, words: LongArray, nodeBits: Int, shapes: ByteArray): ByteArray {
            require(typesDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${typesDigest.size} bytes" }
            require(nodeBits >= 0 && words.size == wordsFor(nodeBits)) { "Presence words do not cover $nodeBits node ids" }
            val body = ByteBuffer.allocate(HEADER_BYTES + words.size * Long.SIZE_BYTES + Int.SIZE_BYTES + shapes.size)
            body.putInt(MAGIC or VERSION).put(typesDigest).putInt(nodeBits)
            words.forEach(body::putLong)
            body.putInt(shapes.size).put(shapes)
            val digest = MessageDigest.getInstance("SHA-256").digest(body.array())
            return body.array() + digest
        }

        fun wordsFor(nodeBits: Int): Int = (nodeBits + WORD_MASK) ushr WORD_SHIFT

        /** The sidecar bound to the table `forward.properties` names in [dir], or null when there is none for it. */
        fun load(dir: Path): DeclaredPresence? {
            val binding = DeclaredTypeStore.readBinding(dir) ?: return null
            val path = dir.resolve(FILE_NAME)
            val bytes = if (Files.isRegularFile(path)) {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    if (channel.size() in MIN_BYTES.toLong()..Int.MAX_VALUE.toLong()) {
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    } else {
                        null
                    }
                }
            } else {
                null
            }
            return bytes?.takeIf { bound(it, binding) }?.let(::parse)
        }

        /** Whether [bytes] is this format and names the table [binding] (hex SHA-256) does. */
        private fun bound(bytes: ByteBuffer, binding: String): Boolean {
            if (bytes.getInt(0) != MAGIC or VERSION) return false
            val digest = ByteArray(DIGEST_BYTES).also { bytes.get(Int.SIZE_BYTES, it) }
            return binding.equals(HexFormat.of().formatHex(digest), ignoreCase = true)
        }

        private fun parse(bytes: ByteBuffer): DeclaredPresence {
            val payload = bytes.limit() - DIGEST_BYTES
            val expected = ByteArray(DIGEST_BYTES).also { bytes.get(payload, it) }
            val actual = MessageDigest.getInstance("SHA-256").also { it.update(bytes.slice(0, payload)) }.digest()
            check(MessageDigest.isEqual(expected, actual)) { "$FILE_NAME does not match its digest" }
            val nodeBits = bytes.getInt(HEADER_BYTES - Int.SIZE_BYTES)
            val wordCount = wordsFor(nodeBits)
            val shapesAt = HEADER_BYTES + wordCount * Long.SIZE_BYTES
            check(nodeBits >= 0 && shapesAt + Int.SIZE_BYTES <= payload) { "$FILE_NAME is truncated" }
            val methodCount = bytes.getInt(shapesAt)
            check(methodCount >= 0 && shapesAt + Int.SIZE_BYTES + methodCount == payload) { "$FILE_NAME is truncated" }
            val words = LongArray(wordCount) { bytes.getLong(HEADER_BYTES + it * Long.SIZE_BYTES) }
            return DeclaredPresence(words, nodeBits, bytes.slice(shapesAt + Int.SIZE_BYTES, methodCount))
        }
    }
}
