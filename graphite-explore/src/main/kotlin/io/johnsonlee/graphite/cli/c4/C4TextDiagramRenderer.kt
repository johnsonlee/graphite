package io.johnsonlee.graphite.cli.c4

internal abstract class C4TextDiagramRenderer {

    internal fun render(workspace: Map<String, Any?>): String {
        val scope = diagramScope(workspace)
        return when (scope.level) {
            "context" -> renderContext(scope)
            "container" -> renderContainers(scope)
            "component" -> renderComponents(scope)
            else -> listOf(
                sectionHeading("Context"),
                renderContext(scope),
                "",
                sectionHeading("Container"),
                renderContainers(scope),
                "",
                sectionHeading("Component"),
                renderComponents(scope)
            ).joinToString("\n")
        }
    }

    protected abstract fun beginDiagram(): List<String>

    protected abstract fun endDiagram(): List<String>

    protected abstract fun emptyDiagram(): String

    protected abstract fun sectionHeading(title: String): String

    protected abstract fun edgeLabel(text: String): String

    protected abstract fun elementLine(element: Map<String, Any?>, depth: Int): String

    protected abstract fun layerStart(layer: DiagramLayer, depth: Int): String

    protected abstract fun layerEnd(layer: DiagramLayer, depth: Int): String

    protected abstract fun edgeLine(edge: PlannedEdge): String

    protected abstract fun truncationNote(truncated: Int): List<String>

    protected open fun shouldGroupLayer(layer: DiagramLayer, depth: Int): Boolean = true

    private fun renderContext(scope: DiagramScope): String =
        renderLayered(
            buildContextDiagramPlan(
                scope = scope,
                maxEdges = C4ViewLimits.MAX_TEXT_DIAGRAM_EDGES,
                labelTransform = ::edgeLabel
            )
        )

    private fun renderContainers(scope: DiagramScope): String =
        buildContainerDiagramPlan(scope, ::edgeLabel)?.let(::renderLayered) ?: emptyDiagram()

    private fun renderComponents(scope: DiagramScope): String =
        buildComponentDiagramPlan(
            scope = scope,
            maxEdges = C4ViewLimits.MAX_TEXT_DIAGRAM_EDGES,
            labelTransform = ::edgeLabel
        )?.toLayeredDiagramPlan()?.let(::renderLayered) ?: emptyDiagram()

    private fun renderLayered(plan: LayeredDiagramPlan): String {
        val lines = beginDiagram().toMutableList()
        plan.layers.forEach { layer -> appendLayer(lines, layer, depth = 0) }
        lines += plan.edges.map(::edgeLine)
        lines += truncationNote(plan.truncated)
        lines += endDiagram()
        return lines.joinToString("\n")
    }

    private fun appendLayer(lines: MutableList<String>, layer: DiagramLayer, depth: Int) {
        if (!layer.isNotEmpty) return
        if (!shouldGroupLayer(layer, depth)) {
            layer.elements.forEach { element -> lines += elementLine(element, depth) }
            layer.children.forEach { child -> appendLayer(lines, child, depth) }
            return
        }

        lines += layerStart(layer, depth)
        layer.elements.forEach { element -> lines += elementLine(element, depth + 1) }
        layer.children.forEach { child -> appendLayer(lines, child, depth + 1) }
        lines += layerEnd(layer, depth)
    }
}
