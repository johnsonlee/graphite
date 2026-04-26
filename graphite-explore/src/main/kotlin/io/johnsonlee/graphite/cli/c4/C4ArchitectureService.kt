package io.johnsonlee.graphite.cli.c4

import io.johnsonlee.graphite.graph.Graph

internal class C4ArchitectureService(
    private val inferer: C4ModelInferer = C4ModelInferer(),
    private val mapper: C4StructurizrMapper = C4StructurizrMapper(),
    private val mermaidRenderer: C4MermaidRenderer = C4MermaidRenderer(),
    private val plantUmlRenderer: C4PlantUmlRenderer = C4PlantUmlRenderer(),
    private val dslRenderer: C4StructurizrDslRenderer = C4StructurizrDslRenderer()
) {

    internal fun buildModel(
        graph: Graph,
        level: String,
        limit: Int = C4ViewLimits.FALLBACK_MODEL_ELEMENTS
    ): Map<String, Any?> =
        mapper.toWorkspace(inferer.buildViewModel(graph, level, limit))

    internal fun renderMermaid(workspace: Map<String, Any?>): String =
        mermaidRenderer.render(workspace)

    internal fun renderPlantUml(workspace: Map<String, Any?>): String =
        plantUmlRenderer.render(workspace)

    internal fun renderStructurizrDsl(workspace: Map<String, Any?>): String =
        dslRenderer.render(workspace)

    internal companion object {
        internal val LEVELS = listOf("context", "container", "component", "all")
        internal val FORMATS = listOf("json", "mermaid", "plantuml", "dsl")
    }
}
