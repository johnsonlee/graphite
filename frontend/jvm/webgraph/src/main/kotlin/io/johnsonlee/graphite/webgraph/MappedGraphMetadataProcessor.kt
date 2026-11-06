package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.EnumValueIndexSource
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.ResourceAccessor

internal abstract class MappedGraphMetadataProcessor(protected val context: MappedGraphContext) : Graph, EnumValueIndexSource {
    override val resources: ResourceAccessor
        get() = context.resources

    override fun declaredTypes(): DeclaredTypeTable = context.declaredTypes()

    override fun declaredTypeBinding(): DeclaredTypeBinding? = context.declaredTypeBinding()

    override fun localDefinitions(): Map<NodeId, List<LocalDefinition>> = context.localDefinitions()

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? = context.enumValues(enumClass, enumName)

    override fun enumValueIndex(): Map<String, List<Any?>> = context.enumValueIndex()

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> =
        context.memberAnnotations(className, memberName)

    override fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>> = context.memberAnnotationIndex()

    override fun classOrigin(className: String): String? = context.classOrigin(className)

    override fun classOrigins(): Map<String, String> = context.classOrigins()

    override fun syntheticIdentity(member: String): String? = context.syntheticIdentity(member)

    override fun syntheticIdentities(): Map<String, String> = context.syntheticIdentities()

    override fun artifactDependencies(): Map<String, Map<String, Int>> = context.artifactDependencies()

    override fun classOverview(limit: Int): ClassOverview? = context.classOverview(limit)

    override fun analysisCompleteness(): AnalysisCompleteness? = context.analysisCompleteness()

    override fun branchScopes(): Sequence<BranchScope> = context.branchScopes()

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> = context.branchScopesFor(conditionNodeId)

    override fun typeHierarchyTypes(): Set<String> = context.typeHierarchyTypes()
}
