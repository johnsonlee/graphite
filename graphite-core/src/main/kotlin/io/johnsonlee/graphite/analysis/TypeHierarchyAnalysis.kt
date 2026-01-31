package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes

/**
 * Analyzes type hierarchies to build complete type structure information.
 *
 * This analysis discovers:
 * 1. Actual types returned by methods (not just declared return types)
 * 2. Actual types assigned to Object fields
 * 3. Actual types used for generic type parameters
 * 4. Nested type structures recursively
 *
 * Example output for a method returning ApiResponse<PageData<User>>:
 * ```
 * ApiResponse<PageData<User>>
 * ├── data: PageData<User>
 * │   ├── items: List<User>
 * │   └── extra: Object → PageMetadata
 * └── metadata: Object → RequestMetadata
 * ```
 */
class TypeHierarchyAnalysis(
    private val graph: Graph,
    private val config: TypeHierarchyConfig = TypeHierarchyConfig()
) {
    // Cache for analyzed types to avoid recomputation
    private val typeCache = mutableMapOf<String, TypeStructure>()

    // Track methods being analyzed to prevent infinite recursion
    private val analyzingMethods = mutableSetOf<String>()

    // Global field assignment cache: fieldKey -> set of assigned type class names
    // Key format: "declaringClass#fieldName"
    private val globalFieldAssignments: Map<String, Set<String>> by lazy {
        buildGlobalFieldAssignments()
    }

    /**
     * Build a global map of all field assignments across all methods.
     * This enables cross-method field tracking.
     */
    private fun buildGlobalFieldAssignments(): Map<String, Set<String>> {
        val assignments = mutableMapOf<String, MutableSet<String>>()

        // Find all setter calls across all methods
        graph.nodes<CallSiteNode>()
            .filter { it.callee.name.startsWith("set") && it.callee.parameterTypes.size == 1 }
            .forEach { callSite ->
                val declaringClass = callSite.callee.declaringClass.className
                val fieldName = callSite.callee.name.removePrefix("set").replaceFirstChar { it.lowercase() }
                val fieldKey = "$declaringClass#$fieldName"

                if (callSite.arguments.isNotEmpty()) {
                    val argNodeId = callSite.arguments[0]
                    val argTypes = findTypesForNode(argNodeId)
                    assignments.getOrPut(fieldKey) { mutableSetOf() }.addAll(argTypes)
                }
            }

        // Find all direct field assignments
        graph.nodes<FieldNode>().forEach { fieldNode ->
            val fieldKey = "${fieldNode.descriptor.declaringClass.className}#${fieldNode.descriptor.name}"

            graph.incoming(fieldNode.id, DataFlowEdge::class.java).forEach { edge ->
                val sourceNode = graph.node(edge.from)
                if (sourceNode is LocalVariable) {
                    val typeName = sourceNode.type.className
                    if (typeName != "java.lang.Object" && typeName != "unknown") {
                        assignments.getOrPut(fieldKey) { mutableSetOf() }.add(typeName)
                    }
                }
            }
        }

        return assignments
    }

    /**
     * Find types for a node by tracing backward through dataflow.
     */
    private fun findTypesForNode(nodeId: NodeId): Set<String> {
        val types = mutableSetOf<String>()
        val visited = mutableSetOf<NodeId>()
        findTypesRecursive(nodeId, types, visited, 0)
        return types
    }

    private fun findTypesRecursive(nodeId: NodeId, types: MutableSet<String>, visited: MutableSet<NodeId>, depth: Int) {
        if (nodeId in visited || depth > 5) return
        visited.add(nodeId)

        val node = graph.node(nodeId)
        when (node) {
            is LocalVariable -> {
                val typeName = node.type.className
                if (typeName != "java.lang.Object" && typeName != "unknown") {
                    types.add(typeName)
                } else {
                    // Continue tracing
                    graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
                        findTypesRecursive(edge.from, types, visited, depth + 1)
                    }
                }
            }
            is CallSiteNode -> {
                val returnType = node.callee.returnType.className
                if (returnType != "java.lang.Object" && returnType != "void") {
                    types.add(returnType)
                }
            }
            else -> {
                graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
                    findTypesRecursive(edge.from, types, visited, depth + 1)
                }
            }
        }
    }

    /**
     * Analyze the return type hierarchy for methods matching the pattern.
     *
     * @param pattern Method pattern to match
     * @return List of type hierarchy results for each matching method
     */
    fun analyzeReturnTypes(pattern: MethodPattern): List<TypeHierarchyResult> {
        return graph.methods(pattern).map { method ->
            analyzeMethod(method)
        }.toList()
    }

    /**
     * Analyze a specific method's return type hierarchy.
     */
    fun analyzeMethod(method: MethodDescriptor): TypeHierarchyResult {
        val methodSignature = method.signature

        // Prevent infinite recursion for recursive methods
        if (methodSignature in analyzingMethods) {
            return TypeHierarchyResult(
                method = method,
                returnStructures = setOf(TypeStructure.from(method.returnType))
            )
        }

        analyzingMethods.add(methodSignature)

        try {
            // Find all return statements in this method
            val returnNodes = graph.nodes<ReturnNode>()
                .filter { it.method.signature == methodSignature }
                .toList()

            // For each return, find the actual type being returned
            val returnStructures = mutableSetOf<TypeStructure>()
            val callSiteStructures = mutableMapOf<CallSiteNode, TypeStructure>()

            returnNodes.forEach { returnNode ->
                val structures = analyzeReturnNode(returnNode, method, 0)
                returnStructures.addAll(structures)
            }

            return TypeHierarchyResult(
                method = method,
                returnStructures = returnStructures,
                callSiteStructures = callSiteStructures
            )
        } finally {
            analyzingMethods.remove(methodSignature)
        }
    }

    /**
     * Analyze a return node to find the actual type structure.
     */
    private fun analyzeReturnNode(
        returnNode: ReturnNode,
        method: MethodDescriptor,
        depth: Int
    ): Set<TypeStructure> {
        if (depth > config.maxDepth) {
            return emptySet()
        }

        val structures = mutableSetOf<TypeStructure>()
        val visited = mutableSetOf<NodeId>()

        // Trace backward from return to find actual types
        traceTypeFromNode(returnNode.id, method, structures, visited, depth)

        return structures
    }

    /**
     * Trace backward through dataflow to find actual types.
     */
    private fun traceTypeFromNode(
        nodeId: NodeId,
        contextMethod: MethodDescriptor,
        structures: MutableSet<TypeStructure>,
        visited: MutableSet<NodeId>,
        depth: Int
    ) {
        if (nodeId in visited || depth > config.maxDepth) return
        visited.add(nodeId)

        // First, check the node itself
        val node = graph.node(nodeId)
        when (node) {
            is LocalVariable -> {
                val typeName = node.type.className
                if (shouldAnalyzeType(typeName)) {
                    // Build type structure for this local variable
                    val structure = buildTypeStructure(node.type, contextMethod, depth + 1)
                    structures.add(structure)
                    return // Found a concrete type, no need to trace further
                }
            }
            is CallSiteNode -> {
                val returnType = node.callee.returnType
                if (shouldAnalyzeType(returnType.className) && returnType.className != "java.lang.Object") {
                    val structure = buildTypeStructure(returnType, contextMethod, depth + 1)
                    structures.add(structure)
                    return
                }
            }
            else -> {}
        }

        // Then trace through incoming edges
        graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
            val sourceNode = graph.node(edge.from)
            when (sourceNode) {
                is LocalVariable -> {
                    val typeName = sourceNode.type.className
                    if (shouldAnalyzeType(typeName)) {
                        // Build type structure for this local variable
                        val structure = buildTypeStructure(sourceNode.type, contextMethod, depth + 1)
                        structures.add(structure)
                    } else if (typeName == "java.lang.Object" || typeName == "unknown") {
                        // Continue tracing back
                        traceTypeFromNode(edge.from, contextMethod, structures, visited, depth + 1)
                    }
                }
                is CallSiteNode -> {
                    val returnType = sourceNode.callee.returnType
                    if (shouldAnalyzeType(returnType.className) && returnType.className != "java.lang.Object") {
                        // Build structure from the return type
                        val structure = buildTypeStructure(returnType, contextMethod, depth + 1)
                        structures.add(structure)
                    } else if (config.interProcedural && sourceNode.callee.signature !in analyzingMethods) {
                        // Trace into the called method
                        val calleeResults = analyzeMethod(sourceNode.callee)
                        structures.addAll(calleeResults.returnStructures)
                    }
                }
                is FieldNode -> {
                    val fieldType = sourceNode.descriptor.type
                    val declaringClass = sourceNode.descriptor.declaringClass.className
                    val fieldName = sourceNode.descriptor.name
                    val fieldKey = "$declaringClass#$fieldName"

                    // Use global field assignments to find actual types
                    val actualTypes = globalFieldAssignments[fieldKey]
                    if (!actualTypes.isNullOrEmpty()) {
                        actualTypes
                            .filter { shouldAnalyzeType(it) }
                            .forEach { typeName ->
                                val structure = buildTypeStructure(TypeDescriptor(typeName), contextMethod, depth + 1)
                                structures.add(structure)
                            }
                    } else if (shouldAnalyzeType(fieldType.className)) {
                        // Fall back to declared type
                        val structure = buildTypeStructure(fieldType, contextMethod, depth + 1)
                        structures.add(structure)
                    }
                }
                else -> {
                    // Continue tracing for other node types
                    traceTypeFromNode(edge.from, contextMethod, structures, visited, depth + 1)
                }
            }
        }
    }

    /**
     * Build a complete TypeStructure for a given type.
     */
    private fun buildTypeStructure(
        type: TypeDescriptor,
        contextMethod: MethodDescriptor,
        depth: Int
    ): TypeStructure {
        if (depth > config.maxDepth) {
            return TypeStructure.simple(type.className)
        }

        // Check cache
        val cacheKey = "${type.className}:${contextMethod.signature}"
        typeCache[cacheKey]?.let { return it }

        // Skip analysis for excluded packages
        if (!shouldAnalyzeType(type.className)) {
            return TypeStructure.simple(type.className)
        }

        // Find fields of this type and their assignments in the context method
        val fields = analyzeFieldAssignments(type, contextMethod, depth)

        // Find generic type arguments from constructor calls
        val typeArguments = analyzeGenericTypeArguments(type, contextMethod, depth)

        val structure = TypeStructure(
            type = type,
            typeArguments = typeArguments,
            fields = fields
        )

        typeCache[cacheKey] = structure
        return structure
    }

    /**
     * Analyze generic type arguments by looking at constructor calls.
     *
     * For a generic class like L1<T> with constructor L1(T value),
     * when we see `new L1<>(l2)` where l2 is of type L2,
     * we know T -> L2.
     */
    private fun analyzeGenericTypeArguments(
        type: TypeDescriptor,
        contextMethod: MethodDescriptor,
        depth: Int
    ): Map<String, TypeStructure> {
        val typeArgs = mutableMapOf<String, TypeStructure>()

        if (depth > config.maxDepth) {
            return typeArgs
        }

        // Find constructor calls (<init>) for this type in the context method
        val constructorCalls = graph.nodes<CallSiteNode>()
            .filter { it.caller.signature == contextMethod.signature }
            .filter { it.callee.name == "<init>" }
            .filter { it.callee.declaringClass.className == type.className }
            .toList()

        if (constructorCalls.isEmpty()) return typeArgs

        // For each constructor call, analyze the arguments to find type parameters
        constructorCalls.forEach { constructorCall ->
            val args = constructorCall.arguments

            args.forEachIndexed { index, argNodeId ->
                // Find the type of the argument directly without deep tracing
                val argType = findArgumentType(argNodeId)

                // Use "T", "T0", "T1", etc. as generic parameter names
                val paramName = if (index == 0) "T" else "T$index"

                // Only include non-primitive, non-Object types
                if (argType != null && shouldAnalyzeType(argType.className)) {
                    // Recursively build structure for the type argument
                    // Only increment depth by 1 for each level of nesting
                    val nestedStructure = buildTypeStructure(
                        argType,
                        contextMethod,
                        depth + 1
                    )
                    typeArgs[paramName] = nestedStructure
                }
            }
        }

        return typeArgs
    }

    /**
     * Find the type of an argument node by looking at its LocalVariable type.
     */
    private fun findArgumentType(nodeId: NodeId): TypeDescriptor? {
        val node = graph.node(nodeId)

        when (node) {
            is LocalVariable -> {
                val typeName = node.type.className
                if (typeName != "java.lang.Object" && typeName != "unknown") {
                    return node.type
                }
            }
            is CallSiteNode -> {
                val returnType = node.callee.returnType
                if (returnType.className != "java.lang.Object" && returnType.className != "void") {
                    return returnType
                }
            }
            else -> {}
        }

        // Trace through incoming edges to find the actual type
        graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
            val sourceType = findArgumentType(edge.from)
            if (sourceType != null) {
                return sourceType
            }
        }

        return null
    }

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
    private fun analyzeFieldAssignments(
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

            // Get Jackson annotation info for the field
            val jacksonInfo = graph.jacksonFieldInfo(type.className, fieldName)
                ?: graph.jacksonGetterInfo(type.className, "get${fieldName.replaceFirstChar { it.uppercase() }}")

            fields[fieldName] = FieldStructure(
                name = fieldName,
                declaredType = declaredType,
                actualTypes = actualTypes,
                isGenericParameter = false,
                jsonName = jacksonInfo?.jsonName,
                isJsonIgnored = jacksonInfo?.isIgnored ?: false
            )
        }

        // Strategy 2: Find direct field assignments
        // Pattern: obj.field = value
        findDirectFieldAssignments(type, contextMethod).forEach { (fieldName, fieldNode, assignedTypes) ->
            val existing = fields[fieldName]
            if (existing != null) {
                // Merge with setter-based analysis
                fields[fieldName] = existing.copy(
                    actualTypes = existing.actualTypes + assignedTypes
                )
            } else {
                val jacksonInfo = graph.jacksonFieldInfo(type.className, fieldName)
                    ?: graph.jacksonGetterInfo(type.className, "get${fieldName.replaceFirstChar { it.uppercase() }}")
                fields[fieldName] = FieldStructure(
                    name = fieldName,
                    declaredType = fieldNode.descriptor.type,
                    actualTypes = assignedTypes,
                    jsonName = jacksonInfo?.jsonName,
                    isJsonIgnored = jacksonInfo?.isIgnored ?: false
                )
            }
        }

        // Strategy 3: Find constructor calls and map arguments to fields
        // Pattern: new Foo(arg1, arg2) where arg1 -> field1, arg2 -> field2
        val constructorFields = findConstructorAssignments(type, contextMethod, depth)
        constructorFields.forEach { (fieldName, fieldStructure) ->
            val existing = fields[fieldName]
            if (existing != null) {
                // Merge with existing analysis
                fields[fieldName] = existing.copy(
                    actualTypes = existing.actualTypes + fieldStructure.actualTypes
                )
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

                val jacksonInfo = graph.jacksonFieldInfo(type.className, fieldName)
                    ?: graph.jacksonGetterInfo(type.className, "get${fieldName.replaceFirstChar { it.uppercase() }}")
                fields[fieldName] = FieldStructure(
                    name = fieldName,
                    declaredType = declaredType,
                    actualTypes = actualTypes,
                    isGenericParameter = false,
                    jsonName = jacksonInfo?.jsonName,
                    isJsonIgnored = jacksonInfo?.isIgnored ?: false
                )
            }
        }

        return fields
    }

    /**
     * Add field assignments from global cross-method tracking.
     * This enables discovering field types even when the field is assigned
     * in a different method than where it's returned.
     *
     * Also handles inheritance: includes fields from parent classes using the type hierarchy graph.
     */
    private fun addGlobalFieldAssignments(
        type: TypeDescriptor,
        fields: MutableMap<String, FieldStructure>,
        depth: Int
    ) {
        // Collect all class names to check: current type + all supertypes (inherited)
        val classesToCheck = mutableSetOf(type.className)
        collectAllSupertypes(type, classesToCheck)

        // Get all fields of this type AND parent classes from type hierarchy
        val typeFields = graph.nodes<FieldNode>()
            .filter { fieldNode ->
                fieldNode.descriptor.declaringClass.className in classesToCheck
            }
            .toList()

        typeFields.forEach { fieldNode ->
            val fieldName = fieldNode.descriptor.name
            val declaringClass = fieldNode.descriptor.declaringClass.className
            val fieldKey = "$declaringClass#$fieldName"

            // Get globally tracked assignments for this field
            val globalTypes = globalFieldAssignments[fieldKey] ?: emptySet()

            if (globalTypes.isNotEmpty()) {
                val actualTypes = globalTypes
                    .filter { shouldAnalyzeType(it) }
                    .map { className ->
                        if (depth < config.maxDepth) {
                            buildTypeStructure(TypeDescriptor(className), MethodDescriptor(
                                declaringClass = type,
                                name = "",
                                parameterTypes = emptyList(),
                                returnType = TypeDescriptor("void")
                            ), depth + 1)
                        } else {
                            TypeStructure.simple(className)
                        }
                    }
                    .toSet()

                val existing = fields[fieldName]
                if (existing != null) {
                    // Merge with existing
                    fields[fieldName] = existing.copy(
                        actualTypes = existing.actualTypes + actualTypes
                    )
                } else if (actualTypes.isNotEmpty()) {
                    // Get Jackson annotation info (try field first, then getter)
                    val jacksonInfo = graph.jacksonFieldInfo(declaringClass, fieldName)
                        ?: graph.jacksonGetterInfo(declaringClass, "get${fieldName.replaceFirstChar { it.uppercase() }}")
                    fields[fieldName] = FieldStructure(
                        name = fieldName,
                        declaredType = fieldNode.descriptor.type,
                        actualTypes = actualTypes,
                        jsonName = jacksonInfo?.jsonName,
                        isJsonIgnored = jacksonInfo?.isIgnored ?: false
                    )
                }
            }
        }
    }

    /**
     * Collect all supertypes (parents, grandparents, etc.) for a type using the type hierarchy graph.
     */
    private fun collectAllSupertypes(type: TypeDescriptor, result: MutableSet<String>) {
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
    private fun findConstructorAssignments(
        type: TypeDescriptor,
        contextMethod: MethodDescriptor,
        depth: Int
    ): Map<String, FieldStructure> {
        val fields = mutableMapOf<String, FieldStructure>()

        // Find constructor calls (<init>) for this type
        val constructorCalls = graph.nodes<CallSiteNode>()
            .filter { it.caller.signature == contextMethod.signature }
            .filter { it.callee.name == "<init>" }
            .filter { it.callee.declaringClass.className == type.className }
            .toList()

        if (constructorCalls.isEmpty()) return fields

        // Get all fields of this type from the graph
        val typeFields = graph.nodes<FieldNode>()
            .filter { it.descriptor.declaringClass.className == type.className }
            .toList()

        constructorCalls.forEach { constructorCall ->
            val args = constructorCall.arguments

            // Try to match constructor arguments to fields by type
            args.forEachIndexed { index, argNodeId ->
                val argTypes = mutableSetOf<TypeStructure>()
                traceTypeFromNode(argNodeId, contextMethod, argTypes, mutableSetOf(), depth + 1)

                if (argTypes.isNotEmpty()) {
                    // Find a field that could match this argument
                    val matchingField = typeFields.find { fieldNode ->
                        val fieldType = fieldNode.descriptor.type.className
                        argTypes.any { argType ->
                            argType.type.className == fieldType ||
                            fieldType == "java.lang.Object" ||
                            isCompatibleType(argType.type.className, fieldType)
                        }
                    }

                    if (matchingField != null) {
                        val fieldName = matchingField.descriptor.name
                        val declaringClass = matchingField.descriptor.declaringClass.className
                        val existing = fields[fieldName]
                        if (existing != null) {
                            fields[fieldName] = existing.copy(
                                actualTypes = existing.actualTypes + argTypes
                            )
                        } else {
                            val jacksonInfo = graph.jacksonFieldInfo(declaringClass, fieldName)
                                ?: graph.jacksonGetterInfo(declaringClass, "get${fieldName.replaceFirstChar { it.uppercase() }}")
                            fields[fieldName] = FieldStructure(
                                name = fieldName,
                                declaredType = matchingField.descriptor.type,
                                actualTypes = argTypes,
                                jsonName = jacksonInfo?.jsonName,
                                isJsonIgnored = jacksonInfo?.isIgnored ?: false
                            )
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
    private fun addGetterBasedFields(
        type: TypeDescriptor,
        fields: MutableMap<String, FieldStructure>,
        depth: Int
    ) {
        // Collect all class names to check: current type + all supertypes
        val classesToCheck = mutableSetOf(type.className)
        collectAllSupertypes(type, classesToCheck)

        // Find all getter methods from this type and its supertypes
        classesToCheck.forEach { className ->
            graph.methods(MethodPattern(declaringClass = className))
                .filter { method ->
                    // Match getter patterns: getXxx() or isXxx() with no parameters
                    val name = method.name
                    method.parameterTypes.isEmpty() &&
                    method.returnType.className != "void" &&
                    ((name.startsWith("get") && name.length > 3) ||
                     (name.startsWith("is") && name.length > 2 &&
                      method.returnType.className in listOf("boolean", "java.lang.Boolean")))
                }
                .forEach { getter ->
                    // Extract field name from getter
                    val fieldName = if (getter.name.startsWith("get")) {
                        getter.name.removePrefix("get").replaceFirstChar { it.lowercase() }
                    } else {
                        getter.name.removePrefix("is").replaceFirstChar { it.lowercase() }
                    }

                    // Skip if we already have this field from setter/field analysis
                    if (fieldName in fields) return@forEach

                    val returnType = getter.returnType
                    // Include fields with analyzable types, primitive/wrapper types, or collection types
                    if (shouldAnalyzeType(returnType.className) ||
                        isPrimitiveOrWrapper(returnType.className) ||
                        isCollectionType(returnType.className)) {
                        val actualTypes = if (shouldAnalyzeType(returnType.className) && depth < config.maxDepth) {
                            setOf(buildTypeStructure(returnType, getter, depth + 1))
                        } else {
                            emptySet()
                        }

                        // Get Jackson annotation info from getter method
                        val jacksonInfo = graph.jacksonGetterInfo(className, getter.name)
                            ?: graph.jacksonFieldInfo(className, fieldName)
                        fields[fieldName] = FieldStructure(
                            name = fieldName,
                            declaredType = returnType,
                            actualTypes = actualTypes,
                            isGenericParameter = false,
                            jsonName = jacksonInfo?.jsonName,
                            isJsonIgnored = jacksonInfo?.isIgnored ?: false
                        )
                    }
                }
        }
    }

    /**
     * Discover all declared fields from class definition.
     *
     * This strategy ensures that ALL fields declared in the class are discovered,
     * even if they don't have setters, getters, or assignments in analyzed code.
     * This is important for:
     * - Public fields without getter/setter
     * - Fields only used by frameworks (e.g., Jackson, Lombok)
     * - Fields initialized via reflection or serialization
     */
    private fun addDeclaredFields(
        type: TypeDescriptor,
        fields: MutableMap<String, FieldStructure>,
        depth: Int
    ) {
        // Collect all class names to check: current type + all supertypes
        val classesToCheck = mutableSetOf(type.className)
        collectAllSupertypes(type, classesToCheck)

        // Find all FieldNodes declared in these classes
        graph.nodes<FieldNode>()
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
                if (!shouldAnalyzeType(fieldType.className) &&
                    fieldType.className != "java.lang.Object" &&
                    !isPrimitiveOrWrapper(fieldType.className) &&
                    !isCollectionType(fieldType.className)) {
                    return@forEach
                }

                // Get Jackson annotation info
                val jacksonInfo = graph.jacksonFieldInfo(declaringClass, fieldName)
                    ?: graph.jacksonGetterInfo(declaringClass, "get${fieldName.replaceFirstChar { it.uppercase() }}")
                    ?: graph.jacksonGetterInfo(declaringClass, "is${fieldName.replaceFirstChar { it.uppercase() }}")

                // Build actual types if we can analyze the field type
                val actualTypes = if (shouldAnalyzeType(fieldType.className) && depth < config.maxDepth) {
                    setOf(buildTypeStructure(fieldType, MethodDescriptor(
                        declaringClass = type,
                        name = "",
                        parameterTypes = emptyList(),
                        returnType = TypeDescriptor("void")
                    ), depth + 1))
                } else {
                    emptySet()
                }

                fields[fieldName] = FieldStructure(
                    name = fieldName,
                    declaredType = fieldType,
                    actualTypes = actualTypes,
                    isGenericParameter = false,
                    jsonName = jacksonInfo?.jsonName,
                    isJsonIgnored = jacksonInfo?.isIgnored ?: false
                )
            }
    }

    /**
     * Check if a type is a primitive or wrapper type.
     */
    private fun isPrimitiveOrWrapper(className: String): Boolean {
        return className in setOf(
            "int", "long", "short", "byte", "float", "double", "boolean", "char",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.lang.String", "java.math.BigDecimal", "java.math.BigInteger",
            "java.util.Date", "java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.ZonedDateTime", "java.time.Instant"
        )
    }

    /**
     * Check if a type is a collection or container type.
     * These types should be included in field discovery even when their package
     * is not in includePackages, because they are containers whose element types
     * may be relevant for analysis.
     */
    private fun isCollectionType(className: String): Boolean {
        return className in setOf(
            "java.util.List", "java.util.ArrayList", "java.util.LinkedList",
            "java.util.Set", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.Map", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.Collection", "java.util.Queue", "java.util.Deque",
            "java.util.Optional"
        )
    }

    /**
     * Check if two types are compatible (basic subtype check).
     */
    private fun isCompatibleType(actualType: String, declaredType: String): Boolean {
        // Simple compatibility check
        return actualType == declaredType ||
               declaredType == "java.lang.Object" ||
               (actualType.startsWith("java.") && declaredType.startsWith("java."))
    }

    /**
     * Find all setter calls where the receiver is of the given type.
     * This is a more aggressive search that looks at receiver types.
     */
    private fun findAllSetterCallsForType(
        type: TypeDescriptor,
        contextMethod: MethodDescriptor
    ): Map<String, CallSiteNode> {
        val setters = mutableMapOf<String, CallSiteNode>()

        graph.nodes<CallSiteNode>()
            .filter { it.caller.signature == contextMethod.signature }
            .filter { it.callee.name.startsWith("set") && it.callee.parameterTypes.size == 1 }
            .forEach { callSite ->
                // Check if the receiver is of the target type
                val receiverId = callSite.receiver
                if (receiverId != null) {
                    val receiverNode = graph.node(receiverId)
                    if (receiverNode is LocalVariable) {
                        val receiverType = receiverNode.type.className
                        // Strict match: receiver type must match the target type exactly
                        // or the declaring class must match
                        if (receiverType == type.className ||
                            callSite.callee.declaringClass.className == type.className) {

                            val fieldName = callSite.callee.name.removePrefix("set")
                                .replaceFirstChar { it.lowercase() }
                            setters[fieldName] = callSite
                        }
                    }
                }
            }

        return setters
    }

    /**
     * Find all setter method calls for a given type in a method.
     * Returns a map of field name to call site.
     */
    private fun findSetterCalls(
        type: TypeDescriptor,
        contextMethod: MethodDescriptor
    ): Map<String, CallSiteNode> {
        val setters = mutableMapOf<String, CallSiteNode>()

        // Find all call sites in the context method that:
        // 1. Have the declaring class matching `type` exactly
        // 2. Method name starts with "set"
        graph.nodes<CallSiteNode>()
            .filter { it.caller.signature == contextMethod.signature }
            .filter { it.callee.name.startsWith("set") && it.callee.parameterTypes.size == 1 }
            .filter { callSite ->
                // Strict match: declaring class must match type exactly
                callSite.callee.declaringClass.className == type.className
            }
            .forEach { callSite ->
                val fieldName = callSite.callee.name.removePrefix("set").replaceFirstChar { it.lowercase() }
                setters[fieldName] = callSite
            }

        return setters
    }

    /**
     * Analyze the argument passed to a setter to find its actual type.
     */
    private fun analyzeSetterArgument(
        setterCall: CallSiteNode,
        contextMethod: MethodDescriptor,
        depth: Int
    ): Set<TypeStructure> {
        if (setterCall.arguments.isEmpty()) return emptySet()

        val argNodeId = setterCall.arguments[0]
        val structures = mutableSetOf<TypeStructure>()

        traceTypeFromNode(argNodeId, contextMethod, structures, mutableSetOf(), depth)

        return structures
    }

    /**
     * Infer the declared type from a setter method signature.
     * First tries to find the corresponding field to get generic type info,
     * falls back to setter parameter type if field not found.
     */
    private fun inferDeclaredTypeFromSetter(setterCall: CallSiteNode): TypeDescriptor {
        val fieldName = setterCall.callee.name.removePrefix("set").replaceFirstChar { it.lowercase() }
        val declaringClass = setterCall.callee.declaringClass.className

        // Try to find the field to get its generic type
        val fieldNode = graph.nodes<FieldNode>()
            .filter { it.descriptor.declaringClass.className == declaringClass }
            .filter { it.descriptor.name == fieldName }
            .firstOrNull()

        if (fieldNode != null) {
            // Use field's type which may have generic info from bytecode
            return fieldNode.descriptor.type
        }

        // Fall back to setter parameter type
        return if (setterCall.callee.parameterTypes.isNotEmpty()) {
            setterCall.callee.parameterTypes[0]
        } else {
            TypeDescriptor("java.lang.Object")
        }
    }

    /**
     * Find direct field assignments in a method.
     */
    private fun findDirectFieldAssignments(
        type: TypeDescriptor,
        contextMethod: MethodDescriptor
    ): List<Triple<String, FieldNode, Set<TypeStructure>>> {
        val assignments = mutableListOf<Triple<String, FieldNode, Set<TypeStructure>>>()

        // Find FieldNodes that belong to this type and have incoming dataflow in this method
        graph.nodes<FieldNode>()
            .filter { it.descriptor.declaringClass.className == type.className }
            .forEach { fieldNode ->
                val assignedTypes = mutableSetOf<TypeStructure>()

                // Find incoming dataflow edges that represent assignments
                graph.incoming(fieldNode.id, DataFlowEdge::class.java)
                    .filter { edge ->
                        // Check if the source is in our context method
                        val sourceNode = graph.node(edge.from)
                        sourceNode is LocalVariable && sourceNode.method.signature == contextMethod.signature
                    }
                    .forEach { edge ->
                        val structures = mutableSetOf<TypeStructure>()
                        traceTypeFromNode(edge.from, contextMethod, structures, mutableSetOf(), 1)
                        assignedTypes.addAll(structures)
                    }

                if (assignedTypes.isNotEmpty()) {
                    assignments.add(Triple(fieldNode.descriptor.name, fieldNode, assignedTypes))
                }
            }

        return assignments
    }

    /**
     * Check if a type should be analyzed (not excluded by config).
     */
    private fun shouldAnalyzeType(className: String): Boolean {
        if (className == "java.lang.Object" || className == "void" || className == "unknown") {
            return false
        }

        if (config.excludePackages.any { className.startsWith(it) }) {
            return false
        }

        if (config.includePackages.isNotEmpty()) {
            return config.includePackages.any { className.startsWith(it) }
        }

        return true
    }
}
