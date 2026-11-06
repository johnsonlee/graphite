package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.MethodPattern

internal fun MappedGraphContext.declaredTypes(): DeclaredTypeTable = declaredTypeTable.value

/** Field, Parameter and Return records name their members by string IDs; the GTY05 table keys rows by the same. */
internal fun MappedGraphContext.declaredTypeBinding(): DeclaredTypeBinding? = recordBinding

internal fun MappedGraphContext.localDefinitions(): Map<NodeId, List<LocalDefinition>> = localDefinitionIndex

internal fun MappedGraphContext.supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
    metadata.value.supertypes[type.className]?.asSequence() ?: emptySequence()

internal fun MappedGraphContext.subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> =
    metadata.value.subtypes[type.className]?.asSequence() ?: emptySequence()

internal fun MappedGraphContext.enumValues(enumClass: String, enumName: String): List<Any?>? =
    metadata.value.enumValues["$enumClass#$enumName"]

internal fun MappedGraphContext.enumValueIndex(): Map<String, List<Any?>> = metadata.value.enumValues

internal fun MappedGraphContext.memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
    metadata.value.memberAnnotations["$className#$memberName"] ?: emptyMap()

internal fun MappedGraphContext.memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>> = metadata.value.memberAnnotations

internal fun MappedGraphContext.classOrigin(className: String): String? = metadata.value.classOrigins[className]

internal fun MappedGraphContext.classOrigins(): Map<String, String> = metadata.value.classOrigins

internal fun MappedGraphContext.syntheticIdentity(member: String): String? = metadata.value.syntheticIdentities[member]

internal fun MappedGraphContext.syntheticIdentities(): Map<String, String> = metadata.value.syntheticIdentities

internal fun MappedGraphContext.artifactDependencies(): Map<String, Map<String, Int>> = metadata.value.artifactDependencies

internal fun MappedGraphContext.classOverview(limit: Int): ClassOverview? = classOverviewProvider(limit)

/** An exact lookup through the method index's class range, so a proof over many callees pays no scan per callee. */
internal fun MappedGraphContext.hasMethod(method: MethodDescriptor): Boolean {
    val pattern =
        MethodPattern(
            declaringClass = method.declaringClass.className,
            name = method.name,
            parameterTypes = method.parameterTypes.map { it.className },
            returnType = method.returnType.className)
    if (MappedMethodIndex.cannotMatch(pattern, stringTable)) return false
    return methodIndex().slice(pattern, 1).isNotEmpty()
}

internal fun MappedGraphContext.analysisCompleteness(): AnalysisCompleteness? = analysisCompleteness.value

internal fun MappedGraphContext.branchScopes(): Sequence<BranchScope> = branchScopeIndex.values.asSequence().flatMap { it.asSequence() }

internal fun MappedGraphContext.branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
    branchScopeIndex[conditionNodeId.value]?.asSequence() ?: emptySequence()

internal fun MappedGraphContext.typeHierarchyTypes(): Set<String> = metadata.value.supertypes.keys + metadata.value.subtypes.keys
