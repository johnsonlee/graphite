package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeHierarchyResult
import io.johnsonlee.graphite.core.TypeStructure
import io.johnsonlee.graphite.core.FieldStructure
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

// ========================================================================
// CLI-local endpoint types (formerly in graphite-core)
// ========================================================================

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, ANY
}

data class EndpointInfo(
    val method: MethodDescriptor,
    val httpMethod: HttpMethod,
    val path: String,
    val produces: List<String> = emptyList(),
    val consumes: List<String> = emptyList()
) {
    val fullPath: String = path

    fun matchesPattern(pattern: String): Boolean {
        val normalizedPath = normalizePath(path)
        val normalizedPattern = normalizePath(pattern)
        return matchPath(normalizedPath.split("/"), normalizedPattern.split("/"))
    }

    private fun normalizePath(p: String): String {
        return p.trim('/').replace(Regex("\\{[^}]+}"), "*")
    }

    private fun matchPath(pathParts: List<String>, patternParts: List<String>): Boolean {
        var pi = 0
        var pati = 0

        while (pi < pathParts.size && pati < patternParts.size) {
            val patternPart = patternParts[pati]

            when {
                patternPart == "**" -> {
                    if (pati == patternParts.size - 1) {
                        return true
                    }
                    for (i in pi..pathParts.size) {
                        if (matchPath(pathParts.drop(i), patternParts.drop(pati + 1))) {
                            return true
                        }
                    }
                    return false
                }
                patternPart == "*" -> {
                    pi++
                    pati++
                }
                patternPart == pathParts[pi] -> {
                    pi++
                    pati++
                }
                else -> return false
            }
        }

        while (pati < patternParts.size && patternParts[pati] == "**") {
            pati++
        }

        return pi == pathParts.size && pati == patternParts.size
    }
}

// ========================================================================
// Annotation-based endpoint discovery
// ========================================================================

private const val SPRING_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping"
private const val SPRING_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping"
private const val SPRING_POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping"
private const val SPRING_PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping"
private const val SPRING_DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping"
private const val SPRING_PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping"

private const val SPRING_CONTROLLER = "org.springframework.stereotype.Controller"
private const val SPRING_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController"

private val MAPPING_ANNOTATIONS = setOf(
    SPRING_REQUEST_MAPPING, SPRING_GET_MAPPING, SPRING_POST_MAPPING,
    SPRING_PUT_MAPPING, SPRING_DELETE_MAPPING, SPRING_PATCH_MAPPING
)

private val MAPPING_TO_HTTP_METHOD = mapOf(
    SPRING_GET_MAPPING to HttpMethod.GET,
    SPRING_POST_MAPPING to HttpMethod.POST,
    SPRING_PUT_MAPPING to HttpMethod.PUT,
    SPRING_DELETE_MAPPING to HttpMethod.DELETE,
    SPRING_PATCH_MAPPING to HttpMethod.PATCH,
    SPRING_REQUEST_MAPPING to HttpMethod.ANY
)

private const val CLASS_LEVEL_MEMBER = "<class>"

/**
 * Discover HTTP endpoints from annotations stored in the graph.
 *
 * When a subclass inherits endpoint methods from a parent controller,
 * the subclass gets endpoints using its own class-level @RequestMapping path.
 */
internal fun discoverEndpoints(
    graph: Graph,
    pattern: String? = null,
    httpMethodFilter: HttpMethod? = null
): List<EndpointInfo> {
    val endpoints = mutableListOf<EndpointInfo>()

    // Group methods by declaring class to look up class-level annotations once
    val methodsByClass = graph.methods(MethodPattern()).groupBy { it.declaringClass.className }

    // Find controller classes (those with controller/mapping annotations at class level)
    val controllerClasses = mutableSetOf<String>()
    for ((className, _) in methodsByClass) {
        val classAnnotations = graph.memberAnnotations(className, CLASS_LEVEL_MEMBER)
        if (classAnnotations.keys.any { it in MAPPING_ANNOTATIONS || it == SPRING_CONTROLLER || it == SPRING_REST_CONTROLLER }) {
            controllerClasses.add(className)
        }
    }

    // Also include classes that have methods with mapping annotations
    for ((className, methods) in methodsByClass) {
        if (className in controllerClasses) continue
        for (method in methods) {
            val methodAnnotations = graph.memberAnnotations(className, method.name)
            if (findHttpMethod(methodAnnotations) != null) {
                controllerClasses.add(className)
                break
            }
        }
    }

    // For each controller, collect own methods + inherited methods from supertypes
    for (className in controllerClasses) {
        val classAnnotations = graph.memberAnnotations(className, CLASS_LEVEL_MEMBER)
        val classPath = classAnnotations[SPRING_REQUEST_MAPPING]?.let { extractPath(it) } ?: ""

        // Collect methods: own + inherited
        val allMethods = mutableListOf<MethodDescriptor>()
        methodsByClass[className]?.let { allMethods.addAll(it) }
        collectSupertypeMethods(graph, className, methodsByClass, allMethods, mutableSetOf())

        for (method in allMethods) {
            val methodAnnotations = graph.memberAnnotations(method.declaringClass.className, method.name)

            // Find the first Spring mapping annotation
            val (annotationFqn, httpMethod) = findHttpMethod(methodAnnotations) ?: continue
            val annotationValues = methodAnnotations[annotationFqn] ?: emptyMap()
            val methodPath = extractPath(annotationValues)
            val fullPath = combinePaths(classPath, methodPath)

            val produces = annotationValues["produces"]?.let { listOf(it.toString()) } ?: emptyList()
            val consumes = annotationValues["consumes"]?.let { listOf(it.toString()) } ?: emptyList()

            endpoints.add(EndpointInfo(
                method = method,
                httpMethod = httpMethod,
                path = fullPath,
                produces = produces,
                consumes = consumes
            ))
        }
    }

    return endpoints.filter { endpoint ->
        val pathMatches = pattern == null || endpoint.matchesPattern(pattern)
        val methodMatches = httpMethodFilter == null || endpoint.httpMethod == httpMethodFilter || endpoint.httpMethod == HttpMethod.ANY
        pathMatches && methodMatches
    }
}

/**
 * Recursively collect methods from supertypes of the given class.
 */
private fun collectSupertypeMethods(
    graph: Graph,
    className: String,
    methodsByClass: Map<String, List<MethodDescriptor>>,
    result: MutableList<MethodDescriptor>,
    visited: MutableSet<String>
) {
    if (!visited.add(className)) return
    for (supertype in graph.supertypes(TypeDescriptor(className))) {
        val superName = supertype.className
        methodsByClass[superName]?.let { result.addAll(it) }
        collectSupertypeMethods(graph, superName, methodsByClass, result, visited)
    }
}

private fun findHttpMethod(annotations: Map<String, Map<String, Any?>>): Pair<String, HttpMethod>? {
    for ((fqn, httpMethod) in MAPPING_TO_HTTP_METHOD) {
        if (fqn in annotations) return fqn to httpMethod
    }
    return null
}

private fun extractPath(values: Map<String, Any?>): String {
    val raw = values["value"] ?: values["path"] ?: return ""
    return raw.toString().removeSurrounding("[", "]").removeSurrounding("\"")
}

private fun combinePaths(classPath: String, methodPath: String): String {
    val normalizedClass = classPath.trimEnd('/')
    val normalizedMethod = methodPath.trimStart('/')
    return when {
        normalizedClass.isEmpty() && normalizedMethod.isEmpty() -> "/"
        normalizedClass.isEmpty() -> "/$normalizedMethod"
        normalizedMethod.isEmpty() -> normalizedClass
        else -> "$normalizedClass/$normalizedMethod"
    }
}

// ========================================================================
// Command
// ========================================================================

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

            val endpoints = discoverEndpoints(graph, endpointPattern, methodFilter)

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

    internal fun formatText(
        endpoints: List<EndpointInfo>,
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

    internal fun formatTypeStructure(
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

        val filteredFields = structure.fields.entries
            .sortedBy { it.value.name }

        filteredFields.forEachIndexed { index, (_, field) ->
            val isLast = index == filteredFields.size - 1
            val displayName = field.name
            sb.append(indent)
            sb.append(if (isLast) "└── " else "├── ")
            sb.append("$displayName: ")
            val childIndent = indent + if (isLast) "    " else "│   "
            formatFieldStructure(field, sb, childIndent, visited.toMutableSet(), depth + 1)
        }
    }

    internal fun formatFieldStructure(
        field: FieldStructure,
        sb: StringBuilder,
        indent: String,
        visited: MutableSet<String>,
        depth: Int
    ) {
        val declaredType = formatDeclaredType(field.declaredType)
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
     * Format a TypeDescriptor with generic arguments.
     * e.g., List<User>, Map<String, Integer>
     */
    internal fun formatDeclaredType(type: TypeDescriptor): String {
        return if (type.typeArguments.isEmpty()) {
            type.simpleName
        } else {
            val args = type.typeArguments.joinToString(", ") { it.simpleName }
            "${type.simpleName}<$args>"
        }
    }

    /**
     * Format output as OpenAPI 3.0 specification.
     * Generates a complete OpenAPI document with:
     * - paths containing all endpoints
     * - components/schemas containing all type definitions
     */
    internal fun formatJsonSchema(
        endpoints: List<EndpointInfo>,
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
    internal fun buildTypeSchema(
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
                .forEach { (_, field) ->
                    val fieldName = field.name
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
    internal fun buildFieldSchema(
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
