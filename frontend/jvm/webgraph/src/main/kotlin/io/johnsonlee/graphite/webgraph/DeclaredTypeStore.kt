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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties

/** Independent, versioned type-expression table; erased node records remain unchanged. */
internal object DeclaredTypeStore {
    const val FILE_NAME = "graph.types"
    internal const val BINDING_KEY = "graphite.declaredTypes.sha256"
    private const val PROPERTIES_FILE = "forward.properties"
    private const val HEADER = 0x47545901 // GTY, version 1
    private const val DIGEST_SIZE = 32

    fun save(table: DeclaredTypeTable, dir: Path) {
        val path = dir.resolve(FILE_NAME)
        if (table == DeclaredTypeTable.EMPTY) {
            Files.deleteIfExists(path)
            writeBinding(dir, null)
            return
        }
        table.validate()
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
        writeBinding(dir, HexFormat.of().formatHex(digest(path)))
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
            val types = List(reader.count()) {
                DeclaredType(
                    reader.text(), reader.text(), reader.text(), reader.optionalId(), reader.optionalId(), reader.text(), reader.ids()
                )
            }
            val fields = linkedMapOf<MemberTypeKey, Int>()
            repeat(reader.count()) {
                require(fields.put(reader.key(), reader.int()) == null) { "Duplicate field in graph.types" }
            }
            val methods = linkedMapOf<MemberTypeKey, MethodTypes>()
            repeat(reader.count()) {
                val key = reader.key()
                require(methods.put(key, MethodTypes(reader.ids(), reader.int(), reader.parameters())) == null) {
                    "Duplicate method in graph.types"
                }
            }
            val classes = linkedMapOf<String, ClassTypes>()
            repeat(reader.count()) {
                val name = reader.text()
                require(classes.put(name, ClassTypes(reader.parameters(), reader.optionalId(), reader.ids())) == null) {
                    "Duplicate class in graph.types"
                }
            }
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

    private class Reader(val bytes: ByteBuffer) {
        fun int(): Int {
            require(bytes.remaining() >= Int.SIZE_BYTES) { "Truncated graph.types" }
            return bytes.int
        }
        fun count(): Int = int().also {
            require(it >= 0 && it <= bytes.remaining() / Int.SIZE_BYTES) { "Invalid graph.types count" }
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
    }

}
