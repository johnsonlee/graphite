package io.johnsonlee.graphite.cli.c4

internal object C4RenderingLayers {
    const val ACTORS = "Actors"
    const val APPLICATION_LAYER = "Application Layer"
    const val EXTERNAL_SYSTEMS = "External Systems"
    const val LIBRARY_LAYER = "Library Layer"
    const val TECHNOLOGY_LAYER = "Technology Layer"

    // Renderer-only sublayers used to make inferred responsibility areas
    // readable. These are not additional C4 abstractions; the C4 element is
    // still a container/component, and the layer is only diagram layout.
    const val RUNTIME_BOUNDARY = "Runtime Boundary"
    const val INTERFACE_ADAPTERS = "Interface Adapters"
    const val COORDINATION = "Coordination"
    const val INTERNAL_CAPABILITIES = "Internal Capabilities"
    const val SHARED_FOUNDATION = "Shared Foundation"

    val APPLICATION_CONTAINER_LAYERS = listOf(
        RUNTIME_BOUNDARY,
        INTERFACE_ADAPTERS,
        COORDINATION,
        INTERNAL_CAPABILITIES,
        SHARED_FOUNDATION
    )
}

internal data class DiagramLayer(
    val title: String,
    val elements: List<Map<String, Any?>>,
    val children: List<DiagramLayer> = emptyList(),
    val id: String = title
) {
    val isNotEmpty: Boolean
        get() = elements.isNotEmpty() || children.any { it.isNotEmpty }
}

internal data class LayeredDiagramPlan(
    val layers: List<DiagramLayer>,
    val edges: List<PlannedEdge>,
    val truncated: Int = 0
)

internal data class ComponentDiagramGroup(
    val container: Map<String, Any?>,
    val components: List<Map<String, Any?>>
)

internal data class ComponentDiagramPlan(
    val groups: List<ComponentDiagramGroup>,
    val edges: List<PlannedEdge>,
    val truncated: Int
)

internal data class DiagramScope(
    val level: String,
    val people: List<Map<String, Any?>>,
    val softwareSystems: List<Map<String, Any?>>,
    val primarySystem: Map<String, Any?>?
)

internal data class PlannedEdge(
    val weight: Int,
    val from: String,
    val to: String,
    val description: String,
    val kind: String
)

internal data class EdgePlan(
    val edges: List<PlannedEdge>,
    val truncated: Int
)

internal data class ContainerPlan(
    val elements: List<Map<String, Any?>>,
    val edges: List<PlannedEdge>
)

internal data class ComponentPlan(
    val containerComponents: List<Pair<Map<String, Any?>, List<Map<String, Any?>>>>,
    val visibleComponentIds: Set<String>,
    val edges: List<PlannedEdge>,
    val truncated: Int
)

internal fun diagramScope(workspace: Map<String, Any?>): DiagramScope {
    @Suppress("UNCHECKED_CAST")
    val properties = workspace["properties"] as? Map<String, String> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val model = workspace["model"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val people = (model["people"] as? List<Map<String, Any?>>).orEmpty()
    @Suppress("UNCHECKED_CAST")
    val softwareSystems = (model["softwareSystems"] as? List<Map<String, Any?>>).orEmpty()
    return DiagramScope(
        level = properties["graphite.level"] ?: "all",
        people = people,
        softwareSystems = softwareSystems,
        primarySystem = softwareSystems.firstOrNull { (it["id"] as? String)?.startsWith("system:") == true }
    )
}

internal fun diagramId(value: String): String =
    value.replace(Regex("[^A-Za-z0-9_]"), "_")

internal fun diagramArchitectureTypeOf(element: Map<String, Any?>): String =
    element["properties"].asStructurizrProperty("graphite.architectureType")
        ?: when {
            (element["id"]?.toString() ?: "").startsWith("person:") -> "actor"
            (element["id"]?.toString() ?: "").startsWith("dependency:") -> "external-library"
            else -> "application-component"
        }

internal fun diagramLayerOf(element: Map<String, Any?>): String =
    when (diagramArchitectureTypeOf(element)) {
        "actor" -> "actors"
        "runtime-platform" -> "technology"
        "external-library", "library" -> "libraries"
        "external-system" -> "external-systems"
        else -> "application"
    }

internal fun applicationContainerLayerOf(element: Map<String, Any?>): String =
    when (element["properties"].asStructurizrProperty("graphite.kind")) {
        "application-runtime", "application-service" -> C4RenderingLayers.RUNTIME_BOUNDARY
        "interface" -> C4RenderingLayers.INTERFACE_ADAPTERS
        "orchestrator", "integration" -> C4RenderingLayers.COORDINATION
        "shared-capability" -> C4RenderingLayers.SHARED_FOUNDATION
        else -> C4RenderingLayers.INTERNAL_CAPABILITIES
    }

internal fun layeredDiagramPlan(
    elements: List<Map<String, Any?>>,
    edges: List<PlannedEdge>,
    splitApplicationLayer: Boolean,
    truncated: Int = 0
): LayeredDiagramPlan =
    LayeredDiagramPlan(
        layers = listOf(
            DiagramLayer(
                title = C4RenderingLayers.ACTORS,
                elements = elements.filter { diagramLayerOf(it) == "actors" }
            ),
            applicationLayer(elements, splitApplicationLayer),
            DiagramLayer(
                title = C4RenderingLayers.EXTERNAL_SYSTEMS,
                elements = elements.filter { diagramLayerOf(it) == "external-systems" }
            ),
            DiagramLayer(
                title = C4RenderingLayers.LIBRARY_LAYER,
                elements = elements.filter { diagramLayerOf(it) == "libraries" }
            ),
            DiagramLayer(
                title = C4RenderingLayers.TECHNOLOGY_LAYER,
                elements = elements.filter { diagramLayerOf(it) == "technology" }
            )
        ).filter { it.isNotEmpty },
        edges = edges,
        truncated = truncated
    )

private fun applicationLayer(
    elements: List<Map<String, Any?>>,
    splitApplicationLayer: Boolean
): DiagramLayer {
    val applicationElements = elements.filter { diagramLayerOf(it) == "application" }
    return if (!splitApplicationLayer) {
        DiagramLayer(C4RenderingLayers.APPLICATION_LAYER, applicationElements)
    } else {
        DiagramLayer(
            title = C4RenderingLayers.APPLICATION_LAYER,
            elements = emptyList(),
            children = C4RenderingLayers.APPLICATION_CONTAINER_LAYERS
                .map { title ->
                    DiagramLayer(
                        title = title,
                        elements = applicationElements.filter { applicationContainerLayerOf(it) == title }
                    )
                }
                .filter { it.isNotEmpty }
        )
    }
}

internal fun rawDiagramEdges(
    source: Map<String, Any?>,
    allowed: Set<String>,
    labelTransform: (String) -> String
): List<Map<String, Any?>> {
    @Suppress("UNCHECKED_CAST")
    val relationships = (source["relationships"] as? List<Map<String, Any?>>).orEmpty()
    val sourceId = source["id"]?.toString() ?: return emptyList()
    return relationships.mapNotNull { relationship ->
        val destinationId = relationship["destinationId"]?.toString() ?: return@mapNotNull null
        if (destinationId !in allowed || sourceId !in allowed) return@mapNotNull null
        val properties = relationship["properties"] as? Map<*, *>
        mapOf(
            "weight" to (((properties?.get("graphite.weight") as? String)?.toIntOrNull()) ?: 0),
            "from" to sourceId,
            "to" to destinationId,
            "description" to labelTransform(diagramRelationshipLabel(relationship)),
            "kind" to (properties?.get("graphite.relationshipKind") as? String ?: relationship["kind"]?.toString().orEmpty())
        )
    }
}

internal fun plannedEdge(edge: Map<String, Any?>): PlannedEdge =
    PlannedEdge(
        weight = (edge["weight"] as? Int) ?: 0,
        from = edge["from"].toString(),
        to = edge["to"].toString(),
        description = edge["description"].toString(),
        kind = edge["kind"].toString()
    )

internal fun dedupeAndSort(edges: List<Map<String, Any?>>): List<Map<String, Any?>> =
    edges
        .distinctBy { "${it["from"]}:${it["to"]}:${it["kind"]}" }
        .sortedByDescending { (it["weight"] as? Int) ?: 0 }

private data class VisibleDiagramSelection(
    val elements: List<Map<String, Any?>>,
    val edges: List<PlannedEdge>,
    val omittedEdges: Int
)

private fun selectVisibleDiagramSlice(
    elements: List<Map<String, Any?>>,
    edges: List<PlannedEdge>,
    maxElements: Int
): VisibleDiagramSelection {
    if (maxElements <= 0) return VisibleDiagramSelection(emptyList(), emptyList(), edges.size)
    if (edges.isEmpty()) {
        return VisibleDiagramSelection(elements.take(maxElements), emptyList(), 0)
    }

    val elementIds = elements.mapNotNull { it["id"]?.toString() }.toSet()
    val visibleIds = linkedSetOf<String>()
    edges.forEach { edge ->
        if (edge.from !in elementIds || edge.to !in elementIds) return@forEach
        val missing = listOf(edge.from, edge.to).filterNot { it in visibleIds }.distinct()
        if (visibleIds.size + missing.size <= maxElements) {
            visibleIds += missing
        }
    }

    if (visibleIds.isEmpty()) {
        visibleIds += elements.take(maxElements).mapNotNull { it["id"]?.toString() }
    }
    val visibleElements = elements.filter { (it["id"]?.toString() ?: "") in visibleIds }
    val visibleElementIds = visibleElements.mapNotNull { it["id"]?.toString() }.toSet()
    val visibleEdges = edges.filter { it.from in visibleElementIds && it.to in visibleElementIds }
    return VisibleDiagramSelection(
        elements = visibleElements,
        edges = visibleEdges,
        omittedEdges = edges.size - visibleEdges.size
    )
}

internal fun buildContextPlan(
    scope: DiagramScope,
    maxEdges: Int,
    labelTransform: (String) -> String
): EdgePlan {
    val all = scope.people + scope.softwareSystems
    val allowed = all.mapNotNull { it["id"]?.toString() }.toSet()
    val reducedEdges = reduceDiagramTransitiveEdges(
        dedupeAndSort(all.flatMap { rawDiagramEdges(it, allowed, labelTransform) })
    )
    val kept = reducedEdges.take(maxEdges).map(::plannedEdge)
    return EdgePlan(kept, (reducedEdges.size - kept.size).coerceAtLeast(0))
}

internal fun buildContextDiagramPlan(
    scope: DiagramScope,
    maxEdges: Int,
    labelTransform: (String) -> String
): LayeredDiagramPlan {
    val plan = buildContextPlan(scope, maxEdges, labelTransform)
    val all = scope.people + scope.softwareSystems
    val visible = selectVisibleDiagramSlice(all, plan.edges, C4ViewLimits.DEFAULT_CONTEXT_DIAGRAM_ELEMENTS)
    return layeredDiagramPlan(
        elements = visible.elements,
        edges = visible.edges,
        splitApplicationLayer = false,
        truncated = plan.truncated + visible.omittedEdges
    )
}

internal fun buildContainerPlan(
    scope: DiagramScope,
    labelTransform: (String) -> String
): ContainerPlan? {
    val app = scope.primarySystem ?: return null
    @Suppress("UNCHECKED_CAST")
    val containers = (app["containers"] as? List<Map<String, Any?>>).orEmpty()
    val externals = scope.softwareSystems.filter { it["id"] != app["id"] }
    val actorsAndSystems = scope.people + containers + externals
    val elementsById = actorsAndSystems.associateBy { it["id"]?.toString().orEmpty() }
    val allowed = actorsAndSystems.mapNotNull { it["id"]?.toString() }.toSet()

    fun containerEdges(source: Map<String, Any?>): List<Map<String, Any?>> =
        rawDiagramEdges(source, allowed, labelTransform)

    val allContainerEdges = containers.flatMap { containerEdges(it) }
    val selectedEdges = buildList {
        containers.forEach { container ->
            val containerId = container["id"]?.toString() ?: return@forEach
            val outgoing = containerEdges(container).sortedByDescending { (it["weight"] as? Int) ?: 0 }
            addAll(
                outgoing
                    .filter { (it["to"]?.toString() ?: "").startsWith("container:") }
                    .take(C4ViewLimits.MAX_INTERNAL_EDGES_PER_CONTAINER)
            )
            outgoing.firstOrNull { target ->
                val element = elementsById[target["to"]?.toString()]
                element?.get("id")?.toString()?.startsWith("dependency:") == true &&
                    element["properties"].asStructurizrProperty("graphite.kind") != "runtime"
            }?.let(::add)
            if (applicationContainerLayerOf(container) == C4RenderingLayers.SHARED_FOUNDATION) {
                allContainerEdges
                    .filter { edge ->
                        edge["to"] == containerId &&
                            (edge["from"]?.toString() ?: "").startsWith("container:")
                    }
                    .maxByOrNull { (it["weight"] as? Int) ?: 0 }
                    ?.let(::add)
            }
        }
    }
    val edges = reduceSharedLibraryFanIn(
        reduceSharedInternalFanIn(
            reduceDiagramTransitiveEdges(dedupeAndSort(selectedEdges))
        )
    ).map(::plannedEdge)
    val connectedIds = edges.flatMap { listOf(it.from, it.to) }.toSet()
    val visibleElements = if (connectedIds.isNotEmpty()) {
        actorsAndSystems.filter { (it["id"]?.toString() ?: "") in connectedIds }
    } else {
        actorsAndSystems.take(C4ViewLimits.DEFAULT_CONTAINER_DIAGRAM_ELEMENTS)
    }
    return ContainerPlan(visibleElements, edges)
}

internal fun buildContainerDiagramPlan(
    scope: DiagramScope,
    labelTransform: (String) -> String
): LayeredDiagramPlan? {
    val plan = buildContainerPlan(scope, labelTransform) ?: return null
    val visible = selectVisibleDiagramSlice(plan.elements, plan.edges, C4ViewLimits.DEFAULT_CONTAINER_DIAGRAM_ELEMENTS)
    return layeredDiagramPlan(
        elements = visible.elements,
        edges = visible.edges,
        splitApplicationLayer = true
    ).copy(truncated = visible.omittedEdges)
}

internal fun buildComponentPlan(
    scope: DiagramScope,
    maxEdges: Int,
    labelTransform: (String) -> String
): ComponentPlan? {
    val app = scope.primarySystem ?: return null
    @Suppress("UNCHECKED_CAST")
    val containers = (app["containers"] as? List<Map<String, Any?>>).orEmpty()
    val containerComponents = containers.map { container ->
        @Suppress("UNCHECKED_CAST")
        container to (container["components"] as? List<Map<String, Any?>>).orEmpty()
    }
    val allComponents = containerComponents.flatMap { it.second }
    val allowed = allComponents.mapNotNull { it["id"]?.toString() }.toSet()
    val allEdges = allComponents
        .flatMap { rawDiagramEdges(it, allowed, labelTransform) }
        .sortedByDescending { (it["weight"] as? Int) ?: 0 }
    val keptEdges = allEdges.take(maxEdges).map(::plannedEdge)
    val visible = selectVisibleDiagramSlice(
        elements = allComponents,
        edges = keptEdges,
        maxElements = C4ViewLimits.DEFAULT_COMPONENT_DIAGRAM_ELEMENTS
    )
    val connectedIds = visible.elements.mapNotNull { it["id"]?.toString() }.toSet()
    val visibleComponentIds = if (connectedIds.isNotEmpty()) {
        connectedIds
    } else {
        allComponents
            .take(C4ViewLimits.DEFAULT_COMPONENT_DIAGRAM_ELEMENTS)
            .mapNotNull { it["id"]?.toString() }
            .toSet()
    }
    return ComponentPlan(
        containerComponents = containerComponents,
        visibleComponentIds = visibleComponentIds,
        edges = visible.edges,
        truncated = (allEdges.size - keptEdges.size).coerceAtLeast(0) + visible.omittedEdges
    )
}

internal fun buildComponentDiagramPlan(
    scope: DiagramScope,
    maxEdges: Int,
    labelTransform: (String) -> String
): ComponentDiagramPlan? {
    val plan = buildComponentPlan(scope, maxEdges, labelTransform) ?: return null
    return ComponentDiagramPlan(
        groups = plan.containerComponents.mapNotNull { (container, rawComponents) ->
            val components = rawComponents.filter { (it["id"]?.toString() ?: "") in plan.visibleComponentIds }
            if (components.isEmpty()) null else ComponentDiagramGroup(container, components)
        },
        edges = plan.edges,
        truncated = plan.truncated
    )
}

internal fun ComponentDiagramPlan.toLayeredDiagramPlan(): LayeredDiagramPlan =
    LayeredDiagramPlan(
        layers = listOf(
            DiagramLayer(
                title = C4RenderingLayers.APPLICATION_LAYER,
                elements = emptyList(),
                children = groups.map { group ->
                    val containerId = group.container["id"]?.toString().orEmpty()
                    DiagramLayer(
                        title = (group.container["name"] ?: containerId.ifBlank { "Container" }).toString(),
                        elements = group.components,
                        id = containerId.ifBlank { (group.container["name"] ?: "container").toString() }
                    )
                }
            )
        ).filter { it.isNotEmpty },
        edges = edges,
        truncated = truncated
    )
