package io.johnsonlee.graphite.cli

@Suppress("StringLiteralDuplication")
internal class OpenApiSpecBuilder {
    internal fun build(): Map<String, Any?> {
        val paths = linkedMapOf<String, Any?>(
            "/api/topology" to mapOf(
                "get" to operation(
                    "Get the startup-built graph-to-graph call topology",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("Topology graph with loaded graphs and aggregated call relations"))
                )
            ),
            "/api/graphs" to mapOf(
                "get" to operation(
                    "List loaded webgraphs with cached statistics and aggregate totals",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("Loaded graph registry, per-graph statistics, and totals"))
                )
            ),
            "/api/graphs/{graphId}" to mapOf(
                "get" to operation(
                    "Get metadata and cached statistics for one graph",
                    parameters = listOf(pathParameter(API_FIELD_GRAPH_ID, TYPE_STRING, "Unique graph id")),
                    responses = mapOf(
                        "200" to response("Loaded graph descriptor and statistics"),
                        "404" to response("Graph not loaded")
                    )
                ),
                "put" to operation(
                    "Load or replace a webgraph by id",
                    parameters = listOf(pathParameter(API_FIELD_GRAPH_ID, TYPE_STRING, "Unique graph id")),
                    requestBody = graphLoadRequestBody(),
                    responses = mapOf(
                        "200" to response("Loaded graph descriptor"),
                        "400" to response("Invalid graph id, path, or graph payload")
                    )
                ),
                "post" to operation(
                    "Load or replace a webgraph by id",
                    parameters = listOf(pathParameter(API_FIELD_GRAPH_ID, TYPE_STRING, "Unique graph id")),
                    requestBody = graphLoadRequestBody(),
                    responses = mapOf(
                        "200" to response("Loaded graph descriptor"),
                        "400" to response("Invalid graph id, path, or graph payload")
                    )
                ),
                "delete" to operation(
                    "Unload a webgraph by id",
                    parameters = listOf(pathParameter(API_FIELD_GRAPH_ID, TYPE_STRING, "Unique graph id")),
                    responses = mapOf(
                        "204" to response("Graph unloaded"),
                        "404" to response("Graph not loaded")
                    )
                )
            ),
            "/api/graphs/{graphId}/cypher" to mapOf(
                "get" to operation(
                    "Execute a Cypher query against a specific graph",
                    parameters = listOf(
                        pathParameter(API_FIELD_GRAPH_ID, TYPE_STRING, "Unique graph id"),
                        queryParameter(API_PARAM_QUERY, TYPE_STRING, true, "Cypher query text"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Server-side maximum result rows")
                    ),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query"),
                        "429" to response("Cypher concurrency or work budget exceeded"),
                        "404" to response("Graph not loaded")
                    )
                ),
                "post" to operation(
                    "Execute a Cypher query against a specific graph",
                    parameters = listOf(
                        pathParameter(API_FIELD_GRAPH_ID, TYPE_STRING, "Unique graph id"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Server-side maximum result rows")
                    ),
                    requestBody = cypherRequestBody(),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query"),
                        "429" to response("Cypher concurrency or work budget exceeded"),
                        "404" to response("Graph not loaded")
                    )
                )
            ),
            "/api/cypher/graphs" to mapOf(
                "get" to operation(
                    "Execute one cross-graph query or explicit fanout over selected graphs",
                    parameters = listOf(
                        queryParameter(API_PARAM_QUERY, TYPE_STRING, true, "Cypher query text"),
                        queryParameter(API_PARAM_ALL_GRAPHS, TYPE_BOOLEAN, false, "Query all loaded graphs; mutually exclusive with graph"),
                        queryParameter(API_PARAM_GRAPH, TYPE_STRING, false, "Graph id selection; repeat or comma-separate"),
                        queryParameter(API_PARAM_MODE, TYPE_STRING, false, "cross-graph (default) or fanout"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum total result rows across queried graphs"),
                        queryParameter(API_PARAM_PER_GRAPH_LIMIT, TYPE_INTEGER, false, "Optional maximum result rows per graph"),
                        queryParameter(
                            API_PARAM_INCLUDE_GRAPH_ROWS,
                            TYPE_BOOLEAN,
                            false,
                            "Include duplicate per-graph row arrays in graph summaries"
                        )
                    ),
                    responses = mapOf(
                        "200" to response("Multi-graph Cypher result with graphId-tagged rows"),
                        "400" to response("Missing or invalid query"),
                        "429" to response("Cypher concurrency or work budget exceeded"),
                        "404" to response("Requested graph not loaded")
                    )
                ),
                "post" to operation(
                    "Execute one cross-graph query or explicit fanout over selected graphs",
                    parameters = listOf(
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum total result rows across queried graphs"),
                        queryParameter(API_PARAM_PER_GRAPH_LIMIT, TYPE_INTEGER, false, "Optional maximum result rows per graph"),
                        queryParameter(
                            API_PARAM_INCLUDE_GRAPH_ROWS,
                            TYPE_BOOLEAN,
                            false,
                            "Include duplicate per-graph row arrays in graph summaries"
                        )
                    ),
                    requestBody = multiGraphCypherRequestBody(),
                    responses = mapOf(
                        "200" to response("Multi-graph Cypher result with graphId-tagged rows"),
                        "400" to response("Missing or invalid query"),
                        "429" to response("Cypher concurrency or work budget exceeded"),
                        "404" to response("Requested graph not loaded")
                    )
                )
            ),
            "/api/node/{id}" to mapOf(
                "get" to operation(
                    "Fetch every graph-local node with this id, grouped by graphId",
                    parameters = listOf(
                        pathParameter(API_FIELD_ID, TYPE_INTEGER, API_OPENAPI_NODE_IDENTIFIER)
                    ),
                    responses = mapOf(
                        "200" to response("Node payload"),
                        "400" to response(API_ERROR_INVALID_NODE_ID_OPENAPI),
                        "404" to response(API_ERROR_NODE_NOT_FOUND)
                    )
                )
            ),
            "/api/node/{id}/outgoing" to mapOf(
                "get" to operation(
                    "List outgoing edges for this local node id, grouped by graphId",
                    parameters = listOf(
                        pathParameter(API_FIELD_ID, TYPE_INTEGER, API_OPENAPI_NODE_IDENTIFIER),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum number of edges to return")
                    ),
                    responses = mapOf(
                        "200" to response("Outgoing edges"),
                        "400" to response(API_ERROR_INVALID_NODE_ID_OPENAPI)
                    )
                )
            ),
            "/api/node/{id}/incoming" to mapOf(
                "get" to operation(
                    "List incoming edges for this local node id, grouped by graphId",
                    parameters = listOf(
                        pathParameter(API_FIELD_ID, TYPE_INTEGER, API_OPENAPI_NODE_IDENTIFIER),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum number of edges to return")
                    ),
                    responses = mapOf(
                        "200" to response("Incoming edges"),
                        "400" to response(API_ERROR_INVALID_NODE_ID_OPENAPI)
                    )
                )
            ),
            "/api/methods" to mapOf(
                "get" to operation(
                    "List declared methods across all loaded graphs, grouped by graphId",
                    parameters = listOf(
                        queryParameter(API_PARAM_CLASS, TYPE_STRING, false, "Optional declaring class filter"),
                        queryParameter(API_PARAM_NAME, TYPE_STRING, false, "Optional method name filter"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, API_OPENAPI_MAX_RESULTS)
                    ),
                    responses = mapOf("200" to response("Method list with declared return types"))
                )
            ),
            "/api/annotations" to mapOf(
                "get" to operation(
                    "Fetch member annotations across all loaded graphs, grouped by graphId",
                    parameters = listOf(
                        queryParameter(API_PARAM_CLASS, TYPE_STRING, true, "Declaring class name"),
                        queryParameter(API_PARAM_MEMBER, TYPE_STRING, true, "Member name")
                    ),
                    responses = mapOf(
                        "200" to response("Annotation map"),
                        "400" to response("Missing required parameters")
                    )
                )
            ),
            "/api/resources" to mapOf(
                "get" to operation(
                    "List persisted resources across all loaded graphs, grouped by graphId",
                    parameters = listOf(
                        queryParameter(API_PARAM_PATTERN, TYPE_STRING, false, "Glob pattern, defaults to **"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, API_OPENAPI_MAX_RESULTS)
                    ),
                    responses = mapOf("200" to response("Resource listing"))
                )
            ),
            "/api/resources/{path}" to mapOf(
                "get" to operation(
                    "Read every matching persisted resource, grouped by graphId",
                    parameters = listOf(
                        pathParameter(API_FIELD_PATH, TYPE_STRING, "Resource path inside the graph payload; may include nested segments")
                    ),
                    responses = mapOf(
                        "200" to response("Raw resource content"),
                        "413" to response("Resource exceeds maximum response size"),
                        "404" to response(API_ERROR_RESOURCE_NOT_FOUND)
                    )
                )
            ),
            "/api/endpoints" to mapOf(
                "get" to operation(
                    "Extract framework API endpoints across all loaded graphs, grouped by graphId",
                    parameters = listOf(
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum number of endpoints"),
                        queryParameter(API_PARAM_CLASS, TYPE_STRING, false, "Optional controller class filter")
                    ),
                    responses = mapOf("200" to response("Extracted framework HTTP endpoints"))
                )
            ),
            "/api/architecture/c4" to mapOf(
                "get" to operation(
                    "Build C4 architecture views for all loaded graphs, grouped by graphId for JSON",
                    parameters = listOf(
                        queryParameter(API_PARAM_LEVEL, TYPE_STRING, false, "context, container, component, or all"),
                        queryParameter(API_PARAM_FORMAT, TYPE_STRING, false, "json, dsl, mermaid, or plantuml")
                    ),
                    responses = mapOf(
                        "200" to response("Structurizr workspace JSON, Structurizr DSL, Mermaid text, or PlantUML text"),
                        "400" to response("Invalid level or format parameter")
                    )
                )
            ),
            "/api/overview" to mapOf(
                "get" to operation(
                    "Build class-level overviews for all loaded graphs, grouped by graphId",
                    parameters = listOf(
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum number of classes")
                    ),
                    responses = mapOf("200" to response("Overview graph"))
                )
            ),
            "/api/subgraph" to mapOf(
                "get" to operation(
                    "Build local subgraphs for this graph-local node id, grouped by graphId",
                    parameters = listOf(
                        queryParameter(API_PARAM_CENTER, TYPE_INTEGER, true, "Center node id"),
                        queryParameter(API_PARAM_DEPTH, TYPE_INTEGER, false, "Traversal depth"),
                        queryParameter(API_PARAM_DIRECTION, TYPE_STRING, false, "outgoing, incoming, or both")
                    ),
                    responses = mapOf(
                        "200" to response("Subgraph"),
                        "400" to response("Missing or invalid parameters")
                    )
                )
            ),
            "/api/cypher" to mapOf(
                "get" to operation(
                    "Execute one query over the union of all loaded graphs",
                    parameters = listOf(
                        queryParameter(API_PARAM_QUERY, TYPE_STRING, true, "Cypher query text"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Server-side maximum result rows")
                    ),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query"),
                        "429" to response("Cypher concurrency or work budget exceeded")
                    )
                ),
                "post" to operation(
                    "Execute one query over the union of all loaded graphs",
                    parameters = listOf(
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Server-side maximum result rows")
                    ),
                    requestBody = cypherRequestBody(),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query"),
                        "429" to response("Cypher concurrency or work budget exceeded")
                    )
                )
            ),
            "/openapi.json" to mapOf(
                "get" to operation(
                    "Fetch this OpenAPI document",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("OpenAPI specification"))
                )
            ),
            "/swagger.json" to mapOf(
                "get" to operation(
                    "Fetch this API document via Swagger-compatible alias",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("OpenAPI specification"))
                )
            )
        )
        addGraphScopedPaths(paths)
        GRAPH_LOCAL_ID_PATHS.forEach(paths::remove)
        return mapOf(
            "openapi" to "3.0.3",
            "info" to mapOf(
                "title" to "Graphite Explore API",
                "version" to GraphiteVersionProvider.currentVersion(),
                "description" to "Graph-qualified single-graph and cross-graph query API for Graphite Explore."
            ),
            "paths" to paths
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun addGraphScopedPaths(paths: MutableMap<String, Any?>) {
        val graphBoundRoots = listOf(
            "/api/node/{id}",
            "/api/node/{id}/outgoing",
            "/api/node/{id}/incoming",
            "/api/methods",
            "/api/annotations",
            "/api/resources",
            "/api/resources/{path}",
            "/api/endpoints",
            "/api/architecture/c4",
            "/api/overview",
            "/api/subgraph"
        )
        graphBoundRoots.forEach { rootPath ->
            val scopedPath = "/api/graphs/{graphId}" + rootPath.removePrefix("/api")
            val operations = paths.getValue(rootPath) as Map<String, Map<String, Any?>>
            paths[scopedPath] = operations.mapValues { (_, operation) ->
                operation.toMutableMap().apply {
                    val parameters = (this["parameters"] as? List<Map<String, Any?>>).orEmpty()
                    this["parameters"] = listOf(
                        pathParameter(API_FIELD_GRAPH_ID, TYPE_STRING, "Unique graph id")
                    ) + parameters
                    this["summary"] = scopedSummary(rootPath)
                }
            }
        }
    }

    private fun scopedSummary(rootPath: String): String = when (rootPath) {
        "/api/node/{id}" -> "Fetch a node by graph-local id in one explicit graph"
        "/api/node/{id}/outgoing" -> "List outgoing edges in one explicit graph"
        "/api/node/{id}/incoming" -> "List incoming edges in one explicit graph"
        "/api/methods" -> "List declared methods in one explicit graph"
        "/api/annotations" -> "Fetch member annotations in one explicit graph"
        "/api/resources" -> "List persisted resources in one explicit graph"
        "/api/resources/{path}" -> "Read persisted resource content in one explicit graph"
        "/api/endpoints" -> "Extract framework API endpoints from one explicit graph"
        "/api/architecture/c4" -> "Build C4 architecture views for one explicit graph"
        "/api/overview" -> "Build a class-level overview for one explicit graph"
        "/api/subgraph" -> "Build a local subgraph in one explicit graph"
        else -> error("Unknown graph-bound route: $rootPath")
    }

    private fun operation(
        summary: String,
        parameters: List<Map<String, Any?>>,
        responses: Map<String, Any?>,
        requestBody: Map<String, Any?>? = null
    ): Map<String, Any?> = buildMap {
        put("summary", summary)
        put("responses", responses)
        if (parameters.isNotEmpty()) put("parameters", parameters)
        if (requestBody != null) put("requestBody", requestBody)
    }

    private fun cypherRequestBody(): Map<String, Any?> =
        objectRequestBody(
            mapOf(
                API_PARAM_QUERY to mapOf(
                    API_FIELD_TYPE to TYPE_STRING,
                    FIELD_DESCRIPTION to "Cypher query text"
                )
            ),
            listOf(API_PARAM_QUERY)
        )

    private fun graphLoadRequestBody(): Map<String, Any?> =
        objectRequestBody(
            mapOf(
                API_FIELD_PATH to mapOf(
                    API_FIELD_TYPE to TYPE_STRING,
                    FIELD_DESCRIPTION to "Absolute graph path, or path relative to --data"
                ),
                API_FIELD_LOAD_MODE to mapOf(
                    API_FIELD_TYPE to TYPE_STRING,
                    FIELD_DESCRIPTION to "Optional graph load mode: EAGER, MAPPED, or AUTO"
                )
            ),
            listOf(API_FIELD_PATH)
        )

    private fun multiGraphCypherRequestBody(): Map<String, Any?> =
        objectRequestBody(
            mapOf(
                API_PARAM_QUERY to mapOf(
                    API_FIELD_TYPE to TYPE_STRING,
                    FIELD_DESCRIPTION to "Cypher query text"
                ),
                API_FIELD_GRAPHS to mapOf(
                    API_FIELD_TYPE to "array",
                    FIELD_DESCRIPTION to "Explicit graph ids to query; mutually exclusive with allGraphs",
                    "items" to mapOf(API_FIELD_TYPE to TYPE_STRING)
                ),
                API_PARAM_ALL_GRAPHS to mapOf(
                    API_FIELD_TYPE to TYPE_BOOLEAN,
                    FIELD_DESCRIPTION to "Query all loaded graphs; mutually exclusive with graphs"
                ),
                API_PARAM_MODE to mapOf(
                    API_FIELD_TYPE to TYPE_STRING,
                    FIELD_DESCRIPTION to "cross-graph (default) or fanout"
                ),
                API_PARAM_LIMIT to mapOf(
                    API_FIELD_TYPE to TYPE_INTEGER,
                    FIELD_DESCRIPTION to "Maximum total result rows across queried graphs"
                ),
                API_PARAM_PER_GRAPH_LIMIT to mapOf(
                    API_FIELD_TYPE to TYPE_INTEGER,
                    FIELD_DESCRIPTION to "Optional maximum result rows per graph"
                ),
                API_PARAM_INCLUDE_GRAPH_ROWS to mapOf(
                    API_FIELD_TYPE to TYPE_BOOLEAN,
                    FIELD_DESCRIPTION to "Include duplicate per-graph row arrays in graph summaries"
                )
            ),
            listOf(API_PARAM_QUERY)
        )

    private fun objectRequestBody(properties: Map<String, Any?>, required: List<String>): Map<String, Any?> =
        mapOf(
            FIELD_REQUIRED to true,
            "content" to mapOf(
                "application/json" to mapOf(
                    "schema" to mapOf(
                        API_FIELD_TYPE to TYPE_OBJECT,
                        "properties" to properties,
                        FIELD_REQUIRED to required
                    )
                )
            )
        )

    private fun queryParameter(name: String, type: String, required: Boolean, description: String): Map<String, Any?> =
        parameter("query", name, type, required, description)

    private fun pathParameter(name: String, type: String, description: String): Map<String, Any?> =
        parameter("path", name, type, true, description)

    private fun parameter(location: String, name: String, type: String, required: Boolean, description: String): Map<String, Any?> =
        mapOf(
            "in" to location,
            API_FIELD_NAME to name,
            FIELD_REQUIRED to required,
            FIELD_DESCRIPTION to description,
            "schema" to mapOf(API_FIELD_TYPE to type)
        )

    private fun response(description: String): Map<String, Any?> =
        mapOf(FIELD_DESCRIPTION to description)

    companion object {
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_REQUIRED = "required"
        private const val TYPE_BOOLEAN = "boolean"
        private const val TYPE_INTEGER = "integer"
        private const val TYPE_OBJECT = "object"
        private const val TYPE_STRING = "string"
        private val GRAPH_LOCAL_ID_PATHS = listOf(
            "/api/node/{id}",
            "/api/node/{id}/outgoing",
            "/api/node/{id}/incoming",
            "/api/subgraph"
        )
    }

}
