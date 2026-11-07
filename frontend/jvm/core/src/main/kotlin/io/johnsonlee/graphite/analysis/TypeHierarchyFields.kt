package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FieldStructure
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeStructure
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes

/**
 * Analyze field assignments to a type within a method context.
 *
 * For example, if we have:
 * ```
 * ApiResponse<User> response = new ApiResponse<>();
 * response.setData(user);
 * response.setMetadata(new RequestMetadata(...));
 * ```
 *
 * We want to find:
 * - data field -> User type
 * - metadata field -> RequestMetadata type
 */
internal fun TypeHierarchyAnalysis.analyzeFieldAssignments(
    type: TypeDescriptor,
    contextMethod: MethodDescriptor,
    depth: Int
): Map<String, FieldStructure> {
    val fields = mutableMapOf<String, FieldStructure>()

    // Strategy 1: Find setter calls on this type within the context method
    // Pattern: obj.setXxx(value) where obj is of `type`
    val setterCalls = findSetterCalls(type, contextMethod)

    setterCalls.forEach { (fieldName, setterCall) ->
        val actualTypes = analyzeSetterArgument(setterCall, contextMethod, depth + 1)
        val declaredType = inferDeclaredTypeFromSetter(setterCall)

        fields[fieldName] =
            FieldStructure(name = fieldName, declaredType = declaredType, actualTypes = actualTypes, isGenericParameter = false)
    }

    // Strategy 2: Find direct field assignments
    // Pattern: obj.field = value
    findDirectFieldAssignments(type, contextMethod).forEach { (fieldName, fieldNode, assignedTypes) ->
        val existing = fields[fieldName]
        if (existing != null) {
            // Merge with setter-based analysis
            fields[fieldName] = existing.copy(actualTypes = existing.actualTypes + assignedTypes)
        } else {
            fields[fieldName] = FieldStructure(name = fieldName, declaredType = fieldNode.descriptor.type, actualTypes = assignedTypes)
        }
    }

    // Strategy 3: Find constructor calls and map arguments to fields
    // Pattern: new Foo(arg1, arg2) where arg1 -> field1, arg2 -> field2
    val constructorFields = findConstructorAssignments(type, contextMethod, depth)
    constructorFields.forEach { (fieldName, fieldStructure) ->
        val existing = fields[fieldName]
        if (existing != null) {
            // Merge with existing analysis
            fields[fieldName] = existing.copy(actualTypes = existing.actualTypes + fieldStructure.actualTypes)
        } else {
            fields[fieldName] = fieldStructure
        }
    }

    // Strategy 4: Use global cross-method field assignment tracking
    // This finds field assignments from ANY method, not just the current context
    addGlobalFieldAssignments(type, fields, depth)

    // Strategy 5: Find getter methods to discover fields and their return types
    // Pattern: getXxx() -> field xxx with return type
    addGetterBasedFields(type, fields, depth)

    // Strategy 6: Discover all declared fields from class definition
    // This ensures we find fields even if they don't have setters, getters, or assignments
    addDeclaredFields(type, fields, depth)

    // If still no setters found, try to find fields from setter calls in other methods
    if (fields.isEmpty()) {
        // Look for any setter calls to this type's methods
        findAllSetterCallsForType(type, contextMethod).forEach { (fieldName, setterCall) ->
            val actualTypes = analyzeSetterArgument(setterCall, contextMethod, depth + 1)
            val declaredType = inferDeclaredTypeFromSetter(setterCall)

            fields[fieldName] =
                FieldStructure(name = fieldName, declaredType = declaredType, actualTypes = actualTypes, isGenericParameter = false)
        }
    }

    return fields
}

/**
 * Add field assignments from global cross-method tracking. This enables discovering field types even when the field is assigned in a
 * different method than where it's returned.
 *
 * Also handles inheritance: includes fields from parent classes using the type hierarchy graph.
 */
internal fun TypeHierarchyAnalysis.addGlobalFieldAssignments(type: TypeDescriptor, fields: MutableMap<String, FieldStructure>, depth: Int) {
    // Collect all class names to check: current type + all supertypes (inherited)
    val classesToCheck = mutableSetOf(type.className)
    collectAllSupertypes(type, classesToCheck)

    // Get all fields of this type AND parent classes from type hierarchy
    val typeFields =
        graph.nodes<FieldNode>().filter { fieldNode -> fieldNode.descriptor.declaringClass.className in classesToCheck }.toList()

    typeFields.forEach { fieldNode ->
        val fieldName = fieldNode.descriptor.name
        val declaringClass = fieldNode.descriptor.declaringClass.className
        val fieldKey = fieldKey(declaringClass, fieldName)

        // Get globally tracked assignments for this field
        val globalTypes = globalFieldAssignments[fieldKey] ?: emptySet()

        if (globalTypes.isNotEmpty()) {
            val actualTypes =
                globalTypes
                    .filter { shouldAnalyzeType(it) }
                    .map { className ->
                        if (depth < config.maxDepth) {
                            buildTypeStructure(
                                TypeDescriptor(className),
                                MethodDescriptor(
                                    declaringClass = type, name = "", parameterTypes = emptyList(), returnType = TypeDescriptor(VOID_TYPE)),
                                depth + 1)
                        } else {
                            TypeStructure.simple(className)
                        }
                    }
                    .toSet()

            val existing = fields[fieldName]
            if (existing != null) {
                // Merge with existing
                fields[fieldName] = existing.copy(actualTypes = existing.actualTypes + actualTypes)
            } else if (actualTypes.isNotEmpty()) {
                fields[fieldName] = FieldStructure(name = fieldName, declaredType = fieldNode.descriptor.type, actualTypes = actualTypes)
            }
        }
    }
}

/** Collect all supertypes (parents, grandparents, etc.) for a type using the type hierarchy graph. */
internal fun TypeHierarchyAnalysis.collectAllSupertypes(type: TypeDescriptor, result: MutableSet<String>) {
    graph.supertypes(type).forEach { supertype ->
        if (supertype.className !in result) {
            result.add(supertype.className)
            collectAllSupertypes(supertype, result)
        }
    }
}

/**
 * Find constructor calls for a type and map arguments to fields.
 *
 * For example:
 * ```
 * new User(userId, "John")  // Maps to User(String id, String name)
 * ```
 *
 * We try to map constructor arguments to fields by:
 * 1. Finding all constructor calls for the type
 * 2. Getting the declared field types from FieldNodes
 * 3. Matching argument types to field types
 */
internal fun TypeHierarchyAnalysis.findConstructorAssignments(
    type: TypeDescriptor,
    contextMethod: MethodDescriptor,
    depth: Int
): Map<String, FieldStructure> {
    val fields = mutableMapOf<String, FieldStructure>()

    // Find constructor calls (<init>) for this type
    val constructorCalls =
        graph
            .nodes<CallSiteNode>()
            .filter { it.caller.signature == contextMethod.signature }
            .filter { it.callee.name == "<init>" }
            .filter { it.callee.declaringClass.className == type.className }
            .toList()

    if (constructorCalls.isEmpty()) return fields

    // Get all fields of this type from the graph
    val typeFields = graph.nodes<FieldNode>().filter { it.descriptor.declaringClass.className == type.className }.toList()

    constructorCalls.forEach { constructorCall ->
        val args = constructorCall.arguments

        // Try to match constructor arguments to fields by type
        args.forEachIndexed { index, argNodeId ->
            val argTypes = mutableSetOf<TypeStructure>()
            traceTypeFromNode(argNodeId, contextMethod, argTypes, mutableSetOf(), depth + 1)

            if (argTypes.isNotEmpty()) {
                // Find a field that could match this argument
                val matchingField =
                    typeFields.find { fieldNode ->
                        val fieldType = fieldNode.descriptor.type.className
                        argTypes.any { argType ->
                            argType.type.className == fieldType ||
                                fieldType == JAVA_LANG_OBJECT ||
                                isCompatibleType(argType.type.className, fieldType)
                        }
                    }

                if (matchingField != null) {
                    val fieldName = matchingField.descriptor.name
                    val declaringClass = matchingField.descriptor.declaringClass.className
                    val existing = fields[fieldName]
                    if (existing != null) {
                        fields[fieldName] = existing.copy(actualTypes = existing.actualTypes + argTypes)
                    } else {
                        fields[fieldName] =
                            FieldStructure(name = fieldName, declaredType = matchingField.descriptor.type, actualTypes = argTypes)
                    }
                }
            }
        }
    }

    return fields
}

/**
 * Find getter methods for a type and add their return types as field information.
 *
 * This discovers fields by looking at getter patterns:
 * - getXxx() -> field xxx
 * - isXxx() -> field xxx (for boolean)
 *
 * Also handles inheritance by looking at supertypes.
 */
internal fun TypeHierarchyAnalysis.addGetterBasedFields(type: TypeDescriptor, fields: MutableMap<String, FieldStructure>, depth: Int) {
    // Collect all class names to check: current type + all supertypes
    val classesToCheck = mutableSetOf(type.className)
    collectAllSupertypes(type, classesToCheck)

    // Find all getter methods from this type and its supertypes
    classesToCheck
        .asSequence()
        .flatMap { className -> graph.methods(MethodPattern(declaringClass = className)) }
        .filter { method ->
            // Match getter patterns: getXxx() or isXxx() with no parameters
            val name = method.name
            method.parameterTypes.isEmpty() &&
                method.returnType.className != VOID_TYPE &&
                ((name.startsWith(GETTER_PREFIX) && name.length > TYPE_GETTER_PREFIX_LENGTH) ||
                    (name.startsWith(BOOLEAN_GETTER_PREFIX) &&
                        name.length > TYPE_BOOLEAN_GETTER_PREFIX_LENGTH &&
                        method.returnType.className in listOf(BOOLEAN_PRIMITIVE, JAVA_LANG_BOOLEAN)))
        }
        .forEach { getter ->
            // Extract field name from getter
            val fieldName =
                if (getter.name.startsWith(GETTER_PREFIX)) {
                    getter.name.removePrefix(GETTER_PREFIX).replaceFirstChar { it.lowercase() }
                } else {
                    getter.name.removePrefix(BOOLEAN_GETTER_PREFIX).replaceFirstChar { it.lowercase() }
                }

            // Skip if we already have this field from setter/field analysis
            if (fieldName in fields) return@forEach

            val returnType = getter.returnType
            // Include fields with analyzable types, primitive/wrapper types, or collection types
            if (shouldAnalyzeType(returnType.className) ||
                isPrimitiveOrWrapper(returnType.className) ||
                isCollectionType(returnType.className)) {
                val actualTypes =
                    if (shouldAnalyzeType(returnType.className) && depth < config.maxDepth) {
                        setOf(buildTypeStructure(returnType, getter, depth + 1))
                    } else {
                        emptySet()
                    }

                fields[fieldName] =
                    FieldStructure(name = fieldName, declaredType = returnType, actualTypes = actualTypes, isGenericParameter = false)
            }
        }
}

/**
 * Discover all declared fields from class definition.
 *
 * This strategy ensures that ALL fields declared in the class are discovered, even if they don't have setters, getters, or assignments in
 * analyzed code. This is important for:
 * - Public fields without getter/setter
 * - Fields only used by frameworks (e.g., Jackson, Lombok)
 * - Fields initialized via reflection or serialization
 */
internal fun TypeHierarchyAnalysis.addDeclaredFields(type: TypeDescriptor, fields: MutableMap<String, FieldStructure>, depth: Int) {
    // Collect all class names to check: current type + all supertypes
    val classesToCheck = mutableSetOf(type.className)
    collectAllSupertypes(type, classesToCheck)

    // Find all FieldNodes declared in these classes
    graph
        .nodes<FieldNode>()
        .filter { it.descriptor.declaringClass.className in classesToCheck }
        .forEach { fieldNode ->
            val fieldName = fieldNode.descriptor.name
            val declaringClass = fieldNode.descriptor.declaringClass.className

            // Skip if this field is already discovered by other strategies
            if (fieldName in fields) return@forEach

            // Skip synthetic or special fields
            if (fieldName.startsWith("\$") || fieldName.startsWith("this\$")) return@forEach

            val fieldType = fieldNode.descriptor.type

            // Skip if field type should not be analyzed
            // But allow collection types (List, Set, Map, etc.) as their element types may be relevant
            if (!shouldTraceFieldType(fieldType)) {
                return@forEach
            }

            // Build actual types if we can analyze the field type
            val actualTypes =
                if (shouldAnalyzeType(fieldType.className) && depth < config.maxDepth) {
                    setOf(
                        buildTypeStructure(
                            fieldType,
                            MethodDescriptor(
                                declaringClass = type, name = "", parameterTypes = emptyList(), returnType = TypeDescriptor(VOID_TYPE)),
                            depth + 1))
                } else {
                    emptySet()
                }

            fields[fieldName] =
                FieldStructure(name = fieldName, declaredType = fieldType, actualTypes = actualTypes, isGenericParameter = false)
        }
}

internal fun TypeHierarchyAnalysis.shouldTraceFieldType(fieldType: TypeDescriptor): Boolean =
    shouldAnalyzeType(fieldType.className) ||
        fieldType.className == JAVA_LANG_OBJECT ||
        isPrimitiveOrWrapper(fieldType.className) ||
        isCollectionType(fieldType.className)

/** Check if a type is a primitive or wrapper type. */
internal fun TypeHierarchyAnalysis.isPrimitiveOrWrapper(className: String): Boolean {
    return className in
        setOf(
            "int",
            "long",
            "short",
            "byte",
            "float",
            "double",
            BOOLEAN_PRIMITIVE,
            "char",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Short",
            "java.lang.Byte",
            "java.lang.Float",
            "java.lang.Double",
            JAVA_LANG_BOOLEAN,
            "java.lang.Character",
            "java.lang.String",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.util.Date",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.ZonedDateTime",
            "java.time.Instant")
}

/**
 * Check if a type is a collection or container type. These types should be included in field discovery even when their package is not in
 * includePackages, because they are containers whose element types may be relevant for analysis.
 */
internal fun TypeHierarchyAnalysis.isCollectionType(className: String): Boolean {
    return className in
        setOf(
            "java.util.List",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.Set",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.Collection",
            "java.util.Queue",
            "java.util.Deque",
            "java.util.Optional")
}

/** Check if two types are compatible (basic subtype check). */
internal fun TypeHierarchyAnalysis.isCompatibleType(actualType: String, declaredType: String): Boolean {
    // Simple compatibility check
    return actualType == declaredType ||
        declaredType == JAVA_LANG_OBJECT ||
        (actualType.startsWith("java.") && declaredType.startsWith("java."))
}
