package io.johnsonlee.graphite.query

import io.johnsonlee.graphite.analysis.AnalysisConfig
import io.johnsonlee.graphite.analysis.DataFlowAnalysis
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
     * Example - Find all AB IDs:
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
     */
    fun findArgumentConstants(block: ArgumentConstantQuery.() -> Unit): List<ArgumentConstantResult> {
        val query = ArgumentConstantQuery().apply(block)
        val analysis = DataFlowAnalysis(graph, query.analysisConfig)
        val results = mutableListOf<ArgumentConstantResult>()

        // Find all call sites matching the method pattern
        graph.callSites(query.methodPattern).forEach { callSite ->
            val argIndex = query.argumentIndex ?: 0
            if (argIndex < callSite.arguments.size) {
                val argNodeId = callSite.arguments[argIndex]
                val flowResult = analysis.backwardSlice(argNodeId)

                // Use allConstants() to include enum constants from field accesses
                flowResult.allConstants().forEach { constant ->
                    results.add(
                        ArgumentConstantResult(
                            callSite = callSite,
                            argumentIndex = argIndex,
                            constant = constant,
                            path = flowResult.paths.firstOrNull()
                        )
                    )
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
                findActualTypesFromNode(returnNode.id, actualTypes, mutableSetOf())
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
     * Recursively trace backward through dataflow edges to find actual types.
     * Handles cases where values flow through intermediate locals with unknown types.
     */
    private fun findActualTypesFromNode(
        nodeId: NodeId,
        types: MutableSet<TypeDescriptor>,
        visited: MutableSet<NodeId>
    ) {
        if (nodeId in visited) return
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
                        findActualTypesFromNode(edge.from, types, visited)
                    }
                }
                is FieldNode -> types.add(sourceNode.descriptor.type)
                is CallSiteNode -> {
                    val returnType = sourceNode.callee.returnType
                    if (returnType.className != "java.lang.Object" && returnType.className != "void") {
                        types.add(returnType)
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
    var analysisConfig: AnalysisConfig = AnalysisConfig()

    fun method(block: MethodPatternBuilder.() -> Unit) {
        methodPattern = MethodPatternBuilder().apply(block).build()
    }

    fun config(block: AnalysisConfig.() -> Unit) {
        analysisConfig = AnalysisConfig().run {
            block()
            this
        }
    }
}

class ReturnTypeQuery {
    var methodPattern: MethodPattern = MethodPattern()
    var analysisConfig: AnalysisConfig = AnalysisConfig()

    fun method(block: MethodPatternBuilder.() -> Unit) {
        methodPattern = MethodPatternBuilder().apply(block).build()
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

    fun build() = MethodPattern(
        declaringClass = declaringClass,
        name = name,
        parameterTypes = parameterTypes,
        returnType = returnType,
        annotations = annotations
    )
}

// Result classes

data class ArgumentConstantResult(
    val callSite: CallSiteNode,
    val argumentIndex: Int,
    val constant: ConstantNode,
    val path: io.johnsonlee.graphite.analysis.DataFlowPath?
) {
    val location: String get() = "${callSite.caller.signature}:${callSite.lineNumber ?: "?"}"

    val value: Any? get() = constant.value
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
