package io.johnsonlee.graphite.cli.c4

internal enum class DiagramLayerKind(
    val id: String,
    val title: String
) {
    ACTORS("actors", "Actors"),
    APPLICATION("application", "Application Layer"),
    EXTERNAL_SYSTEMS("external-systems", "External Systems"),
    LIBRARIES("libraries", "Library Layer"),
    TECHNOLOGY("technology", "Technology Layer");

    companion object {
        val ordered: List<DiagramLayerKind> = listOf(
            ACTORS,
            APPLICATION,
            EXTERNAL_SYSTEMS,
            LIBRARIES,
            TECHNOLOGY
        )

        fun fromArchitectureType(architectureType: C4ArchitectureType): DiagramLayerKind =
            when (architectureType) {
                C4ArchitectureType.ACTOR -> ACTORS
                C4ArchitectureType.RUNTIME_PLATFORM -> TECHNOLOGY
                C4ArchitectureType.EXTERNAL_LIBRARY,
                C4ArchitectureType.LIBRARY -> LIBRARIES
                C4ArchitectureType.EXTERNAL_SYSTEM -> EXTERNAL_SYSTEMS
                else -> APPLICATION
            }
    }
}

internal enum class ApplicationDiagramLayerKind(
    val id: String,
    val title: String
) {
    RUNTIME_BOUNDARY("runtime-boundary", "Runtime Boundary"),
    INTERFACE_ADAPTERS("interface-adapters", "Interface Adapters"),
    COORDINATION("coordination", "Coordination"),
    INTERNAL_CAPABILITIES("internal-capabilities", "Internal Capabilities"),
    SHARED_FOUNDATION("shared-foundation", "Shared Foundation");

    companion object {
        val ordered: List<ApplicationDiagramLayerKind> = listOf(
            RUNTIME_BOUNDARY,
            INTERFACE_ADAPTERS,
            COORDINATION,
            INTERNAL_CAPABILITIES,
            SHARED_FOUNDATION
        )

        fun fromElementKind(kind: C4ElementKind?): ApplicationDiagramLayerKind =
            when (kind) {
                C4ElementKind.APPLICATION_RUNTIME,
                C4ElementKind.APPLICATION_SERVICE -> RUNTIME_BOUNDARY
                C4ElementKind.INTERFACE -> INTERFACE_ADAPTERS
                C4ElementKind.ORCHESTRATOR,
                C4ElementKind.INTEGRATION -> COORDINATION
                C4ElementKind.SHARED_CAPABILITY -> SHARED_FOUNDATION
                else -> INTERNAL_CAPABILITIES
            }
    }
}

internal data class DiagramRelationship(
    val destinationId: String,
    val label: String,
    val kind: C4RelationshipKind,
    val weight: Int
) {
    companion object {
        fun fromStructurizr(raw: C4StructurizrRelationship): DiagramRelationship? {
            val destinationId = raw.destinationId.takeIf { it.isNotBlank() } ?: return null
            val kind = C4RelationshipKind.fromWire(raw.properties["graphite.relationshipKind"])
                ?: C4RelationshipKind.USES
            return DiagramRelationship(
                destinationId = destinationId,
                label = diagramRelationshipLabel(kind),
                kind = kind,
                weight = raw.properties["graphite.weight"]?.toIntOrNull() ?: 0
            )
        }
    }
}

internal data class DiagramElement(
    val id: String,
    val name: String,
    val properties: Map<String, String> = emptyMap(),
    val relationships: List<DiagramRelationship> = emptyList(),
    val containers: List<DiagramElement> = emptyList(),
    val components: List<DiagramElement> = emptyList()
) {
    val architectureType: C4ArchitectureType
        get() = architectureTypeOf(id, properties)

    val graphiteKind: C4ElementKind?
        get() = C4ElementKind.fromWire(properties["graphite.kind"])

    val layer: DiagramLayerKind
        get() = DiagramLayerKind.fromArchitectureType(architectureType)

    val applicationLayer: ApplicationDiagramLayerKind
        get() = ApplicationDiagramLayerKind.fromElementKind(graphiteKind)

    val label: String
        get() = diagramLabel(name, architectureType)

    companion object {
        fun fromStructurizr(raw: C4StructurizrElement): DiagramElement? {
            val id = raw.id.takeIf { it.isNotBlank() } ?: return null
            return DiagramElement(
                id = id,
                name = raw.name,
                properties = raw.properties,
                relationships = raw.relationships.mapNotNull(DiagramRelationship::fromStructurizr),
                containers = raw.containers.mapNotNull(::fromStructurizr),
                components = raw.components.mapNotNull(::fromStructurizr)
            )
        }
    }
}

internal data class DiagramModel(
    val people: List<DiagramElement>,
    val softwareSystems: List<DiagramElement>
) {
    val primarySystem: DiagramElement?
        get() = softwareSystems.firstOrNull { it.id.startsWith("system:") }

    val contextElements: List<DiagramElement>
        get() = people + softwareSystems

    companion object {
        fun fromWorkspace(workspace: C4StructurizrWorkspace): DiagramModel =
            DiagramModel(
                people = workspace.model.people.mapNotNull(DiagramElement::fromStructurizr),
                softwareSystems = workspace.model.softwareSystems.mapNotNull(DiagramElement::fromStructurizr)
            )
    }
}

internal data class DiagramScope(
    val level: C4Level,
    val model: DiagramModel
) {
    val people: List<DiagramElement>
        get() = model.people

    val softwareSystems: List<DiagramElement>
        get() = model.softwareSystems

    val primarySystem: DiagramElement?
        get() = model.primarySystem

    val contextElements: List<DiagramElement>
        get() = model.contextElements
}

internal data class DiagramLayer(
    val title: String,
    val elements: List<DiagramElement>,
    val children: List<DiagramLayer> = emptyList(),
    val id: String = title
) {
    val isNotEmpty: Boolean
        get() = elements.isNotEmpty() || children.any { it.isNotEmpty }

    fun isKind(kind: DiagramLayerKind): Boolean =
        id == kind.id

    companion object {
        fun of(
            kind: DiagramLayerKind,
            elements: List<DiagramElement>,
            children: List<DiagramLayer> = emptyList()
        ): DiagramLayer =
            DiagramLayer(
                title = kind.title,
                elements = elements,
                children = children,
                id = kind.id
            )

        fun of(
            kind: ApplicationDiagramLayerKind,
            elements: List<DiagramElement>
        ): DiagramLayer =
            DiagramLayer(
                title = kind.title,
                elements = elements,
                id = kind.id
            )
    }
}

internal data class LayeredDiagramPlan(
    val layers: List<DiagramLayer>,
    val edges: List<PlannedEdge>,
    val truncated: Int = 0
)

internal data class ComponentDiagramGroup(
    val container: DiagramElement,
    val components: List<DiagramElement>
)

internal data class ComponentDiagramPlan(
    val groups: List<ComponentDiagramGroup>,
    val edges: List<PlannedEdge>,
    val truncated: Int
)

internal data class PlannedEdge(
    override val weight: Int,
    override val from: String,
    override val to: String,
    val description: String,
    override val kind: C4RelationshipKind
) : C4DirectedEdge

internal data class EdgePlan(
    val edges: List<PlannedEdge>,
    val truncated: Int
)

internal data class ContainerPlan(
    val elements: List<DiagramElement>,
    val edges: List<PlannedEdge>
)

internal data class ComponentPlan(
    val containerComponents: List<Pair<DiagramElement, List<DiagramElement>>>,
    val visibleComponentIds: Set<String>,
    val edges: List<PlannedEdge>,
    val truncated: Int
)

internal fun diagramScope(workspace: Map<String, Any?>): DiagramScope {
    val typedWorkspace = C4StructurizrCodec.decode(workspace)
    return DiagramScope(
        level = C4Level.fromWire(typedWorkspace.properties["graphite.level"]),
        model = DiagramModel.fromWorkspace(typedWorkspace)
    )
}

internal fun diagramId(value: String): String =
    value.replace(Regex("[^A-Za-z0-9_]"), "_")

internal fun diagramArchitectureTypeOf(element: Map<String, Any?>): String =
    architectureTypeOf(element["id"]?.toString().orEmpty(), element["properties"].asStringProperties()).wireName

internal fun diagramLayerOf(element: Map<String, Any?>): String =
    DiagramLayerKind
        .fromArchitectureType(diagramArchitectureTypeOf(element).toC4ArchitectureType() ?: C4ArchitectureType.APPLICATION_COMPONENT)
        .id

internal fun applicationContainerLayerOf(element: Map<String, Any?>): String =
    ApplicationDiagramLayerKind
        .fromElementKind(C4ElementKind.fromWire(element["properties"].asStringProperties()["graphite.kind"]))
        .title

private fun architectureTypeOf(id: String, properties: Map<String, String>): C4ArchitectureType =
    C4ArchitectureType.fromWire(properties["graphite.architectureType"])
        ?: when {
            id.startsWith("person:") -> C4ArchitectureType.ACTOR
            id.startsWith("dependency:") -> C4ArchitectureType.EXTERNAL_LIBRARY
            else -> C4ArchitectureType.APPLICATION_COMPONENT
        }

private fun diagramLabel(name: String, architectureType: C4ArchitectureType): String =
    when (architectureType) {
        C4ArchitectureType.EXTERNAL_LIBRARY,
        C4ArchitectureType.LIBRARY -> humanizeArtifactLabel(name)
        C4ArchitectureType.RUNTIME_PLATFORM -> name
        else -> name.substringAfterLast('.')
    }

internal fun layeredDiagramPlan(
    elements: List<DiagramElement>,
    edges: List<PlannedEdge>,
    splitApplicationLayer: Boolean,
    truncated: Int = 0
): LayeredDiagramPlan =
    LayeredDiagramPlan(
        layers = topLevelDiagramLayers(elements, splitApplicationLayer).filter { it.isNotEmpty },
        edges = edges,
        truncated = truncated
    )

private fun topLevelDiagramLayers(
    elements: List<DiagramElement>,
    splitApplicationLayer: Boolean
): List<DiagramLayer> {
    val elementsByLayer = elements.groupBy { it.layer }
    return DiagramLayerKind.ordered.map { kind ->
        if (kind == DiagramLayerKind.APPLICATION) {
            applicationLayer(elementsByLayer[kind].orEmpty(), splitApplicationLayer)
        } else {
            DiagramLayer.of(kind, elementsByLayer[kind].orEmpty())
        }
    }
}

private fun applicationLayer(
    applicationElements: List<DiagramElement>,
    splitApplicationLayer: Boolean
): DiagramLayer {
    return if (!splitApplicationLayer) {
        DiagramLayer.of(DiagramLayerKind.APPLICATION, applicationElements)
    } else {
        DiagramLayer.of(
            kind = DiagramLayerKind.APPLICATION,
            elements = emptyList(),
            children = ApplicationDiagramLayerKind.ordered
                .map { kind ->
                    DiagramLayer.of(
                        kind = kind,
                        elements = applicationElements.filter { it.applicationLayer == kind }
                    )
                }
                .filter { it.isNotEmpty }
        )
    }
}

internal fun rawDiagramEdges(
    source: DiagramElement,
    allowed: Set<String>,
    labelTransform: (String) -> String
): List<PlannedEdge> =
    source.relationships.mapNotNull { relationship ->
        if (relationship.destinationId !in allowed || source.id !in allowed) return@mapNotNull null
        PlannedEdge(
            weight = relationship.weight,
            from = source.id,
            to = relationship.destinationId,
            description = labelTransform(relationship.label),
            kind = relationship.kind
        )
    }

internal fun plannedEdge(edge: Map<String, Any?>): PlannedEdge =
    PlannedEdge(
        weight = (edge["weight"] as? Int) ?: 0,
        from = edge["from"].toString(),
        to = edge["to"].toString(),
        description = edge["description"].toString(),
        kind = C4RelationshipKind.fromWire(edge["kind"]?.toString()) ?: C4RelationshipKind.USES
    )

internal fun dedupeAndSort(edges: List<PlannedEdge>): List<PlannedEdge> =
    edges
        .distinctBy { "${it.from}:${it.to}:${it.kind}" }
        .sortedByDescending { it.weight }

private data class VisibleDiagramSelection(
    val elements: List<DiagramElement>,
    val edges: List<PlannedEdge>,
    val omittedEdges: Int
)

private fun selectVisibleDiagramSlice(
    elements: List<DiagramElement>,
    edges: List<PlannedEdge>,
    maxElements: Int
): VisibleDiagramSelection {
    if (maxElements <= 0) return VisibleDiagramSelection(emptyList(), emptyList(), edges.size)
    if (edges.isEmpty()) {
        return VisibleDiagramSelection(elements.take(maxElements), emptyList(), 0)
    }

    val elementIds = elements.map { it.id }.toSet()
    val visibleIds = linkedSetOf<String>()
    edges.forEach { edge ->
        if (edge.from !in elementIds || edge.to !in elementIds) return@forEach
        val missing = listOf(edge.from, edge.to).filterNot { it in visibleIds }.distinct()
        if (visibleIds.size + missing.size <= maxElements) {
            visibleIds += missing
        }
    }

    if (visibleIds.isEmpty()) {
        visibleIds += elements.take(maxElements).map { it.id }
    }
    val visibleElements = elements.filter { it.id in visibleIds }
    val visibleElementIds = visibleElements.map { it.id }.toSet()
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
    val all = scope.contextElements
    val allowed = all.map { it.id }.toSet()
    val reducedEdges = reducePlannedTransitiveEdges(
        dedupeAndSort(all.flatMap { rawDiagramEdges(it, allowed, labelTransform) })
    )
    val kept = reducedEdges.take(maxEdges)
    return EdgePlan(kept, (reducedEdges.size - kept.size).coerceAtLeast(0))
}

internal fun buildContextDiagramPlan(
    scope: DiagramScope,
    maxEdges: Int,
    labelTransform: (String) -> String
): LayeredDiagramPlan {
    val plan = buildContextPlan(scope, maxEdges, labelTransform)
    val visible = selectVisibleDiagramSlice(
        elements = scope.contextElements,
        edges = plan.edges,
        maxElements = C4ViewLimits.DEFAULT_CONTEXT_DIAGRAM_ELEMENTS
    )
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
    val containers = app.containers
    val externals = scope.softwareSystems.filter { it.id != app.id }
    val actorsAndSystems = scope.people + containers + externals
    val elementsById = actorsAndSystems.associateBy { it.id }
    val allowed = actorsAndSystems.map { it.id }.toSet()

    fun containerEdges(source: DiagramElement): List<PlannedEdge> =
        rawDiagramEdges(source, allowed, labelTransform)

    val allContainerEdges = containers.flatMap { containerEdges(it) }
    val selectedEdges = buildList {
        containers.forEach { container ->
            val outgoing = containerEdges(container).sortedByDescending { it.weight }
            addAll(
                outgoing
                    .filter { it.to.startsWith("container:") }
                    .take(C4ViewLimits.MAX_INTERNAL_EDGES_PER_CONTAINER)
            )
            outgoing.firstOrNull { target ->
                val element = elementsById[target.to]
                element?.id?.startsWith("dependency:") == true && element.graphiteKind != C4ElementKind.RUNTIME
            }?.let(::add)
            if (container.applicationLayer == ApplicationDiagramLayerKind.SHARED_FOUNDATION) {
                allContainerEdges
                    .filter { edge -> edge.to == container.id && edge.from.startsWith("container:") }
                    .maxByOrNull { it.weight }
                    ?.let(::add)
            }
        }
    }
    val edges = reduceSharedLibraryFanInPlanned(
        reduceSharedInternalFanInPlanned(
            reducePlannedTransitiveEdges(dedupeAndSort(selectedEdges))
        )
    )
    val connectedIds = edges.flatMap { listOf(it.from, it.to) }.toSet()
    val visibleElements = if (connectedIds.isNotEmpty()) {
        actorsAndSystems.filter { it.id in connectedIds }
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
    val visible = selectVisibleDiagramSlice(
        elements = plan.elements,
        edges = plan.edges,
        maxElements = C4ViewLimits.DEFAULT_CONTAINER_DIAGRAM_ELEMENTS
    )
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
    val containerComponents = app.containers.map { container ->
        container to container.components
    }
    val allComponents = containerComponents.flatMap { it.second }
    val allowed = allComponents.map { it.id }.toSet()
    val allEdges = allComponents
        .flatMap { rawDiagramEdges(it, allowed, labelTransform) }
        .sortedByDescending { it.weight }
    val keptEdges = allEdges.take(maxEdges)
    val visible = selectVisibleDiagramSlice(
        elements = allComponents,
        edges = keptEdges,
        maxElements = C4ViewLimits.DEFAULT_COMPONENT_DIAGRAM_ELEMENTS
    )
    val connectedIds = visible.elements.map { it.id }.toSet()
    val visibleComponentIds = if (connectedIds.isNotEmpty()) {
        connectedIds
    } else {
        allComponents
            .take(C4ViewLimits.DEFAULT_COMPONENT_DIAGRAM_ELEMENTS)
            .map { it.id }
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
            val components = rawComponents.filter { it.id in plan.visibleComponentIds }
            if (components.isEmpty()) null else ComponentDiagramGroup(container, components)
        },
        edges = plan.edges,
        truncated = plan.truncated
    )
}

internal fun ComponentDiagramPlan.toLayeredDiagramPlan(): LayeredDiagramPlan =
    LayeredDiagramPlan(
        layers = listOf(
            DiagramLayer.of(
                kind = DiagramLayerKind.APPLICATION,
                elements = emptyList(),
                children = groups.map { group ->
                    DiagramLayer(
                        title = group.container.name.ifBlank { "Container" },
                        elements = group.components,
                        id = group.container.id.ifBlank { group.container.name.ifBlank { "container" } }
                    )
                }
            )
        ).filter { it.isNotEmpty },
        edges = edges,
        truncated = truncated
    )

private fun reducePlannedTransitiveEdges(edges: List<PlannedEdge>): List<PlannedEdge> =
    reduceTransitiveEdges(edges, preserveRuntimeEdges = false)

private fun reduceSharedLibraryFanInPlanned(edges: List<PlannedEdge>): List<PlannedEdge> =
    reduceSharedLibraryFanInTyped(edges)

private fun reduceSharedInternalFanInPlanned(edges: List<PlannedEdge>): List<PlannedEdge> =
    reduceSharedInternalFanInTyped(edges)
