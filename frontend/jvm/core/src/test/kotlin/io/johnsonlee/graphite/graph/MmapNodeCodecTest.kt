package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ComparisonOp
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
import io.johnsonlee.graphite.input.JavaArchiveLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MmapNodeCodecTest : MmapGraphBuilderTestSupport() {
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
        val node =
            EnumConstant(
                id,
                TypeDescriptor("com.example.Priority"),
                "HIGH",
                listOf(1, "urgent", true, null, EnumValueReference("com.example.Level", "TOP")))
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
        val node = FieldNode(id, FieldDescriptor(TypeDescriptor("com.example.Foo"), "count", TypeDescriptor("int")), isStatic = true)
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
    fun `round-trip ResourceFileNode`() {
        val id = NodeId.next()
        val node =
            ResourceFileNode(id, "config/application.properties", JavaArchiveLayout.BOOT_INF_CLASSES.removeSuffix("/"), "properties", "dev")
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

    @Test
    fun `round-trip AnnotationNode with values`() {
        val id = NodeId.next()
        val node =
            AnnotationNode(
                id,
                "org.springframework.web.bind.annotation.GetMapping",
                "com.example.UserController",
                "getUser",
                mapOf(
                    "value" to "/api/users/{id}",
                    "produces" to listOf("application/json", "application/xml"),
                    "required" to true,
                    "status" to 200))
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as AnnotationNode
        assertEquals(node.name, restored.name)
        assertEquals(node.className, restored.className)
        assertEquals(node.memberName, restored.memberName)
        assertEquals(node.values, restored.values)
    }

    @Test
    fun `round-trip AnnotationNode with empty values`() {
        val id = NodeId.next()
        val node = AnnotationNode(id, "javax.persistence.Id", "com.example.User", "id", emptyMap())
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as AnnotationNode
        assertEquals(node, restored)
    }

    @Test
    fun `round-trip AnnotationNode with null value`() {
        val id = NodeId.next()
        val node = AnnotationNode(id, "com.example.MyAnnotation", "com.example.Foo", "bar", mapOf("key" to null))
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as AnnotationNode
        assertEquals(node.name, restored.name)
        assertNull(restored.values["key"])
    }

    @Test
    fun `round-trip AnnotationNode with very large string value`() {
        val id = NodeId.next()
        val largeValue = "x".repeat(1_100_000)
        val node = AnnotationNode(id, "com.example.BigAnnotation", "com.example.Foo", "bar", mapOf("value" to largeValue))
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as AnnotationNode
        assertEquals(largeValue, restored.values["value"])
    }

    @Test
    fun `round-trip AnnotationNode class-level annotation`() {
        val id = NodeId.next()
        val node = AnnotationNode(id, "org.springframework.stereotype.Controller", "com.example.MyController", "<class>", emptyMap())
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as AnnotationNode
        assertEquals("<class>", restored.memberName)
        assertEquals(node, restored)
    }

    @Test
    fun `round-trip ResourceValueNode`() {
        val id = NodeId.next()
        val node = ResourceValueNode(id, "application.yml", "server.port", 8080, "yaml", "dev")
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as ResourceValueNode
        assertEquals(node, restored)
    }

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
        val graph = MmapGraphBuilder().addTypeRelation(sub, sup, TypeRelation.EXTENDS).build()
        assertEquals(listOf(sup), graph.supertypes(sub).toList())
        assertEquals(listOf(sub), graph.subtypes(sup).toList())
    }

    @Test
    fun `round-trip enum values`() {
        val graph = MmapGraphBuilder().addEnumValues("com.example.Status", "ACTIVE", listOf(1, "active", null)).build()
        val values = graph.enumValues("com.example.Status", "ACTIVE")
        assertNotNull(values)
        assertEquals(listOf(1, "active", null), values)
    }

    @Test
    fun `round-trip member annotations`() {
        val graph =
            MmapGraphBuilder()
                .addMemberAnnotation(
                    "com.example.User",
                    "name",
                    "javax.validation.NotNull",
                    mapOf("message" to "required", "groups" to listOf("Create", "Update")))
                .addMemberAnnotation("com.example.User", "name", "javax.persistence.Column", mapOf("length" to 255, "nullable" to false))
                .build()
        val annotations = graph.memberAnnotations("com.example.User", "name")
        assertEquals(2, annotations.size)
        assertEquals("required", annotations["javax.validation.NotNull"]?.get("message"))
        assertEquals(listOf("Create", "Update"), annotations["javax.validation.NotNull"]?.get("groups"))
        assertEquals(255, annotations["javax.persistence.Column"]?.get("length"))
        assertEquals(false, annotations["javax.persistence.Column"]?.get("nullable"))
    }

    @Test
    fun `round-trip member annotation with default empty values`() {
        val graph = MmapGraphBuilder().addMemberAnnotation("com.example.User", "id", "javax.persistence.Id").build()
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
        val graph = MmapGraphBuilder().addBranchScope(condId, method, comp, intArrayOf(10, 20), intArrayOf(30, 40)).build()
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
    fun `EnumConstant with unknown type constructor arg round-trips via toString`() {
        // A non-primitive, non-String, non-EnumValueReference argument triggers the else branch
        // in writeAnyValue (writes as VAL_STRING + toString()) and readAnyValue (reads as String)
        val id = NodeId.next()
        val customValue = java.math.BigDecimal("123.456")
        val node = EnumConstant(id, TypeDescriptor("com.example.Custom"), "VALUE", listOf(customValue))
        val graph = MmapGraphBuilder().addNode(node).build()
        val restored = graph.node(id) as EnumConstant
        // The value is serialized via toString() and deserialized as a String
        assertEquals("123.456", restored.constructorArgs[0])
    }
}
