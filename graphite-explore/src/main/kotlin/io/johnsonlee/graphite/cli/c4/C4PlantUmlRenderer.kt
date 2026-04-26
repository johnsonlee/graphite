package io.johnsonlee.graphite.cli.c4

internal class C4PlantUmlRenderer : C4TextDiagramRenderer() {

    override fun beginDiagram(): List<String> =
        listOf("@startuml", "top to bottom direction", "skinparam shadowing false")

    override fun endDiagram(): List<String> =
        listOf("@enduml")

    override fun emptyDiagram(): String =
        "@startuml\n@enduml"

    override fun sectionHeading(title: String): String =
        "' $title"

    override fun edgeLabel(text: String): String =
        escape(text)

    override fun shouldGroupLayer(layer: DiagramLayer, depth: Int): Boolean =
        !(depth == 0 && layer.title == C4RenderingLayers.ACTORS)

    override fun elementLine(element: Map<String, Any?>, depth: Int): String {
        val id = element["id"]?.toString() ?: "unknown"
        val label = escape(diagramElementLabel(element))
        return when (diagramArchitectureTypeOf(element)) {
            "actor" -> "${indent(depth)}actor \"$label\" as ${diagramId(id)}"
            "runtime-platform" -> "${indent(depth)}node \"$label\" as ${diagramId(id)}"
            "software-system" -> "${indent(depth)}rectangle \"$label\" as ${diagramId(id)}"
            else -> "${indent(depth)}component \"$label\" as ${diagramId(id)}"
        }
    }

    override fun layerStart(layer: DiagramLayer, depth: Int): String =
        "${indent(depth)}package \"${escape(layer.title)}\" {"

    override fun layerEnd(layer: DiagramLayer, depth: Int): String =
        "${indent(depth)}}"

    override fun edgeLine(edge: PlannedEdge): String =
        "${diagramId(edge.from)} --> ${diagramId(edge.to)} : ${edge.description}"

    override fun truncationNote(truncated: Int): List<String> =
        if (truncated <= 0) {
            emptyList()
        } else {
            listOf(
                "note as N1",
                "PlantUML view truncated: $truncated edges omitted",
                "end note"
            )
        }

    private fun escape(text: String): String =
        text.replace("\"", "'").replace("\n", "\\n")

    private fun indent(depth: Int): String =
        "  ".repeat(depth)
}
