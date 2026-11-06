package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ConstantNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph store. */
class GraphStoreTest : GraphStoreTestFixture() {

    @Test
    fun `save preserves its published three argument JVM descriptor`() {
        val parameterTypes = arrayOf(Graph::class.java, Path::class.java, Integer.TYPE)

        assertNotNull(GraphStore::class.java.getDeclaredMethod("save", *parameterTypes))
        assertNotNull(
            GraphStore::class
                .java
                .getDeclaredMethod(
                    "save\$default",
                    GraphStore::class.java,
                    Graph::class.java,
                    Path::class.java,
                    Integer.TYPE,
                    Integer.TYPE,
                    Any::class.java))
    }

    @Test
    fun `saving the same graph twice writes byte-identical files`() {
        val returnType = TypeDescriptor("void")
        val graph =
            DefaultGraph.Builder()
                .apply {
                    repeat(64) { nodeId ->
                        addNode(
                            CallSiteNode(
                                NodeId(nodeId),
                                MethodDescriptor(TypeDescriptor("example.Caller${nodeId % 4}"), "call", emptyList(), returnType),
                                MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke$nodeId", emptyList(), returnType),
                                nodeId,
                                null,
                                emptyList()))
                    }
                    repeat(63) { nodeId -> addEdge(DataFlowEdge(NodeId(nodeId), NodeId(nodeId + 1), DataFlowKind.ASSIGN)) }
                }
                .build()
        val first = Files.createTempDirectory("webgraph-stable-first")
        val second = Files.createTempDirectory("webgraph-stable-second")
        try {
            GraphStore.save(graph, first)
            Thread.sleep(1_100)
            GraphStore.save(graph, second)
            val names = Files.list(first).use { it.map { p -> p.fileName.toString() }.toList() }.sorted()
            assertEquals(names, Files.list(second).use { it.map { p -> p.fileName.toString() }.toList() }.sorted())
            for (name in names) {
                assertTrue(
                    Files.readAllBytes(first.resolve(name)).contentEquals(Files.readAllBytes(second.resolve(name))),
                    "$name differs between two saves of the same graph")
            }
            val properties = Files.readAllLines(first.resolve("forward.properties"))
            assertTrue(properties.none { it.startsWith("#") }, "forward.properties still carries comments: $properties")
            assertTrue(properties.any { it.startsWith("arcs=") })
            // The stripped file still loads: the persisted graph round-trips.
            assertEquals(64, GraphStore.load(first).nodes(Node::class.java).count())
        } finally {
            first.toFile().deleteRecursively()
            second.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip save and load preserves nodes`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // Verify nodes
            val originalNodes = graph.nodes(Node::class.java).toList()
            val loadedNodes = loaded.nodes(Node::class.java).toList()
            assertEquals(originalNodes.size, loadedNodes.size, "Node count should match")

            for (node in originalNodes) {
                assertNotNull(loaded.node(node.id), "Node ${node.id} should exist in loaded graph")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves outgoing edges`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val nodes = graph.nodes(Node::class.java).toList()
            for (node in nodes) {
                val originalEdges = graph.outgoing(node.id).toList()
                val loadedEdges = loaded.outgoing(node.id).toList()
                assertEquals(originalEdges.size, loadedEdges.size, "Outgoing edge count should match for node ${node.id}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves incoming edges`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val nodes = graph.nodes(Node::class.java).toList()
            for (node in nodes) {
                val originalEdges = graph.incoming(node.id).toList()
                val loadedEdges = loaded.incoming(node.id).toList()
                assertEquals(originalEdges.size, loadedEdges.size, "Incoming edge count should match for node ${node.id}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip exposes precomputed edge count`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(graph.edgeCount(), loaded.edgeCount())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves typed edge queries`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val node = graph.nodes(Node::class.java).first()
            val originalDataFlow = graph.outgoing(node.id, DataFlowEdge::class.java).toList()
            val loadedDataFlow = loaded.outgoing(node.id, DataFlowEdge::class.java).toList()
            assertEquals(originalDataFlow.size, loadedDataFlow.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves methods`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val originalMethods = graph.methods(MethodPattern()).toList()
            val loadedMethods = loaded.methods(MethodPattern()).toList()
            assertEquals(originalMethods.size, loadedMethods.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load from nonexistent directory throws`() {
        val file = Files.createTempFile("not", "dir")
        try {
            val failure = assertFailsWith<IllegalArgumentException> { GraphStore.load(file) }
            assertTrue(failure.message.orEmpty().contains("not a directory", ignoreCase = true))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `empty graph round-trips`() {
        val graph = DefaultGraph.Builder().build()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            assertEquals(0, loaded.nodes(Node::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves all constant node types`() {
        val builder = DefaultGraph.Builder()
        val strConst = StringConstant(NodeId.next(), "hello")
        val longConst = LongConstant(NodeId.next(), 123456789L)
        val floatConst = FloatConstant(NodeId.next(), 3.14f)
        val doubleConst = DoubleConstant(NodeId.next(), 2.718281828)
        val boolConst = BooleanConstant(NodeId.next(), true)
        val nullConst = NullConstant(NodeId.next())
        val intConst = IntConstant(NodeId.next(), 99)

        builder.addNode(strConst)
        builder.addNode(longConst)
        builder.addNode(floatConst)
        builder.addNode(doubleConst)
        builder.addNode(boolConst)
        builder.addNode(nullConst)
        builder.addNode(intConst)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-const-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedStr = loaded.node(strConst.id) as StringConstant
            assertEquals("hello", loadedStr.value)

            val loadedLong = loaded.node(longConst.id) as LongConstant
            assertEquals(123456789L, loadedLong.value)

            val loadedFloat = loaded.node(floatConst.id) as FloatConstant
            assertEquals(3.14f, loadedFloat.value)

            val loadedDouble = loaded.node(doubleConst.id) as DoubleConstant
            assertEquals(2.718281828, loadedDouble.value)

            val loadedBool = loaded.node(boolConst.id) as BooleanConstant
            assertEquals(true, loadedBool.value)

            val loadedNull = loaded.node(nullConst.id) as NullConstant
            assertNull(loadedNull.value)

            assertEquals(7, loaded.nodes(ConstantNode::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
