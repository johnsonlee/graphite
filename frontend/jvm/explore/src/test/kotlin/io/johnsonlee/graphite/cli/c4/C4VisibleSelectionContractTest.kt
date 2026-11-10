package io.johnsonlee.graphite.cli.c4

import kotlin.test.Test
import kotlin.test.assertEquals

internal class C4VisibleSelectionContractTest {
    private val first = DiagramElement("system:first", "First")
    private val second = DiagramElement("system:second", "Second")
    private val third = DiagramElement("system:third", "Third")
    private val elements = listOf(first, second, third)

    @Test
    fun `one element budget keeps the first element when a relationship cannot fit`() {
        val relationship = edge(first.id, second.id)

        val selected = selectVisibleDiagramSlice(elements, listOf(relationship), maxElements = 1)

        assertEquals(listOf(first), selected.elements)
        assertEquals(emptyList(), selected.edges)
        assertEquals(1, selected.omittedEdges)
    }

    @Test
    fun `foreign endpoints do not suppress the available diagram elements`() {
        val relationships = listOf(edge(first.id, "external:missing"), edge("external:missing", second.id))

        val selected = selectVisibleDiagramSlice(elements, relationships, maxElements = 2)

        assertEquals(listOf(first, second), selected.elements)
        assertEquals(emptyList(), selected.edges)
        assertEquals(2, selected.omittedEdges)
    }

    @Test
    fun `selection prioritizes a complete relationship and retains original element order`() {
        val chosen = edge(third.id, first.id)
        val relationships = listOf(edge("external:missing", second.id), chosen, edge(second.id, third.id))

        val selected = selectVisibleDiagramSlice(elements, relationships, maxElements = 2)

        assertEquals(listOf(first, third), selected.elements)
        assertEquals(listOf(chosen), selected.edges)
        assertEquals(2, selected.omittedEdges)
    }

    @Test
    fun `self relationship fits a single element without replacing it with the fallback`() {
        val relationship = edge(third.id, third.id)

        val selected = selectVisibleDiagramSlice(elements, listOf(relationship), maxElements = 1)

        assertEquals(listOf(third), selected.elements)
        assertEquals(listOf(relationship), selected.edges)
        assertEquals(0, selected.omittedEdges)
    }

    @Test
    fun `nonpositive limits hide all elements and account for omitted relationships`() {
        for (limit in listOf(-1, 0)) {
            val selected = selectVisibleDiagramSlice(elements, listOf(edge(first.id, second.id)), limit)

            assertEquals(emptyList(), selected.elements)
            assertEquals(emptyList(), selected.edges)
            assertEquals(1, selected.omittedEdges)
        }
    }

    @Test
    fun `context plan retains isolated systems after filtering out foreign relationships`() {
        val source = first.copy(relationships = listOf(
            DiagramRelationship("external:missing", "uses", C4RelationshipKind.USES, 1)))
        val scope = DiagramScope(C4Level.CONTEXT, DiagramModel(emptyList(), listOf(source, second)))

        val plan = buildContextDiagramPlan(scope, maxEdges = 2) { it }

        assertEquals(listOf(source, second), plan.layers.flatMap { it.elements })
        assertEquals(emptyList(), plan.edges)
        assertEquals(0, plan.truncated, "foreign endpoints are rejected before visible-edge truncation")
    }

    private fun edge(from: String, to: String) = PlannedEdge(1, from, to, "uses", C4RelationshipKind.USES)
}
