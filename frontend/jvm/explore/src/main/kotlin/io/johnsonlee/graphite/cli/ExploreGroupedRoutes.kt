package io.johnsonlee.graphite.cli

import io.javalin.http.Context
import io.johnsonlee.graphite.graph.Graph

internal fun ExploreRoutes.requiredQueryParam(ctx: Context, name: String): String? =
    ctx.queryParam(name)
        ?: run {
            ctx.status(HTTP_BAD_REQUEST).result("Missing '$name' parameter")
            null
        }

internal fun ExploreRoutes.grouped(graphId: String, data: Any?): Map<String, Any?> =
    mapOf(API_FIELD_GRAPH_ID to graphId, API_FIELD_DATA to data)

internal fun ExploreRoutes.groupedEnvelope(selectedGraphCount: Int, results: List<Map<String, Any?>>): Map<String, Any?> =
    mapOf(API_FIELD_GRAPH_COUNT to selectedGraphCount, "resultGraphCount" to results.size, API_FIELD_RESULTS to results)

internal fun ExploreRoutes.respondGrouped(ctx: Context, leases: List<GraphLease>, data: (Graph) -> Any?) {
    ctx.json(groupedEnvelope(leases.size, leases.map { grouped(it.id, data(it.graph)) }))
}

internal fun ExploreRoutes.respondGroupedLimited(ctx: Context, leases: List<GraphLease>, totalLimit: Int, data: (GraphLease, Int) -> Any?) {
    val limits = distributedLimits(leases.size, totalLimit)
    val results = leases.mapIndexed { index, lease -> grouped(lease.id, data(lease, limits[index])) }
    ctx.json(groupedEnvelope(leases.size, results))
}

internal inline fun ExploreRoutes.withAllGraphs(ctx: Context, acquire: (Context) -> List<GraphLease>, block: (List<GraphLease>) -> Unit) {
    val leases =
        try {
            acquire(ctx)
        } catch (error: IllegalArgumentException) {
            ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message))
            return
        } catch (error: GraphNotLoadedException) {
            ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to error.message))
            return
        }
    try {
        block(leases)
    } finally {
        leases.forEach { it.close() }
    }
}

internal fun ExploreRoutes.distributedLimits(graphCount: Int, totalLimit: Int): IntArray {
    if (graphCount == 0) return IntArray(0)
    val base = totalLimit / graphCount
    val remainder = totalLimit % graphCount
    return IntArray(graphCount) { index -> base + if (index < remainder) 1 else 0 }
}

internal inline fun ExploreRoutes.withGraph(ctx: Context, provider: GraphProvider, block: (Graph) -> Unit) {
    val lease =
        try {
            provider.acquire(ctx)
        } catch (e: IllegalArgumentException) {
            ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to e.message))
            return
        }
    if (lease == null) {
        ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to "Graph not loaded"))
        return
    }
    lease.use { block(it.graph) }
}
