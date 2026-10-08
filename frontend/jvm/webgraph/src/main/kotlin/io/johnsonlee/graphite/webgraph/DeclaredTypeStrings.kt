package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.TypeParameter
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Save-local dictionary. First occurrence determines the ID; no process-wide string interning. */
internal class DeclaredTypeStringIds(table: DeclaredTypeTable) {
    private val ids = LinkedHashMap<String, Int>()

    init {
        fun add(value: String) { ids.getOrPut(value) { ids.size } }
        fun parameters(values: List<TypeParameter>) { values.forEach { add(it.name); add(it.scope) } }
        table.types.forEach { add(it.kind); add(it.name); add(it.scope); add(it.variance) }
        table.fields.keys.forEach { add(it.owner); add(it.name); add(it.descriptor) }
        table.methods.forEach { (key, method) ->
            add(key.owner); add(key.name); add(key.descriptor); parameters(method.typeParameters)
        }
        table.classes.forEach { (name, type) -> add(name); parameters(type.typeParameters) }
    }

    operator fun get(value: String): Int = ids.getValue(value)

    fun write(output: DataOutputStream) {
        output.writeInt(ids.size)
        val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        for (value in ids.keys) {
            val bytes = encoder.encode(CharBuffer.wrap(value))
            output.writeInt(bytes.remaining())
            output.write(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining())
        }
    }
}

/** Validated UTF-8 spans and UTF-16 hashes; decoded strings are never retained by the mapped reader. */
internal class DeclaredTypeStringPool private constructor(
    private val bytes: ByteBuffer,
    private val offsets: IntArray,
    private val hashes: IntArray
) {
    fun offset(id: Int): Int {
        require(id in offsets.indices) { "Invalid graph.types string ID" }
        return offsets[id]
    }

    fun text(id: Int): String = readDeclaredTypeText(bytes.duplicate().apply { position(offset(id)) })

    fun hash(id: Int): Int { offset(id); return hashes[id] }

    companion object {
        private const val HASH_SPREAD_SHIFT = 16

        fun read(bytes: ByteBuffer): DeclaredTypeStringPool {
            require(bytes.remaining() >= Int.SIZE_BYTES) { "Truncated graph.types" }
            val count = bytes.int
            require(count >= 0 && count <= bytes.remaining() / Int.SIZE_BYTES) { "Invalid graph.types count" }
            val offsets = IntArray(count)
            val hashes = IntArray(count)
            val capacity = if (count == 0) 1 else Integer.highestOneBit(count * 2 - 1) shl 1
            val slots = IntArray(capacity)
            repeat(count) { id ->
                offsets[id] = bytes.position()
                val hash = hashDeclaredTypeText(bytes)
                hashes[id] = hash
                var slot = (hash xor (hash ushr HASH_SPREAD_SHIFT)) and (capacity - 1)
                while (slots[slot] != 0) {
                    val previous = slots[slot] - 1
                    require(hashes[previous] != hash || !sameText(bytes, offsets[previous], offsets[id])) {
                        "Duplicate string in graph.types dictionary"
                    }
                    slot = (slot + 1) and (capacity - 1)
                }
                slots[slot] = id + 1
            }
            return DeclaredTypeStringPool(bytes, offsets, hashes)
        }

        private fun sameText(bytes: ByteBuffer, first: Int, second: Int): Boolean {
            val length = bytes.getInt(first)
            return length == bytes.getInt(second) && (0 until length).all {
                bytes.get(first + Int.SIZE_BYTES + it) == bytes.get(second + Int.SIZE_BYTES + it)
            }
        }
    }
}

internal fun readDeclaredTypeText(bytes: ByteBuffer): String {
    val length = declaredTypeTextLength(bytes)
    val slice = bytes.slice().apply { limit(length) }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(slice).toString()
    bytes.position(bytes.position() + length)
    return text
}

private const val DECLARED_TEXT_HASH_MULTIPLIER = 31

/** Java/Kotlin String.hashCode over strict UTF-8, including UTF-16 surrogate-pair hashing. */
internal fun hashDeclaredTypeText(bytes: ByteBuffer): Int {
    val start = bytes.position()
    val length = declaredTypeTextLength(bytes)
    var position = bytes.position()
    val end = position + length
    var hash = 0
    while (position < end) {
        val character = bytes.get(position++).toInt()
        if (character < 0) {
            bytes.position(start)
            return readDeclaredTypeText(bytes).hashCode()
        }
        hash = DECLARED_TEXT_HASH_MULTIPLIER * hash + character
    }
    bytes.position(end)
    return hash
}

private fun declaredTypeTextLength(bytes: ByteBuffer): Int {
    require(bytes.remaining() >= Int.SIZE_BYTES) { "Truncated graph.types" }
    val length = bytes.int
    require(length >= 0 && length <= bytes.remaining()) { "Invalid graph.types string length" }
    return length
}
