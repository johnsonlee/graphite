package io.johnsonlee.graphite.cli

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.javalin.http.Context
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Path

internal fun ExploreRoutes.parseCypherQuery(ctx: Context): String? {
    val body = ctx.body().trim()
    val query =
        if (body.isNotEmpty()) {
            runCatching { JsonParser.parseString(body).asJsonObject.get(API_PARAM_QUERY)?.asString }.getOrNull()
        } else {
            null
        } ?: ctx.queryParam(API_PARAM_QUERY)
    if (query.isNullOrBlank()) {
        ctx.status(HTTP_BAD_REQUEST).result(API_ERROR_MISSING_QUERY)
        return null
    }
    return query.trim()
}

internal fun ExploreRoutes.parseClientCypherTimeoutMillis(ctx: Context): Long? {
    val body = ctx.body().trim()
    val bodyTimeout =
        if (body.isEmpty()) {
            null
        } else {
            JsonParser.parseString(body)
                .asJsonObject
                .get(API_PARAM_TIMEOUT_MILLIS)
                ?.takeUnless { it.isJsonNull }
                ?.let { value ->
                    require(value.isJsonPrimitive) { API_ERROR_INVALID_TIMEOUT }
                    value.asString
                }
        }
    val raw = bodyTimeout ?: ctx.queryParam(API_PARAM_TIMEOUT_MILLIS) ?: return null
    val timeoutMillis = raw.toLongOrNull() ?: throw IllegalArgumentException(API_ERROR_INVALID_TIMEOUT)
    require(timeoutMillis > 0) { API_ERROR_INVALID_TIMEOUT }
    return timeoutMillis
}

internal fun ExploreRoutes.loadGraphFromRequest(ctx: Context, registry: GraphRegistry, topology: TopologyService) {
    val id = ctx.pathParam(API_FIELD_GRAPH_ID)
    runCatching {
            val request = parseGraphLoadRequest(ctx)
            val descriptor = registry.load(id, request.path, request.loadMode ?: registry.defaultLoadMode) { topology.rebuild() }
            ctx.json(mapOf("graph" to descriptor.toApiMap()))
        }
        .onFailure { error -> ctx.status(HTTP_BAD_REQUEST).json(mapOf(API_FIELD_ERROR to error.message)) }
}

internal fun ExploreRoutes.parseGraphLoadRequest(ctx: Context): GraphLoadRequest {
    val body = ctx.body().trim()
    val path =
        if (body.isBlank()) {
            ctx.queryParam(API_FIELD_PATH) ?: error("Missing 'path' field")
        } else {
            JsonParser.parseString(body).asJsonObject.get(API_FIELD_PATH)?.asString ?: error("Missing 'path' field")
        }
    val loadMode =
        if (body.isBlank()) {
                ctx.queryParam(API_FIELD_LOAD_MODE)
            } else {
                JsonParser.parseString(body).asJsonObject.get(API_FIELD_LOAD_MODE)?.asString
            }
            ?.let { GraphStore.LoadMode.valueOf(it.uppercase()) }
    return GraphLoadRequest(Path.of(path), loadMode)
}

internal fun ExploreRoutes.parseMultiGraphCypherRequest(ctx: Context): MultiGraphCypherRequest {
    val body = ctx.body().trim()
    val json = if (body.isBlank()) null else JsonParser.parseString(body).asJsonObject
    val query =
        if (json == null) {
            ctx.queryParam(API_PARAM_QUERY) ?: error(API_ERROR_MISSING_QUERY)
        } else {
            json.get(API_PARAM_QUERY)?.asString ?: error(API_ERROR_MISSING_QUERY)
        }
    val graphIds = json?.let { parseGraphIdsFromJson(it) } ?: parseGraphIdsFromQuery(ctx)
    require(graphIds.distinct().size == graphIds.size) { "Graph ids must be unique" }
    val allGraphs = parseBoolean(firstJsonString(json, API_PARAM_ALL_GRAPHS) ?: firstQueryParam(ctx, API_PARAM_ALL_GRAPHS))
    require(allGraphs.xor(graphIds.isNotEmpty())) { "Specify exactly one of 'allGraphs=true' or a non-empty 'graphs' list" }
    val mode = MultiGraphQueryMode.parse(firstJsonString(json, API_PARAM_MODE) ?: firstQueryParam(ctx, API_PARAM_MODE))
    val perGraphLimit =
        optionalBoundedLimit(
            firstJsonString(json, API_PARAM_PER_GRAPH_LIMIT, API_FIELD_PER_GRAPH_LIMIT)
                ?: firstQueryParam(ctx, API_PARAM_PER_GRAPH_LIMIT, API_FIELD_PER_GRAPH_LIMIT),
            MAX_CYPHER_ROW_LIMIT)
    val limit =
        boundedLimit(
            firstJsonString(json, API_PARAM_LIMIT) ?: firstQueryParam(ctx, API_PARAM_LIMIT), DEFAULT_CYPHER_ROW_LIMIT, MAX_CYPHER_ROW_LIMIT)
    val includeGraphRows =
        parseBoolean(firstJsonString(json, API_PARAM_INCLUDE_GRAPH_ROWS) ?: firstQueryParam(ctx, API_PARAM_INCLUDE_GRAPH_ROWS))
    return MultiGraphCypherRequest(query.trim(), graphIds, allGraphs, mode, perGraphLimit, limit, includeGraphRows)
}

internal fun ExploreRoutes.parseGraphIdsFromJson(json: JsonObject): List<String> {
    val graphElement = json.get(API_FIELD_GRAPHS) ?: json.get(API_PARAM_GRAPH) ?: return emptyList()
    return when {
            graphElement.isJsonArray -> graphElement.asJsonArray.map { it.asString }
            graphElement.isJsonPrimitive -> splitGraphIds(graphElement.asString)
            else -> error("Invalid 'graphs' field")
        }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

internal fun ExploreRoutes.parseGraphIdsFromQuery(ctx: Context): List<String> {
    val params = ctx.queryParamMap()
    return (params[API_PARAM_GRAPH].orEmpty() + params[API_FIELD_GRAPHS].orEmpty())
        .flatMap(::splitGraphIds)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

internal fun ExploreRoutes.splitGraphIds(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

internal fun ExploreRoutes.firstJsonString(json: JsonObject?, vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> json?.get(name)?.takeUnless { it.isJsonNull }?.asString }

internal fun ExploreRoutes.firstQueryParam(ctx: Context, vararg names: String): String? = names.firstNotNullOfOrNull { ctx.queryParam(it) }

internal fun ExploreRoutes.parseBoolean(raw: String?): Boolean =
    when (raw?.trim()?.lowercase()) {
        "true",
        "1",
        "yes",
        "on" -> true
        else -> false
    }

internal fun ExploreRoutes.boundedLimit(ctx: io.javalin.http.Context, default: Int, max: Int): Int =
    boundedLimit(ctx.queryParam(API_PARAM_LIMIT), default, max)

internal fun ExploreRoutes.boundedLimit(raw: String?, default: Int, max: Int): Int = (raw?.toIntOrNull() ?: default).coerceIn(0, max)

internal fun ExploreRoutes.optionalBoundedLimit(raw: String?, max: Int): Int? = raw?.toIntOrNull()?.coerceIn(0, max)

internal fun ExploreRoutes.boundedDepth(raw: String?): Int = (raw?.toIntOrNull() ?: DEFAULT_SUBGRAPH_DEPTH).coerceAtMost(MAX_SUBGRAPH_DEPTH)

internal fun ExploreRoutes.resolveSubgraphDirection(raw: String?): SubgraphDirection? =
    when (raw?.lowercase()) {
        null,
        "both" -> SubgraphDirection.BOTH
        "outgoing" -> SubgraphDirection.OUTGOING
        "incoming" -> SubgraphDirection.INCOMING
        else -> null
    }
