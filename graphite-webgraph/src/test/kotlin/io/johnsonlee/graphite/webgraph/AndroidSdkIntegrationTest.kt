package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import org.junit.Assume.assumeTrue
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        releaseLargeGraphMemory()
    }

    @org.junit.After
    @Suppress("ExplicitGarbageCollectionCall")
    fun releaseLargeGraphMemory() {
        System.gc()
        System.runFinalization()
    }

    @Test
    fun `load Android SDK and verify millions of nodes`() {
        val graph = loadGraph()
        try {
            val nodeCount = graph.nodes(Node::class.java).count()
            assertTrue(nodeCount > 1000000, "Android graph should have >1M nodes, got $nodeCount")
        } finally {
            closeQuietly(graph)
        }
    }

    @Test
    fun `Cypher query works on Android graph`() {
        val graph = loadGraph()
        try {
            val result = graph.query("MATCH (n:CallSiteNode) RETURN count(*)")
            assertTrue(result.rows.isNotEmpty())
            val count = result.rows[0].values.first()
            assertTrue((count as Number).toLong() > 10000, "Should have >10K call sites")
        } finally {
            closeQuietly(graph)
        }
    }

    @Test
    fun `save and load Android graph`() {
        val resourcePath = "AndroidManifest.xml"
        val expectedResource = JarFile(androidJar!!.toFile()).use { jar ->
            val entry = requireNotNull(jar.getJarEntry(resourcePath)) {
                "Android fixture does not contain $resourcePath"
            }
            jar.getInputStream(entry).use { it.readBytes() }
        }
        val original = loadGraph()
        val dir = Files.createTempDirectory("android-webgraph")
        try {
            assertContentEquals(
                expectedResource,
                original.resources.open(resourcePath).use { it.readBytes() },
                "Source graph must expose the real Android fixture resource"
            )
            GraphStore.save(original, dir)
            assertTrue(Files.isRegularFile(dir.resolve("graph.resources")))
            val loaded = GraphStore.load(dir)
            try {
                val originalCount = original.nodes(Node::class.java).count()
                val loadedCount = loaded.nodes(Node::class.java).count()
                assertTrue(loadedCount == originalCount, "Node count should match: $originalCount vs $loadedCount")
                assertContentEquals(
                    expectedResource,
                    loaded.resources.open(resourcePath).use { it.readBytes() },
                    "Persisted graph must preserve the real Android fixture resource"
                )
            } finally {
                closeQuietly(loaded)
            }
        } finally {
            closeQuietly(original)
            dir.toFile().deleteRecursively()
        }
    }

    private fun loadGraph(): Graph {
        return JavaProjectLoader(
            LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )
        ).load(androidJar!!)
    }

    private fun closeQuietly(graph: Graph) {
        runCatching { (graph as? Closeable)?.close() }
    }
}
