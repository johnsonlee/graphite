package io.johnsonlee.graphite.cli.c4

internal fun diagramElementLabel(element: Map<String, Any?>): String {
    val rawName = element["name"]?.toString() ?: element["id"]?.toString() ?: "Unknown"
    val architectureType = element["properties"].asStructurizrProperty("graphite.architectureType")
    return when (architectureType) {
        "external-library", "library" -> humanizeArtifactLabel(rawName)
        "runtime-platform" -> rawName
        else -> rawName.substringAfterLast('.')
    }
}

internal fun humanizeArtifactLabel(name: String): String {
    val base = name.replace(Regex("-\\d+(?:[.-][0-9A-Za-z]+)*$"), "")
    return base
        .split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            when {
                token.length <= C4NamingHeuristics.ACRONYM_TOKEN_MAX_LENGTH -> token.uppercase()
                else -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
}

internal fun humanizeSubjectArtifactLabel(name: String): String =
    name.replace(Regex("-\\d+(?:[.-][0-9A-Za-z]+)*$"), "")
        .split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { token ->
            token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

internal fun diagramRelationshipLabel(relationship: Map<String, Any?>): String {
    val properties = relationship["properties"] as? Map<*, *>
    return when (properties?.get("graphite.relationshipKind") as? String ?: relationship["kind"]?.toString()) {
        "routes-to" -> "routes to"
        "orchestrates" -> "orchestrates"
        "builds-on" -> "builds on"
        "runs-on" -> "runs on"
        "collaborates-with" -> "collaborates with"
        else -> "uses"
    }
}

internal fun reduceTransitiveContainerEdges(edges: List<Map<String, Any?>>): List<Map<String, Any?>> =
    reduceTransitiveContainerEdges(edges, preserveRuntimeEdges = true)

internal fun reduceDiagramTransitiveEdges(edges: List<Map<String, Any?>>): List<Map<String, Any?>> =
    reduceTransitiveContainerEdges(edges, preserveRuntimeEdges = false)

internal fun reduceTransitiveContainerEdges(
    edges: List<Map<String, Any?>>,
    preserveRuntimeEdges: Boolean
): List<Map<String, Any?>> {
    val reducible = edges.filter { edge ->
        val kind = edge["kind"]?.toString().orEmpty()
        kind != "runs-on" || !preserveRuntimeEdges
    }
    if (reducible.size > C4ReductionLimits.MAX_TRANSITIVE_REDUCTION_EDGES) return edges

    fun edgeWeight(edge: Map<String, Any?>): Int =
        ((edge["weight"] as? Int) ?: 1).coerceAtLeast(1)

    fun relationshipKind(edge: Map<String, Any?>): String =
        edge["kind"]?.toString().orEmpty()

    fun isHierarchyRelationship(edge: Map<String, Any?>): Boolean =
        relationshipKind(edge) in C4ReductionLimits.HIERARCHY_REDUCTION_KINDS

    fun hasAlternatePath(source: String, destination: String, omitted: Map<String, Any?>): Boolean {
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue += source
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            reducible.asSequence()
                .filter { edge -> edge !== omitted && edge["from"]?.toString() == current }
                .forEach { edge ->
                    val next = edge["to"]?.toString() ?: return@forEach
                    if (next == destination) return true
                    if (next !in visited) queue += next
                }
        }
        return false
    }

    // For evidence-bearing call/dependency relationships, keep a direct edge
    // unless the alternate path has at least the same bottleneck strength. For
    // hierarchy relationships, plain reachability is enough because A -> B -> C
    // already communicates the layered dependency.
    fun maxAlternatePathCapacity(source: String, destination: String, omitted: Map<String, Any?>): Int {
        val queue = ArrayDeque<Pair<String, Int>>()
        val bestCapacity = mutableMapOf<String, Int>()
        queue += source to Int.MAX_VALUE
        while (queue.isNotEmpty()) {
            val (current, capacity) = queue.removeFirst()
            if ((bestCapacity[current] ?: -1) >= capacity) continue
            bestCapacity[current] = capacity
            reducible.asSequence()
                .filter { edge -> edge !== omitted && edge["from"]?.toString() == current }
                .forEach { edge ->
                    val next = edge["to"]?.toString() ?: return@forEach
                    val nextCapacity = minOf(capacity, edgeWeight(edge))
                    if (next == destination) return nextCapacity
                    if ((bestCapacity[next] ?: -1) < nextCapacity) queue += next to nextCapacity
                }
        }
        return -1
    }

    val redundant = reducible.filter { edge ->
        val source = edge["from"]?.toString() ?: return@filter false
        val destination = edge["to"]?.toString() ?: return@filter false
        if (isHierarchyRelationship(edge)) {
            hasAlternatePath(source, destination, edge)
        } else {
            maxAlternatePathCapacity(source, destination, edge) >= edgeWeight(edge)
        }
    }.toSet()

    return edges.filterNot { it in redundant }
}

internal fun reduceSharedLibraryFanIn(edges: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val sharedLibraryTargets = edges
        .filter { edge ->
            edge["from"]?.toString()?.startsWith("container:") == true &&
                edge["to"]?.toString()?.startsWith("dependency:") == true &&
                edge["kind"]?.toString() != "runs-on"
        }
        .groupBy { it["to"]?.toString().orEmpty() }
        .filterValues { candidates -> candidates.size > C4ReductionLimits.MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY }

    if (sharedLibraryTargets.isEmpty()) return edges

    val kept = sharedLibraryTargets.values
        .flatMap { candidates ->
            candidates
                .sortedByDescending { (it["weight"] as? Int) ?: 0 }
                .take(C4ReductionLimits.MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY)
        }
        .toSet()

    return edges.filter { edge ->
        val target = edge["to"]?.toString().orEmpty()
        target !in sharedLibraryTargets || edge in kept
    }
}

internal fun reduceSharedInternalFanIn(edges: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val sharedContainerTargets = edges
        .filter { edge ->
            edge["from"]?.toString()?.startsWith("container:") == true &&
                edge["to"]?.toString()?.startsWith("container:") == true
        }
        .groupBy { it["to"]?.toString().orEmpty() }
        .filterValues { candidates -> candidates.size > C4ReductionLimits.MAX_ENTRYPOINTS_PER_SHARED_CONTAINER }

    if (sharedContainerTargets.isEmpty()) return edges

    val kept = sharedContainerTargets.values
        .flatMap { candidates ->
            candidates
                .sortedByDescending { (it["weight"] as? Int) ?: 0 }
                .take(C4ReductionLimits.MAX_ENTRYPOINTS_PER_SHARED_CONTAINER)
        }
        .toSet()

    return edges.filter { edge ->
        val target = edge["to"]?.toString().orEmpty()
        target !in sharedContainerTargets || edge in kept
    }
}

internal fun Map<String, Any?>.toMapWithoutNulls(): Map<String, Any?> =
    entries.filter { it.value != null }.associate { it.key to it.value }

internal fun Any?.asStructurizrProperty(key: String): String? =
    (this as? Map<String, String>)?.get(key)

internal fun slugify(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

internal fun externalArchitectureType(kind: String): String =
    when (kind) {
        "runtime" -> "runtime-platform"
        "external-system" -> "external-system"
        else -> "external-library"
    }
