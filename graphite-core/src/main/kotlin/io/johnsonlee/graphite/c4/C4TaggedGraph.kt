package io.johnsonlee.graphite.c4

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.input.ResourceAccessor

/**
 * Decorates a [Graph] with synthetic C4 stereotype / external-system annotations
 * exposed through [memberAnnotations] under the `<class>` member key.
 *
 * The decorator is applied once during build time (in `JavaProjectLoader.load`),
 * so [io.johnsonlee.graphite.webgraph.GraphStore.save] picks up the synthetic
 * tags via the regular [Graph.memberAnnotations] interface and persists them
 * into the saved-graph metadata. Loaded graphs therefore expose the tags
 * directly without re-wrapping.
 *
 * Stereotype tags are added when [C4Tags.classifyStereotype] matches a class's
 * existing annotations. External-system tags are added for any class FQN
 * referenced by a [CallSiteNode] callee that matches an external prefix rule
 * in [C4Tags.classifyExternalSystem].
 */
class C4TaggedGraph(private val delegate: Graph) : Graph {

    private val externalCalleeClasses: Set<String> by lazy {
        delegate.nodes(CallSiteNode::class.java)
            .map { it.callee.declaringClass.className }
            .filter { C4Tags.classifyExternalSystem(it) != null }
            .toSet()
    }

    override fun node(id: NodeId): Node? = delegate.node(id)

    override fun <T : Node> nodes(type: Class<T>): Sequence<T> = delegate.nodes(type)

    override fun outgoing(id: NodeId): Sequence<Edge> = delegate.outgoing(id)

    override fun incoming(id: NodeId): Sequence<Edge> = delegate.incoming(id)

    override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> = delegate.outgoing(id, type)

    override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> = delegate.incoming(id, type)

    override fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> =
        delegate.callSites(methodPattern)

    override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> = delegate.supertypes(type)

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> = delegate.subtypes(type)

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> = delegate.methods(pattern)

    override fun enumValues(enumClass: String, enumName: String): List<Any?>? =
        delegate.enumValues(enumClass, enumName)

    override fun memberAnnotations(className: String, memberName: String): Map<String, Map<String, Any?>> {
        val base = delegate.memberAnnotations(className, memberName)
        if (memberName != CLASS_MEMBER) return base

        val stereotype = C4Tags.classifyStereotype(base)
        val externalSystem = if (className in externalCalleeClasses) {
            C4Tags.classifyExternalSystem(className)
        } else {
            null
        }

        if (stereotype == null && externalSystem == null) return base

        val merged = LinkedHashMap<String, Map<String, Any?>>(base.size + 2)
        merged.putAll(base)
        if (stereotype != null) {
            merged[C4Tags.STEREOTYPE_ANNOTATION] = mapOf(C4Tags.VALUE_ATTRIBUTE to stereotype)
        }
        if (externalSystem != null) {
            merged[C4Tags.EXTERNAL_SYSTEM_ANNOTATION] = mapOf(C4Tags.VALUE_ATTRIBUTE to externalSystem)
        }
        return merged
    }

    override val resources: ResourceAccessor get() = delegate.resources

    override fun branchScopes(): Sequence<BranchScope> = delegate.branchScopes()

    override fun branchScopesFor(conditionNodeId: NodeId): Sequence<BranchScope> =
        delegate.branchScopesFor(conditionNodeId)

    override fun typeHierarchyTypes(): Set<String> = delegate.typeHierarchyTypes()

    private companion object {
        const val CLASS_MEMBER = "<class>"
    }
}
