package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.*
import kotlin.test.*

class HelpersTest {

    companion object {
        private val fooType = TypeDescriptor("com.example.Foo")
        private val childType = TypeDescriptor("com.example.Child")
        private val barMethod = MethodDescriptor(fooType, "bar", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        private val bazMethod = MethodDescriptor(fooType, "baz", emptyList(), TypeDescriptor("void"))

        private val paramNode = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), barMethod)
        private val localNode = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), barMethod)
        private val intConstNode = IntConstant(NodeId.next(), 42)
        private val strConstNode = StringConstant(NodeId.next(), "hello")
        private val returnNode = ReturnNode(NodeId.next(), barMethod)
        private val callSiteNode = CallSiteNode(NodeId.next(), barMethod, bazMethod, 10, null, listOf(paramNode.id))
        private val enumConstNode = EnumConstant(NodeId.next(), TypeDescriptor("com.example.Status"), "ACTIVE", listOf(1, "active"))
        private val fieldNode = FieldNode(NodeId.next(), FieldDescriptor(fooType, "name", TypeDescriptor("java.lang.String")), false)
    }

    // ========================================================================
    // resolveNodeType
    // ========================================================================

    @Test
    fun `resolveNodeType maps CallSiteNode`() {
        assertEquals(CallSiteNode::class.java, resolveNodeType("CallSiteNode"))
        assertEquals(CallSiteNode::class.java, resolveNodeType("callsite"))
    }

    @Test
    fun `resolveNodeType maps ConstantNode`() {
        assertEquals(ConstantNode::class.java, resolveNodeType("constant"))
        assertEquals(ConstantNode::class.java, resolveNodeType("ConstantNode"))
    }

    @Test
    fun `resolveNodeType maps FieldNode`() {
        assertEquals(FieldNode::class.java, resolveNodeType("field"))
        assertEquals(FieldNode::class.java, resolveNodeType("FieldNode"))
    }

    @Test
    fun `resolveNodeType maps ParameterNode`() {
        assertEquals(ParameterNode::class.java, resolveNodeType("parameter"))
        assertEquals(ParameterNode::class.java, resolveNodeType("ParameterNode"))
    }

    @Test
    fun `resolveNodeType maps ReturnNode`() {
        assertEquals(ReturnNode::class.java, resolveNodeType("return"))
        assertEquals(ReturnNode::class.java, resolveNodeType("ReturnNode"))
    }

    @Test
    fun `resolveNodeType maps LocalVariable`() {
        assertEquals(LocalVariable::class.java, resolveNodeType("local"))
        assertEquals(LocalVariable::class.java, resolveNodeType("LocalVariable"))
    }

    @Test
    fun `resolveNodeType defaults to Node for null`() {
        assertEquals(Node::class.java, resolveNodeType(null))
    }

    @Test
    fun `resolveNodeType defaults to Node for unknown`() {
        assertEquals(Node::class.java, resolveNodeType("SomethingElse"))
    }

    // ========================================================================
    // formatNode
    // ========================================================================

    @Test
    fun `formatNode formats CallSiteNode`() {
        val result = formatNode(callSiteNode)
        assertTrue(result.startsWith("CallSite["), "Should start with 'CallSite[', got: $result")
        assertTrue(result.contains("Foo.bar"), "Should contain caller 'Foo.bar', got: $result")
        assertTrue(result.contains("Foo.baz"), "Should contain callee 'Foo.baz', got: $result")
    }

    @Test
    fun `formatNode formats IntConstant`() {
        val result = formatNode(intConstNode)
        assertTrue(result.startsWith("IntConstant["), "Should start with 'IntConstant[', got: $result")
        assertTrue(result.contains("= 42"), "Should contain value '= 42', got: $result")
    }

    @Test
    fun `formatNode formats StringConstant`() {
        val result = formatNode(strConstNode)
        assertTrue(result.startsWith("StringConstant["), "Should start with 'StringConstant[', got: $result")
        assertTrue(result.contains("\"hello\""), "Should contain quoted value, got: $result")
    }

    @Test
    fun `formatNode formats EnumConstant`() {
        val result = formatNode(enumConstNode)
        assertTrue(result.startsWith("EnumConstant["), "Should start with 'EnumConstant[', got: $result")
        assertTrue(result.contains("Status.ACTIVE"), "Should contain enum name, got: $result")
    }

    @Test
    fun `formatNode formats FieldNode`() {
        val result = formatNode(fieldNode)
        assertTrue(result.startsWith("Field["), "Should start with 'Field[', got: $result")
        assertTrue(result.contains("Foo.name"), "Should contain field name, got: $result")
        assertTrue(result.contains("String"), "Should contain field type, got: $result")
    }

    @Test
    fun `formatNode formats ParameterNode`() {
        val result = formatNode(paramNode)
        assertTrue(result.startsWith("Parameter["), "Should start with 'Parameter[', got: $result")
        assertTrue(result.contains("bar"), "Should contain method name, got: $result")
        assertTrue(result.contains("#0"), "Should contain parameter index, got: $result")
    }

    @Test
    fun `formatNode formats ReturnNode`() {
        val result = formatNode(returnNode)
        assertTrue(result.startsWith("Return["), "Should start with 'Return[', got: $result")
        assertTrue(result.contains("bar"), "Should contain method name, got: $result")
    }

    @Test
    fun `formatNode formats LocalVariable`() {
        val result = formatNode(localNode)
        assertTrue(result.startsWith("Local["), "Should start with 'Local[', got: $result")
        assertTrue(result.contains("x"), "Should contain variable name, got: $result")
        assertTrue(result.contains("int"), "Should contain variable type, got: $result")
    }

    @Test
    fun `formatNode formats LongConstant`() {
        val node = LongConstant(NodeId.next(), 123456789L)
        val result = formatNode(node)
        assertTrue(result.startsWith("LongConstant["), "Should start with 'LongConstant[', got: $result")
        assertTrue(result.contains("= 123456789"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats FloatConstant`() {
        val node = FloatConstant(NodeId.next(), 3.14f)
        val result = formatNode(node)
        assertTrue(result.startsWith("FloatConstant["), "Should start with 'FloatConstant[', got: $result")
        assertTrue(result.contains("3.14"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats DoubleConstant`() {
        val node = DoubleConstant(NodeId.next(), 2.718)
        val result = formatNode(node)
        assertTrue(result.startsWith("DoubleConstant["), "Should start with 'DoubleConstant[', got: $result")
        assertTrue(result.contains("2.718"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats BooleanConstant`() {
        val node = BooleanConstant(NodeId.next(), true)
        val result = formatNode(node)
        assertTrue(result.startsWith("BooleanConstant["), "Should start with 'BooleanConstant[', got: $result")
        assertTrue(result.contains("= true"), "Should contain value, got: $result")
    }

    @Test
    fun `formatNode formats NullConstant`() {
        val node = NullConstant(NodeId.next())
        val result = formatNode(node)
        assertTrue(result.startsWith("NullConstant["), "Should start with 'NullConstant[', got: $result")
    }

    // ========================================================================
    // nodeToMap
    // ========================================================================

    @Test
    fun `nodeToMap for CallSiteNode includes correct fields`() {
        val map = nodeToMap(callSiteNode)
        assertEquals("CallSiteNode", map["type"])
        assertEquals(callSiteNode.id.value, map["id"])
        assertNotNull(map["caller"])
        assertNotNull(map["callee"])
        assertNotNull(map["label"])
        assertTrue((map["label"] as String).contains("Foo.baz"))
    }

    @Test
    fun `nodeToMap for IntConstant includes correct fields`() {
        val map = nodeToMap(intConstNode)
        assertEquals("IntConstant", map["type"])
        assertEquals(intConstNode.id.value, map["id"])
        assertEquals(42, map["value"])
        assertEquals("42", map["label"])
    }

    @Test
    fun `nodeToMap for StringConstant includes correct fields`() {
        val map = nodeToMap(strConstNode)
        assertEquals("StringConstant", map["type"])
        assertEquals("hello", map["value"])
        assertEquals("\"hello\"", map["label"])
    }

    @Test
    fun `nodeToMap for EnumConstant includes correct fields`() {
        val map = nodeToMap(enumConstNode)
        assertEquals("EnumConstant", map["type"])
        assertEquals("com.example.Status", map["enumType"])
        assertEquals("ACTIVE", map["enumName"])
    }

    @Test
    fun `nodeToMap for FieldNode includes correct fields`() {
        val map = nodeToMap(fieldNode)
        assertEquals("FieldNode", map["type"])
        assertEquals("com.example.Foo", map["class"])
        assertEquals("name", map["name"])
        assertEquals("java.lang.String", map["fieldType"])
    }

    @Test
    fun `nodeToMap for ParameterNode includes correct fields`() {
        val map = nodeToMap(paramNode)
        assertEquals("ParameterNode", map["type"])
        assertEquals(0, map["index"])
        assertEquals("int", map["paramType"])
        assertNotNull(map["method"])
    }

    @Test
    fun `nodeToMap for ReturnNode includes correct fields`() {
        val map = nodeToMap(returnNode)
        assertEquals("ReturnNode", map["type"])
        assertNotNull(map["method"])
        assertEquals("return", map["label"])
    }

    @Test
    fun `nodeToMap for LocalVariable includes correct fields`() {
        val map = nodeToMap(localNode)
        assertEquals("LocalVariable", map["type"])
        assertEquals("x", map["name"])
        assertEquals("int", map["varType"])
        assertNotNull(map["method"])
        assertEquals("x", map["label"])
    }

    @Test
    fun `nodeToMap for LongConstant includes correct fields`() {
        val node = LongConstant(NodeId.next(), 99L)
        val map = nodeToMap(node)
        assertEquals("LongConstant", map["type"])
        assertEquals(99L, map["value"])
        assertEquals("99L", map["label"])
    }

    @Test
    fun `nodeToMap for FloatConstant includes correct fields`() {
        val node = FloatConstant(NodeId.next(), 1.5f)
        val map = nodeToMap(node)
        assertEquals("FloatConstant", map["type"])
        assertEquals(1.5f, map["value"])
        assertEquals("1.5f", map["label"])
    }

    @Test
    fun `nodeToMap for DoubleConstant includes correct fields`() {
        val node = DoubleConstant(NodeId.next(), 2.5)
        val map = nodeToMap(node)
        assertEquals("DoubleConstant", map["type"])
        assertEquals(2.5, map["value"])
        assertEquals("2.5d", map["label"])
    }

    @Test
    fun `nodeToMap for BooleanConstant includes correct fields`() {
        val node = BooleanConstant(NodeId.next(), false)
        val map = nodeToMap(node)
        assertEquals("BooleanConstant", map["type"])
        assertEquals(false, map["value"])
        assertEquals("false", map["label"])
    }

    @Test
    fun `nodeToMap for NullConstant includes correct fields`() {
        val node = NullConstant(NodeId.next())
        val map = nodeToMap(node)
        assertEquals("NullConstant", map["type"])
        assertEquals("null", map["label"])
    }

    // ========================================================================
    // edgeToMap
    // ========================================================================

    @Test
    fun `edgeToMap for DataFlowEdge includes correct fields`() {
        val edge = DataFlowEdge(paramNode.id, localNode.id, DataFlowKind.ASSIGN)
        val map = edgeToMap(edge)
        assertEquals(paramNode.id.value, map["from"])
        assertEquals(localNode.id.value, map["to"])
        assertEquals("DataFlow", map["type"])
        assertEquals("ASSIGN", map["kind"])
    }

    @Test
    fun `edgeToMap for CallEdge includes correct fields`() {
        val edge = CallEdge(callSiteNode.id, callSiteNode.id, isVirtual = true, isDynamic = true)
        val map = edgeToMap(edge)
        assertEquals(callSiteNode.id.value, map["from"])
        assertEquals(callSiteNode.id.value, map["to"])
        assertEquals("Call", map["type"])
        assertEquals(true, map["virtual"])
        assertEquals(true, map["dynamic"])
    }

    @Test
    fun `edgeToMap for CallEdge with defaults`() {
        val edge = CallEdge(paramNode.id, localNode.id, isVirtual = false)
        val map = edgeToMap(edge)
        assertEquals(false, map["virtual"])
        assertEquals(false, map["dynamic"])
    }

    @Test
    fun `edgeToMap for TypeEdge includes correct fields`() {
        val n1Id = NodeId.next()
        val n2Id = NodeId.next()
        val edge = TypeEdge(n1Id, n2Id, TypeRelation.IMPLEMENTS)
        val map = edgeToMap(edge)
        assertEquals(n1Id.value, map["from"])
        assertEquals(n2Id.value, map["to"])
        assertEquals("Type", map["type"])
        assertEquals("IMPLEMENTS", map["kind"])
    }

    @Test
    fun `edgeToMap for ControlFlowEdge includes correct fields`() {
        val n1Id = NodeId.next()
        val n2Id = NodeId.next()
        val edge = ControlFlowEdge(n1Id, n2Id, ControlFlowKind.BRANCH_TRUE)
        val map = edgeToMap(edge)
        assertEquals(n1Id.value, map["from"])
        assertEquals(n2Id.value, map["to"])
        assertEquals("ControlFlow", map["type"])
        assertEquals("BRANCH_TRUE", map["kind"])
    }
}
