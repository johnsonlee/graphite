package io.johnsonlee.graphite.cli

@Suppress("StringLiteralDuplication")
internal class OpenApiSpecBuilder {
    internal fun build(): Map<String, Any?> = mapOf(
        "openapi" to "3.0.3",
        "info" to mapOf(
            "title" to "Graphite Explore API",
            "version" to "1.0.0",
            "description" to "Machine-readable API surface for Graphite Explore."
        ),
        "paths" to mapOf(
            "/api/graphs" to mapOf(
                "get" to operation(
                    "List loaded webgraphs",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("Loaded graph registry"))
                )
            ),
            "/api/graphs/{graphId}" to mapOf(
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
                        "404" to response("Graph not loaded")
                    )
                )
            ),
            "/api/cypher/graphs" to mapOf(
                "get" to operation(
                    "Execute a Cypher query across loaded graphs",
                    parameters = listOf(
                        queryParameter(API_PARAM_QUERY, TYPE_STRING, true, "Cypher query text"),
                        queryParameter(API_PARAM_GRAPH, TYPE_STRING, false, "Optional graph id filter; repeat or comma-separate"),
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
                        "404" to response("Requested graph not loaded")
                    )
                ),
                "post" to operation(
                    "Execute a Cypher query across loaded graphs",
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
                        "404" to response("Requested graph not loaded")
                    )
                )
            ),
            "/api/info" to mapOf(
                "get" to operation(
                    "Get graph summary statistics",
                    parameters = emptyList(),
                    responses = mapOf("200" to response("Graph statistics"))
                )
            ),
            "/api/nodes" to mapOf(
                "get" to operation(
                    "List graph nodes",
                    parameters = listOf(
                        queryParameter(API_PARAM_TYPE, TYPE_STRING, false, "Optional node label/type filter"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum number of nodes to return")
                    ),
                    responses = mapOf("200" to response("Node list"))
                )
            ),
            "/api/node/{id}" to mapOf(
                "get" to operation(
                    "Fetch a single node by id",
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
                    "List outgoing edges for a node",
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
                    "List incoming edges for a node",
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
            "/api/call-sites" to mapOf(
                "get" to operation(
                    "List call sites",
                    parameters = listOf(
                        queryParameter(API_PARAM_CLASS, TYPE_STRING, false, "Optional caller/callee class filter"),
                        queryParameter(API_PARAM_METHOD, TYPE_STRING, false, "Optional method name filter"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, API_OPENAPI_MAX_RESULTS)
                    ),
                    responses = mapOf("200" to response("Call site list"))
                )
            ),
            "/api/methods" to mapOf(
                "get" to operation(
                    "List methods",
                    parameters = listOf(
                        queryParameter(API_PARAM_CLASS, TYPE_STRING, false, "Optional declaring class filter"),
                        queryParameter(API_PARAM_NAME, TYPE_STRING, false, "Optional method name filter"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, API_OPENAPI_MAX_RESULTS)
                    ),
                    responses = mapOf("200" to response("Method list"))
                )
            ),
            "/api/annotations" to mapOf(
                "get" to operation(
                    "Fetch member annotations",
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
                    "List persisted resources",
                    parameters = listOf(
                        queryParameter(API_PARAM_PATTERN, TYPE_STRING, false, "Glob pattern, defaults to **"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, API_OPENAPI_MAX_RESULTS)
                    ),
                    responses = mapOf("200" to response("Resource listing"))
                )
            ),
            "/api/resources/{path}" to mapOf(
                "get" to operation(
                    "Read persisted raw resource content",
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
            "/api/api-spec" to mapOf(
                "get" to operation(
                    "Extract framework API endpoints from the graph",
                    parameters = listOf(
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum number of endpoints"),
                        queryParameter(API_PARAM_CLASS, TYPE_STRING, false, "Optional controller class filter")
                    ),
                    responses = mapOf("200" to response("Extracted framework API specification"))
                )
            ),
            "/api/architecture/c4" to mapOf(
                "get" to operation(
                    "Build C4 architecture views automatically derived from the code graph as Structurizr workspace JSON, Structurizr DSL, Mermaid, or PlantUML",
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
                    "Build a class-level overview graph",
                    parameters = listOf(
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Maximum number of classes")
                    ),
                    responses = mapOf("200" to response("Overview graph"))
                )
            ),
            "/api/subgraph" to mapOf(
                "get" to operation(
                    "Build a local subgraph around a node",
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
                    "Execute a Cypher query via query string",
                    parameters = listOf(
                        queryParameter(API_PARAM_QUERY, TYPE_STRING, true, "Cypher query text"),
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Server-side maximum result rows")
                    ),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query")
                    )
                ),
                "post" to operation(
                    "Execute a Cypher query via JSON body",
                    parameters = listOf(
                        queryParameter(API_PARAM_LIMIT, TYPE_INTEGER, false, "Server-side maximum result rows")
                    ),
                    requestBody = cypherRequestBody(),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query")
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
    )

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
                    FIELD_DESCRIPTION to "Optional graph ids to query; omitted means all loaded graphs",
                    "items" to mapOf(API_FIELD_TYPE to TYPE_STRING)
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
    }

}
