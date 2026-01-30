package io.johnsonlee.graphite.query

import io.johnsonlee.graphite.analysis.AnalysisConfig
import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.analysis.TypeHierarchyAnalysis
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes

/**
 * High-level Query DSL for common analysis patterns.
 *
 * Design goal: Express WHAT you want to find, not HOW to find it.
 */
class GraphiteQuery(private val graph: Graph) {

    /**
     * Find all constant values passed to methods matching the pattern.
     *
     * Example - Find all AB IDs (single argument):
     * ```
     * graphite.findArgumentConstants {
     *     method {
     *         name = "getOption"
     *         declaringClass = "com.example.ab.AbClient"
     *         parameterTypes = listOf("java.lang.Integer")
     *     }
     *     argumentIndex = 0
     * }
     * ```
     *
     * Example - Track multiple arguments:
     * ```
     * graphite.findArgumentConstants {
     *     method {
     *         name = "setConfig"
     *         declaringClass = "com.example.ConfigManager"
     *     }
     *     argumentIndices = listOf(0, 1, 2)
     * }
     * ```
     */
    fun findArgumentConstants(block: ArgumentConstantQuery.() -> Unit): List<ArgumentConstantResult> {
        val query = ArgumentConstantQuery().apply(block)
        val analysis = DataFlowAnalysis(graph, query.analysisConfig)
        val results = mutableListOf<ArgumentConstantResult>()
        val indices = query.resolveIndices()

        // Find all call sites matching the method pattern
        graph.callSites(query.methodPattern).forEach { callSite ->
            for (argIndex in indices) {
                if (argIndex < callSite.arguments.size) {
                    val argNodeId = callSite.arguments[argIndex]
                    val flowResult = analysis.backwardSlice(argNodeId)

                    // Use constantsWithPaths() to get constants with their propagation paths
                    flowResult.constantsWithPaths().forEach { (constant, propagationPath) ->
                        results.add(
                            ArgumentConstantResult(
                                callSite = callSite,
                                argumentIndex = argIndex,
                                constant = constant,
                                path = flowResult.paths.firstOrNull(),
                                propagationPath = propagationPath
                            )
                        )
                    }
                }
            }
        }

        return results
    }

    /**
     * Find actual return types for methods matching the pattern.
     *
     * Example - Find actual types returned by REST controllers:
     * ```
     * graphite.findActualReturnTypes {
     *     method {
     *         annotations = listOf("org.springframework.web.bind.annotation.GetMapping")
     *     }
     * }
     * ```
     */
    fun findActualReturnTypes(block: ReturnTypeQuery.() -> Unit): List<ReturnTypeResult> {
        val query = ReturnTypeQuery().apply(block)
        val results = mutableListOf<ReturnTypeResult>()

        // Find all methods matching the pattern
        graph.methods(query.methodPattern).forEach { method ->
            val returnNodes = graph.nodes<ReturnNode>()
                .filter { it.method == method }
                .toList()

            val actualTypes = mutableSetOf<TypeDescriptor>()

            returnNodes.forEach { returnNode ->
                // Recursively trace backward from return to find actual types
                // Use interprocedural analysis context to track visited methods
                val context = InterproceduralContext(
                    maxDepth = query.analysisConfig.maxDepth
                )
                findActualTypesFromNode(returnNode.id, actualTypes, mutableSetOf(), context)
            }

            if (actualTypes.isNotEmpty()) {
                results.add(
                    ReturnTypeResult(
                        method = method,
                        declaredType = method.returnType,
                        actualTypes = actualTypes.toList()
                    )
                )
            }
        }

        return results
    }

    /**
     * Context for interprocedural analysis to track visited methods and depth.
     */
    private class InterproceduralContext(
        val maxDepth: Int = 10,
        val visitedMethods: MutableSet<String> = mutableSetOf(),
        var currentDepth: Int = 0
    )

    /**
     * Recursively trace backward through dataflow edges to find actual types.
     * Handles cases where values flow through intermediate locals with unknown types.
     * Supports interprocedural analysis by tracing into called methods.
     */
    private fun findActualTypesFromNode(
        nodeId: NodeId,
        types: MutableSet<TypeDescriptor>,
        visited: MutableSet<NodeId>,
        context: InterproceduralContext
    ) {
        if (nodeId in visited) return
        if (context.currentDepth > context.maxDepth) return
        visited.add(nodeId)

        graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
            val sourceNode = graph.node(edge.from)
            when (sourceNode) {
                is LocalVariable -> {
                    val typeName = sourceNode.type.className
                    if (typeName != "java.lang.Object" && typeName != "unknown") {
                        types.add(sourceNode.type)
                    } else {
                        // Continue tracing back if type is unknown/Object
                        findActualTypesFromNode(edge.from, types, visited, context)
                    }
                }
                is FieldNode -> types.add(sourceNode.descriptor.type)
                is CallSiteNode -> {
                    val returnType = sourceNode.callee.returnType
                    val calleeSignature = sourceNode.callee.signature

                    // If return type is concrete (not Object/void), use it
                    if (returnType.className != "java.lang.Object" && returnType.className != "void") {
                        types.add(returnType)
                    } else {
                        // Interprocedural: trace into the called method to find actual return types
                        // Only if we haven't visited this method yet (prevent infinite recursion)
                        if (calleeSignature !in context.visitedMethods) {
                            context.visitedMethods.add(calleeSignature)
                            context.currentDepth++

                            // Find return nodes of the callee method
                            val calleeReturnNodes = graph.nodes<ReturnNode>()
                                .filter { it.method.signature == calleeSignature }
                                .toList()

                            // Trace each return node in the callee
                            calleeReturnNodes.forEach { calleeReturn ->
                                findActualTypesFromNode(calleeReturn.id, types, mutableSetOf(), context)
                            }

                            context.currentDepth--
                        }
                    }
                }
                is ConstantNode -> {
                    when (sourceNode) {
                        is IntConstant -> types.add(TypeDescriptor("java.lang.Integer"))
                        is LongConstant -> types.add(TypeDescriptor("java.lang.Long"))
                        is FloatConstant -> types.add(TypeDescriptor("java.lang.Float"))
                        is DoubleConstant -> types.add(TypeDescriptor("java.lang.Double"))
                        is BooleanConstant -> types.add(TypeDescriptor("java.lang.Boolean"))
                        is StringConstant -> types.add(TypeDescriptor("java.lang.String"))
                        is EnumConstant -> types.add(sourceNode.enumType)
                        is NullConstant -> {} // null doesn't contribute a type
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Find complete type hierarchy for method return types.
     *
     * This analyzes not just what type is returned, but the complete
     * type structure including:
     * - Generic type parameters
     * - Actual types assigned to Object fields
     * - Nested type structures
     *
     * Example:
     * ```kotlin
     * graphite.query {
     *     findTypeHierarchy {
     *         method {
     *             declaringClass = "com.example.UserController"
     *             name = "getUsers"
     *         }
     *     }
     * }
     * ```
     *
     * Output structure for ApiResponse<PageData<User>>:
     * ```
     * ApiResponse<PageData<User>>
     * ├── data: PageData<User>
     * │   ├── items: List<User>
     * │   └── extra: Object → PageMetadata
     * └── metadata: Object → RequestMetadata
     * ```
     */
    fun findTypeHierarchy(block: TypeHierarchyQuery.() -> Unit): List<TypeHierarchyResult> {
        val query = TypeHierarchyQuery().apply(block)
        val analysis = TypeHierarchyAnalysis(graph, query.config)
        return analysis.analyzeReturnTypes(query.methodPattern)
    }

    /**
     * Find fields of specific types (for i18n compliance checking)
     */
    fun findFieldsOfType(block: FieldTypeQuery.() -> Unit): List<FieldTypeResult> {
        val query = FieldTypeQuery().apply(block)
        val results = mutableListOf<FieldTypeResult>()

        graph.nodes<FieldNode>().forEach { fieldNode ->
            val fieldType = fieldNode.descriptor.type
            if (query.typePatterns.any { pattern -> matchesTypePattern(fieldType, pattern) }) {
                results.add(
                    FieldTypeResult(
                        field = fieldNode.descriptor,
                        containingClass = fieldNode.descriptor.declaringClass,
                        isCompliant = query.complianceCheck?.invoke(fieldType) ?: true
                    )
                )
            }
        }

        return results
    }

    private fun matchesTypePattern(type: TypeDescriptor, pattern: String): Boolean {
        if (pattern.endsWith("*")) {
            return type.className.startsWith(pattern.dropLast(1))
        }
        return type.className == pattern
    }
}

// Query DSL classes

class ArgumentConstantQuery {
    var methodPattern: MethodPattern = MethodPattern()
    var argumentIndex: Int? = null
    var argumentIndices: List<Int>? = null
    var analysisConfig: AnalysisConfig = AnalysisConfig()

    /**
     * Resolve the effective argument indices to analyze.
     *
     * Priority:
     * 1. [argumentIndices] if explicitly set
     * 2. [argumentIndex] if explicitly set (wrapped in a list)
     * 3. Default: listOf(0)
     */
    internal fun resolveIndices(): List<Int> {
        return argumentIndices ?: argumentIndex?.let { listOf(it) } ?: listOf(0)
    }

    fun method(block: MethodPatternBuilder.() -> Unit) {
        methodPattern = MethodPatternBuilder().apply(block).build()
    }

    fun config(block: AnalysisConfig.() -> AnalysisConfig) {
        analysisConfig = AnalysisConfig().block()
    }
}

class ReturnTypeQuery {
    var methodPattern: MethodPattern = MethodPattern()
    var analysisConfig: AnalysisConfig = AnalysisConfig()

    fun method(block: MethodPatternBuilder.() -> Unit) {
        methodPattern = MethodPatternBuilder().apply(block).build()
    }
}

class TypeHierarchyQuery {
    var methodPattern: MethodPattern = MethodPattern()
    var config: TypeHierarchyConfig = TypeHierarchyConfig()

    fun method(block: MethodPatternBuilder.() -> Unit) {
        methodPattern = MethodPatternBuilder().apply(block).build()
    }

    fun config(block: TypeHierarchyConfig.() -> TypeHierarchyConfig) {
        config = TypeHierarchyConfig().block()
    }
}

class FieldTypeQuery {
    var typePatterns: List<String> = emptyList()
    var complianceCheck: ((TypeDescriptor) -> Boolean)? = null
}

class MethodPatternBuilder {
    var declaringClass: String? = null
    var name: String? = null
    var parameterTypes: List<String>? = null
    var returnType: String? = null
    var annotations: List<String> = emptyList()
    var useRegex: Boolean = false

    fun build() = MethodPattern(
        declaringClass = declaringClass,
        name = name,
        parameterTypes = parameterTypes,
        returnType = returnType,
        annotations = annotations,
        useRegex = useRegex
    )
}

// Result classes

data class ArgumentConstantResult(
    val callSite: CallSiteNode,
    val argumentIndex: Int,
    val constant: ConstantNode,
    val path: io.johnsonlee.graphite.analysis.DataFlowPath?,
    val propagationPath: io.johnsonlee.graphite.analysis.PropagationPath? = null
) {
    val location: String get() = "${callSite.caller.signature}:${callSite.lineNumber ?: "?"}"

    val value: Any? get() = constant.value

    /**
     * Get the propagation depth (number of steps from source to sink).
     * Higher depth indicates more complex value flow.
     */
    val propagationDepth: Int get() = propagationPath?.depth ?: 0

    /**
     * Get a human-readable description of the propagation path.
     */
    val propagationDescription: String get() = propagationPath?.toDisplayString() ?: "(direct)"

    /**
     * Check if this result involves method return value propagation.
     */
    val involvesReturnValue: Boolean get() =
        propagationPath?.steps?.any { it.nodeType == io.johnsonlee.graphite.analysis.PropagationNodeType.CALL_SITE } ?: false

    /**
     * Check if this result involves field access.
     */
    val involvesFieldAccess: Boolean get() =
        propagationPath?.steps?.any { it.nodeType == io.johnsonlee.graphite.analysis.PropagationNodeType.FIELD } ?: false
}

data class ReturnTypeResult(
    val method: MethodDescriptor,
    val declaredType: TypeDescriptor,
    val actualTypes: List<TypeDescriptor>
) {
    val hasGenericReturn: Boolean get() =
        declaredType.className == "java.lang.Object" ||
                declaredType.typeArguments.any { it.className == "?" }

    val typesMismatch: Boolean get() =
        actualTypes.any { it != declaredType && !it.isSubtypeOf(declaredType) }
}

data class FieldTypeResult(
    val field: FieldDescriptor,
    val containingClass: TypeDescriptor,
    val isCompliant: Boolean
)
