package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration test using Android SDK (5.9M nodes).
 * Tests scalability at large graph sizes.
 */
class AndroidSdkIntegrationTest {

    companion object {
        private val androidJar: Path? by lazy {
            System.getProperty("android.jar.path")?.let { Path.of(it) }
        }
    }

    @org.junit.Before
    fun checkFixture() {
        assumeTrue("Android JAR not available", androidJar != null && Files.exists(androidJar!!))
    }

    @Test
    fun `load Android SDK and verify millions of nodes`() {
        val graph = loadGraph()
        val nodeCount = graph.nodes(Node::class.java).count()
        assertTrue(nodeCount > 1000000, "Android graph should have >1M nodes, got $nodeCount")
    }

    @Test
    fun `Cypher query works on Android graph`() {
        val graph = loadGraph()
        val result = graph.query("MATCH (n:CallSiteNode) RETURN count(*)")
        assertTrue(result.rows.isNotEmpty())
        val count = result.rows[0].values.first()
        assertTrue((count as Number).toLong() > 10000, "Should have >10K call sites")
    }

    @Test
    fun `save and load Android graph`() {
        val original = loadGraph()
        val dir = Files.createTempDirectory("android-webgraph")
        try {
            GraphStore.save(original, dir)
            val loaded = GraphStore.load(dir)
            val originalCount = original.nodes(Node::class.java).count()
            val loadedCount = loaded.nodes(Node::class.java).count()
            assertTrue(loadedCount == originalCount, "Node count should match: $originalCount vs $loadedCount")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun loadGraph(): Graph {
        return JavaProjectLoader(LoaderConfig(buildCallGraph = false)).load(androidJar!!)
    }
}
