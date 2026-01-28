package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.core.HttpMethod
import io.johnsonlee.graphite.core.TypeHierarchyResult
import io.johnsonlee.graphite.core.TypeStructure
import io.johnsonlee.graphite.core.FieldStructure
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "find-endpoints",
    description = ["Find HTTP endpoints from Spring MVC annotations and analyze their return types."],
    mixinStandardHelpOptions = true
)
class FindEndpointsCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["Input path (JAR, WAR, Spring Boot JAR, or directory)"]
    )
    lateinit var input: Path

    @Option(
        names = ["-e", "--endpoint"],
        description = [
            "Endpoint path pattern to match. Supports wildcards:",
            "  * matches a single path segment (/users/* matches /users/123)",
            "  ** matches multiple segments (/api/** matches /api/users/123)",
            "  {id} path params are treated as wildcards"
        ]
    )
    var endpointPattern: String? = null

    @Option(
        names = ["-m", "--method"],
        description = ["HTTP method filter: GET, POST, PUT, DELETE, PATCH"]
    )
    var httpMethod: String? = null

    @Option(
        names = ["--include"],
        description = ["Package prefixes to include (comma-separated)"],
        split = ","
    )
    var includePackages: List<String> = emptyList()

    @Option(
        names = ["--exclude"],
        description = ["Package prefixes to exclude (comma-separated)"],
        split = ","
    )
    var excludePackages: List<String> = emptyList()

    @Option(
        names = ["-f", "--format"],
        description = ["Output format: text, schema (JSON Schema) (default: text)"],
        defaultValue = "text"
    )
    var outputFormat: String = "text"

    @Option(
        names = ["-v", "--verbose"],
        description = ["Enable verbose output"]
    )
    var verbose: Boolean = false

    @Option(
        names = ["--include-libs"],
        description = ["Include library JARs from WEB-INF/lib or BOOT-INF/lib"]
    )
    var includeLibs: Boolean? = null

    @Option(
        names = ["--lib-filter"],
        description = ["Only load JARs matching these patterns (comma-separated)"],
        split = ","
    )
    var libFilters: List<String> = emptyList()

    override fun call(): Int {
        if (!input.toFile().exists()) {
            System.err.println("Error: Input path does not exist: $input")
            return 1
        }

        if (verbose) {
            System.err.println("Loading bytecode from: $input")
            if (endpointPattern != null) {
                System.err.println("Endpoint pattern: $endpointPattern")
            }
            if (httpMethod != null) {
                System.err.println("HTTP method filter: $httpMethod")
            }
        }

        try {
            val shouldIncludeLibs = includeLibs ?: (input.toString().endsWith(".war") || input.toString().endsWith(".jar"))

            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = includePackages.ifEmpty { listOf("") },
                    excludePackages = excludePackages,
                    includeLibraries = shouldIncludeLibs,
                    libraryFilters = libFilters,
                    buildCallGraph = false,
                    verbose = if (verbose) { msg -> System.err.println(msg) } else null
                )
            )

            val graph = loader.load(input)

            val methodFilter = httpMethod?.let {
                try {
                    HttpMethod.valueOf(it.uppercase())
                } catch (e: IllegalArgumentException) {
                    System.err.println("Invalid HTTP method: $it. Valid values: GET, POST, PUT, DELETE, PATCH")
                    return 1
                }
            }

            val endpoints = graph.endpoints(endpointPattern, methodFilter).toList()

            if (endpoints.isEmpty()) {
                if (verbose) {
                    System.err.println("\nNo endpoints found matching the criteria")
                }
                if (outputFormat.lowercase() == "schema") {
                    println(formatJsonSchema(emptyList(), emptyMap()))
                }
                return 0
            }

            // Analyze return type hierarchy
            val graphite = io.johnsonlee.graphite.Graphite.from(graph)
            val returnTypes = endpoints.mapNotNull { endpoint ->
                val results = graphite.query {
                    findTypeHierarchy {
                        method {
                            declaringClass = endpoint.method.declaringClass.className
                            name = endpoint.method.name
                        }
                    }
                }
                results.firstOrNull()?.let { endpoint.path to it }
            }.toMap()

            val output = when (outputFormat.lowercase()) {
                "schema", "json" -> formatJsonSchema(endpoints, returnTypes)
                else -> formatText(endpoints, returnTypes)
            }

            println(output)
            return 0

        } catch (e: Exception) {
            System.err.println("Error during analysis: ${e.message}")
            if (verbose) {
                e.printStackTrace(System.err)
            }
            return 1
        }
    }

    private fun formatText(
        endpoints: List<io.johnsonlee.graphite.core.EndpointInfo>,
        returnTypes: Map<String, TypeHierarchyResult>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Found ${endpoints.size} endpoint(s):")
        sb.appendLine()

        // Group by base path
        val grouped = endpoints.groupBy { it.path.split("/").take(3).joinToString("/") }

        grouped.forEach { (basePath, eps) ->
            sb.appendLine(basePath)
            eps.sortedBy { it.path }.forEach { endpoint ->
                val method = endpoint.httpMethod.name.padEnd(7)
                val path = endpoint.path
                val handler = "${endpoint.method.declaringClass.simpleName}.${endpoint.method.name}()"

                sb.appendLine("  $method $path")
                sb.appendLine("          -> $handler")

                val declaredReturn = endpoint.method.returnType.className.substringAfterLast('.')
                sb.appendLine("          Declared: $declaredReturn")

                returnTypes[endpoint.path]?.let { result ->
                    result.returnStructures.forEach { structure ->
                        sb.appendLine("          Actual:   ${structure.formatTypeName()}")
                        formatTypeStructure(structure, sb, "                    ", mutableSetOf(), 0)
                    }
                }
            }
            sb.appendLine()
        }

        sb.appendLine("Summary: ${endpoints.size} endpoint(s)")
        return sb.toString()
    }

    private fun formatTypeStructure(
        structure: TypeStructure,
        sb: StringBuilder,
        indent: String,
        visited: MutableSet<String>,
        depth: Int
    ) {
        // Prevent infinite recursion
        if (depth > 10 || structure.className in visited) {
            if (structure.className in visited) {
                sb.appendLine("${indent}(circular reference to ${structure.simpleName})")
            }
            return
        }
        visited.add(structure.className)

        structure.fields.entries
            .filter { !it.value.isJsonIgnored }  // Filter out @JsonIgnore fields
            .sortedBy { it.value.effectiveJsonName }  // Sort by effective JSON name
            .forEach { (_, field) ->
                val displayName = field.effectiveJsonName  // Use @JsonProperty name if available
                sb.append(indent)
                sb.append("├── $displayName: ")
                formatFieldStructure(field, sb, "$indent│   ", visited.toMutableSet(), depth + 1)
            }
    }

    private fun formatFieldStructure(
        field: FieldStructure,
        sb: StringBuilder,
        indent: String,
        visited: MutableSet<String>,
        depth: Int
    ) {
        val declaredType = field.declaredType.simpleName
        if (field.actualTypes.isEmpty()) {
            sb.appendLine(declaredType)
        } else if (field.actualTypes.size == 1) {
            val actual = field.actualTypes.first()
            if (actual.type == field.declaredType) {
                sb.appendLine(actual.formatTypeName())
            } else {
                sb.appendLine("$declaredType → ${actual.formatTypeName()}")
            }
            formatTypeStructure(actual, sb, indent, visited.toMutableSet(), depth + 1)
        } else {
            sb.append(declaredType)
            sb.append(" → [")
            sb.append(field.actualTypes.joinToString(" | ") { it.formatTypeName() })
            sb.appendLine("]")
        }
    }

    /**
     * Format output as OpenAPI 3.0 specification.
     * Generates a complete OpenAPI document with:
     * - paths containing all endpoints
     * - components/schemas containing all type definitions
     */
    private fun formatJsonSchema(
        endpoints: List<io.johnsonlee.graphite.core.EndpointInfo>,
        returnTypes: Map<String, TypeHierarchyResult>
    ): String {
        val gson = GsonBuilder().setPrettyPrinting().create()

        // Collect all type definitions
        val schemas = mutableMapOf<String, Any>()
        val processedTypes = mutableSetOf<String>()

        // Build paths
        val paths = mutableMapOf<String, MutableMap<String, Any>>()

        endpoints.forEach { endpoint ->
            val pathItem = paths.getOrPut(endpoint.path) { mutableMapOf() }
            val method = endpoint.httpMethod.name.lowercase()

            val operation = mutableMapOf<String, Any>(
                "operationId" to "${endpoint.method.declaringClass.simpleName}_${endpoint.method.name}",
                "tags" to listOf(endpoint.method.declaringClass.simpleName)
            )

            // Build responses
            val responses = mutableMapOf<String, Any>()
            val successResponse = mutableMapOf<String, Any>(
                "description" to "Successful response"
            )

            returnTypes[endpoint.path]?.let { result ->
                if (result.returnStructures.isNotEmpty()) {
                    val contentType = endpoint.produces.firstOrNull() ?: "application/json"
                    val responseSchemas = result.returnStructures.map { structure ->
                        buildTypeSchema(structure, schemas, processedTypes, mutableSetOf(), 0)
                    }
                    val schema = if (responseSchemas.size == 1) {
                        responseSchemas.first()
                    } else {
                        mapOf("oneOf" to responseSchemas)
                    }
                    successResponse["content"] = mapOf(
                        contentType to mapOf("schema" to schema)
                    )
                }
            }

            responses["200"] = successResponse
            operation["responses"] = responses

            pathItem[method] = operation
        }

        val output = mutableMapOf<String, Any>(
            "openapi" to "3.0.3",
            "info" to mapOf(
                "title" to "API Documentation",
                "description" to "Generated from bytecode analysis by Graphite",
                "version" to "1.0.0"
            ),
            "paths" to paths
        )

        if (schemas.isNotEmpty()) {
            output["components"] = mapOf("schemas" to schemas)
        }

        return gson.toJson(output)
    }

    /**
     * Build OpenAPI schema for a TypeStructure.
     */
    private fun buildTypeSchema(
        structure: TypeStructure,
        schemas: MutableMap<String, Any>,
        processedTypes: MutableSet<String>,
        visited: MutableSet<String>,
        depth: Int
    ): Map<String, Any> {
        val typeName = structure.simpleName

        // Handle circular references
        if (depth > 10 || structure.className in visited) {
            return mapOf("\$ref" to "#/components/schemas/$typeName")
        }

        // For complex types, use $ref and add to schemas
        if (structure.fields.isNotEmpty() && typeName !in processedTypes) {
            processedTypes.add(typeName)
            visited.add(structure.className)

            val properties = mutableMapOf<String, Any>()

            structure.fields.entries
                .filter { !it.value.isJsonIgnored }
                .forEach { (_, field) ->
                    val fieldName = field.effectiveJsonName
                    properties[fieldName] = buildFieldSchema(field, schemas, processedTypes, visited.toMutableSet(), depth + 1)
                }

            val typeSchema = mutableMapOf<String, Any>(
                "type" to "object",
                "properties" to properties
            )

            // Add generic type info as description if available
            if (structure.typeArguments.isNotEmpty()) {
                typeSchema["description"] = structure.formatTypeName()
            }

            schemas[typeName] = typeSchema
            return mapOf("\$ref" to "#/components/schemas/$typeName")
        }

        // For types already processed, just return reference
        if (typeName in processedTypes) {
            return mapOf("\$ref" to "#/components/schemas/$typeName")
        }

        // Simple types without fields
        return mapOf(
            "type" to "object",
            "description" to structure.formatTypeName()
        )
    }

    /**
     * Build OpenAPI schema for a field.
     */
    private fun buildFieldSchema(
        field: FieldStructure,
        schemas: MutableMap<String, Any>,
        processedTypes: MutableSet<String>,
        visited: MutableSet<String>,
        depth: Int
    ): Map<String, Any> {
        val declaredType = field.declaredType.className

        // Map Java types to OpenAPI types
        return when {
            declaredType in listOf("int", "java.lang.Integer", "short", "java.lang.Short",
                "byte", "java.lang.Byte") -> {
                mapOf("type" to "integer", "format" to "int32")
            }
            declaredType in listOf("long", "java.lang.Long") -> {
                mapOf("type" to "integer", "format" to "int64")
            }
            declaredType in listOf("float", "java.lang.Float") -> {
                mapOf("type" to "number", "format" to "float")
            }
            declaredType in listOf("double", "java.lang.Double", "java.math.BigDecimal") -> {
                mapOf("type" to "number", "format" to "double")
            }
            declaredType in listOf("boolean", "java.lang.Boolean") -> {
                mapOf("type" to "boolean")
            }
            declaredType in listOf("java.lang.String", "char", "java.lang.Character") -> {
                mapOf("type" to "string")
            }
            declaredType == "java.util.Date" || declaredType == "java.time.LocalDate" -> {
                mapOf("type" to "string", "format" to "date")
            }
            declaredType == "java.time.LocalDateTime" || declaredType == "java.time.ZonedDateTime" ||
                declaredType == "java.time.Instant" -> {
                mapOf("type" to "string", "format" to "date-time")
            }
            declaredType.startsWith("java.util.List") || declaredType.startsWith("java.util.Collection") ||
                declaredType.startsWith("java.util.Set") || declaredType.endsWith("[]") -> {
                val itemSchema = if (field.actualTypes.isNotEmpty()) {
                    val actualType = field.actualTypes.first()
                    if (actualType.fields.isNotEmpty()) {
                        buildTypeSchema(actualType, schemas, processedTypes, visited, depth)
                    } else {
                        mapOf("type" to "object")
                    }
                } else {
                    mapOf("type" to "object")
                }
                mapOf("type" to "array", "items" to itemSchema)
            }
            declaredType.startsWith("java.util.Map") -> {
                mapOf("type" to "object", "additionalProperties" to mapOf("type" to "object"))
            }
            declaredType == "java.lang.Object" -> {
                // For Object fields, use the actual type if available
                if (field.actualTypes.isNotEmpty()) {
                    val actualType = field.actualTypes.first()
                    buildTypeSchema(actualType, schemas, processedTypes, visited, depth)
                } else {
                    mapOf("type" to "object")
                }
            }
            else -> {
                // Complex object type
                if (field.actualTypes.isNotEmpty()) {
                    val actualType = field.actualTypes.first()
                    buildTypeSchema(actualType, schemas, processedTypes, visited, depth)
                } else {
                    mapOf("type" to "object", "description" to field.declaredType.simpleName)
                }
            }
        }
    }
}
