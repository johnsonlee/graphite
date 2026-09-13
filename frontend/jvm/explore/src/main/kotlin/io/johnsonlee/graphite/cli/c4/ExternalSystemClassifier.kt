package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.graph.Graph

internal object ExternalSystemClassifier {
    fun summarize(
        graph: Graph?,
        externalWeights: Map<String, Int>,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS
    ): List<ExternalDependency> {
        val grouped = externalWeights.entries.groupBy { key(graph, it.key) }
        val namespaceFamilies = grouped.keys
            .filter { it.startsWith(NAMESPACE_ID_PREFIX) }
            .groupBy { namespaceFamily(it) }
        val merged = linkedMapOf<String, List<Map.Entry<String, Int>>>()
        grouped.forEach { (key, entries) ->
            if (!key.startsWith(NAMESPACE_ID_PREFIX)) {
                merged[key] = merged[key].orEmpty() + entries
                return@forEach
            }
            val family = namespaceFamily(key)
            val familyKeys = namespaceFamilies[family].orEmpty()
            val mergedKey = if (familyKeys.size > 1) "$NAMESPACE_ID_PREFIX$family" else key
            merged[mergedKey] = merged[mergedKey].orEmpty() + entries
        }
        return merged
            .map { (key, entries) ->
                val weight = entries.sumOf { it.value }
                val kind = dependencyKind(key)
                ExternalDependency(
                    id = "$DEPENDENCY_ID_PREFIX$key",
                    name = name(graph, key, entries.map { it.key }),
                    weight = weight,
                    source = source(key),
                    kind = kind,
                    confidence = confidence(key),
                    responsibility = responsibility(kind, key)
                )
            }
            .sortedByDescending { it.weight }
            .take(limit)
    }

    fun key(graph: Graph?, className: String): String {
        graph?.classOrigin(className)?.let { origin ->
            artifactKey(origin)?.let { return "$ARTIFACT_ID_PREFIX$it" }
        }
        return when {
            isJavaRuntimeArchitectureClass(className) -> "${RUNTIME_ID_PREFIX}java"
            className.startsWith("kotlin.") -> "${RUNTIME_ID_PREFIX}kotlin"
            className.startsWith("scala.") -> "${RUNTIME_ID_PREFIX}scala"
            else -> "$NAMESPACE_ID_PREFIX${namespaceGroup(className)}"
        }
    }

    fun name(graph: Graph?, key: String, classNames: List<String>): String {
        return when (key) {
            "${RUNTIME_ID_PREFIX}java" -> "Java Runtime"
            "${RUNTIME_ID_PREFIX}kotlin" -> "Kotlin Runtime"
            "${RUNTIME_ID_PREFIX}scala" -> "Scala Runtime"
            else -> when {
                key.startsWith(ARTIFACT_ID_PREFIX) -> key.removePrefix(ARTIFACT_ID_PREFIX)
                key.startsWith(NAMESPACE_ID_PREFIX) -> key.removePrefix(NAMESPACE_ID_PREFIX)
                else -> classNames.firstNotNullOfOrNull { graph?.classOrigin(it) } ?: key
            }
        }
    }

    fun source(key: String): String =
        when {
            key.startsWith(ARTIFACT_ID_PREFIX) -> "artifact"
            key.startsWith(RUNTIME_ID_PREFIX) -> "runtime"
            else -> "namespace"
        }

    fun kind(key: String): String =
        dependencyKind(key).wireName

    fun dependencyKind(key: String): ExternalDependencyKind =
        when {
            key.startsWith(RUNTIME_ID_PREFIX) -> ExternalDependencyKind.RUNTIME
            key.startsWith(ARTIFACT_ID_PREFIX) -> ExternalDependencyKind.LIBRARY
            else -> ExternalDependencyKind.EXTERNAL_SYSTEM
        }

    fun confidence(key: String): String =
        when {
            key.startsWith(ARTIFACT_ID_PREFIX) -> "high"
            key.startsWith(RUNTIME_ID_PREFIX) -> "high"
            else -> "medium"
        }

    fun responsibility(kind: String, key: String): String =
        responsibility(ExternalDependencyKind.fromWire(kind), key)

    fun responsibility(kind: ExternalDependencyKind, @Suppress("UNUSED_PARAMETER") key: String): String =
        when (kind) {
            ExternalDependencyKind.RUNTIME -> "Provides language and platform runtime services used by the application"
            ExternalDependencyKind.LIBRARY -> "Provides reusable library capabilities linked from the application runtime"
            ExternalDependencyKind.EXTERNAL_SYSTEM ->
                "Represents an inferred external software system boundary grouped from referenced classes"
        }

    fun description(kind: String): String =
        ExternalDependencyKind.entries
            .firstOrNull { it.wireName == kind }
            ?.let(::description)
            ?: "External collaborator inferred from code graph evidence"

    fun description(kind: ExternalDependencyKind): String =
        when (kind) {
            ExternalDependencyKind.LIBRARY -> "Reusable third-party library capabilities referenced from the subject system"
            ExternalDependencyKind.EXTERNAL_SYSTEM -> "External software system candidate inferred from referenced-but-absent classes"
            ExternalDependencyKind.RUNTIME -> "Language and platform runtime supporting the subject system"
        }

    fun artifactKey(origin: String): String? {
        val candidate = origin.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".jar")
        return candidate.takeIf { it.isNotBlank() }
    }

    fun artifactNameFromDependencyId(id: String): String? =
        id.removePrefix(DEPENDENCY_ID_PREFIX).takeIf { it.startsWith(ARTIFACT_ID_PREFIX) }
            ?.removePrefix(ARTIFACT_ID_PREFIX)
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
        key.removePrefix(NAMESPACE_ID_PREFIX)
            .split('.')
            .filter { it.isNotBlank() }
            .take(C4NamespaceHeuristics.FAMILY_SEGMENTS)
            .joinToString(".")
}
