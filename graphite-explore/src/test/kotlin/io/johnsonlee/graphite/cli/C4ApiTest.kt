package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.c4.C4Tags
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class C4ApiTest {

    companion object {
        private lateinit var app: Javalin
        private var port: Int = 0

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val controller = TypeDescriptor("com.example.api.UserController")
            val service = TypeDescriptor("com.example.svc.UserService")
            val jdbc = TypeDescriptor("java.sql.Connection")
            val controllerMethod = MethodDescriptor(controller, "list", emptyList(), TypeDescriptor("void"))
            val serviceMethod = MethodDescriptor(service, "findAll", emptyList(), TypeDescriptor("void"))
            val jdbcMethod = MethodDescriptor(jdbc, "prepareStatement", listOf(TypeDescriptor("java.lang.String")), TypeDescriptor("java.sql.PreparedStatement"))

            val builder = DefaultGraph.Builder()
            builder.addNode(CallSiteNode(NodeId.next(), controllerMethod, serviceMethod, 1, null, emptyList()))
            builder.addNode(CallSiteNode(NodeId.next(), serviceMethod, jdbcMethod, 2, null, emptyList()))
            builder.addMemberAnnotation(
                controller.className, "<class>",
                C4Tags.STEREOTYPE_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.Stereotype.API)
            )
            builder.addMemberAnnotation(
                service.className, "<class>",
                C4Tags.STEREOTYPE_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.Stereotype.SERVICE)
            )
            builder.addMemberAnnotation(
                jdbc.className, "<class>",
                C4Tags.EXTERNAL_SYSTEM_ANNOTATION, mapOf(C4Tags.VALUE_ATTRIBUTE to C4Tags.ExternalSystem.DATABASE)
            )

            val graph = builder.build()
            app = Javalin.create { config ->
                config.jsonMapper(JavalinGson(GsonBuilder().create()))
            }.start(0)
            port = app.port()

            ExploreCommand().registerApiRoutes(app, graph)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            app.stop()
        }
    }

    private fun get(path: String): Pair<Int, String> {
        val conn = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val code = conn.responseCode
        val body = if (code in 200..299) {
            InputStreamReader(conn.inputStream).use { it.readText() }
        } else {
            conn.errorStream?.let { InputStreamReader(it).use { r -> r.readText() } } ?: ""
        }
        conn.disconnect()
        return code to body
    }

    @Test
    fun `defaults to component-level json`() {
        val (code, body) = get("/api/c4?include=com.example.")
        assertEquals(200, code)
        assertTrue(body.contains("\"externalContainers\""))
        assertTrue(body.contains("\"Database\""))
    }

    @Test
    fun `serves plantuml format`() {
        val (code, body) = get("/api/c4?format=plantuml&include=com.example.")
        assertEquals(200, code)
        assertTrue(body.contains("@startuml"))
        assertTrue(body.contains("Container_Boundary"))
    }

    @Test
    fun `serves mermaid format`() {
        val (code, body) = get("/api/c4?format=mermaid&include=com.example.")
        assertEquals(200, code)
        assertTrue(body.contains("```mermaid"))
        assertTrue(body.contains("C4Component"))
    }

    @Test
    fun `serves container level`() {
        val (code, body) = get("/api/c4?level=container&format=plantuml&include=com.example.")
        assertEquals(200, code)
        assertTrue(body.contains("!include <C4/C4_Container>"))
        assertTrue(body.contains("Container(container.app"))
    }

    @Test
    fun `serves context level`() {
        val (code, body) = get("/api/c4?level=context&format=plantuml&include=com.example.")
        assertEquals(200, code)
        assertTrue(body.contains("!include <C4/C4_Context>"))
        assertTrue(body.contains("System(system.app"))
    }

    @Test
    fun `rejects unknown level`() {
        val (code, _) = get("/api/c4?level=bogus")
        assertEquals(400, code)
    }

    @Test
    fun `rejects unknown format`() {
        val (code, _) = get("/api/c4?format=bogus&include=com.example.")
        assertEquals(400, code)
    }

    @Test
    fun `package grouping returns Domain components`() {
        val (code, body) = get("/api/c4?group=package&groupDepth=3&include=com.example.")
        assertEquals(200, code)
        assertTrue(body.contains("Domain: com.example."))
    }
}
