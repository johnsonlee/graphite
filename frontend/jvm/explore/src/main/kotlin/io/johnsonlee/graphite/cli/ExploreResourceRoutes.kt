package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.ResourceEntry
import io.johnsonlee.graphite.input.unavailableReason
import java.io.IOException

@Suppress("ReturnCount")
internal fun ExploreRoutes.readResource(graph: Graph, path: String, maxBytes: Int): Map<String, Any?>? {
    val entry = resolveResourceEntry(graph, path) ?: return null
    val bytes =
        try {
            readBoundedResource(graph, path, maxBytes)
        } catch (_: IOException) {
            return null
        }
    return mapOf(
        API_FIELD_PATH to path,
        API_FIELD_SOURCE to entry.source,
        API_FIELD_DERIVED to false,
        API_FIELD_SIZE to bytes.size,
        API_FIELD_CONTENT to bytes.toString(Charsets.UTF_8))
}

internal fun ExploreRoutes.resolveResourceEntry(graph: Graph, path: String): ResourceEntry? =
    graph.resources.list("**").firstOrNull { it.path == path }

internal fun ExploreRoutes.requireResourceStore(ctx: Context, graph: Graph): Boolean {
    val reason = graph.resources.unavailableReason ?: return true
    ctx.status(HTTP_CONFLICT).json(mapOf(API_FIELD_ERROR to reason))
    return false
}

internal fun ExploreRoutes.requireResourceStores(ctx: Context, leases: List<GraphLease>): Boolean {
    val unavailable =
        leases.mapNotNull { lease ->
            lease.graph.resources.unavailableReason?.let { reason -> mapOf(API_FIELD_GRAPH_ID to lease.id, API_FIELD_ERROR to reason) }
        }
    if (unavailable.isEmpty()) return true
    ctx.status(HTTP_CONFLICT)
        .json(mapOf(API_FIELD_ERROR to "Persisted resources are unavailable for one or more graphs", API_FIELD_RESULTS to unavailable))
    return false
}

internal fun ExploreRoutes.listResources(graph: Graph, pattern: String, limit: Int): List<Map<String, Any?>> {
    return graph.resources
        .list(pattern)
        .map { entry -> mapOf(API_FIELD_PATH to entry.path, API_FIELD_SOURCE to entry.source, API_FIELD_DERIVED to false) }
        .take(limit)
        .toList()
}

internal fun ExploreRoutes.readBoundedResource(graph: Graph, path: String, maxBytes: Int = MAX_RESOURCE_BYTES): ByteArray =
    graph.resources.open(path).use { input ->
        val bytes = input.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) throw ResourceTooLargeException()
        bytes
    }
