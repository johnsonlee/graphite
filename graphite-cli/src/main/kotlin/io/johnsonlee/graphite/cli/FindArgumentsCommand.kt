package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "find-args",
    description = ["Find constant values passed as arguments to specified methods."],
    mixinStandardHelpOptions = true
)
class FindArgumentsCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["Input path (JAR, WAR, Spring Boot JAR, or directory)"]
    )
    lateinit var input: Path

    @Option(
        names = ["-c", "--class"],
        description = ["Fully qualified class name of the target method (e.g., com.example.AbClient)"],
        required = true
    )
    lateinit var targetClass: String

    @Option(
        names = ["-m", "--method"],
        description = ["Method name to search for (e.g., getOption)"],
        required = true
    )
    lateinit var targetMethod: String

    @Option(
        names = ["-r", "--regex"],
        description = ["Treat class and method names as regex patterns (e.g., -c '.*Client' -m 'getOption.*' -r)"]
    )
    var useRegex: Boolean = false

    @Option(
        names = ["-p", "--param-types"],
        description = ["Parameter types (comma-separated, e.g., java.lang.Integer,java.lang.String)"],
        split = ","
    )
    var paramTypes: List<String> = emptyList()

    @Option(
        names = ["-i", "--arg-index"],
        description = ["Argument index to analyze (0-based, default: 0)"],
        defaultValue = "0"
    )
    var argIndex: Int = 0

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
        description = ["Include library JARs from WEB-INF/lib or BOOT-INF/lib (default: true for WAR/Spring Boot JAR)"]
    )
    var includeLibs: Boolean? = null  // null means auto-detect

    @Option(
        names = ["--lib-filter"],
        description = ["Only load JARs matching these patterns (comma-separated, e.g., 'modular-*,business-*')"],
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
            System.err.println("Target method: $targetClass.$targetMethod")
            if (paramTypes.isNotEmpty()) {
                System.err.println("Parameter types: $paramTypes")
            }
            System.err.println("Argument index: $argIndex")
        }

        try {
            // Auto-detect whether to include libraries
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

            // Pass original path directly - JavaProjectLoader handles WAR/JAR extraction
            val graph = loader.load(input)

            if (verbose) {
                val methodCount = graph.methods(io.johnsonlee.graphite.graph.MethodPattern()).count()
                val callSiteCount = graph.nodes(CallSiteNode::class.java).count()
                System.err.println("Loaded $methodCount methods, $callSiteCount call sites")
            }

            val graphite = Graphite.from(graph)

            // Show what we're searching for
            if (verbose) {
                System.err.println("Searching for: $targetClass.$targetMethod")
                if (useRegex) {
                    System.err.println("Class and method names treated as regex patterns")
                }
                if (paramTypes.isNotEmpty()) {
                    System.err.println("With parameter types: $paramTypes")
                }

                // Find matching call sites for preview
                val methodPattern = io.johnsonlee.graphite.graph.MethodPattern(
                    declaringClass = targetClass,
                    name = targetMethod,
                    useRegex = useRegex
                )
                val matchingCallSites = graph.callSites(methodPattern).toList()
                System.err.println("Found ${matchingCallSites.size} matching call sites")

                if (matchingCallSites.isNotEmpty()) {
                    System.err.println("Sample call sites:")
                    matchingCallSites.take(5).forEach { cs ->
                        System.err.println("  - ${cs.callee.declaringClass.className}.${cs.callee.name}(${cs.callee.parameterTypes.joinToString(",") { it.className }})")
                        System.err.println("    called from: ${cs.caller.declaringClass.className}.${cs.caller.name}")
                    }
                }
            }

            val results = graphite.query {
                findArgumentConstants {
                    method {
                        declaringClass = targetClass
                        name = targetMethod
                        useRegex = this@FindArgumentsCommand.useRegex
                        if (paramTypes.isNotEmpty()) {
                            parameterTypes = paramTypes
                        }
                    }
                    argumentIndex = argIndex
                }
            }

            if (results.isEmpty()) {
                if (verbose) {
                    System.err.println("\nNo call sites found for $targetClass.$targetMethod")

                    // Try to help diagnose
                    val similarMethods = graph.callSites(
                        io.johnsonlee.graphite.graph.MethodPattern(name = targetMethod)
                    ).map { it.callee.declaringClass.className }.distinct().toList()

                    if (similarMethods.isNotEmpty()) {
                        System.err.println("But found '$targetMethod' in these classes:")
                        similarMethods.forEach { System.err.println("  - $it") }
                        System.err.println("\nDid you mean one of these?")
                    }
                }
                // Output empty result in the requested format
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
                e.printStackTrace()
            }
            return 1
        }
    }

    private fun formatText(results: List<io.johnsonlee.graphite.query.ArgumentConstantResult>): String {
        val sb = StringBuilder()
        sb.appendLine("Found ${results.size} argument constant(s) for $targetClass.$targetMethod (arg $argIndex):")
        sb.appendLine()

        // Group by constant value for deduplication
        val grouped = results.groupBy { constantToString(it.constant) }

        grouped.forEach { (value, occurrences) ->
            sb.appendLine("  $value")
            sb.appendLine("    Type: ${constantTypeName(occurrences.first().constant)}")
            sb.appendLine("    Occurrences: ${occurrences.size}")
            occurrences.take(5).forEach { result ->
                sb.appendLine("      - ${result.location}")
            }
            if (occurrences.size > 5) {
                sb.appendLine("      ... and ${occurrences.size - 5} more")
            }
            sb.appendLine()
        }

        sb.appendLine("Summary: ${grouped.size} unique value(s), ${results.size} total occurrence(s)")
        return sb.toString()
    }

    private fun formatJson(results: List<io.johnsonlee.graphite.query.ArgumentConstantResult>): String {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val output = mapOf(
            "targetClass" to targetClass,
            "targetMethod" to targetMethod,
            "argumentIndex" to argIndex,
            "totalOccurrences" to results.size,
            "uniqueValues" to results.groupBy { constantKey(it.constant) }.map { (_, occurrences) ->
                val constant = occurrences.first().constant
                val baseMap = mutableMapOf<String, Any?>(
                    "type" to constantTypeName(constant),
                    "occurrences" to occurrences.map { result ->
                        mapOf(
                            "location" to result.location,
                            "callerMethod" to result.callSite.caller.name,
                            "callerClass" to result.callSite.caller.declaringClass.className
                        )
                    }
                )
                // Add type-specific fields
                when (constant) {
                    is EnumConstant -> {
                        baseMap["enumType"] = constant.enumType.className
                        baseMap["enumName"] = constant.enumName
                        baseMap["constructorArgs"] = constant.constructorArgs
                        // For convenience, also include the primary value (first arg)
                        baseMap["value"] = constant.value
                    }
                    else -> {
                        baseMap["value"] = constant.value
                    }
                }
                baseMap
            }
        )
        return gson.toJson(output)
    }

    private fun constantKey(constant: ConstantNode): String {
        return when (constant) {
            is EnumConstant -> "enum:${constant.enumType.className}.${constant.enumName}"
            else -> "value:${constant.value}"
        }
    }

    private fun constantToString(constant: ConstantNode): String {
        return when (constant) {
            is IntConstant -> constant.value.toString()
            is LongConstant -> "${constant.value}L"
            is FloatConstant -> "${constant.value}f"
            is DoubleConstant -> constant.value.toString()
            is StringConstant -> "\"${constant.value}\""
            is EnumConstant -> {
                val base = "${constant.enumType.simpleName}.${constant.enumName}"
                if (constant.value != null) "$base (value: ${constant.value})" else base
            }
            is BooleanConstant -> constant.value.toString()
            is NullConstant -> "null"
        }
    }

    private fun constantTypeName(constant: ConstantNode): String {
        return when (constant) {
            is IntConstant -> "int"
            is LongConstant -> "long"
            is FloatConstant -> "float"
            is DoubleConstant -> "double"
            is StringConstant -> "String"
            is EnumConstant -> "enum (${constant.enumType.className})"
            is BooleanConstant -> "boolean"
            is NullConstant -> "null"
        }
    }
}
