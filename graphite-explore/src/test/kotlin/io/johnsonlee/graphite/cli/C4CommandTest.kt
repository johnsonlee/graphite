package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.c4.C4Tags
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class C4CommandTest {

    private fun savedGraph(): Path {
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
        val dir = Files.createTempDirectory("c4-cmd")
        GraphStore.save(graph, dir)
        return dir
    }

    private inline fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `c4 subcommand renders plantuml to stdout`() {
        val dir = savedGraph()
        try {
            val out = captureStdout {
                val exit = CommandLine(C4Command()).execute(dir.toString(), "--include", "com.example.")
                assertEquals(0, exit)
            }
            assertTrue(out.contains("@startuml"))
            assertTrue(out.contains("Component(component.api"))
            assertTrue(out.contains("@enduml"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `c4 subcommand writes mermaid output to file`() {
        val dir = savedGraph()
        val output = Files.createTempFile("c4-out", ".md")
        try {
            val exit = CommandLine(C4Command()).execute(
                dir.toString(),
                "--include", "com.example.",
                "--format", "mermaid",
                "-o", output.toString()
            )
            assertEquals(0, exit)
            val written = Files.readString(output)
            assertTrue(written.contains("```mermaid"))
            assertTrue(written.contains("C4Component"))
        } finally {
            Files.deleteIfExists(output)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `c4 subcommand emits json with model fields`() {
        val dir = savedGraph()
        try {
            val out = captureStdout {
                val exit = CommandLine(C4Command()).execute(
                    dir.toString(),
                    "--include", "com.example.",
                    "--format", "json"
                )
                assertEquals(0, exit)
            }
            assertTrue(out.contains("\"externalContainers\""))
            assertTrue(out.contains("\"Database\""))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `c4 subcommand rejects unknown level`() {
        val dir = savedGraph()
        try {
            val exit = CommandLine(C4Command()).execute(dir.toString(), "--level", "bogus")
            assertEquals(1, exit)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `c4 subcommand rejects unknown format`() {
        val dir = savedGraph()
        try {
            val exit = CommandLine(C4Command()).execute(dir.toString(), "--format", "bogus")
            assertEquals(1, exit)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `c4 subcommand rejects non-directory input`() {
        val file = Files.createTempFile("not-a-graph", ".txt")
        try {
            val exit = CommandLine(C4Command()).execute(file.toString())
            assertEquals(1, exit)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
