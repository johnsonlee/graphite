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

    @Suppress("CyclomaticComplexMethod") // Five variants retain the same explicit shape constraints.
    private fun validShape(access: DeclaredTypeValidationAccess, id: Int): Boolean {
        fun kind(value: String) = access.textEquals(id, DeclaredTypeTextField.KIND, value)
        fun empty(field: DeclaredTypeTextField) = access.textIsEmpty(id, field)
        fun variance(value: String) = access.textEquals(id, DeclaredTypeTextField.VARIANCE, value)
        return when {
            kind("class") -> !empty(DeclaredTypeTextField.NAME) && access.component(id) == null &&
                empty(DeclaredTypeTextField.VARIANCE)
            kind("primitive") -> setOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "void")
                .any { access.textEquals(id, DeclaredTypeTextField.NAME, it) } && access.owner(id) == null &&
                access.component(id) == null && access.argumentCount(id) == 0 && empty(DeclaredTypeTextField.VARIANCE)
            kind("array") -> access.component(id) != null && access.owner(id) == null &&
                access.argumentCount(id) == 0 && empty(DeclaredTypeTextField.VARIANCE)
            kind("variable") -> !empty(DeclaredTypeTextField.NAME) && !empty(DeclaredTypeTextField.SCOPE) &&
                access.owner(id) == null && access.component(id) == null && access.argumentCount(id) == 0 &&
                empty(DeclaredTypeTextField.VARIANCE)
            kind("wildcard") -> access.owner(id) == null && access.argumentCount(id) == 0 && when {
                variance("extends") || variance("super") -> access.component(id) != null
                variance("unbounded") -> access.component(id) == null
                else -> false
            }
            else -> false
        }
    }

    /** Validate before exposing recursive renderers to persisted references. */
    private fun validateExpressions() {
        fun reference(id: Int) { require(id in types.indices) { "Invalid graph.types type ID $id" } }
        fun parameters(values: List<TypeParameter>) { values.forEach { parameter -> parameter.bounds.forEach(::reference) } }
        validateMemberReferences(fields, ::reference)
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
        val access = types as? DeclaredTypeValidationAccess ?: OrdinaryDeclaredTypeValidationAccess(types)
        types.indices.forEach { id ->
            require(validShape(access, id)) { "Invalid graph.types ${access.text(id, DeclaredTypeTextField.KIND)} expression" }
            access.owner(id)?.let(::reference)
            access.component(id)?.let(::reference)
            repeat(access.argumentCount(id)) { reference(access.argument(id, it)) }
        }
        validateExpressionDag(access)
    }

    private fun validateExpressionDag(access: DeclaredTypeValidationAccess) {
        val state = ByteArray(types.size)
        val depths = IntArray(types.size)
        val expandedNodes = IntArray(types.size)
        val expandedBytes = IntArray(types.size)
        fun visit(id: Int, depth: Int): Int {
            require(depth <= MAX_DEPTH && state[id] != 1.toByte()) { "Cyclic or excessive graph.types nesting" }
            if (state[id] == 2.toByte()) return depths[id]
            state[id] = 1
            var height = 1
            var nodes = 1
            var bytes = DeclaredTypeTextField.entries.fold(0) { total, field ->
                saturatedAdd(total, access.textUtf8Length(id, field), MAX_EXPANDED_BYTES)
            }
            val argumentCount = access.argumentCount(id)
            // Long indexing keeps the two optional slots from overflowing the argument count.
            for (index in 0L until argumentCount.toLong() + 2) {
                val child = expressionChild(access, id, index, argumentCount)
                if (child < 0) continue
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

    /** All present IDs have been range-checked before DFS; -1 here denotes an absent optional edge only. */
    private fun expressionChild(access: DeclaredTypeValidationAccess, id: Int, index: Long, arguments: Int): Int = when {
        index < arguments -> access.argument(id, index.toInt())
        index == arguments.toLong() -> access.owner(id) ?: -1
        else -> access.component(id) ?: -1
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
