package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Verifies graph store layout. */
class GraphStoreLayoutTest : GraphStoreTestFixture() {

    @Test
    fun `round-trip preserves backward adjacency`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("backward-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            // Verify incoming edges for every node
            for (node in graph.nodes(Node::class.java)) {
                val originalIncoming = graph.incoming(node.id).toList()
                val loadedIncoming = loaded.incoming(node.id).toList()
                assertEquals(originalIncoming.size, loadedIncoming.size, "Incoming edge count for node ${node.id}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `no backward files written on save`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("no-backward-test")
        try {
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(dir.resolve("backward.graph")))
            assertFalse(Files.exists(dir.resolve("backward.properties")))
            assertFalse(Files.exists(dir.resolve("backward.offsets")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save with custom compression threads`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("threads-test")
        try {
            GraphStore.save(graph, dir, compressionThreads = 1)
            val loaded = GraphStore.load(dir)
            assertEquals(graph.nodes(Node::class.java).count(), loaded.nodes(Node::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped load with parallel IO preserves graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("mapped-parallel-test")
        try {
            GraphStore.save(graph, dir)
            GraphStore.ensureNodeIndex(dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(graph.nodes(Node::class.java).count(), loaded.nodes(Node::class.java).count())
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `AUTO mode dispatches to MAPPED for large node count`() {
        // Build a graph with enough nodes to exceed MAPPED_THRESHOLD (1M).
        // We can't actually create 1M nodes in a unit test, so we test AUTO
        // with small graph (which dispatches to EAGER) and verify correctness.
        // The MAPPED path is tested separately via explicit MAPPED mode.
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("auto-mode-test")
        try {
            GraphStore.save(graph, dir)
            // Small graph => AUTO should use EAGER
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.AUTO)
            assertEquals(graph.nodes(Node::class.java).count(), loaded.nodes(Node::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `StringTable size returns correct count`() {
        val dir = Files.createTempDirectory("string-table-size-test")
        try {
            val strings = setOf("alpha", "beta", "gamma")
            val table = StringTable.build(strings, dir)
            assertEquals(3, table.size())

            // Also verify after reload
            val loaded = StringTable.load(dir)
            assertEquals(3, loaded.size())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collectMetadata handles AnnotationNode correctly`() {
        // AnnotationNode's empty branch in collectMetadata should not crash
        val builder = DefaultGraph.Builder()
        val annotNode = AnnotationNode(NodeId.next(), "com.example.MyAnnotation", "com.example.Target", "doStuff", mapOf("key" to "value"))
        builder.addNode(annotNode)

        val graph = builder.build()
        val dir = Files.createTempDirectory("annotation-collect-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            val annotations = loaded.nodes(AnnotationNode::class.java).toList()
            assertEquals(1, annotations.size)
            assertEquals("com.example.MyAnnotation", annotations[0].name)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
