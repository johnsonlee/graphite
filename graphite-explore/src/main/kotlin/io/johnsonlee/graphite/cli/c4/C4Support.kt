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
    val kind = C4RelationshipKind.fromWire(
        properties?.get("graphite.relationshipKind") as? String ?: relationship["kind"]?.toString()
    )
    return diagramRelationshipLabel(kind)
}

internal fun diagramRelationshipLabel(kind: C4RelationshipKind?): String =
    when (kind) {
        C4RelationshipKind.ROUTES_TO -> "routes to"
        C4RelationshipKind.ORCHESTRATES -> "orchestrates"
        C4RelationshipKind.BUILDS_ON -> "builds on"
        C4RelationshipKind.RUNS_ON -> "runs on"
        C4RelationshipKind.COLLABORATES_WITH -> "collaborates with"
        else -> "uses"
    }

internal fun reduceTransitiveContainerEdges(edges: List<Map<String, Any?>>): List<Map<String, Any?>> =
    reduceTransitiveContainerEdges(edges, preserveRuntimeEdges = true)

internal fun reduceDiagramTransitiveEdges(edges: List<Map<String, Any?>>): List<Map<String, Any?>> =
    reduceTransitiveContainerEdges(edges, preserveRuntimeEdges = false)

internal fun reduceTransitiveContainerEdges(
    edges: List<Map<String, Any?>>,
    preserveRuntimeEdges: Boolean
): List<Map<String, Any?>> {
    val byEdge = edges.map(::MapBackedEdge)
    return reduceTransitiveEdges(byEdge, preserveRuntimeEdges).map { it.raw }
}

internal fun <T : C4DirectedEdge> reduceTransitiveEdges(
    edges: List<T>,
    preserveRuntimeEdges: Boolean
): List<T> {
    val reducible = edges.filter { edge ->
        edge.kind != C4RelationshipKind.RUNS_ON || !preserveRuntimeEdges
    }
    if (reducible.size > C4ReductionLimits.MAX_TRANSITIVE_REDUCTION_EDGES) return edges

    fun edgeWeight(edge: C4DirectedEdge): Int =
        (edge.weight ?: 1).coerceAtLeast(1)

    fun isHierarchyRelationship(edge: C4DirectedEdge): Boolean =
        edge.kind?.wireName in C4ReductionLimits.HIERARCHY_REDUCTION_KINDS

    fun hasAlternatePath(source: String, destination: String, omitted: T): Boolean {
        val queue = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        queue += source
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            reducible.asSequence()
                .filter { edge -> edge !== omitted && edge.from == current }
                .forEach { edge ->
                    val next = edge.to
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
    fun maxAlternatePathCapacity(source: String, destination: String, omitted: T): Int {
        val queue = ArrayDeque<Pair<String, Int>>()
        val bestCapacity = mutableMapOf<String, Int>()
        queue += source to Int.MAX_VALUE
        while (queue.isNotEmpty()) {
            val (current, capacity) = queue.removeFirst()
            if ((bestCapacity[current] ?: -1) >= capacity) continue
            bestCapacity[current] = capacity
            reducible.asSequence()
                .filter { edge -> edge !== omitted && edge.from == current }
                .forEach { edge ->
                    val next = edge.to
                    val nextCapacity = minOf(capacity, edgeWeight(edge))
                    if (next == destination) return nextCapacity
                    if ((bestCapacity[next] ?: -1) < nextCapacity) queue += next to nextCapacity
                }
        }
        return -1
    }

    val redundant = reducible.filter { edge ->
        val source = edge.from
        val destination = edge.to
        if (isHierarchyRelationship(edge)) {
            hasAlternatePath(source, destination, edge)
        } else {
            maxAlternatePathCapacity(source, destination, edge) >= edgeWeight(edge)
        }
    }.toSet()

    return edges.filterNot { it in redundant }
}

internal fun reduceSharedLibraryFanIn(edges: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val byEdge = edges.map(::MapBackedEdge)
    return reduceSharedLibraryFanInTyped(byEdge).map { it.raw }
}

internal fun <T : C4DirectedEdge> reduceSharedLibraryFanInTyped(edges: List<T>): List<T> {
    val sharedLibraryTargets = edges
        .filter { edge ->
            edge.from.startsWith("container:") &&
                edge.to.startsWith("dependency:") &&
                edge.kind != C4RelationshipKind.RUNS_ON
        }
        .groupBy { it.to }
        .filterValues { candidates -> candidates.size > C4ReductionLimits.MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY }

    if (sharedLibraryTargets.isEmpty()) return edges

    val kept = sharedLibraryTargets.values
        .flatMap { candidates ->
            candidates
                .sortedByDescending { it.weight ?: 0 }
                .take(C4ReductionLimits.MAX_CONTAINER_ENTRYPOINTS_PER_SHARED_DEPENDENCY)
        }
        .toSet()

    return edges.filter { edge ->
        val target = edge.to
        target !in sharedLibraryTargets || edge in kept
    }
}

internal fun reduceSharedInternalFanIn(edges: List<Map<String, Any?>>): List<Map<String, Any?>> {
    val byEdge = edges.map(::MapBackedEdge)
    return reduceSharedInternalFanInTyped(byEdge).map { it.raw }
}

internal fun <T : C4DirectedEdge> reduceSharedInternalFanInTyped(edges: List<T>): List<T> {
    val sharedContainerTargets = edges
        .filter { edge ->
            edge.from.startsWith("container:") &&
                edge.to.startsWith("container:")
        }
        .groupBy { it.to }
        .filterValues { candidates -> candidates.size > C4ReductionLimits.MAX_ENTRYPOINTS_PER_SHARED_CONTAINER }

    if (sharedContainerTargets.isEmpty()) return edges

    val kept = sharedContainerTargets.values
        .flatMap { candidates ->
            candidates
                .sortedByDescending { it.weight ?: 0 }
                .take(C4ReductionLimits.MAX_ENTRYPOINTS_PER_SHARED_CONTAINER)
        }
        .toSet()

    return edges.filter { edge ->
        val target = edge.to
        target !in sharedContainerTargets || edge in kept
    }
}

private data class MapBackedEdge(
    val raw: Map<String, Any?>
) : C4DirectedEdge {
    override val from: String = raw["from"]?.toString().orEmpty()
    override val to: String = raw["to"]?.toString().orEmpty()
    override val kind: C4RelationshipKind? = C4RelationshipKind.fromWire(raw["kind"]?.toString())
    override val weight: Int? = raw["weight"] as? Int
}

internal fun Map<String, Any?>.toMapWithoutNulls(): Map<String, Any?> =
    entries.filter { it.value != null }.associate { it.key to it.value }

internal fun Any?.asStructurizrProperty(key: String): String? =
    this.asStringProperties()[key]

internal fun slugify(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

internal fun externalArchitectureType(kind: ExternalDependencyKind): C4ArchitectureType =
    kind.toArchitectureType()
