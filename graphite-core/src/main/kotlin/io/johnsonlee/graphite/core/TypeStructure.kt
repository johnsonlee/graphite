package io.johnsonlee.graphite.core

/**
 * Represents a complete type structure with resolved generic parameters and field types.
 *
 * This is the core output of type hierarchy analysis, enabling understanding of
 * nested type structures like:
 *
 * ```
 * ApiResponse<PageData<User>>
 * ├── data: PageData<User>
 * │   ├── items: List<User>
 * │   │   └── [element]: User
 * │   └── extra: Object → PageMetadata
 * └── metadata: Object → RequestMetadata
 * ```
 *
 * @property type The base type descriptor
 * @property typeArguments Resolved generic type arguments (e.g., T -> User)
 * @property fields Field structures with their actual assigned types
 */
data class TypeStructure(
    val type: TypeDescriptor,
    val typeArguments: Map<String, TypeStructure> = emptyMap(),
    val fields: Map<String, FieldStructure> = emptyMap()
) {
    /**
     * Get the simple class name without package
     */
    val simpleName: String get() = type.simpleName

    /**
     * Get the fully qualified class name
     */
    val className: String get() = type.className

    /**
     * Check if this type has any generic type arguments
     */
    val isGeneric: Boolean get() = typeArguments.isNotEmpty()

    /**
     * Check if any fields have Object as declared type (potential polymorphism)
     */
    val hasObjectFields: Boolean get() = fields.values.any {
        it.declaredType.className == "java.lang.Object"
    }

    /**
     * Get a human-readable representation of the type structure
     */
    fun toTreeString(indent: String = ""): String {
        val sb = StringBuilder()
        sb.append(formatTypeName())
        sb.appendLine()

        fields.entries.sortedBy { it.key }.forEach { (name, field) ->
            sb.append(indent)
            sb.append("├── $name: ")
            sb.append(field.toTreeString("$indent│   "))
        }

        return sb.toString()
    }

    /**
     * Format the type name with generic arguments
     * e.g., ApiResponse<PageData<User>>
     */
    fun formatTypeName(): String {
        // First check if TypeStructure has named type arguments
        if (typeArguments.isNotEmpty()) {
            val args = typeArguments.values.joinToString(", ") { it.formatTypeName() }
            return "${type.simpleName}<$args>"
        }

        // Otherwise check if the underlying TypeDescriptor has type arguments
        if (type.typeArguments.isNotEmpty()) {
            val args = type.typeArguments.joinToString(", ") { it.simpleName }
            return "${type.simpleName}<$args>"
        }

        return type.simpleName
    }

    companion object {
        /**
         * Create a simple type structure without generics or fields
         */
        fun simple(className: String): TypeStructure {
            return TypeStructure(TypeDescriptor(className))
        }

        /**
         * Create a type structure from a TypeDescriptor
         */
        fun from(type: TypeDescriptor): TypeStructure {
            return TypeStructure(type)
        }
    }
}

/**
 * Represents a field's type information including both declared and actual types.
 *
 * For fields declared as Object or generic types, the actualTypes set contains
 * all concrete types that have been assigned to this field.
 *
 * @property name The field name
 * @property declaredType The declared type of the field
 * @property actualTypes Set of actual types assigned to this field (may differ from declared type)
 * @property isGenericParameter True if this field's declared type is a generic parameter (e.g., T)
 * @property jsonName The JSON property name from @JsonProperty annotation (null if not specified)
 * @property isJsonIgnored True if the field is marked with @JsonIgnore
 */
data class FieldStructure(
    val name: String,
    val declaredType: TypeDescriptor,
    val actualTypes: Set<TypeStructure> = emptySet(),
    val isGenericParameter: Boolean = false,
    val jsonName: String? = null,
    val isJsonIgnored: Boolean = false
) {
    /**
     * Get the effective JSON name (from @JsonProperty or field name)
     */
    val effectiveJsonName: String get() = jsonName ?: name
    /**
     * Check if the actual types differ from the declared type
     */
    val hasPolymorphicAssignment: Boolean get() =
        actualTypes.isNotEmpty() && actualTypes.any { it.type != declaredType }

    /**
     * Check if the declared type is Object
     */
    val isDeclaredAsObject: Boolean get() =
        declaredType.className == "java.lang.Object"

    /**
     * Format the declared type with generic arguments if available
     */
    private fun formatDeclaredType(): String {
        return if (declaredType.typeArguments.isEmpty()) {
            declaredType.simpleName
        } else {
            val args = declaredType.typeArguments.joinToString(", ") { it.simpleName }
            "${declaredType.simpleName}<$args>"
        }
    }

    /**
     * Get a human-readable representation
     */
    fun toTreeString(indent: String = ""): String {
        val sb = StringBuilder()
        val declaredTypeName = formatDeclaredType()

        if (actualTypes.isEmpty()) {
            sb.appendLine(declaredTypeName)
        } else if (actualTypes.size == 1) {
            val actual = actualTypes.first()
            if (actual.type == declaredType) {
                sb.appendLine(actual.formatTypeName())
            } else {
                sb.append(declaredTypeName)
                sb.append(" → ")
                sb.appendLine(actual.formatTypeName())
            }
            // Print nested fields
            actual.fields.entries.sortedBy { it.key }.forEach { (name, field) ->
                sb.append(indent)
                sb.append("├── $name: ")
                sb.append(field.toTreeString("$indent│   "))
            }
        } else {
            // Multiple possible types
            sb.append(declaredTypeName)
            sb.append(" → [")
            sb.append(actualTypes.joinToString(" | ") { it.formatTypeName() })
            sb.appendLine("]")
        }

        return sb.toString()
    }
}

/**
 * Result of type hierarchy analysis for a method.
 *
 * @property method The analyzed method
 * @property returnStructures All possible return type structures
 * @property callSiteStructures Type structures at specific call sites
 */
data class TypeHierarchyResult(
    val method: MethodDescriptor,
    val returnStructures: Set<TypeStructure>,
    val callSiteStructures: Map<CallSiteNode, TypeStructure> = emptyMap()
) {
    /**
     * Get a consolidated view of all types that can be returned
     */
    fun allReturnTypes(): Set<TypeDescriptor> {
        return returnStructures.map { it.type }.toSet()
    }

    /**
     * Get all field types used in the return structures (recursively)
     */
    fun allFieldTypes(): Set<TypeDescriptor> {
        val types = mutableSetOf<TypeDescriptor>()
        fun collect(structure: TypeStructure) {
            structure.fields.values.forEach { field ->
                types.add(field.declaredType)
                field.actualTypes.forEach { actual ->
                    types.add(actual.type)
                    collect(actual)
                }
            }
        }
        returnStructures.forEach { collect(it) }
        return types
    }

    /**
     * Print a human-readable tree representation
     */
    fun toTreeString(): String {
        val sb = StringBuilder()
        sb.appendLine("Method: ${method.name}()")
        sb.appendLine("Declared return type: ${method.returnType.className}")
        sb.appendLine()
        sb.appendLine("Actual return type structure(s):")
        returnStructures.forEach { structure ->
            sb.appendLine(structure.toTreeString("  "))
        }
        return sb.toString()
    }
}

/**
 * Configuration for type hierarchy analysis.
 */
data class TypeHierarchyConfig(
    /**
     * Maximum depth to traverse nested types
     */
    val maxDepth: Int = 10,

    /**
     * Whether to analyze Object fields to find actual types
     */
    val resolveObjectFields: Boolean = true,

    /**
     * Whether to analyze generic type parameters
     */
    val resolveGenerics: Boolean = true,

    /**
     * Whether to follow method calls to analyze returned types
     */
    val interProcedural: Boolean = true,

    /**
     * Package prefixes to include in analysis (empty = all)
     */
    val includePackages: List<String> = emptyList(),

    /**
     * Package prefixes to exclude from analysis
     */
    val excludePackages: List<String> = listOf("java.", "javax.", "kotlin.", "sun.", "com.sun.")
)
