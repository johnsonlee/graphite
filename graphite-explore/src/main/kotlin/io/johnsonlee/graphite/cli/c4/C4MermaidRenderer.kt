package io.johnsonlee.graphite.cli.c4

internal class C4MermaidRenderer : C4TextDiagramRenderer() {

    override val syntax: TextDiagramSyntax = TextDiagramSyntax(
        header = listOf("graph TD"),
        footer = emptyList(),
        emptyDocument = "graph TD",
        sectionHeading = { title -> "%% $title" },
        relationshipLabel = ::edgeLabel,
        element = ::elementLine,
        layerStart = ::layerStart,
        layerEnd = { _, depth -> "${indent(depth)}end" },
        edge = { edge -> "    ${diagramId(edge.from)} -->|${edge.description}| ${diagramId(edge.to)}" },
        truncation = ::truncationNote
    )

    private fun edgeLabel(text: String): String =
        text.replace("\"", "'")
            .replace("|", "/")
            .replace("(", "")
            .replace(")", "")
            .replace("[", "")
            .replace("]", "")
            .replace("{", "")
            .replace("}", "")

    private fun elementLine(element: DiagramElement, depth: Int): String =
        "${indent(depth)}${diagramId(element.id)}${nodeShape(element)}"

    private fun layerStart(layer: DiagramLayer, depth: Int): String =
        "${indent(depth)}subgraph ${diagramId(layer.id.lowercase())}[${quotedLabel(layer.title)}]"

    private fun truncationNote(truncated: Int): List<String> =
        if (truncated <= 0) {
            emptyList()
        } else {
            listOf("    graph_note[${quotedLabel("Mermaid view truncated: $truncated edges omitted")}]")
        }

    private fun nodeShape(element: DiagramElement): String {
        val label = element.label
        val rawLabel = quotedLabel(label).removePrefix("\"").removeSuffix("\"")
        return when (element.architectureType) {
            C4ArchitectureType.ACTOR -> "([$rawLabel])"
            C4ArchitectureType.SOFTWARE_SYSTEM -> "[[$rawLabel]]"
            C4ArchitectureType.APPLICATION_SERVICE -> "([$rawLabel])"
            C4ArchitectureType.RUNTIME_PLATFORM -> "[($rawLabel)]"
            else -> "[${quotedLabel(label)}]"
        }
    }

    private fun quotedLabel(text: String): String =
        "\"${text.replace("\"", "'").replace("\n", "<br/>")}\""

    private fun indent(depth: Int): String =
        "    ".repeat(depth + 1)
}
