package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph

/**
 * Dataflow analysis engine.
 *
 * Supports both forward and backward analysis with configurable
 * inter-procedural depth and sensitivity.
 */
class DataFlowAnalysis(
    private val graph: Graph,
    private val config: AnalysisConfig = AnalysisConfig()
) {
    /**
     * Backward slice: find all nodes that can flow TO the given node.
     *
     * Use case: AB ID detection
     * - Start from getOption(Integer) argument
     * - Trace backward to find all possible constant values
     */
    fun backwardSlice(from: NodeId): DataFlowResult {
        val visited = mutableSetOf<NodeId>()
        val sources = mutableListOf<SourceInfo>()
        val paths = mutableListOf<DataFlowPath>()
        val propagationPaths = mutableListOf<PropagationPath>()

        fun traverse(
            current: NodeId,
            path: List<NodeId>,
            propSteps: List<PropagationStep>,
            lastEdgeKind: DataFlowKind?,
            depth: Int
        ) {
            if (current in visited) return
            if (depth > config.maxDepth) return
            visited.add(current)

            val node = graph.node(current) ?: return
            val currentPath = path + current

            // Create a propagation step for the current node
            val step = createPropagationStep(node, lastEdgeKind, depth)
            val currentPropSteps = propSteps + step

            // Check if we've reached a source (constant or parameter)
            when (node) {
                is ConstantNode -> {
                    sources.add(SourceInfo.Constant(node, currentPath))
                    paths.add(DataFlowPath(currentPath.reversed()))
                    // Create propagation path (reversed to show source → sink)
                    val sourceType = when (node) {
                        is EnumConstant -> PropagationSourceType.ENUM_CONSTANT
                        else -> PropagationSourceType.CONSTANT
                    }
                    propagationPaths.add(PropagationPath(currentPropSteps.reversed(), sourceType, depth))
                }
                is ParameterNode -> {
                    sources.add(SourceInfo.Parameter(node, currentPath))
                    propagationPaths.add(PropagationPath(currentPropSteps.reversed(), PropagationSourceType.PARAMETER, depth))
                    if (config.interProcedural) {
                        // Find all call sites and trace arguments
                        traceParameterSources(node, currentPath, depth)
                    }
                }
                is FieldNode -> {
                    sources.add(SourceInfo.Field(node, currentPath))
                    val sourceType = if (isEnumConstantField(node)) {
                        PropagationSourceType.ENUM_CONSTANT
                    } else {
                        PropagationSourceType.FIELD
                    }
                    propagationPaths.add(PropagationPath(currentPropSteps.reversed(), sourceType, depth))
                    if (config.interProcedural) {
                        traceFieldStores(node, currentPath, depth)
                    }
                }
                is CallSiteNode -> {
                    // Trace backward through receiver for instance method calls
                    // This enables tracing patterns like receiver.getId() back to the receiver
                    if (node.receiver != null) {
                        traverse(node.receiver, currentPath, currentPropSteps, DataFlowKind.RETURN_VALUE, depth + 1)
                    }

                    // Handle collection factory calls specially
                    val isCollFactory = isCollectionFactory(node.callee)
                    if (isCollFactory) {
                        if (config.expandCollections) {
                            val collectionDepth = currentPath.count { nodeId ->
                                val n = graph.node(nodeId)
                                n is CallSiteNode && isCollectionFactory(n.callee)
                            }
                            if (collectionDepth < config.maxCollectionDepth) {
                                // For varargs methods like Arrays.asList(1, 2, 3), the arguments
                                // in bytecode are the actual values (not an array), so we trace
                                // incoming PARAMETER_PASS edges to find the constants
                                graph.incoming(current, DataFlowEdge::class.java).forEach { edge ->
                                    traverse(edge.from, currentPath, currentPropSteps, edge.kind, depth + 1)
                                }
                            }
                        }
                        // For collection factories, we've already handled tracing above
                        // (either traced incoming edges, or nothing if expandCollections=false)
                        return
                    }
                }
                else -> {}
            }

            // Continue backward traversal (skip for collection factories - handled above)
            graph.incoming(current, DataFlowEdge::class.java).forEach { edge ->
                traverse(edge.from, currentPath, currentPropSteps, edge.kind, depth + 1)
            }
        }

        traverse(from, emptyList(), emptyList(), null, 0)
        return DataFlowResult(sources, paths, propagationPaths, graph)
    }

    /**
     * Create a PropagationStep from a node.
     */
    private fun createPropagationStep(node: Node, edgeKind: DataFlowKind?, depth: Int): PropagationStep {
        return when (node) {
            is ConstantNode -> PropagationStep(
                nodeId = node.id,
                nodeType = PropagationNodeType.CONSTANT,
                description = formatConstantDescription(node),
                location = null,
                edgeKind = edgeKind,
                depth = depth
            )
            is LocalVariable -> PropagationStep(
                nodeId = node.id,
                nodeType = PropagationNodeType.LOCAL_VARIABLE,
                description = "var ${node.name}: ${node.type.simpleName}",
                location = "${node.method.declaringClass.simpleName}.${node.method.name}()",
                edgeKind = edgeKind,
                depth = depth
            )
            is ParameterNode -> PropagationStep(
                nodeId = node.id,
                nodeType = PropagationNodeType.PARAMETER,
                description = "param[${node.index}]: ${node.type.simpleName}",
                location = "${node.method.declaringClass.simpleName}.${node.method.name}()",
                edgeKind = edgeKind,
                depth = depth
            )
            is FieldNode -> PropagationStep(
                nodeId = node.id,
                nodeType = PropagationNodeType.FIELD,
                description = "${if (node.isStatic) "static " else ""}${node.descriptor.declaringClass.simpleName}.${node.descriptor.name}",
                location = null,
                edgeKind = edgeKind,
                depth = depth
            )
            is ReturnNode -> PropagationStep(
                nodeId = node.id,
                nodeType = PropagationNodeType.RETURN_VALUE,
                description = "return: ${node.actualType?.simpleName ?: node.method.returnType.simpleName}",
                location = "${node.method.declaringClass.simpleName}.${node.method.name}()",
                edgeKind = edgeKind,
                depth = depth
            )
            is CallSiteNode -> PropagationStep(
                nodeId = node.id,
                nodeType = PropagationNodeType.CALL_SITE,
                description = "${node.callee.declaringClass.simpleName}.${node.callee.name}()",
                location = "${node.caller.declaringClass.simpleName}.${node.caller.name}():${node.lineNumber ?: "?"}",
                edgeKind = edgeKind,
                depth = depth
            )
            else -> PropagationStep(
                nodeId = node.id,
                nodeType = PropagationNodeType.UNKNOWN,
                description = node.javaClass.simpleName,
                location = null,
                edgeKind = edgeKind,
                depth = depth
            )
        }
    }

    /**
     * Format a constant node for display.
     */
    private fun formatConstantDescription(node: ConstantNode): String {
        return when (node) {
            is IntConstant -> "const int: ${node.value}"
            is LongConstant -> "const long: ${node.value}L"
            is FloatConstant -> "const float: ${node.value}f"
            is DoubleConstant -> "const double: ${node.value}"
            is StringConstant -> "const string: \"${node.value.take(50)}${if (node.value.length > 50) "..." else ""}\""
            is BooleanConstant -> "const boolean: ${node.value}"
            is EnumConstant -> "enum: ${node.enumType.simpleName}.${node.enumName}"
            is NullConstant -> "const null"
        }
    }

    /**
     * Check if a field is an enum constant (static field where type == declaring class)
     */
    private fun isEnumConstantField(fieldNode: FieldNode): Boolean {
        val descriptor = fieldNode.descriptor
        return descriptor.type.className == descriptor.declaringClass.className
    }

    /**
     * Forward slice: find all nodes that values can flow TO from the given node.
     *
     * Use case: API return type analysis
     * - Start from a value node inside a method
     * - Trace forward to return statements
     * - Collect actual types at each point
     */
    fun forwardSlice(from: NodeId): DataFlowResult {
        val visited = mutableSetOf<NodeId>()
        val sinks = mutableListOf<SourceInfo>()
        val paths = mutableListOf<DataFlowPath>()
        val propagationPaths = mutableListOf<PropagationPath>()

        fun traverse(
            current: NodeId,
            path: List<NodeId>,
            propSteps: List<PropagationStep>,
            lastEdgeKind: DataFlowKind?,
            depth: Int
        ) {
            if (current in visited) return
            if (depth > config.maxDepth) return
            visited.add(current)

            val node = graph.node(current) ?: return
            val currentPath = path + current

            // Create a propagation step for the current node
            val step = createPropagationStep(node, lastEdgeKind, depth)
            val currentPropSteps = propSteps + step

            // Check if we've reached a sink (return, field store, parameter pass)
            when (node) {
                is ReturnNode -> {
                    sinks.add(SourceInfo.Return(node, currentPath))
                    paths.add(DataFlowPath(currentPath))
                    propagationPaths.add(PropagationPath(currentPropSteps, PropagationSourceType.RETURN_VALUE, depth))
                }
                is FieldNode -> {
                    sinks.add(SourceInfo.Field(node, currentPath))
                    propagationPaths.add(PropagationPath(currentPropSteps, PropagationSourceType.FIELD, depth))
                }
                else -> {}
            }

            // Continue forward traversal
            graph.outgoing(current, DataFlowEdge::class.java).forEach { edge ->
                traverse(edge.to, currentPath, currentPropSteps, edge.kind, depth + 1)
            }
        }

        traverse(from, emptyList(), emptyList(), null, 0)
        return DataFlowResult(sinks, paths, propagationPaths, graph)
    }

    private fun traceParameterSources(param: ParameterNode, path: List<NodeId>, depth: Int) {
        // Find all call sites to this method and trace the argument at param.index
        graph.callSites(
            io.johnsonlee.graphite.graph.MethodPattern(
                declaringClass = param.method.declaringClass.className,
                name = param.method.name
            )
        ).forEach { callSite ->
            if (param.index < callSite.arguments.size) {
                val argNodeId = callSite.arguments[param.index]
                // Recursively trace this argument
                // (simplified - real implementation would merge results)
            }
        }
    }

    private fun traceFieldStores(field: FieldNode, path: List<NodeId>, depth: Int) {
        // Find all stores to this field and trace the stored values
        // (simplified - real implementation would handle this)
    }

    companion object {
        /**
         * Known collection factory methods that create collections from varargs.
         * Format: "fully.qualified.ClassName.methodName"
         */
        private val COLLECTION_FACTORY_METHODS = setOf(
            // Kotlin stdlib
            "kotlin.collections.CollectionsKt.listOf",
            "kotlin.collections.CollectionsKt.listOfNotNull",
            "kotlin.collections.CollectionsKt.mutableListOf",
            "kotlin.collections.CollectionsKt.arrayListOf",
            "kotlin.collections.CollectionsKt.setOf",
            "kotlin.collections.CollectionsKt.mutableSetOf",
            "kotlin.collections.CollectionsKt.hashSetOf",
            "kotlin.collections.CollectionsKt.linkedSetOf",
            "kotlin.collections.SetsKt.setOf",
            "kotlin.collections.SetsKt.mutableSetOf",
            // Java stdlib
            "java.util.Arrays.asList",
            "java.util.List.of",
            "java.util.Set.of",
            "java.util.Map.of",
            // Guava
            "com.google.common.collect.ImmutableList.of",
            "com.google.common.collect.ImmutableSet.of",
            "com.google.common.collect.Lists.newArrayList",
            "com.google.common.collect.Sets.newHashSet"
        )

        /**
         * Check if a method is a collection factory that creates a collection from varargs.
         */
        fun isCollectionFactory(method: MethodDescriptor): Boolean {
            val fullName = "${method.declaringClass.className}.${method.name}"
            return fullName in COLLECTION_FACTORY_METHODS
        }
    }
}

/**
 * Configuration for dataflow analysis
 */
data class AnalysisConfig(
    val maxDepth: Int = 50,
    val interProcedural: Boolean = true,
    val contextSensitive: Boolean = false,
    val flowSensitive: Boolean = true,
    /**
     * When true, expand collection factory calls (listOf, Arrays.asList, etc.)
     * to trace constants inside the collection elements.
     * Default: false (preserves backward compatibility)
     */
    val expandCollections: Boolean = false,
    /**
     * Maximum depth for nested collection expansion (e.g., listOf(listOf(1, 2))).
     * Only used when expandCollections is true.
     * Default: 3
     */
    val maxCollectionDepth: Int = 3
)

/**
 * Result of dataflow analysis
 */
class DataFlowResult(
    val sources: List<SourceInfo>,
    val paths: List<DataFlowPath>,
    val propagationPaths: List<PropagationPath> = emptyList(),
    private val graph: Graph? = null  // Optional graph for enum value lookup
) {
    /**
     * Get all constant values that were found (direct constants only)
     */
    fun constants(): List<ConstantNode> = sources.filterIsInstance<SourceInfo.Constant>().map { it.node }

    /**
     * Get all constant values including enum constants from field accesses.
     *
     * Enum constants are accessed via static field reads (e.g., ExperimentId.CHECKOUT_V2),
     * which are represented as FieldNode in the graph. This method extracts both:
     * - Direct ConstantNode values
     * - Enum constants from FieldNode sources (synthetic EnumConstant with resolved values)
     */
    fun allConstants(): List<ConstantNode> {
        val directConstants = constants()
        val enumFieldConstants = sources.filterIsInstance<SourceInfo.Field>()
            .filter { isEnumConstantField(it.node) }
            .map { fieldInfo ->
                val field = fieldInfo.node.descriptor
                val enumClass = field.declaringClass.className
                val enumName = field.name
                // Look up the enum values from the graph
                val constructorArgs = graph?.enumValues(enumClass, enumName) ?: emptyList()
                EnumConstant(
                    id = NodeId.next(),
                    enumType = field.declaringClass,
                    enumName = enumName,
                    constructorArgs = constructorArgs
                )
            }
        return directConstants + enumFieldConstants
    }

    /**
     * Check if a field is an enum constant (static field where type == declaring class)
     */
    private fun isEnumConstantField(fieldNode: FieldNode): Boolean {
        val descriptor = fieldNode.descriptor
        // Enum constants are static fields where the field type is the same as the declaring class
        return descriptor.type.className == descriptor.declaringClass.className
    }

    /**
     * Get all field sources
     */
    fun fields(): List<FieldNode> = sources.filterIsInstance<SourceInfo.Field>().map { it.node }

    /**
     * Get all integer constants
     */
    fun intConstants(): List<Int> = constants().filterIsInstance<IntConstant>().map { it.value }

    /**
     * Get all enum constants (direct only, use allConstants() for field-based enum constants)
     */
    fun enumConstants(): List<EnumConstant> = constants().filterIsInstance<EnumConstant>()

    /**
     * Get all propagation paths grouped by source type.
     */
    fun propagationPathsBySourceType(): Map<PropagationSourceType, List<PropagationPath>> =
        propagationPaths.groupBy { it.sourceType }

    /**
     * Get the maximum propagation depth across all paths.
     */
    fun maxPropagationDepth(): Int = propagationPaths.maxOfOrNull { it.depth } ?: 0

    /**
     * Get propagation paths with their associated constants.
     * Returns pairs of (constant, path) for each found constant.
     */
    fun constantsWithPaths(): List<Pair<ConstantNode, PropagationPath?>> {
        val constantPaths = mutableListOf<Pair<ConstantNode, PropagationPath?>>()

        // Direct constants with their paths
        sources.filterIsInstance<SourceInfo.Constant>().forEachIndexed { index, source ->
            val path = propagationPaths.getOrNull(index)
            constantPaths.add(source.node to path)
        }

        // Enum constants from field accesses
        val directConstCount = sources.filterIsInstance<SourceInfo.Constant>().size
        sources.filterIsInstance<SourceInfo.Field>()
            .filter { isEnumConstantField(it.node) }
            .forEachIndexed { index, fieldInfo ->
                val field = fieldInfo.node.descriptor
                val enumClass = field.declaringClass.className
                val enumName = field.name
                val constructorArgs = graph?.enumValues(enumClass, enumName) ?: emptyList()
                val enumConstant = EnumConstant(
                    id = NodeId.next(),
                    enumType = field.declaringClass,
                    enumName = enumName,
                    constructorArgs = constructorArgs
                )
                // Find the corresponding propagation path (after direct constants)
                val path = propagationPaths.getOrNull(directConstCount + index)
                constantPaths.add(enumConstant to path)
            }

        return constantPaths
    }
}

/**
 * Information about a source/sink in the dataflow
 */
sealed interface SourceInfo {
    val path: List<NodeId>

    data class Constant(val node: ConstantNode, override val path: List<NodeId>) : SourceInfo
    data class Parameter(val node: ParameterNode, override val path: List<NodeId>) : SourceInfo
    data class Field(val node: FieldNode, override val path: List<NodeId>) : SourceInfo
    data class Return(val node: ReturnNode, override val path: List<NodeId>) : SourceInfo
}

/**
 * A path through the dataflow graph
 */
data class DataFlowPath(val nodes: List<NodeId>) {
    val length: Int get() = nodes.size
}

/**
 * Represents a single step in a propagation path.
 * Contains detailed information about the node and how the value flows through it.
 */
data class PropagationStep(
    val nodeId: NodeId,
    val nodeType: PropagationNodeType,
    val description: String,
    val location: String?,
    val edgeKind: DataFlowKind?,
    val depth: Int
) {
    /**
     * Compact representation for display
     */
    fun toDisplayString(): String {
        val locSuffix = if (location != null) " @ $location" else ""
        return "$description$locSuffix"
    }
}

/**
 * Types of nodes in a propagation path
 */
enum class PropagationNodeType {
    CONSTANT,           // Literal constant value
    LOCAL_VARIABLE,     // Local variable assignment
    PARAMETER,          // Method parameter
    FIELD,              // Field access (load/store)
    RETURN_VALUE,       // Method return value
    CALL_SITE,          // Method invocation
    RECEIVER,           // Receiver of method call
    UNKNOWN
}

/**
 * Detailed propagation path with step information.
 * Tracks the complete flow from source (constant) to sink (argument).
 */
data class PropagationPath(
    val steps: List<PropagationStep>,
    val sourceType: PropagationSourceType,
    val depth: Int
) {
    /**
     * Format the path as a readable string with arrows showing flow direction.
     */
    fun toDisplayString(): String {
        if (steps.isEmpty()) return "(empty path)"
        return steps.joinToString(" → ") { it.toDisplayString() }
    }

    /**
     * Format the path as a tree-like structure for verbose output.
     */
    fun toTreeString(indent: String = ""): String {
        if (steps.isEmpty()) return "${indent}(empty path)"
        val sb = StringBuilder()
        steps.forEachIndexed { index, step ->
            val prefix = if (index == 0) "└─ " else "   └─ "
            val edgeLabel = step.edgeKind?.let { " [$it]" } ?: ""
            sb.appendLine("$indent$prefix${step.toDisplayString()}$edgeLabel")
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Type of source where the value originates
 */
enum class PropagationSourceType {
    CONSTANT,           // Direct constant value
    ENUM_CONSTANT,      // Enum constant via field access
    PARAMETER,          // Method parameter (interprocedural)
    FIELD,              // Field value
    RETURN_VALUE        // Return value from another method
}
