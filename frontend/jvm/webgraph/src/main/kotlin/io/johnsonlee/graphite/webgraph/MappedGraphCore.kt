package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.MethodPattern

internal class MappedGraphCore(context: MappedGraphContext) : MappedGraphMetadataProcessor(context) {
    override fun node(id: NodeId): Node? = context.node(id)

    override fun <T : Node> nodes(type: Class<T>): Sequence<T> = context.nodes(type)

    override fun nodeCount(type: Class<out Node>): Long = context.nodeCount(type)

    override fun edgeCount(): Long = context.edgeCount()

    override fun outgoing(id: NodeId): Sequence<Edge> = context.outgoing(id)

    override fun incoming(id: NodeId): Sequence<Edge> = context.incoming(id)

    override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> = context.outgoing(id, type)

    override fun <T : Edge> incoming(id: NodeId, type: Class<T>): Sequence<T> = context.incoming(id, type)

    override fun callSites(methodPattern: MethodPattern): Sequence<CallSiteNode> = context.callSites(methodPattern)

    override fun supertypes(type: TypeDescriptor): Sequence<TypeDescriptor> = context.supertypes(type)

    override fun subtypes(type: TypeDescriptor): Sequence<TypeDescriptor> = context.subtypes(type)

    override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> = context.methods(pattern)

    override fun methodCount(): Long = context.methodCount()

    override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> = context.methodSlice(pattern, limit)

    override fun hasMethod(method: MethodDescriptor): Boolean = context.hasMethod(method)
}
