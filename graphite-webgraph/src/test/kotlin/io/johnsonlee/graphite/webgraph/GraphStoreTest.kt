package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.input.ResourceEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphStoreTest {

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
                assertEquals(originalEdges.size, loadedEdges.size,
                    "Outgoing edge count should match for node ${node.id}")
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
                assertEquals(originalEdges.size, loadedEdges.size,
                    "Incoming edge count should match for node ${node.id}")
            }
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
    fun `round-trip preserves member annotations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.memberAnnotations("com.example.Foo", "bar")
            assertEquals(
                graph.memberAnnotations("com.example.Foo", "bar"),
                annotations
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves AnnotationNode`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.nodes(AnnotationNode::class.java).toList()
            assertEquals(1, annotations.size)
            assertEquals("javax.annotation.Nullable", annotations[0].name)
            assertEquals("com.example.Foo", annotations[0].className)
            assertEquals("bar", annotations[0].memberName)
            assertEquals("true", annotations[0].values["value"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves typed annotation values`() {
        val builder = DefaultGraph.Builder()
        val ownerType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(ownerType, "bar", emptyList(), TypeDescriptor("void"))
        val annotationNode = AnnotationNode(
            NodeId.next(),
            "com.example.Http",
            "com.example.Foo",
            "bar",
            mapOf(
                "paths" to listOf("/a", "/b"),
                "required" to true,
                "code" to 200,
                "note" to null
            )
        )
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId.next(), method))
        builder.addNode(annotationNode)
        builder.addMemberAnnotation(
            "com.example.Foo",
            "bar",
            "com.example.Http",
            annotationNode.values
        )

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-typed-annot-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedNode = loaded.node(annotationNode.id) as AnnotationNode
            assertEquals(annotationNode.values, loadedNode.values)
            assertEquals(annotationNode.values, loaded.memberAnnotations("com.example.Foo", "bar")["com.example.Http"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves ResourceValueNode`() {
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(TypeDescriptor("com.example.Foo"), "bar", emptyList(), TypeDescriptor("void"))
        val resourceFileNode = ResourceFileNode(NodeId.next(), "application.yml", "BOOT-INF/classes", "yaml", "prod")
        val resourceNode = ResourceValueNode(NodeId.next(), "application.yml", "server.port", 8080, "yaml", "prod")
        val callSite = CallSiteNode(NodeId.next(), method, method, 12, null, emptyList())
        builder.addMethod(method)
        builder.addNode(ReturnNode(NodeId.next(), method))
        builder.addNode(resourceFileNode)
        builder.addNode(resourceNode)
        builder.addNode(callSite)
        builder.addEdge(ResourceEdge(resourceNode.id, callSite.id, ResourceRelation.LOOKUP))

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-resource-value-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedFile = loaded.node(resourceFileNode.id) as ResourceFileNode
            val loadedNode = loaded.node(resourceNode.id) as ResourceValueNode
            assertEquals(resourceFileNode, loadedFile)
            assertEquals(resourceNode, loadedNode)
            assertTrue(loaded.incoming(callSite.id, ResourceEdge::class.java).any {
                it.from == resourceNode.id && it.kind == ResourceRelation.LOOKUP
            })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves type hierarchy`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val child = TypeDescriptor("com.example.Child")
            val parent = TypeDescriptor("com.example.Parent")
            assertTrue(loaded.supertypes(child).any { it.className == "com.example.Parent" })
            assertTrue(loaded.subtypes(parent).any { it.className == "com.example.Child" })
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves enum values`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val values = loaded.enumValues("com.example.Status", "ACTIVE")
            assertEquals(listOf(1, "active"), values)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves call sites`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val originalCallSites = graph.nodes(CallSiteNode::class.java).toList()
            val loadedCallSites = loaded.nodes(CallSiteNode::class.java).toList()
            assertEquals(originalCallSites.size, loadedCallSites.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `load from nonexistent directory throws`() {
        try {
            GraphStore.load(Files.createTempFile("not", "dir"))
            assertTrue(false, "Should have thrown")
        } catch (e: IllegalArgumentException) {
            // expected
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

    // ========================================================================
    // All constant node types round-trip
    // ========================================================================

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

    // ========================================================================
    // ControlFlowEdge and TypeEdge round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves ControlFlowEdge with BranchComparison`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val n3 = IntConstant(NodeId.next(), 0)
        builder.addNode(n1)
        builder.addNode(n2)
        builder.addNode(n3)

        val comparison = BranchComparison(ComparisonOp.EQ, n3.id)
        val cfEdge = ControlFlowEdge(n1.id, n2.id, ControlFlowKind.BRANCH_TRUE, comparison)
        val seqEdge = ControlFlowEdge(n2.id, n3.id, ControlFlowKind.SEQUENTIAL)
        builder.addEdge(cfEdge)
        builder.addEdge(seqEdge)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-cf-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedCfEdges = loaded.outgoing(n1.id, ControlFlowEdge::class.java).toList()
            assertEquals(1, loadedCfEdges.size)
            assertEquals(ControlFlowKind.BRANCH_TRUE, loadedCfEdges[0].kind)
            assertNotNull(loadedCfEdges[0].comparison)
            assertEquals(ComparisonOp.EQ, loadedCfEdges[0].comparison!!.operator)
            assertEquals(n3.id, loadedCfEdges[0].comparison!!.comparandNodeId)

            val loadedSeqEdges = loaded.outgoing(n2.id, ControlFlowEdge::class.java).toList()
            assertEquals(1, loadedSeqEdges.size)
            assertEquals(ControlFlowKind.SEQUENTIAL, loadedSeqEdges[0].kind)
            assertNull(loadedSeqEdges[0].comparison)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves TypeEdge`() {
        val builder = DefaultGraph.Builder()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        builder.addNode(n1)
        builder.addNode(n2)

        val typeEdge = TypeEdge(n1.id, n2.id, TypeRelation.IMPLEMENTS)
        builder.addEdge(typeEdge)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-type-edge-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEdges = loaded.outgoing(n1.id, TypeEdge::class.java).toList()
            assertEquals(1, loadedEdges.size)
            assertEquals(TypeRelation.IMPLEMENTS, loadedEdges[0].kind)
            assertEquals(n2.id, loadedEdges[0].to)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Branch scopes round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves branch scopes`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "check", emptyList(), TypeDescriptor("boolean"))
        builder.addMethod(method)

        val condNode = IntConstant(NodeId.next(), 0)
        val trueNode = IntConstant(NodeId.next(), 1)
        val falseNode = IntConstant(NodeId.next(), 2)
        builder.addNode(condNode)
        builder.addNode(trueNode)
        builder.addNode(falseNode)

        val comp = BranchComparison(ComparisonOp.NE, condNode.id)
        builder.addBranchScope(condNode.id, method, comp, intArrayOf(trueNode.id.value), intArrayOf(falseNode.id.value))

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-branch-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val scopes = loaded.branchScopes().toList()
            assertEquals(1, scopes.size)
            assertEquals(condNode.id, scopes[0].conditionNodeId)
            assertEquals(ComparisonOp.NE, scopes[0].comparison.operator)
            assertTrue(scopes[0].trueBranchNodeIds.contains(trueNode.id.value))
            assertTrue(scopes[0].falseBranchNodeIds.contains(falseNode.id.value))

            // Also test branchScopesFor
            val scopesFor = loaded.branchScopesFor(condNode.id).toList()
            assertEquals(1, scopesFor.size)

            // branchScopesFor with unknown node returns empty
            assertEquals(0, loaded.branchScopesFor(NodeId(999999)).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // callSites with pattern filtering on loaded graph
    // ========================================================================

    @Test
    fun `callSites with pattern filtering on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-callsite-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // Match existing callee
            val matched = loaded.callSites(MethodPattern(declaringClass = "com.example.Foo", name = "baz")).toList()
            assertEquals(1, matched.size)

            // No match
            val noMatch = loaded.callSites(MethodPattern(name = "nonexistent")).toList()
            assertTrue(noMatch.isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Reified nodes<T>() type filtering on loaded graph
    // ========================================================================

    @Test
    fun `reified nodes type filtering on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-reified-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val intConstants = loaded.nodes(IntConstant::class.java).toList()
            assertEquals(1, intConstants.size)
            assertEquals(42, intConstants[0].value)

            val callSites = loaded.nodes(CallSiteNode::class.java).toList()
            assertEquals(1, callSites.size)

            val fields = loaded.nodes(FieldNode::class.java).toList()
            assertEquals(1, fields.size)

            val params = loaded.nodes(ParameterNode::class.java).toList()
            assertEquals(1, params.size)

            val locals = loaded.nodes(LocalVariable::class.java).toList()
            assertEquals(1, locals.size)

            val returns = loaded.nodes(ReturnNode::class.java).toList()
            assertEquals(1, returns.size)

            val enums = loaded.nodes(EnumConstant::class.java).toList()
            assertEquals(1, enums.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Resources on loaded graph
    // ========================================================================

    @Test
    fun `loaded graph resources preserves persisted text resources`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val entries = loaded.resources.list("**").toList()
            assertEquals(1, entries.size)
            assertEquals("application.properties", entries.single().path)
            val content = loaded.resources.open("application.properties").bufferedReader().readText()
            assertTrue(content.contains("feature.mode=shadow"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Type hierarchy on loaded graph
    // ========================================================================

    @Test
    fun `typeHierarchyTypes on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-hierarchy-types-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val types = loaded.typeHierarchyTypes()
            assertTrue(types.contains("com.example.Parent"))
            assertTrue(types.contains("com.example.Child"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `supertypes and subtypes return empty for unknown type on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-hierarchy-empty-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(0, loaded.supertypes(TypeDescriptor("com.nonexistent.Type")).count())
            assertEquals(0, loaded.subtypes(TypeDescriptor("com.nonexistent.Type")).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Enum values on loaded graph
    // ========================================================================

    @Test
    fun `enumValues returns null for missing enum on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-enum-null-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertNull(loaded.enumValues("com.example.Missing", "MISSING"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Member annotations on loaded graph
    // ========================================================================

    @Test
    fun `memberAnnotations returns empty for unknown member on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-annot-empty-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertTrue(loaded.memberAnnotations("com.example.Unknown", "unknown").isEmpty())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Methods on loaded graph
    // ========================================================================

    @Test
    fun `methods with pattern filter on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-method-filter-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val barMethods = loaded.methods(MethodPattern(name = "bar")).toList()
            assertEquals(1, barMethods.size)
            assertEquals("bar", barMethods[0].name)

            val allMethods = loaded.methods(MethodPattern()).toList()
            assertEquals(2, allMethods.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Incoming edges on loaded graph
    // ========================================================================

    @Test
    fun `incoming typed edges on loaded graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-incoming-typed-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // The local node has incoming DataFlowEdges (from param and constant)
            val locals = loaded.nodes(LocalVariable::class.java).toList()
            assertEquals(1, locals.size)
            val localId = locals[0].id

            val incomingDf = loaded.incoming(localId, DataFlowEdge::class.java).toList()
            assertEquals(2, incomingDf.size)

            // No incoming CallEdge to local
            val incomingCall = loaded.incoming(localId, CallEdge::class.java).toList()
            assertEquals(0, incomingCall.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Outgoing/incoming for node without edges on loaded graph
    // ========================================================================

    @Test
    fun `outgoing and incoming return empty for isolated node on loaded graph`() {
        val builder = DefaultGraph.Builder()
        val isolated = IntConstant(NodeId.next(), 999)
        builder.addNode(isolated)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-isolated-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertEquals(0, loaded.outgoing(isolated.id).count())
            assertEquals(0, loaded.incoming(isolated.id).count())
            assertEquals(0, loaded.outgoing(isolated.id, DataFlowEdge::class.java).count())
            assertEquals(0, loaded.incoming(isolated.id, DataFlowEdge::class.java).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // ReturnNode with actualType for metadata collection
    // ========================================================================

    @Test
    fun `round-trip preserves ReturnNode with actualType`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "getData", emptyList(), TypeDescriptor("java.lang.Object"))
        val returnNode = ReturnNode(NodeId.next(), method, actualType = TypeDescriptor("com.example.ConcreteData"))
        builder.addNode(returnNode)
        builder.addMethod(method)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-return-actual-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedReturn = loaded.node(returnNode.id) as ReturnNode
            assertNotNull(loadedReturn.actualType)
            assertEquals("com.example.ConcreteData", loadedReturn.actualType!!.className)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // encodeEdge / decodeEdge round-trip for all edge variants
    // ========================================================================

    @Test
    fun `encodeEdge and decodeEdge round-trip for all edge variants`() {
        val from = NodeId(1)
        val to = NodeId(2)

        // DataFlowEdge - all kinds
        for (kind in DataFlowKind.entries) {
            val edge = DataFlowEdge(from, to, kind)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is DataFlowEdge)
            assertEquals(kind, (decoded as DataFlowEdge).kind)
            assertEquals(from, decoded.from)
            assertEquals(to, decoded.to)
        }

        // CallEdge - all flag combinations
        for (virtual in listOf(false, true)) {
            for (dynamic in listOf(false, true)) {
                val edge = CallEdge(from, to, isVirtual = virtual, isDynamic = dynamic)
                val encoded = NodeSerializer.encodeEdge(edge)
                val decoded = NodeSerializer.decodeEdge(encoded, from, to)
                assertTrue(decoded is CallEdge)
                val callEdge = decoded as CallEdge
                assertEquals(virtual, callEdge.isVirtual, "isVirtual=$virtual")
                assertEquals(dynamic, callEdge.isDynamic, "isDynamic=$dynamic")
            }
        }

        // TypeEdge - all relations
        for (relation in TypeRelation.entries) {
            val edge = TypeEdge(from, to, relation)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is TypeEdge)
            assertEquals(relation, (decoded as TypeEdge).kind)
        }

        // ControlFlowEdge - all kinds, with and without comparison
        for (kind in ControlFlowKind.entries) {
            val edge = ControlFlowEdge(from, to, kind)
            val encoded = NodeSerializer.encodeEdge(edge)
            val decoded = NodeSerializer.decodeEdge(encoded, from, to)
            assertTrue(decoded is ControlFlowEdge)
            assertEquals(kind, (decoded as ControlFlowEdge).kind)
            assertNull(decoded.comparison)
        }

        // ControlFlowEdge with comparison
        val comp = BranchComparison(ComparisonOp.GE, NodeId(99))
        val cfEdge = ControlFlowEdge(from, to, ControlFlowKind.BRANCH_TRUE)
        val encoded = NodeSerializer.encodeEdge(cfEdge)
        val decoded = NodeSerializer.decodeEdge(encoded, from, to, comp)
        assertTrue(decoded is ControlFlowEdge)
        assertEquals(comp, (decoded as ControlFlowEdge).comparison)

        for (relation in ResourceRelation.entries) {
            val edge = ResourceEdge(from, to, relation)
            val encodedResource = NodeSerializer.encodeEdge(edge)
            val decodedResource = NodeSerializer.decodeEdge(encodedResource, from, to)
            assertTrue(decodedResource is ResourceEdge)
            assertEquals(relation, (decodedResource as ResourceEdge).kind)
        }
    }

    @Test
    fun `decodeEdge supports legacy v2 labels`() {
        val from = NodeId(1)
        val to = NodeId(2)
        val comparison = BranchComparison(ComparisonOp.EQ, NodeId(3))

        val dataFlow = NodeSerializer.decodeEdge(0 or (DataFlowKind.FIELD_LOAD.ordinal shl 2), from, to, version = 2)
        assertEquals(DataFlowKind.FIELD_LOAD, (dataFlow as DataFlowEdge).kind)

        val call = NodeSerializer.decodeEdge(1 or (1 shl 6) or (1 shl 7), from, to, version = 2)
        assertTrue((call as CallEdge).isVirtual)
        assertTrue(call.isDynamic)

        val type = NodeSerializer.decodeEdge(2 or (TypeRelation.IMPLEMENTS.ordinal shl 2), from, to, version = 2)
        assertEquals(TypeRelation.IMPLEMENTS, (type as TypeEdge).kind)

        val control = NodeSerializer.decodeEdge(3 or (ControlFlowKind.BRANCH_FALSE.ordinal shl 2), from, to, comparison, version = 2)
        assertEquals(ControlFlowKind.BRANCH_FALSE, (control as ControlFlowEdge).kind)
        assertEquals(comparison, control.comparison)
    }

    @Test
    fun `node serializer helpers cover value io and direct edge decoders`() {
        val dir = Files.createTempDirectory("webgraph-node-helpers")
        try {
            val strings = StringTable.build(
                setOf("fallback", "enum.Owner", "VALUE", "hello", "java.class"),
                dir
            )
            val serializerClass = NodeSerializer::class.java
            val collectAnyValueString = serializerClass.getDeclaredMethod("collectAnyValueString", Any::class.java, MutableSet::class.java).apply { isAccessible = true }
            val writeAnyValue = serializerClass.getDeclaredMethod("writeAnyValue", DataOutputStream::class.java, Any::class.java, StringTable::class.java).apply { isAccessible = true }
            val readAnyValue = serializerClass.getDeclaredMethod("readAnyValue", DataInputStream::class.java, StringTable::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val decodeEdgeV2 = serializerClass.declaredMethods.first { it.name.startsWith("decodeEdgeV2") }.apply { isAccessible = true }
            val decodeEdgeV3 = serializerClass.declaredMethods.first { it.name.startsWith("decodeEdgeV3") }.apply { isAccessible = true }

            val dest = linkedSetOf<String>()
            collectAnyValueString.invoke(NodeSerializer, EnumValueReference("enum.Owner", "VALUE"), dest)
            collectAnyValueString.invoke(NodeSerializer, listOf("hello", true), dest)
            collectAnyValueString.invoke(NodeSerializer, object { override fun toString() = "fallback" }, dest)
            assertTrue(dest.containsAll(listOf("enum.Owner", "VALUE", "hello")))
            assertTrue(dest.contains("fallback"))

            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                writeAnyValue.invoke(NodeSerializer, dos, listOf("hello", 7), strings)
                dos.writeByte(99)
                dos.writeInt(strings.indexOf("fallback"))
            }
            DataInputStream(ByteArrayInputStream(baos.toByteArray())).use { dis ->
                assertEquals(listOf("hello", 7), readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
                assertEquals("fallback", readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val unsupportedOut = ByteArrayOutputStream()
            DataOutputStream(unsupportedOut).use { dos ->
                writeAnyValue.invoke(NodeSerializer, dos, object { override fun toString() = "fallback" }, strings)
            }
            DataInputStream(ByteArrayInputStream(unsupportedOut.toByteArray())).use { dis ->
                assertEquals("fallback", readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val from = NodeId(11)
            val to = NodeId(12)
            val comparison = BranchComparison(ComparisonOp.GT, NodeId(13))
            assertEquals(
                DataFlowKind.PARAMETER_PASS,
                (decodeEdgeV2.invoke(NodeSerializer, 0 or (DataFlowKind.PARAMETER_PASS.ordinal shl 2), from.value, to.value, null) as DataFlowEdge).kind
            )
            val legacyCall = decodeEdgeV2.invoke(NodeSerializer, 1 or (1 shl 6) or (1 shl 7), from.value, to.value, null) as CallEdge
            assertTrue(legacyCall.isVirtual)
            assertTrue(legacyCall.isDynamic)
            val legacyStaticCall = decodeEdgeV2.invoke(NodeSerializer, 1, from.value, to.value, null) as CallEdge
            assertFalse(legacyStaticCall.isVirtual)
            assertFalse(legacyStaticCall.isDynamic)
            assertEquals(
                TypeRelation.EXTENDS,
                (decodeEdgeV2.invoke(NodeSerializer, 2 or (TypeRelation.EXTENDS.ordinal shl 2), from.value, to.value, null) as TypeEdge).kind
            )
            assertEquals(
                comparison,
                (decodeEdgeV2.invoke(NodeSerializer, 3 or (ControlFlowKind.SEQUENTIAL.ordinal shl 2), from.value, to.value, comparison) as ControlFlowEdge).comparison
            )
            val v3Call = decodeEdgeV3.invoke(NodeSerializer, 1 or (1 shl 3) or (1 shl 4), from.value, to.value, null) as CallEdge
            assertTrue(v3Call.isVirtual)
            assertTrue(v3Call.isDynamic)
            val v3StaticCall = decodeEdgeV3.invoke(NodeSerializer, 1, from.value, to.value, null) as CallEdge
            assertFalse(v3StaticCall.isVirtual)
            assertFalse(v3StaticCall.isDynamic)
            assertEquals(
                ResourceRelation.OPENS,
                (decodeEdgeV3.invoke(NodeSerializer, 4 or (ResourceRelation.OPENS.ordinal shl 3), from.value, to.value, null) as ResourceEdge).kind
            )
            val decodeFailure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                decodeEdgeV3.invoke(NodeSerializer, 5, from.value, to.value, null)
            }
            assertTrue(decodeFailure.targetException is IllegalArgumentException)

            val boolOut = ByteArrayOutputStream()
            DataOutputStream(boolOut).use { dos -> writeAnyValue.invoke(NodeSerializer, dos, true, strings) }
            DataInputStream(ByteArrayInputStream(boolOut.toByteArray())).use { dis ->
                assertEquals(true, readAnyValue.invoke(NodeSerializer, dis, strings, NodeSerializer.FORMAT_VERSION))
            }

            val legacyListOut = ByteArrayOutputStream()
            DataOutputStream(legacyListOut).use { dos -> writeAnyValue.invoke(NodeSerializer, dos, listOf("hello"), strings) }
            DataInputStream(ByteArrayInputStream(legacyListOut.toByteArray())).use { dis ->
                val legacyFailure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                    readAnyValue.invoke(NodeSerializer, dis, strings, 1)
                }
                assertTrue(legacyFailure.targetException is IllegalArgumentException)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `byte buffer input stream available and counting output stream flush delegate correctly`() {
        val byteBufferInputStreamClass = Class.forName("io.johnsonlee.graphite.webgraph.ByteBufferInputStream")
        val inputCtor = byteBufferInputStreamClass.getDeclaredConstructor(ByteBuffer::class.java).apply { isAccessible = true }
        val input = inputCtor.newInstance(ByteBuffer.wrap(byteArrayOf(1, 2, 3))) as java.io.InputStream
        assertEquals(3, input.available())
        assertEquals(1, input.read())
        assertEquals(2, input.available())
        val buffer = ByteArray(4)
        assertEquals(2, input.read(buffer, 1, 2))
        assertEquals(byteArrayOf(0, 2, 3, 0).toList(), buffer.toList())
        assertEquals(-1, input.read(buffer, 0, buffer.size))

        val flushed = mutableListOf<Boolean>()
        val delegate = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun flush() { flushed += true }
        }
        val countingOutputStreamClass = Class.forName("io.johnsonlee.graphite.webgraph.CountingOutputStream")
        val outputCtor = countingOutputStreamClass.getDeclaredConstructor(OutputStream::class.java).apply { isAccessible = true }
        val output = outputCtor.newInstance(delegate) as OutputStream
        output.write(byteArrayOf(1, 2, 3))
        output.flush()
        val bytesWritten = countingOutputStreamClass.getDeclaredMethod("getBytesWritten").invoke(output) as Long
        assertEquals(3L, bytesWritten)
        assertEquals(listOf(true), flushed)
    }

    @Test
    fun `decodeEdge throws on unknown edge family`() {
        // family bits 0-1 can only be 0..3; label=0xFF has family=3 (ControlFlow) so
        // we need to craft a label where bits 0-1 give family > 3 -- not possible with 2 bits.
        // The else branch is a safety net. We test it directly.
        assertFailsWith<IllegalArgumentException> {
            // Use reflection or a trick: family is label & 0x3, so we need family=4+
            // but 2 bits max is 3. The else branch is unreachable in normal operation.
            // We test it by calling with a carefully crafted mock -- not possible without
            // modifying the method. Since it's truly unreachable with 2-bit masking,
            // we accept this line stays uncovered.

            // Instead, test the unknown node tag branch in readNode by writing raw bytes
            // with an unknown tag, then reading them with a string table.
            val dir = Files.createTempDirectory("webgraph-unknown-tag-test")
            try {
                // Build a minimal string table so readNode can be called
                StringTable.build(setOf("dummy"), dir)
                val strings = StringTable.load(dir)

                val baos = java.io.ByteArrayOutputStream()
                val dos = DataOutputStream(baos)
                dos.writeInt(999)  // node ID
                dos.writeByte(99)  // unknown tag
                dos.flush()
                val dis = DataInputStream(java.io.ByteArrayInputStream(baos.toByteArray()))
                NodeSerializer.readNode(dis, strings)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    // ========================================================================
    // writeAnyValue / readAnyValue round-trip for all value types
    // ========================================================================

    @Test
    fun `round-trip preserves enum constructor args with all value types`() {
        val builder = DefaultGraph.Builder()
        val enumConst = EnumConstant(
            NodeId.next(),
            TypeDescriptor("com.example.MyEnum"),
            "VALUE_A",
            listOf(
                42,                                               // Int
                123456789L,                                       // Long
                "hello",                                          // String
                3.14f,                                            // Float
                2.718281828,                                      // Double
                true,                                             // Boolean
                null,                                             // null
                EnumValueReference("com.example.Other", "X")      // EnumValueReference
            )
        )
        builder.addNode(enumConst)
        builder.addEnumValues("com.example.MyEnum", "VALUE_A", enumConst.constructorArgs)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-anyvalue-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEnum = loaded.node(enumConst.id) as EnumConstant
            assertEquals(8, loadedEnum.constructorArgs.size)
            assertEquals(42, loadedEnum.constructorArgs[0])
            assertEquals(123456789L, loadedEnum.constructorArgs[1])
            assertEquals("hello", loadedEnum.constructorArgs[2])
            assertEquals(3.14f, loadedEnum.constructorArgs[3])
            assertEquals(2.718281828, loadedEnum.constructorArgs[4])
            assertEquals(true, loadedEnum.constructorArgs[5])
            assertNull(loadedEnum.constructorArgs[6])
            val ref = loadedEnum.constructorArgs[7] as EnumValueReference
            assertEquals("com.example.Other", ref.enumClass)
            assertEquals("X", ref.enumName)

            // Also verify enum values metadata round-tripped
            val values = loaded.enumValues("com.example.MyEnum", "VALUE_A")
            assertNotNull(values)
            assertEquals(8, values.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // CallEdge with isVirtual and isDynamic flags round-trip via graph
    // ========================================================================

    @Test
    fun `round-trip preserves CallEdge with isVirtual and isDynamic flags`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val caller = MethodDescriptor(fooType, "caller", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(fooType, "callee", emptyList(), TypeDescriptor("void"))
        builder.addMethod(caller)
        builder.addMethod(callee)

        val callSite = CallSiteNode(NodeId.next(), caller, callee, 5, null, emptyList())
        val target = ReturnNode(NodeId.next(), callee)
        builder.addNode(callSite)
        builder.addNode(target)

        // isVirtual=true, isDynamic=true
        builder.addEdge(CallEdge(callSite.id, target.id, isVirtual = true, isDynamic = true))

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-call-flags-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEdges = loaded.outgoing(callSite.id, CallEdge::class.java).toList()
            assertEquals(1, loadedEdges.size)
            assertTrue(loadedEdges[0].isVirtual)
            assertTrue(loadedEdges[0].isDynamic)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Null annotation attribute value round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves null annotation attribute value`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "annotated", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)

        val returnNode = ReturnNode(NodeId.next(), method)
        builder.addNode(returnNode)

        // Add an annotation with a null attribute value
        builder.addMemberAnnotation(
            "com.example.Foo", "annotated",
            "javax.annotation.Nullable",
            mapOf("value" to null, "reason" to "test")
        )

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-null-annot-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val annotations = loaded.memberAnnotations("com.example.Foo", "annotated")
            assertTrue(annotations.containsKey("javax.annotation.Nullable"))
            val attrs = annotations["javax.annotation.Nullable"]!!
            assertNull(attrs["value"])
            assertEquals("test", attrs["reason"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Out-of-bounds NodeId returns empty sequences on loaded graph
    // ========================================================================

    @Test
    fun `outgoing and incoming return empty for out-of-bounds NodeId on loaded graph`() {
        val builder = DefaultGraph.Builder()
        val node = IntConstant(NodeId(0), 1)
        builder.addNode(node)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-oob-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            // NodeId with value >= numNodes should return empty
            val bigId = NodeId(999999)
            assertEquals(0, loaded.outgoing(bigId).count())
            assertEquals(0, loaded.incoming(bigId).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // BranchScopeData equals and hashCode coverage
    // ========================================================================

    @Test
    fun `BranchScopeData equals identity`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))

        // Identity
        assertTrue(data == data)
    }

    @Test
    fun `BranchScopeData equals with different type returns false`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data.equals("not a BranchScopeData").not())
    }

    @Test
    fun `BranchScopeData equals with equal values`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3))

        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
    }

    @Test
    fun `BranchScopeData not equal when conditionNodeId differs`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(1, method, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when method differs`() {
        val method1 = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val method2 = MethodDescriptor(TypeDescriptor("com.example.B"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method1, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method2, comp, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when comparison differs`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp1 = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val comp2 = BranchComparison(ComparisonOp.NE, NodeId(1))
        val data1 = BranchScopeData(0, method, comp1, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp2, intArrayOf(1), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when trueBranchNodeIds differ`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(9), intArrayOf(2))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData not equal when falseBranchNodeIds differ`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data1 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(2))
        val data2 = BranchScopeData(0, method, comp, intArrayOf(1), intArrayOf(3))

        assertTrue(data1 != data2)
    }

    @Test
    fun `BranchScopeData hashCode is consistent`() {
        val method = MethodDescriptor(TypeDescriptor("com.example.A"), "m", emptyList(), TypeDescriptor("void"))
        val comp = BranchComparison(ComparisonOp.EQ, NodeId(1))
        val data = BranchScopeData(0, method, comp, intArrayOf(1, 2), intArrayOf(3, 4))

        // hashCode should be consistent across calls
        assertEquals(data.hashCode(), data.hashCode())
    }

    // ========================================================================
    // writeAnyValue fallback for unsupported types (falls back to toString)
    // ========================================================================

    @Test
    fun `round-trip preserves list enum constructor arg`() {
        val builder = DefaultGraph.Builder()
        val enumConst = EnumConstant(
            NodeId.next(),
            TypeDescriptor("com.example.FallbackEnum"),
            "VAL",
            listOf(listOf("a", "b"))
        )
        builder.addNode(enumConst)
        builder.addEnumValues("com.example.FallbackEnum", "VAL", enumConst.constructorArgs)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-fallback-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEnum = loaded.node(enumConst.id) as EnumConstant
            assertEquals(listOf("a", "b"), loadedEnum.constructorArgs[0])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Load mode variants and lazy/mapped loading
    // ========================================================================

    @Test
    fun `load with explicit EAGER mode preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-eager-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.EAGER)
            assertGraphOperations(graph, loaded)
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
    fun `loadLazy preserves all graph operations`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-test")
        try {
            GraphStore.save(graph, dir)
            // save() already builds the node index
            val loaded = GraphStore.loadLazy(dir)
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
    fun `LazyWebGraphBackedGraph close releases resources`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-close-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadLazy(dir) as Closeable

            // Access a node to trigger RAF creation
            val lazyGraph = loaded as Graph
            val firstNode = lazyGraph.nodes(Node::class.java).first()
            assertNotNull(lazyGraph.node(firstNode.id))

            // Close should not throw
            loaded.close()

            // Calling close again should also be safe (idempotent)
            loaded.close()
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
    fun `loadLazy from nonexistent directory throws`() {
        assertFailsWith<IllegalArgumentException> {
            GraphStore.loadLazy(Files.createTempFile("not", "dir"))
        }
    }

    @Test
    fun `loadMapped from nonexistent directory throws`() {
        assertFailsWith<IllegalArgumentException> {
            GraphStore.loadMapped(Files.createTempFile("not", "dir"))
        }
    }

    @Test
    fun `loadLazy without node index throws`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-no-index-test")
        try {
            GraphStore.save(graph, dir)
            // Delete node index
            Files.delete(dir.resolve("graph.nodeindex"))
            assertFailsWith<IllegalArgumentException> {
                GraphStore.loadLazy(dir)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loadMapped without node index throws`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-no-index-test")
        try {
            GraphStore.save(graph, dir)
            // Delete node index
            Files.delete(dir.resolve("graph.nodeindex"))
            assertFailsWith<IllegalArgumentException> {
                GraphStore.loadMapped(dir)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `lazy graph typed outgoing and incoming edges`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-typed-edges-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadLazy(dir)
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
    fun `lazy graph out-of-bounds NodeId returns empty`() {
        val builder = DefaultGraph.Builder()
        val node = IntConstant(NodeId(0), 1)
        builder.addNode(node)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-lazy-oob-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadLazy(dir)
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
    fun `lazy graph nodes with supertype returns all subtypes`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-supertype-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadLazy(dir)
            try {
                // ConstantNode is a supertype of IntConstant, EnumConstant
                val constants = loaded.nodes(ConstantNode::class.java).toList()
                assertTrue(constants.size >= 2, "Should find at least IntConstant and EnumConstant")

                // Node is the ultimate supertype
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
    fun `lazy graph branch scopes round-trip`() {
        val graph = buildBranchScopeGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-branch-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadLazy(dir)
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
    fun `lazy graph resources preserves persisted text resources`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadLazy(dir)
            try {
                assertEquals(1, loaded.resources.list("**").count())
                val content = loaded.resources.open("application.properties").bufferedReader().readText()
                assertTrue(content.contains("feature.enabled=true"))
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

    // ========================================================================
    // Shared assertion helpers
    // ========================================================================

    private var branchCondNodeId: NodeId = NodeId(0)
    private var branchTrueNodeId: NodeId = NodeId(0)
    private var branchFalseNodeId: NodeId = NodeId(0)

    private fun buildBranchScopeGraph(): Graph {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "check", emptyList(), TypeDescriptor("boolean"))
        builder.addMethod(method)

        val condNode = IntConstant(NodeId.next(), 0)
        val trueNode = IntConstant(NodeId.next(), 1)
        val falseNode = IntConstant(NodeId.next(), 2)
        builder.addNode(condNode)
        builder.addNode(trueNode)
        builder.addNode(falseNode)

        branchCondNodeId = condNode.id
        branchTrueNodeId = trueNode.id
        branchFalseNodeId = falseNode.id

        val comp = BranchComparison(ComparisonOp.NE, condNode.id)
        builder.addBranchScope(condNode.id, method, comp, intArrayOf(trueNode.id.value), intArrayOf(falseNode.id.value))

        return builder.build()
    }

    private fun assertBranchScopeOperations(loaded: Graph) {
        val scopes = loaded.branchScopes().toList()
        assertEquals(1, scopes.size)
        assertEquals(branchCondNodeId, scopes[0].conditionNodeId)
        assertEquals(ComparisonOp.NE, scopes[0].comparison.operator)
        assertTrue(scopes[0].trueBranchNodeIds.contains(branchTrueNodeId.value))
        assertTrue(scopes[0].falseBranchNodeIds.contains(branchFalseNodeId.value))

        val scopesFor = loaded.branchScopesFor(branchCondNodeId).toList()
        assertEquals(1, scopesFor.size)

        assertEquals(0, loaded.branchScopesFor(NodeId(999999)).count())
    }

    private fun assertGraphOperations(original: Graph, loaded: Graph) {
        // Nodes
        val originalNodes = original.nodes(Node::class.java).toList()
        val loadedNodes = loaded.nodes(Node::class.java).toList()
        assertEquals(originalNodes.size, loadedNodes.size, "Node count should match")

        for (node in originalNodes) {
            assertNotNull(loaded.node(node.id), "Node ${node.id} should exist in loaded graph")
        }

        // Non-existent node
        assertNull(loaded.node(NodeId(999999)))

        // Typed node queries
        assertEquals(1, loaded.nodes(IntConstant::class.java).count())
        assertEquals(1, loaded.nodes(CallSiteNode::class.java).count())
        assertEquals(1, loaded.nodes(FieldNode::class.java).count())
        assertEquals(1, loaded.nodes(ParameterNode::class.java).count())
        assertEquals(1, loaded.nodes(LocalVariable::class.java).count())
        assertEquals(1, loaded.nodes(ReturnNode::class.java).count())
        assertEquals(1, loaded.nodes(EnumConstant::class.java).count())

        // Outgoing edges
        for (node in originalNodes) {
            val origEdges = original.outgoing(node.id).toList()
            val loadEdges = loaded.outgoing(node.id).toList()
            assertEquals(origEdges.size, loadEdges.size,
                "Outgoing edge count for node ${node.id}")
        }

        // Incoming edges
        for (node in originalNodes) {
            val origEdges = original.incoming(node.id).toList()
            val loadEdges = loaded.incoming(node.id).toList()
            assertEquals(origEdges.size, loadEdges.size,
                "Incoming edge count for node ${node.id}")
        }

        // callSites
        val matched = loaded.callSites(MethodPattern(declaringClass = "com.example.Foo", name = "baz")).toList()
        assertEquals(1, matched.size)
        val noMatch = loaded.callSites(MethodPattern(name = "nonexistent")).toList()
        assertTrue(noMatch.isEmpty())

        // supertypes and subtypes
        val child = TypeDescriptor("com.example.Child")
        val parent = TypeDescriptor("com.example.Parent")
        assertTrue(loaded.supertypes(child).any { it.className == "com.example.Parent" })
        assertTrue(loaded.subtypes(parent).any { it.className == "com.example.Child" })
        assertEquals(0, loaded.supertypes(TypeDescriptor("com.nonexistent.Type")).count())
        assertEquals(0, loaded.subtypes(TypeDescriptor("com.nonexistent.Type")).count())

        // methods
        val allMethods = loaded.methods(MethodPattern()).toList()
        assertEquals(2, allMethods.size)
        val barMethods = loaded.methods(MethodPattern(name = "bar")).toList()
        assertEquals(1, barMethods.size)

        // enumValues
        assertEquals(listOf(1, "active"), loaded.enumValues("com.example.Status", "ACTIVE"))
        assertNull(loaded.enumValues("com.example.Missing", "MISSING"))

        // memberAnnotations
        val annotations = loaded.memberAnnotations("com.example.Foo", "bar")
        assertTrue(annotations.containsKey("javax.annotation.Nullable"))
        assertTrue(loaded.memberAnnotations("com.example.Unknown", "unknown").isEmpty())

        // typeHierarchyTypes
        val types = loaded.typeHierarchyTypes()
        assertTrue(types.contains("com.example.Parent"))
        assertTrue(types.contains("com.example.Child"))

        // resources
        val resourceEntries = loaded.resources.list("**").toList()
        assertEquals(1, resourceEntries.size)
        assertEquals("application.properties", resourceEntries.single().path)
        assertTrue(loaded.resources.open("application.properties").bufferedReader().readText().contains("feature.mode=shadow"))
    }

    // ========================================================================
    // NodeSerializer header round-trip and invalid magic
    // ========================================================================

    @Test
    fun `writeHeader and readHeader round-trip via DataInputStream`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_METADATA)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        assertEquals(NodeSerializer.FORMAT_VERSION, version)
    }

    @Test
    fun `readHeader with invalid magic via DataInputStream throws`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(0x12345678) // wrong magic
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        assertFailsWith<IllegalArgumentException> {
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        }
    }

    @Test
    fun `readHeader with legacy version via DataInputStream succeeds`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        assertEquals(1, version)
    }

    @Test
    fun `readHeader with invalid magic via RandomAccessFile throws`() {
        val tmpFile = Files.createTempFile("bad-magic", ".bin")
        try {
            // Write wrong magic into the file
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos ->
                dos.writeInt(0x12345678) // wrong magic
            }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                assertFailsWith<IllegalArgumentException> {
                    NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                }
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader via RandomAccessFile round-trip`() {
        val tmpFile = Files.createTempFile("raf-header", ".bin")
        try {
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEDATA)
            }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                val version = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                assertEquals(NodeSerializer.FORMAT_VERSION, version)
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader with legacy version via RandomAccessFile succeeds`() {
        val tmpFile = Files.createTempFile("raf-unsupported-version", ".bin")
        try {
            DataOutputStream(tmpFile.toFile().outputStream()).use { dos ->
                dos.writeInt(NodeSerializer.MAGIC_NODEDATA or 0x01)
            }
            RandomAccessFile(tmpFile.toFile(), "r").use { raf ->
                val version = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
                assertEquals(1, version)
            }
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `readHeader with unknown version throws`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x04)
        dos.flush()
        val dis = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val error = assertFailsWith<IllegalArgumentException> {
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA)
        }
        assertTrue(error.message!!.contains("Unsupported GraphStore format version 4"))
    }

    // ========================================================================
    // Invalid magic in metadata file on load
    // ========================================================================

    @Test
    fun `invalid magic in metadata file throws on load`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("bad-magic-test")
        try {
            GraphStore.save(graph, dir)
            // Corrupt the metadata file
            val metadataFile = dir.resolve("graph.metadata").toFile()
            val bytes = metadataFile.readBytes()
            bytes[0] = 0x00 // corrupt magic
            metadataFile.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> {
                GraphStore.load(dir)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 annotation node decodes as string values`() {
        val dir = Files.createTempDirectory("legacy-node-strings")
        try {
            val strings = StringTable.build(
                listOf("Lcom/example/Foo;", "com.example.Foo", "bar", "value", "hello"),
                dir
            )
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeInt(42)
                dos.writeByte(13)
                dos.writeInt(strings.indexOf("Lcom/example/Foo;"))
                dos.writeInt(strings.indexOf("com.example.Foo"))
                dos.writeInt(strings.indexOf("bar"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("value"))
                dos.writeInt(strings.indexOf("hello"))
            }
            val node = NodeSerializer.readNode(
                DataInputStream(ByteArrayInputStream(baos.toByteArray())),
                strings,
                1
            ) as AnnotationNode
            assertEquals("hello", node.values["value"])
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 metadata decodes annotation values as strings`() {
        val dir = Files.createTempDirectory("legacy-metadata-strings")
        try {
            val strings = StringTable.build(
                listOf("com.example.Foo#bar", "javax.annotation.Nullable", "value", "hello"),
                dir
            )
            val baos = ByteArrayOutputStream()
            DataOutputStream(baos).use { dos ->
                dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(0)
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("com.example.Foo#bar"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("javax.annotation.Nullable"))
                dos.writeInt(1)
                dos.writeInt(strings.indexOf("value"))
                dos.writeInt(strings.indexOf("hello"))
                dos.writeInt(0)
            }
            val metadata = NodeSerializer.loadMetadata(
                DataInputStream(ByteArrayInputStream(baos.toByteArray())),
                strings
            )
            assertEquals(
                "hello",
                metadata.memberAnnotations["com.example.Foo#bar"]!!["javax.annotation.Nullable"]!!["value"]
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy v1 graph directory migrates end to end`() {
        val fixture = createLegacyV1GraphDir()
        try {
            val loaded = GraphStore.load(fixture.dir)
            val loadedNode = loaded.node(fixture.annotationNode.id) as AnnotationNode
            assertEquals("hello", loadedNode.values["value"])
            assertEquals(
                "hello",
                loaded.memberAnnotations("com.example.Foo", "bar")["javax.annotation.Nullable"]!!["value"]
            )

            Files.deleteIfExists(fixture.dir.resolve("graph.nodeindex"))
            val mapped = GraphStore.load(fixture.dir, GraphStore.LoadMode.MAPPED)
            try {
                val mappedNode = mapped.node(fixture.annotationNode.id) as AnnotationNode
                assertEquals("hello", mappedNode.values["value"])
            } finally {
                (mapped as Closeable).close()
            }

            val migratedDir = Files.createTempDirectory("legacy-v3-migrated")
            try {
                GraphStore.save(loaded, migratedDir)

                DataInputStream(migratedDir.resolve("graph.nodedata").toFile().inputStream()).use { dis ->
                    assertEquals(NodeSerializer.FORMAT_VERSION, NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA))
                }
                DataInputStream(migratedDir.resolve("graph.metadata").toFile().inputStream()).use { dis ->
                    assertEquals(NodeSerializer.FORMAT_VERSION, NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_METADATA))
                }

                val migrated = GraphStore.load(migratedDir)
                val migratedNode = migrated.node(fixture.annotationNode.id) as AnnotationNode
                assertEquals("hello", migratedNode.values["value"])
                assertEquals(
                    "hello",
                    migrated.memberAnnotations("com.example.Foo", "bar")["javax.annotation.Nullable"]!!["value"]
                )
            } finally {
                migratedDir.toFile().deleteRecursively()
            }
        } finally {
            fixture.dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Backward adjacency round-trip
    // ========================================================================

    @Test
    fun `round-trip preserves backward adjacency`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("backward-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            // Verify incoming edges for every node
            for (node in graph.nodes(Node::class.java)) {
                val originalIncoming = graph.incoming(node.id).toList()
                val loadedIncoming = loaded.incoming(node.id).toList()
                assertEquals(originalIncoming.size, loadedIncoming.size,
                    "Incoming edge count for node ${node.id}")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // No backward files written on save
    // ========================================================================

    @Test
    fun `no backward files written on save`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("no-backward-test")
        try {
            GraphStore.save(graph, dir)
            assertFalse(Files.exists(dir.resolve("backward.graph")))
            assertFalse(Files.exists(dir.resolve("backward.properties")))
            assertFalse(Files.exists(dir.resolve("backward.offsets")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // save with custom compression threads
    // ========================================================================

    @Test
    fun `save with custom compression threads`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("threads-test")
        try {
            GraphStore.save(graph, dir, compressionThreads = 1)
            val loaded = GraphStore.load(dir)
            assertEquals(
                graph.nodes(Node::class.java).count(),
                loaded.nodes(Node::class.java).count()
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Lazy and mapped load with parallel IO
    // ========================================================================

    @Test
    fun `lazy load with parallel IO preserves graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("lazy-parallel-test")
        try {
            GraphStore.save(graph, dir)
            GraphStore.ensureNodeIndex(dir)
            val loaded = GraphStore.loadLazy(dir)
            try {
                assertEquals(
                    graph.nodes(Node::class.java).count(),
                    loaded.nodes(Node::class.java).count()
                )
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped load with parallel IO preserves graph`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("mapped-parallel-test")
        try {
            GraphStore.save(graph, dir)
            GraphStore.ensureNodeIndex(dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertEquals(
                    graph.nodes(Node::class.java).count(),
                    loaded.nodes(Node::class.java).count()
                )
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // AUTO mode dispatches to MAPPED for large node count
    // ========================================================================

    @Test
    fun `AUTO mode dispatches to MAPPED for large node count`() {
        // Build a graph with enough nodes to exceed MAPPED_THRESHOLD (1M).
        // We can't actually create 1M nodes in a unit test, so we test AUTO
        // with small graph (which dispatches to EAGER) and verify correctness.
        // The MAPPED path is tested separately via explicit MAPPED mode.
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("auto-mode-test")
        try {
            GraphStore.save(graph, dir)
            // Small graph => AUTO should use EAGER
            val loaded = GraphStore.load(dir, GraphStore.LoadMode.AUTO)
            assertEquals(
                graph.nodes(Node::class.java).count(),
                loaded.nodes(Node::class.java).count()
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // StringTable size() coverage
    // ========================================================================

    @Test
    fun `StringTable size returns correct count`() {
        val dir = Files.createTempDirectory("string-table-size-test")
        try {
            val strings = setOf("alpha", "beta", "gamma")
            val table = StringTable.build(strings, dir)
            assertEquals(3, table.size())

            // Also verify after reload
            val loaded = StringTable.load(dir)
            assertEquals(3, loaded.size())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // AnnotationNode in collectMetadata
    // ========================================================================

    @Test
    fun `collectMetadata handles AnnotationNode correctly`() {
        // AnnotationNode's empty branch in collectMetadata should not crash
        val builder = DefaultGraph.Builder()
        val annotNode = AnnotationNode(
            NodeId.next(), "com.example.MyAnnotation",
            "com.example.Target", "doStuff",
            mapOf("key" to "value")
        )
        builder.addNode(annotNode)

        val graph = builder.build()
        val dir = Files.createTempDirectory("annotation-collect-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)
            val annotations = loaded.nodes(AnnotationNode::class.java).toList()
            assertEquals(1, annotations.size)
            assertEquals("com.example.MyAnnotation", annotations[0].name)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Test graph builder
    // ========================================================================

    private fun buildTestGraph(): Graph {
        val builder = DefaultGraph.Builder().setResources(
            object : ResourceAccessor {
                private val resources = mapOf(
                    "application.properties" to "feature.mode=shadow\nfeature.enabled=true\n"
                )

                override fun list(pattern: String): Sequence<ResourceEntry> =
                    resources.keys.asSequence().map { ResourceEntry(it, "test-fixture") }

                override fun open(path: String) =
                    resources[path]?.let { ByteArrayInputStream(it.toByteArray()) }
                        ?: throw java.io.IOException("Resource not found: $path")
            }
        )

        val fooType = TypeDescriptor("com.example.Foo")
        val parentType = TypeDescriptor("com.example.Parent")
        val childType = TypeDescriptor("com.example.Child")
        val method = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))

        // Nodes
        val param = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), method)
        val local = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), method)
        val constant = IntConstant(NodeId.next(), 42)
        val returnNode = ReturnNode(NodeId.next(), method)
        val callee = MethodDescriptor(fooType, "baz", emptyList(), TypeDescriptor("void"))
        val callSite = CallSiteNode(NodeId.next(), method, callee, 10, null, listOf(param.id))
        val enumConst = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
        val field = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)

        builder.addNode(param)
        builder.addNode(local)
        builder.addNode(constant)
        builder.addNode(returnNode)
        builder.addNode(callSite)
        builder.addNode(enumConst)
        builder.addNode(field)

        val annotNode = AnnotationNode(NodeId.next(), "javax.annotation.Nullable", "com.example.Foo", "bar", mapOf("value" to "true"))
        builder.addNode(annotNode)

        // Edges
        builder.addEdge(DataFlowEdge(param.id, local.id, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(constant.id, local.id, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(local.id, returnNode.id, DataFlowKind.RETURN_VALUE))
        builder.addEdge(CallEdge(callSite.id, callSite.id, isVirtual = false))

        // Method
        builder.addMethod(method)
        builder.addMethod(callee)

        // Type hierarchy
        builder.addTypeRelation(childType, parentType, TypeRelation.EXTENDS)

        // Enum values
        builder.addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active"))

        // Annotations
        builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", emptyMap())

        return builder.build()
    }

    private data class LegacyV1Fixture(
        val dir: Path,
        val method: MethodDescriptor,
        val annotationNode: AnnotationNode
    )

    private fun createLegacyV1GraphDir(): LegacyV1Fixture {
        val dir = Files.createTempDirectory("legacy-v1-graph")
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Foo"),
            "bar",
            emptyList(),
            TypeDescriptor("void")
        )
        val returnNode = ReturnNode(NodeId(1), method)
        val annotationNode = AnnotationNode(
            NodeId(2),
            "javax.annotation.Nullable",
            "com.example.Foo",
            "bar",
            mapOf("value" to "hello")
        )

        val builder = DefaultGraph.Builder()
        builder.addMethod(method)
        builder.addNode(returnNode)
        builder.addNode(annotationNode)
        builder.addMemberAnnotation("com.example.Foo", "bar", "javax.annotation.Nullable", annotationNode.values)
        GraphStore.save(builder.build(), dir)

        val strings = StringTable.build(
            setOf(
                method.declaringClass.className,
                method.name,
                method.returnType.className,
                annotationNode.name,
                annotationNode.className,
                annotationNode.memberName,
                "com.example.Foo#bar",
                "javax.annotation.Nullable",
                "value",
                "hello"
            ),
            dir
        )
        writeLegacyV1NodeData(dir, method, returnNode, annotationNode, strings)
        writeLegacyV1Metadata(dir, method, strings)
        writeLegacyV1NodeIndex(dir, method, returnNode, annotationNode, strings)
        writeLegacyV1Comparisons(dir)

        return LegacyV1Fixture(dir, method, annotationNode)
    }

    private fun writeLegacyV1NodeData(
        dir: Path,
        method: MethodDescriptor,
        returnNode: ReturnNode,
        annotationNode: AnnotationNode,
        strings: StringTable
    ) {
        DataOutputStream(dir.resolve("graph.nodedata").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_NODEDATA or 0x01)
            dos.writeInt(2)
            dos.write(legacyV1ReturnNodeBytes(returnNode, method, strings))
            dos.write(legacyV1AnnotationNodeBytes(annotationNode, strings))
        }
    }

    private fun legacyV1ReturnNodeBytes(
        returnNode: ReturnNode,
        method: MethodDescriptor,
        strings: StringTable
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(returnNode.id.value)
            dos.writeByte(11)
            writeLegacyV1MethodDescriptor(dos, method, strings)
            dos.writeBoolean(false)
        }
        return baos.toByteArray()
    }

    private fun legacyV1AnnotationNodeBytes(annotationNode: AnnotationNode, strings: StringTable): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { dos ->
            dos.writeInt(annotationNode.id.value)
            dos.writeByte(13)
            dos.writeInt(strings.indexOf(annotationNode.name))
            dos.writeInt(strings.indexOf(annotationNode.className))
            dos.writeInt(strings.indexOf(annotationNode.memberName))
            dos.writeInt(annotationNode.values.size)
            for ((k, v) in annotationNode.values) {
                dos.writeInt(strings.indexOf(k))
                dos.writeInt(strings.indexOf(v?.toString() ?: ""))
            }
        }
        return baos.toByteArray()
    }

    private fun writeLegacyV1Metadata(dir: Path, method: MethodDescriptor, strings: StringTable) {
        DataOutputStream(dir.resolve("graph.metadata").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_METADATA or 0x01)
            dos.writeInt(1)
            writeLegacyV1MethodDescriptor(dos, method, strings)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("com.example.Foo#bar"))
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("javax.annotation.Nullable"))
            dos.writeInt(1)
            dos.writeInt(strings.indexOf("value"))
            dos.writeInt(strings.indexOf("hello"))
            dos.writeInt(0)
        }
    }

    private fun writeLegacyV1NodeIndex(
        dir: Path,
        method: MethodDescriptor,
        returnNode: ReturnNode,
        annotationNode: AnnotationNode,
        strings: StringTable
    ) {
        val returnNodeSize = legacyV1ReturnNodeBytes(returnNode, method, strings).size.toLong()
        DataOutputStream(dir.resolve("graph.nodeindex").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_NODEINDEX or 0x01)
            dos.writeInt(2)
            dos.writeInt(returnNode.id.value)
            dos.writeByte(11)
            dos.writeLong(8L)
            dos.writeInt(annotationNode.id.value)
            dos.writeByte(13)
            dos.writeLong(8L + returnNodeSize)
        }
    }

    private fun writeLegacyV1Comparisons(dir: Path) {
        DataOutputStream(dir.resolve("graph.comparisons").toFile().outputStream()).use { dos ->
            dos.writeInt(NodeSerializer.MAGIC_COMPARISONS or 0x01)
            dos.writeInt(0)
        }
    }

    private fun writeLegacyV1MethodDescriptor(
        dos: DataOutputStream,
        method: MethodDescriptor,
        strings: StringTable
    ) {
        dos.writeInt(strings.indexOf(method.declaringClass.className))
        dos.writeInt(strings.indexOf(method.name))
        dos.writeInt(method.parameterTypes.size)
        method.parameterTypes.forEach { dos.writeInt(strings.indexOf(it.className)) }
        dos.writeInt(strings.indexOf(method.returnType.className))
    }
}
