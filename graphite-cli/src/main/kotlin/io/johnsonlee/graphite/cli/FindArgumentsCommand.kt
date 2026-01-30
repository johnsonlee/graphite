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
        description = ["Argument indices to analyze (0-based, comma-separated, e.g., 0,1,2; default: 0)"],
        split = ","
    )
    var argIndices: List<Int> = listOf(0)

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

    @Option(
        names = ["--show-path"],
        description = ["Show propagation paths for each constant value (useful for complexity analysis)"]
    )
    var showPath: Boolean = false

    @Option(
        names = ["--min-depth"],
        description = ["Only show results with propagation depth >= this value (for complexity filtering)"],
        defaultValue = "0"
    )
    var minDepth: Int = 0

    @Option(
        names = ["--max-path-depth"],
        description = ["Only show results with propagation depth <= this value"],
        defaultValue = "100"
    )
    var maxPathDepth: Int = 100

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
            System.err.println("Argument indices: $argIndices")
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
                    argumentIndices = argIndices
                }
            }

            // Filter results by propagation depth
            val filteredResults = results.filter { result ->
                val depth = result.propagationDepth
                depth >= minDepth && depth <= maxPathDepth
            }

            if (filteredResults.isEmpty()) {
                if (verbose) {
                    if (results.isEmpty()) {
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
                    } else {
                        System.err.println("\nFound ${results.size} result(s), but none matched depth filter (min=$minDepth, max=$maxPathDepth)")
                        val depths = results.map { it.propagationDepth }.distinct().sorted()
                        System.err.println("Available depths: $depths")
                    }
                }
                // Output empty result in the requested format
                if (outputFormat.lowercase() == "json") {
                    println(formatJson(emptyList()))
                }
                return 0
            }

            val output = when (outputFormat.lowercase()) {
                "json" -> formatJson(filteredResults)
                else -> formatText(filteredResults)
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
        val argLabel = if (argIndices.size == 1) "arg ${argIndices.first()}" else "args ${argIndices.joinToString(",")}"
        sb.appendLine("Found ${results.size} argument constant(s) for $targetClass.$targetMethod ($argLabel):")
        sb.appendLine()

        // Group by argument index first if multiple indices, then by constant value
        val byArgIndex = results.groupBy { it.argumentIndex }
        val sortedArgIndices = byArgIndex.keys.sorted()
        val multiArg = sortedArgIndices.size > 1

        // Calculate complexity statistics
        val depths = results.map { it.propagationDepth }
        val maxDepthFound = depths.maxOrNull() ?: 0
        val avgDepth = if (depths.isNotEmpty()) depths.average() else 0.0
        val complexResults = results.count { it.involvesReturnValue || it.involvesFieldAccess }

        for (argIdx in sortedArgIndices) {
            val argResults = byArgIndex[argIdx] ?: continue
            if (multiArg) {
                sb.appendLine("  [arg $argIdx]")
            }

            val grouped = argResults.groupBy { constantToString(it.constant) }
            val indent = if (multiArg) "    " else "  "

            grouped.forEach { (value, occurrences) ->
                sb.appendLine("$indent$value")
                sb.appendLine("$indent  Type: ${constantTypeName(occurrences.first().constant)}")
                sb.appendLine("$indent  Occurrences: ${occurrences.size}")

                // Show depth statistics for this value
                val valueDepths = occurrences.map { it.propagationDepth }
                val maxValueDepth = valueDepths.maxOrNull() ?: 0
                val minValueDepth = valueDepths.minOrNull() ?: 0
                if (maxValueDepth > 0) {
                    sb.appendLine("$indent  Propagation depth: ${if (minValueDepth == maxValueDepth) "$minValueDepth" else "$minValueDepth-$maxValueDepth"}")
                }

                occurrences.take(5).forEach { result ->
                    val depthStr = if (result.propagationDepth > 0) " [depth=${result.propagationDepth}]" else ""
                    sb.appendLine("$indent    - ${result.location}$depthStr")

                    // Show propagation path if --show-path is enabled
                    if (showPath) {
                        result.propagationPath?.let { path ->
                            sb.appendLine("$indent      Path: ${path.toDisplayString()}")
                            if (verbose) {
                                sb.appendLine("$indent      Source type: ${path.sourceType}")
                                path.steps.forEachIndexed { idx, step ->
                                    val arrow = if (idx < path.steps.size - 1) " →" else ""
                                    sb.appendLine("$indent        ${idx + 1}. ${step.toDisplayString()}${step.edgeKind?.let { " [$it]" } ?: ""}$arrow")
                                }
                            }
                        }
                    }
                }
                if (occurrences.size > 5) {
                    sb.appendLine("$indent    ... and ${occurrences.size - 5} more")
                }
                sb.appendLine()
            }
        }

        sb.appendLine("Summary:")
        sb.appendLine("  Unique values: ${results.groupBy { constantToString(it.constant) }.size}")
        sb.appendLine("  Total occurrences: ${results.size}")
        if (multiArg) {
            sb.appendLine("  Arguments analyzed: ${sortedArgIndices.joinToString(", ")}")
        }
        sb.appendLine("  Max propagation depth: $maxDepthFound")
        sb.appendLine("  Avg propagation depth: ${"%.1f".format(avgDepth)}")
        if (complexResults > 0) {
            sb.appendLine("  Complex paths (method calls/field access): $complexResults")
        }

        return sb.toString()
    }

    private fun formatJson(results: List<io.johnsonlee.graphite.query.ArgumentConstantResult>): String {
        val gson = GsonBuilder().setPrettyPrinting().create()

        // Calculate complexity statistics
        val depths = results.map { it.propagationDepth }
        val maxDepthFound = depths.maxOrNull() ?: 0
        val avgDepth = if (depths.isNotEmpty()) depths.average() else 0.0
        val complexResults = results.count { it.involvesReturnValue || it.involvesFieldAccess }

        val output = mapOf(
            "targetClass" to targetClass,
            "targetMethod" to targetMethod,
            "argumentIndices" to argIndices,
            "totalOccurrences" to results.size,
            "statistics" to mapOf(
                "maxPropagationDepth" to maxDepthFound,
                "avgPropagationDepth" to "%.2f".format(avgDepth).toDouble(),
                "complexPaths" to complexResults
            ),
            "uniqueValues" to results.groupBy { constantKey(it.constant) }.map { (_, occurrences) ->
                val constant = occurrences.first().constant
                val valueDepths = occurrences.map { it.propagationDepth }
                val baseMap = mutableMapOf<String, Any?>(
                    "type" to constantTypeName(constant),
                    "depthRange" to mapOf(
                        "min" to (valueDepths.minOrNull() ?: 0),
                        "max" to (valueDepths.maxOrNull() ?: 0)
                    ),
                    "occurrences" to occurrences.map { result ->
                        val occMap = mutableMapOf<String, Any?>(
                            "argumentIndex" to result.argumentIndex,
                            "location" to result.location,
                            "callerMethod" to result.callSite.caller.name,
                            "callerClass" to result.callSite.caller.declaringClass.className,
                            "propagationDepth" to result.propagationDepth,
                            "involvesReturnValue" to result.involvesReturnValue,
                            "involvesFieldAccess" to result.involvesFieldAccess
                        )
                        // Add propagation path if --show-path is enabled
                        if (showPath) {
                            result.propagationPath?.let { path ->
                                occMap["propagationPath"] = mapOf(
                                    "sourceType" to path.sourceType.name,
                                    "depth" to path.depth,
                                    "display" to path.toDisplayString(),
                                    "steps" to path.steps.map { step ->
                                        mapOf(
                                            "nodeType" to step.nodeType.name,
                                            "description" to step.description,
                                            "location" to step.location,
                                            "edgeKind" to step.edgeKind?.name,
                                            "depth" to step.depth
                                        )
                                    }
                                )
                            }
                        }
                        occMap
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
