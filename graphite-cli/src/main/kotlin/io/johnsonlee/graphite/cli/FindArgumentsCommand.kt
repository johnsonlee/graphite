package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.johnsonlee.graphite.Graphite
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import picocli.CommandLine.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.createTempDirectory

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

    override fun call(): Int {
        if (!input.toFile().exists()) {
            System.err.println("Error: Input path does not exist: $input")
            return 1
        }

        if (verbose) {
            println("Loading bytecode from: $input")
            println("Target method: $targetClass.$targetMethod")
            if (paramTypes.isNotEmpty()) {
                println("Parameter types: $paramTypes")
            }
            println("Argument index: $argIndex")
        }

        try {
            val analysisPath = resolveAnalysisPath(input)
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = includePackages.ifEmpty { listOf("") },
                    excludePackages = excludePackages,
                    buildCallGraph = false
                )
            )

            val graph = loader.load(analysisPath)
            val graphite = Graphite.from(graph)

            val results = graphite.query {
                findArgumentConstants {
                    method {
                        declaringClass = targetClass
                        name = targetMethod
                        if (paramTypes.isNotEmpty()) {
                            parameterTypes = paramTypes
                        }
                    }
                    argumentIndex = argIndex
                }
            }

            if (results.isEmpty()) {
                if (verbose) {
                    println("No call sites found for $targetClass.$targetMethod")
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

    private fun resolveAnalysisPath(path: Path): Path {
        val file = path.toFile()

        // Handle Spring Boot executable JAR
        if (file.isFile && file.name.endsWith(".jar")) {
            return extractSpringBootClassesIfNeeded(file) ?: path
        }

        // Handle WAR files
        if (file.isFile && file.name.endsWith(".war")) {
            return extractWarClassesIfNeeded(file) ?: path
        }

        return path
    }

    private fun extractSpringBootClassesIfNeeded(jarFile: File): Path? {
        // Check if it's a Spring Boot JAR by looking for BOOT-INF/classes
        val tempDir = createTempDirectory("graphite-spring-boot-").toFile()
        try {
            java.util.zip.ZipFile(jarFile).use { zip ->
                val bootInfClasses = zip.entries().asSequence()
                    .any { it.name.startsWith("BOOT-INF/classes/") }

                if (bootInfClasses) {
                    if (verbose) {
                        println("Detected Spring Boot JAR, extracting BOOT-INF/classes...")
                    }
                    // Extract BOOT-INF/classes and BOOT-INF/lib
                    zip.entries().asSequence().forEach { entry ->
                        if (entry.name.startsWith("BOOT-INF/classes/") ||
                            entry.name.startsWith("BOOT-INF/lib/")) {
                            val targetFile = File(tempDir, entry.name)
                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else {
                                targetFile.parentFile.mkdirs()
                                zip.getInputStream(entry).use { input ->
                                    targetFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                    }
                    return tempDir.resolve("BOOT-INF/classes").toPath()
                }
            }
        } catch (e: Exception) {
            if (verbose) {
                println("Warning: Could not extract Spring Boot JAR: ${e.message}")
            }
        }
        return null
    }

    private fun extractWarClassesIfNeeded(warFile: File): Path? {
        val tempDir = createTempDirectory("graphite-war-").toFile()
        try {
            java.util.zip.ZipFile(warFile).use { zip ->
                if (verbose) {
                    println("Extracting WAR file...")
                }
                // Extract WEB-INF/classes and WEB-INF/lib
                zip.entries().asSequence().forEach { entry ->
                    if (entry.name.startsWith("WEB-INF/classes/") ||
                        entry.name.startsWith("WEB-INF/lib/")) {
                        val targetFile = File(tempDir, entry.name)
                        if (entry.isDirectory) {
                            targetFile.mkdirs()
                        } else {
                            targetFile.parentFile.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                targetFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
                return tempDir.resolve("WEB-INF/classes").toPath()
            }
        } catch (e: Exception) {
            if (verbose) {
                println("Warning: Could not extract WAR file: ${e.message}")
            }
        }
        return null
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
            "uniqueValues" to results.groupBy { constantToString(it.constant) }.map { (value, occurrences) ->
                mapOf(
                    "value" to value,
                    "type" to constantTypeName(occurrences.first().constant),
                    "occurrences" to occurrences.map { result ->
                        mapOf(
                            "location" to result.location,
                            "callerMethod" to result.callSite.caller.name,
                            "callerClass" to result.callSite.caller.declaringClass.className
                        )
                    }
                )
            }
        )
        return gson.toJson(output)
    }

    private fun constantToString(constant: ConstantNode): String {
        return when (constant) {
            is IntConstant -> constant.value.toString()
            is LongConstant -> "${constant.value}L"
            is FloatConstant -> "${constant.value}f"
            is DoubleConstant -> constant.value.toString()
            is StringConstant -> "\"${constant.value}\""
            is EnumConstant -> "${constant.enumType.simpleName}.${constant.enumName}"
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
