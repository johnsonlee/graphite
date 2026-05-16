package io.johnsonlee.graphite.cli

internal class OpenApiSpecBuilder {
    internal fun build(): Map<String, Any?> = mapOf(
        "openapi" to "3.0.3",
        "info" to mapOf(
            "title" to "Graphite Explore API",
            "version" to "1.0.0",
            "description" to "Machine-readable API surface for Graphite Explore."
        ),
        "paths" to mapOf(
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
                    parameters = listOf(pathParameter(API_FIELD_ID, TYPE_INTEGER, API_OPENAPI_NODE_IDENTIFIER)),
                    responses = mapOf(
                        "200" to response("Outgoing edges"),
                        "400" to response(API_ERROR_INVALID_NODE_ID_OPENAPI)
                    )
                )
            ),
            "/api/node/{id}/incoming" to mapOf(
                "get" to operation(
                    "List incoming edges for a node",
                    parameters = listOf(pathParameter(API_FIELD_ID, TYPE_INTEGER, API_OPENAPI_NODE_IDENTIFIER)),
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
                        queryParameter(API_PARAM_DEPTH, TYPE_INTEGER, false, "Traversal depth")
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
                        queryParameter(API_PARAM_QUERY, TYPE_STRING, true, "Cypher query text")
                    ),
                    responses = mapOf(
                        "200" to response("Cypher result"),
                        "400" to response("Missing or invalid query")
                    )
                ),
                "post" to operation(
                    "Execute a Cypher query via JSON body",
                    parameters = emptyList(),
                    requestBody = mapOf(
                        FIELD_REQUIRED to true,
                        "content" to mapOf(
                            "application/json" to mapOf(
                                "schema" to mapOf(
                                    API_FIELD_TYPE to TYPE_OBJECT,
                                    "properties" to mapOf(
                                        API_PARAM_QUERY to mapOf(
                                            API_FIELD_TYPE to TYPE_STRING,
                                            FIELD_DESCRIPTION to "Cypher query text"
                                        )
                                    ),
                                    FIELD_REQUIRED to listOf(API_PARAM_QUERY)
                                )
                            )
                        )
                    ),
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
        private const val TYPE_INTEGER = "integer"
        private const val TYPE_OBJECT = "object"
        private const val TYPE_STRING = "string"
    }

}
