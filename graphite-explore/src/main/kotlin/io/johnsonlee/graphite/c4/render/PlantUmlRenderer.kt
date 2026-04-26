package io.johnsonlee.graphite.c4.render

import io.johnsonlee.graphite.c4.C4Level
import io.johnsonlee.graphite.c4.C4Model

object PlantUmlRenderer {

    fun render(model: C4Model): String = buildString {
        appendLine("@startuml")
        when (model.level) {
            C4Level.CONTEXT -> appendLine("!include <C4/C4_Context>")
            C4Level.CONTAINER -> appendLine("!include <C4/C4_Container>")
            C4Level.COMPONENT -> appendLine("!include <C4/C4_Component>")
        }
        appendLine("title ${escape(model.systemName)} - ${model.level.name.lowercase()} view")
        appendLine()

        when (model.level) {
            C4Level.CONTEXT -> renderContext(model)
            C4Level.CONTAINER -> renderContainer(model)
            C4Level.COMPONENT -> renderComponent(model)
        }

        appendLine()
        for (rel in model.relationships) {
            val tech = rel.technology?.let { ", ${quote(it)}" } ?: ""
            appendLine("Rel(${rel.from}, ${rel.to}, ${quote(rel.description)}$tech)")
        }
        appendLine("@enduml")
    }

    private fun StringBuilder.renderContext(model: C4Model) {
        for (person in model.persons) {
            appendLine("Person(${person.id}, ${quote(person.name)}, ${quote(person.description ?: "")})")
        }
        appendLine("System(system.app, ${quote(model.systemName)})")
        for (ext in model.externalContainers) {
            appendLine("System_Ext(${ext.id}, ${quote(ext.name)}, ${quote(ext.technology ?: "")})")
        }
    }

    private fun StringBuilder.renderContainer(model: C4Model) {
        for (person in model.persons) {
            appendLine("Person(${person.id}, ${quote(person.name)}, ${quote(person.description ?: "")})")
        }
        for (container in model.containers) {
            appendLine("Container(${container.id}, ${quote(container.name)}, ${quote(container.technology ?: "")})")
        }
        for (ext in model.externalContainers) {
            appendLine("ContainerDb_Ext(${ext.id}, ${quote(ext.name)}, ${quote(ext.technology ?: "")})")
        }
    }

    private fun StringBuilder.renderComponent(model: C4Model) {
        for (person in model.persons) {
            appendLine("Person(${person.id}, ${quote(person.name)}, ${quote(person.description ?: "")})")
        }
        for (container in model.containers) {
            appendLine("Container_Boundary(${container.id}, ${quote(container.name)}) {")
            for (component in container.components) {
                appendLine("  Component(${component.id}, ${quote(component.name)}, ${quote(component.technology ?: "")}, ${quote(component.description ?: "")})")
            }
            appendLine("}")
        }
        for (ext in model.externalContainers) {
            appendLine("ContainerDb_Ext(${ext.id}, ${quote(ext.name)}, ${quote(ext.technology ?: "")})")
        }
    }

    private fun quote(s: String): String = "\"${escape(s)}\""

    private fun escape(s: String): String = s.replace("\"", "\\\"")
}
