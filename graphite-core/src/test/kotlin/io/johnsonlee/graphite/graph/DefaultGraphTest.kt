package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.ByteArrayInputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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

    @Test
    fun `typeHierarchyTypes returns all types with relations`() {
        val graph = DefaultGraph.Builder()
            .addTypeRelation(barType, fooType, TypeRelation.EXTENDS)
            .build()

        val types = graph.typeHierarchyTypes()
        assertTrue(types.contains("com.example.Foo"))
        assertTrue(types.contains("com.example.Bar"))
        assertEquals(2, types.size)
    }

    @Test
    fun `typeHierarchyTypes returns empty when no relations`() {
        val graph = DefaultGraph.Builder().build()
        assertTrue(graph.typeHierarchyTypes().isEmpty())
    }

    @Test
    fun `builder setResources preserves accessor on built graph`() {
        val accessor = object : ResourceAccessor {
            override fun list(pattern: String): Sequence<ResourceEntry> =
                sequenceOf(ResourceEntry("application.yml", "test"))

            override fun open(path: String) = ByteArrayInputStream("server.port=8080".toByteArray())
        }

        val graph = DefaultGraph.Builder()
            .setResources(accessor)
            .build()

        assertSame(accessor, graph.resources)
        assertEquals(listOf("application.yml"), graph.resources.list("**").map { it.path }.toList())
    }

    @Test
    fun `class origins and artifact dependencies are exposed from DefaultGraph`() {
        val graph = DefaultGraph.Builder()
            .addClassOrigin("com.example.App", "app.jar")
            .addClassOrigin("com.example.App", "ignored.jar")
            .addClassOrigin("org.example.Lib", "lib.jar")
            .addArtifactDependency("app.jar", "lib.jar", 2)
            .addArtifactDependency("app.jar", "lib.jar", 3)
            .addArtifactDependency("", "lib.jar", 1)
            .addArtifactDependency("app.jar", "", 1)
            .addArtifactDependency("app.jar", "app.jar", 1)
            .addArtifactDependency("app.jar", "ignored.jar", 0)
            .build()

        assertEquals("app.jar", graph.classOrigin("com.example.App"))
        assertNull(graph.classOrigin("com.example.Missing"))
        assertEquals(
            mapOf(
                "com.example.App" to "app.jar",
                "org.example.Lib" to "lib.jar"
            ),
            graph.classOrigins()
        )
        assertEquals(mapOf("app.jar" to mapOf("lib.jar" to 5)), graph.artifactDependencies())
    }

    @Test
    fun `Graph default metadata APIs return empty values`() {
        val graph = object : Graph {
            override fun node(id: NodeId) = null
            override fun <T : Node> nodes(type: Class<T>) = emptySequence<T>()
            override fun outgoing(id: NodeId) = emptySequence<Edge>()
            override fun incoming(id: NodeId) = emptySequence<Edge>()
            override fun <T : Edge> outgoing(id: NodeId, type: Class<T>) = emptySequence<T>()
            override fun <T : Edge> incoming(id: NodeId, type: Class<T>) = emptySequence<T>()
            override fun callSites(methodPattern: MethodPattern) = emptySequence<CallSiteNode>()
            override fun supertypes(type: TypeDescriptor) = emptySequence<TypeDescriptor>()
            override fun subtypes(type: TypeDescriptor) = emptySequence<TypeDescriptor>()
            override fun methods(pattern: MethodPattern) = emptySequence<MethodDescriptor>()
            override fun enumValues(enumClass: String, enumName: String) = null
            override fun memberAnnotations(className: String, memberName: String) = emptyMap<String, Map<String, Any?>>()
            override val resources = EmptyResourceAccessor
            override fun branchScopes() = emptySequence<BranchScope>()
            override fun branchScopesFor(conditionNodeId: NodeId) = emptySequence<BranchScope>()
        }

        assertTrue(graph.typeHierarchyTypes().isEmpty())
        assertNull(graph.classOrigin("com.example.App"))
        assertTrue(graph.classOrigins().isEmpty())
        assertTrue(graph.artifactDependencies().isEmpty())
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

    // ========================================================================
    // Member annotations
    // ========================================================================

    @Test
    fun `memberAnnotations returns empty for unknown member`() {
        val graph = DefaultGraph.Builder().build()
        assertTrue(graph.memberAnnotations("com.example.Foo", "bar").isEmpty())
    }

    @Test
    fun `addMemberAnnotation and memberAnnotations query`() {
        val graph = DefaultGraph.Builder()
            .addMemberAnnotation("com.example.User", "name", "javax.validation.NotNull", mapOf("message" to "required"))
            .build()
        val annotations = graph.memberAnnotations("com.example.User", "name")
        assertEquals(1, annotations.size)
        assertEquals("required", annotations["javax.validation.NotNull"]?.get("message"))
    }

    @Test
    fun `addMemberAnnotation with default empty values`() {
        val graph = DefaultGraph.Builder()
            .addMemberAnnotation("com.example.User", "id", "javax.persistence.Id")
            .build()
        val annotations = graph.memberAnnotations("com.example.User", "id")
        assertTrue(annotations.containsKey("javax.persistence.Id"))
        assertTrue(annotations["javax.persistence.Id"]!!.isEmpty())
    }

    // ========================================================================
    // Reified extension functions
    // ========================================================================

    @Test
    fun `reified nodes extension returns typed sequence`() {
        val builder = DefaultGraph.Builder()
        builder.addNode(IntConstant(NodeId.next(), 1))
        builder.addNode(StringConstant(NodeId.next(), "hello"))
        val graph = builder.build()

        val intConstants = graph.nodes<IntConstant>().toList()
        assertEquals(1, intConstants.size)
    }

    @Test
    fun `reified outgoing extension returns typed edges`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = DataFlowEdge(from, to, DataFlowKind.ASSIGN)
        val graph = DefaultGraph.Builder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(edge)
            .build()

        val edges = graph.outgoing<DataFlowEdge>(from).toList()
        assertEquals(1, edges.size)
    }

    @Test
    fun `reified incoming extension returns typed edges`() {
        val from = NodeId.next()
        val to = NodeId.next()
        val edge = DataFlowEdge(from, to, DataFlowKind.ASSIGN)
        val graph = DefaultGraph.Builder()
            .addNode(IntConstant(from, 1))
            .addNode(IntConstant(to, 2))
            .addEdge(edge)
            .build()

        val edges = graph.incoming<DataFlowEdge>(to).toList()
        assertEquals(1, edges.size)
    }
}
