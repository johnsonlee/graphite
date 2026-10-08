package io.johnsonlee.graphite.graph

/** A structurally interned declared type. References are table-local zero-based IDs. */
data class DeclaredType(
    val kind: String,
    val name: String = "",
    val scope: String = "",
    val owner: Int? = null,
    val component: Int? = null,
    val variance: String = "",
    val arguments: List<Int> = emptyList()
)

/** JVM descriptors keep overloads, covariant bridge methods and same-name fields distinct. */
data class MemberTypeKey(val owner: String, val name: String, val descriptor: String)
data class TypeParameter(val name: String, val scope: String, val bounds: List<Int>)
data class MethodTypes(
    val parameterTypes: List<Int>,
    val returnType: Int,
    val typeParameters: List<TypeParameter> = emptyList()
)
data class ClassTypes(val typeParameters: List<TypeParameter>, val superType: Int?, val interfaces: List<Int>)

/**
 * Declaration metadata is independent of erased graph identities. Recursive bounds live on
 * [TypeParameter], so type expressions themselves form an acyclic graph.
 */
data class DeclaredTypeTable(
    val types: List<DeclaredType>,
    val fields: Map<MemberTypeKey, Int>,
    val methods: Map<MemberTypeKey, MethodTypes>,
    val classes: Map<String, ClassTypes>
) {
    private val validation by lazy { validateExpressions() }

    /** Validate shape, references, nesting and maximum recursive projection expansion once. */
    fun validate() { validation }

    fun render(id: Int): String {
        validate()
        return renderType(id)
    }

    private fun renderType(id: Int): String {
        val type = types[id]
        return when (type.kind) {
            "array" -> renderType(requireNotNull(type.component)) + "[]"
            "wildcard" -> when (type.variance) {
                "unbounded" -> "?"
                else -> "? ${type.variance} ${renderType(requireNotNull(type.component))}"
            }
            "class" -> {
                val name = type.owner?.let { renderType(it) + "." + type.name.removePrefix(types[it].name + "\$") } ?: type.name
                name + if (type.arguments.isEmpty()) "" else type.arguments.joinToString(", ", "<", ">", transform = ::renderType)
            }
            else -> type.name
        }
    }

    /** Structured query projection; IDs are an implementation detail and never cross graphs. */
    fun info(id: Int): Map<String, Any?> {
        validate()
        return typeInfo(id)
    }

    private fun typeInfo(id: Int): Map<String, Any?> {
        val type = types[id]
        return buildMap {
            put("kind", type.kind)
            if (type.name.isNotEmpty()) put("name", type.name)
            if (type.scope.isNotEmpty()) put("scope", type.scope)
            type.owner?.let { put("owner", typeInfo(it)) }
            type.component?.let { put("component", typeInfo(it)) }
            if (type.variance.isNotEmpty()) put("variance", type.variance)
            put("arguments", type.arguments.map(::typeInfo))
        }
    }

    @Suppress("CyclomaticComplexMethod") // Five wire variants, each with explicit shape constraints.
    private fun validShape(type: DeclaredType): Boolean = when (type.kind) {
        "class" -> type.name.isNotEmpty() && type.component == null && type.variance.isEmpty()
        "primitive" -> type.name in setOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "void") &&
            type.owner == null && type.component == null && type.arguments.isEmpty() && type.variance.isEmpty()
        "array" -> type.component != null && type.owner == null && type.arguments.isEmpty() && type.variance.isEmpty()
        "variable" -> type.name.isNotEmpty() && type.scope.isNotEmpty() && type.owner == null &&
            type.component == null && type.arguments.isEmpty() && type.variance.isEmpty()
        "wildcard" -> type.owner == null && type.arguments.isEmpty() && when (type.variance) {
            "extends", "super" -> type.component != null
            "unbounded" -> type.component == null
            else -> false
        }
        else -> false
    }

    /** Validate before exposing recursive renderers to persisted references. */
    private fun validateExpressions() {
        fun reference(id: Int) { require(id in types.indices) { "Invalid graph.types type ID $id" } }
        fun parameters(values: List<TypeParameter>) { values.forEach { parameter -> parameter.bounds.forEach(::reference) } }
        fields.values.forEach(::reference)
        validateMemberReferences(methods) { method ->
            method.parameterTypes.forEach(::reference)
            reference(method.returnType)
            parameters(method.typeParameters)
        }
        validateMemberReferences(classes) { type ->
            type.superType?.let(::reference)
            type.interfaces.forEach(::reference)
            parameters(type.typeParameters)
        }
        types.forEach { type ->
            require(validShape(type)) { "Invalid graph.types ${type.kind} expression" }
            type.owner?.let(::reference)
            type.component?.let(::reference)
            type.arguments.forEach(::reference)
        }
        val state = ByteArray(types.size)
        val depths = IntArray(types.size)
        val expandedNodes = IntArray(types.size)
        val expandedBytes = IntArray(types.size)
        fun visit(id: Int, depth: Int): Int {
            require(depth <= MAX_DEPTH && state[id] != 1.toByte()) { "Cyclic or excessive graph.types nesting" }
            if (state[id] == 2.toByte()) return depths[id]
            state[id] = 1
            val type = types[id]
            var height = 1
            var nodes = 1
            var bytes = listOf(type.kind, type.name, type.scope, type.variance)
                .fold(0) { total, value -> saturatedAdd(total, value.toByteArray(Charsets.UTF_8).size, MAX_EXPANDED_BYTES) }
            for (child in type.arguments + listOfNotNull(type.owner, type.component)) {
                height = maxOf(height, 1 + visit(child, depth + 1))
                nodes = saturatedAdd(nodes, expandedNodes[child], MAX_EXPANDED_NODES)
                bytes = saturatedAdd(bytes, expandedBytes[child], MAX_EXPANDED_BYTES)
            }
            require(height <= MAX_DEPTH) { "Excessive graph.types nesting" }
            require(nodes <= MAX_EXPANDED_NODES && bytes <= MAX_EXPANDED_BYTES) { "Excessive graph.types projection expansion" }
            state[id] = 2
            expandedNodes[id] = nodes
            expandedBytes[id] = bytes
            depths[id] = height
            return height
        }
        types.indices.forEach { visit(it, 1) }
    }

    private fun <V> validateMemberReferences(values: Map<*, V>, validateValue: (V) -> Unit) {
        if (values is DeclaredTypeReferences) values.validateTypeReferences(types.size)
        else values.values.forEach(validateValue)
    }

    private fun saturatedAdd(left: Int, right: Int, limit: Int): Int =
        (left.toLong() + right).coerceAtMost(limit.toLong() + 1).toInt()

    companion object {
        const val MAX_DEPTH = 256
        private const val MAX_EXPANDED_NODES = 100_000
        private const val MAX_EXPANDED_BYTES = 1_000_000
        val EMPTY = DeclaredTypeTable(emptyList(), emptyMap(), emptyMap(), emptyMap())
    }
}
