package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "find-returns",
    description = ["Find actual return types for methods (useful for API documentation)."],
    mixinStandardHelpOptions = true
)
class FindReturnTypesCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["Input path (JAR, WAR, Spring Boot JAR, or directory)"]
    )
    lateinit var input: Path

    @Option(
        names = ["-c", "--class"],
        description = ["Fully qualified class name pattern (e.g., com.example.UserController)"],
        required = false
    )
    var targetClass: String? = null

    @Option(
        names = ["-m", "--method"],
        description = ["Method name pattern (e.g., get*)"],
        required = false
    )
    var targetMethod: String? = null

    @Option(
        names = ["-r", "--regex"],
        description = ["Treat class and method names as regex patterns"]
    )
    var useRegex: Boolean = false

    @Option(
        names = ["-t", "--return-type"],
        description = ["Filter by declared return type (e.g., java.lang.Object, org.springframework.http.ResponseEntity)"]
    )
    var declaredReturnType: String? = null

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
            if (targetClass != null) {
                System.err.println("Target class: $targetClass")
            }
            if (targetMethod != null) {
                System.err.println("Target method: $targetMethod")
            }
            if (declaredReturnType != null) {
                System.err.println("Declared return type filter: $declaredReturnType")
            }
        }

        try {
            val shouldIncludeLibs = includeLibs ?: (input.toString().endsWith(".war") || input.toString().endsWith(".jar"))

            if (verbose && shouldIncludeLibs) {
                System.err.println("Including library JARs in analysis")
            }

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

            if (verbose) {
                val methodCount = graph.methods(io.johnsonlee.graphite.graph.MethodPattern()).count()
                System.err.println("Loaded $methodCount methods")
            }

            val graphite = Graphite.from(graph)

            val results = graphite.query {
                findActualReturnTypes {
                    method {
                        declaringClass = targetClass
                        name = targetMethod
                        returnType = declaredReturnType
                        useRegex = this@FindReturnTypesCommand.useRegex
                    }
                }
            }

            if (results.isEmpty()) {
                if (verbose) {
                    System.err.println("\nNo methods found matching the criteria")
                }
                if (outputFormat.lowercase() == "json") {
                    println(formatJson(emptyList()))
                }
                return 0
            }

            val output = when (outputFormat.lowercase()) {
                "json" -> formatJson(results)
                else -> formatText(results)
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

    private fun formatText(results: List<io.johnsonlee.graphite.query.ReturnTypeResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} method(s) with actual return type information:")
        sb.appendLine()

        // Group by declaring class
        val grouped = results.groupBy { it.method.declaringClass.className }

        grouped.forEach { (className, methods) ->
            sb.appendLine("$className:")
            methods.forEach { result ->
                val actualTypeNames = result.actualTypes.map { it.className }.distinct()
                sb.appendLine("  ${result.method.name}():")
                sb.appendLine("    Declared: ${result.declaredType.className}")
                sb.appendLine("    Actual:   ${actualTypeNames.joinToString(", ")}")
                if (result.typesMismatch) {
                    sb.appendLine("    ⚠ Type mismatch detected")
                }
            }
            sb.appendLine()
        }

        sb.appendLine("Summary: ${results.size} method(s) in ${grouped.size} class(es)")
        return sb.toString()
    }

    private fun formatJson(results: List<io.johnsonlee.graphite.query.ReturnTypeResult>): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val output = mapOf(
            "totalMethods" to results.size,
            "methods" to results.map { result ->
                mapOf(
                    "declaringClass" to result.method.declaringClass.className,
                    "methodName" to result.method.name,
                    "signature" to result.method.signature,
                    "declaredReturnType" to result.declaredType.className,
                    "actualReturnTypes" to result.actualTypes.map { it.className }.distinct(),
                    "typesMismatch" to result.typesMismatch,
                    "hasGenericReturn" to result.hasGenericReturn
                )
            }
        )
        return gson.toJson(output)
    }
}
