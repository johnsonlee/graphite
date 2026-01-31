package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.analysis.*
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "find-dead-code",
    description = ["Find and remove dead code via bytecode analysis."],
    mixinStandardHelpOptions = true
)
class FindDeadCodeCommand : Callable<Int> {

    @Parameters(
        index = "0",
        description = ["Input path (JAR, WAR, Spring Boot JAR, or directory)"]
    )
    lateinit var input: Path

    // --- Common options ---

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

    // --- Unreferenced code options ---

    @Option(
        names = ["--entry-points"],
        description = ["Additional entry point patterns (regex, comma-separated)"],
        split = ","
    )
    var entryPoints: List<String> = emptyList()

    // --- Scan & export options ---

    @Option(
        names = ["-c", "--class"],
        description = ["Target class name for scan (e.g., com.example.AbClient)"]
    )
    var targetClass: String? = null

    @Option(
        names = ["-m", "--method"],
        description = ["Target method name for scan (e.g., getOption)"]
    )
    var targetMethod: String? = null

    @Option(
        names = ["-r", "--regex"],
        description = ["Treat class and method names as regex patterns"]
    )
    var useRegex: Boolean = false

    @Option(
        names = ["-p", "--param-types"],
        description = ["Parameter types (comma-separated)"],
        split = ","
    )
    var paramTypes: List<String> = emptyList()

    @Option(
        names = ["-i", "--arg-index"],
        description = ["Argument indices to trace (0-based, comma-separated, default: 0)"],
        split = ","
    )
    var argIndices: List<Int> = listOf(0)

    @Option(
        names = ["--export-assumptions"],
        description = ["Export assumption template to YAML file"]
    )
    var exportAssumptions: File? = null

    // --- Assumption-based analysis options ---

    @Option(
        names = ["--assumptions"],
        description = ["Load assumptions from YAML file"]
    )
    var assumptionsFile: File? = null

    // --- Source & deletion options (Phase 5) ---

    @Option(
        names = ["--source-dir"],
        description = ["Source directories (comma-separated)"],
        split = ","
    )
    var sourceDirs: List<Path> = emptyList()

    @Option(
        names = ["--delete"],
        description = ["Actually delete dead code (default: report only)"]
    )
    var delete: Boolean = false

    @Option(
        names = ["--dry-run"],
        description = ["Preview deletions without modifying files"]
    )
    var dryRun: Boolean = false

    override fun call(): Int {
        if (!input.toFile().exists()) {
            System.err.println("Error: Input path does not exist: $input")
            return 1
        }

        try {
            val graph = loadGraph()

            return when {
                // Mode 1: Scan & export assumptions template
                exportAssumptions != null -> runScanExport(graph)

                // Mode 2: Assumption-based analysis
                assumptionsFile != null -> runAssumptionAnalysis(graph)

                // Mode 3: Unreferenced code detection (default)
                else -> runUnreferencedDetection(graph)
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace(System.err)
            }
            return 1
        }
    }

    private fun loadGraph(): Graph {
        val shouldIncludeLibs = includeLibs
            ?: (input.toString().endsWith(".war") || input.toString().endsWith(".jar"))

        if (verbose) {
            System.err.println("Loading bytecode from: $input")
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
            val methodCount = graph.methods(MethodPattern()).count()
            val callSiteCount = graph.nodes(CallSiteNode::class.java).count()
            val branchCount = graph.branchScopes().count()
            System.err.println("Loaded $methodCount methods, $callSiteCount call sites, $branchCount branch scopes")
        }

        return graph
    }

    // ========================================================================
    // Mode 1: Scan & Export
    // ========================================================================

    private fun runScanExport(graph: Graph): Int {
        if (targetClass == null || targetMethod == null) {
            System.err.println("Error: --export-assumptions requires -c/--class and -m/--method")
            return 1
        }

        val graphite = Graphite.from(graph)

        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = targetClass!!
                    name = targetMethod!!
                    useRegex = this@FindDeadCodeCommand.useRegex
                    if (paramTypes.isNotEmpty()) {
                        parameterTypes = paramTypes
                    }
                }
                argumentIndices = argIndices
            }
        }

        if (results.isEmpty()) {
            System.err.println("No call sites found for $targetClass.$targetMethod")
            return 0
        }

        // Group results by argument constant value
        val grouped = results.groupBy { result ->
            val argValues = mutableMapOf<Int, String>()
            argValues[result.argumentIndex] = constantDisplayValue(result.constant)
            ArgumentKey(result.argumentIndex, constantDisplayValue(result.constant))
        }

        // Build YAML structure
        val assumptions = grouped.map { (key, occurrences) ->
            val entry = linkedMapOf<String, Any?>()

            entry["call"] = linkedMapOf(
                "class" to targetClass!!,
                "method" to targetMethod!!,
                "args" to mapOf(key.argIndex.toString() to parseYamlValue(key.argValue))
            )
            entry["value"] = "???"

            val callSites = occurrences.map { result ->
                val site = linkedMapOf<String, Any?>(
                    "caller" to result.location
                )
                result.propagationPath?.let { path ->
                    site["propagation"] = path.toDisplayString()
                }
                site
            }
            entry["call_sites"] = callSites

            entry
        }

        val yamlContent = buildString {
            appendLine("# Generated by: graphite find-dead-code --export-assumptions")
            appendLine("# Method: $targetClass.$targetMethod")
            appendLine("# Fill in 'value: ???' with the actual constant value (true/false/int/string)")
            appendLine()
        } + dumpYaml(mapOf("assumptions" to assumptions))

        val outFile = exportAssumptions!!
        outFile.writeText(yamlContent)
        System.err.println("Exported ${assumptions.size} assumption(s) to ${outFile.absolutePath}")

        return 0
    }

    // ========================================================================
    // Mode 2: Assumption-based analysis
    // ========================================================================

    private fun runAssumptionAnalysis(graph: Graph): Int {
        val assumptions = loadAssumptions(assumptionsFile!!)
        if (assumptions.isEmpty()) {
            System.err.println("No assumptions found in ${assumptionsFile!!.name}")
            return 0
        }

        if (verbose) {
            System.err.println("Loaded ${assumptions.size} assumption(s)")
        }

        val analysis = BranchReachabilityAnalysis(graph)
        val result = analysis.analyze(assumptions)

        // Also run unreferenced detection
        val unreferenced = analysis.findUnreferencedMethods()

        val combinedResult = DeadCodeResult(
            deadBranches = result.deadBranches,
            deadMethods = result.deadMethods,
            deadCallSites = result.deadCallSites,
            unreferencedMethods = unreferenced
        )

        print(
            when (outputFormat.lowercase()) {
                "json" -> formatDeadCodeResultJson(combinedResult)
                else -> formatDeadCodeResult(combinedResult)
            }
        )

        // Handle --delete / --dry-run
        if (delete || dryRun) {
            return executeDeletions(combinedResult, graph)
        }

        return 0
    }

    @Suppress("UNCHECKED_CAST")
    internal fun loadAssumptions(file: File): List<Assumption> {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(file.readText()) ?: return emptyList()
        val assumptionList = data["assumptions"] as? List<Map<String, Any>> ?: return emptyList()

        return assumptionList.mapNotNull { entry ->
            val value = entry["value"]
            if (value == null || value == "???") return@mapNotNull null

            val call = entry["call"] as? Map<String, Any>
            if (call != null) {
                val className = call["class"] as? String ?: return@mapNotNull null
                val methodName = call["method"] as? String ?: return@mapNotNull null
                val args = call["args"] as? Map<String, Any>

                val argIndex = args?.keys?.firstOrNull()?.toIntOrNull()
                val argValue = args?.values?.firstOrNull()

                Assumption(
                    methodPattern = MethodPattern(
                        declaringClass = className,
                        name = methodName
                    ),
                    argumentIndex = argIndex,
                    argumentValue = argValue,
                    assumedValue = value
                )
            } else {
                val field = entry["field"] as? Map<String, Any> ?: return@mapNotNull null
                val className = field["class"] as? String ?: return@mapNotNull null
                val fieldName = field["name"] as? String ?: return@mapNotNull null

                // Field assumptions: match any method that reads this field
                // For now, treat as a method pattern matching the field getter
                Assumption(
                    methodPattern = MethodPattern(
                        declaringClass = className,
                        name = fieldName
                    ),
                    assumedValue = value
                )
            }
        }
    }

    // ========================================================================
    // Mode 3: Unreferenced code detection
    // ========================================================================

    private fun runUnreferencedDetection(graph: Graph): Int {
        val analysis = BranchReachabilityAnalysis(graph)
        val unreferenced = analysis.findUnreferencedMethods()

        // Filter by entry points
        val entryPointPatterns = entryPoints.map { Regex(it) }
        val filtered = unreferenced.filter { method ->
            // Don't report methods matching entry point patterns
            entryPointPatterns.none { pattern ->
                pattern.matches(method.signature) || pattern.matches(method.name)
            }
        }.filter { method ->
            // Skip synthetic methods
            !isSyntheticMethod(method)
        }.toSet()

        val result = DeadCodeResult(
            deadBranches = emptyList(),
            deadMethods = emptySet(),
            deadCallSites = emptySet(),
            unreferencedMethods = filtered
        )

        print(
            when (outputFormat.lowercase()) {
                "json" -> formatDeadCodeResultJson(result)
                else -> formatDeadCodeResult(result)
            }
        )

        // Handle --delete / --dry-run
        if (delete || dryRun) {
            return executeDeletions(result, graph)
        }

        return 0
    }

    internal fun isSyntheticMethod(method: MethodDescriptor): Boolean {
        return method.name.contains("\$") ||
            method.name.startsWith("access\$") ||
            method.name == "values" ||
            method.name == "valueOf" ||
            method.name.startsWith("lambda\$")
    }

    // ========================================================================
    // Source code deletion
    // ========================================================================

    private fun executeDeletions(result: DeadCodeResult, graph: Graph): Int {
        if (sourceDirs.isEmpty()) {
            System.err.println("Error: --delete/--dry-run requires --source-dir")
            return 1
        }

        // Validate source directories exist
        for (dir in sourceDirs) {
            if (!dir.toFile().isDirectory) {
                System.err.println("Error: Source directory does not exist: $dir")
                return 1
            }
        }

        val resolver = SourceFileResolver(sourceDirs)
        val editor = SourceCodeEditor(
            resolver = resolver,
            verbose = if (verbose) { msg -> System.err.println(msg) } else null
        )

        if (verbose) {
            System.err.println("Planning deletions across ${sourceDirs.size} source directory/directories...")
        }

        val actions = editor.planDeletions(result, graph)

        if (actions.isEmpty()) {
            System.err.println("No source files matched for deletion.")
            return 0
        }

        println()
        if (dryRun) {
            println("Deletion preview (dry-run):")
        } else {
            println("Executing deletions:")
        }

        val report = editor.execute(actions, dryRun = dryRun || !delete)
        report.forEach { println("  $it") }

        println()
        println("${if (dryRun) "Would modify" else "Modified"} ${report.count { !it.contains("[SKIP]") && !it.contains("[ERROR]") }} file(s)")

        return 0
    }

    // ========================================================================
    // Output formatting
    // ========================================================================

    internal fun formatDeadCodeResult(result: DeadCodeResult): String {
        val sb = StringBuilder()

        // Unreferenced methods
        if (result.unreferencedMethods.isNotEmpty()) {
            sb.appendLine("Unreferenced methods (${result.unreferencedMethods.size}):")
            result.unreferencedMethods
                .sortedBy { it.signature }
                .forEach { method ->
                    sb.appendLine("  ${method.declaringClass.className}.${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})")
                }
            sb.appendLine()
        }

        // Dead branches
        if (result.deadBranches.isNotEmpty()) {
            sb.appendLine("Dead branches (${result.deadBranches.size}):")
            result.deadBranches.forEach { branch ->
                sb.appendLine("  In ${branch.method.declaringClass.className}.${branch.method.name}():")
                sb.appendLine("    Dead: ${branch.deadKind} branch")
                sb.appendLine("    Dead call sites: ${branch.deadCallSites.size}")
                branch.deadCallSites.take(10).forEach { callSite ->
                    sb.appendLine("      - ${callSite.callee.declaringClass.simpleName}.${callSite.callee.name}()")
                }
                if (branch.deadCallSites.size > 10) {
                    sb.appendLine("      ... and ${branch.deadCallSites.size - 10} more")
                }
            }
            sb.appendLine()
        }

        // Transitively dead methods
        if (result.deadMethods.isNotEmpty()) {
            sb.appendLine("Transitively dead methods (${result.deadMethods.size}):")
            result.deadMethods
                .sortedBy { it.signature }
                .forEach { method ->
                    sb.appendLine("  ${method.declaringClass.className}.${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})")
                }
            sb.appendLine()
        }

        // Summary
        val totalDead = result.unreferencedMethods.size + result.deadMethods.size
        val totalDeadCallSites = result.deadCallSites.size
        sb.appendLine("Summary:")
        sb.appendLine("  Unreferenced methods: ${result.unreferencedMethods.size}")
        sb.appendLine("  Dead branches: ${result.deadBranches.size}")
        sb.appendLine("  Transitively dead methods: ${result.deadMethods.size}")
        sb.appendLine("  Dead call sites: $totalDeadCallSites")
        sb.appendLine("  Total dead methods: $totalDead")

        return sb.toString()
    }

    internal fun formatDeadCodeResultJson(result: DeadCodeResult): String {
        val gson = GsonBuilder().setPrettyPrinting().create()

        val unreferencedMethods = result.unreferencedMethods
            .sortedBy { it.signature }
            .map { method ->
                mapOf(
                    "class" to method.declaringClass.className,
                    "method" to method.name,
                    "parameters" to method.parameterTypes.map { it.className },
                    "signature" to "${method.declaringClass.className}.${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
                )
            }

        val deadBranches = result.deadBranches.map { branch ->
            mapOf(
                "class" to branch.method.declaringClass.className,
                "method" to branch.method.name,
                "deadKind" to branch.deadKind.name,
                "deadCallSites" to branch.deadCallSites.map { callSite ->
                    mapOf(
                        "callee" to "${callSite.callee.declaringClass.className}.${callSite.callee.name}",
                        "lineNumber" to callSite.lineNumber
                    )
                }
            )
        }

        val deadMethods = result.deadMethods
            .sortedBy { it.signature }
            .map { method ->
                mapOf(
                    "class" to method.declaringClass.className,
                    "method" to method.name,
                    "parameters" to method.parameterTypes.map { it.className },
                    "signature" to "${method.declaringClass.className}.${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
                )
            }

        val totalDead = result.unreferencedMethods.size + result.deadMethods.size

        val output = linkedMapOf(
            "unreferencedMethods" to unreferencedMethods,
            "deadBranches" to deadBranches,
            "deadMethods" to deadMethods,
            "summary" to linkedMapOf(
                "unreferencedMethods" to result.unreferencedMethods.size,
                "deadBranches" to result.deadBranches.size,
                "transitivelyDeadMethods" to result.deadMethods.size,
                "deadCallSites" to result.deadCallSites.size,
                "totalDeadMethods" to totalDead
            )
        )

        return gson.toJson(output)
    }

    // ========================================================================
    // YAML helpers
    // ========================================================================

    internal fun dumpYaml(data: Any): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            indent = 2
            indicatorIndent = 0
            defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
        }
        return Yaml(options).dump(data)
    }

    internal fun constantDisplayValue(constant: ConstantNode): String {
        return when (constant) {
            is IntConstant -> constant.value.toString()
            is LongConstant -> constant.value.toString()
            is StringConstant -> constant.value
            is BooleanConstant -> constant.value.toString()
            is EnumConstant -> {
                if (constant.value != null) constant.value.toString()
                else "${constant.enumType.simpleName}.${constant.enumName}"
            }
            is FloatConstant -> constant.value.toString()
            is DoubleConstant -> constant.value.toString()
            is NullConstant -> "null"
        }
    }

    internal fun parseYamlValue(value: String): Any {
        return when {
            value == "true" -> true
            value == "false" -> false
            value == "null" -> "null"
            value.toIntOrNull() != null -> value.toInt()
            value.toLongOrNull() != null -> value.toLong()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }

    private data class ArgumentKey(val argIndex: Int, val argValue: String)
}
