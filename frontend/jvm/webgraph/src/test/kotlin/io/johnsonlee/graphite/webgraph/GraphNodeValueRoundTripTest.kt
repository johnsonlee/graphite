package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies graph node value round trip. */
class GraphNodeValueRoundTripTest : GraphStoreTestFixture() {

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

    @Test
    fun `round-trip preserves enum constructor args with all value types`() {
        val builder = DefaultGraph.Builder()
        val enumConst =
            EnumConstant(
                NodeId.next(),
                TypeDescriptor("com.example.MyEnum"),
                "VALUE_A",
                listOf(
                    42, // Int
                    123456789L, // Long
                    "hello", // String
                    3.14f, // Float
                    2.718281828, // Double
                    true, // Boolean
                    null, // null
                    EnumValueReference("com.example.Other", "X") // EnumValueReference
                    ))
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

    @Test
    fun `round-trip preserves null annotation attribute value`() {
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val method = MethodDescriptor(fooType, "annotated", emptyList(), TypeDescriptor("void"))
        builder.addMethod(method)

        val returnNode = ReturnNode(NodeId.next(), method)
        builder.addNode(returnNode)

        // Add an annotation with a null attribute value
        builder.addMemberAnnotation("com.example.Foo", "annotated", "javax.annotation.Nullable", mapOf("value" to null, "reason" to "test"))

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

    @Test
    fun `outgoing and incoming return empty for out-of-bounds NodeId on loaded graph`() {
        val builder = DefaultGraph.Builder()
        val node = IntConstant(NodeId(0), 1)
        builder.addNode(node)
        val graph = builder.build()

        val dir = Files.createTempDirectory("webgraph-oob-test")
        try {
            GraphStore.save(graph, dir)
            val loaded = GraphStore.loadMapped(dir)

            // NodeId with value >= numNodes should return empty
            val bigId = NodeId(999999)
            assertEquals(0, loaded.outgoing(bigId).count())
            assertEquals(0, loaded.incoming(bigId).count())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `round-trip preserves list enum constructor arg`() {
        val builder = DefaultGraph.Builder()
        val enumConst = EnumConstant(NodeId.next(), TypeDescriptor("com.example.FallbackEnum"), "VAL", listOf(listOf("a", "b")))
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
}
