package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
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
    fun `budgeted lazy match materializes its parent linked path`() {
        val tracker = CypherWorkTracker(CypherExecutionBudget(maxWorkUnits = 20))
        val match = PathFinder.findPathMatches(
            graph,
            setOf(nodeA),
            PathFinder.SearchOptions(
                targets = setOf(nodeD),
                edgeType = DataFlowEdge::class.java,
                minDepth = 1,
                maxDepth = 5,
                direction = PathFinder.Direction.OUTGOING,
                workTracker = tracker
            )
        ).single()

        assertEquals(nodeD, match.endNode().id)
        val path = match.materialize(tracker)
        assertEquals(listOf(nodeA, nodeB, nodeC, nodeD), path.nodes.map(Node::id))
        assertEquals(3, path.edges.size)
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

    @Test
    fun `enumerates distinct trails to the same endpoint`() {
        val left = NodeId.next()
        val right = NodeId.next()
        val end = NodeId.next()
        val builder = DefaultGraph.Builder()
        listOf(nodeA, left, right, end).forEachIndexed { value, id ->
            builder.addNode(IntConstant(id, value))
        }
        builder.addEdge(DataFlowEdge(nodeA, left, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(nodeA, right, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(left, end, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(right, end, DataFlowKind.ASSIGN))

        val paths = PathFinder.findPaths(
            builder.build(),
            setOf(nodeA),
            setOf(end),
            edgeType = DataFlowEdge::class.java,
            minDepth = 2,
            maxDepth = 2
        )

        assertEquals(
            setOf(listOf(nodeA, left, end), listOf(nodeA, right, end)),
            paths.map { it.nodes.map(Node::id) }.toSet()
        )
    }

    @Test
    fun `unbounded search goes beyond ten hops`() {
        val ids = List(12) { NodeId.next() }
        val builder = DefaultGraph.Builder()
        ids.forEachIndexed { value, id -> builder.addNode(IntConstant(id, value)) }
        ids.zipWithNext().forEach { (from, to) ->
            builder.addEdge(DataFlowEdge(from, to, DataFlowKind.ASSIGN))
        }

        val paths = PathFinder.findPaths(
            builder.build(),
            setOf(ids.first()),
            setOf(ids.last()),
            edgeType = DataFlowEdge::class.java
        )

        assertEquals(1, paths.size)
        assertEquals(11, paths.single().edges.size)
    }

    @Test
    fun `unbounded search allows repeated nodes but never repeated relationships`() {
        val first = NodeId.next()
        val second = NodeId.next()
        val builder = DefaultGraph.Builder()
        builder.addNode(IntConstant(first, 1))
        builder.addNode(IntConstant(second, 2))
        builder.addEdge(DataFlowEdge(first, second, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(second, first, DataFlowKind.RETURN_VALUE))

        val paths = PathFinder.findPaths(
            builder.build(),
            setOf(first),
            setOf(first),
            edgeType = DataFlowEdge::class.java
        )

        assertEquals(1, paths.size)
        assertEquals(listOf(first, second, first), paths.single().nodes.map(Node::id))
        assertEquals(2, paths.single().edges.size)
    }

    @Test
    fun `edge predicate applies to every hop`() {
        val paths = PathFinder.findPathMatches(
            graph,
            setOf(nodeA),
            PathFinder.SearchOptions(
                targets = null,
                edgeType = DataFlowEdge::class.java,
                minDepth = 1,
                maxDepth = null,
                direction = PathFinder.Direction.OUTGOING,
                edgeFilter = { edge -> edge is DataFlowEdge && edge.kind == DataFlowKind.ASSIGN }
            )
        ).map { it.materialize(null) }.toList()

        assertEquals(listOf(listOf(nodeA, nodeB), listOf(nodeA, nodeB, nodeC)), paths.map { path ->
            path.nodes.map(Node::id)
        })
    }
}
