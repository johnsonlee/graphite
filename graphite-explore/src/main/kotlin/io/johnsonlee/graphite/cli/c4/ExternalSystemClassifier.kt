package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.graph.Graph

internal object ExternalSystemClassifier {
    fun summarize(
        graph: Graph?,
        externalWeights: Map<String, Int>,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS
    ): List<Map<String, Any?>> {
        val grouped = externalWeights.entries.groupBy { key(graph, it.key) }
        val namespaceFamilies = grouped.keys
            .filter { it.startsWith("namespace:") }
            .groupBy { namespaceFamily(it) }
        val merged = linkedMapOf<String, List<Map.Entry<String, Int>>>()
        grouped.forEach { (key, entries) ->
            if (!key.startsWith("namespace:")) {
                merged[key] = merged[key].orEmpty() + entries
                return@forEach
            }
            val family = namespaceFamily(key)
            val familyKeys = namespaceFamilies[family].orEmpty()
            val mergedKey = if (familyKeys.size > 1) "namespace:$family" else key
            merged[mergedKey] = merged[mergedKey].orEmpty() + entries
        }
        return merged
            .map { (key, entries) ->
                val weight = entries.sumOf { it.value }
                val kind = kind(key)
                mapOf(
                    "id" to "dependency:$key",
                    "name" to name(graph, key, entries.map { it.key }),
                    "weight" to weight,
                    "source" to source(key),
                    "kind" to kind,
                    "confidence" to confidence(key),
                    "responsibility" to responsibility(kind, key)
                )
            }
            .sortedByDescending { (it["weight"] as Int) }
            .take(limit)
    }

    fun key(graph: Graph?, className: String): String {
        graph?.classOrigin(className)?.let { origin ->
            artifactKey(origin)?.let { return "artifact:$it" }
        }
        return when {
            isJavaRuntimeArchitectureClass(className) -> "runtime:java"
            className.startsWith("kotlin.") -> "runtime:kotlin"
            className.startsWith("scala.") -> "runtime:scala"
            else -> "namespace:${namespaceGroup(className)}"
        }
    }

    fun name(graph: Graph?, key: String, classNames: List<String>): String {
        return when (key) {
            "runtime:java" -> "Java Runtime"
            "runtime:kotlin" -> "Kotlin Runtime"
            "runtime:scala" -> "Scala Runtime"
            else -> when {
                key.startsWith("artifact:") -> key.removePrefix("artifact:")
                key.startsWith("namespace:") -> key.removePrefix("namespace:")
                else -> classNames.firstNotNullOfOrNull { graph?.classOrigin(it) } ?: key
            }
        }
    }

    fun source(key: String): String =
        when {
            key.startsWith("artifact:") -> "artifact"
            key.startsWith("runtime:") -> "runtime"
            else -> "namespace"
        }

    fun kind(key: String): String =
        when {
            key.startsWith("runtime:") -> "runtime"
            key.startsWith("artifact:") -> "library"
            else -> "external-system"
        }

    fun confidence(key: String): String =
        when {
            key.startsWith("artifact:") -> "high"
            key.startsWith("runtime:") -> "high"
            else -> "medium"
        }

    fun responsibility(kind: String, key: String): String =
        when (kind) {
            "runtime" -> "Provides language and platform runtime services used by the application"
            "library" -> "Provides reusable library capabilities linked from the application runtime"
            else -> "Represents an inferred external software system boundary grouped from referenced classes"
        }

    fun description(kind: String): String =
        when (kind) {
            "library" -> "Reusable third-party library capabilities referenced from the subject system"
            "external-system" -> "External software system candidate inferred from referenced-but-absent classes"
            "runtime" -> "Language and platform runtime supporting the subject system"
            else -> "External collaborator inferred from code graph evidence"
        }

    fun artifactKey(origin: String): String? {
        val candidate = origin.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".jar")
        return candidate.takeIf { it.isNotBlank() }
    }

    fun artifactNameFromDependencyId(id: String): String? =
        id.removePrefix("dependency:").takeIf { it.startsWith("artifact:") }
            ?.removePrefix("artifact:")
            ?.takeIf { it.isNotBlank() }

    fun artifactBaseName(name: String): String =
        name.replace(Regex("-\\d+(?:[.-][0-9A-Za-z]+)*$"), "")

    fun namespaceGroup(name: String): String {
        val segments = name.split('.').filter { it.isNotBlank() }
        if (segments.isEmpty()) return name
        val cutoff = segments.indexOfFirst { segment -> segment.firstOrNull()?.isUpperCase() == true }
        val packageSegments = when {
            cutoff > 0 -> segments.take(cutoff)
            else -> segments
        }
        val rootSegments = namespaceRootSegmentCount(packageSegments)
        val selected = when {
            rootSegments == C4NamespaceHeuristics.NON_REVERSE_DNS_ROOT_SEGMENTS -> packageSegments.take(rootSegments)
            else -> packageSegments.take(minOf(C4NamespaceHeuristics.DEFAULT_SEGMENTS, packageSegments.size))
        }
        return selected.joinToString(".")
    }

    private fun namespaceFamily(key: String): String =
        key.removePrefix("namespace:")
            .split('.')
            .filter { it.isNotBlank() }
            .take(C4NamespaceHeuristics.FAMILY_SEGMENTS)
            .joinToString(".")
}

internal fun summarizeExternalSystems(
    graph: Graph?,
    externalWeights: Map<String, Int>,
    limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS
): List<Map<String, Any?>> =
    ExternalSystemClassifier.summarize(graph, externalWeights, limit)

internal fun externalSystemKey(graph: Graph?, className: String): String =
    ExternalSystemClassifier.key(graph, className)

internal fun externalSystemName(graph: Graph?, key: String, classNames: List<String>): String =
    ExternalSystemClassifier.name(graph, key, classNames)

internal fun externalSystemSource(key: String): String =
    ExternalSystemClassifier.source(key)

internal fun externalSystemKind(key: String): String =
    ExternalSystemClassifier.kind(key)

internal fun externalSystemConfidence(key: String): String =
    ExternalSystemClassifier.confidence(key)

internal fun externalSystemResponsibility(kind: String, key: String): String =
    ExternalSystemClassifier.responsibility(kind, key)

internal fun externalSystemDescription(kind: String): String =
    ExternalSystemClassifier.description(kind)

internal fun artifactKey(origin: String): String? =
    ExternalSystemClassifier.artifactKey(origin)

internal fun artifactNameFromDependencyId(id: String): String? =
    ExternalSystemClassifier.artifactNameFromDependencyId(id)

internal fun artifactBaseName(name: String): String =
    ExternalSystemClassifier.artifactBaseName(name)

internal fun namespaceGroup(name: String): String =
    ExternalSystemClassifier.namespaceGroup(name)
