package io.johnsonlee.graphite.cli.c4

internal class C4PlantUmlRenderer : C4TextDiagramRenderer() {

    override val syntax: TextDiagramSyntax = TextDiagramSyntax(
        header = listOf("@startuml", "top to bottom direction", "skinparam shadowing false"),
        footer = listOf("@enduml"),
        emptyDocument = "@startuml\n@enduml",
        sectionHeading = { title -> "' $title" },
        relationshipLabel = ::escape,
        element = ::elementLine,
        layerStart = { layer, depth -> "${indent(depth)}package \"${escape(layer.title)}\" {" },
        layerEnd = { _, depth -> "${indent(depth)}}" },
        edge = { edge -> "${diagramId(edge.from)} --> ${diagramId(edge.to)} : ${edge.description}" },
        truncation = ::truncationNote,
        shouldGroupLayer = { layer, depth -> !(depth == 0 && layer.isKind(DiagramLayerKind.ACTORS)) }
    )

    private fun elementLine(element: DiagramElement, depth: Int): String {
        val label = escape(element.label)
        val id = diagramId(element.id)
        return when (element.architectureType) {
            C4ArchitectureType.ACTOR -> "${indent(depth)}actor \"$label\" as $id"
            C4ArchitectureType.RUNTIME_PLATFORM -> "${indent(depth)}node \"$label\" as $id"
            C4ArchitectureType.SOFTWARE_SYSTEM -> "${indent(depth)}rectangle \"$label\" as $id"
            else -> "${indent(depth)}component \"$label\" as $id"
        }
    }

    private fun truncationNote(truncated: Int): List<String> =
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
