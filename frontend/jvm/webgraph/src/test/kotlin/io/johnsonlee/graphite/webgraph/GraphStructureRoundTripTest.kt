package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.unavailableReason
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph structure round trip. */
class GraphStructureRoundTripTest : GraphStoreTestFixture() {

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

    @Test
    fun `saving a graph without resources writes an empty resource store`() {
        val graph = DefaultGraph.Builder().build()
        val dir = Files.createTempDirectory("webgraph-empty-resources-test")
        try {
            GraphStore.save(graph, dir)

            assertTrue(Files.isRegularFile(dir.resolve("graph.resources")))
            assertEquals(emptyList(), GraphStore.load(dir).resources.list("**").toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `saving a graph without resources replaces stale persisted resources`() {
        val dir = Files.createTempDirectory("webgraph-stale-resources-test")
        try {
            GraphStore.save(buildTestGraph(), dir)
            assertEquals(1, GraphStore.load(dir).resources.list("**").count())

            GraphStore.save(DefaultGraph.Builder().build(), dir)

            assertEquals(emptyList(), GraphStore.load(dir).resources.list("**").toList())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loading a graph without a resource store reports resources unavailable`() {
        val dir = Files.createTempDirectory("webgraph-missing-resources-test")
        try {
            GraphStore.save(DefaultGraph.Builder().build(), dir)
            Files.delete(dir.resolve("graph.resources"))

            val loaded = GraphStore.load(dir)
            assertTrue(loaded.resources.unavailableReason.orEmpty().contains("rebuild this graph"))
            assertEquals(emptyList(), loaded.resources.list("**").toList())
            assertFailsWith<java.io.IOException> { loaded.resources.open("application.properties") }

            val mapped = GraphStore.loadMapped(dir)
            try {
                assertTrue(mapped.resources.unavailableReason.orEmpty().contains("rebuild this graph"))
            } finally {
                (mapped as Closeable).close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

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
}
