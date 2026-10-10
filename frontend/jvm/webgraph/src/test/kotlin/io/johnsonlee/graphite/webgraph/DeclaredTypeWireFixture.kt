package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.TypeParameter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Independent format encoder for legacy fixtures and malformed tables that production save must reject. */
internal object DeclaredTypeWireFixture {
    fun write(dir: Path, table: DeclaredTypeTable, version: Int, unused: List<String> = emptyList()) {
        val strings = dictionary(table, unused)
        val shared = if (version >= 3) StringTable.build(strings, dir, true) else null
        val ids = strings.withIndex().associate { (id, value) -> value to (shared?.findId(value) ?: id) }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out -> Encoder(out, table, version, ids).write(dir, strings) }
        Files.write(dir.resolve(DeclaredTypeStore.FILE_NAME), output.toByteArray())
        rebind(dir)
    }

    /** Deliberately independent of the production codec and its tag/offset constants. */
    private class Encoder(
        val out: DataOutputStream, val table: DeclaredTypeTable, val version: Int, val ids: Map<String, Int>
    ) {
        fun inline(value: String) { val bytes = value.toByteArray(); out.writeInt(bytes.size); out.write(bytes) }
        fun text(value: String) { if (version == 1) inline(value) else out.writeInt(ids.getValue(value)) }
        fun refs(values: List<Int>) { out.writeInt(values.size); values.forEach(out::writeInt) }
        private val descriptors = table.methods.keys.map { it.descriptor }.distinct()

        private fun rawDescriptor(id: Int): String {
            val row = table.types[id]
            return when (row.kind) {
                "class" -> { require(row.arguments.isEmpty() && row.owner == null); "L${row.name.replace('.', '/')};" }
                "array" -> "[" + rawDescriptor(requireNotNull(row.component))
                "primitive" -> mapOf("void" to "V", "boolean" to "Z", "byte" to "B", "char" to "C", "short" to "S",
                    "int" to "I", "long" to "J", "float" to "F", "double" to "D").getValue(row.name)
                else -> error("Not a fixture raw type")
            }
        }

        private fun rawId(descriptor: String): Int = table.types.indices.first { id ->
            runCatching { rawDescriptor(id) }.getOrNull() == descriptor
        }

        fun writeKey(value: MemberTypeKey, method: Boolean = false) {
            text(value.owner); text(value.name)
            if (version < 5) text(value.descriptor)
            else out.writeInt(if (method) descriptors.indexOf(value.descriptor) else rawId(value.descriptor))
        }

        private fun signatures() {
            out.writeInt(descriptors.size)
            descriptors.forEach { descriptor ->
                val parameters = ArrayList<Int>()
                var at = 1
                while (descriptor[at] != ')') {
                    val start = at
                    while (descriptor[at] == '[') at++
                    at = if (descriptor[at] == 'L') descriptor.indexOf(';', at) + 1 else at + 1
                    parameters.add(rawId(descriptor.substring(start, at)))
                }
                refs(parameters)
                out.writeInt(rawId(descriptor.substring(at + 1)))
            }
        }

        fun scope(value: String): Pair<Int, Int> {
            if (value.isEmpty()) return 0 to -1
            val unresolved = value.startsWith("unresolved:")
            val name = value.removePrefix("unresolved:")
            val owner = table.classes.keys.indexOfFirst { "class:$it" == name }
            return if (owner >= 0) (if (unresolved) 3 else 1) to owner else {
                val method = table.methods.keys.indexOfFirst { "method:${it.owner}#${it.name}${it.descriptor}" == name }
                require(method >= 0) { "Fixture scope has no declaration: $value" }
                (if (unresolved) 4 else 2) to method
            }
        }

        fun params(values: List<TypeParameter>) {
            out.writeInt(values.size)
            values.forEach {
                text(it.name)
                if (version < 4) text(it.scope) else {
                    val (tag, target) = scope(it.scope)
                    out.writeByte(tag); out.writeByte(0); out.writeShort(0); out.writeInt(target)
                }
                refs(it.bounds)
            }
        }

        fun type(type: DeclaredType) {
            if (version < 4) { text(type.kind); text(type.name); text(type.scope) } else {
                out.writeByte(listOf("class", "primitive", "array", "variable", "wildcard").indexOf(type.kind))
                out.writeByte(listOf("", "extends", "super", "unbounded").indexOf(type.variance))
                val (tag, target) = scope(type.scope)
                out.writeByte(tag); out.writeByte(0)
                out.writeInt(if (type.name.isEmpty()) -1 else ids.getValue(type.name)); out.writeInt(target)
            }
            out.writeInt(type.owner ?: -1); out.writeInt(type.component ?: -1)
            if (version < 4) text(type.variance)
            refs(type.arguments)
        }

        fun write(dir: Path, strings: Set<String>) {
            out.writeInt(0x47545900 or version)
            out.write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("graph.metadata"))))
            if (version == 2) { out.writeInt(strings.size); strings.forEach(::inline) }
            if (version >= 3) out.write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("graph.strings"))))
            out.writeInt(table.types.size)
            table.types.forEach(::type)
            if (version >= 5) signatures()
            out.writeInt(table.fields.size)
            table.fields.forEach { (key, value) -> writeKey(key); out.writeInt(value) }
            out.writeInt(table.methods.size)
            table.methods.forEach { (key, value) ->
                writeKey(key, method = true); refs(value.parameterTypes); out.writeInt(value.returnType); params(value.typeParameters)
            }
            out.writeInt(table.classes.size)
            table.classes.forEach { (name, value) ->
                text(name); params(value.typeParameters); out.writeInt(value.superType ?: -1); refs(value.interfaces)
            }
        }
    }

    private fun dictionary(table: DeclaredTypeTable, unused: List<String>): Set<String> {
        val strings = linkedSetOf<String>()
        fun parameters(values: List<TypeParameter>) { values.forEach { strings.add(it.name); strings.add(it.scope) } }
        table.types.forEach { strings.addAll(listOf(it.kind, it.name, it.scope, it.variance)) }
        table.fields.keys.forEach { strings.addAll(listOf(it.owner, it.name, it.descriptor)) }
        table.methods.forEach { (key, value) ->
            strings.addAll(listOf(key.owner, key.name, key.descriptor)); parameters(value.typeParameters)
        }
        table.classes.forEach { (name, value) -> strings.add(name); parameters(value.typeParameters) }
        strings.addAll(unused)
        return strings
    }

    fun dictionaryEnd(bytes: ByteArray): Int {
        val input = ByteBuffer.wrap(bytes).apply { position(36) }
        repeat(input.int) { val length = input.int; input.position(input.position() + length) }
        return input.position()
    }

    fun rebind(dir: Path) {
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve(DeclaredTypeStore.FILE_NAME)))
        val path = dir.resolve("forward.properties")
        val previous = if (Files.exists(path)) Files.readString(path) else ""
        val preserved = previous.lineSequence().filterNot { it.startsWith("${DeclaredTypeStore.BINDING_KEY}=") }.joinToString("\n")
        Files.writeString(path, preserved.trimEnd('\n') + "\n${DeclaredTypeStore.BINDING_KEY}=${HexFormat.of().formatHex(digest)}\n")
    }
}
