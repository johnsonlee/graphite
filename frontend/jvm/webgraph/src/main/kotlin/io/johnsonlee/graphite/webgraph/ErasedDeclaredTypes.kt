package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import java.io.DataOutputStream
import java.nio.ByteBuffer

private const val ERASED_DESCRIPTOR_LIMIT = "Erased descriptor exceeds string representation"

/** A descriptor is rendered from raw expressions only, never by erasing a generic variable or bound. */
internal class ErasedDeclaredTypes(private val count: Int, private val type: (Int) -> DeclaredType) {
    private val utf8Lengths = IntArray(count) { -1 }
    private val utf16Lengths = IntArray(count) { -1 }
    private val validatedLeaves = BooleanArray(count)
    private val voidRoots = BooleanArray(count)

    data class Shape(val dimensions: Int, val primitive: Boolean, val name: String)

    fun shape(id: Int, allowVoid: Boolean = false): Shape {
        var current = id
        var dimensions = 0
        while (true) {
            require(current in 0 until count) { "Invalid erased type reference" }
            val row = type(current)
            require(row.scope.isEmpty() && row.owner == null && row.arguments.isEmpty() && row.variance.isEmpty()) {
                "Noncanonical erased type"
            }
            if (row.kind != "array") {
                return leafShape(current, row, dimensions, allowVoid)
            }
            require(row.name.isEmpty() && ++dimensions < DeclaredTypeTable.MAX_DEPTH) { "Invalid erased array nesting" }
            current = requireNotNull(row.component) { "Missing erased array component" }
        }
    }

    private fun leafShape(id: Int, row: DeclaredType, dimensions: Int, allowVoid: Boolean): Shape {
        require(row.component == null) { "Noncanonical erased type component" }
        when (row.kind) {
            "class" -> if (!validatedLeaves[id]) {
                require(validClassName(row.name)) { "Invalid erased class name" }
                validatedLeaves[id] = true
            }
            "primitive" -> {
                require(row.name in PRIMITIVES) { "Invalid erased primitive" }
                require(row.name != "void" || allowVoid && dimensions == 0) { "Void erased field, parameter or array" }
            }
            else -> throw IllegalArgumentException("Noncanonical erased type kind")
        }
        return Shape(dimensions, row.kind == "primitive", row.name)
    }

    fun length(id: Int, utf8: Boolean, allowVoid: Boolean = false): Int {
        require(id in 0 until count) { "Invalid erased type reference" }
        if (utf8Lengths[id] < 0) {
            val shape = shape(id, allowVoid)
            var bytes = shape.dimensions.toLong()
            val units: Long
            if (shape.primitive) {
                bytes++
                units = bytes
            } else {
                bytes += 2
                shape.name.forEach { bytes += utf8Width(it) }
                units = shape.dimensions.toLong() + 2 + shape.name.length
            }
            require(bytes <= Int.MAX_VALUE && units <= Int.MAX_VALUE) { ERASED_DESCRIPTOR_LIMIT }
            utf8Lengths[id] = bytes.toInt()
            utf16Lengths[id] = units.toInt()
            voidRoots[id] = shape.primitive && shape.name == "void"
        }
        require(allowVoid || !voidRoots[id]) { "Void erased field or parameter" }
        return if (utf8) utf8Lengths[id] else utf16Lengths[id]
    }

    fun characters(id: Int, allowVoid: Boolean = false, consume: (Char) -> Unit) {
        val shape = shape(id, allowVoid)
        repeat(shape.dimensions) { consume('[') }
        if (shape.primitive) consume(PRIMITIVES.getValue(shape.name).single()) else {
            consume('L')
            shape.name.forEach { consume(if (it == '.') '/' else it) }
            consume(';')
        }
    }

    fun descriptor(id: Int, allowVoid: Boolean = false): String = buildString { characters(id, allowVoid) { append(it) } }

    companion object {
        private const val ASCII_LIMIT = 128
        private const val TWO_BYTE_LIMIT = 2048
        private const val THREE_BYTE_LENGTH = 3
        private fun utf8Width(char: Char): Int = when {
            char.code < ASCII_LIMIT -> 1
            char.code < TWO_BYTE_LIMIT || char.isSurrogate() -> 2
            else -> THREE_BYTE_LENGTH
        }
        private fun validClassName(name: String): Boolean {
            if (name.isEmpty() || name.first() == '.' || name.last() == '.') return false
            var previous = '\u0000'
            return name.all { char ->
                val forbidden = char == '/' || char == ';' || char == '['
                val emptySegment = char == '.' && previous == '.'
                previous = char
                !forbidden && !emptySegment
            }
        }
        private val PRIMITIVES = mapOf("boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S", "int" to "I",
            "long" to "J", "float" to "F", "double" to "D", "void" to "V")
    }
}

/** Save-local index references existing rows; the writer never appends or renumbers types. */
internal class ErasedDeclaredTypePlan private constructor(
    private val rawIds: Map<String, Int>, private val signatureIds: Map<String, Int>,
    private val signatures: List<ErasedMethodSignature>
) {
    fun field(descriptor: String): Int = rawIds.getValue(descriptor)
    fun method(descriptor: String): Int = signatureIds.getValue(descriptor)
    fun write(output: DataOutputStream) {
        output.writeInt(signatures.size)
        signatures.forEach { signature ->
            output.writeInt(signature.parameters.size)
            signature.parameters.forEach(output::writeInt)
            output.writeInt(signature.returns)
        }
    }

    companion object {
        fun forTable(table: DeclaredTypeTable): ErasedDeclaredTypePlan? {
            val raw = ErasedDeclaredTypes(table.types.size, table.types::get)
            val ids = HashMap<String, Int>()
            table.types.indices.forEach { id ->
                val descriptor = try { raw.descriptor(id, allowVoid = true) } catch (_: IllegalArgumentException) { null }
                if (descriptor != null) ids.putIfAbsent(descriptor, id)
            }
            return if (table.fields.keys.any { it.descriptor == "V" || it.descriptor !in ids }) null else methods(table, ids)
        }

        private fun methods(table: DeclaredTypeTable, ids: Map<String, Int>): ErasedDeclaredTypePlan? {
            val signatureIds = LinkedHashMap<String, Int>()
            val signatures = ArrayList<ErasedMethodSignature>()
            for (key in table.methods.keys) {
                if (key.descriptor in signatureIds) continue
                val signature = parseSignature(key.descriptor, ids) ?: return null
                signatureIds[key.descriptor] = signatures.size
                signatures.add(signature)
            }
            return ErasedDeclaredTypePlan(ids, signatureIds, signatures)
        }

        private fun parseSignature(value: String, ids: Map<String, Int>): ErasedMethodSignature? = try {
            require(value.startsWith('('))
            val parameters = ArrayList<Int>()
            var position = 1
            while (position < value.length && value[position] != ')') {
                val start = position
                while (position < value.length && value[position] == '[') position++
                require(position < value.length && value[position] != 'V')
                position = if (value[position] == 'L') {
                    val end = value.indexOf(';', position)
                    require(end >= 0)
                    end + 1
                } else position + 1
                parameters.add(requireNotNull(ids[value.substring(start, position)]))
            }
            require(position < value.length)
            val returns = requireNotNull(ids[value.substring(position + 1)])
            ErasedMethodSignature(parameters, returns)
        } catch (_: IllegalArgumentException) {
            null
        }

    }
}

internal data class ErasedMethodSignature(val parameters: List<Int>, val returns: Int)

/** Validated signature offsets and raw expression access; no retained rendered descriptor strings. */
internal class ErasedDeclaredTypeContext(
    private val bytes: ByteBuffer, private val raw: ErasedDeclaredTypes, private val signatureOffsets: IntArray
) {
    // Reuse descriptor hashes while constructing member indexes, then release these primitive-only caches.
    private var fieldHashes: Int2IntOpenHashMap? = Int2IntOpenHashMap()
    private var methodHashes: Int2IntOpenHashMap? = Int2IntOpenHashMap()

    fun field(id: Int): String = raw.descriptor(id)
    fun method(id: Int): String = buildString { characters(id, method = true) { append(it) } }

    fun characters(id: Int, method: Boolean, consume: (Char) -> Unit) {
        if (!method) raw.characters(id, consume = consume) else {
            val input = signature(id)
            consume('(')
            repeat(input.int) { raw.characters(input.int, consume = consume) }
            consume(')')
            raw.characters(input.int, allowVoid = true, consume)
        }
    }

    fun hash(id: Int, method: Boolean): Int {
        val cache = if (method) methodHashes else fieldHashes
        val hash = if (cache != null && cache.containsKey(id)) cache.get(id) else {
            utf8Length(id, method)
            var value = 0
            characters(id, method) { value = HASH_MULTIPLIER * value + it.code }
            cache?.put(id, value)
            value
        }
        return hash
    }

    fun finishIndexing() {
        fieldHashes = null
        methodHashes = null
    }

    fun matches(id: Int, method: Boolean, value: String): Boolean {
        var index = 0L
        var matches = true
        characters(id, method) {
            if (index >= value.length || value[index.toInt()] != it) matches = false
            index++
        }
        return matches && index == value.length.toLong()
    }

    fun equivalent(left: Int, right: Int, method: Boolean): Boolean =
        if (method) equivalentMethods(left, right) else raw.shape(left) == raw.shape(right)

    private fun equivalentMethods(left: Int, right: Int): Boolean {
        val a = signature(left)
        val b = signature(right)
        val count = a.int
        var equivalent = count == b.int
        var index = 0
        while (equivalent && index < count) {
            equivalent = raw.shape(a.int) == raw.shape(b.int)
            index++
        }
        return equivalent && raw.shape(a.int, allowVoid = true) == raw.shape(b.int, allowVoid = true)
    }

    fun utf8Length(id: Int, method: Boolean): Int {
        if (!method) return raw.length(id, utf8 = true)
        val input = signature(id)
        var bytes = 2L // Method descriptor parentheses.
        var units = 2L
        repeat(input.int) {
            val parameter = input.int
            bytes += raw.length(parameter, utf8 = true)
            units += raw.length(parameter, utf8 = false)
            require(bytes <= Int.MAX_VALUE && units <= Int.MAX_VALUE) { ERASED_DESCRIPTOR_LIMIT }
        }
        val returns = input.int
        bytes += raw.length(returns, utf8 = true, allowVoid = true)
        units += raw.length(returns, utf8 = false, allowVoid = true)
        require(bytes <= Int.MAX_VALUE && units <= Int.MAX_VALUE) { ERASED_DESCRIPTOR_LIMIT }
        return bytes.toInt()
    }

    private fun signature(id: Int): ByteBuffer {
        require(id in signatureOffsets.indices) { "Invalid erased signature reference" }
        return bytes.duplicate().apply { position(signatureOffsets[id]) }
    }

    companion object {
        private const val SIGNATURE_MIN_BYTES = 8
        private const val HASH_MULTIPLIER = 31
        fun read(bytes: ByteBuffer, raw: ErasedDeclaredTypes): ErasedDeclaredTypeContext {
            require(bytes.remaining() >= Int.SIZE_BYTES) { StructuralDeclaredTypeWire.TRUNCATED }
            val count = bytes.int
            require(count >= 0 && count <= bytes.remaining() / SIGNATURE_MIN_BYTES) { "Invalid erased signature count" }
            val offsets = IntArray(count)
            repeat(count) { index ->
                require(bytes.remaining() >= SIGNATURE_MIN_BYTES) { StructuralDeclaredTypeWire.TRUNCATED }
                offsets[index] = bytes.position()
                val parameters = bytes.int
                require(parameters >= 0 && parameters <= (bytes.remaining() - Int.SIZE_BYTES) / Int.SIZE_BYTES) {
                    "Invalid erased signature parameter count"
                }
                repeat(parameters) { raw.length(bytes.int, utf8 = true) }
                raw.length(bytes.int, utf8 = true, allowVoid = true)
            }
            val context = ErasedDeclaredTypeContext(bytes, raw, offsets)
            val seen = HashMap<Int, MutableList<Int>>()
            offsets.indices.forEach { id ->
                context.utf8Length(id, method = true)
                val sameHash = seen.getOrPut(context.hash(id, method = true)) { ArrayList() }
                require(sameHash.none { context.equivalent(it, id, method = true) }) { "Duplicate erased method signature" }
                sameHash.add(id)
            }
            return context
        }
    }
}
