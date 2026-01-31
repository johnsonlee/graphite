package io.johnsonlee.graphite.core

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    // ========================================================================
    // NodeId
    // ========================================================================

    @Test
    fun `next returns incrementing IDs`() {
        val id1 = NodeId.next()
        val id2 = NodeId.next()
        val id3 = NodeId.next()
        assertEquals(1, id1.value)
        assertEquals(2, id2.value)
        assertEquals(3, id3.value)
    }

    @Test
    fun `reset resets counter to zero`() {
        NodeId.next()
        NodeId.next()
        NodeId.reset()
        val id = NodeId.next()
        assertEquals(1, id.value)
    }

    @Test
    fun `toString returns node hash format`() {
        val id = NodeId(42)
        assertEquals("node#42", id.toString())
    }

    @Test
    @Suppress("DEPRECATION")
    fun `fromString creates deterministic NodeId`() {
        val id1 = NodeId.fromString("test")
        val id2 = NodeId.fromString("test")
        assertEquals(id1, id2)
    }

    @Test
    fun `value class equality works`() {
        val id1 = NodeId(5)
        val id2 = NodeId(5)
        assertEquals(id1, id2)
    }

    // ========================================================================
    // Constant nodes
    // ========================================================================

    @Test
    fun `IntConstant preserves value`() {
        val c = IntConstant(NodeId.next(), 42)
        assertEquals(42, c.value)
    }

    @Test
    fun `StringConstant preserves value`() {
        val c = StringConstant(NodeId.next(), "hello")
        assertEquals("hello", c.value)
    }

    @Test
    fun `BooleanConstant preserves value`() {
        val t = BooleanConstant(NodeId.next(), true)
        val f = BooleanConstant(NodeId.next(), false)
        assertEquals(true, t.value)
        assertEquals(false, f.value)
    }

    @Test
    fun `LongConstant preserves value`() {
        val c = LongConstant(NodeId.next(), 123456789L)
        assertEquals(123456789L, c.value)
    }

    @Test
    fun `FloatConstant preserves value`() {
        val c = FloatConstant(NodeId.next(), 3.14f)
        assertEquals(3.14f, c.value)
    }

    @Test
    fun `DoubleConstant preserves value`() {
        val c = DoubleConstant(NodeId.next(), 2.718)
        assertEquals(2.718, c.value)
    }

    @Test
    fun `NullConstant has null value`() {
        val c = NullConstant(NodeId.next())
        assertNull(c.value)
    }

    @Test
    fun `EnumConstant value returns first constructor arg`() {
        val c = EnumConstant(
            id = NodeId.next(),
            enumType = TypeDescriptor("com.example.Status"),
            enumName = "ACTIVE",
            constructorArgs = listOf(1, "desc")
        )
        assertEquals(1, c.value)
    }

    @Test
    fun `EnumConstant value returns null when no constructor args`() {
        val c = EnumConstant(
            id = NodeId.next(),
            enumType = TypeDescriptor("com.example.Status"),
            enumName = "INACTIVE"
        )
        assertNull(c.value)
    }

    @Test
    fun `data class equality for IntConstant`() {
        val id = NodeId.next()
        val c1 = IntConstant(id, 42)
        val c2 = IntConstant(id, 42)
        assertEquals(c1, c2)
    }

    @Test
    fun `data class inequality for different values`() {
        val c1 = IntConstant(NodeId.next(), 1)
        val c2 = IntConstant(NodeId.next(), 2)
        assertNotEquals(c1, c2)
    }

    // ========================================================================
    // EnumValueReference
    // ========================================================================

    @Test
    fun `EnumValueReference toString format`() {
        val ref = EnumValueReference("com.example.Priority", "HIGH")
        assertEquals("com.example.Priority.HIGH", ref.toString())
    }

    // ========================================================================
    // TypeDescriptor
    // ========================================================================

    @Test
    fun `simpleName extracts class name from package`() {
        val td = TypeDescriptor("com.example.MyClass")
        assertEquals("MyClass", td.simpleName)
    }

    @Test
    fun `simpleName for class without package`() {
        val td = TypeDescriptor("MyClass")
        assertEquals("MyClass", td.simpleName)
    }

    @Test
    fun `isSubtypeOf returns true for same class`() {
        val td = TypeDescriptor("com.example.Foo")
        assertTrue(td.isSubtypeOf(TypeDescriptor("com.example.Foo")))
    }

    @Test
    fun `typeArguments defaults to empty list`() {
        val td = TypeDescriptor("java.util.List")
        assertTrue(td.typeArguments.isEmpty())
    }

    @Test
    fun `typeArguments preserves values`() {
        val td = TypeDescriptor("java.util.List", listOf(TypeDescriptor("java.lang.String")))
        assertEquals(1, td.typeArguments.size)
        assertEquals("String", td.typeArguments[0].simpleName)
    }

    // ========================================================================
    // MethodDescriptor
    // ========================================================================

    @Test
    fun `signature generation with params`() {
        val md = MethodDescriptor(
            declaringClass = TypeDescriptor("com.example.Foo"),
            name = "bar",
            parameterTypes = listOf(TypeDescriptor("int"), TypeDescriptor("java.lang.String")),
            returnType = TypeDescriptor("void")
        )
        assertEquals("com.example.Foo.bar(int,java.lang.String)", md.signature)
    }

    @Test
    fun `signature generation without params`() {
        val md = MethodDescriptor(
            declaringClass = TypeDescriptor("com.example.Foo"),
            name = "baz",
            parameterTypes = emptyList(),
            returnType = TypeDescriptor("void")
        )
        assertEquals("com.example.Foo.baz()", md.signature)
    }

    // ========================================================================
    // FieldDescriptor
    // ========================================================================

    @Test
    fun `FieldDescriptor construction`() {
        val fd = FieldDescriptor(
            declaringClass = TypeDescriptor("com.example.Foo"),
            name = "bar",
            type = TypeDescriptor("int")
        )
        assertEquals("bar", fd.name)
        assertEquals("int", fd.type.className)
    }

    // ========================================================================
    // ValueNode subtypes
    // ========================================================================

    @Test
    fun `LocalVariable construction`() {
        val md = MethodDescriptor(TypeDescriptor("Foo"), "m", emptyList(), TypeDescriptor("void"))
        val lv = LocalVariable(NodeId.next(), "x", TypeDescriptor("int"), md)
        assertEquals("x", lv.name)
        assertEquals("int", lv.type.className)
    }

    @Test
    fun `FieldNode construction`() {
        val fd = FieldDescriptor(TypeDescriptor("Foo"), "f", TypeDescriptor("int"))
        val fn = FieldNode(NodeId.next(), fd, isStatic = true)
        assertTrue(fn.isStatic)
        assertEquals("f", fn.descriptor.name)
    }

    @Test
    fun `ParameterNode construction`() {
        val md = MethodDescriptor(TypeDescriptor("Foo"), "m", emptyList(), TypeDescriptor("void"))
        val pn = ParameterNode(NodeId.next(), 0, TypeDescriptor("int"), md)
        assertEquals(0, pn.index)
    }

    @Test
    fun `ReturnNode construction with actual type`() {
        val md = MethodDescriptor(TypeDescriptor("Foo"), "m", emptyList(), TypeDescriptor("java.lang.Object"))
        val rn = ReturnNode(NodeId.next(), md, actualType = TypeDescriptor("com.example.User"))
        assertEquals("User", rn.actualType?.simpleName)
    }

    @Test
    fun `ReturnNode construction without actual type`() {
        val md = MethodDescriptor(TypeDescriptor("Foo"), "m", emptyList(), TypeDescriptor("void"))
        val rn = ReturnNode(NodeId.next(), md)
        assertNull(rn.actualType)
    }

    @Test
    fun `CallSiteNode construction`() {
        val caller = MethodDescriptor(TypeDescriptor("Foo"), "main", emptyList(), TypeDescriptor("void"))
        val callee = MethodDescriptor(TypeDescriptor("Bar"), "doWork", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
        val argId = NodeId.next()
        val cs = CallSiteNode(
            id = NodeId.next(),
            caller = caller,
            callee = callee,
            lineNumber = 42,
            receiver = null,
            arguments = listOf(argId)
        )
        assertEquals(42, cs.lineNumber)
        assertEquals(1, cs.arguments.size)
        assertNull(cs.receiver)
    }
}
