package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.cypher.query
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
 * Integration test using the current Elasticsearch core JAR fixture.
 */
class ElasticsearchIntegrationTest {

    companion object {
        private val esJar: Path? by lazy {
            System.getProperty("elasticsearch.jar.path")?.let { Path.of(it) }
        }
    }

    @org.junit.Before
    fun checkFixture() {
        assumeTrue("Elasticsearch JAR not available", esJar != null && Files.exists(esJar!!))
    }

    @Test
    fun `load Elasticsearch and verify graph has many nodes`() {
        val graph = loadGraph()
        val nodeCount = graph.nodes(Node::class.java).count()
        assertTrue(nodeCount > 500, "ES graph should have >500 nodes, got $nodeCount")
    }

    @Test
    fun `round-trip preserves node count`() {
        val original = loadGraph()
        val dir = Files.createTempDirectory("es-webgraph")
        try {
            GraphStore.save(original, dir)
            val loaded = GraphStore.load(dir)
            val originalCount = original.nodes(Node::class.java).count()
            val loadedCount = loaded.nodes(Node::class.java).count()
            assertEquals(originalCount, loadedCount)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves methods`() {
        val original = loadGraph()
        val dir = Files.createTempDirectory("es-webgraph")
        try {
            GraphStore.save(original, dir)
            val loaded = GraphStore.load(dir)
            assertEquals(
                original.methods(MethodPattern()).count(),
                loaded.methods(MethodPattern()).count()
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Cypher query works on loaded graph`() {
        val graph = loadGraph()
        val result = graph.query("MATCH (n:CallSiteNode) RETURN count(*)")
        assertTrue(result.rows.isNotEmpty())
        val count = result.rows[0].values.first()
        assertTrue((count as Number).toLong() > 100, "Should have >100 call sites, got $count")
    }

    private fun loadGraph(): Graph {
        return JavaProjectLoader(
            LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )
        ).load(esJar!!)
    }
}
