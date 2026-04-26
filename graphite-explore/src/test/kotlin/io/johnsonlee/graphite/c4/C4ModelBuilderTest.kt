package io.johnsonlee.graphite.c4

import io.johnsonlee.graphite.c4.C4Tags
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class C4ModelBuilderTest {

    private val controllerType = TypeDescriptor("com.example.api.UserController")
    private val serviceType = TypeDescriptor("com.example.svc.UserService")
    private val repoType = TypeDescriptor("com.example.dao.UserRepository")
    private val jdbcType = TypeDescriptor("java.sql.Connection")
    private val plainType = TypeDescriptor("com.example.dom.User")

    private val controllerMethod = MethodDescriptor(controllerType, "list", emptyList(), TypeDescriptor("void"))
    private val serviceMethod = MethodDescriptor(serviceType, "findAll", emptyList(), TypeDescriptor("void"))
    private val repoMethod = MethodDescriptor(repoType, "all", emptyList(), TypeDescriptor("void"))
    private val jdbcMethod = MethodDescriptor(jdbcType, "prepareStatement", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.sql.PreparedStatement"))

    private fun fixture(): DefaultGraph.Builder {
        val builder = DefaultGraph.Builder()
        builder.addNode(CallSiteNode(NodeId.next(), controllerMethod, serviceMethod, 1, null, emptyList()))
        builder.addNode(CallSiteNode(NodeId.next(), serviceMethod, repoMethod, 2, null, emptyList()))
        builder.addNode(CallSiteNode(NodeId.next(), repoMethod, jdbcMethod, 3, null, emptyList()))

        // Pre-baked synthetic tags (what C4TaggedGraph would inject and GraphStore would persist).
        builder.addMemberAnnotation(
            controllerType.className, "<class>",
            C4Tags.STEREOTYPE_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.Stereotype.API)
        )
        builder.addMemberAnnotation(
            serviceType.className, "<class>",
            C4Tags.STEREOTYPE_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.Stereotype.SERVICE)
        )
        builder.addMemberAnnotation(
            repoType.className, "<class>",
            C4Tags.STEREOTYPE_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.Stereotype.DATA_ACCESS)
        )
        builder.addMemberAnnotation(
            jdbcType.className, "<class>",
            C4Tags.EXTERNAL_SYSTEM_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.ExternalSystem.DATABASE)
        )
        return builder
    }

    @Test
    fun `groups classes by stereotype and produces API-Service-DataAccess components`() {
        val graph = fixture().build()
        val model = C4ModelBuilder.build(graph, C4Options(systemName = "Demo", include = listOf("com.example.")))
        val container = model.containers.single()
        val componentNames = container.components.map { it.name }.toSet()
        assertEquals(setOf(C4Tags.Stereotype.API, C4Tags.Stereotype.SERVICE, C4Tags.Stereotype.DATA_ACCESS), componentNames)
    }

    @Test
    fun `produces API to Service to Data Access relationships`() {
        val graph = fixture().build()
        val model = C4ModelBuilder.build(graph, C4Options(include = listOf("com.example.")))
        val components = model.containers.single().components.associateBy { it.name }
        val apiId = components.getValue(C4Tags.Stereotype.API).id
        val serviceId = components.getValue(C4Tags.Stereotype.SERVICE).id
        val dataAccessId = components.getValue(C4Tags.Stereotype.DATA_ACCESS).id
        assertTrue(model.relationships.any { it.from == apiId && it.to == serviceId })
        assertTrue(model.relationships.any { it.from == serviceId && it.to == dataAccessId })
    }

    @Test
    fun `creates Database external container with usage edge`() {
        val graph = fixture().build()
        val model = C4ModelBuilder.build(graph, C4Options(include = listOf("com.example.")))
        val database = model.externalContainers.singleOrNull { it.name == C4Tags.ExternalSystem.DATABASE }
        assertNotNull(database)
        val dataAccessId = model.containers.single().components.first { it.name == C4Tags.Stereotype.DATA_ACCESS }.id
        assertTrue(model.relationships.any { it.from == dataAccessId && it.to == database.id && it.description == "uses" })
    }

    @Test
    fun `infers User person when API component exists`() {
        val graph = fixture().build()
        val model = C4ModelBuilder.build(graph, C4Options(include = listOf("com.example.")))
        val person = model.persons.singleOrNull()
        assertNotNull(person)
        val apiId = model.containers.single().components.first { it.name == C4Tags.Stereotype.API }.id
        assertTrue(model.relationships.any { it.from == person.id && it.to == apiId })
    }

    @Test
    fun `package grouping ignores stereotype tags and groups by package depth`() {
        val graph = fixture().build()
        val model = C4ModelBuilder.build(
            graph,
            C4Options(include = listOf("com.example."), groupByPackage = true, groupDepth = 3)
        )
        val componentNames = model.containers.single().components.map { it.name }.toSet()
        assertTrue(componentNames.all { it.startsWith("Domain: com.example.") })
        assertEquals(setOf("Domain: com.example.api", "Domain: com.example.svc", "Domain: com.example.dao"), componentNames)
    }

    @Test
    fun `excludes packages and limits include filter`() {
        val graph = fixture().build()
        val model = C4ModelBuilder.build(
            graph,
            C4Options(include = listOf("com.example."), exclude = listOf("com.example.dao."))
        )
        val componentNames = model.containers.single().components.map { it.name }
        assertTrue(C4Tags.Stereotype.DATA_ACCESS !in componentNames)
        assertTrue(C4Tags.Stereotype.API in componentNames)
    }

    @Test
    fun `omits person when no API component is present`() {
        val builder = DefaultGraph.Builder()
        builder.addNode(CallSiteNode(NodeId.next(), serviceMethod, repoMethod, 2, null, emptyList()))
        builder.addMemberAnnotation(
            serviceType.className, "<class>",
            C4Tags.STEREOTYPE_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.Stereotype.SERVICE)
        )
        builder.addMemberAnnotation(
            repoType.className, "<class>",
            C4Tags.STEREOTYPE_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.Stereotype.DATA_ACCESS)
        )
        val graph = builder.build()
        val model = C4ModelBuilder.build(graph, C4Options(include = listOf("com.example.")))
        assertTrue(model.persons.isEmpty())
    }
}
