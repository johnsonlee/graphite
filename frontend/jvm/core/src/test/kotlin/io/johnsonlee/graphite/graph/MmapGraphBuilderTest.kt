package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MmapGraphBuilderTest : MmapGraphBuilderTestSupport() {
    @Test
    fun `a bridge and its covariant override are two methods`() {
        val override = makeMethod("com.example.Foo", "load", listOf("int")).copy(returnType = TypeDescriptor("java.lang.String"))
        val bridge = override.copy(returnType = TypeDescriptor("java.lang.Object"))
        assertEquals(bridge.signature, override.signature)
        val graph = MmapGraphBuilder().addMethod(bridge).addMethod(override).addMethod(bridge).build()
        assertEquals(2L, graph.methodCount())
        assertEquals(setOf(bridge, override), graph.methods(MethodPattern(declaringClass = "com.example.Foo", name = "load")).toSet())
    }

    @Test
    fun `FullGraphBuilder default arguments are honored by mmap builder`() {
        val builder: FullGraphBuilder = MmapGraphBuilder()
        val graph =
            builder.addMemberAnnotation("com.example.User", "id", "javax.persistence.Id").addArtifactDependency("app", "lib").build()

        assertEquals(emptyMap(), graph.memberAnnotations("com.example.User", "id")["javax.persistence.Id"])
        assertEquals(1, graph.artifactDependencies()["app"]?.get("lib"))
    }

    @Test
    fun `addBranchScope keeps packed constant definitions per side`() {
        val condId = NodeId.next()
        val comp = BranchComparison(ComparisonOp.EQ, NodeId.next())
        val m = makeMethod("com.example.Foo", "check")
        val graph =
            MmapGraphBuilder()
                .addBranchScope(
                    condId,
                    m,
                    comp,
                    intArrayOf(1),
                    intArrayOf(2),
                    trueDefinitions = intArrayOf(3, 1, 7),
                    falseDefinitions = intArrayOf(5, 2, 8, 6, 2, 9))
                .addBranchScope(NodeId.next(), m, comp, intArrayOf(4), intArrayOf(5))
                .build()

        val scopes = graph.branchScopes().toList()
        assertEquals(2, scopes.size)
        val withDefinitions = graph.branchScopesFor(condId).single()
        assertEquals(listOf(LocalDefinition(3, NodeId(1), NodeId(7))), withDefinitions.trueDefinitions)
        assertEquals(
            listOf(LocalDefinition(5, NodeId(2), NodeId(8)), LocalDefinition(6, NodeId(2), NodeId(9))), withDefinitions.falseDefinitions)
        val without = scopes.single { it.conditionNodeId != condId }
        assertEquals(emptyList(), without.trueDefinitions)
        assertEquals(emptyList(), without.falseDefinitions)
    }

    @Test
    fun `addLocalDefinitions records every definition of a tracked local`() {
        val local = NodeId.next()
        val graph = MmapGraphBuilder().addLocalDefinitions(local, intArrayOf(2, local.value, 7, 9, local.value, 8)).build()
        assertEquals(listOf(LocalDefinition(2, local, NodeId(7)), LocalDefinition(9, local, NodeId(8))), graph.localDefinitionsFor(local))
        assertEquals(emptyList(), graph.localDefinitionsFor(NodeId(999)))
        assertEquals(setOf(local), graph.localDefinitions().keys)
        assertEquals(emptyMap(), MmapGraphBuilder().build().localDefinitions())
    }

    @Test
    fun `branchScopesFor returns scopes for specific node`() {
        val condId1 = NodeId.next()
        val condId2 = NodeId.next()
        val comp = BranchComparison(ComparisonOp.EQ, NodeId.next())
        val method = makeMethod("com.example.Foo", "check")
        val graph =
            MmapGraphBuilder()
                .addBranchScope(condId1, method, comp, intArrayOf(1), intArrayOf(2))
                .addBranchScope(condId2, method, comp, intArrayOf(3), intArrayOf(4))
                .build()
        assertEquals(1, graph.branchScopesFor(condId1).count())
        assertEquals(1, graph.branchScopesFor(condId2).count())
        assertEquals(0, graph.branchScopesFor(NodeId(999)).count())
    }

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
            mmapGraph.methods(MethodPattern()).toList().sortedBy { it.signature })
        assertEquals(
            defaultGraph.supertypes(TypeDescriptor("com.example.Bar")).toList(),
            mmapGraph.supertypes(TypeDescriptor("com.example.Bar")).toList())
        assertEquals(defaultGraph.enumValues("com.example.Status", "ACTIVE"), mmapGraph.enumValues("com.example.Status", "ACTIVE"))
        assertEquals(defaultGraph.memberAnnotations("com.example.User", "name"), mmapGraph.memberAnnotations("com.example.User", "name"))
    }

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

        val graph =
            MmapGraphBuilder()
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
        val graph =
            MmapGraphBuilder()
                .addNode(IntConstant(from, 1))
                .addNode(IntConstant(to, 2))
                .addEdge(DataFlowEdge(from, to, DataFlowKind.ASSIGN))
                .addEdge(CallEdge(from, to, isVirtual = false))
                .build()
        assertEquals(2, graph.outgoing(from).count())
        assertEquals(2, graph.incoming(to).count())
    }

    @Test
    fun `EnumConstant with float, double, long constructor args`() {
        val id = NodeId.next()
        val node = EnumConstant(id, TypeDescriptor("com.example.Metric"), "LATENCY", listOf(1.5f, 2.5, 100L, false))
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as EnumConstant
        assertEquals(1.5f, restored.constructorArgs[0])
        assertEquals(2.5, restored.constructorArgs[1])
        assertEquals(100L, restored.constructorArgs[2])
        assertEquals(false, restored.constructorArgs[3])
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
        val processCallSites = graph.callSites(MethodPattern(declaringClass = "com.example.Service", name = "process")).toList()
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
}
