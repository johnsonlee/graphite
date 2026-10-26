package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.jvmTypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The `graph.declared-keys` sidecar: for Field, Parameter and Return nodes, how many carry a
 * declaration in `graph.types` (bound) and how many do not, with the first node id of each
 * partition, plus the table's field and method counts. It is what the native reader computes on
 * its first declared-key question (`Graph::compute_declared_key_partitions`): the table parsed and
 * every declaration-bearing node decoded and looked up, 50 ms a graph, which the first `keys()`
 * over 64 fresh graphs paid as 3.8 s on the hosted runner. The saver knows both the table and the
 * nodes, so it writes the answer in the pass that writes the nodes, bound to the SHA-256 of
 * `graph.types` that `forward.properties` already binds and to the SHA-256 of `graph.nodedata` it
 * records beside it ([ForwardProperties.NODE_LAYOUT_KEY]): the partitions name node ids, so a
 * summary of another graph with the same table and other node ids must not be accepted. A reader
 * that has verified the table digest and read the layout binding trusts a summary naming both at no
 * further cost; a sidecar beside a rewritten table or rewritten nodes names a digest the directory
 * no longer has and is read as absent, and the reader computes the partitions itself.
 *
 * Layout, big-endian: `int32 header = MAGIC | VERSION`, the 32-byte table digest, the 32-byte node
 * layout digest, `int64 fieldCount`, `int64 methodCount`, then per tag (Field, Parameter, Return)
 * `byte present` and, when 1, `int32 first, int64 count` for the unbound partition and again for the
 * bound one, then the SHA-256 of every byte before it, so a parsable bit flip is refused rather than
 * read as a different partition. A tag is present on the exact conditions under which the native
 * reader computes it: the table has members of its kind, the tag's node ids are ascending in
 * type-index order, no method has more than [MAX_PARAMETERS] parameters, and no node's identity
 * strings exceed [MAX_KEY_BYTES] in UTF-8; otherwise the reader is left to its own computation,
 * which declines the same way.
 */
internal class DeclaredKeySummaryBuilder(private val table: DeclaredTypeTable, maxNodeId: Int = -1) {
    /** One bit per node id, set for a bound node; empty when the saver did not say how many ids there are. */
    val presenceWords: LongArray = LongArray(if (maxNodeId < 0) 0 else DeclaredPresence.wordsFor(maxNodeId + 1))
    val nodeBits: Int = maxNodeId + 1

    private class Partitions {
        var present = true
        var previousId = -1L
        val first = IntArray(2)
        val count = LongArray(2)

        fun add(id: Int, bound: Boolean, words: LongArray) {
            if (bound && words.isNotEmpty()) words[id ushr WORD_SHIFT] = words[id ushr WORD_SHIFT] or (1L shl (id and WORD_MASK))
            if (id.toLong() < previousId) present = false
            previousId = id.toLong()
            val index = if (bound) 1 else 0
            if (count[index] == 0L) first[index] = id
            count[index]++
        }
    }

    private val tags = Array(TAGS) { Partitions() }

    /** Record [node] in type-index order; nodes of other kinds are ignored. */
    fun add(node: Node) {
        when (node) {
            is FieldNode -> {
                val descriptor = node.descriptor
                val classes = descriptor.declaringClass.className
                val type = descriptor.type.className
                val slot = tags[FIELD]
                if (keyBytes(classes, descriptor.name, type) > MAX_KEY_BYTES) slot.present = false
                slot.add(node.id.value, MemberTypeKey(classes, descriptor.name, jvmTypeDescriptor(type)) in table.fields, presenceWords)
            }
            is ParameterNode -> {
                val slot = tags[PARAMETER]
                val method = method(node.method, slot)
                slot.add(node.id.value, method != null && node.index >= 0 && node.index < method.parameterTypes.size, presenceWords)
            }
            is ReturnNode -> {
                val slot = tags[RETURN]
                slot.add(node.id.value, method(node.method, slot) != null, presenceWords)
            }
            else -> Unit
        }
    }

    private fun method(method: MethodDescriptor, slot: Partitions) = table.methods[
        MemberTypeKey(method.declaringClass.className, method.name, method.descriptor)
    ].also {
        if (method.parameterTypes.size > MAX_PARAMETERS) slot.present = false
        var bytes = keyBytes(method.declaringClass.className, method.name, method.returnType.className)
        for (parameter in method.parameterTypes) bytes += utf8Length(parameter.className) + KEY_OVERHEAD
        if (bytes > MAX_KEY_BYTES) slot.present = false
    }

    /** The sidecar bound to the table whose `graph.types` has [typesDigest] and the nodes whose `graph.nodedata` has [layoutDigest]. */
    fun encode(typesDigest: ByteArray, layoutDigest: ByteArray): ByteArray {
        require(typesDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${typesDigest.size} bytes" }
        require(layoutDigest.size == DIGEST_BYTES) { "Expected a SHA-256 digest, got ${layoutDigest.size} bytes" }
        val present = BooleanArray(TAGS) { tag ->
            tags[tag].present && (if (tag == FIELD) table.fields.isNotEmpty() else table.methods.isNotEmpty())
        }
        val buffer = ByteBuffer.allocate(HEADER_BYTES + present.count { it } * PRESENT_TAG_BYTES)
        buffer.putInt(MAGIC or VERSION)
        buffer.put(typesDigest)
        buffer.put(layoutDigest)
        buffer.putLong(table.fields.size.toLong())
        buffer.putLong(table.methods.size.toLong())
        for (tag in 0 until TAGS) {
            if (!present[tag]) {
                buffer.put(0)
                continue
            }
            buffer.put(1)
            val slot = tags[tag]
            for (index in 0..1) {
                buffer.putInt(slot.first[index])
                buffer.putLong(slot.count[index])
            }
        }
        return buffer.array() + MessageDigest.getInstance("SHA-256").digest(buffer.array())
    }

    companion object {
        const val FILE_NAME = "graph.declared-keys"
        internal const val MAGIC = 0x47444B00 // "GDK"
        internal const val VERSION = 2
        private const val TAGS = 3
        private const val FIELD = 0
        private const val PARAMETER = 1
        private const val RETURN = 2
        private const val DIGEST_BYTES = 32
        private const val WORD_SHIFT = 6
        private const val WORD_MASK = 63
        private const val HEADER_BYTES = Int.SIZE_BYTES + 2 * DIGEST_BYTES + 2 * Long.SIZE_BYTES + TAGS
        private const val PRESENT_TAG_BYTES = 2 * (Int.SIZE_BYTES + Long.SIZE_BYTES)
        /** JVM descriptors have at most 255 parameter slots; the native summary decoder stops at 256. */
        internal const val MAX_PARAMETERS = 256
        /** The native reader declines a node whose identity strings, two bytes of overhead each, exceed this. */
        internal const val MAX_KEY_BYTES = 1_000_000L
        private const val KEY_OVERHEAD = 2L

        private fun keyBytes(vararg strings: String): Long = strings.sumOf { utf8Length(it) + KEY_OVERHEAD }

        private const val ONE_BYTE_LIMIT = 0x80
        private const val TWO_BYTE_LIMIT = 0x800
        private const val THREE_BYTE_LIMIT = 0x10000
        private const val THREE_BYTES = 3L
        private const val FOUR_BYTES = 4L

        /** The UTF-8 length of [text] without encoding it. */
        internal fun utf8Length(text: String): Long {
            var bytes = 0L
            var index = 0
            while (index < text.length) {
                val code = text.codePointAt(index)
                bytes += when {
                    code < ONE_BYTE_LIMIT -> 1L
                    code < TWO_BYTE_LIMIT -> 2L
                    code < THREE_BYTE_LIMIT -> THREE_BYTES
                    else -> FOUR_BYTES
                }
                index += Character.charCount(code)
            }
            return bytes
        }
    }
}
