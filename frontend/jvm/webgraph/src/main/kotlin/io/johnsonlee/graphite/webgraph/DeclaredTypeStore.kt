package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.ClassTypes
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeReferences
import io.johnsonlee.graphite.graph.DeclaredTypeTextField
import io.johnsonlee.graphite.graph.DeclaredTypeValidationAccess
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.ImmutableDeclaredTypeStorage
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
    private const val SHARED_HEADER = 0x47545903 // GTY, graph.strings IDs
    private const val STRUCTURAL_HEADER = 0x47545904 // GTY, enum tags and declaration scope references
    private const val DIGEST_SIZE = 32
    private const val TYPE_MIN_BYTES = 28
    private const val FIELD_MIN_BYTES = 16
    private const val METHOD_MIN_BYTES = 24
    private const val CLASS_MIN_BYTES = 16
    private const val PARAMETER_MIN_BYTES = 12
    private const val HASH_MULTIPLIER = 31

    fun collectStrings(table: DeclaredTypeTable, target: MutableSet<String>) {
        DeclaredTypeStringIds(table, StructuralDeclaredTypeScopes.forTable(table) != null).collect(target)
    }

    /** Advisory optimization only: load still independently requires the verified digest for GTY03/GTY04. */
    fun needsSerializedStrings(dir: Path): Boolean {
        val path = dir.resolve(FILE_NAME)
        if (!Files.isRegularFile(path)) return false
        return java.io.DataInputStream(Files.newInputStream(path)).use {
            if (Files.size(path) < Int.SIZE_BYTES) false else it.readInt() in SHARED_HEADER..STRUCTURAL_HEADER
        }
    }

    fun save(table: DeclaredTypeTable, dir: Path, strings: StringTable) = saveTable(table, dir, strings)

    /** Explicit legacy wire fixture writer; GraphStore writes GTY04 when all scopes have declaration references. */
    internal fun saveLegacyV2(table: DeclaredTypeTable, dir: Path) = saveTable(table, dir, null)

    internal fun saveLegacyV3(table: DeclaredTypeTable, dir: Path, strings: StringTable) =
        saveTable(table, dir, strings, legacyShared = true)

    private fun saveTable(table: DeclaredTypeTable, dir: Path, strings: StringTable?, legacyShared: Boolean = false) {
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
            writeTable(table, dir, temporary, strings, legacyShared)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            writeBinding(dir, HexFormat.of().formatHex(WireIo.digest(path)))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun writeTable(table: DeclaredTypeTable, dir: Path, path: Path, shared: StringTable?, legacyShared: Boolean) {
        val structural = if (shared == null || legacyShared) null else StructuralDeclaredTypeScopes.forTable(table)
        val local = if (shared == null) DeclaredTypeStringIds(table) else null
        val strings: (String) -> Int = { value ->
            Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value))
            if (shared == null) checkNotNull(local)[value] else shared.findId(value).also {
                require(it >= 0) { "Missing declared text in graph.strings" }
            }
        }
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path))).use { out ->
            out.writeInt(if (shared == null) HEADER else if (structural == null) SHARED_HEADER else STRUCTURAL_HEADER)
            out.write(WireIo.digest(dir.resolve("graph.metadata")))
            if (shared == null) checkNotNull(local).write(out) else {
                out.write(requireNotNull(shared.serializedDigest()) { "Unverified graph.strings bytes" })
            }
            out.writeInt(table.types.size)
            for (type in table.types) {
                out.type(type, strings, structural)
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
                out.parameters(method.typeParameters, strings, structural)
            }
            out.writeInt(table.classes.size)
            for ((name, type) in table.classes) {
                out.text(name, strings)
                out.parameters(type.typeParameters, strings, structural)
                out.writeInt(type.superType ?: -1)
                out.ids(type.interfaces)
            }
        }
    }

    private fun DataOutputStream.type(
        type: DeclaredType, strings: (String) -> Int, structural: StructuralDeclaredTypeScopes?
    ) {
        if (structural == null) {
            text(type.kind, strings)
            text(type.name, strings)
            text(type.scope, strings)
        } else {
            writeByte(DECLARED_TYPE_KINDS.indexOf(type.kind))
            writeByte(DECLARED_TYPE_VARIANCES.indexOf(type.variance))
            structural.write(this, type.scope)
            writeInt(if (type.name.isEmpty()) -1 else strings(type.name))
            writeInt(structural.target(type.scope))
        }
        writeInt(type.owner ?: -1)
        writeInt(type.component ?: -1)
        if (structural == null) text(type.variance, strings)
        ids(type.arguments)
    }

    fun load(dir: Path, stringTable: StringTable? = null): DeclaredTypeTable {
        val binding = readBinding(dir) ?: return DeclaredTypeTable.EMPTY
        val path = dir.resolve(FILE_NAME)
        require(Files.isRegularFile(path)) { "Missing graph.types referenced by forward.properties" }
        require(binding.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid graph.types binding" }
        require(binding.equals(HexFormat.of().formatHex(WireIo.digest(path)), ignoreCase = true)) {
            "graph.types does not match forward.properties"
        }
        return FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            require(channel.size() in (Int.SIZE_BYTES + DIGEST_SIZE).toLong()..Int.MAX_VALUE.toLong()) {
                "Invalid graph.types length"
            }
            val reader = Reader(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()))
            val header = reader.int()
            require(header in LEGACY_HEADER..STRUCTURAL_HEADER) { "Unsupported graph.types header/version" }
            val metadataHash = ByteArray(DIGEST_SIZE).also(reader.bytes::get)
            require(MessageDigest.isEqual(metadataHash, WireIo.digest(dir.resolve("graph.metadata")))) {
                "graph.types does not match graph.metadata"
            }
            if (header == HEADER) reader.strings = DeclaredTypeStringPool.read(reader.bytes)
            if (header >= SHARED_HEADER) {
                require(reader.bytes.remaining() >= DIGEST_SIZE) { StructuralDeclaredTypeWire.TRUNCATED }
                val expected = ByteArray(DIGEST_SIZE).also(reader.bytes::get)
                val shared = stringTable ?: StringTable.load(dir, verifySerializedDigest = true)
                val actual = requireNotNull(shared.serializedDigest()) { "Unverified graph.strings bytes" }
                require(MessageDigest.isEqual(expected, actual)) { "graph.types does not match graph.strings" }
                reader.strings = SharedDeclaredTypeTexts(shared)
            }
            if (header == STRUCTURAL_HEADER) reader.structural = StructuralDeclaredTypeContext()
            val typeCount = reader.rowCount(if (reader.structural == null) TYPE_MIN_BYTES else StructuralDeclaredTypeWire.TYPE_MIN_BYTES)
            val typeOffsets = IntArray(typeCount) {
                reader.bytes.position().also { reader.skipType(typeCount) }
            }
            // Member index text is not part of generic_type/type_info and must not dilute their negative summary.
            val textSummary = (reader.strings as? SharedDeclaredTypeTexts)?.finishTypeTextSummary()
            val fields = reader.rows(FIELD_MIN_BYTES, "field", typeCount, Reader::key, ::keyHash, Reader::reference, Reader::int)
            val methods = reader.rows(
                METHOD_MIN_BYTES, "method", typeCount, Reader::key, ::keyHash, Reader::skipMethod, Reader::method
            )
            val classes = reader.rows(
                CLASS_MIN_BYTES, "class", typeCount, Reader::text, ::textHash, Reader::skipClass, Reader::classTypes
            )
            require(!reader.bytes.hasRemaining()) { "Trailing bytes in graph.types" }
            reader.structural?.let { context ->
                context.classCount = classes.size
                context.methodCount = methods.size
                context.classKey = { index -> (classes as MappedRows<String, ClassTypes>).keyAt(index) }
                context.methodKey = { index -> (methods as MappedRows<MemberTypeKey, MethodTypes>).keyAt(index) }
            }
            val types = MappedTypes(reader.bytes, typeOffsets, reader.strings, fields, methods, classes, textSummary,
                reader.structural)
            types.finishStructuralSummary(textSummary)
            DeclaredTypeTable(types, fields, methods, classes).also {
                it.validate()
                (reader.strings as? SharedDeclaredTypeTexts)?.finishLoading()
            }
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

    private fun DataOutputStream.text(value: String, strings: (String) -> Int) = writeInt(strings(value))

    private fun DataOutputStream.ids(values: List<Int>) {
        writeInt(values.size)
        values.forEach(::writeInt)
    }

    private fun DataOutputStream.key(key: MemberTypeKey, strings: (String) -> Int) {
        text(key.owner, strings)
        text(key.name, strings)
        text(key.descriptor, strings)
    }

    private fun DataOutputStream.parameters(
        parameters: List<TypeParameter>, strings: (String) -> Int, structural: StructuralDeclaredTypeScopes?
    ) {
        writeInt(parameters.size)
        for (parameter in parameters) {
            text(parameter.name, strings)
            if (structural == null) text(parameter.scope, strings) else structural.write(this, parameter.scope, formal = true)
            ids(parameter.bounds)
        }
    }

    /** Read-only views keep offsets and primitive hash indexes, never decoded declarations. */
    // One immutable backing implements the list, semantic validator and absolute atom access contracts.
    @Suppress("TooManyFunctions")
    private class MappedTypes(
        private val bytes: ByteBuffer,
        private val offsets: IntArray,
        private val strings: DeclaredTypeTexts?,
        override val immutableFields: Map<MemberTypeKey, Int>,
        override val immutableMethods: Map<MemberTypeKey, MethodTypes>,
        override val immutableClasses: Map<String, ClassTypes>,
        override var textSummary: DeclaredTypeTextSummary?,
        private val structural: StructuralDeclaredTypeContext?
    ) :
        AbstractList<DeclaredType>(), DeclaredTypeAtoms, DeclaredTypeValidationAccess, ImmutableDeclaredTypeStorage {
        fun finishStructuralSummary(textSummary: DeclaredTypeTextSummary?) {
            if (structural == null) return
            val generated = DeclaredTypeTextSummary.Builder()
            val fields = listOf(DeclaredTypeTextField.KIND, DeclaredTypeTextField.SCOPE, DeclaredTypeTextField.VARIANCE)
            for (id in indices) {
                for (field in fields) {
                    generated.beginText()
                    text(id, field).forEach(generated::add)
                }
            }
            this.textSummary = checkNotNull(textSummary).union(generated.build())
        }

        private val validationAccess = MappedDeclaredTypeValidationAccess(this, offsets.size)
        override fun text(id: Int, field: DeclaredTypeTextField): String = validationAccess.text(id, field)
        override fun textIsEmpty(id: Int, field: DeclaredTypeTextField): Boolean = validationAccess.textIsEmpty(id, field)
        override fun textEquals(id: Int, field: DeclaredTypeTextField, expected: String): Boolean =
            validationAccess.textEquals(id, field, expected)
        override fun textUtf8Length(id: Int, field: DeclaredTypeTextField): Int = validationAccess.textUtf8Length(id, field)
        override fun owner(id: Int): Int? = validationAccess.owner(id)
        override fun component(id: Int): Int? = validationAccess.component(id)
        override fun argumentCount(id: Int): Int = validationAccess.argumentCount(id)
        override fun argument(id: Int, index: Int): Int = validationAccess.argument(id, index)

        override fun typeOffset(index: Int): Int {
            checkElementIndex(index, size)
            return offsets[index]
        }
        override fun atomInt(offset: Int): Int = bytes.getInt(offset)
        override fun atomByte(offset: Int): Byte = bytes.get(offset)
        override fun fieldOffset(id: Int, field: DeclaredTypeTextField): Int {
            checkElementIndex(id, size)
            return if (structural == null) super.fieldOffset(id, field)
            else -(id * StructuralDeclaredTypeWire.TEXT_FIELDS + field.ordinal + 1)
        }
        override fun referencesOffset(id: Int): Int =
            if (structural == null) super.referencesOffset(id) else typeOffset(id) + StructuralDeclaredTypeWire.REFERENCES_OFFSET
        override fun argumentsOffset(id: Int): Int =
            if (structural == null) super.argumentsOffset(id) else typeOffset(id) + StructuralDeclaredTypeWire.ARGUMENTS_OFFSET
        private fun structuralText(position: Int): String {
            val token = -position - 1
            val offset = typeOffset(token / StructuralDeclaredTypeWire.TEXT_FIELDS)
            return when (DeclaredTypeTextField.entries[token % StructuralDeclaredTypeWire.TEXT_FIELDS]) {
                DeclaredTypeTextField.KIND -> DECLARED_TYPE_KINDS[bytes.get(offset).toInt() and StructuralDeclaredTypeWire.BYTE_MASK]
                DeclaredTypeTextField.NAME -> bytes.getInt(offset + StructuralDeclaredTypeWire.NAME_OFFSET).let {
                    if (it == -1) "" else checkNotNull(strings).text(it)
                }
                DeclaredTypeTextField.SCOPE -> checkNotNull(structural).scope(bytes, offset + 2)
                DeclaredTypeTextField.VARIANCE ->
                    DECLARED_TYPE_VARIANCES[bytes.get(offset + 1).toInt() and StructuralDeclaredTypeWire.BYTE_MASK]
            }
        }
        override fun atomTextOffset(position: Int): Int = (strings as? DeclaredTypeStringPool)?.offset(bytes.getInt(position))
            ?: position.also { check(strings == null) { "Shared strings have no graph.types byte offset" } }
        override fun atomText(position: Int): String = if (position < 0) structuralText(position)
            else strings?.text(bytes.getInt(position)) ?: super.atomText(position)
        override fun atomTextLength(position: Int): Int = if (position < 0) {
            val token = -position - 1
            if (token % StructuralDeclaredTypeWire.TEXT_FIELDS == DeclaredTypeTextField.NAME.ordinal) {
                val id = bytes.getInt(typeOffset(token / StructuralDeclaredTypeWire.TEXT_FIELDS) + StructuralDeclaredTypeWire.NAME_OFFSET)
                if (id == -1) 0 else (strings as SharedDeclaredTypeTexts).utf8Length(id)
            } else structuralText(position).toByteArray(Charsets.UTF_8).size
        } else if (strings is SharedDeclaredTypeTexts) {
            strings.utf8Length(bytes.getInt(position))
        } else super.atomTextLength(position)
        override fun atomTextEquals(position: Int, expected: String): Boolean = if (position < 0) structuralText(position) == expected
        else if (strings is SharedDeclaredTypeTexts) {
            strings.text(bytes.getInt(position)) == expected
        } else super.atomTextEquals(position, expected)
        override fun atomContains(position: Int, fragment: String): Boolean = if (position < 0) structuralText(position).contains(fragment)
        else if (strings is SharedDeclaredTypeTexts) {
            strings.text(bytes.getInt(position)).contains(fragment)
        } else super.atomContains(position, fragment)
        override fun queryMatcher(fragments: List<String>): DeclaredTypeAtomMatcher? {
            if (strings !is SharedDeclaredTypeTexts || fragments.size > DeclaredTypeAtomMatcher.MAX_FRAGMENTS) return null
            val matcher = strings.queryMatcher(fragments)
            return DeclaredTypeAtomMatcher { position ->
                if (position >= 0) matcher.match(bytes.getInt(position)) else {
                    val token = -position - 1
                    if (token % StructuralDeclaredTypeWire.TEXT_FIELDS == DeclaredTypeTextField.NAME.ordinal) {
                        val offset = typeOffset(token / StructuralDeclaredTypeWire.TEXT_FIELDS)
                        val id = bytes.getInt(offset + StructuralDeclaredTypeWire.NAME_OFFSET)
                        if (id == -1) 0 else matcher.match(id)
                    } else {
                        val value = structuralText(position)
                        var mask = if (value.isEmpty()) 0 else DeclaredTypeAtomMatcher.NON_EMPTY
                        fragments.forEachIndexed { index, fragment -> if (value.contains(fragment)) mask = mask or (1 shl index) }
                        mask
                    }
                }
            }
        }
        override fun nextTextField(position: Int): Int =
            position + Int.SIZE_BYTES + if (strings == null) bytes.getInt(position) else 0
        override val size: Int get() = offsets.size
        override fun get(index: Int): DeclaredType {
            checkElementIndex(index, size)
            return Reader(bytes.duplicate().apply { position(offsets[index]) }, strings, structural).type()
        }
    }

    private fun interface RowKeyHash {
        fun hash(reader: Reader): Int
    }

    private fun interface TypeReferenceCheck {
        fun validate(reader: Reader, typeCount: Int)
    }

    private class RowIndex(val offsets: IntArray, val valuesAt: IntArray, val hashes: IntArray, val slots: IntArray)

    private class MappedRows<K : Any, V : Any>(
        private val bytes: ByteBuffer,
        private val index: RowIndex,
        private val key: (Reader) -> K,
        private val value: (Reader) -> V,
        private val strings: DeclaredTypeTexts?,
        private val references: TypeReferenceCheck,
        private val structural: StructuralDeclaredTypeContext?
    ) : AbstractMap<K, V>(), DeclaredTypeReferences {
        private val offsets get() = index.offsets
        private val valuesAt get() = index.valuesAt
        private val hashes get() = index.hashes
        private val slots get() = index.slots
        override val size: Int get() = offsets.size

        private fun reader(offset: Int) = Reader(bytes.duplicate().apply { position(offset) }, strings, structural)
        fun keyAt(index: Int): K = key(reader(offsets[index]))

        override fun validateTypeReferences(typeCount: Int) {
            val input = reader(0)
            for (offset in valuesAt) {
                input.bytes.position(offset)
                references.validate(input, typeCount)
            }
        }

        override fun containsKey(key: K): Boolean = findRow(key) >= 0

        override fun get(key: K): V? {
            val row = findRow(key)
            return if (row < 0) null else value(reader(valuesAt[row]))
        }

        private fun findRow(key: K): Int {
            val hash = key.hashCode()
            var slot = WireIo.hashSlot(hash, slots.size)
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

    private fun Reader.optionalId(): Int? = int().also { require(it >= -1) { "Invalid graph.types reference" } }.takeIf { it >= 0 }

    private class Reader(
        val bytes: ByteBuffer, var strings: DeclaredTypeTexts? = null,
        var structural: StructuralDeclaredTypeContext? = null
    ) {
        fun int(): Int {
            require(bytes.remaining() >= Int.SIZE_BYTES) { StructuralDeclaredTypeWire.TRUNCATED }
            return bytes.int
        }
        fun rowCount(minimumBytes: Int): Int = int().also {
            require(it >= 0 && it <= bytes.remaining() / minimumBytes) { "Invalid graph.types count" }
        }
        fun ids(): List<Int> = List(rowCount(Int.SIZE_BYTES)) { int() }
        fun text(): String = strings?.text(int()) ?: readDeclaredTypeText(bytes)
        private fun skipText() {
            val pool = strings
            if (pool == null) text() else pool.validateId(int())
        }
        fun key(): MemberTypeKey = MemberTypeKey(text(), text(), text())
        fun parameters(): List<TypeParameter> = List(rowCount(Int.SIZE_BYTES)) {
            TypeParameter(text(), if (structural == null) text() else checkNotNull(structural).readFormal(bytes), ids())
        }
        fun type(): DeclaredType {
            if (structural == null) return DeclaredType(text(), text(), text(), optionalId(), optionalId(), text(), ids())
            val kind = DECLARED_TYPE_KINDS[bytes.readDeclaredTypeByte()]
            val variance = DECLARED_TYPE_VARIANCES[bytes.readDeclaredTypeByte()]
            val tag = bytes.readDeclaredTypeByte()
            bytes.readDeclaredTypeReserved()
            val name = int().let { if (it == -1) "" else checkNotNull(strings).text(it) }
            val scope = checkNotNull(structural).render(tag, int())
            return DeclaredType(kind, name, scope, optionalId(), optionalId(), variance, ids())
        }
        fun method() = MethodTypes(ids(), int(), parameters())
        fun classTypes() = ClassTypes(parameters(), optionalId(), ids())

        fun reference(typeCount: Int) {
            require(int() in 0 until typeCount) { "Invalid graph.types type ID" }
        }
        private fun optionalReference(typeCount: Int) {
            val id = int()
            require(id == -1 || id in 0 until typeCount) { "Invalid graph.types reference" }
        }
        private fun skipIds(typeCount: Int) { repeat(rowCount(Int.SIZE_BYTES)) { reference(typeCount) } }
        private fun skipParameters(typeCount: Int) {
            repeat(rowCount(if (structural == null) PARAMETER_MIN_BYTES else StructuralDeclaredTypeWire.PARAMETER_MIN_BYTES)) {
                skipText()
                if (structural == null) skipText() else {
                    val tag = bytes.readDeclaredTypeByte()
                    repeat(StructuralDeclaredTypeWire.FORMAL_RESERVED_BYTES) { bytes.readDeclaredTypeReserved() }
                    checkNotNull(structural).validate(tag, int())
                }
                skipIds(typeCount)
            }
        }
        fun skipType(typeCount: Int) {
            if (structural == null) {
                skipText(); skipText(); skipText()
            } else {
                require(bytes.readDeclaredTypeByte() in DECLARED_TYPE_KINDS.indices) { "Invalid graph.types kind tag" }
                require(bytes.readDeclaredTypeByte() in DECLARED_TYPE_VARIANCES.indices) { "Invalid graph.types variance tag" }
                val tag = bytes.readDeclaredTypeByte()
                bytes.readDeclaredTypeReserved()
                val name = int()
                if (name != -1) checkNotNull(strings).validateId(name)
                checkNotNull(structural).validate(tag, int())
            }
            optionalReference(typeCount); optionalReference(typeCount)
            if (structural == null) skipText()
            skipIds(typeCount)
        }
        fun skipMethod(typeCount: Int) {
            skipIds(typeCount); reference(typeCount); skipParameters(typeCount)
        }
        fun skipClass(typeCount: Int) {
            skipParameters(typeCount); optionalReference(typeCount); skipIds(typeCount)
        }

        private fun <K> sameKey(key: (Reader) -> K, first: Int, firstEnd: Int, second: Int, secondEnd: Int): Boolean =
            if (strings is SharedDeclaredTypeTexts) {
                key(Reader(bytes.duplicate().apply { position(first) }, strings, structural)) ==
                    key(Reader(bytes.duplicate().apply { position(second) }, strings, structural))
            } else WireIo.sameBytes(bytes, first, firstEnd, second, secondEnd)

        fun <K : Any, V : Any> rows(
            minimumBytes: Int,
            name: String,
            typeCount: Int,
            key: (Reader) -> K,
            keyHash: RowKeyHash,
            skipValue: TypeReferenceCheck,
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
                val hash = keyHash.hash(this)
                hashes[row] = hash
                valuesAt[row] = bytes.position()
                skipValue.validate(this, typeCount)
                var slot = WireIo.hashSlot(hash, capacity)
                while (slots[slot] != 0) {
                    val previous = slots[slot] - 1
                    val duplicate = hashes[previous] == hash &&
                        sameKey(key, offsets[previous], valuesAt[previous], offsets[row], valuesAt[row])
                    require(!duplicate) {
                        "Duplicate $name in graph.types"
                    }
                    slot = (slot + 1) and (capacity - 1)
                }
                slots[slot] = row + 1
            }
            return MappedRows(bytes, RowIndex(offsets, valuesAt, hashes, slots), key, value, strings, skipValue, structural)
        }
    }
}

private fun checkElementIndex(index: Int, size: Int) {
    if (index !in 0 until size) throw IndexOutOfBoundsException("index=$index, size=$size")
}

private object WireIo {
    private const val HASH_SPREAD_SHIFT = 16

    fun digest(path: Path): ByteArray {
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

    fun hashSlot(hash: Int, capacity: Int): Int = (hash xor (hash ushr HASH_SPREAD_SHIFT)) and (capacity - 1)

    fun sameBytes(bytes: ByteBuffer, first: Int, firstEnd: Int, second: Int, secondEnd: Int): Boolean {
        if (firstEnd - first != secondEnd - second) return false
        return (0 until firstEnd - first).all { bytes.get(first + it) == bytes.get(second + it) }
    }
}
