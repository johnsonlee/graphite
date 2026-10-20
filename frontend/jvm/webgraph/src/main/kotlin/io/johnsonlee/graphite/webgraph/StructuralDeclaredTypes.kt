package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MemberTypeKey
import java.io.DataOutputStream
import java.nio.ByteBuffer

internal object StructuralDeclaredTypeWire {
    const val TYPE_MIN_BYTES = 24
    const val PARAMETER_MIN_BYTES = 16
    const val TEXT_FIELDS = 4
    const val NAME_OFFSET = 4
    const val REFERENCES_OFFSET = 12
    const val ARGUMENTS_OFFSET = 20
    const val TYPE_SCOPE_TARGET_OFFSET = 6
    const val FORMAL_RESERVED_BYTES = 3
    const val CLASS_SCOPE = 1
    const val METHOD_SCOPE = 2
    const val UNRESOLVED_CLASS_SCOPE = 3
    const val UNRESOLVED_METHOD_SCOPE = 4
    const val UNRESOLVED_OFFSET = 2
    const val BYTE_MASK = 255
    const val TRUNCATED = "Truncated graph.types"
}

/** GTY04 and later keep scopes as declaration references; rendered scopes are never retained by a loaded table. */
internal class StructuralDeclaredTypeScopes private constructor(private val scopes: Map<String, Scope>) {
    private data class Scope(val tag: Int, val target: Int)

    fun write(output: DataOutputStream, scope: String, formal: Boolean = false) {
        val value = if (scope.isEmpty()) Scope(0, -1) else scopes.getValue(scope)
        output.writeByte(value.tag)
        if (formal) { output.writeByte(0); output.writeShort(0) }
        else output.writeByte(0)
        if (formal) output.writeInt(value.target)
    }

    fun target(scope: String): Int = if (scope.isEmpty()) -1 else scopes.getValue(scope).target

    companion object {
        fun forTable(table: DeclaredTypeTable): StructuralDeclaredTypeScopes? {
            val needed = HashSet<String>()
            fun need(scope: String) { if (scope.isNotEmpty()) needed.add(scope) }
            table.types.forEach { need(it.scope) }
            table.methods.values.forEach { method -> method.typeParameters.forEach { need(it.scope) } }
            table.classes.values.forEach { type -> type.typeParameters.forEach { need(it.scope) } }
            val scopes = HashMap<String, Scope>()
            var ambiguous = false
            fun addIfNeeded(text: String, tag: Int, target: Int) {
                if (text !in needed) return
                val value = Scope(tag, target)
                val previous = scopes.putIfAbsent(text, value)
                if (previous != null && previous != value) ambiguous = true
            }
            fun add(text: String, tag: Int, target: Int) {
                addIfNeeded(text, tag, target)
                addIfNeeded("unresolved:$text", tag + StructuralDeclaredTypeWire.UNRESOLVED_OFFSET, target)
            }
            if (needed.isNotEmpty()) {
                table.classes.keys.forEachIndexed { index, name -> add("class:$name", StructuralDeclaredTypeWire.CLASS_SCOPE, index) }
                table.methods.keys.forEachIndexed { index, key -> add(methodScope(key), StructuralDeclaredTypeWire.METHOD_SCOPE, index) }
            }
            if (ambiguous || scopes.size != needed.size) return null
            return StructuralDeclaredTypeScopes(scopes)
        }
    }
}

/** Declaration key readers borrow mapped rows. No decoded key or concatenated scope cache is kept. */
internal class StructuralDeclaredTypeContext {
    var erased: ErasedDeclaredTypeContext? = null
    var classCount: Int = 0
    var methodCount: Int = 0
    var classKey: ((Int) -> String)? = null
    var methodKey: ((Int) -> MemberTypeKey)? = null
    var classKeyLength: ((Int) -> Int)? = null
    var methodKeyLength: ((Int) -> Int)? = null

    fun validate(tag: Int, target: Int) {
        require(tag in 0..StructuralDeclaredTypeWire.UNRESOLVED_METHOD_SCOPE && if (tag == 0) target == -1 else target >= 0) {
            "Invalid graph.types scope reference"
        }
        when (tag) {
            StructuralDeclaredTypeWire.CLASS_SCOPE, StructuralDeclaredTypeWire.UNRESOLVED_CLASS_SCOPE ->
                if (classKey != null) require(target < classCount) { "Invalid graph.types class scope reference" }
            StructuralDeclaredTypeWire.METHOD_SCOPE, StructuralDeclaredTypeWire.UNRESOLVED_METHOD_SCOPE ->
                if (methodKey != null) require(target < methodCount) { "Invalid graph.types method scope reference" }
        }
    }

    fun render(tag: Int, target: Int): String {
        validate(tag, target)
        val scope = when (tag) {
            0 -> return ""
            StructuralDeclaredTypeWire.CLASS_SCOPE, StructuralDeclaredTypeWire.UNRESOLVED_CLASS_SCOPE ->
                "class:${checkNotNull(classKey)(target)}"
            else -> methodScope(checkNotNull(methodKey)(target))
        }
        return if (tag >= StructuralDeclaredTypeWire.UNRESOLVED_CLASS_SCOPE) "unresolved:$scope" else scope
    }

    fun scopeLength(bytes: ByteBuffer, offset: Int): Int {
        val tag = bytes.get(offset).toInt() and StructuralDeclaredTypeWire.BYTE_MASK
        val target = bytes.getInt(offset + StructuralDeclaredTypeWire.TYPE_SCOPE_TARGET_OFFSET)
        validate(tag, target)
        if (tag == 0) return 0
        val unresolved = if (tag >= StructuralDeclaredTypeWire.UNRESOLVED_CLASS_SCOPE) "unresolved:".length else 0
        val size = when (tag) {
            StructuralDeclaredTypeWire.CLASS_SCOPE, StructuralDeclaredTypeWire.UNRESOLVED_CLASS_SCOPE ->
                "class:".length.toLong() + checkNotNull(classKeyLength)(target)
            else -> "method:#".length.toLong() + checkNotNull(methodKeyLength)(target)
        }
        return (size + unresolved).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun readFormal(bytes: ByteBuffer): String {
        val tag = bytes.readDeclaredTypeByte()
        repeat(StructuralDeclaredTypeWire.FORMAL_RESERVED_BYTES) { bytes.readDeclaredTypeReserved() }
        require(bytes.remaining() >= Int.SIZE_BYTES) { StructuralDeclaredTypeWire.TRUNCATED }
        return render(tag, bytes.int)
    }

    fun scope(bytes: ByteBuffer, offset: Int, formal: Boolean = false): String {
        val tag = bytes.get(offset).toInt() and StructuralDeclaredTypeWire.BYTE_MASK
        val target = bytes.getInt(offset + if (formal) Int.SIZE_BYTES else StructuralDeclaredTypeWire.TYPE_SCOPE_TARGET_OFFSET)
        return render(tag, target)
    }
}

internal val DECLARED_TYPE_KINDS = listOf("class", "primitive", "array", "variable", "wildcard")
internal val DECLARED_TYPE_VARIANCES = listOf("", "extends", "super", "unbounded")
private fun methodScope(key: MemberTypeKey): String = "method:${key.owner}#${key.name}${key.descriptor}"

internal fun ByteBuffer.readDeclaredTypeByte(): Int {
    require(hasRemaining()) { StructuralDeclaredTypeWire.TRUNCATED }
    return get().toInt() and StructuralDeclaredTypeWire.BYTE_MASK
}

internal fun ByteBuffer.readDeclaredTypeReserved() {
    require(readDeclaredTypeByte() == 0) { "Invalid graph.types reserved byte" }
}
