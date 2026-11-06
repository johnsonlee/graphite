package io.johnsonlee.graphite.cli

import io.javalin.Javalin
import io.javalin.http.Context
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import io.johnsonlee.graphite.cypher.CypherQueryTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

internal fun ExploreRoutes.executeSingleGraphCypher(ctx: Context, provider: GraphProvider, query: String) {
    val limit = boundedLimit(ctx, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT)
    executeCypherAsync(
        ctx = ctx,
        acquire = { listOf(provider.acquire(ctx) ?: throw GraphNotLoadedException()) },
        execute = { leases, executionContext -> CypherExecutor(leases.single().graph, executionContext).execute(query, limit) },
        respond = { result, cancellationSignal ->
            cypherResponseSerializer.write(
                ctx,
                mapOf(API_FIELD_COLUMNS to result.columns, API_FIELD_ROWS to result.rows, API_FIELD_ROW_COUNT to result.rows.size),
                cancellationSignal)
        })
}

internal fun ExploreRoutes.registerCypherLifecycle(app: Javalin) {
    app.events { events -> events.serverStopping { cypherGuard.close() } }
}

internal fun <T : Any> ExploreRoutes.executeCypherAsync(
    ctx: Context,
    acquire: () -> List<GraphLease>,
    execute: (List<GraphLease>, CypherExecutionContext) -> T,
    respond: (T, CypherCancellationSignal) -> Unit
) {
    ctx.future { createCypherFuture(ctx, acquire, execute, respond) }
}

@Suppress("ReturnCount", "TooGenericExceptionCaught")
internal fun <T : Any> ExploreRoutes.createCypherFuture(
    ctx: Context,
    acquire: () -> List<GraphLease>,
    execute: (List<GraphLease>, CypherExecutionContext) -> T,
    respond: (T, CypherCancellationSignal) -> Unit
): CompletableFuture<Unit> {
    val clientTimeoutMillis =
        try {
            parseClientCypherTimeoutMillis(ctx)
        } catch (error: IllegalArgumentException) {
            respondCypherError(ctx, error)
            return CompletableFuture.completedFuture(Unit)
        }
    val cancellationSignal = cancellationSignalFactory()
    val clientCancellation = clientCancellationObserver(ctx, cancellationSignal)
    val leases =
        try {
            acquire()
        } catch (error: RuntimeException) {
            clientCancellation.close()
            respondCypherError(ctx, error)
            return CompletableFuture.completedFuture(Unit)
        }

    val task =
        try {
            cypherGuard.submit(
                cancellationSignal,
                clientTimeoutMillis = clientTimeoutMillis,
                continuation = { outcome ->
                    try {
                        val error = unwrapCompletionFailure(outcome.exceptionOrNull())
                        if (error == null) {
                            try {
                                respond(outcome.getOrThrow(), cancellationSignal)
                            } catch (cancelled: CypherQueryCancelledException) {
                                if (!clientCancellation.isClientDisconnected) {
                                    respondCypherError(ctx, cancelled)
                                }
                            }
                        } else if (error !is CypherQueryCancelledException || !clientCancellation.isClientDisconnected) {
                            respondCypherError(ctx, error)
                        }
                    } finally {
                        try {
                            leases.forEach { it.close() }
                        } finally {
                            clientCancellation.close()
                        }
                    }
                }) { executionContext ->
                    execute(leases, executionContext)
                }
        } catch (error: RuntimeException) {
            leases.forEach { it.close() }
            clientCancellation.close()
            respondCypherError(ctx, error)
            return CompletableFuture.completedFuture(Unit)
        }
    clientCancellation.bind(task)

    return task.completion.handle { _, failure ->
        val error = unwrapCompletionFailure(failure)
        if (error is CypherContinuationException) throw error.cause ?: error
        Unit
    }
}

internal fun ExploreRoutes.unwrapCompletionFailure(failure: Throwable?): Throwable? {
    var current = failure
    while (current is CompletionException && current.cause != null) current = current.cause
    return current
}

internal fun ExploreRoutes.queryAllGraphs(ctx: Context, acquire: (Context) -> List<GraphLease>) {
    val query = parseCypherQuery(ctx) ?: return
    val limit = boundedLimit(ctx, DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT)
    executeCypherAsync(
        ctx = ctx,
        acquire = { acquire(ctx) },
        execute = { leases, executionContext ->
            val result =
                CrossGraphCypherExecutor(leases.map { lease -> CypherGraph(lease.id, lease.graph) }, executionContext).execute(query, limit)
            leases.size to result
        },
        respond = { (graphCount, result), cancellationSignal ->
            cypherResponseSerializer.write(
                ctx,
                mapOf(
                    API_FIELD_COLUMNS to result.columns,
                    API_FIELD_ROWS to result.rows,
                    API_FIELD_ROW_COUNT to result.rows.size,
                    API_FIELD_GRAPH_COUNT to graphCount),
                cancellationSignal)
        })
}

internal fun ExploreRoutes.respondCypherError(ctx: Context, error: Throwable) {
    if (error is GraphNotLoadedException) {
        ctx.status(HTTP_NOT_FOUND).json(mapOf(API_FIELD_ERROR to error.message))
        return
    }
    val code =
        when (error) {
            is CypherConcurrencyLimitException -> "cypher_concurrency_limit"
            is CypherBudgetExceededException -> "cypher_work_budget_exceeded"
            is CypherQueryTimeoutException -> "cypher_query_timeout"
            is CypherQueryCancelledException -> "cypher_query_cancelled"
            else -> "cypher_query_failed"
        }
    val status =
        when (code) {
            "cypher_query_failed" -> HTTP_BAD_REQUEST
            "cypher_query_timeout" -> HTTP_GATEWAY_TIMEOUT
            "cypher_query_cancelled" -> HTTP_SERVICE_UNAVAILABLE
            else -> HTTP_TOO_MANY_REQUESTS
        }
    if (error is CypherConcurrencyLimitException) ctx.header("Retry-After", "1")
    ctx.status(status)
        .json(
            buildMap<String, Any?> {
                put(API_FIELD_ERROR, error.message ?: "Query execution failed")
                put("code", code)
                if (error is CypherQueryTimeoutException) put(API_PARAM_TIMEOUT_MILLIS, error.timeoutMillis)
            })
}

@Suppress("TooGenericExceptionCaught")
internal fun ExploreRoutes.queryLoadedGraphsFromRequest(ctx: Context, registry: GraphRegistry) {
    val request =
        try {
            parseMultiGraphCypherRequest(ctx)
        } catch (error: RuntimeException) {
            respondCypherError(ctx, error)
            return
        }
    val graphIds = if (request.allGraphs) registry.ids() else request.graphIds
    executeCypherAsync(
        ctx = ctx,
        acquire = { registry.acquireAll(graphIds) },
        execute = { leases, executionContext ->
            when (request.mode) {
                MultiGraphQueryMode.CROSS_GRAPH -> {
                    require(request.perGraphLimit == null) { "perGraphLimit is only valid in fanout mode" }
                    require(!request.includeGraphRows) { "includeGraphRows is only valid in fanout mode" }
                    val result =
                        CrossGraphCypherExecutor(
                                leases.map { lease -> CypherGraph(lease.id, lease.graph) },
                                executionContext,
                                graphSourceScopeApplied = !request.allGraphs)
                            .execute(request.query, request.limit)
                    mapOf(
                        API_PARAM_MODE to request.mode.wireName,
                        API_FIELD_GRAPHS to graphIds,
                        API_FIELD_GRAPH_COUNT to graphIds.size,
                        API_FIELD_COLUMNS to result.columns,
                        API_FIELD_ROWS to result.rows,
                        API_FIELD_ROW_COUNT to result.rows.size,
                        API_PARAM_LIMIT to request.limit)
                }
                MultiGraphQueryMode.FANOUT -> {
                    val perGraphLimit = request.perGraphLimit ?: defaultPerGraphLimit(graphIds.size, request.limit)
                    executeFanoutQuery(request, leases, perGraphLimit, executionContext)
                }
            }
        },
        respond = { response, cancellationSignal -> cypherResponseSerializer.write(ctx, response, cancellationSignal) })
}

internal fun ExploreRoutes.executeFanoutQuery(
    request: MultiGraphCypherRequest,
    leases: List<GraphLease>,
    perGraphLimit: Int,
    executionContext: CypherExecutionContext
): Map<String, Any?> {
    val results = mutableListOf<MultiGraphCypherResult>()
    var remainingRows = request.limit
    var truncated = false
    for (lease in leases) {
        if (remainingRows <= 0) {
            truncated = true
            break
        }
        val result = CypherExecutor(lease.graph, executionContext).execute(request.query, minOf(perGraphLimit, remainingRows))
        val rows = result.rows.map { rowWithGraphId(lease.id, it) }
        remainingRows -= rows.size
        truncated = truncated || remainingRows <= 0
        results += MultiGraphCypherResult(lease.id, result.columns, rows)
    }
    return buildMultiGraphCypherResponse(
        results = results,
        requestedGraphCount = leases.size,
        queriedGraphCount = results.size,
        perGraphLimit = perGraphLimit,
        limit = request.limit,
        includeGraphRows = request.includeGraphRows,
        truncated = truncated) + (API_PARAM_MODE to MultiGraphQueryMode.FANOUT.wireName)
}

internal fun ExploreRoutes.rowWithGraphId(graphId: String, row: Map<String, Any?>): Map<String, Any?> =
    linkedMapOf<String, Any?>(API_FIELD_GRAPH_ID to graphId).apply {
        putAll(row)
        put(API_FIELD_GRAPH_ID, graphId)
    }

internal fun ExploreRoutes.defaultPerGraphLimit(graphCount: Int, limit: Int): Int =
    if (graphCount <= 0) {
        0
    } else {
        ((limit + graphCount - 1) / graphCount).coerceIn(0, MAX_CYPHER_ROW_LIMIT)
    }

internal fun ExploreRoutes.buildMultiGraphCypherResponse(
    results: List<MultiGraphCypherResult>,
    requestedGraphCount: Int,
    queriedGraphCount: Int,
    perGraphLimit: Int,
    limit: Int,
    includeGraphRows: Boolean,
    truncated: Boolean
): Map<String, Any?> {
    val columns = linkedSetOf(API_FIELD_GRAPH_ID)
    results.forEach { columns.addAll(it.columns) }
    val rows = results.flatMap { it.rows }
    return mapOf(
        API_FIELD_COLUMNS to columns.toList(),
        API_FIELD_ROWS to rows,
        API_FIELD_ROW_COUNT to rows.size,
        API_FIELD_GRAPH_COUNT to requestedGraphCount,
        API_FIELD_QUERIED_GRAPH_COUNT to queriedGraphCount,
        API_FIELD_PER_GRAPH_LIMIT to perGraphLimit,
        API_PARAM_LIMIT to limit,
        API_FIELD_TRUNCATED to truncated,
        API_FIELD_GRAPHS to
            results.map {
                buildMap {
                    put(API_FIELD_GRAPH_ID, it.graphId)
                    put(API_FIELD_COLUMNS, it.columns)
                    put(API_FIELD_ROW_COUNT, it.rows.size)
                    if (includeGraphRows) put(API_FIELD_ROWS, it.rows)
                }
            })
}
