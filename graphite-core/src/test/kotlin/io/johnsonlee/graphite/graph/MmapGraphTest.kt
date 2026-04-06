package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [MmapGraph] -- the disk-backed Graph implementation.
 *
 * These tests verify that [MmapGraphBuilder.build] returns an [MmapGraph]
 * (not a [DefaultGraph]) and that all Graph operations work correctly
 * when data is read from disk on demand.
 */
class MmapGraphTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    private fun makeMethod(className: String, name: String, params: List<String> = emptyList()): MethodDescriptor {
        return MethodDescriptor(
            TypeDescriptor(className),
            name,
            params.map { TypeDescriptor(it) },
            TypeDescriptor("void")
        )
    }

    // ========================================================================
    // Verify MmapGraphBuilder.build() returns MmapGraph
    // ========================================================================

    @Test
    fun `build returns MmapGraph instance`() {
        val graph = MmapGraphBuilder().addNode(IntConstant(NodeId.next(), 1)).build()
        assertIs<MmapGraph>(graph)
    }

    // ========================================================================
    // Node lookup by ID
    // ========================================================================

    @Test
    fun `node returns correct node by ID`() {
        val id = NodeId.next()
        val node = IntConstant(id, 42)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `node returns null for unknown ID`() {
        val graph = MmapGraphBuilder().addNode(IntConstant(NodeId.next(), 1)).build()
        assertNull(graph.node(NodeId(9999)))
    }

    // ========================================================================
    // nodes(type) queries
    // ========================================================================

    @Test
    fun `nodes by exact type returns matching nodes`() {
        val id1 = NodeId.next()
        val id2 = NodeId.next()
        val id3 = NodeId.next()
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(id1, 1))
            .addNode(IntConstant(id2, 2))
            .addNode(StringConstant(id3, "hello"))
            .build()

        val intNodes = graph.nodes(IntConstant::class.java).toList()
        assertEquals(2, intNodes.size)
        intNodes.forEach { assertIs<IntConstant>(it) }

        val strNodes = graph.nodes(StringConstant::class.java).toList()
        assertEquals(1, strNodes.size)
        assertEquals("hello", (strNodes[0] as StringConstant).value)
    }

    @Test
    fun `nodes by supertype returns all matching subtypes`() {
        val id1 = NodeId.next()
        val id2 = NodeId.next()
        val id3 = NodeId.next()
        val method = makeMethod("com.example.Foo", "bar")
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(id1, 1))
            .addNode(StringConstant(id2, "hello"))
            .addNode(LocalVariable(id3, "x", TypeDescriptor("int"), method))
            .build()

        // ConstantNode is a supertype of IntConstant and StringConstant
        val constants = graph.nodes(ConstantNode::class.java).toList()
        assertEquals(2, constants.size)

        // ValueNode covers constants and LocalVariable
        val valueNodes = graph.nodes(ValueNode::class.java).toList()
        assertEquals(3, valueNodes.size)
    }

    @Test
    fun `nodes of CallSiteNode type`() {
        val callId = NodeId.next()
        val intId = NodeId.next()
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Service", "process")
        val graph = MmapGraphBuilder()
            .addNode(CallSiteNode(callId, caller, callee, 10, null, emptyList()))
            .addNode(IntConstant(intId, 42))
            .build()

        val callSites = graph.nodes(CallSiteNode::class.java).toList()
        assertEquals(1, callSites.size)
        assertEquals(callId, callSites[0].id)
    }

    // ========================================================================
    // Edge traversal
    // ========================================================================

    @Test
    fun `outgoing and incoming edges work correctly`() {
        val n1 = NodeId.next()
        val n2 = NodeId.next()
        val n3 = NodeId.next()
        val edge1 = DataFlowEdge(n1, n2, DataFlowKind.ASSIGN)
        val edge2 = DataFlowEdge(n1, n3, DataFlowKind.PARAMETER_PASS)

        val graph = MmapGraphBuilder()
            .addNode(IntConstant(n1, 1))
            .addNode(IntConstant(n2, 2))
            .addNode(IntConstant(n3, 3))
            .addEdge(edge1)
            .addEdge(edge2)
            .build()

        val outgoing = graph.outgoing(n1).toList()
        assertEquals(2, outgoing.size)

        val incoming2 = graph.incoming(n2).toList()
        assertEquals(1, incoming2.size)
        assertEquals(edge1, incoming2[0])

        val incoming3 = graph.incoming(n3).toList()
        assertEquals(1, incoming3.size)
        assertEquals(edge2, incoming3[0])
    }

    @Test
    fun `outgoing returns empty for node with no edges`() {
        val id = NodeId.next()
        val graph = MmapGraphBuilder().addNode(IntConstant(id, 1)).build()
        assertEquals(0, graph.outgoing(id).count())
        assertEquals(0, graph.incoming(id).count())
    }

    @Test
    fun `typed edge queries filter correctly`() {
        val n1 = NodeId.next()
        val n2 = NodeId.next()
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(n1, 1))
            .addNode(IntConstant(n2, 2))
            .addEdge(DataFlowEdge(n1, n2, DataFlowKind.ASSIGN))
            .addEdge(CallEdge(n1, n2, isVirtual = false))
            .build()

        val dataFlowEdges = graph.outgoing(n1, DataFlowEdge::class.java).toList()
        assertEquals(1, dataFlowEdges.size)
        assertIs<DataFlowEdge>(dataFlowEdges[0])

        val callEdges = graph.outgoing(n1, CallEdge::class.java).toList()
        assertEquals(1, callEdges.size)
        assertIs<CallEdge>(callEdges[0])
    }

    // ========================================================================
    // callSites query
    // ========================================================================

    @Test
    fun `callSites finds matching call sites`() {
        val callId = NodeId.next()
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Service", "process", listOf("int"))
        val graph = MmapGraphBuilder()
            .addNode(CallSiteNode(callId, caller, callee, 10, null, emptyList()))
            .build()

        val results = graph.callSites(MethodPattern(declaringClass = "com.example.Service", name = "process")).toList()
        assertEquals(1, results.size)
        assertEquals(callId, results[0].id)

        val noResults = graph.callSites(MethodPattern(declaringClass = "com.example.Other")).toList()
        assertEquals(0, noResults.size)
    }

    // ========================================================================
    // Metadata queries
    // ========================================================================

    @Test
    fun `methods query works`() {
        val method = makeMethod("com.example.Foo", "doWork", listOf("int"))
        val graph = MmapGraphBuilder().addMethod(method).build()
        val found = graph.methods(MethodPattern(declaringClass = "com.example.Foo")).toList()
        assertEquals(1, found.size)
        assertEquals(method, found[0])
    }

    @Test
    fun `type hierarchy queries work`() {
        val sub = TypeDescriptor("com.example.Bar")
        val sup = TypeDescriptor("com.example.Foo")
        val graph = MmapGraphBuilder()
            .addTypeRelation(sub, sup, TypeRelation.EXTENDS)
            .build()

        assertEquals(listOf(sup), graph.supertypes(sub).toList())
        assertEquals(listOf(sub), graph.subtypes(sup).toList())
    }

    @Test
    fun `typeHierarchyTypes returns all types`() {
        val sub = TypeDescriptor("com.example.Bar")
        val sup = TypeDescriptor("com.example.Foo")
        val graph = MmapGraphBuilder()
            .addTypeRelation(sub, sup, TypeRelation.EXTENDS)
            .build()

        val types = graph.typeHierarchyTypes()
        assertTrue(types.contains("com.example.Bar"))
        assertTrue(types.contains("com.example.Foo"))
    }

    @Test
    fun `enumValues works`() {
        val graph = MmapGraphBuilder()
            .addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))
            .build()
        val values = graph.enumValues("com.example.Status", "ACTIVE")
        assertNotNull(values)
        assertEquals(listOf(1, "active"), values)
        assertNull(graph.enumValues("com.example.Status", "UNKNOWN"))
    }

    @Test
    fun `memberAnnotations works`() {
        val graph = MmapGraphBuilder()
            .addMemberAnnotation("com.example.User", "name", "NotNull", mapOf("msg" to "required"))
            .build()
        val annotations = graph.memberAnnotations("com.example.User", "name")
        assertEquals(1, annotations.size)
        assertEquals("required", annotations["NotNull"]?.get("msg"))
        assertTrue(graph.memberAnnotations("com.example.User", "unknown").isEmpty())
    }

    @Test
    fun `branchScopes and branchScopesFor work`() {
        val condId = NodeId.next()
        val comparand = NodeId.next()
        val comp = BranchComparison(ComparisonOp.EQ, comparand)
        val method = makeMethod("com.example.Foo", "check")
        val graph = MmapGraphBuilder()
            .addBranchScope(condId, method, comp, intArrayOf(10, 20), intArrayOf(30))
            .build()

        val scopes = graph.branchScopes().toList()
        assertEquals(1, scopes.size)
        assertEquals(condId, scopes[0].conditionNodeId)

        val forCond = graph.branchScopesFor(condId).toList()
        assertEquals(1, forCond.size)

        val forOther = graph.branchScopesFor(NodeId(9999)).toList()
        assertEquals(0, forOther.size)
    }

    // ========================================================================
    // Equivalence with DefaultGraph.Builder
    // ========================================================================

    @Test
    fun `MmapGraph produces same results as DefaultGraph`() {
        NodeId.reset()
        val n1 = NodeId.next()
        val n2 = NodeId.next()
        val n3 = NodeId.next()
        val method = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Service", "doWork")

        val intNode = IntConstant(n1, 42)
        val strNode = StringConstant(n2, "hello")
        val callSite = CallSiteNode(n3, method, callee, 10, null, listOf(n1))

        val dfEdge = DataFlowEdge(n1, n3, DataFlowKind.PARAMETER_PASS)
        val callEdge = CallEdge(n3, n2, isVirtual = false)

        // Build with DefaultGraph.Builder
        val defaultBuilder = DefaultGraph.Builder()
        defaultBuilder.addNode(intNode).addNode(strNode).addNode(callSite)
        defaultBuilder.addEdge(dfEdge).addEdge(callEdge)
        defaultBuilder.addMethod(method).addMethod(callee)
        defaultBuilder.addTypeRelation(TypeDescriptor("com.example.Bar"), TypeDescriptor("com.example.Foo"), TypeRelation.EXTENDS)
        defaultBuilder.addEnumValues("com.example.Status", "ACTIVE", listOf(1))
        defaultBuilder.addMemberAnnotation("com.example.User", "name", "NotNull", mapOf("msg" to "x"))
        val defaultGraph = defaultBuilder.build()

        // Build with MmapGraphBuilder (produces MmapGraph)
        val mmapBuilder = MmapGraphBuilder()
        mmapBuilder.addNode(intNode).addNode(strNode).addNode(callSite)
        mmapBuilder.addEdge(dfEdge).addEdge(callEdge)
        mmapBuilder.addMethod(method).addMethod(callee)
        mmapBuilder.addTypeRelation(TypeDescriptor("com.example.Bar"), TypeDescriptor("com.example.Foo"), TypeRelation.EXTENDS)
        mmapBuilder.addEnumValues("com.example.Status", "ACTIVE", listOf(1))
        mmapBuilder.addMemberAnnotation("com.example.User", "name", "NotNull", mapOf("msg" to "x"))
        val mmapGraph = mmapBuilder.build()

        assertIs<MmapGraph>(mmapGraph)

        // Verify nodes
        assertEquals(defaultGraph.node(n1), mmapGraph.node(n1))
        assertEquals(defaultGraph.node(n2), mmapGraph.node(n2))
        assertEquals(defaultGraph.node(n3), mmapGraph.node(n3))

        // Verify edges
        assertEquals(defaultGraph.outgoing(n1).toList(), mmapGraph.outgoing(n1).toList())
        assertEquals(defaultGraph.outgoing(n3).toList(), mmapGraph.outgoing(n3).toList())
        assertEquals(defaultGraph.incoming(n3).toList(), mmapGraph.incoming(n3).toList())
        assertEquals(defaultGraph.incoming(n2).toList(), mmapGraph.incoming(n2).toList())

        // Verify metadata
        assertEquals(
            defaultGraph.methods(MethodPattern()).toList().sortedBy { it.signature },
            mmapGraph.methods(MethodPattern()).toList().sortedBy { it.signature }
        )
        assertEquals(
            defaultGraph.supertypes(TypeDescriptor("com.example.Bar")).toList(),
            mmapGraph.supertypes(TypeDescriptor("com.example.Bar")).toList()
        )
        assertEquals(
            defaultGraph.enumValues("com.example.Status", "ACTIVE"),
            mmapGraph.enumValues("com.example.Status", "ACTIVE")
        )
        assertEquals(
            defaultGraph.memberAnnotations("com.example.User", "name"),
            mmapGraph.memberAnnotations("com.example.User", "name")
        )
    }

    // ========================================================================
    // Scale test
    // ========================================================================

    @Test
    fun `10K nodes and edges work with disk-backed graph`() {
        val builder = MmapGraphBuilder()
        val nodeIds = mutableListOf<NodeId>()

        for (i in 0 until 10_000) {
            val id = NodeId.next()
            nodeIds.add(id)
            val node = when (i % 5) {
                0 -> IntConstant(id, i)
                1 -> StringConstant(id, "str_$i")
                2 -> LongConstant(id, i.toLong())
                3 -> BooleanConstant(id, i % 2 == 0)
                else -> FloatConstant(id, i.toFloat())
            }
            builder.addNode(node)
        }

        for (i in 0 until 10_000) {
            val from = nodeIds[i]
            val to = nodeIds[(i + 1) % 10_000]
            builder.addEdge(DataFlowEdge(from, to, DataFlowKind.ASSIGN))
        }

        val graph = builder.build()
        assertIs<MmapGraph>(graph)

        // Verify all nodes are present
        for (i in 0 until 10_000) {
            assertNotNull(graph.node(nodeIds[i]), "Node $i missing")
        }

        // Verify edges
        for (i in 0 until 10_000) {
            val outgoing = graph.outgoing(nodeIds[i]).toList()
            assertEquals(1, outgoing.size, "Node $i should have 1 outgoing edge")
        }

        // Verify node type queries
        val intConstants = graph.nodes(IntConstant::class.java).toList()
        assertEquals(2000, intConstants.size) // 10000 / 5

        val allConstants = graph.nodes(ConstantNode::class.java).toList()
        assertEquals(10_000, allConstants.size)
    }

    // ========================================================================
    // Empty graph
    // ========================================================================

    @Test
    fun `empty graph builds successfully`() {
        val graph = MmapGraphBuilder().build()
        assertIs<MmapGraph>(graph)
        assertNull(graph.node(NodeId(1)))
        assertEquals(0, graph.branchScopes().count())
    }

    // ========================================================================
    // close() releases resources
    // ========================================================================

    @Test
    fun `close does not throw and cleans up RAF handles`() {
        val n1 = NodeId.next()
        val n2 = NodeId.next()
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(n1, 1))
            .addNode(IntConstant(n2, 2))
            .addEdge(DataFlowEdge(n1, n2, DataFlowKind.ASSIGN))
            .build()
        assertIs<MmapGraph>(graph)

        // Access nodes and edges to trigger RAF creation
        assertNotNull(graph.node(n1))
        assertEquals(1, graph.outgoing(n1).count())

        // close() should not throw
        (graph as MmapGraph).close()
    }

    // ========================================================================
    // typed incoming edge query
    // ========================================================================

    @Test
    fun `incoming with type filter returns only matching edge types`() {
        val n1 = NodeId.next()
        val n2 = NodeId.next()
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(n1, 1))
            .addNode(IntConstant(n2, 2))
            .addEdge(DataFlowEdge(n1, n2, DataFlowKind.ASSIGN))
            .addEdge(CallEdge(n1, n2, isVirtual = false))
            .build()

        val dataFlowEdges = graph.incoming(n2, DataFlowEdge::class.java).toList()
        assertEquals(1, dataFlowEdges.size)
        assertIs<DataFlowEdge>(dataFlowEdges[0])

        val callEdges = graph.incoming(n2, CallEdge::class.java).toList()
        assertEquals(1, callEdges.size)
        assertIs<CallEdge>(callEdges[0])
    }
}
