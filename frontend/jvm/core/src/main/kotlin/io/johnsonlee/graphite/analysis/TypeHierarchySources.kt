package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeStructure
import io.johnsonlee.graphite.graph.nodes

/** Build a global map of all field assignments across all methods. This enables cross-method field tracking. */
internal fun TypeHierarchyAnalysis.buildGlobalFieldAssignments(): Map<String, Set<String>> {
    val assignments = mutableMapOf<String, MutableSet<String>>()

    // Find all setter calls across all methods
    graph
        .nodes<CallSiteNode>()
        .filter { it.callee.name.startsWith(SETTER_PREFIX) && it.callee.parameterTypes.size == 1 }
        .forEach { callSite ->
            val declaringClass = callSite.callee.declaringClass.className
            val fieldName = callSite.callee.name.removePrefix(SETTER_PREFIX).replaceFirstChar { it.lowercase() }
            val fieldKey = fieldKey(declaringClass, fieldName)

            if (callSite.arguments.isNotEmpty()) {
                val argNodeId = callSite.arguments[0]
                val argTypes = findTypesForNode(argNodeId)
                assignments.getOrPut(fieldKey) { mutableSetOf() }.addAll(argTypes)
            }
        }

    // Find all direct field assignments
    graph.nodes<FieldNode>().forEach { fieldNode ->
        val fieldKey = fieldKey(fieldNode.descriptor.declaringClass.className, fieldNode.descriptor.name)

        graph.incoming(fieldNode.id, DataFlowEdge::class.java).forEach { edge ->
            val sourceNode = graph.node(edge.from) as? LocalVariable
            val typeName = sourceNode?.type?.className
            if (typeName != null && typeName != JAVA_LANG_OBJECT && typeName != UNKNOWN_TYPE) {
                assignments.getOrPut(fieldKey) { mutableSetOf() }.add(typeName)
            }
        }
    }

    return assignments
}

/** Find types for a node by tracing backward through dataflow. */
internal fun TypeHierarchyAnalysis.findTypesForNode(nodeId: NodeId): Set<String> {
    val types = mutableSetOf<String>()
    val visited = mutableSetOf<NodeId>()
    findTypesRecursive(nodeId, types, visited, 0)
    return types
}

internal fun TypeHierarchyAnalysis.findTypesRecursive(nodeId: NodeId, types: MutableSet<String>, visited: MutableSet<NodeId>, depth: Int) {
    if (nodeId in visited || depth > LOCAL_TYPE_TRACE_MAX_DEPTH) return
    visited.add(nodeId)

    val node = graph.node(nodeId)
    when (node) {
        is LocalVariable -> {
            val typeName = node.type.className
            if (typeName != JAVA_LANG_OBJECT && typeName != UNKNOWN_TYPE) {
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
            if (returnType != JAVA_LANG_OBJECT && returnType != VOID_TYPE) {
                types.add(returnType)
            }
        }
        else -> {
            graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge -> findTypesRecursive(edge.from, types, visited, depth + 1) }
        }
    }
}

/** Trace backward through dataflow to find actual types. */
internal fun TypeHierarchyAnalysis.traceTypeFromNode(
    nodeId: NodeId,
    contextMethod: MethodDescriptor,
    structures: MutableSet<TypeStructure>,
    visited: MutableSet<NodeId>,
    depth: Int
) {
    if (nodeId in visited || depth > config.maxDepth) return
    visited.add(nodeId)

    val directType = directTraceType(graph.node(nodeId))
    if (directType != null) {
        structures.add(buildTypeStructure(directType, contextMethod, depth + 1))
        return
    }

    // Then trace through incoming edges
    graph.incoming(nodeId, DataFlowEdge::class.java).forEach { edge ->
        traceTypeSource(graph.node(edge.from), edge.from, contextMethod, structures, visited, depth)
    }
}

/** Find the type of an argument node by looking at its LocalVariable type. */
internal fun TypeHierarchyAnalysis.findArgumentType(nodeId: NodeId): TypeDescriptor? {
    val direct =
        when (val node = graph.node(nodeId)) {
            is LocalVariable -> node.type.takeUnless { it.className == JAVA_LANG_OBJECT || it.className == UNKNOWN_TYPE }
            is CallSiteNode -> node.callee.returnType.takeUnless { it.className == JAVA_LANG_OBJECT || it.className == VOID_TYPE }
            else -> null
        }
    return direct ?: graph.incoming(nodeId, DataFlowEdge::class.java).mapNotNull { findArgumentType(it.from) }.firstOrNull()
}

private fun TypeHierarchyAnalysis.directTraceType(node: Node?): TypeDescriptor? =
    when (node) {
        is LocalVariable -> node.type.takeIf { shouldAnalyzeType(it.className) }
        is CallSiteNode -> node.callee.returnType.takeIf { shouldAnalyzeType(it.className) && it.className != JAVA_LANG_OBJECT }
        else -> null
    }

private fun TypeHierarchyAnalysis.traceTypeSource(
    sourceNode: Node?,
    sourceId: NodeId,
    contextMethod: MethodDescriptor,
    structures: MutableSet<TypeStructure>,
    visited: MutableSet<NodeId>,
    depth: Int
) {
    when (sourceNode) {
        is LocalVariable -> {
            val typeName = sourceNode.type.className
            if (shouldAnalyzeType(typeName)) {
                // Build type structure for this local variable
                val structure = buildTypeStructure(sourceNode.type, contextMethod, depth + 1)
                structures.add(structure)
            } else if (typeName == JAVA_LANG_OBJECT || typeName == UNKNOWN_TYPE) {
                // Continue tracing back
                traceTypeFromNode(sourceId, contextMethod, structures, visited, depth + 1)
            }
        }
        is CallSiteNode -> {
            val returnType = sourceNode.callee.returnType
            if (shouldAnalyzeType(returnType.className) && returnType.className != JAVA_LANG_OBJECT) {
                // Build structure from the return type
                val structure = buildTypeStructure(returnType, contextMethod, depth + 1)
                structures.add(structure)
            } else if (config.interProcedural && sourceNode.callee.signature !in analyzingMethods) {
                // Trace into the called method
                val calleeResults = analyzeMethod(sourceNode.callee)
                structures.addAll(calleeResults.returnStructures)
            }
        }
        is FieldNode -> traceFieldType(sourceNode, contextMethod, structures, depth)
        else -> {
            // Continue tracing for other node types
            traceTypeFromNode(sourceId, contextMethod, structures, visited, depth + 1)
        }
    }
}

private fun TypeHierarchyAnalysis.traceFieldType(
    sourceNode: FieldNode,
    contextMethod: MethodDescriptor,
    structures: MutableSet<TypeStructure>,
    depth: Int
) {
    val fieldType = sourceNode.descriptor.type
    val declaringClass = sourceNode.descriptor.declaringClass.className
    val fieldName = sourceNode.descriptor.name
    val fieldKey = fieldKey(declaringClass, fieldName)

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
