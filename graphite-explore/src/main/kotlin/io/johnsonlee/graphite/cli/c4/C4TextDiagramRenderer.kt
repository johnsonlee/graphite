package io.johnsonlee.graphite.cli.c4

internal abstract class C4TextDiagramRenderer {

    protected abstract val syntax: TextDiagramSyntax

    internal fun render(workspace: Map<String, Any?>): String {
        val scope = diagramScope(workspace)
        return when (scope.level) {
            C4Level.CONTEXT -> renderContext(scope)
            C4Level.CONTAINER -> renderContainers(scope)
            C4Level.COMPONENT -> renderComponents(scope)
            else -> listOf(
                syntax.sectionHeading("Context"),
                renderContext(scope),
                "",
                syntax.sectionHeading("Container"),
                renderContainers(scope),
                "",
                syntax.sectionHeading("Component"),
                renderComponents(scope)
            ).joinToString("\n")
        }
    }

    private fun renderContext(scope: DiagramScope): String =
        renderLayered(
            buildContextDiagramPlan(
                scope = scope,
                maxEdges = C4ViewLimits.MAX_TEXT_DIAGRAM_EDGES,
                labelTransform = syntax.relationshipLabel
            )
        )

    private fun renderContainers(scope: DiagramScope): String =
        buildContainerDiagramPlan(scope, syntax.relationshipLabel)?.let(::renderLayered) ?: syntax.emptyDocument

    private fun renderComponents(scope: DiagramScope): String =
        buildComponentDiagramPlan(
            scope = scope,
            maxEdges = C4ViewLimits.MAX_TEXT_DIAGRAM_EDGES,
            labelTransform = syntax.relationshipLabel
        )?.toLayeredDiagramPlan()?.let(::renderLayered) ?: syntax.emptyDocument

    private fun renderLayered(plan: LayeredDiagramPlan): String =
        diagramDocument(syntax) {
            plan.layers.forEach(::layer)
            plan.edges.forEach(::edge)
            truncation(plan.truncated)
        }
}

internal data class TextDiagramSyntax(
    val header: List<String>,
    val footer: List<String>,
    val emptyDocument: String,
    val sectionHeading: (String) -> String,
    val relationshipLabel: (String) -> String,
    val element: (DiagramElement, Int) -> String,
    val layerStart: (DiagramLayer, Int) -> String,
    val layerEnd: (DiagramLayer, Int) -> String,
    val edge: (PlannedEdge) -> String,
    val truncation: (Int) -> List<String>,
    val shouldGroupLayer: (DiagramLayer, Int) -> Boolean = { _, _ -> true }
)

private fun diagramDocument(
    syntax: TextDiagramSyntax,
    block: DiagramDocumentBuilder.() -> Unit
): String =
    DiagramDocumentBuilder(syntax).apply(block).build()

private class DiagramDocumentBuilder(
    private val syntax: TextDiagramSyntax
) {
    private val lines = syntax.header.toMutableList()

    fun layer(layer: DiagramLayer, depth: Int = 0) {
        if (!layer.isNotEmpty) return
        if (!syntax.shouldGroupLayer(layer, depth)) {
            layer.elements.forEach { element -> lines += syntax.element(element, depth) }
            layer.children.forEach { child -> layer(child, depth) }
            return
        }

        lines += syntax.layerStart(layer, depth)
        layer.elements.forEach { element -> lines += syntax.element(element, depth + 1) }
        layer.children.forEach { child -> layer(child, depth + 1) }
        lines += syntax.layerEnd(layer, depth)
    }

    fun edge(edge: PlannedEdge) {
        lines += syntax.edge(edge)
    }

    fun truncation(truncated: Int) {
        lines += syntax.truncation(truncated)
    }

    fun build(): String =
        (lines + syntax.footer).joinToString("\n")
}
