package io.johnsonlee.graphite.webgraph

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
        val ids = strings.withIndex().associate { (id, value) -> value to id }
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { out ->
            fun inline(value: String) { val bytes = value.toByteArray(); out.writeInt(bytes.size); out.write(bytes) }
            fun text(value: String) { if (version == 1) inline(value) else out.writeInt(ids.getValue(value)) }
            fun refs(values: List<Int>) { out.writeInt(values.size); values.forEach(out::writeInt) }
            fun writeKey(value: MemberTypeKey) { text(value.owner); text(value.name); text(value.descriptor) }
            fun params(values: List<TypeParameter>) {
                out.writeInt(values.size)
                values.forEach { text(it.name); text(it.scope); refs(it.bounds) }
            }
            out.writeInt(0x47545900 or version)
            out.write(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve("graph.metadata"))))
            if (version == 2) { out.writeInt(strings.size); strings.forEach(::inline) }
            out.writeInt(table.types.size)
            table.types.forEach { type ->
                text(type.kind); text(type.name); text(type.scope)
                out.writeInt(type.owner ?: -1); out.writeInt(type.component ?: -1)
                text(type.variance); refs(type.arguments)
            }
            out.writeInt(table.fields.size)
            table.fields.forEach { (key, value) -> writeKey(key); out.writeInt(value) }
            out.writeInt(table.methods.size)
            table.methods.forEach { (key, value) ->
                writeKey(key); refs(value.parameterTypes); out.writeInt(value.returnType); params(value.typeParameters)
            }
            out.writeInt(table.classes.size)
            table.classes.forEach { (name, value) ->
                text(name); params(value.typeParameters); out.writeInt(value.superType ?: -1); refs(value.interfaces)
            }
        }
        Files.write(dir.resolve(DeclaredTypeStore.FILE_NAME), output.toByteArray())
        rebind(dir)
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
