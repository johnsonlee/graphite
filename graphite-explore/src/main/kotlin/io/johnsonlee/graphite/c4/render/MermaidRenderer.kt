package io.johnsonlee.graphite.c4.render

import io.johnsonlee.graphite.c4.C4Level
import io.johnsonlee.graphite.c4.C4Model

object MermaidRenderer {

    fun render(model: C4Model): String = buildString {
        appendLine("```mermaid")
        when (model.level) {
            C4Level.CONTEXT -> appendLine("C4Context")
            C4Level.CONTAINER -> appendLine("C4Container")
            C4Level.COMPONENT -> appendLine("C4Component")
        }
        appendLine("  title ${escape(model.systemName)} - ${model.level.name.lowercase()} view")

        when (model.level) {
            C4Level.CONTEXT -> renderContext(model)
            C4Level.CONTAINER -> renderContainer(model)
            C4Level.COMPONENT -> renderComponent(model)
        }

        for (rel in model.relationships) {
            val tech = rel.technology?.let { ", ${quote(it)}" } ?: ""
            appendLine("  Rel(${rel.from}, ${rel.to}, ${quote(rel.description)}$tech)")
        }
        appendLine("```")
    }

    private fun StringBuilder.renderContext(model: C4Model) {
        for (person in model.persons) {
            appendLine("  Person(${person.id}, ${quote(person.name)}, ${quote(person.description ?: "")})")
        }
        appendLine("  System(system_app, ${quote(model.systemName)})")
        for (ext in model.externalContainers) {
            appendLine("  System_Ext(${ext.id}, ${quote(ext.name)}, ${quote(ext.technology ?: "")})")
        }
    }

    private fun StringBuilder.renderContainer(model: C4Model) {
        for (person in model.persons) {
            appendLine("  Person(${person.id}, ${quote(person.name)}, ${quote(person.description ?: "")})")
        }
        for (container in model.containers) {
            appendLine("  Container(${container.id}, ${quote(container.name)}, ${quote(container.technology ?: "")})")
        }
        for (ext in model.externalContainers) {
            appendLine("  ContainerDb(${ext.id}, ${quote(ext.name)}, ${quote(ext.technology ?: "")})")
        }
    }

    private fun StringBuilder.renderComponent(model: C4Model) {
        for (person in model.persons) {
            appendLine("  Person(${person.id}, ${quote(person.name)}, ${quote(person.description ?: "")})")
        }
        for (container in model.containers) {
            appendLine("  Container_Boundary(${container.id}, ${quote(container.name)}) {")
            for (component in container.components) {
                appendLine("    Component(${component.id}, ${quote(component.name)}, ${quote(component.technology ?: "")}, ${quote(component.description ?: "")})")
            }
            appendLine("  }")
        }
        for (ext in model.externalContainers) {
            appendLine("  ContainerDb(${ext.id}, ${quote(ext.name)}, ${quote(ext.technology ?: "")})")
        }
    }

    private fun quote(s: String): String = "\"${escape(s)}\""

    private fun escape(s: String): String = s.replace("\"", "\\\"")
}
