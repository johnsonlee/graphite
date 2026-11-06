package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeStructure
import io.johnsonlee.graphite.graph.nodes

/** Build a complete TypeStructure for a given type. */
internal fun TypeHierarchyAnalysis.buildTypeStructure(type: TypeDescriptor, contextMethod: MethodDescriptor, depth: Int): TypeStructure {
    if (depth > config.maxDepth || !shouldAnalyzeType(type.className)) {
        return TypeStructure.simple(type.className)
    }
    val cacheKey = "${type.className}:${contextMethod.signature}"
    return typeCache[cacheKey] ?: createTypeStructure(type, contextMethod, depth, cacheKey)
}

private fun TypeHierarchyAnalysis.createTypeStructure(
    type: TypeDescriptor,
    contextMethod: MethodDescriptor,
    depth: Int,
    cacheKey: String
): TypeStructure {
    // Find fields of this type and their assignments in the context method
    val fields = analyzeFieldAssignments(type, contextMethod, depth)

    // Find generic type arguments from constructor calls
    val typeArguments = analyzeGenericTypeArguments(type, contextMethod, depth)

    val structure = TypeStructure(type = type, typeArguments = typeArguments, fields = fields)

    typeCache[cacheKey] = structure
    return structure
}

/**
 * Analyze generic type arguments by looking at constructor calls.
 *
 * For a generic class like L1<T> with constructor L1(T value), when we see `new L1<>(l2)` where l2 is of type L2, we know T -> L2.
 */
internal fun TypeHierarchyAnalysis.analyzeGenericTypeArguments(
    type: TypeDescriptor,
    contextMethod: MethodDescriptor,
    depth: Int
): Map<String, TypeStructure> {
    val typeArgs = mutableMapOf<String, TypeStructure>()

    if (depth > config.maxDepth) {
        return typeArgs
    }

    // Find constructor calls (<init>) for this type in the context method
    val constructorCalls =
        graph
            .nodes<CallSiteNode>()
            .filter { it.caller.signature == contextMethod.signature }
            .filter { it.callee.name == "<init>" }
            .filter { it.callee.declaringClass.className == type.className }
            .toList()

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
                val nestedStructure = buildTypeStructure(argType, contextMethod, depth + 1)
                typeArgs[paramName] = nestedStructure
            }
        }
    }

    return typeArgs
}

/** Find all setter calls where the receiver is of the given type. This is a more aggressive search that looks at receiver types. */
internal fun TypeHierarchyAnalysis.findAllSetterCallsForType(
    type: TypeDescriptor,
    contextMethod: MethodDescriptor
): Map<String, CallSiteNode> {
    val setters = mutableMapOf<String, CallSiteNode>()

    graph
        .nodes<CallSiteNode>()
        .filter { it.caller.signature == contextMethod.signature }
        .filter { it.callee.name.startsWith(SETTER_PREFIX) && it.callee.parameterTypes.size == 1 }
        .forEach { callSite ->
            // Check if the receiver is of the target type
            val receiverNode = callSite.receiver?.let(graph::node) as? LocalVariable
            val receiverType = receiverNode?.type?.className
            if (receiverType != null && (receiverType == type.className || callSite.callee.declaringClass.className == type.className)) {
                val fieldName = callSite.callee.name.removePrefix(SETTER_PREFIX).replaceFirstChar { it.lowercase() }
                setters[fieldName] = callSite
            }
        }

    return setters
}

/** Find all setter method calls for a given type in a method. Returns a map of field name to call site. */
internal fun TypeHierarchyAnalysis.findSetterCalls(type: TypeDescriptor, contextMethod: MethodDescriptor): Map<String, CallSiteNode> {
    val setters = mutableMapOf<String, CallSiteNode>()

    // Find all call sites in the context method that:
    // 1. Have the declaring class matching `type` exactly
    // 2. Method name starts with "set"
    graph
        .nodes<CallSiteNode>()
        .filter { it.caller.signature == contextMethod.signature }
        .filter { it.callee.name.startsWith(SETTER_PREFIX) && it.callee.parameterTypes.size == 1 }
        .filter { callSite ->
            // Strict match: declaring class must match type exactly
            callSite.callee.declaringClass.className == type.className
        }
        .forEach { callSite ->
            val fieldName = callSite.callee.name.removePrefix(SETTER_PREFIX).replaceFirstChar { it.lowercase() }
            setters[fieldName] = callSite
        }

    return setters
}

/** Analyze the argument passed to a setter to find its actual type. */
internal fun TypeHierarchyAnalysis.analyzeSetterArgument(
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
 * Infer the declared type from a setter method signature. First tries to find the corresponding field to get generic type info, falls back
 * to setter parameter type if field not found.
 */
internal fun TypeHierarchyAnalysis.inferDeclaredTypeFromSetter(setterCall: CallSiteNode): TypeDescriptor {
    val fieldName = setterCall.callee.name.removePrefix(SETTER_PREFIX).replaceFirstChar { it.lowercase() }
    val declaringClass = setterCall.callee.declaringClass.className

    // Try to find the field to get its generic type
    val fieldNode =
        graph
            .nodes<FieldNode>()
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
        TypeDescriptor(JAVA_LANG_OBJECT)
    }
}

/** Find direct field assignments in a method. */
internal fun TypeHierarchyAnalysis.findDirectFieldAssignments(
    type: TypeDescriptor,
    contextMethod: MethodDescriptor
): List<Triple<String, FieldNode, Set<TypeStructure>>> {
    val assignments = mutableListOf<Triple<String, FieldNode, Set<TypeStructure>>>()

    // Find FieldNodes that belong to this type and have incoming dataflow in this method
    graph
        .nodes<FieldNode>()
        .filter { it.descriptor.declaringClass.className == type.className }
        .forEach { fieldNode ->
            val assignedTypes = mutableSetOf<TypeStructure>()

            // Find incoming dataflow edges that represent assignments
            graph
                .incoming(fieldNode.id, DataFlowEdge::class.java)
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

/** Check if a type should be analyzed (not excluded by config). */
internal fun TypeHierarchyAnalysis.shouldAnalyzeType(className: String): Boolean {
    val special = className == JAVA_LANG_OBJECT || className == VOID_TYPE || className == UNKNOWN_TYPE
    val excluded = config.excludePackages.any { className.startsWith(it) }
    val included = config.includePackages.isEmpty() || config.includePackages.any { className.startsWith(it) }
    return !special && !excluded && included
}
