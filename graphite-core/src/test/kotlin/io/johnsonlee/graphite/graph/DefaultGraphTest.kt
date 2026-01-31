package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultGraphTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    private val fooType = TypeDescriptor("com.example.Foo")
    private val barType = TypeDescriptor("com.example.Bar")

    private fun makeMethod(className: String, name: String): MethodDescriptor {
        return MethodDescriptor(TypeDescriptor(className), name, emptyList(), TypeDescriptor("void"))
    }

    // ========================================================================
    // Node operations
    // ========================================================================

    @Test
    fun `node returns added node by id`() {
        val id = NodeId.next()
        val node = IntConstant(id, 42)
        val graph = DefaultGraph.Builder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `node returns null for missing id`() {
        val graph = DefaultGraph.Builder().build()
        assertNull(graph.node(NodeId(999)))
    }

    @Test
    fun `nodes filters by type`() {
        val builder = DefaultGraph.Builder()
        builder.addNode(IntConstant(NodeId.next(), 1))
        builder.addNode(StringConstant(NodeId.next(), "hello"))
        builder.addNode(IntConstant(NodeId.next(), 2))
        val graph = builder.build()

        val intConstants = graph.nodes(IntConstant::class.java).toList()
        assertEquals(2, intConstants.size)

        val stringConstants = graph.nodes(StringConstant::class.java).toList()
        assertEquals(1, stringConstants.size)
    }

    // ========================================================================
    // Edge operations
    // ========================================================================

    @Test
    fun `outgoing returns edges from node`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = DataFlowEdge(from, to, DataFlowKind.ASSIGN)
        val graph = DefaultGraph.Builder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(edge)
            .build()

        val edges = graph.outgoing(from).toList()
        assertEquals(1, edges.size)
        assertEquals(edge, edges[0])
    }

    @Test
    fun `incoming returns edges to node`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = DataFlowEdge(from, to, DataFlowKind.ASSIGN)
        val graph = DefaultGraph.Builder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(edge)
            .build()

        val edges = graph.incoming(to).toList()
        assertEquals(1, edges.size)
        assertEquals(edge, edges[0])
    }

    @Test
    fun `outgoing with type filter`() {
        val from = NodeId.next()
        val to1 = NodeId.next()
        val to2 = NodeId.next()
        val dfEdge = DataFlowEdge(from, to1, DataFlowKind.ASSIGN)
        val callEdge = CallEdge(from, to2, isVirtual = true)
        val graph = DefaultGraph.Builder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to1, 2))
            .addNode(IntConstant(to2, 3))
            .addEdge(dfEdge)
            .addEdge(callEdge)
            .build()

        val dataFlowEdges = graph.outgoing(from, DataFlowEdge::class.java).toList()
        assertEquals(1, dataFlowEdges.size)

        val callEdges = graph.outgoing(from, CallEdge::class.java).toList()
        assertEquals(1, callEdges.size)
    }

    @Test
    fun `incoming with type filter`() {
        val from1 = NodeId.next()
        val from2 = NodeId.next()
        val to = NodeId.next()
        val dfEdge = DataFlowEdge(from1, to, DataFlowKind.ASSIGN)
        val callEdge = CallEdge(from2, to, isVirtual = false)
        val graph = DefaultGraph.Builder()
            .addNode(IntConstant(from1, 1))
            .addNode(IntConstant(from2, 2))
            .addNode(IntConstant(to, 3))
            .addEdge(dfEdge)
            .addEdge(callEdge)
            .build()

        assertEquals(1, graph.incoming(to, DataFlowEdge::class.java).count())
        assertEquals(1, graph.incoming(to, CallEdge::class.java).count())
    }

    @Test
    fun `outgoing returns empty for node without edges`() {
        val id = NodeId.next()
        val graph = DefaultGraph.Builder().addNode(IntConstant(id, 1)).build()
        assertEquals(0, graph.outgoing(id).count())
    }

    @Test
    fun `incoming returns empty for node without edges`() {
        val id = NodeId.next()
        val graph = DefaultGraph.Builder().addNode(IntConstant(id, 1)).build()
        assertEquals(0, graph.incoming(id).count())
    }

    // ========================================================================
    // Methods
    // ========================================================================

    @Test
    fun `addMethod and methods query`() {
        val m = makeMethod("com.example.Foo", "doWork")
        val graph = DefaultGraph.Builder().addMethod(m).build()

        val methods = graph.methods(MethodPattern()).toList()
        assertEquals(1, methods.size)
        assertEquals("doWork", methods[0].name)
    }

    @Test
    fun `methods with pattern filter`() {
        val m1 = makeMethod("com.example.Foo", "doWork")
        val m2 = makeMethod("com.example.Bar", "process")
        val graph = DefaultGraph.Builder().addMethod(m1).addMethod(m2).build()

        val fooMethods = graph.methods(MethodPattern(declaringClass = "com.example.Foo")).toList()
        assertEquals(1, fooMethods.size)
    }

    // ========================================================================
    // Type hierarchy
    // ========================================================================

    @Test
    fun `addTypeRelation and supertypes query`() {
        val graph = DefaultGraph.Builder()
            .addTypeRelation(barType, fooType, TypeRelation.EXTENDS)
            .build()

        val supertypes = graph.supertypes(barType).toList()
        assertEquals(1, supertypes.size)
        assertEquals("com.example.Foo", supertypes[0].className)
    }

    @Test
    fun `addTypeRelation and subtypes query`() {
        val graph = DefaultGraph.Builder()
            .addTypeRelation(barType, fooType, TypeRelation.EXTENDS)
            .build()

        val subtypes = graph.subtypes(fooType).toList()
        assertEquals(1, subtypes.size)
        assertEquals("com.example.Bar", subtypes[0].className)
    }

    @Test
    fun `supertypes returns empty for type without relations`() {
        val graph = DefaultGraph.Builder().build()
        assertEquals(0, graph.supertypes(fooType).count())
    }

    // ========================================================================
    // Enum values
    // ========================================================================

    @Test
    fun `addEnumValues and enumValues query`() {
        val graph = DefaultGraph.Builder()
            .addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))
            .build()

        val values = graph.enumValues("com.example.Status", "ACTIVE")
        assertNotNull(values)
        assertEquals(2, values.size)
        assertEquals(1, values[0])
        assertEquals("active", values[1])
    }

    @Test
    fun `enumValues returns null for missing enum`() {
        val graph = DefaultGraph.Builder().build()
        assertNull(graph.enumValues("com.example.Status", "MISSING"))
    }

    // ========================================================================
    // Endpoints
    // ========================================================================

    @Test
    fun `addEndpoint and endpoints query`() {
        val m = makeMethod("com.example.UserCtrl", "getUsers")
        val ep = EndpointInfo(m, HttpMethod.GET, "/api/users")
        val graph = DefaultGraph.Builder().addEndpoint(ep).build()

        val endpoints = graph.endpoints().toList()
        assertEquals(1, endpoints.size)
        assertEquals("/api/users", endpoints[0].path)
    }

    @Test
    fun `endpoints with pattern filter`() {
        val m = makeMethod("com.example.UserCtrl", "getUsers")
        val ep1 = EndpointInfo(m, HttpMethod.GET, "/api/users")
        val ep2 = EndpointInfo(m, HttpMethod.POST, "/api/orders")
        val graph = DefaultGraph.Builder().addEndpoint(ep1).addEndpoint(ep2).build()

        val filtered = graph.endpoints(pattern = "/api/users").toList()
        assertEquals(1, filtered.size)
    }

    @Test
    fun `endpoints with httpMethod filter`() {
        val m = makeMethod("com.example.UserCtrl", "getUsers")
        val ep1 = EndpointInfo(m, HttpMethod.GET, "/api/users")
        val ep2 = EndpointInfo(m, HttpMethod.POST, "/api/users")
        val graph = DefaultGraph.Builder().addEndpoint(ep1).addEndpoint(ep2).build()

        val filtered = graph.endpoints(httpMethod = HttpMethod.GET).toList()
        assertEquals(1, filtered.size)
    }

    @Test
    fun `endpoints matches ANY httpMethod`() {
        val m = makeMethod("com.example.UserCtrl", "handle")
        val ep = EndpointInfo(m, HttpMethod.ANY, "/api/users")
        val graph = DefaultGraph.Builder().addEndpoint(ep).build()

        val filtered = graph.endpoints(httpMethod = HttpMethod.GET).toList()
        assertEquals(1, filtered.size)
    }

    // ========================================================================
    // Branch scopes
    // ========================================================================

    @Test
    fun `addBranchScope and branchScopes query`() {
        val condId = NodeId.next()
        val comp = BranchComparison(ComparisonOp.EQ, NodeId.next())
        val m = makeMethod("com.example.Foo", "check")
        val graph = DefaultGraph.Builder()
            .addBranchScope(condId, m, comp, intArrayOf(1, 2), intArrayOf(3, 4))
            .build()

        val scopes = graph.branchScopes().toList()
        assertEquals(1, scopes.size)
        assertEquals(condId, scopes[0].conditionNodeId)
    }

    @Test
    fun `branchScopesFor returns scopes for specific node`() {
        val condId = NodeId.next()
        val otherId = NodeId.next()
        val comp = BranchComparison(ComparisonOp.EQ, NodeId.next())
        val m = makeMethod("com.example.Foo", "check")
        val graph = DefaultGraph.Builder()
            .addBranchScope(condId, m, comp, intArrayOf(1), intArrayOf(2))
            .addBranchScope(otherId, m, comp, intArrayOf(3), intArrayOf(4))
            .build()

        assertEquals(1, graph.branchScopesFor(condId).count())
        assertEquals(1, graph.branchScopesFor(otherId).count())
    }

    @Test
    fun `branchScopesFor returns empty for unknown node`() {
        val graph = DefaultGraph.Builder().build()
        assertEquals(0, graph.branchScopesFor(NodeId(999)).count())
    }

    // ========================================================================
    // Jackson info
    // ========================================================================

    @Test
    fun `addJacksonFieldInfo and jacksonFieldInfo query`() {
        val info = JacksonFieldInfo(jsonName = "user_name", isIgnored = false)
        val graph = DefaultGraph.Builder()
            .addJacksonFieldInfo("com.example.User", "name", info)
            .build()

        val result = graph.jacksonFieldInfo("com.example.User", "name")
        assertNotNull(result)
        assertEquals("user_name", result.jsonName)
    }

    @Test
    fun `addJacksonGetterInfo and jacksonGetterInfo query`() {
        val info = JacksonFieldInfo(jsonName = "user_name", isIgnored = false)
        val graph = DefaultGraph.Builder()
            .addJacksonGetterInfo("com.example.User", "getName", info)
            .build()

        val result = graph.jacksonGetterInfo("com.example.User", "getName")
        assertNotNull(result)
        assertEquals("user_name", result.jsonName)
    }

    @Test
    fun `jacksonFieldInfo returns null for missing field`() {
        val graph = DefaultGraph.Builder().build()
        assertNull(graph.jacksonFieldInfo("com.example.User", "missing"))
    }

    @Test
    fun `jacksonGetterInfo returns null for missing getter`() {
        val graph = DefaultGraph.Builder().build()
        assertNull(graph.jacksonGetterInfo("com.example.User", "getMissing"))
    }

    // ========================================================================
    // Call sites query
    // ========================================================================

    @Test
    fun `callSites returns matching call sites`() {
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Service", "doWork")
        val cs = CallSiteNode(NodeId.next(), caller, callee, 10, null, emptyList())
        val graph = DefaultGraph.Builder().addNode(cs).build()

        val result = graph.callSites(MethodPattern(declaringClass = "com.example.Service", name = "doWork")).toList()
        assertEquals(1, result.size)
    }

    @Test
    fun `callSites returns empty for no match`() {
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Service", "doWork")
        val cs = CallSiteNode(NodeId.next(), caller, callee, 10, null, emptyList())
        val graph = DefaultGraph.Builder().addNode(cs).build()

        val result = graph.callSites(MethodPattern(name = "missing")).toList()
        assertTrue(result.isEmpty())
    }
}
