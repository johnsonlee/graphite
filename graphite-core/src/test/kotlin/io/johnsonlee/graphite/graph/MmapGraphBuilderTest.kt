package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MmapGraphBuilderTest {

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
    // Round-trip: every node type
    // ========================================================================

    @Test
    fun `round-trip IntConstant`() {
        val id = NodeId.next()
        val node = IntConstant(id, 42)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip StringConstant`() {
        val id = NodeId.next()
        val node = StringConstant(id, "hello world")
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip LongConstant`() {
        val id = NodeId.next()
        val node = LongConstant(id, 123456789L)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip FloatConstant`() {
        val id = NodeId.next()
        val node = FloatConstant(id, 3.14f)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip DoubleConstant`() {
        val id = NodeId.next()
        val node = DoubleConstant(id, 2.71828)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip BooleanConstant`() {
        val id = NodeId.next()
        val node = BooleanConstant(id, true)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip NullConstant`() {
        val id = NodeId.next()
        val node = NullConstant(id)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip EnumConstant with constructor args`() {
        val id = NodeId.next()
        val node = EnumConstant(
            id,
            TypeDescriptor("com.example.Priority"),
            "HIGH",
            listOf(1, "urgent", true, null, EnumValueReference("com.example.Level", "TOP"))
        )
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as EnumConstant
        assertEquals(node.enumType, restored.enumType)
        assertEquals(node.enumName, restored.enumName)
        assertEquals(node.constructorArgs, restored.constructorArgs)
    }

    @Test
    fun `round-trip EnumConstant with no args`() {
        val id = NodeId.next()
        val node = EnumConstant(id, TypeDescriptor("com.example.Color"), "RED")
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip LocalVariable`() {
        val id = NodeId.next()
        val method = makeMethod("com.example.Foo", "bar")
        val node = LocalVariable(id, "x", TypeDescriptor("int"), method)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip FieldNode`() {
        val id = NodeId.next()
        val node = FieldNode(
            id,
            FieldDescriptor(TypeDescriptor("com.example.Foo"), "count", TypeDescriptor("int")),
            isStatic = true
        )
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip ParameterNode`() {
        val id = NodeId.next()
        val method = makeMethod("com.example.Foo", "bar", listOf("java.lang.String"))
        val node = ParameterNode(id, 0, TypeDescriptor("java.lang.String"), method)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip ReturnNode with actualType`() {
        val id = NodeId.next()
        val method = makeMethod("com.example.Foo", "bar")
        val node = ReturnNode(id, method, actualType = TypeDescriptor("java.lang.String"))
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip ReturnNode without actualType`() {
        val id = NodeId.next()
        val method = makeMethod("com.example.Foo", "bar")
        val node = ReturnNode(id, method, actualType = null)
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip CallSiteNode with receiver and arguments`() {
        val id = NodeId.next()
        val receiverId = NodeId.next()
        val arg1 = NodeId.next()
        val arg2 = NodeId.next()
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Service", "process", listOf("int", "java.lang.String"))
        val node = CallSiteNode(id, caller, callee, lineNumber = 42, receiver = receiverId, arguments = listOf(arg1, arg2))
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    @Test
    fun `round-trip CallSiteNode without receiver or line number`() {
        val id = NodeId.next()
        val caller = makeMethod("com.example.Main", "run")
        val callee = makeMethod("com.example.Util", "helper")
        val node = CallSiteNode(id, caller, callee, lineNumber = null, receiver = null, arguments = emptyList())
        val graph = MmapGraphBuilder().addNode(node).build()
        assertEquals(node, graph.node(id))
    }

    // ========================================================================
    // Round-trip: every edge type
    // ========================================================================

    @Test
    fun `round-trip DataFlowEdge for each kind`() {
        for (kind in DataFlowKind.entries) {
            NodeId.reset()
            val from = NodeId.next()
            val to = NodeId.next()
            val edge = DataFlowEdge(from, to, kind)
            val graph = MmapGraphBuilder()
                .addNode(IntConstant(from, 1))
                .addNode(IntConstant(to, 2))
                .addEdge(edge)
                .build()
            val edges = graph.outgoing(from).toList()
            assertEquals(1, edges.size, "Failed for kind=$kind")
            assertEquals(edge, edges[0], "Failed for kind=$kind")
        }
    }

    @Test
    fun `round-trip CallEdge`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = CallEdge(from, to, isVirtual = true, isDynamic = true)
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(edge)
            .build()
        val restored = graph.outgoing(from).first() as CallEdge
        assertEquals(true, restored.isVirtual)
        assertEquals(true, restored.isDynamic)
    }

    @Test
    fun `round-trip CallEdge non-virtual non-dynamic`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = CallEdge(from, to, isVirtual = false, isDynamic = false)
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(edge)
            .build()
        val restored = graph.outgoing(from).first() as CallEdge
        assertEquals(false, restored.isVirtual)
        assertEquals(false, restored.isDynamic)
    }

    @Test
    fun `round-trip TypeEdge for each relation`() {
        for (relation in TypeRelation.entries) {
            NodeId.reset()
            val from = NodeId.next()
            val to = NodeId.next()
            val edge = TypeEdge(from, to, relation)
            val graph = MmapGraphBuilder()
                .addNode(IntConstant(from, 1))
                .addNode(IntConstant(to, 2))
                .addEdge(edge)
                .build()
            val edges = graph.outgoing(from).toList()
            assertEquals(1, edges.size, "Failed for relation=$relation")
            assertEquals(edge, edges[0], "Failed for relation=$relation")
        }
    }

    @Test
    fun `round-trip ControlFlowEdge with comparison`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val comparand = NodeId.next()
        val edge = ControlFlowEdge(from, to, ControlFlowKind.BRANCH_TRUE, BranchComparison(ComparisonOp.EQ, comparand))
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addNode(IntConstant(comparand, 0))
            .addEdge(edge)
            .build()
        val restored = graph.outgoing(from).first() as ControlFlowEdge
        assertEquals(ControlFlowKind.BRANCH_TRUE, restored.kind)
        assertNotNull(restored.comparison)
        assertEquals(ComparisonOp.EQ, restored.comparison!!.operator)
        assertEquals(comparand, restored.comparison!!.comparandNodeId)
    }

    @Test
    fun `round-trip ControlFlowEdge without comparison`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = ControlFlowEdge(from, to, ControlFlowKind.SEQUENTIAL)
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(edge)
            .build()
        val restored = graph.outgoing(from).first() as ControlFlowEdge
        assertEquals(ControlFlowKind.SEQUENTIAL, restored.kind)
        assertNull(restored.comparison)
    }

    @Test
    fun `round-trip ControlFlowEdge for each kind`() {
        for (kind in ControlFlowKind.entries) {
            NodeId.reset()
            val from = NodeId.next()
            val to = NodeId.next()
            val edge = ControlFlowEdge(from, to, kind)
            val graph = MmapGraphBuilder()
                .addNode(IntConstant(from, 1))
                .addNode(IntConstant(to, 2))
                .addEdge(edge)
                .build()
            assertEquals(edge, graph.outgoing(from).first(), "Failed for kind=$kind")
        }
    }

    // ========================================================================
    // Metadata round-trip
    // ========================================================================

    @Test
    fun `round-trip methods`() {
        val method = makeMethod("com.example.Foo", "doWork", listOf("int", "java.lang.String"))
        val graph = MmapGraphBuilder().addMethod(method).build()
        val found = graph.methods(MethodPattern(declaringClass = "com.example.Foo", name = "doWork")).toList()
        assertEquals(1, found.size)
        assertEquals(method, found[0])
    }

    @Test
    fun `round-trip type hierarchy`() {
        val sub = TypeDescriptor("com.example.Bar")
        val sup = TypeDescriptor("com.example.Foo")
        val graph = MmapGraphBuilder()
            .addTypeRelation(sub, sup, TypeRelation.EXTENDS)
            .build()
        assertEquals(listOf(sup), graph.supertypes(sub).toList())
        assertEquals(listOf(sub), graph.subtypes(sup).toList())
    }

    @Test
    fun `round-trip enum values`() {
        val graph = MmapGraphBuilder()
            .addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active", null))
            .build()
        val values = graph.enumValues("com.example.Status", "ACTIVE")
        assertNotNull(values)
        assertEquals(listOf(1, "active", null), values)
    }

    @Test
    fun `round-trip member annotations`() {
        val graph = MmapGraphBuilder()
            .addMemberAnnotation("com.example.User", "name", "javax.validation.NotNull", mapOf("message" to "required"))
            .addMemberAnnotation("com.example.User", "name", "javax.persistence.Column", mapOf("length" to 255))
            .build()
        val annotations = graph.memberAnnotations("com.example.User", "name")
        assertEquals(2, annotations.size)
        assertEquals("required", annotations["javax.validation.NotNull"]?.get("message"))
        assertEquals(255, annotations["javax.persistence.Column"]?.get("length"))
    }

    @Test
    fun `round-trip member annotation with default empty values`() {
        val graph = MmapGraphBuilder()
            .addMemberAnnotation("com.example.User", "id", "javax.persistence.Id")
            .build()
        val annotations = graph.memberAnnotations("com.example.User", "id")
        assertTrue(annotations.containsKey("javax.persistence.Id"))
        assertTrue(annotations["javax.persistence.Id"]!!.isEmpty())
    }

    @Test
    fun `round-trip branch scopes`() {
        val condId = NodeId.next()
        val comparand = NodeId.next()
        val comp = BranchComparison(ComparisonOp.NE, comparand)
        val method = makeMethod("com.example.Foo", "check")
        val graph = MmapGraphBuilder()
            .addBranchScope(condId, method, comp, intArrayOf(10, 20), intArrayOf(30, 40))
            .build()
        val scopes = graph.branchScopes().toList()
        assertEquals(1, scopes.size)
        assertEquals(condId, scopes[0].conditionNodeId)
        assertEquals(ComparisonOp.NE, scopes[0].comparison.operator)
        assertEquals(comparand, scopes[0].comparison.comparandNodeId)
        assertTrue(scopes[0].trueBranchNodeIds.contains(10))
        assertTrue(scopes[0].trueBranchNodeIds.contains(20))
        assertTrue(scopes[0].falseBranchNodeIds.contains(30))
        assertTrue(scopes[0].falseBranchNodeIds.contains(40))
    }

    @Test
    fun `branchScopesFor returns scopes for specific node`() {
        val condId1 = NodeId.next()
        val condId2 = NodeId.next()
        val comp = BranchComparison(ComparisonOp.EQ, NodeId.next())
        val method = makeMethod("com.example.Foo", "check")
        val graph = MmapGraphBuilder()
            .addBranchScope(condId1, method, comp, intArrayOf(1), intArrayOf(2))
            .addBranchScope(condId2, method, comp, intArrayOf(3), intArrayOf(4))
            .build()
        assertEquals(1, graph.branchScopesFor(condId1).count())
        assertEquals(1, graph.branchScopesFor(condId2).count())
        assertEquals(0, graph.branchScopesFor(NodeId(999)).count())
    }

    // ========================================================================
    // Equivalence with DefaultGraph.Builder
    // ========================================================================

    @Test
    fun `MmapGraphBuilder produces same graph as DefaultGraph Builder`() {
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
        defaultBuilder.addNode(intNode)
        defaultBuilder.addNode(strNode)
        defaultBuilder.addNode(callSite)
        defaultBuilder.addEdge(dfEdge)
        defaultBuilder.addEdge(callEdge)
        defaultBuilder.addMethod(method)
        defaultBuilder.addMethod(callee)
        defaultBuilder.addTypeRelation(TypeDescriptor("com.example.Bar"), TypeDescriptor("com.example.Foo"), TypeRelation.EXTENDS)
        defaultBuilder.addEnumValues("com.example.Status", "ACTIVE", listOf(1))
        defaultBuilder.addMemberAnnotation("com.example.User", "name", "NotNull", mapOf("msg" to "x"))
        val defaultGraph = defaultBuilder.build()

        // Build with MmapGraphBuilder
        val mmapBuilder = MmapGraphBuilder()
        mmapBuilder.addNode(intNode)
        mmapBuilder.addNode(strNode)
        mmapBuilder.addNode(callSite)
        mmapBuilder.addEdge(dfEdge)
        mmapBuilder.addEdge(callEdge)
        mmapBuilder.addMethod(method)
        mmapBuilder.addMethod(callee)
        mmapBuilder.addTypeRelation(TypeDescriptor("com.example.Bar"), TypeDescriptor("com.example.Foo"), TypeRelation.EXTENDS)
        mmapBuilder.addEnumValues("com.example.Status", "ACTIVE", listOf(1))
        mmapBuilder.addMemberAnnotation("com.example.User", "name", "NotNull", mapOf("msg" to "x"))
        val mmapGraph = mmapBuilder.build()

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
    fun `10K nodes and edges round-trip without error`() {
        val builder = MmapGraphBuilder()
        val nodeIds = mutableListOf<NodeId>()

        // Add 10K nodes of various types
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

        // Add 10K edges
        for (i in 0 until 10_000) {
            val from = nodeIds[i]
            val to = nodeIds[(i + 1) % 10_000]
            builder.addEdge(DataFlowEdge(from, to, DataFlowKind.ASSIGN))
        }

        val graph = builder.build()

        // Verify all nodes are present
        for (i in 0 until 10_000) {
            assertNotNull(graph.node(nodeIds[i]), "Node $i missing")
        }

        // Verify edges
        for (i in 0 until 10_000) {
            val outgoing = graph.outgoing(nodeIds[i]).toList()
            assertEquals(1, outgoing.size, "Node $i should have 1 outgoing edge")
        }
    }

    // ========================================================================
    // Mixed node types in a single graph
    // ========================================================================

    @Test
    fun `all node types in one graph`() {
        val method = makeMethod("com.example.Foo", "bar", listOf("int"))

        val intId = NodeId.next()
        val strId = NodeId.next()
        val longId = NodeId.next()
        val floatId = NodeId.next()
        val doubleId = NodeId.next()
        val boolId = NodeId.next()
        val nullId = NodeId.next()
        val enumId = NodeId.next()
        val localId = NodeId.next()
        val fieldId = NodeId.next()
        val paramId = NodeId.next()
        val returnId = NodeId.next()
        val callId = NodeId.next()

        val graph = MmapGraphBuilder()
            .addNode(IntConstant(intId, 42))
            .addNode(StringConstant(strId, "hello"))
            .addNode(LongConstant(longId, 99L))
            .addNode(FloatConstant(floatId, 1.5f))
            .addNode(DoubleConstant(doubleId, 2.5))
            .addNode(BooleanConstant(boolId, false))
            .addNode(NullConstant(nullId))
            .addNode(EnumConstant(enumId, TypeDescriptor("com.example.Status"), "OK", listOf(200)))
            .addNode(LocalVariable(localId, "x", TypeDescriptor("int"), method))
            .addNode(FieldNode(fieldId, FieldDescriptor(TypeDescriptor("com.example.Foo"), "f", TypeDescriptor("int")), false))
            .addNode(ParameterNode(paramId, 0, TypeDescriptor("int"), method))
            .addNode(ReturnNode(returnId, method, TypeDescriptor("int")))
            .addNode(CallSiteNode(callId, method, method, 5, null, listOf(intId)))
            .build()

        // Verify each type
        assertTrue(graph.node(intId) is IntConstant)
        assertTrue(graph.node(strId) is StringConstant)
        assertTrue(graph.node(longId) is LongConstant)
        assertTrue(graph.node(floatId) is FloatConstant)
        assertTrue(graph.node(doubleId) is DoubleConstant)
        assertTrue(graph.node(boolId) is BooleanConstant)
        assertTrue(graph.node(nullId) is NullConstant)
        assertTrue(graph.node(enumId) is EnumConstant)
        assertTrue(graph.node(localId) is LocalVariable)
        assertTrue(graph.node(fieldId) is FieldNode)
        assertTrue(graph.node(paramId) is ParameterNode)
        assertTrue(graph.node(returnId) is ReturnNode)
        assertTrue(graph.node(callId) is CallSiteNode)
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Test
    fun `empty graph builds successfully`() {
        val graph = MmapGraphBuilder().build()
        assertNull(graph.node(NodeId(1)))
        assertEquals(0, graph.branchScopes().count())
    }

    @Test
    fun `nodes only, no edges`() {
        val id = NodeId.next()
        val graph = MmapGraphBuilder().addNode(IntConstant(id, 1)).build()
        assertNotNull(graph.node(id))
        assertEquals(0, graph.outgoing(id).count())
        assertEquals(0, graph.incoming(id).count())
    }

    @Test
    fun `multiple edges between same nodes`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(DataFlowEdge(from, to, DataFlowKind.ASSIGN))
            .addEdge(CallEdge(from, to, isVirtual = false))
            .build()
        assertEquals(2, graph.outgoing(from).count())
        assertEquals(2, graph.incoming(to).count())
    }

    @Test
    fun `setResources is propagated to built graph`() {
        val graph = MmapGraphBuilder()
            .setResources(io.johnsonlee.graphite.input.EmptyResourceAccessor)
            .build()
        assertNotNull(graph.resources)
    }

    @Test
    fun `EnumConstant with unknown type constructor arg round-trips via toString`() {
        // A non-primitive, non-String, non-EnumValueReference argument triggers the else branch
        // in writeAnyValue (writes as VAL_STRING + toString()) and readAnyValue (reads as String)
        val id = NodeId.next()
        val customValue = java.math.BigDecimal("123.456")
        val node = EnumConstant(
            id,
            TypeDescriptor("com.example.Custom"),
            "VALUE",
            listOf(customValue)
        )
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as EnumConstant
        // The value is serialized via toString() and deserialized as a String
        assertEquals("123.456", restored.constructorArgs[0])
    }

    @Test
    fun `EnumConstant with float, double, long constructor args`() {
        val id = NodeId.next()
        val node = EnumConstant(
            id,
            TypeDescriptor("com.example.Metric"),
            "LATENCY",
            listOf(1.5f, 2.5, 100L, false)
        )
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as EnumConstant
        assertEquals(1.5f, restored.constructorArgs[0])
        assertEquals(2.5, restored.constructorArgs[1])
        assertEquals(100L, restored.constructorArgs[2])
        assertEquals(false, restored.constructorArgs[3])
    }

    // ========================================================================
    // MmapGraph operations on a realistic graph
    // ========================================================================

    /**
     * Builds a small but realistic graph for testing MmapGraph operations.
     * Contains a mix of node types, edge types, methods, type hierarchy,
     * enum values, member annotations, and branch scopes.
     */
    private fun buildTestGraph(): Triple<Graph, Map<String, NodeId>, Map<String, MethodDescriptor>> {
        val ids = mutableMapOf<String, NodeId>()
        fun id(name: String): NodeId = ids.getOrPut(name) { NodeId.next() }

        val mainRun = makeMethod("com.example.Main", "run")
        val serviceProcess = makeMethod("com.example.Service", "process", listOf("int", "java.lang.String"))
        val serviceHelper = makeMethod("com.example.Service", "helper")
        val methods = mapOf("mainRun" to mainRun, "serviceProcess" to serviceProcess, "serviceHelper" to serviceHelper)

        val builder = MmapGraphBuilder()

        // Nodes: constants
        builder.addNode(IntConstant(id("int1"), 42))
        builder.addNode(IntConstant(id("int2"), 99))
        builder.addNode(StringConstant(id("str1"), "hello"))
        builder.addNode(LongConstant(id("long1"), 1000L))
        builder.addNode(BooleanConstant(id("bool1"), true))
        builder.addNode(NullConstant(id("null1")))

        // Nodes: structural
        builder.addNode(ParameterNode(id("param1"), 0, TypeDescriptor("int"), serviceProcess))
        builder.addNode(ParameterNode(id("param2"), 1, TypeDescriptor("java.lang.String"), serviceProcess))
        builder.addNode(ReturnNode(id("ret1"), serviceProcess, TypeDescriptor("java.lang.String")))
        builder.addNode(LocalVariable(id("local1"), "x", TypeDescriptor("int"), mainRun))
        builder.addNode(FieldNode(id("field1"), FieldDescriptor(TypeDescriptor("com.example.Service"), "count", TypeDescriptor("int")), false))

        // Nodes: call sites
        builder.addNode(CallSiteNode(id("call1"), mainRun, serviceProcess, 10, null, listOf(id("int1"), id("str1"))))
        builder.addNode(CallSiteNode(id("call2"), mainRun, serviceHelper, 20, null, emptyList()))

        // Edges: data flow
        builder.addEdge(DataFlowEdge(id("int1"), id("call1"), DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(id("str1"), id("call1"), DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(id("param1"), id("local1"), DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(id("local1"), id("ret1"), DataFlowKind.RETURN_VALUE))

        // Edges: call
        builder.addEdge(CallEdge(id("call1"), id("param1"), isVirtual = true))
        builder.addEdge(CallEdge(id("call2"), id("ret1"), isVirtual = false, isDynamic = true))

        // Edges: type
        builder.addEdge(TypeEdge(id("field1"), id("int1"), TypeRelation.EXTENDS))

        // Edges: control flow with comparison
        builder.addEdge(ControlFlowEdge(id("bool1"), id("call1"), ControlFlowKind.BRANCH_TRUE, BranchComparison(ComparisonOp.EQ, id("int2"))))
        builder.addEdge(ControlFlowEdge(id("bool1"), id("call2"), ControlFlowKind.BRANCH_FALSE))

        // Methods
        builder.addMethod(mainRun)
        builder.addMethod(serviceProcess)
        builder.addMethod(serviceHelper)

        // Type hierarchy
        builder.addTypeRelation(TypeDescriptor("com.example.ServiceImpl"), TypeDescriptor("com.example.Service"), TypeRelation.EXTENDS)
        builder.addTypeRelation(TypeDescriptor("com.example.ServiceImpl"), TypeDescriptor("com.example.Runnable"), TypeRelation.IMPLEMENTS)
        builder.addTypeRelation(TypeDescriptor("com.example.AdvancedService"), TypeDescriptor("com.example.Service"), TypeRelation.EXTENDS)

        // Enum values
        builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active", true))
        builder.addEnumValues("com.example.Status", "INACTIVE", listOf(0, "inactive", false))

        // Member annotations
        builder.addMemberAnnotation("com.example.User", "name", "javax.validation.NotNull", mapOf("message" to "required"))
        builder.addMemberAnnotation("com.example.User", "name", "javax.persistence.Column", mapOf("length" to 255))
        builder.addMemberAnnotation("com.example.User", "id", "javax.persistence.Id")

        // Branch scopes
        builder.addBranchScope(
            id("bool1"), mainRun,
            BranchComparison(ComparisonOp.EQ, id("int2")),
            intArrayOf(id("call1").value), intArrayOf(id("call2").value)
        )

        return Triple(builder.build(), ids, methods)
    }

    @Test
    fun `node() returns correct node by ID`() {
        val (graph, ids, _) = buildTestGraph()
        val intNode = graph.node(ids["int1"]!!)
        assertNotNull(intNode)
        assertTrue(intNode is IntConstant)
        assertEquals(42, (intNode as IntConstant).value)

        val strNode = graph.node(ids["str1"]!!)
        assertNotNull(strNode)
        assertTrue(strNode is StringConstant)
        assertEquals("hello", (strNode as StringConstant).value)

        // Non-existent node returns null
        assertNull(graph.node(NodeId(999999)))
    }

    @Test
    fun `nodes(type) returns nodes of correct type`() {
        val (graph, _, _) = buildTestGraph()

        val intConstants = graph.nodes(IntConstant::class.java).toList()
        assertEquals(2, intConstants.size)
        assertTrue(intConstants.all { it is IntConstant })
        assertTrue(intConstants.map { it.value }.containsAll(listOf(42, 99)))

        val stringConstants = graph.nodes(StringConstant::class.java).toList()
        assertEquals(1, stringConstants.size)
        assertEquals("hello", stringConstants[0].value)

        val callSites = graph.nodes(CallSiteNode::class.java).toList()
        assertEquals(2, callSites.size)

        val paramNodes = graph.nodes(ParameterNode::class.java).toList()
        assertEquals(2, paramNodes.size)
    }

    @Test
    fun `outgoing() returns correct edges`() {
        val (graph, ids, _) = buildTestGraph()

        // int1 has one outgoing DataFlowEdge to call1
        val int1Outgoing = graph.outgoing(ids["int1"]!!).toList()
        assertEquals(1, int1Outgoing.size)
        assertTrue(int1Outgoing[0] is DataFlowEdge)
        assertEquals(ids["call1"]!!.value, int1Outgoing[0].to.value)

        // bool1 has two outgoing ControlFlowEdges
        val bool1Outgoing = graph.outgoing(ids["bool1"]!!).toList()
        assertEquals(2, bool1Outgoing.size)
        assertTrue(bool1Outgoing.all { it is ControlFlowEdge })

        // Node with no outgoing edges
        val retOutgoing = graph.outgoing(ids["ret1"]!!).toList()
        assertEquals(0, retOutgoing.size)
    }

    @Test
    fun `incoming() returns correct edges`() {
        val (graph, ids, _) = buildTestGraph()

        // call1 has incoming edges: DataFlowEdge from int1, DataFlowEdge from str1,
        // ControlFlowEdge from bool1
        val call1Incoming = graph.incoming(ids["call1"]!!).toList()
        assertEquals(3, call1Incoming.size)

        // param1 has incoming CallEdge from call1
        val param1Incoming = graph.incoming(ids["param1"]!!).toList()
        assertEquals(1, param1Incoming.size)
        assertTrue(param1Incoming[0] is CallEdge)
        assertEquals(ids["call1"]!!.value, param1Incoming[0].from.value)

        // Node with no incoming edges
        val int1Incoming = graph.incoming(ids["int1"]!!).toList()
        // int1 has incoming TypeEdge from field1
        assertEquals(1, int1Incoming.size)
    }

    @Test
    fun `callSites() returns matching call sites`() {
        val (graph, _, methods) = buildTestGraph()

        // Find call sites targeting serviceProcess
        val processCallSites = graph.callSites(MethodPattern(
            declaringClass = "com.example.Service",
            name = "process"
        )).toList()
        assertEquals(1, processCallSites.size)
        assertEquals(methods["serviceProcess"], processCallSites[0].callee)

        // Find call sites targeting serviceHelper
        val helperCallSites = graph.callSites(MethodPattern(name = "helper")).toList()
        assertEquals(1, helperCallSites.size)

        // Find all call sites with wildcard
        val allCallSites = graph.callSites(MethodPattern()).toList()
        assertEquals(2, allCallSites.size)

        // No match
        val noMatch = graph.callSites(MethodPattern(name = "nonexistent")).toList()
        assertEquals(0, noMatch.size)
    }

    @Test
    fun `supertypes() and subtypes() work`() {
        val (graph, _, _) = buildTestGraph()

        // ServiceImpl extends Service and implements Runnable
        val serviceImplSupertypes = graph.supertypes(TypeDescriptor("com.example.ServiceImpl")).toList()
        assertEquals(2, serviceImplSupertypes.size)
        assertTrue(serviceImplSupertypes.contains(TypeDescriptor("com.example.Service")))
        assertTrue(serviceImplSupertypes.contains(TypeDescriptor("com.example.Runnable")))

        // Service has two subtypes: ServiceImpl and AdvancedService
        val serviceSubtypes = graph.subtypes(TypeDescriptor("com.example.Service")).toList()
        assertEquals(2, serviceSubtypes.size)
        assertTrue(serviceSubtypes.contains(TypeDescriptor("com.example.ServiceImpl")))
        assertTrue(serviceSubtypes.contains(TypeDescriptor("com.example.AdvancedService")))

        // Type with no hierarchy info
        val noSupertypes = graph.supertypes(TypeDescriptor("com.example.Unknown")).toList()
        assertEquals(0, noSupertypes.size)
    }

    @Test
    fun `methods() returns matching methods`() {
        val (graph, _, methods) = buildTestGraph()

        // Find all methods
        val allMethods = graph.methods(MethodPattern()).toList()
        assertEquals(3, allMethods.size)

        // Find by declaring class
        val serviceMethods = graph.methods(MethodPattern(declaringClass = "com.example.Service")).toList()
        assertEquals(2, serviceMethods.size)

        // Find by name
        val runMethods = graph.methods(MethodPattern(name = "run")).toList()
        assertEquals(1, runMethods.size)
        assertEquals(methods["mainRun"], runMethods[0])

        // Find by declaring class and name
        val specific = graph.methods(MethodPattern(declaringClass = "com.example.Service", name = "process")).toList()
        assertEquals(1, specific.size)
        assertEquals(methods["serviceProcess"], specific[0])
    }

    @Test
    fun `enumValues() returns correct values`() {
        val (graph, _, _) = buildTestGraph()

        val activeValues = graph.enumValues("com.example.Status", "ACTIVE")
        assertNotNull(activeValues)
        assertEquals(listOf(1, "active", true), activeValues)

        val inactiveValues = graph.enumValues("com.example.Status", "INACTIVE")
        assertNotNull(inactiveValues)
        assertEquals(listOf(0, "inactive", false), inactiveValues)

        // Non-existent enum
        assertNull(graph.enumValues("com.example.Status", "UNKNOWN"))
        assertNull(graph.enumValues("com.example.NonExistent", "VALUE"))
    }

    @Test
    fun `memberAnnotations() returns correct annotations`() {
        val (graph, _, _) = buildTestGraph()

        val nameAnnotations = graph.memberAnnotations("com.example.User", "name")
        assertEquals(2, nameAnnotations.size)
        assertEquals("required", nameAnnotations["javax.validation.NotNull"]?.get("message"))
        assertEquals(255, nameAnnotations["javax.persistence.Column"]?.get("length"))

        val idAnnotations = graph.memberAnnotations("com.example.User", "id")
        assertEquals(1, idAnnotations.size)
        assertTrue(idAnnotations.containsKey("javax.persistence.Id"))
        assertTrue(idAnnotations["javax.persistence.Id"]!!.isEmpty())

        // Non-existent member
        val noAnnotations = graph.memberAnnotations("com.example.User", "nonexistent")
        assertTrue(noAnnotations.isEmpty())
    }

    @Test
    fun `branchScopes() and branchScopesFor() work`() {
        val (graph, ids, _) = buildTestGraph()

        val allScopes = graph.branchScopes().toList()
        assertEquals(1, allScopes.size)
        val scope = allScopes[0]
        assertEquals(ids["bool1"], scope.conditionNodeId)
        assertEquals(ComparisonOp.EQ, scope.comparison.operator)
        assertEquals(ids["int2"]!!.value, scope.comparison.comparandNodeId.value)
        assertTrue(scope.trueBranchNodeIds.contains(ids["call1"]!!.value))
        assertTrue(scope.falseBranchNodeIds.contains(ids["call2"]!!.value))

        // branchScopesFor specific node
        val scopesForBool1 = graph.branchScopesFor(ids["bool1"]!!).toList()
        assertEquals(1, scopesForBool1.size)

        // branchScopesFor non-condition node
        val scopesForInt1 = graph.branchScopesFor(ids["int1"]!!).toList()
        assertEquals(0, scopesForInt1.size)
    }

    @Test
    fun `typeHierarchyTypes() returns all types with hierarchy info`() {
        val (graph, _, _) = buildTestGraph()

        val types = graph.typeHierarchyTypes()
        assertTrue(types.contains("com.example.ServiceImpl"))
        assertTrue(types.contains("com.example.Service"))
        assertTrue(types.contains("com.example.Runnable"))
        assertTrue(types.contains("com.example.AdvancedService"))
        // Types not in hierarchy should not appear
        assertTrue(!types.contains("com.example.Main"))
    }

    @Test
    fun `graph data persists on disk after builder completes`() {
        // Build a graph, then verify it works -- the builder's temp buffers are gone
        // but the disk files remain accessible
        val id1 = NodeId.next()
        val id2 = NodeId.next()
        val graph = MmapGraphBuilder()
            .addNode(IntConstant(id1, 111))
            .addNode(StringConstant(id2, "persisted"))
            .addEdge(DataFlowEdge(id1, id2, DataFlowKind.ASSIGN))
            .addMethod(makeMethod("com.example.Persist", "test"))
            .addTypeRelation(TypeDescriptor("com.example.Child"), TypeDescriptor("com.example.Parent"), TypeRelation.EXTENDS)
            .addEnumValues("com.example.Level", "HIGH", listOf(3))
            .build()

        // Multiple reads to verify disk data is stable
        for (i in 0 until 3) {
            assertEquals(111, (graph.node(id1) as IntConstant).value)
            assertEquals("persisted", (graph.node(id2) as StringConstant).value)
            assertEquals(1, graph.outgoing(id1).count())
            assertEquals(1, graph.incoming(id2).count())
            assertEquals(1, graph.methods(MethodPattern(name = "test")).count())
            assertEquals(listOf(TypeDescriptor("com.example.Parent")), graph.supertypes(TypeDescriptor("com.example.Child")).toList())
            assertEquals(listOf(3), graph.enumValues("com.example.Level", "HIGH"))
        }
    }
}
