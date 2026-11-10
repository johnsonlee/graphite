package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.StringMatchMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CypherValueBoundaryTest {
    @Test
    fun `opaque parameter values retain text ordering and equal map ordering`() {
        val first = object { override fun toString() = "alpha" }
        val last = object { override fun toString() = "omega" }
        assertTrue(compareOrderValues(first, last, true) < 0)
        assertTrue(compareOrderValues(first, last, false) > 0)
        assertTrue(compareExpressionValues(first, last) < 0)
        assertEquals(0, compareOrderValues(mapOf("x" to listOf(1, null)), mapOf("x" to listOf(1, null)), true))
        assertNull(CypherFunctions.toInteger("bad"))
        assertEquals(12L, CypherFunctions.toInteger("12"))
        assertEquals(1.5, CypherFunctions.toFloat("1.5"))
        assertEquals(false, CypherFunctions.toBoolean("false"))
        val nullKey = cypherValueKey(null)
        assertEquals("null group", mapOf(nullKey to "null group")[cypherValueKey(null)])
    }

    @Test
    fun `method deduplication uses graph and descriptor identity rather than graph object`() {
        val method = MethodDescriptor(TypeDescriptor("Owner"), "read", emptyList(), TypeDescriptor("int"))
        val a = MethodValue("a", method, DefaultGraph.Builder().build())
        val same = MethodValue("a", method.copy(), DefaultGraph.Builder().build())
        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertEquals(1, setOf(a, same).size)
        assertFalse(a == MethodValue("b", method))
        assertFalse(a.equals(method))
        assertEquals(MethodValue(null, method).hashCode(), MethodValue(null, method.copy()).hashCode())
    }

    @Test
    fun `generic projection keeps aliases nulls graph identity and provenance`() {
        val node = StringConstant(NodeId(7), "hello")
        val graph = DefaultGraph.Builder().addNode(node).build()
        val projected = projectedNodeRow(CypherGraph("scope", graph), node,
            listOf("value", "graphId", "missing"), listOf("text", "source", "absent"))
        assertEquals(linkedMapOf("text" to "hello", "source" to "scope", "absent" to null,
            INTERNAL_PROVENANCE_KEY to setOf("scope")), projected)
        val context = PipelineExecutionContext(graph, false)
        assertNull(context.stringValue(CypherExpr.Variable("unbound")))
        val access = PipelineGraphAccess(listOf(CypherGraph("scope", graph)), false, context)
        assertNull(access.nodeCursor(12))
        assertNull(access.nodeProperty(12, "id"))
    }

    @Test
    fun `literal string plans reject dynamic operands and choose necessary selective predicates`() {
        val property = CypherExpr.Property(CypherExpr.Variable("n"), "value")
        assertNull(DirectStringFilter.compile(CypherExpr.StringOp("unsupported", property, CypherExpr.Literal("x")), "n"))
        assertNull(DirectStringFilter.compile(CypherExpr.StringOp("CONTAINS", property, CypherExpr.Variable("dynamic")), "n"))
        assertNull(DirectStringFilter.compile(
            CypherExpr.StringOp("CONTAINS", CypherExpr.Literal("constant"), CypherExpr.Literal("x")), "n"))
        val lowerLiteral = CypherExpr.FunctionCall("toLower", listOf(CypherExpr.Literal("constant")))
        assertNull(DirectStringFilter.compile(CypherExpr.StringOp("CONTAINS", lowerLiteral, CypherExpr.Literal("x")), "n"))
        val contains = CypherExpr.StringOp("CONTAINS", property, CypherExpr.Literal("xyz"))
        val exact = CypherExpr.Comparison("=", property, CypherExpr.Literal("xyz"))
        assertNotNull(DirectStringDisjunction.compile(CypherExpr.Or(contains, exact), "n"))
        assertNotNull(DirectStringConjunction.compile(CypherExpr.And(contains, exact), "n"))
        for ((op, expectedMode) in listOf("STARTS WITH" to StringMatchMode.STARTS_WITH,
            "ENDS WITH" to StringMatchMode.ENDS_WITH, "=" to StringMatchMode.EQUALS)) {
            val selected = if (op == "=") exact else CypherExpr.StringOp(op, property, CypherExpr.Literal("xyz"))
            val plan = assertNotNull(DirectStringCandidatePlan.compile(CypherExpr.And(selected, contains), "n", emptyMap()))
            assertEquals(expectedMode, plan.candidates.filters.single().mode)
            assertTrue(plan.candidates.matches(StringConstant(NodeId(1), "xyz"), DefaultGraph.Builder().build()))
        }
    }
}
