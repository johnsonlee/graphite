package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.analysis.DataFlowResult
import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.webgraph.GraphStore
import org.yaml.snakeyaml.Yaml
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Callable

@Command(
    name = "ab-keys",
    description = ["Find all AB test keys used in the codebase, including those from config files"]
)
class AbKeysCommand : Callable<Int> {

    @Parameters(index = "0", description = ["Path to saved graph directory"])
    lateinit var graphDir: Path

    @Option(
        names = ["--methods", "-m"],
        description = ["AB method names to scan (default: getOption,isEnabled,getVariant,experiment)"],
        split = ","
    )
    var methods: List<String> = listOf("getOption", "isEnabled", "getVariant", "experiment")

    @Option(
        names = ["--format", "-f"],
        description = ["Output format: text (default) or json"],
        defaultValue = "text"
    )
    var format: String = "text"

    @Option(
        names = ["--config-prefix"],
        description = ["Config key prefix to consider (e.g., 'ab.', 'feature.'). Default: any."],
        defaultValue = ""
    )
    var configPrefix: String = ""

    override fun call(): Int = runWithGraph(GraphStore.load(graphDir))

    internal fun runWithGraph(graph: Graph): Int {
        // 1. Find all call sites matching AB methods
        val abCallSites = graph.nodes<CallSiteNode>()
            .filter { it.callee.name in methods }
            .toList()

        if (abCallSites.isEmpty()) {
            System.err.println("No AB call sites found for methods: ${methods.joinToString(",")}")
            return 0
        }

        // 2. Parse all config files from graph.resources
        val configEntries = parseConfigFiles(graph)

        // 3. For each AB call site, trace backward to find the key
        val analysis = DataFlowAnalysis(graph)
        val results = mutableListOf<AbKeyResult>()

        for (cs in abCallSites) {
            if (cs.arguments.isEmpty()) continue
            val keyArgId = cs.arguments[0]

            val slice = analysis.backwardSlice(keyArgId)

            // Source 1: StringConstant in slice
            slice.allConstants().filterIsInstance<StringConstant>().forEach { sc ->
                results.add(
                    AbKeyResult(
                        key = sc.value,
                        source = "literal",
                        framework = cs.callee.declaringClass.className,
                        method = cs.callee.name,
                        callerClass = cs.caller.declaringClass.className,
                        callerMethod = cs.caller.name
                    )
                )
            }

            // Source 2: EnumConstant in slice
            slice.allConstants().filterIsInstance<EnumConstant>().forEach { ec ->
                results.add(
                    AbKeyResult(
                        key = "${ec.enumType.className}.${ec.enumName}",
                        source = "enum",
                        framework = cs.callee.declaringClass.className,
                        method = cs.callee.name,
                        callerClass = cs.caller.declaringClass.className,
                        callerMethod = cs.caller.name
                    )
                )
            }

            // Source 3: Config getter — find call sites visited by the slice that look like
            // config.getString("ab.foo") and resolve the key against parsed configs.
            findConfigCalls(slice, graph, configEntries).forEach { (configKey, configValue) ->
                if (configPrefix.isEmpty() || configKey.startsWith(configPrefix)) {
                    results.add(
                        AbKeyResult(
                            key = configValue,
                            source = "config:$configKey",
                            framework = cs.callee.declaringClass.className,
                            method = cs.callee.name,
                            callerClass = cs.caller.declaringClass.className,
                            callerMethod = cs.caller.name
                        )
                    )
                }
            }
        }

        when (format) {
            "json" -> outputJson(results)
            else -> outputText(results)
        }

        return 0
    }

    private fun parseConfigFiles(graph: Graph): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        val yaml = Yaml()

        fun loadYaml(pattern: String) {
            graph.resources.list(pattern).forEach { entry ->
                try {
                    graph.resources.open(entry.path).use { stream ->
                        val data: Any? = yaml.load(stream)
                        flattenYaml(data, "", entries)
                    }
                } catch (_: Exception) { /* skip malformed */ }
            }
        }

        loadYaml("**/*.yml")
        loadYaml("**/*.yaml")

        graph.resources.list("**/*.properties").forEach { entry ->
            try {
                graph.resources.open(entry.path).use { stream ->
                    val props = Properties()
                    props.load(stream)
                    props.forEach { k, v -> entries[k.toString()] = v.toString() }
                }
            } catch (_: Exception) { /* skip */ }
        }

        return entries
    }

    @Suppress("UNCHECKED_CAST")
    private fun flattenYaml(node: Any?, prefix: String, out: MutableMap<String, String>) {
        when (node) {
            is Map<*, *> -> {
                node.forEach { (k, v) ->
                    val newKey = if (prefix.isEmpty()) k.toString() else "$prefix.$k"
                    flattenYaml(v, newKey, out)
                }
            }
            null -> {}
            else -> out[prefix] = node.toString()
        }
    }

    private fun findConfigCalls(
        slice: DataFlowResult,
        graph: Graph,
        configEntries: Map<String, String>
    ): List<Pair<String, String>> {
        // Collect all NodeIds visited by the slice (across all recorded paths)
        val configMethodNames = setOf("getString", "getProperty", "get", "getValue")
        val results = mutableListOf<Pair<String, String>>()
        val seen = HashSet<NodeId>()

        slice.paths.asSequence()
            .flatMap { it.nodes.asSequence() }
            .filter { seen.add(it) }
            .mapNotNull { graph.node(it) }
            .filterIsInstance<CallSiteNode>()
            .filter { it.callee.name in configMethodNames }
            .forEach { configCs ->
                if (configCs.arguments.isEmpty()) return@forEach
                val keyArgId = configCs.arguments[0]
                val keyArg = graph.node(keyArgId)
                if (keyArg is StringConstant) {
                    val configKey = keyArg.value
                    val value = configEntries[configKey]
                    if (value != null) {
                        results.add(configKey to value)
                    }
                }
            }
        return results
    }

    private fun outputText(results: List<AbKeyResult>) {
        if (results.isEmpty()) {
            println("No AB keys found.")
            return
        }
        val grouped = results.groupBy { it.key }
        println("Found ${grouped.size} unique AB key(s):")
        println()
        grouped.entries.sortedBy { it.key }.forEach { (key, occurrences) ->
            println("  $key  (${occurrences.size} usage${if (occurrences.size > 1) "s" else ""})")
            occurrences.distinctBy { "${it.callerClass}.${it.callerMethod}" }.take(5).forEach {
                println("    <- ${it.callerClass}.${it.callerMethod}() [${it.source}]")
            }
            if (occurrences.size > 5) {
                println("    ... and ${occurrences.size - 5} more")
            }
        }
    }

    private fun outputJson(results: List<AbKeyResult>) {
        val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
        println(gson.toJson(results))
    }

    data class AbKeyResult(
        val key: String,
        val source: String,
        val framework: String,
        val method: String,
        val callerClass: String,
        val callerMethod: String
    )
}
