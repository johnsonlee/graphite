package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherFunctionsTest : CypherFunctionsTestFixture() {

    @Test
    fun `id returns node id value`() {
        val node = IntConstant(NodeId.next(), 42)
        assertEquals(node.id.value, CypherFunctions.call("id", listOf(node)))
    }

    @Test
    fun `id returns null for non-node`() {
        assertNull(CypherFunctions.call("id", listOf("not a node")))
    }

    @Test
    fun `graphId returns null for non-qualified values`() {
        assertNull(CypherFunctions.call("graphId", listOf("not a qualified value")))
    }

    @Test
    fun `coalesce returns first non-null`() {
        assertEquals("a", CypherFunctions.call("coalesce", listOf(null, "a", "b")))
    }

    @Test
    fun `coalesce returns null when all null`() {
        assertNull(CypherFunctions.call("coalesce", listOf(null, null)))
    }

    @Test
    fun `timestamp returns current time`() {
        val before = System.currentTimeMillis()
        val ts = CypherFunctions.call("timestamp", emptyList()) as Long
        val after = System.currentTimeMillis()
        assertTrue(ts in before..after)
    }

    @Test
    fun `labels returns correct labels for IntConstant`() {
        val node = IntConstant(NodeId.next(), 42)
        assertEquals(listOf("IntConstant", "Constant"), CypherFunctions.call("labels", listOf(node)))
    }

    @Test
    fun `labels returns correct labels for CallSiteNode`() {
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(NodeId.next(), method, callee, 10, null, emptyList())
        assertEquals(listOf("CallSiteNode"), CypherFunctions.call("labels", listOf(node)))
    }

    @Test
    fun `labels returns correct labels for all constant types`() {
        assertEquals(listOf("StringConstant", "Constant"), CypherFunctions.call("labels", listOf(StringConstant(NodeId.next(), "x"))))
        assertEquals(listOf("LongConstant", "Constant"), CypherFunctions.call("labels", listOf(LongConstant(NodeId.next(), 1L))))
        assertEquals(listOf("FloatConstant", "Constant"), CypherFunctions.call("labels", listOf(FloatConstant(NodeId.next(), 1.0f))))
        assertEquals(listOf("DoubleConstant", "Constant"), CypherFunctions.call("labels", listOf(DoubleConstant(NodeId.next(), 1.0))))
        assertEquals(listOf("BooleanConstant", "Constant"), CypherFunctions.call("labels", listOf(BooleanConstant(NodeId.next(), true))))
        assertEquals(listOf("NullConstant", "Constant"), CypherFunctions.call("labels", listOf(NullConstant(NodeId.next()))))
        assertEquals(listOf("EnumConstant", "Constant"), CypherFunctions.call("labels", listOf(EnumConstant(NodeId.next(), type, "A"))))
    }

    @Test
    fun `labels returns correct labels for non-constant node types`() {
        assertEquals(listOf("LocalVariable"), CypherFunctions.call("labels", listOf(LocalVariable(NodeId.next(), "x", intType, method))))
        assertEquals(
            listOf("FieldNode"),
            CypherFunctions.call("labels", listOf(FieldNode(NodeId.next(), FieldDescriptor(type, "f", intType), false))))
        assertEquals(listOf("ParameterNode"), CypherFunctions.call("labels", listOf(ParameterNode(NodeId.next(), 0, intType, method))))
        assertEquals(listOf("ReturnNode"), CypherFunctions.call("labels", listOf(ReturnNode(NodeId.next(), method))))
    }

    @Test
    fun `labels returns correct labels for AnnotationNode`() {
        val node = AnnotationNode(NodeId.next(), "com.example.MyAnnotation", "com.example.Foo", "bar", emptyMap())
        assertEquals(listOf("AnnotationNode", "Annotation"), CypherFunctions.call("labels", listOf(node)))
    }

    @Test
    fun `labels returns correct labels for ResourceValueNode`() {
        val node = ResourceValueNode(NodeId.next(), "application.yml", "server.port", 8080, "yaml")
        assertEquals(listOf("ResourceValueNode", "ResourceValue", "Resource"), CypherFunctions.call("labels", listOf(node)))
    }

    @Test
    fun `labels returns correct labels for ResourceFileNode`() {
        val node = ResourceFileNode(NodeId.next(), "application.yml", "BOOT-INF/classes", "yaml")
        assertEquals(listOf("ResourceFileNode", "ResourceFile"), CypherFunctions.call("labels", listOf(node)))
    }

    @Test
    fun `labels returns empty for non-node`() {
        assertEquals(emptyList<String>(), CypherFunctions.call("labels", listOf("not a node")))
    }

    @Test
    fun `type returns edge type name`() {
        val from = NodeId.next()
        val to = NodeId.next()
        assertEquals("DATAFLOW", CypherFunctions.call("type", listOf(DataFlowEdge(from, to, DataFlowKind.ASSIGN))))
        assertEquals("CALL", CypherFunctions.call("type", listOf(CallEdge(from, to, false))))
        assertEquals("TYPE", CypherFunctions.call("type", listOf(TypeEdge(from, to, TypeRelation.EXTENDS))))
        assertEquals("CONTROL_FLOW", CypherFunctions.call("type", listOf(ControlFlowEdge(from, to, ControlFlowKind.SEQUENTIAL))))
        assertEquals("RESOURCE_LOOKUP", CypherFunctions.call("type", listOf(ResourceEdge(from, to, ResourceRelation.LOOKUP))))
        assertEquals("RESOURCE_OPEN", CypherFunctions.call("type", listOf(ResourceEdge(from, to, ResourceRelation.OPENS))))
        assertEquals("RESOURCE_LOAD", CypherFunctions.call("type", listOf(ResourceEdge(from, to, ResourceRelation.LOADS))))
        assertEquals(
            "RESOURCE_BUNDLE_CANDIDATE", CypherFunctions.call("type", listOf(ResourceEdge(from, to, ResourceRelation.BUNDLE_CANDIDATE))))
        assertEquals("RESOURCE_KEYS", CypherFunctions.call("type", listOf(ResourceEdge(from, to, ResourceRelation.ENUMERATES))))
    }

    @Test
    fun `type returns null for non-edge`() {
        assertNull(CypherFunctions.call("type", listOf("not an edge")))
    }

    @Test
    fun `properties returns all properties of a node`() {
        val node = IntConstant(NodeId.next(), 42)
        val props = CypherFunctions.call("properties", listOf(node)) as Map<*, *>
        assertEquals(node.id.value, props["id"])
        assertEquals(42, props["value"])
    }

    @Test
    fun `properties returns null for non-node`() {
        assertNull(CypherFunctions.call("properties", listOf("not a node")))
    }

    @Test
    fun `keys returns property names of a node`() {
        val node = IntConstant(NodeId.next(), 42)
        val keyList = CypherFunctions.call("keys", listOf(node)) as List<*>
        assertTrue(keyList.contains("id"))
        assertTrue(keyList.contains("value"))
    }

    @Test
    fun `keys returns null for non-node`() {
        assertNull(CypherFunctions.call("keys", listOf("not a node")))
    }

    @Test
    fun `exists returns true for non-null`() {
        assertEquals(true, CypherFunctions.call("exists", listOf("something")))
    }

    @Test
    fun `exists returns false for null`() {
        assertEquals(false, CypherFunctions.call("exists", listOf(null)))
    }

    @Test
    fun `getAllProperties returns complete map for CallSiteNode`() {
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(NodeId.next(), method, callee, 42, null, emptyList())
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("com.example.Repo", props["callee_class"])
        assertEquals("save", props["callee_name"])
        assertEquals("com.example.Service", props["caller_class"])
        assertEquals("process", props["caller_name"])
        assertEquals(42, props["line"])
        assertNotNull(props["callee_signature"])
        assertNotNull(props["caller_signature"])
    }

    @Test
    fun `getAllProperties returns complete map for FieldNode`() {
        val node = FieldNode(NodeId.next(), FieldDescriptor(type, "name", stringType), true)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("name", props["name"])
        assertEquals("java.lang.String", props["type"])
        assertEquals("com.example.Service", props["class"])
        assertEquals(true, props["static"])
    }

    @Test
    fun `getAllProperties returns complete map for ParameterNode`() {
        val node = ParameterNode(NodeId.next(), 0, intType, method)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals(0, props["index"])
        assertEquals("int", props["type"])
        assertNotNull(props["method"])
    }

    @Test
    fun `getAllProperties returns complete map for ReturnNode`() {
        val node = ReturnNode(NodeId.next(), method, stringType)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertNotNull(props["method"])
        assertEquals("java.lang.String", props["actual_type"])
    }

    @Test
    fun `getAllProperties returns complete map for ReturnNode without actual type`() {
        val node = ReturnNode(NodeId.next(), method, null)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertNull(props["actual_type"])
    }

    @Test
    fun `getAllProperties returns complete map for LocalVariable`() {
        val node = LocalVariable(NodeId.next(), "x", intType, method)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("x", props["name"])
        assertEquals("int", props["type"])
        assertNotNull(props["method"])
    }

    @Test
    fun `getAllProperties returns complete map for NullConstant`() {
        val node = NullConstant(NodeId.next())
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertTrue(props.containsKey("value"))
        assertNull(props["value"])
    }

    @Test
    fun `getAllProperties returns complete map for EnumConstant`() {
        val enumType = TypeDescriptor("com.example.Status")
        val node = EnumConstant(NodeId.next(), enumType, "ACTIVE", listOf("active"))
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("active", props["value"])
        assertEquals("ACTIVE", props["name"])
        assertEquals("com.example.Status", props["enum_type"])
    }

    @Test
    fun `getAllProperties returns complete map for all constant types`() {
        val intNode = IntConstant(NodeId.next(), 42)
        assertEquals(42, NodePropertyAccessor.getAllProperties(intNode)["value"])

        val strNode = StringConstant(NodeId.next(), "hello")
        assertEquals("hello", NodePropertyAccessor.getAllProperties(strNode)["value"])

        val longNode = LongConstant(NodeId.next(), 100L)
        assertEquals(100L, NodePropertyAccessor.getAllProperties(longNode)["value"])

        val floatNode = FloatConstant(NodeId.next(), 3.14f)
        assertEquals(3.14f, NodePropertyAccessor.getAllProperties(floatNode)["value"])

        val doubleNode = DoubleConstant(NodeId.next(), 2.718)
        assertEquals(2.718, NodePropertyAccessor.getAllProperties(doubleNode)["value"])

        val boolNode = BooleanConstant(NodeId.next(), true)
        assertEquals(true, NodePropertyAccessor.getAllProperties(boolNode)["value"])
    }
}
