package io.johnsonlee.graphite.c4.render

import io.johnsonlee.graphite.c4.C4Level
import io.johnsonlee.graphite.c4.C4Model
import io.johnsonlee.graphite.c4.Component
import io.johnsonlee.graphite.c4.Container
import io.johnsonlee.graphite.c4.ExternalContainer
import io.johnsonlee.graphite.c4.Person
import io.johnsonlee.graphite.c4.Relationship
import kotlin.test.Test
import kotlin.test.assertTrue

class RendererTest {

    private fun fixture(level: C4Level = C4Level.COMPONENT): C4Model {
        val api = Component(id = "component.api", name = "API", technology = "Spring MVC", description = "REST", classNames = listOf("com.example.UserController"))
        val service = Component(id = "component.service", name = "Service", technology = "Spring", description = null, classNames = listOf("com.example.UserService"))
        val container = Container(id = "container.app", name = "Demo", technology = "Java", components = listOf(api, service))
        val database = ExternalContainer(id = "external.database", name = "Database", technology = "JDBC")
        val user = Person(id = "person.user", name = "User", description = "End user")
        return C4Model(
            systemName = "Demo",
            level = level,
            containers = listOf(container),
            externalContainers = listOf(database),
            persons = listOf(user),
            relationships = listOf(
                Relationship(from = "person.user", to = "component.api", description = "uses", technology = "HTTPS", weight = 1),
                Relationship(from = "component.api", to = "component.service", description = "calls", technology = null, weight = 5),
                Relationship(from = "component.service", to = "external.database", description = "uses", technology = "JDBC", weight = 3)
            )
        )
    }

    @Test
    fun `plantuml component output contains C4_Component include and component lines`() {
        val out = PlantUmlRenderer.render(fixture(C4Level.COMPONENT))
        assertTrue(out.contains("@startuml"))
        assertTrue(out.contains("!include <C4/C4_Component>"))
        assertTrue(out.contains("Container_Boundary(container.app, \"Demo\")"))
        assertTrue(out.contains("Component(component.api, \"API\""))
        assertTrue(out.contains("Rel(person.user, component.api, \"uses\""))
        assertTrue(out.contains("@enduml"))
    }

    @Test
    fun `plantuml container output collapses components`() {
        val out = PlantUmlRenderer.render(fixture(C4Level.CONTAINER))
        assertTrue(out.contains("!include <C4/C4_Container>"))
        assertTrue(out.contains("Container(container.app"))
        assertTrue(!out.contains("Container_Boundary"))
    }

    @Test
    fun `plantuml context output uses System macros`() {
        val out = PlantUmlRenderer.render(fixture(C4Level.CONTEXT))
        assertTrue(out.contains("!include <C4/C4_Context>"))
        assertTrue(out.contains("System(system.app"))
        assertTrue(out.contains("System_Ext(external.database"))
    }

    @Test
    fun `mermaid component output uses C4Component header`() {
        val out = MermaidRenderer.render(fixture(C4Level.COMPONENT))
        assertTrue(out.startsWith("```mermaid"))
        assertTrue(out.contains("C4Component"))
        assertTrue(out.contains("Component(component.api, \"API\""))
        assertTrue(out.endsWith("```\n"))
    }

    @Test
    fun `mermaid container output collapses components`() {
        val out = MermaidRenderer.render(fixture(C4Level.CONTAINER))
        assertTrue(out.contains("C4Container"))
        assertTrue(out.contains("Container(container.app"))
    }

    @Test
    fun `mermaid context output uses C4Context`() {
        val out = MermaidRenderer.render(fixture(C4Level.CONTEXT))
        assertTrue(out.contains("C4Context"))
        assertTrue(out.contains("System(system_app"))
    }

    @Test
    fun `json output contains structural fields`() {
        val out = JsonRenderer.render(fixture(C4Level.COMPONENT))
        assertTrue(out.contains("\"systemName\""))
        assertTrue(out.contains("\"containers\""))
        assertTrue(out.contains("\"externalContainers\""))
        assertTrue(out.contains("\"persons\""))
        assertTrue(out.contains("\"relationships\""))
    }
}
