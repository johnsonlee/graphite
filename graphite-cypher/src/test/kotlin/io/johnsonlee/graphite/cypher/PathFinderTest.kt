package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.BooleanConstant
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
    fun `depth zero returns the source and default bounds still find direct paths`() {
        val zeroDepth = PathFinder.findPaths(
            graph, setOf(nodeA), setOf(nodeA),
            edgeType = DataFlowEdge::class.java,
            minDepth = 0, maxDepth = 0
        )
        val defaultBounds = PathFinder.findPaths(
            graph, setOf(nodeA), setOf(nodeB),
            edgeType = DataFlowEdge::class.java
        )

        assertEquals(listOf(nodeA), zeroDepth.single().nodes.map(Node::id))
        assertEquals(listOf(nodeA, nodeB), defaultBounds.single().nodes.map(Node::id))
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
    fun `public path search returns targets in breadth first order`() {
        val source = IntConstant(NodeId.next(), 30)
        val middle = IntConstant(NodeId.next(), 31)
        val directTarget = IntConstant(NodeId.next(), 32)
        val deepTarget = IntConstant(NodeId.next(), 33)
        val sourceToMiddle = DataFlowEdge(source.id, middle.id, DataFlowKind.ASSIGN)
        val sourceToDirectTarget = DataFlowEdge(source.id, directTarget.id, DataFlowKind.ASSIGN)
        val base = DefaultGraph.Builder()
            .addNode(source)
            .addNode(middle)
            .addNode(directTarget)
            .addNode(deepTarget)
            .addEdge(DataFlowEdge(middle.id, deepTarget.id, DataFlowKind.ASSIGN))
            .build()
        val longBranchFirst = object : Graph by base {
            override fun outgoing(id: NodeId): Sequence<Edge> =
                if (id == source.id) sequenceOf(sourceToMiddle, sourceToDirectTarget) else base.outgoing(id)
        }

        val paths = PathFinder.findPaths(
            longBranchFirst,
            sources = setOf(source.id),
            targets = setOf(directTarget.id, deepTarget.id),
            edgeType = DataFlowEdge::class.java,
            minDepth = 1,
            maxDepth = 2
        )

        assertEquals(listOf(1, 2), paths.map { it.edges.size })
        assertEquals(listOf(directTarget.id, deepTarget.id), paths.map { it.nodes.last().id })
    }

    @Test
    fun `lazy match descends before retaining a wide sibling frontier`() {
        val source = IntConstant(NodeId.next(), 10)
        val middle = IntConstant(NodeId.next(), 11)
        val target = IntConstant(NodeId.next(), 12)
        val base = DefaultGraph.Builder()
            .addNode(source)
            .addNode(middle)
            .addNode(target)
            .addEdge(DataFlowEdge(source.id, middle.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(middle.id, target.id, DataFlowKind.ASSIGN))
            .build()
        val guarded = object : Graph by base {
            override fun outgoing(id: NodeId): Sequence<Edge> =
                if (id == source.id) {
                    sequence {
                        yieldAll(base.outgoing(id))
                        error("Lazy traversal consumed the source's sibling frontier before the first deep match")
                    }
                } else {
                    base.outgoing(id)
                }
        }

        val match = PathFinder.findPathMatches(
            guarded,
            setOf(source.id),
            PathFinder.SearchOptions(
                targets = setOf(target.id),
                edgeType = DataFlowEdge::class.java,
                minDepth = 2,
                maxDepth = 2,
                direction = PathFinder.Direction.OUTGOING
            )
        ).first()

        assertEquals(
            listOf(source.id, middle.id, target.id),
            match.materialize(null).nodes.map(Node::id)
        )
    }

    @Test
    fun `lazy match evicts old expansion memo entries after its fixed capacity`() {
        val source = IntConstant(NodeId.next(), 40)
        val middleIdOffset = 100_000
        val targetIdOffset = 200_000
        val width = 16_385
        val base = DefaultGraph.Builder()
            .addNode(source)
            .build()
        val virtualGraph = object : Graph by base {
            override fun node(id: NodeId): Node? = when (id.value) {
                source.id.value -> source
                in middleIdOffset until middleIdOffset + width -> IntConstant(id, id.value)
                in targetIdOffset until targetIdOffset + width -> IntConstant(id, id.value)
                else -> null
            }

            override fun outgoing(id: NodeId): Sequence<Edge> = when (id.value) {
                source.id.value -> (0 until width).asSequence().map { index ->
                    DataFlowEdge(source.id, NodeId(middleIdOffset + index), DataFlowKind.ASSIGN)
                }
                in middleIdOffset until middleIdOffset + width -> sequenceOf(
                    DataFlowEdge(
                        id,
                        NodeId(targetIdOffset + id.value - middleIdOffset),
                        DataFlowKind.ASSIGN
                    )
                )
                else -> emptySequence()
            }
        }

        val matches = PathFinder.findPathMatches(
            virtualGraph,
            setOf(source.id),
            PathFinder.SearchOptions(
                targets = null,
                edgeType = DataFlowEdge::class.java,
                minDepth = 2,
                maxDepth = 2,
                direction = PathFinder.Direction.OUTGOING
            )
        ).count()

        assertEquals(width, matches)
    }

    @Test
    fun `shallower revisit expands a node again within the remaining depth`() {
        val source = IntConstant(NodeId.next(), 20)
        val detour = IntConstant(NodeId.next(), 21)
        val junction = IntConstant(NodeId.next(), 22)
        val predecessor = IntConstant(NodeId.next(), 23)
        val target = IntConstant(NodeId.next(), 24)
        val sourceToDetour = DataFlowEdge(source.id, detour.id, DataFlowKind.ASSIGN)
        val sourceToJunction = DataFlowEdge(source.id, junction.id, DataFlowKind.ASSIGN)
        val base = DefaultGraph.Builder()
            .addNode(source)
            .addNode(detour)
            .addNode(junction)
            .addNode(predecessor)
            .addNode(target)
            .addEdge(DataFlowEdge(detour.id, junction.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(junction.id, predecessor.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(predecessor.id, target.id, DataFlowKind.ASSIGN))
            .build()
        val longBranchFirst = object : Graph by base {
            override fun outgoing(id: NodeId): Sequence<Edge> =
                if (id == source.id) sequenceOf(sourceToDetour, sourceToJunction) else base.outgoing(id)
        }

        val path = PathFinder.findPathMatches(
            longBranchFirst,
            setOf(source.id),
            PathFinder.SearchOptions(
                targets = setOf(target.id),
                edgeType = DataFlowEdge::class.java,
                minDepth = 1,
                maxDepth = 3,
                direction = PathFinder.Direction.OUTGOING
            )
        ).single().materialize(null)

        assertEquals(
            listOf(source.id, junction.id, predecessor.id, target.id),
            path.nodes.map(Node::id)
        )
    }

    @Test
    fun `deeper revisit expands a node again for exact depth matches`() {
        val source = IntConstant(NodeId.next(), 25)
        val detour = IntConstant(NodeId.next(), 26)
        val junction = IntConstant(NodeId.next(), 27)
        val target = IntConstant(NodeId.next(), 28)
        val graph = DefaultGraph.Builder()
            .addNode(source)
            .addNode(detour)
            .addNode(junction)
            .addNode(target)
            .addEdge(DataFlowEdge(source.id, junction.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(source.id, detour.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(detour.id, junction.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(junction.id, target.id, DataFlowKind.ASSIGN))
            .build()

        val path = PathFinder.findPathMatches(
            graph,
            setOf(source.id),
            PathFinder.SearchOptions(
                targets = setOf(target.id),
                edgeType = DataFlowEdge::class.java,
                minDepth = 3,
                maxDepth = 3,
                direction = PathFinder.Direction.OUTGOING
            )
        ).single().materialize(null)

        assertEquals(listOf(source.id, detour.id, junction.id, target.id), path.nodes.map(Node::id))
    }

    @Test
    fun `revisited node expands when a different ancestor set makes its suffix legal`() {
        val source = BooleanConstant(NodeId.next(), true)
        val ancestor = BooleanConstant(NodeId.next(), true)
        val alternate = BooleanConstant(NodeId.next(), true)
        val junction = BooleanConstant(NodeId.next(), true)
        val target = IntConstant(NodeId.next(), 42)
        val graph = DefaultGraph.Builder()
            .addNode(source)
            .addNode(ancestor)
            .addNode(alternate)
            .addNode(junction)
            .addNode(target)
            .addEdge(DataFlowEdge(source.id, ancestor.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(source.id, alternate.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(ancestor.id, junction.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(ancestor.id, target.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(alternate.id, junction.id, DataFlowKind.ASSIGN))
            .addEdge(DataFlowEdge(junction.id, ancestor.id, DataFlowKind.ASSIGN))
            .build()

        val result = CypherExecutor(graph).execute(
            "MATCH (a:BooleanConstant)-[:DATAFLOW*4..4]->(b:IntConstant) RETURN b.id AS id"
        )

        assertEquals(listOf(mapOf("id" to target.id.value)), result.rows)
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
