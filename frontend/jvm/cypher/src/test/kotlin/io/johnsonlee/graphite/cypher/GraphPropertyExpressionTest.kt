package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallEdge
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.ControlFlowKind
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeEdge
import io.johnsonlee.graphite.core.TypeRelation
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Test

class GraphPropertyExpressionTest : ExpressionEvaluatorTestFixture() {

    @Test
    fun `function call - toLower`() {
        val expr = CypherExpr.FunctionCall("toLower", listOf(lit("HELLO")))
        assertEquals("hello", eval(expr))
    }

    @Test
    fun `function call - toUpper`() {
        val expr = CypherExpr.FunctionCall("toUpper", listOf(lit("hello")))
        assertEquals("HELLO", eval(expr))
    }

    @Test
    fun `function call - size`() {
        val expr = CypherExpr.FunctionCall("size", listOf(lit("hello")))
        assertEquals(5, eval(expr))
    }

    @Test
    fun `CountStar throws CypherAggregationException`() {
        assertFailsWith<CypherAggregationException> { eval(CypherExpr.CountStar) }
    }

    @Test
    fun `nested expression - (1 + 2) x 3 = 9`() {
        val inner = CypherExpr.BinaryOp("+", lit(1), lit(2))
        val expr = CypherExpr.BinaryOp("*", inner, lit(3))
        val result = eval(expr)
        assertEquals(9, (result as Number).toInt())
    }

    @Test
    fun `division by zero throws`() {
        val expr = CypherExpr.BinaryOp("/", lit(10), lit(0))
        assertFailsWith<CypherException> { eval(expr) }
    }

    @Test
    fun `edge property access - DataFlowEdge kind`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = DataFlowEdge(fromId, toId, DataFlowKind.ASSIGN)
        val expr = CypherExpr.Property(variable("e"), "kind")
        assertEquals("ASSIGN", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - CallEdge virtual`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = CallEdge(fromId, toId, isVirtual = true, isDynamic = false)
        val expr = CypherExpr.Property(variable("e"), "virtual")
        assertEquals(true, eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - type`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = DataFlowEdge(fromId, toId, DataFlowKind.ASSIGN)
        val expr = CypherExpr.Property(variable("e"), "type")
        assertEquals("DATAFLOW", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `property access on Path returns length`() {
        NodeId.reset()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val edge = DataFlowEdge(n1.id, n2.id, DataFlowKind.ASSIGN)
        val path = PathFinder.Path(listOf(n1, n2), listOf(edge))
        val expr = CypherExpr.Property(variable("p"), "length")
        assertEquals(1, eval(expr, mapOf("p" to path)))
    }

    @Test
    fun `property access on Path unknown property returns null`() {
        NodeId.reset()
        val n1 = IntConstant(NodeId.next(), 1)
        val path = PathFinder.Path(listOf(n1), emptyList())
        val expr = CypherExpr.Property(variable("p"), "unknown")
        assertNull(eval(expr, mapOf("p" to path)))
    }

    @Test
    fun `property access on non-node non-map non-edge returns null`() {
        val expr = CypherExpr.Property(variable("x"), "prop")
        assertNull(eval(expr, mapOf("x" to 42)))
    }

    @Test
    fun `edge property access - CallEdge dynamic`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = CallEdge(fromId, toId, isVirtual = false, isDynamic = true)
        val expr = CypherExpr.Property(variable("e"), "dynamic")
        assertEquals(true, eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - CallEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = CallEdge(fromId, toId, isVirtual = false, isDynamic = false)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - TypeEdge kind`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = TypeEdge(fromId, toId, TypeRelation.EXTENDS)
        val expr = CypherExpr.Property(variable("e"), "kind")
        assertEquals("EXTENDS", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - TypeEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = TypeEdge(fromId, toId, TypeRelation.EXTENDS)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - ControlFlowEdge kind`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = ControlFlowEdge(fromId, toId, ControlFlowKind.SEQUENTIAL)
        val expr = CypherExpr.Property(variable("e"), "kind")
        assertEquals("SEQUENTIAL", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - ControlFlowEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = ControlFlowEdge(fromId, toId, ControlFlowKind.SEQUENTIAL)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - DataFlowEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = DataFlowEdge(fromId, toId, DataFlowKind.ASSIGN)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }
}
