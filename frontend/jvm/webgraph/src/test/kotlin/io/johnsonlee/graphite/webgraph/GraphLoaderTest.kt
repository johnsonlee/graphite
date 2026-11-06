package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.MmapGraphBuilder
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph loader. */
class GraphLoaderTest : GraphStoreTestFixture() {

    @Test
    fun `load with explicit EAGER mode preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-eager-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.EAGER)
            assertGraphOperations(graph, loaded)
            assertEquals(graph.methodCount(), loaded.methodCount())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load with MAPPED mode preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-test")
        try {
            GraphStore.save(graph, dir)
            GraphStore.ensureNodeIndex(dir)
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.MAPPED)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as? Closeable)?.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load with AUTO mode preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-auto-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.AUTO)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as? Closeable)?.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadMapped preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-loadmapped-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, loaded)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save from core mmap graph uses streaming outgoing traversal`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.MmapSource"), "run", emptyList(), TypeDescriptor("void"))
        val graph =
            MmapGraphBuilder()
                .addNode(IntConstant(NodeId(0), 1))
                .addNode(StringConstant(NodeId(1), "two"))
                .addNode(ReturnNode(NodeId(2), method))
                .addNode(BooleanConstant(NodeId(3), true))
                .addEdge(DataFlowEdge(NodeId(0), NodeId(2), DataFlowKind.RETURN_VALUE))
                .addEdge(DataFlowEdge(NodeId(1), NodeId(2), DataFlowKind.PARAMETER_PASS))
                .addEdge(ControlFlowEdge(NodeId(3), NodeId(2), ControlFlowKind.BRANCH_TRUE, BranchComparison(ComparisonOp.EQ, NodeId(0))))
                .addMethod(method)
                .build()
        val dir = Files.createTempDirectory("webgraph-save-mmap-source-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(4L, loaded.nodeCount(Node::class.java))
                assertEquals(3L, loaded.edgeCount())
                assertEquals(graph.outgoing(NodeId(0)).toList(), loaded.outgoing(NodeId(0)).toList())
                assertEquals(graph.incoming(NodeId(2)).toList(), loaded.incoming(NodeId(2)).toList())
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save collects metadata from graph fallback scans`() {
        val base = buildTestGraph()
        val graph = MetadataFallbackGraph(base)
        val dir = Files.createTempDirectory("webgraph-metadata-fallback-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(2L, loaded.methodCount())
                assertEquals(listOf(1, "active"), loaded.enumValues("com.example.Status", "ACTIVE"))
                assertEquals(base.memberAnnotations("com.example.Foo", "bar"), loaded.memberAnnotations("com.example.Foo", "bar"))
                assertEquals(1, loaded.methods(MethodPattern(name = "bar")).count())
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped incoming query persists compressed backward graph lazily`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-backward-test")
        try {
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(dir.resolve("backward.graph")))
            assertFalse(Files.exists(dir.resolve("backward.properties")))

            val nodeWithIncoming = graph.nodes(Node::class.java).first { graph.incoming(it.id).any() }
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(graph.incoming(nodeWithIncoming.id).count(), loaded.incoming(nodeWithIncoming.id).count())
                assertTrue(Files.exists(dir.resolve("backward.graph")))
                assertTrue(Files.exists(dir.resolve("backward.properties")))
            } finally {
                (loaded as Closeable).close()
            }

            val reloaded = GraphStore.loadMapped(dir)
            try {
                assertGraphOperations(graph, reloaded)
            } finally {
                (reloaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ensureNodeIndex is idempotent`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-idempotent-index-test")
        try {
            GraphStore.save(graph, dir)
            val indexFile = dir.resolve("graph.nodeindex")
            assertTrue(Files.exists(indexFile), "Index file should exist after save")
            val sizeAfterSave = Files.size(indexFile)

            // Call ensureNodeIndex -- should be a no-op since index already exists
            GraphStore.ensureNodeIndex(dir)
            val sizeAfterEnsure = Files.size(indexFile)
            assertEquals(sizeAfterSave, sizeAfterEnsure, "Index file should not change when already present")

            // Delete the index and call ensureNodeIndex -- should rebuild
            Files.delete(indexFile)
            assertTrue(!Files.exists(indexFile))
            GraphStore.ensureNodeIndex(dir)
            assertTrue(Files.exists(indexFile), "Index file should be rebuilt")
            val sizeAfterRebuild = Files.size(indexFile)
            assertEquals(sizeAfterSave, sizeAfterRebuild, "Rebuilt index should have same size")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `MappedWebGraphBackedGraph close is safe`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-close-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir) as Closeable

            // Access a node to exercise the mapped path
            val mappedGraph = loaded as Graph
            val firstNode = mappedGraph.nodes(Node::class.java).first()
            assertNotNull(mappedGraph.node(firstNode.id))

            // Close should not throw
            loaded.close()

            // Calling close again should also be safe
            loaded.close()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadMapped from nonexistent directory throws`() {
        assertFailsWith<IllegalArgumentException> { GraphStore.loadMapped(Files.createTempFile("not", "dir")) }
    }

    @Test
    fun `loadMapped without node index throws`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-no-index-test")
        try {
            GraphStore.save(graph, dir)
            // Delete node index
            Files.delete(dir.resolve("graph.nodeindex"))
            assertFailsWith<IllegalArgumentException> { GraphStore.loadMapped(dir) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph typed outgoing and incoming edges`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-typed-edges-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val callSites = loaded.nodes(CallSiteNode::class.java).toList()
                assertEquals(1, callSites.size)
                val cs = callSites[0]

                // Typed outgoing
                val callEdges = loaded.outgoing(cs.id, CallEdge::class.java).toList()
                assertEquals(1, callEdges.size)

                val dataFlowEdges = loaded.outgoing(cs.id, DataFlowEdge::class.java).toList()
                assertEquals(0, dataFlowEdges.size)

                // Typed incoming
                val locals = loaded.nodes(LocalVariable::class.java).toList()
                assertEquals(1, locals.size)
                val incomingDf = loaded.incoming(locals[0].id, DataFlowEdge::class.java).toList()
                assertEquals(2, incomingDf.size)
                val incomingCall = loaded.incoming(locals[0].id, CallEdge::class.java).toList()
                assertEquals(0, incomingCall.size)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph out-of-bounds NodeId returns empty`() {
        val builder = DefaultGraph.Builder()
        val node = IntConstant(NodeId(0), 1)
        builder.addNode(node)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-mapped-oob-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val bigId = NodeId(999999)
                assertEquals(0, loaded.outgoing(bigId).count())
                assertEquals(0, loaded.incoming(bigId).count())
                assertNull(loaded.node(bigId))
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph nodes with supertype returns all subtypes`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-supertype-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                val constants = loaded.nodes(ConstantNode::class.java).toList()
                assertTrue(constants.size >= 2, "Should find at least IntConstant and EnumConstant")

                val allNodes = loaded.nodes(Node::class.java).toList()
                assertEquals(8, allNodes.size)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph branch scopes round-trip`() {
        val graph = buildBranchScopeGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-branch-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertBranchScopeOperations(loaded)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph resources preserves persisted text resources`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(1, loaded.resources.list("**").count())
                val content = loaded.resources.open("application.properties").bufferedReader().readText()
                assertTrue(content.contains("feature.mode=shadow"))
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
