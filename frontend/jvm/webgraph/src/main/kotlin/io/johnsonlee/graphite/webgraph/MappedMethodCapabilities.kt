package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StreamingMethodLookup

internal class MappedMethodCapabilities(private val context: MappedGraphContext) : StreamingMethodLookup {
    override fun methods(pattern: MethodPattern, scanConsumer: MethodMetadataScanConsumer): Sequence<MethodDescriptor> =
        context.methods(pattern, scanConsumer)

    override fun methodSlice(pattern: MethodPattern, limit: Int, scanConsumer: MethodMetadataScanConsumer): List<MethodDescriptor> =
        context.methodSlice(pattern, limit, scanConsumer)
}
