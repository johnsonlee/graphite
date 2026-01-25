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

        fun traverse(current: NodeId, path: List<NodeId>, depth: Int) {
            if (current in visited) return
            if (depth > config.maxDepth) return
            visited.add(current)

            val node = graph.node(current) ?: return
            val currentPath = path + current

            // Check if we've reached a source (constant or parameter)
            when (node) {
                is ConstantNode -> {
                    sources.add(SourceInfo.Constant(node, currentPath))
                    paths.add(DataFlowPath(currentPath.reversed()))
                }
                is ParameterNode -> {
                    sources.add(SourceInfo.Parameter(node, currentPath))
                    if (config.interProcedural) {
                        // Find all call sites and trace arguments
                        traceParameterSources(node, currentPath, depth)
                    }
                }
                is FieldNode -> {
                    sources.add(SourceInfo.Field(node, currentPath))
                    if (config.interProcedural) {
                        traceFieldStores(node, currentPath, depth)
                    }
                }
                else -> {}
            }

            // Continue backward traversal
            graph.incoming(current, DataFlowEdge::class.java).forEach { edge ->
                traverse(edge.from, currentPath, depth + 1)
            }
        }

        traverse(from, emptyList(), 0)
        return DataFlowResult(sources, paths)
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

        fun traverse(current: NodeId, path: List<NodeId>, depth: Int) {
            if (current in visited) return
            if (depth > config.maxDepth) return
            visited.add(current)

            val node = graph.node(current) ?: return
            val currentPath = path + current

            // Check if we've reached a sink (return, field store, parameter pass)
            when (node) {
                is ReturnNode -> {
                    sinks.add(SourceInfo.Return(node, currentPath))
                    paths.add(DataFlowPath(currentPath))
                }
                is FieldNode -> {
                    sinks.add(SourceInfo.Field(node, currentPath))
                }
                else -> {}
            }

            // Continue forward traversal
            graph.outgoing(current, DataFlowEdge::class.java).forEach { edge ->
                traverse(edge.to, currentPath, depth + 1)
            }
        }

        traverse(from, emptyList(), 0)
        return DataFlowResult(sinks, paths)
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
}

/**
 * Configuration for dataflow analysis
 */
data class AnalysisConfig(
    val maxDepth: Int = 50,
    val interProcedural: Boolean = true,
    val contextSensitive: Boolean = false,
    val flowSensitive: Boolean = true
)

/**
 * Result of dataflow analysis
 */
data class DataFlowResult(
    val sources: List<SourceInfo>,
    val paths: List<DataFlowPath>
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
     * - Enum constants from FieldNode sources (synthetic EnumConstant)
     */
    fun allConstants(): List<ConstantNode> {
        val directConstants = constants()
        val enumFieldConstants = sources.filterIsInstance<SourceInfo.Field>()
            .filter { isEnumConstantField(it.node) }
            .map { fieldInfo ->
                val field = fieldInfo.node.descriptor
                EnumConstant(
                    id = NodeId("synthetic:enum:${field.declaringClass.className}.${field.name}"),
                    enumType = field.declaringClass,
                    enumName = field.name,
                    value = null // Ordinal not available from static field access
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
