package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.TypeParameter
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.lang.MutableString
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException

/** Save-local dictionary. First occurrence determines the ID; no process-wide string interning. */
internal interface DeclaredTypeTexts {
    fun text(id: Int): String
    fun hash(id: Int): Int
    fun validateId(id: Int)
}

/** Shared IDs retain no decoded declaration strings and validate every referenced UTF-16 value. */
internal class SharedDeclaredTypeTexts(private val strings: StringTable) : DeclaredTypeTexts {
    private val checked = java.util.BitSet()
    private var loadingStats: SharedDeclaredTypeTextStats? = SharedDeclaredTypeTextStats(strings)

    override fun validateId(id: Int) {
        require(id in 0 until strings.size()) { "Invalid graph.types string ID" }
        if (!checked[id]) {
            val stats = loadingStats
            if (stats == null) {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(strings.get(id)))
            } else {
                stats.add(id)
                checked.set(id)
            }
        }
    }

    override fun text(id: Int): String { validateId(id); return strings.get(id) }
    override fun hash(id: Int): Int {
        validateId(id)
        return loadingStats?.hash(id) ?: strings.get(id).hashCode()
    }

    fun utf8Length(id: Int): Int {
        validateId(id)
        return loadingStats?.utf8Length(id) ?: strings.get(id).toByteArray(Charsets.UTF_8).size
    }

    /** Called after complete validation and before any mapped view is published to query threads. */
    fun finishLoading() { loadingStats = null }
}

/** Per-load primitive facts only. Both the decode buffer and the map are discarded before publication. */
private class SharedDeclaredTypeTextStats(private val strings: StringTable) {
    private val values = Int2LongOpenHashMap()
    private val buffer = MutableString()

    fun add(id: Int) {
        strings.get(id, buffer)
        var hash = 0
        var length = 0L
        var position = 0
        while (position < buffer.length) {
            val character = buffer[position++]
            hash = DECLARED_TEXT_HASH_MULTIPLIER * hash + character.code
            length += when {
                character.code < ASCII_LIMIT -> 1
                character.code < TWO_BYTE_LIMIT -> 2
                character.isHighSurrogate() -> {
                    if (position == buffer.length || !buffer[position].isLowSurrogate()) throw MalformedInputException(1)
                    hash = DECLARED_TEXT_HASH_MULTIPLIER * hash + buffer[position++].code
                    SUPPLEMENTARY_BYTES
                }
                character.isLowSurrogate() -> throw MalformedInputException(1)
                else -> THREE_BYTE_LENGTH
            }
        }
        require(length <= Int.MAX_VALUE) { "Excessive graph.types string length" }
        values.put(id, (hash.toLong() shl Int.SIZE_BITS) or length)
    }

    fun hash(id: Int): Int = (values.get(id) ushr Int.SIZE_BITS).toInt()
    fun utf8Length(id: Int): Int = values.get(id).toInt()

    private companion object {
        const val ASCII_LIMIT = 0x80
        const val TWO_BYTE_LIMIT = 0x800
        const val THREE_BYTE_LENGTH = 3
        const val SUPPLEMENTARY_BYTES = 4
    }
}

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

    fun collect(target: MutableSet<String>) { target.addAll(ids.keys) }

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
) : DeclaredTypeTexts {
    fun offset(id: Int): Int {
        require(id in offsets.indices) { "Invalid graph.types string ID" }
        return offsets[id]
    }

    override fun validateId(id: Int) { offset(id) }

    override fun text(id: Int): String = readDeclaredTypeText(bytes.duplicate().apply { position(offset(id)) })

    override fun hash(id: Int): Int { offset(id); return hashes[id] }

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
