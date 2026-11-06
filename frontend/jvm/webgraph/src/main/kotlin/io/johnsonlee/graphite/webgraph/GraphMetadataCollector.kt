package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.EnumValueIndexSource
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.PackedBranchMetadataSource

/** Collects the graph metadata sections, using indexes when available and complete scans otherwise. */
internal class GraphMetadataCollector(private val graph: Graph) {
    fun collect(): GraphMetadata {
        // Collect type hierarchy
        val supertypes = mutableMapOf<String, Set<TypeDescriptor>>()
        val subtypes = mutableMapOf<String, Set<TypeDescriptor>>()
        val hierarchyTypeNames = graph.typeHierarchyTypes()
        val allTypes =
            if (hierarchyTypeNames.isNotEmpty()) {
                hierarchyTypeNames.mapTo(mutableSetOf()) { TypeDescriptor(it) }
            } else {
                collectReferencedTypes()
            }

        for (type in allTypes) {
            val sups = graph.supertypes(type).toSet()
            if (sups.isNotEmpty()) supertypes[type.className] = sups
            val subs = graph.subtypes(type).toSet()
            if (subs.isNotEmpty()) subtypes[type.className] = subs
        }

        // Collect methods
        val methods = graph.methods(MethodPattern()).associateBy { it.identity }

        // Declaration-only cases have no EnumConstant node. Fill any missing
        // entries from graphs that expose enum arguments through point lookups.
        val enumValues = (graph as? EnumValueIndexSource)?.enumValueIndex().orEmpty().toMutableMap()
        graph.nodes(EnumConstant::class.java).forEach { ec ->
            val key = "${ec.enumType.className}#${ec.enumName}"
            if (key !in enumValues) {
                val values = graph.enumValues(ec.enumType.className, ec.enumName)
                if (values != null) enumValues[key] = values
            }
        }

        val memberAnnotations = graph.memberAnnotationIndex()?.toMap() ?: collectMemberAnnotations()

        // Collect branch scopes
        val packedSource = graph as? PackedBranchMetadataSource
        val branchScopes =
            packedSource
                ?.packedBranchScopes()
                ?.map { scope ->
                    BranchScopeData(
                        scope.conditionNodeId,
                        scope.method,
                        scope.comparison,
                        scope.trueBranchNodeIds,
                        scope.falseBranchNodeIds,
                        scope.trueDefinitions,
                        scope.falseDefinitions)
                }
                ?.toList()
                ?: graph
                    .branchScopes()
                    .map { bs ->
                        BranchScopeData(
                            conditionNodeId = bs.conditionNodeId.value,
                            method = bs.method,
                            comparison = bs.comparison,
                            trueBranchNodeIds = bs.trueBranchNodeIds.toIntArray(),
                            falseBranchNodeIds = bs.falseBranchNodeIds.toIntArray(),
                            trueDefinitions = BranchScope.packDefinitions(bs.trueDefinitions),
                            falseDefinitions = BranchScope.packDefinitions(bs.falseDefinitions))
                    }
                    .toList()

        val localDefinitions =
            packedSource?.packedLocalDefinitions()
                ?: graph.localDefinitions().entries.associate { (localId, definitions) ->
                    localId.value to BranchScope.packDefinitions(definitions)
                }

        return GraphMetadata(
            methods = methods,
            supertypes = supertypes,
            subtypes = subtypes,
            enumValues = enumValues,
            classOrigins = graph.classOrigins(),
            artifactDependencies = graph.artifactDependencies(),
            memberAnnotations = memberAnnotations,
            branchScopes = branchScopes,
            localDefinitions = localDefinitions,
            syntheticIdentities = graph.syntheticIdentities())
    }

    private fun collectReferencedTypes(): MutableSet<TypeDescriptor> {
        val allTypes = mutableSetOf<TypeDescriptor>()
        graph.nodes(Node::class.java).forEach { node ->
            when (node) {
                is LocalVariable -> {
                    allTypes.add(node.type)
                    allTypes.add(node.method.declaringClass)
                }
                is FieldNode -> {
                    allTypes.add(node.descriptor.declaringClass)
                    allTypes.add(node.descriptor.type)
                }
                is ParameterNode -> {
                    allTypes.add(node.type)
                    allTypes.add(node.method.declaringClass)
                }
                is ReturnNode -> {
                    node.actualType?.let { allTypes.add(it) }
                    allTypes.add(node.method.declaringClass)
                    allTypes.add(node.method.returnType)
                }
                is CallSiteNode -> {
                    allTypes.add(node.callee.declaringClass)
                    allTypes.add(node.callee.returnType)
                    allTypes.add(node.caller.declaringClass)
                }
                is EnumConstant -> allTypes.add(node.enumType)
                is AnnotationNode -> {}
                else -> {}
            }
        }
        graph.methods(MethodPattern()).forEach { method ->
            allTypes.add(method.declaringClass)
            allTypes.add(method.returnType)
            method.parameterTypes.forEach { allTypes.add(it) }
        }
        return allTypes
    }

    private fun collectMemberAnnotations(): Map<String, Map<String, Map<String, Any?>>> {
        val memberAnnotations = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        val classMembers = mutableSetOf<Pair<String, String>>()
        graph.nodes(Node::class.java).forEach { node ->
            when (node) {
                is FieldNode -> classMembers.add(node.descriptor.declaringClass.className to node.descriptor.name)
                is CallSiteNode -> classMembers.add(node.callee.declaringClass.className to node.callee.name)
                is ParameterNode -> classMembers.add(node.method.declaringClass.className to node.method.name)
                is ReturnNode -> classMembers.add(node.method.declaringClass.className to node.method.name)
                is AnnotationNode -> {}
                else -> {}
            }
        }
        // Also add <class> level annotations
        val allClasses = classMembers.map { it.first }.toSet()
        for (className in allClasses) {
            classMembers.add(className to "<class>")
        }
        for ((className, memberName) in classMembers) {
            val annotations = graph.memberAnnotations(className, memberName)
            if (annotations.isNotEmpty()) {
                memberAnnotations["$className#$memberName"] = annotations
            }
        }
        return memberAnnotations
    }
}
