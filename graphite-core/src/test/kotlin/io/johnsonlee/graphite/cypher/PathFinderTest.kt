package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathFinderTest {

    private lateinit var graph: Graph
    private var nodeA = NodeId(0)
    private var nodeB = NodeId(0)
    private var nodeC = NodeId(0)
    private var nodeD = NodeId(0)

    @Before
    fun setup() {
        NodeId.reset()

        val type = TypeDescriptor("com.example.Test")
        val intType = TypeDescriptor("int")
        val method = MethodDescriptor(type, "test", emptyList(), intType)

        nodeA = NodeId.next()
        nodeB = NodeId.next()
        nodeC = NodeId.next()
        nodeD = NodeId.next()

        val builder = DefaultGraph.Builder()
        builder.addNode(IntConstant(nodeA, 1))
        builder.addNode(LocalVariable(nodeB, "b", intType, method))
        builder.addNode(LocalVariable(nodeC, "c", intType, method))
        builder.addNode(CallSiteNode(nodeD, method, method, 10, null, emptyList()))

        // A -> B -> C -> D (dataflow chain)
        builder.addEdge(DataFlowEdge(nodeA, nodeB, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(nodeB, nodeC, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(nodeC, nodeD, DataFlowKind.PARAMETER_PASS))

        // A -> D (call edge, different type)
        builder.addEdge(CallEdge(nodeA, nodeD, false))

        graph = builder.build()
    }

    @Test
    fun `find direct paths depth 1`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), setOf(nodeB),
            edgeType = DataFlowEdge::class.java,
            minDepth = 1, maxDepth = 1
        )
        assertEquals(1, paths.size)
        assertEquals(2, paths[0].nodes.size)
        assertEquals(nodeA, paths[0].nodes[0].id)
        assertEquals(nodeB, paths[0].nodes[1].id)
    }

    @Test
    fun `find multi-hop paths`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), setOf(nodeD),
            edgeType = DataFlowEdge::class.java,
            minDepth = 1, maxDepth = 5
        )
        assertEquals(1, paths.size)
        assertEquals(4, paths[0].nodes.size) // A -> B -> C -> D
    }

    @Test
    fun `depth limit prevents finding distant nodes`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), setOf(nodeD),
            edgeType = DataFlowEdge::class.java,
            minDepth = 1, maxDepth = 2
        )
        assertEquals(0, paths.size)
    }

    @Test
    fun `filter by edge type`() {
        // Only call edges from A to D
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), setOf(nodeD),
            edgeType = CallEdge::class.java,
            minDepth = 1, maxDepth = 1
        )
        assertEquals(1, paths.size)
    }

    @Test
    fun `no edge type filter follows all edges`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), null,
            edgeType = null,
            minDepth = 1, maxDepth = 1
        )
        // A has outgoing to B (dataflow) and D (call)
        assertEquals(2, paths.size)
    }

    @Test
    fun `incoming direction`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeB), setOf(nodeA),
            edgeType = DataFlowEdge::class.java,
            minDepth = 1, maxDepth = 1,
            direction = PathFinder.Direction.INCOMING
        )
        assertEquals(1, paths.size)
        assertEquals(nodeA, paths[0].nodes.last().id)
    }

    @Test
    fun `null targets returns all reachable nodes`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), null,
            edgeType = DataFlowEdge::class.java,
            minDepth = 1, maxDepth = 5
        )
        // Should find paths to B, C, D
        assertTrue(paths.size >= 3)
    }

    @Test
    fun `empty sources returns no paths`() {
        val paths = PathFinder.findPaths(
            graph, emptySet(), setOf(nodeD),
            edgeType = null,
            minDepth = 1, maxDepth = 5
        )
        assertEquals(0, paths.size)
    }

    @Test
    fun `nonexistent source returns no paths`() {
        val paths = PathFinder.findPaths(
            graph, setOf(NodeId(9999)), setOf(nodeD),
            edgeType = null,
            minDepth = 1, maxDepth = 5
        )
        assertEquals(0, paths.size)
    }

    @Test
    fun `both direction finds paths`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeB), null,
            edgeType = DataFlowEdge::class.java,
            minDepth = 1, maxDepth = 1,
            direction = PathFinder.Direction.BOTH
        )
        // B has outgoing to C and incoming from A
        assertTrue(paths.size >= 2, "Should find paths in both directions, got ${paths.size}")
    }

    @Test
    fun `both direction with edge type filter`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), setOf(nodeD),
            edgeType = null,
            minDepth = 1, maxDepth = 1,
            direction = PathFinder.Direction.BOTH
        )
        // A -> D via call edge (depth 1, both direction)
        assertTrue(paths.isNotEmpty())
    }

    @Test
    fun `minDepth filters short paths`() {
        val paths = PathFinder.findPaths(
            graph, setOf(nodeA), null,
            edgeType = DataFlowEdge::class.java,
            minDepth = 2, maxDepth = 5
        )
        // Should not include A->B (depth 1), but include A->B->C (depth 2) and longer
        assertTrue(paths.all { it.edges.size >= 2 })
    }
}
