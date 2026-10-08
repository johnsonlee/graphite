package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeReferences
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.HexFormat
import java.util.Properties

/** Independent, versioned type-expression table; erased node records remain unchanged. */
internal object DeclaredTypeStore {
    const val FILE_NAME = "graph.types"
    internal const val BINDING_KEY = "graphite.declaredTypes.sha256"
    private const val PROPERTIES_FILE = "forward.properties"
    private const val LEGACY_HEADER = 0x47545901 // GTY, inline UTF-8
    private const val HEADER = 0x47545902 // GTY, table-local string dictionary
    private const val DIGEST_SIZE = 32
    private const val TYPE_MIN_BYTES = 28
    private const val FIELD_MIN_BYTES = 16
    private const val METHOD_MIN_BYTES = 24
    private const val CLASS_MIN_BYTES = 16
    private const val PARAMETER_MIN_BYTES = 12
    private const val HASH_SPREAD_SHIFT = 16
    private const val HASH_MULTIPLIER = 31

    fun save(table: DeclaredTypeTable, dir: Path) {
        val path = dir.resolve(FILE_NAME)
        if (table == DeclaredTypeTable.EMPTY) {
            Files.deleteIfExists(path)
            writeBinding(dir, null)
            return
        }
        table.validate()
        // A loaded table can map this same path. Replace its inode only after
        // serialization completes, so save(load(dir), dir) never truncates its input.
        val temporary = Files.createTempFile(dir, "graph.types-", ".tmp")
        try {
            writeTable(table, dir, temporary)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            writeBinding(dir, HexFormat.of().formatHex(digest(path)))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeTable(table: DeclaredTypeTable, dir: Path, path: Path) {
        val strings = DeclaredTypeStringIds(table)
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path))).use { out ->
            out.writeInt(HEADER)
            out.write(digest(dir.resolve("graph.metadata")))
            strings.write(out)
            out.writeInt(table.types.size)
            for (type in table.types) {
                out.text(type.kind, strings)
                out.text(type.name, strings)
                out.text(type.scope, strings)
                out.writeInt(type.owner ?: -1)
                out.writeInt(type.component ?: -1)
                out.text(type.variance, strings)
                out.ids(type.arguments)
            }
            out.writeInt(table.fields.size)
            for ((key, type) in table.fields) {
                out.key(key, strings)
                out.writeInt(type)
            }
            out.writeInt(table.methods.size)
            for ((key, method) in table.methods) {
                out.key(key, strings)
                out.ids(method.parameterTypes)
                out.writeInt(method.returnType)
                out.parameters(method.typeParameters, strings)
            }
            out.writeInt(table.classes.size)
            for ((name, type) in table.classes) {
                out.text(name, strings)
                out.parameters(type.typeParameters, strings)
                out.writeInt(type.superType ?: -1)
                out.ids(type.interfaces)
            }
        }
    }

    fun load(dir: Path): DeclaredTypeTable {
        val binding = readBinding(dir) ?: return DeclaredTypeTable.EMPTY
        val path = dir.resolve(FILE_NAME)
        require(Files.isRegularFile(path)) { "Missing graph.types referenced by forward.properties" }
        require(binding.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid graph.types binding" }
        require(binding.equals(HexFormat.of().formatHex(digest(path)), ignoreCase = true)) {
            "graph.types does not match forward.properties"
        }
        return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            require(channel.size() in (Int.SIZE_BYTES + DIGEST_SIZE).toLong()..Int.MAX_VALUE.toLong()) {
                "Invalid graph.types length"
            }
            val reader = Reader(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
            val header = reader.int()
            require(header == HEADER || header == LEGACY_HEADER) { "Unsupported graph.types header/version" }
            val metadataHash = ByteArray(DIGEST_SIZE).also(reader.bytes::get)
            require(MessageDigest.isEqual(metadataHash, digest(dir.resolve("graph.metadata")))) {
                "graph.types does not match graph.metadata"
            }
            if (header == HEADER) reader.strings = DeclaredTypeStringPool.read(reader.bytes)
            val typeCount = reader.rowCount(TYPE_MIN_BYTES)
            val typeOffsets = IntArray(typeCount) {
                reader.bytes.position().also { reader.skipType(typeCount) }
            }
            val types = MappedTypes(reader.bytes, typeOffsets, reader.strings)
            val fields = reader.rows(FIELD_MIN_BYTES, "field", typeCount, Reader::key, ::keyHash, Reader::reference, Reader::int)
            val methods = reader.rows(
                METHOD_MIN_BYTES, "method", typeCount, Reader::key, ::keyHash, Reader::skipMethod, Reader::method
            )
            val classes = reader.rows(
                CLASS_MIN_BYTES, "class", typeCount, Reader::text, ::textHash, Reader::skipClass, Reader::classTypes
            )
            require(!reader.bytes.hasRemaining()) { "Trailing bytes in graph.types" }
            DeclaredTypeTable(types, fields, methods, classes).also { it.validate() }
        }
    }

    private fun readBinding(dir: Path): String? {
        val path = dir.resolve(PROPERTIES_FILE)
        if (!Files.exists(path)) return null
        return Files.newInputStream(path).use { input ->
            Properties().apply { load(input) }.getProperty(BINDING_KEY)
        }
    }

    /** BVGraph rewrites this authoritative file even when an older writer leaves an orphan sidecar. */
    private fun writeBinding(dir: Path, binding: String?) {
        val path = dir.resolve(PROPERTIES_FILE)
        val text = if (Files.exists(path)) Files.readString(path) else ""
        val lines = text.lineSequence().filterNot { it.startsWith("$BINDING_KEY=") }.toList()
        val original = lines.joinToString("\n").trimEnd('\n')
        val content = buildString {
            if (original.isNotEmpty()) append(original).append('\n')
            if (binding != null) append(BINDING_KEY).append('=').append(binding).append('\n')
        }
        if (text != content) Files.writeString(path, content)
    }

    private fun digest(path: Path): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun DataOutputStream.text(value: String, strings: DeclaredTypeStringIds) = writeInt(strings[value])

    private fun DataOutputStream.ids(values: List<Int>) {
        writeInt(values.size)
        values.forEach(::writeInt)
    }

    private fun DataOutputStream.key(key: MemberTypeKey, strings: DeclaredTypeStringIds) {
        text(key.owner, strings)
        text(key.name, strings)
        text(key.descriptor, strings)
    }

    private fun DataOutputStream.parameters(parameters: List<TypeParameter>, strings: DeclaredTypeStringIds) {
        writeInt(parameters.size)
        for (parameter in parameters) {
            text(parameter.name, strings)
            text(parameter.scope, strings)
            ids(parameter.bounds)
        }
    }

    /** Read-only views keep offsets and primitive hash indexes, never decoded declarations. */
    private class MappedTypes(
        private val bytes: ByteBuffer,
        private val offsets: IntArray,
        private val strings: DeclaredTypeStringPool?
    ) :
        AbstractList<DeclaredType>(), DeclaredTypeAtoms {
        override fun typeOffset(index: Int): Int {
            checkElementIndex(index, size)
            return offsets[index]
        }
        override fun atomInt(offset: Int): Int = bytes.getInt(offset)
        override fun atomByte(offset: Int): Byte = bytes.get(offset)
        override fun atomTextOffset(position: Int): Int = strings?.offset(bytes.getInt(position)) ?: position
        override fun nextTextField(position: Int): Int =
            position + Int.SIZE_BYTES + if (strings == null) bytes.getInt(position) else 0
        override val size: Int get() = offsets.size
        override fun get(index: Int): DeclaredType {
            checkElementIndex(index, size)
            return Reader(bytes.duplicate().apply { position(offsets[index]) }, strings).type()
        }
    }

    private class MappedRows<K : Any, V : Any>(
        private val bytes: ByteBuffer,
        private val offsets: IntArray,
        private val valuesAt: IntArray,
        private val hashes: IntArray,
        private val slots: IntArray,
        private val key: (Reader) -> K,
        private val value: (Reader) -> V,
        private val strings: DeclaredTypeStringPool?,
        private val references: Reader.(Int) -> Unit
    ) : AbstractMap<K, V>(), DeclaredTypeReferences {
        override val size: Int get() = offsets.size

        private fun reader(offset: Int) = Reader(bytes.duplicate().apply { position(offset) }, strings)

        override fun validateTypeReferences(typeCount: Int) {
            val input = reader(0)
            for (offset in valuesAt) {
                input.bytes.position(offset)
                input.references(typeCount)
            }
        }

        override fun containsKey(key: K): Boolean = findRow(key) >= 0

        override fun get(key: K): V? {
            val row = findRow(key)
            return if (row < 0) null else value(reader(valuesAt[row]))
        }

        private fun findRow(key: K): Int {
            val hash = key.hashCode()
            var slot = hashSlot(hash, slots.size)
            while (slots[slot] != 0) {
                val row = slots[slot] - 1
                if (hashes[row] == hash && this.key(reader(offsets[row])) == key) {
                    return row
                }
                slot = (slot + 1) and (slots.size - 1)
            }
            return -1
        }

        override val entries: Set<Map.Entry<K, V>> get() = object : AbstractSet<Map.Entry<K, V>>() {
            override val size: Int get() = offsets.size
            override fun iterator(): Iterator<Map.Entry<K, V>> = object : Iterator<Map.Entry<K, V>> {
                private var row = 0
                override fun hasNext() = row < offsets.size
                override fun next(): Map.Entry<K, V> {
                    if (!hasNext()) throw NoSuchElementException()
                    val index = row++
                    return SimpleImmutableEntry(key(reader(offsets[index])), value(reader(valuesAt[index])))
                }
            }
        }

        // Validation consumes values without allocating the unrelated key objects.
        override val values: Collection<V> get() = object : AbstractCollection<V>() {
            override val size: Int get() = offsets.size
            override fun iterator(): Iterator<V> = object : Iterator<V> {
                private var row = 0
                override fun hasNext() = row < offsets.size
                override fun next(): V {
                    if (!hasNext()) throw NoSuchElementException()
                    return value(reader(valuesAt[row++]))
                }
            }
        }
    }

    private fun textHash(reader: Reader): Int = reader.strings?.hash(reader.int()) ?: hashDeclaredTypeText(reader.bytes)

    private fun keyHash(reader: Reader): Int {
        val owner = textHash(reader)
        val name = textHash(reader)
        return HASH_MULTIPLIER * (HASH_MULTIPLIER * owner + name) + textHash(reader)
    }

    private fun hashSlot(hash: Int, capacity: Int): Int = (hash xor (hash ushr HASH_SPREAD_SHIFT)) and (capacity - 1)

    private fun sameBytes(bytes: ByteBuffer, first: Int, firstEnd: Int, second: Int, secondEnd: Int): Boolean {
        if (firstEnd - first != secondEnd - second) return false
        return (0 until firstEnd - first).all { bytes.get(first + it) == bytes.get(second + it) }
    }

    private fun checkElementIndex(index: Int, size: Int) {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index, size=$size")
    }

    private fun Reader.optionalId(): Int? = int().also { require(it >= -1) { "Invalid graph.types reference" } }.takeIf { it >= 0 }

    private class Reader(val bytes: ByteBuffer, var strings: DeclaredTypeStringPool? = null) {
        fun int(): Int {
            require(bytes.remaining() >= Int.SIZE_BYTES) { "Truncated graph.types" }
            return bytes.int
        }
        fun count(): Int = rowCount(Int.SIZE_BYTES)
        fun rowCount(minimumBytes: Int): Int = int().also {
            require(it >= 0 && it <= bytes.remaining() / minimumBytes) { "Invalid graph.types count" }
        }
        fun ids(): List<Int> = List(count()) { int() }
        fun text(): String = strings?.text(int()) ?: readDeclaredTypeText(bytes)
        private fun skipText() {
            val pool = strings
            if (pool == null) text() else pool.offset(int())
        }
        fun key(): MemberTypeKey = MemberTypeKey(text(), text(), text())
        fun parameters(): List<TypeParameter> = List(count()) { TypeParameter(text(), text(), ids()) }
        fun type() = DeclaredType(text(), text(), text(), optionalId(), optionalId(), text(), ids())
        fun method() = MethodTypes(ids(), int(), parameters())
        fun classTypes() = ClassTypes(parameters(), optionalId(), ids())

        fun reference(typeCount: Int) {
            require(int() in 0 until typeCount) { "Invalid graph.types type ID" }
        }
        private fun optionalReference(typeCount: Int) {
            val id = int()
            require(id == -1 || id in 0 until typeCount) { "Invalid graph.types reference" }
        }
        private fun skipIds(typeCount: Int) { repeat(count()) { reference(typeCount) } }
        private fun skipParameters(typeCount: Int) {
            repeat(rowCount(PARAMETER_MIN_BYTES)) { skipText(); skipText(); skipIds(typeCount) }
        }
        fun skipType(typeCount: Int) {
            skipText(); skipText(); skipText()
            optionalReference(typeCount); optionalReference(typeCount)
            skipText(); skipIds(typeCount)
        }
        fun skipMethod(typeCount: Int) {
            skipIds(typeCount); reference(typeCount); skipParameters(typeCount)
        }
        fun skipClass(typeCount: Int) {
            skipParameters(typeCount); optionalReference(typeCount); skipIds(typeCount)
        }

        fun <K : Any, V : Any> rows(
            minimumBytes: Int,
            name: String,
            typeCount: Int,
            key: (Reader) -> K,
            keyHash: (Reader) -> Int,
            skipValue: Reader.(Int) -> Unit,
            value: (Reader) -> V
        ): Map<K, V> {
            val count = rowCount(minimumBytes)
            if (count == 0) return emptyMap()
            val offsets = IntArray(count)
            val valuesAt = IntArray(count)
            val hashes = IntArray(count)
            // Row byte minima bound the count below Int.MAX_VALUE / 16.
            val capacity = Integer.highestOneBit(count * 2 - 1) shl 1
            val slots = IntArray(capacity)
            repeat(count) { row ->
                offsets[row] = bytes.position()
                val hash = keyHash(this)
                hashes[row] = hash
                valuesAt[row] = bytes.position()
                skipValue(typeCount)
                var slot = hashSlot(hash, capacity)
                while (slots[slot] != 0) {
                    val previous = slots[slot] - 1
                    val duplicate = hashes[previous] == hash &&
                        sameBytes(bytes, offsets[previous], valuesAt[previous], offsets[row], valuesAt[row])
                    require(!duplicate) {
                        "Duplicate $name in graph.types"
                    }
                    slot = (slot + 1) and (capacity - 1)
                }
                slots[slot] = row + 1
            }
            return MappedRows(bytes, offsets, valuesAt, hashes, slots, key, value, strings, skipValue)
        }
    }
}
