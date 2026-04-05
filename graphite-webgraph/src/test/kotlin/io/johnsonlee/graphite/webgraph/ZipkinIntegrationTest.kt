package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test using Zipkin Server fat JAR from Maven Central.
 * Tests end-to-end: load bytecode -> build graph -> save WebGraph -> load back -> verify.
 */
class ZipkinIntegrationTest {

    companion object {
        private val zipkinJar: Path? by lazy {
            val resource = System.getProperty("zipkin.jar.path")
            if (resource != null) Path.of(resource)
            else {
                // Try to find from Gradle resolved configuration
                val gradleCache = Path.of(System.getProperty("user.home"), ".gradle", "caches")
                if (!Files.exists(gradleCache)) return@lazy null
                Files.walk(gradleCache)
                    .filter { it.fileName.toString() == "zipkin-server-3.5.1-exec.jar" }
                    .findFirst()
                    .orElse(null)
            }
        }
    }

    @org.junit.Before
    fun checkFixture() {
        assumeTrue("Zipkin JAR not available", zipkinJar != null && Files.exists(zipkinJar!!))
    }

    @Test
    fun `load Zipkin and verify graph has nodes and edges`() {
        val graph = loadZipkinGraph()

        val nodeCount = graph.nodes(Node::class.java).count()
        assertTrue(nodeCount > 100, "Zipkin graph should have many nodes, got $nodeCount")

        val callSiteCount = graph.nodes(CallSiteNode::class.java).count()
        assertTrue(callSiteCount > 10, "Should have call sites, got $callSiteCount")
    }

    @Test
    fun `round-trip Zipkin graph through WebGraph preserves node count`() {
        val original = loadZipkinGraph()
        val dir = Files.createTempDirectory("zipkin-webgraph")
        try {
            GraphStore.save(original, dir)
            val loaded = GraphStore.load(dir)

            val originalCount = original.nodes(Node::class.java).count()
            val loadedCount = loaded.nodes(Node::class.java).count()
            assertEquals(originalCount, loadedCount, "Node count should match after round-trip")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves Zipkin annotations`() {
        val original = loadZipkinGraph()
        val dir = Files.createTempDirectory("zipkin-webgraph")
        try {
            GraphStore.save(original, dir)
            val loaded = GraphStore.load(dir)

            // Find a class with annotations in both
            val fieldNodes = original.nodes(FieldNode::class.java).take(10).toList()
            for (field in fieldNodes) {
                val className = field.descriptor.declaringClass.className
                val fieldName = field.descriptor.name
                val originalAnnotations = original.memberAnnotations(className, fieldName)
                val loadedAnnotations = loaded.memberAnnotations(className, fieldName)
                assertEquals(originalAnnotations, loadedAnnotations,
                    "Annotations for $className.$fieldName should match")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves Zipkin methods`() {
        val original = loadZipkinGraph()
        val dir = Files.createTempDirectory("zipkin-webgraph")
        try {
            GraphStore.save(original, dir)
            val loaded = GraphStore.load(dir)

            val originalMethods = original.methods(MethodPattern()).count()
            val loadedMethods = loaded.methods(MethodPattern()).count()
            assertEquals(originalMethods, loadedMethods, "Method count should match")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun loadZipkinGraph(): Graph {
        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("zipkin2"),
            buildCallGraph = false
        ))
        return loader.load(zipkinJar!!)
    }
}
