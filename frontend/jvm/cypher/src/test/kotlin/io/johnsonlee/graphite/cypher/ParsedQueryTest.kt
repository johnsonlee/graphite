package io.johnsonlee.graphite.cypher

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsedQueryTest {

    @Test
    fun `parse simple node match`() {
        val q = ParsedQuery.parse("MATCH (n:CallSiteNode) RETURN n.callee_name")
        assertEquals(1, q.nodeBindings.size)
        assertEquals("n", q.nodeBindings["n"]?.variable)
        assertEquals("CallSiteNode", q.nodeBindings["n"]?.label)
        assertNull(q.relationship)
        assertEquals(1, q.returnProjections.size)
        assertEquals("n.callee_name", q.returnProjections[0].expression)
    }

    @Test
    fun `parse node without label`() {
        val q = ParsedQuery.parse("MATCH (n) RETURN n.id")
        assertEquals(1, q.nodeBindings.size)
        assertNull(q.nodeBindings["n"]?.label)
    }

    @Test
    fun `parse LIMIT`() {
        val q = ParsedQuery.parse("MATCH (n:IntConstant) RETURN n.value LIMIT 10")
        assertEquals(10, q.limit)
    }

    @Test
    fun `parse without LIMIT`() {
        val q = ParsedQuery.parse("MATCH (n:IntConstant) RETURN n.value")
        assertNull(q.limit)
    }

    @Test
    fun `parse single-hop relationship`() {
        val q = ParsedQuery.parse("MATCH (a:IntConstant)-[:DATAFLOW]->(b:CallSiteNode) RETURN a.value, b.callee_name")
        assertNotNull(q.relationship)
        assertEquals("a", q.relationship!!.sourceVar)
        assertEquals("b", q.relationship!!.targetVar)
        assertEquals("DATAFLOW", q.relationship!!.type)
        assertFalse(q.relationship!!.variableLength)
    }

    @Test
    fun `parse variable-length relationship`() {
        val q = ParsedQuery.parse("MATCH (a)-[:DATAFLOW*..3]->(b:CallSiteNode) RETURN a.id, b.callee_name")
        assertNotNull(q.relationship)
        assertTrue(q.relationship!!.variableLength)
        assertNull(q.relationship!!.minHops)
        assertEquals(3, q.relationship!!.maxHops)
    }

    @Test
    fun `parse variable-length with min and max`() {
        val q = ParsedQuery.parse("MATCH (a)-[:DATAFLOW*2..5]->(b) RETURN a.id")
        assertNotNull(q.relationship)
        assertTrue(q.relationship!!.variableLength)
        assertEquals(2, q.relationship!!.minHops)
        assertEquals(5, q.relationship!!.maxHops)
    }

    @Test
    fun `parse multiple return projections`() {
        val q = ParsedQuery.parse("MATCH (n:CallSiteNode) RETURN n.callee_name, n.line, n.id")
        assertEquals(3, q.returnProjections.size)
        assertEquals("n.callee_name", q.returnProjections[0].expression)
        assertEquals("n.line", q.returnProjections[1].expression)
        assertEquals("n.id", q.returnProjections[2].expression)
    }

    @Test
    fun `parse return with alias`() {
        val q = ParsedQuery.parse("MATCH (n:IntConstant) RETURN n.value AS val")
        assertEquals(1, q.returnProjections.size)
        assertEquals("n.value", q.returnProjections[0].expression)
        assertEquals("val", q.returnProjections[0].alias)
    }

    @Test
    fun `parse node bindings from relationship pattern`() {
        val q = ParsedQuery.parse("MATCH (a:IntConstant)-[:DATAFLOW]->(b:CallSiteNode) RETURN a.value")
        assertEquals(2, q.nodeBindings.size)
        assertEquals("IntConstant", q.nodeBindings["a"]?.label)
        assertEquals("CallSiteNode", q.nodeBindings["b"]?.label)
    }

    @Test
    fun `parse relationship without type`() {
        val q = ParsedQuery.parse("MATCH (a:IntConstant)-[]->(b:CallSiteNode) RETURN a.value")
        assertNotNull(q.relationship)
        assertNull(q.relationship!!.type)
    }

    @Test
    fun `return just variable name`() {
        val q = ParsedQuery.parse("MATCH (n:IntConstant) RETURN n")
        assertEquals(1, q.returnProjections.size)
        assertEquals("n", q.returnProjections[0].expression)
        assertNull(q.returnProjections[0].alias)
    }
}
