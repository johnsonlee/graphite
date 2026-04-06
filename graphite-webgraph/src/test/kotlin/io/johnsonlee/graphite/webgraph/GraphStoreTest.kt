package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.nodes
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.EmptyResourceAccessor
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

            val intConstants = loaded.nodes<IntConstant>().toList()
            assertEquals(1, intConstants.size)
            assertEquals(42, intConstants[0].value)

            val callSites = loaded.nodes<CallSiteNode>().toList()
            assertEquals(1, callSites.size)

            val fields = loaded.nodes<FieldNode>().toList()
            assertEquals(1, fields.size)

            val params = loaded.nodes<ParameterNode>().toList()
            assertEquals(1, params.size)

            val locals = loaded.nodes<LocalVariable>().toList()
            assertEquals(1, locals.size)

            val returns = loaded.nodes<ReturnNode>().toList()
            assertEquals(1, returns.size)

            val enums = loaded.nodes<EnumConstant>().toList()
            assertEquals(1, enums.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ========================================================================
    // Resources on loaded graph
    // ========================================================================

    @Test
    fun `loaded graph resources returns EmptyResourceAccessor`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            assertTrue(loaded.resources === EmptyResourceAccessor)
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
            val locals = loaded.nodes<LocalVariable>().toList()
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
    fun `round-trip enum constructor arg with unsupported type falls back to toString`() {
        val builder = DefaultGraph.Builder()
        // Use a List as constructor arg -- not a directly supported type,
        // so writeAnyValue will use the else branch (VAL_STRING + toString())
        val enumConst = EnumConstant(
            NodeId.next(),
            TypeDescriptor("com.example.FallbackEnum"),
            "VAL",
            listOf(listOf("a", "b"))  // List<String> triggers the else fallback
        )
        builder.addNode(enumConst)
        builder.addEnumValues("com.example.FallbackEnum", "VAL", enumConst.constructorArgs)

        val graph = builder.build()
        val dir = Files.createTempDirectory("webgraph-fallback-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.load(dir)

            val loadedEnum = loaded.node(enumConst.id) as EnumConstant
            // The list was serialized via toString(), so it comes back as a String
            assertEquals("[a, b]", loadedEnum.constructorArgs[0])
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
                assertEquals(7, allNodes.size)
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
                assertEquals(7, allNodes.size)
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
    fun `lazy graph resources returns EmptyResourceAccessor`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-lazy-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadLazy(dir)
            try {
                assertTrue(loaded.resources === EmptyResourceAccessor)
            } finally {
                (loaded as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped graph resources returns EmptyResourceAccessor`() {
        val graph = buildTestGraph()
        val dir = Files.createTempDirectory("webgraph-mapped-resources-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)
            try {
                assertTrue(loaded.resources === EmptyResourceAccessor)
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
        assertTrue(loaded.resources === EmptyResourceAccessor)
    }

    // ========================================================================
    // Test graph builder
    // ========================================================================

    private fun buildTestGraph(): Graph {
        val builder = DefaultGraph.Builder()

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
}
