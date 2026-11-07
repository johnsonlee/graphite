package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Path

internal const val API_ROOT = "/api"
internal const val HTTP_NO_CONTENT = 204
internal const val HTTP_BAD_REQUEST = 400
internal const val HTTP_CONFLICT = 409
internal const val HTTP_NOT_FOUND = 404
internal const val HTTP_PAYLOAD_TOO_LARGE = 413
internal const val HTTP_TOO_MANY_REQUESTS = 429
internal const val HTTP_GATEWAY_TIMEOUT = 504
internal const val HTTP_SERVICE_UNAVAILABLE = 503

internal const val DEFAULT_EDGE_LIMIT = 200
internal const val DEFAULT_RESOURCE_LIMIT = 100
internal const val DEFAULT_ENDPOINT_LIMIT = 200
internal const val DEFAULT_OVERVIEW_LIMIT = 200
internal const val DEFAULT_CYPHER_ROW_LIMIT = 1_000
internal const val DEFAULT_SUBGRAPH_DEPTH = 2
internal const val MAX_EDGE_LIMIT = 2_000
internal const val MAX_RESOURCE_LIMIT = 1_000
internal const val MAX_ENDPOINT_LIMIT = 2_000
internal const val MAX_OVERVIEW_LIMIT = 1_000
internal const val MAX_OVERVIEW_CALL_SITES = 100_000
internal const val MAX_OVERVIEW_CLASSES = 20_000
internal const val MAX_OVERVIEW_EDGES = 50_000
internal const val MAX_CYPHER_ROW_LIMIT = 5_000
internal const val MAX_SUBGRAPH_DEPTH = 4
internal const val MAX_SUBGRAPH_NODES = 2_000
internal const val MAX_SUBGRAPH_EDGES = 5_000
internal const val MAX_RESOURCE_BYTES = 1_048_576
internal const val API_ERROR_RESOURCE_TOO_LARGE = "Resource exceeds maximum response size"

internal class ResourceTooLargeException : RuntimeException()

internal data class GraphLoadRequest(val path: Path, val loadMode: GraphStore.LoadMode?)

internal data class MultiGraphCypherRequest(
    val query: String,
    val graphIds: List<String>,
    val allGraphs: Boolean,
    val mode: MultiGraphQueryMode,
    val perGraphLimit: Int?,
    val limit: Int,
    val includeGraphRows: Boolean
)

internal data class MultiGraphCypherResult(val graphId: String, val columns: List<String>, val rows: List<Map<String, Any?>>)

internal enum class MultiGraphQueryMode(val wireName: String) {
    CROSS_GRAPH("cross-graph"),
    FANOUT("fanout");

    companion object {
        fun parse(value: String?): MultiGraphQueryMode =
            when (value?.trim()?.lowercase()) {
                null,
                "",
                "cross-graph",
                "cross_graph",
                "crossgraph" -> CROSS_GRAPH
                "fanout",
                "fan-out",
                "fan_out" -> FANOUT
                else -> error("Invalid query mode '$value'. Expected 'cross-graph' or 'fanout'")
            }
    }
}

internal enum class SubgraphDirection(val includeOutgoing: Boolean, val includeIncoming: Boolean) {
    OUTGOING(includeOutgoing = true, includeIncoming = false),
    INCOMING(includeOutgoing = false, includeIncoming = true),
    BOTH(includeOutgoing = true, includeIncoming = true)
}

internal class SubgraphFrame(val candidates: Iterator<Edge>, val nextNode: (Edge) -> NodeId, val remaining: Int)

internal const val API_FIELD_GRAPH_COUNT = "graphCount"
internal const val API_GRAPH_PATH = "$API_ROOT/graphs/{$API_FIELD_GRAPH_ID}"
internal const val API_ERROR_MISSING_QUERY = "Missing 'query' parameter"
internal const val API_ERROR_INVALID_TIMEOUT = "'$API_PARAM_TIMEOUT_MILLIS' must be a positive integer"
