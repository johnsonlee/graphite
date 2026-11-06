package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.EnumValueIndexSource
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.NodeIdCandidateLookup
import io.johnsonlee.graphite.graph.NodePropertyTextCandidates
import io.johnsonlee.graphite.graph.PreparedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.ReleasableStringPropertyDisjunctionCache
import io.johnsonlee.graphite.graph.RetainedStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.StreamingMethodLookup
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionDistinctProjection
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionLookupStrategy
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionProjection
import io.johnsonlee.graphite.graph.StringPropertyLookupOrder
import io.johnsonlee.graphite.graph.WorkAwareStreamingStringPropertyProjection
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionAggregation
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyDisjunctionLookup
import io.johnsonlee.graphite.graph.WorkAwareStringPropertyLookup
import io.johnsonlee.graphite.graph.WorkAwareTransformedStringPropertyLookup
import java.io.Closeable

/** A mapped graph facade whose capability processors share one context and cache lifetime. */
internal class MappedWebGraphBackedGraph private constructor(internal val context: MappedGraphContext) :
    Graph by context.graphCapabilities,
    EnumValueIndexSource by context.graphCapabilities,
    NodePropertyTextCandidates by context.nodeCapabilities,
    NodeIdCandidateLookup by context.nodeCapabilities,
    StringPropertyLookupOrder by context.nodeCapabilities,
    StreamingMethodLookup by context.methodCapabilities,
    WorkAwareStringPropertyLookup by context.lookupCapabilities,
    WorkAwareTransformedStringPropertyLookup by context.lookupCapabilities,
    WorkAwareStringPropertyDisjunctionLookup by context.lookupCapabilities,
    PreparedStringPropertyDisjunctionLookup by context.lookupCapabilities,
    RetainedStringPropertyDisjunctionLookup by context.lookupCapabilities,
    StringPropertyDisjunctionLookupStrategy by context.lookupCapabilities,
    WorkAwareStringPropertyDisjunctionAggregation by context.projectionCapabilities,
    StringPropertyDisjunctionProjection by context.projectionCapabilities,
    WorkAwareStreamingStringPropertyProjection by context.projectionCapabilities,
    StringPropertyDisjunctionDistinctProjection by context.projectionCapabilities,
    ReleasableStringPropertyDisjunctionCache by context.cacheCapabilities,
    Closeable {
    internal constructor(
        nodes: MappedNodeStorage,
        edges: MappedEdgeStorage,
        sidecars: MappedGraphSidecars,
        indexes: MappedIndexFiles
    ) : this(MappedGraphContext(nodes, edges, sidecars, indexes))

    // Keep the concrete graph's non-null capability results visible to same-module callers.
    override fun nodeCount(type: Class<out Node>): Long = context.nodeCount(type)

    override fun edgeCount(): Long = context.edgeCount()

    override fun methodCount(): Long = context.methodCount()

    override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> = context.methodSlice(pattern, limit)

    override fun methodSlice(pattern: MethodPattern, limit: Int, scanConsumer: MethodMetadataScanConsumer): List<MethodDescriptor> =
        context.methodSlice(pattern, limit, scanConsumer)

    override fun memberAnnotationIndex(): Map<String, Map<String, Map<String, Any?>>> = context.memberAnnotationIndex()

    override fun close() = context.close()
}

internal fun MappedWebGraphBackedGraph.clearStringPropertyIndexes() = context.clearStringPropertyIndexes()

/**
 * Prepares the complete CallSite string search path before the graph is exposed to queries. A denied shared-memory reservation is a normal
 * fallback and leaves raw scans available.
 */
internal fun MappedWebGraphBackedGraph.prepareCallSiteStringIndex(
    workConsumer: GraphWorkConsumer = CALL_SITE_INDEX_PREPARATION_WORK_CONSUMER
): Boolean = context.prepareCallSiteStringIndex(workConsumer)

internal fun MappedWebGraphBackedGraph.persistPreparedCallSiteStringIndex(): Boolean = context.persistPreparedCallSiteStringIndex()
