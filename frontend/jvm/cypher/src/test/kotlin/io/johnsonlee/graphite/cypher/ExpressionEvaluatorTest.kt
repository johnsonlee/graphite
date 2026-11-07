package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ExpressionEvaluatorTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `literal int`() {
        assertEquals(42, eval(lit(42)))
    }

    @Test
    fun `literal float`() {
        assertEquals(3.14, eval(lit(3.14)))
    }

    @Test
    fun `literal string`() {
        assertEquals("hello", eval(lit("hello")))
    }

    @Test
    fun `literal boolean`() {
        assertEquals(true, eval(lit(true)))
        assertEquals(false, eval(lit(false)))
    }

    @Test
    fun `literal null`() {
        assertNull(eval(lit(null)))
    }

    @Test
    fun `bound variable`() {
        assertEquals(99, eval(variable("x"), mapOf("x" to 99)))
    }

    @Test
    fun `unbound variable returns null`() {
        assertNull(eval(variable("missing"), emptyMap()))
    }

    @Test
    fun `property access on IntConstant`() {
        NodeId.reset()
        val nodeId = NodeId.next()
        val node = IntConstant(nodeId, 42)
        val expr = CypherExpr.Property(variable("n"), "value")
        assertEquals(42, eval(expr, mapOf("n" to node)))
    }

    @Test
    fun `property access on CallSiteNode`() {
        NodeId.reset()
        val nodeId = NodeId.next()
        val type = TypeDescriptor("com.example.Service")
        val intType = TypeDescriptor("int")
        val stringType = TypeDescriptor("java.lang.String")
        val caller = MethodDescriptor(type, "process", listOf(intType), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(nodeId, caller, callee, 10, null, emptyList())
        val expr = CypherExpr.Property(variable("n"), "callee_name")
        assertEquals("save", eval(expr, mapOf("n" to node)))
    }

    @Test
    fun `property access on map`() {
        val expr = CypherExpr.Property(variable("m"), "key")
        assertEquals("val", eval(expr, mapOf("m" to mapOf("key" to "val"))))
    }

    @Test
    fun `property access on null returns null`() {
        val expr = CypherExpr.Property(variable("m"), "key")
        assertNull(eval(expr, mapOf("m" to null)))
    }
}
