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
        description = ["Output format: text, json (default: text)"],
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
                if (outputFormat.lowercase() == "json") {
                    println(formatJson(emptyList(), emptyMap()))
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
                "json" -> formatJson(endpoints, returnTypes)
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

    private fun formatJson(
        endpoints: List<io.johnsonlee.graphite.core.EndpointInfo>,
        returnTypes: Map<String, TypeHierarchyResult>
    ): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val output = mapOf(
            "totalEndpoints" to endpoints.size,
            "endpoints" to endpoints.map { endpoint ->
                val base = mutableMapOf<String, Any>(
                    "httpMethod" to endpoint.httpMethod.name,
                    "path" to endpoint.path,
                    "handler" to mapOf(
                        "class" to endpoint.method.declaringClass.className,
                        "method" to endpoint.method.name,
                        "signature" to endpoint.method.signature
                    ),
                    "declaredReturnType" to endpoint.method.returnType.className,
                    "produces" to endpoint.produces,
                    "consumes" to endpoint.consumes
                )
                returnTypes[endpoint.path]?.let { result ->
                    base["returnTypeHierarchy"] = result.returnStructures.map { typeStructureToMap(it) }
                }
                base
            }
        )
        return gson.toJson(output)
    }

    private fun typeStructureToMap(
        structure: TypeStructure,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "type" to structure.className,
            "simpleName" to structure.simpleName,
            "formatted" to structure.formatTypeName()
        )

        // Prevent infinite recursion from circular references
        if (depth > 10 || structure.className in visited) {
            if (structure.className in visited) {
                map["circularReference"] = true
            }
            return map
        }
        visited.add(structure.className)

        if (structure.typeArguments.isNotEmpty()) {
            map["typeArguments"] = structure.typeArguments.mapValues {
                typeStructureToMap(it.value, visited.toMutableSet(), depth + 1)
            }
        }
        if (structure.fields.isNotEmpty()) {
            // Filter out @JsonIgnore fields and use @JsonProperty names as keys
            val filteredFields = structure.fields.entries
                .filter { !it.value.isJsonIgnored }
                .associate { it.value.effectiveJsonName to fieldStructureToMap(it.value, visited.toMutableSet(), depth + 1) }
            if (filteredFields.isNotEmpty()) {
                map["fields"] = filteredFields
            }
        }
        return map
    }

    private fun fieldStructureToMap(
        field: FieldStructure,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0
    ): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "declaredType" to field.declaredType.className,
            "isGenericParameter" to field.isGenericParameter
        )
        // Include Jackson annotation info in output
        if (field.jsonName != null) {
            map["jsonPropertyName"] = field.jsonName
        }
        if (field.actualTypes.isNotEmpty()) {
            map["actualTypes"] = field.actualTypes.map {
                typeStructureToMap(it, visited.toMutableSet(), depth + 1)
            }
        }
        return map
    }
}
