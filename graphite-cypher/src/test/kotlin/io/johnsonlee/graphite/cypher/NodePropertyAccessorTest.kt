package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodePropertyAccessorTest {

    private val type = TypeDescriptor("com.example.Service")
    private val intType = TypeDescriptor("int")
    private val stringType = TypeDescriptor("java.lang.String")
    private val method = MethodDescriptor(type, "process", listOf(intType), stringType)

    @Before
    fun setup() {
        NodeId.reset()
    }

    @Test
    fun `getProperty returns id for any node`() {
        val node = IntConstant(NodeId.next(), 42)
        assertEquals(node.id.value, NodePropertyAccessor.getProperty(node, "id"))
    }

    @Test
    fun `getProperty returns type name for nodes without type property`() {
        val node = IntConstant(NodeId.next(), 42)
        assertEquals("IntConstant", NodePropertyAccessor.getProperty(node, "type"))
    }

    @Test
    fun `getProperty returns specific type for nodes with type property`() {
        val node = FieldNode(NodeId.next(), FieldDescriptor(type, "f", intType), false)
        // FieldNode has a "type" property that returns the field's type
        assertEquals("int", NodePropertyAccessor.getProperty(node, "type"))
    }

    @Test
    fun `IntConstant properties`() {
        val node = IntConstant(NodeId.next(), 42)
        assertEquals(42, NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `StringConstant properties`() {
        val node = StringConstant(NodeId.next(), "hello")
        assertEquals("hello", NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `LongConstant properties`() {
        val node = LongConstant(NodeId.next(), 100L)
        assertEquals(100L, NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `FloatConstant properties`() {
        val node = FloatConstant(NodeId.next(), 3.14f)
        assertEquals(3.14f, NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `DoubleConstant properties`() {
        val node = DoubleConstant(NodeId.next(), 2.718)
        assertEquals(2.718, NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `BooleanConstant properties`() {
        val node = BooleanConstant(NodeId.next(), true)
        assertEquals(true, NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `NullConstant properties with unknown key`() {
        val node = NullConstant(NodeId.next())
        // NullConstant.value is always null
        assertNull(NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "nonexistent"))
    }

    @Test
    fun `NullConstant returns null for any property`() {
        val node = NullConstant(NodeId.next())
        assertNull(NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `EnumConstant properties`() {
        val enumType = TypeDescriptor("com.example.Status")
        val node = EnumConstant(NodeId.next(), enumType, "ACTIVE", listOf("active"))
        assertEquals("ACTIVE", NodePropertyAccessor.getProperty(node, "name"))
        assertEquals("com.example.Status", NodePropertyAccessor.getProperty(node, "enum_type"))
        assertEquals("active", NodePropertyAccessor.getProperty(node, "value"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `CallSiteNode properties`() {
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(NodeId.next(), method, callee, 42, null, emptyList())
        assertEquals("com.example.Repo", NodePropertyAccessor.getProperty(node, "callee_class"))
        assertEquals("save", NodePropertyAccessor.getProperty(node, "callee_name"))
        assertEquals("com.example.Service", NodePropertyAccessor.getProperty(node, "caller_class"))
        assertEquals("process", NodePropertyAccessor.getProperty(node, "caller_name"))
        assertEquals(42, NodePropertyAccessor.getProperty(node, "line"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `FieldNode properties`() {
        val node = FieldNode(NodeId.next(), FieldDescriptor(type, "name", stringType), true)
        assertEquals("name", NodePropertyAccessor.getProperty(node, "name"))
        assertEquals("java.lang.String", NodePropertyAccessor.getProperty(node, "type"))
        assertEquals("com.example.Service", NodePropertyAccessor.getProperty(node, "class"))
        assertEquals(true, NodePropertyAccessor.getProperty(node, "static"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `ParameterNode properties`() {
        val node = ParameterNode(NodeId.next(), 0, intType, method)
        assertEquals(0, NodePropertyAccessor.getProperty(node, "index"))
        assertEquals("int", NodePropertyAccessor.getProperty(node, "type"))
        assertTrue((NodePropertyAccessor.getProperty(node, "method") as String).contains("process"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `ReturnNode properties`() {
        val node = ReturnNode(NodeId.next(), method, stringType)
        assertTrue((NodePropertyAccessor.getProperty(node, "method") as String).contains("process"))
        assertEquals("java.lang.String", NodePropertyAccessor.getProperty(node, "actual_type"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `ReturnNode without actual type`() {
        val node = ReturnNode(NodeId.next(), method, null)
        assertNull(NodePropertyAccessor.getProperty(node, "actual_type"))
    }

    @Test
    fun `LocalVariable properties`() {
        val node = LocalVariable(NodeId.next(), "x", intType, method)
        assertEquals("x", NodePropertyAccessor.getProperty(node, "name"))
        assertEquals("int", NodePropertyAccessor.getProperty(node, "type"))
        assertTrue((NodePropertyAccessor.getProperty(node, "method") as String).contains("process"))
        assertNull(NodePropertyAccessor.getProperty(node, "unknown"))
    }

    @Test
    fun `resolveNodeLabel maps standard names`() {
        assertEquals(CallSiteNode::class.java, NodePropertyAccessor.resolveNodeLabel("CallSiteNode"))
        assertEquals(CallSiteNode::class.java, NodePropertyAccessor.resolveNodeLabel("callsite"))
        assertEquals(IntConstant::class.java, NodePropertyAccessor.resolveNodeLabel("IntConstant"))
        assertEquals(StringConstant::class.java, NodePropertyAccessor.resolveNodeLabel("StringConstant"))
        assertEquals(LongConstant::class.java, NodePropertyAccessor.resolveNodeLabel("LongConstant"))
        assertEquals(FloatConstant::class.java, NodePropertyAccessor.resolveNodeLabel("FloatConstant"))
        assertEquals(DoubleConstant::class.java, NodePropertyAccessor.resolveNodeLabel("DoubleConstant"))
        assertEquals(BooleanConstant::class.java, NodePropertyAccessor.resolveNodeLabel("BooleanConstant"))
        assertEquals(NullConstant::class.java, NodePropertyAccessor.resolveNodeLabel("NullConstant"))
        assertEquals(EnumConstant::class.java, NodePropertyAccessor.resolveNodeLabel("EnumConstant"))
        assertEquals(ConstantNode::class.java, NodePropertyAccessor.resolveNodeLabel("Constant"))
        assertEquals(ConstantNode::class.java, NodePropertyAccessor.resolveNodeLabel("ConstantNode"))
        assertEquals(FieldNode::class.java, NodePropertyAccessor.resolveNodeLabel("FieldNode"))
        assertEquals(FieldNode::class.java, NodePropertyAccessor.resolveNodeLabel("field"))
        assertEquals(ParameterNode::class.java, NodePropertyAccessor.resolveNodeLabel("ParameterNode"))
        assertEquals(ParameterNode::class.java, NodePropertyAccessor.resolveNodeLabel("parameter"))
        assertEquals(ReturnNode::class.java, NodePropertyAccessor.resolveNodeLabel("ReturnNode"))
        assertEquals(ReturnNode::class.java, NodePropertyAccessor.resolveNodeLabel("return"))
        assertEquals(LocalVariable::class.java, NodePropertyAccessor.resolveNodeLabel("LocalVariable"))
        assertEquals(LocalVariable::class.java, NodePropertyAccessor.resolveNodeLabel("local"))
        assertEquals(Node::class.java, NodePropertyAccessor.resolveNodeLabel("node"))
        assertEquals(Node::class.java, NodePropertyAccessor.resolveNodeLabel("Node"))
    }

    @Test
    fun `resolveNodeLabel returns Node for unknown labels`() {
        assertEquals(Node::class.java, NodePropertyAccessor.resolveNodeLabel("Unknown"))
    }

    @Test
    fun `resolveEdgeType maps standard types`() {
        assertEquals(DataFlowEdge::class.java, NodePropertyAccessor.resolveEdgeType("DATAFLOW"))
        assertEquals(DataFlowEdge::class.java, NodePropertyAccessor.resolveEdgeType("dataflow"))
        assertEquals(DataFlowEdge::class.java, NodePropertyAccessor.resolveEdgeType("DATA_FLOW"))
        assertEquals(CallEdge::class.java, NodePropertyAccessor.resolveEdgeType("CALL"))
        assertEquals(TypeEdge::class.java, NodePropertyAccessor.resolveEdgeType("TYPE"))
        assertEquals(ControlFlowEdge::class.java, NodePropertyAccessor.resolveEdgeType("CONTROL_FLOW"))
        assertEquals(ControlFlowEdge::class.java, NodePropertyAccessor.resolveEdgeType("CONTROLFLOW"))
    }

    @Test
    fun `resolveEdgeType returns null for unknown types`() {
        assertNull(NodePropertyAccessor.resolveEdgeType("UNKNOWN"))
    }

    @Test
    fun `nodeTypeName returns correct names`() {
        assertEquals("IntConstant", NodePropertyAccessor.nodeTypeName(IntConstant(NodeId.next(), 0)))
        assertEquals("StringConstant", NodePropertyAccessor.nodeTypeName(StringConstant(NodeId.next(), "")))
        assertEquals("BooleanConstant", NodePropertyAccessor.nodeTypeName(BooleanConstant(NodeId.next(), false)))
        assertEquals("NullConstant", NodePropertyAccessor.nodeTypeName(NullConstant(NodeId.next())))
        assertEquals("FieldNode", NodePropertyAccessor.nodeTypeName(FieldNode(NodeId.next(), FieldDescriptor(type, "f", intType), false)))
        assertEquals("ParameterNode", NodePropertyAccessor.nodeTypeName(ParameterNode(NodeId.next(), 0, intType, method)))
        assertEquals("ReturnNode", NodePropertyAccessor.nodeTypeName(ReturnNode(NodeId.next(), method)))
        assertEquals("LocalVariable", NodePropertyAccessor.nodeTypeName(LocalVariable(NodeId.next(), "x", intType, method)))
    }

    @Test
    fun `CallSiteNode callee_signature and caller_signature`() {
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(NodeId.next(), method, callee, 42, null, emptyList())
        val calleeSig = NodePropertyAccessor.getProperty(node, "callee_signature") as String
        assertTrue(calleeSig.contains("save"))
        val callerSig = NodePropertyAccessor.getProperty(node, "caller_signature") as String
        assertTrue(callerSig.contains("process"))
    }
}
