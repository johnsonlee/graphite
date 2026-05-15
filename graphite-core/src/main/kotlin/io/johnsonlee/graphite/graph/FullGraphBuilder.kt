package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.input.ResourceAccessor

/**
 * Extended [GraphBuilder] that supports all metadata operations required
 * during graph construction (methods, type hierarchy, annotations, etc.).
 *
 * Both [DefaultGraph.Builder] and [MmapGraphBuilder] implement this
 * interface, allowing [SootUpAdapter][io.johnsonlee.graphite.sootup.SootUpAdapter]
 * to use either implementation transparently.
 */
interface FullGraphBuilder : GraphBuilder {
    override fun addNode(node: Node): FullGraphBuilder
    override fun addEdge(edge: Edge): FullGraphBuilder
    fun addMethod(method: MethodDescriptor): FullGraphBuilder
    fun addTypeRelation(subtype: TypeDescriptor, supertype: TypeDescriptor, relation: TypeRelation): FullGraphBuilder
    fun addEnumValues(enumClass: String, enumName: String, values: List<Any?>): FullGraphBuilder
    fun addMemberAnnotation(
        className: String,
        memberName: String,
        annotationFqn: String,
        values: Map<String, Any?> = emptyMap()
    ): FullGraphBuilder
    fun addBranchScope(
        conditionNodeId: NodeId,
        method: MethodDescriptor,
        comparison: BranchComparison,
        trueBranchNodeIds: IntArray,
        falseBranchNodeIds: IntArray
    ): FullGraphBuilder
    fun addClassOrigin(className: String, source: String): FullGraphBuilder
    fun addArtifactDependency(fromArtifact: String, toArtifact: String, weight: Int = 1): FullGraphBuilder
    fun setResources(resources: ResourceAccessor): FullGraphBuilder
}
