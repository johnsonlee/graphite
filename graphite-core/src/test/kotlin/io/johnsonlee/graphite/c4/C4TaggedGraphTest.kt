package io.johnsonlee.graphite.c4

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class C4TaggedGraphTest {

    private val userServiceType = TypeDescriptor("com.example.UserService")
    private val controllerType = TypeDescriptor("com.example.UserController")
    private val jdbcType = TypeDescriptor("java.sql.Connection")
    private val plainType = TypeDescriptor("com.example.Plain")

    private val controllerMethod = MethodDescriptor(controllerType, "list", emptyList(), TypeDescriptor("void"))
    private val serviceMethod = MethodDescriptor(userServiceType, "findAll", emptyList(), TypeDescriptor("void"))
    private val jdbcMethod = MethodDescriptor(jdbcType, "prepareStatement", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.sql.PreparedStatement"))

    private fun buildGraph(): C4TaggedGraph {
        val builder = DefaultGraph.Builder()
        // Caller in our app, callee in JDBC -> external tag should appear
        builder.addNode(CallSiteNode(NodeId.next(), serviceMethod, jdbcMethod, 1, null, emptyList()))
        builder.addMemberAnnotation(
            "com.example.UserService", "<class>",
            "org.springframework.stereotype.Service", emptyMap()
        )
        builder.addMemberAnnotation(
            "com.example.UserController", "<class>",
            "org.springframework.web.bind.annotation.RestController", emptyMap()
        )
        return C4TaggedGraph(builder.build())
    }

    @Test
    fun `injects stereotype annotation for service class`() {
        val graph = buildGraph()
        val annotations = graph.memberAnnotations("com.example.UserService", "<class>")
        assertEquals(C4Tags.Stereotype.SERVICE, annotations[C4Tags.STEREOTYPE_ANNOTATION]?.get(C4Tags.VALUE_ATTRIBUTE))
    }

    @Test
    fun `injects stereotype annotation for controller class`() {
        val graph = buildGraph()
        val annotations = graph.memberAnnotations("com.example.UserController", "<class>")
        assertEquals(C4Tags.Stereotype.API, annotations[C4Tags.STEREOTYPE_ANNOTATION]?.get(C4Tags.VALUE_ATTRIBUTE))
    }

    @Test
    fun `injects external system annotation for JDBC callee`() {
        val graph = buildGraph()
        val annotations = graph.memberAnnotations("java.sql.Connection", "<class>")
        assertEquals(C4Tags.ExternalSystem.DATABASE, annotations[C4Tags.EXTERNAL_SYSTEM_ANNOTATION]?.get(C4Tags.VALUE_ATTRIBUTE))
    }

    @Test
    fun `does not inject anything for plain unannotated class`() {
        val graph = buildGraph()
        val annotations = graph.memberAnnotations("com.example.Plain", "<class>")
        assertFalse(annotations.containsKey(C4Tags.STEREOTYPE_ANNOTATION))
        assertFalse(annotations.containsKey(C4Tags.EXTERNAL_SYSTEM_ANNOTATION))
    }

    @Test
    fun `does not inject for non-class members`() {
        val graph = buildGraph()
        val annotations = graph.memberAnnotations("com.example.UserService", "findAll")
        assertFalse(annotations.containsKey(C4Tags.STEREOTYPE_ANNOTATION))
        assertFalse(annotations.containsKey(C4Tags.EXTERNAL_SYSTEM_ANNOTATION))
    }

    @Test
    fun `preserves underlying real annotations alongside synthetic tags`() {
        val graph = buildGraph()
        val annotations = graph.memberAnnotations("com.example.UserService", "<class>")
        assertTrue(annotations.containsKey("org.springframework.stereotype.Service"))
        assertTrue(annotations.containsKey(C4Tags.STEREOTYPE_ANNOTATION))
    }

    @Test
    fun `does not tag external system for non-callee class`() {
        val graph = buildGraph()
        // plainType is not a callee anywhere; even though it doesn't match an external prefix, also not a callee
        val annotations = graph.memberAnnotations("com.example.NotAnything", "<class>")
        assertFalse(annotations.containsKey(C4Tags.EXTERNAL_SYSTEM_ANNOTATION))
    }
}
