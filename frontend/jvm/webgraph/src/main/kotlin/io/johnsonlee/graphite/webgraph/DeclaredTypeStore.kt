package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
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
    private const val HEADER = 0x47545901 // GTY, version 1
    private const val DIGEST_SIZE = 32
    private const val TYPE_MIN_BYTES = 28
    private const val FIELD_MIN_BYTES = 16
    private const val METHOD_MIN_BYTES = 24
    private const val CLASS_MIN_BYTES = 16
    private const val PARAMETER_MIN_BYTES = 12
    private const val HASH_SPREAD_SHIFT = 16

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
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path))).use { out ->
            out.writeInt(HEADER)
            out.write(digest(dir.resolve("graph.metadata")))
            out.writeInt(table.types.size)
            for (type in table.types) {
                out.text(type.kind)
                out.text(type.name)
                out.text(type.scope)
                out.writeInt(type.owner ?: -1)
                out.writeInt(type.component ?: -1)
                out.text(type.variance)
                out.ids(type.arguments)
            }
            out.writeInt(table.fields.size)
            for ((key, type) in table.fields) {
                out.key(key)
                out.writeInt(type)
            }
            out.writeInt(table.methods.size)
            for ((key, method) in table.methods) {
                out.key(key)
                out.ids(method.parameterTypes)
                out.writeInt(method.returnType)
                out.parameters(method.typeParameters)
            }
            out.writeInt(table.classes.size)
            for ((name, type) in table.classes) {
                out.text(name)
                out.parameters(type.typeParameters)
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
            require(reader.int() == HEADER) { "Unsupported graph.types header/version" }
            val metadataHash = ByteArray(DIGEST_SIZE).also(reader.bytes::get)
            require(MessageDigest.isEqual(metadataHash, digest(dir.resolve("graph.metadata")))) {
                "graph.types does not match graph.metadata"
            }
            val typeCount = reader.rowCount(TYPE_MIN_BYTES)
            val typeOffsets = IntArray(typeCount) {
                reader.bytes.position().also { reader.skipType(typeCount) }
            }
            val types = MappedTypes(reader.bytes, typeOffsets)
            val fields = reader.rows(FIELD_MIN_BYTES, "field", Reader::key, { reference(typeCount) }, Reader::int)
            val methods = reader.rows(METHOD_MIN_BYTES, "method", Reader::key, { skipMethod(typeCount) }, Reader::method)
            val classes = reader.rows(CLASS_MIN_BYTES, "class", Reader::text, { skipClass(typeCount) }, Reader::classTypes)
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

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataOutputStream.ids(values: List<Int>) {
        writeInt(values.size)
        values.forEach(::writeInt)
    }

    private fun DataOutputStream.key(key: MemberTypeKey) {
        text(key.owner)
        text(key.name)
        text(key.descriptor)
    }

    private fun DataOutputStream.parameters(parameters: List<TypeParameter>) {
        writeInt(parameters.size)
        for (parameter in parameters) {
            text(parameter.name)
            text(parameter.scope)
            ids(parameter.bounds)
        }
    }

    /** Read-only views keep offsets and primitive hash indexes, never decoded declarations. */
    private class MappedTypes(private val bytes: ByteBuffer, private val offsets: IntArray) : AbstractList<DeclaredType>() {
        override val size: Int get() = offsets.size
        override fun get(index: Int): DeclaredType {
            checkElementIndex(index, size)
            return Reader(bytes.duplicate().apply { position(offsets[index]) }).type()
        }
    }

    private class MappedRows<K : Any, V : Any>(
        private val bytes: ByteBuffer,
        private val offsets: IntArray,
        private val valuesAt: IntArray,
        private val hashes: IntArray,
        private val slots: IntArray,
        private val key: (Reader) -> K,
        private val value: (Reader) -> V
    ) : AbstractMap<K, V>() {
        override val size: Int get() = offsets.size

        private fun reader(offset: Int) = Reader(bytes.duplicate().apply { position(offset) })

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

    private fun hashSlot(hash: Int, capacity: Int): Int = (hash xor (hash ushr HASH_SPREAD_SHIFT)) and (capacity - 1)

    private fun sameBytes(bytes: ByteBuffer, first: Int, firstEnd: Int, second: Int, secondEnd: Int): Boolean {
        if (firstEnd - first != secondEnd - second) return false
        return (0 until firstEnd - first).all { bytes.get(first + it) == bytes.get(second + it) }
    }

    private fun checkElementIndex(index: Int, size: Int) {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index, size=$size")
    }

    private class Reader(val bytes: ByteBuffer) {
        fun int(): Int {
            require(bytes.remaining() >= Int.SIZE_BYTES) { "Truncated graph.types" }
            return bytes.int
        }
        fun count(): Int = rowCount(Int.SIZE_BYTES)
        fun rowCount(minimumBytes: Int): Int = int().also {
            require(it >= 0 && it <= bytes.remaining() / minimumBytes) { "Invalid graph.types count" }
        }
        fun optionalId(): Int? = int().also { require(it >= -1) { "Invalid graph.types reference" } }.takeIf { it >= 0 }
        fun ids(): List<Int> = List(count()) { int() }
        fun text(): String {
            val length = int()
            require(length >= 0 && length <= bytes.remaining()) { "Invalid graph.types string length" }
            val slice = bytes.slice().apply { limit(length) }
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(slice).toString()
            bytes.position(bytes.position() + length)
            return text
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
            repeat(rowCount(PARAMETER_MIN_BYTES)) { text(); text(); skipIds(typeCount) }
        }
        fun skipType(typeCount: Int) {
            text(); text(); text()
            optionalReference(typeCount); optionalReference(typeCount)
            text(); skipIds(typeCount)
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
            key: (Reader) -> K,
            skipValue: Reader.() -> Unit,
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
                val hash = key(this).hashCode()
                hashes[row] = hash
                valuesAt[row] = bytes.position()
                skipValue()
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
            return MappedRows(bytes, offsets, valuesAt, hashes, slots, key, value)
        }
    }
}
