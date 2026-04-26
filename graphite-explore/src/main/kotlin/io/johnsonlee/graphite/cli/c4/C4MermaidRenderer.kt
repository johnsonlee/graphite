package io.johnsonlee.graphite.cli.c4

internal class C4MermaidRenderer : C4TextDiagramRenderer() {

    override fun beginDiagram(): List<String> =
        listOf("graph TD")

    override fun endDiagram(): List<String> =
        emptyList()

    override fun emptyDiagram(): String =
        "graph TD"

    override fun sectionHeading(title: String): String =
        "%% $title"

    override fun edgeLabel(text: String): String =
        text.replace("\"", "'")
            .replace("|", "/")
            .replace("(", "")
            .replace(")", "")
            .replace("[", "")
            .replace("]", "")
            .replace("{", "")
            .replace("}", "")

    override fun elementLine(element: Map<String, Any?>, depth: Int): String {
        val id = element["id"]?.toString() ?: "unknown"
        val label = diagramElementLabel(element)
        return "${indent(depth)}${diagramId(id)}${nodeShape(element, label)}"
    }

    override fun layerStart(layer: DiagramLayer, depth: Int): String =
        "${indent(depth)}subgraph ${diagramId(layer.id.lowercase())}[${quotedLabel(layer.title)}]"

    override fun layerEnd(layer: DiagramLayer, depth: Int): String =
        "${indent(depth)}end"

    override fun edgeLine(edge: PlannedEdge): String =
        "    ${diagramId(edge.from)} -->|${edge.description}| ${diagramId(edge.to)}"

    override fun truncationNote(truncated: Int): List<String> =
        if (truncated <= 0) {
            emptyList()
        } else {
            listOf("    graph_note[${quotedLabel("Mermaid view truncated: $truncated edges omitted")}]")
        }

    private fun nodeShape(element: Map<String, Any?>, label: String): String {
        val rawLabel = quotedLabel(label).removePrefix("\"").removeSuffix("\"")
        return when (diagramArchitectureTypeOf(element)) {
            "actor" -> "([$rawLabel])"
            "software-system" -> "[[$rawLabel]]"
            "application-service" -> "([$rawLabel])"
            "runtime-platform" -> "[($rawLabel)]"
            else -> "[${quotedLabel(label)}]"
        }
    }

    private fun quotedLabel(text: String): String =
        "\"${text.replace("\"", "'").replace("\n", "<br/>")}\""

    private fun indent(depth: Int): String =
        "    ".repeat(depth + 1)
}
