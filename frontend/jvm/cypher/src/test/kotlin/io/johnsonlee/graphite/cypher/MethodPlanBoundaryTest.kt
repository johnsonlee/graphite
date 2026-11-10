package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MethodPlanBoundaryTest {
    @Test
    fun `method count merging rejects overflow without corrupting existing count or provenance`() {
        val key = listOf<Any?>("group")
        val groups = linkedMapOf(key to MethodCountGroup(Long.MAX_VALUE - 1, linkedSetOf("a")))
        assertTrue(mergeMethodCountGroups(groups, linkedMapOf(key to MethodCountGroup(1, linkedSetOf("b")))))
        assertEquals(MethodCountGroup(Long.MAX_VALUE, linkedSetOf("a", "b")), groups[key])
        assertFalse(mergeMethodCountGroups(groups, linkedMapOf(key to MethodCountGroup(1, linkedSetOf("c")))))
        assertEquals(MethodCountGroup(Long.MAX_VALUE, linkedSetOf("a", "b")), groups[key])
        assertFalse(mergeMethodCountGroups(groups, null))
    }

    @Test
    fun `method graph property is routed and reused binding obeys inline constraints`() {
        val method = MethodDescriptor(TypeDescriptor("Owner"), "read", emptyList(), TypeDescriptor("int"))
        val graph = DefaultGraph.Builder().addMethod(method).build()
        val clauses = CypherDslAdapter.parse("MATCH (m:Method {graphId: 'a'}) RETURN m.name AS name")
        assertEquals("a", assertNotNull(compileMethodQuery(clauses)).scan.sourceGraphId)
        assertEquals(listOf(mapOf("name" to "read")), CypherExecutor(graph).execute(
            "MATCH (m:Method) WITH m MATCH (m:Method {name: 'read'}) RETURN m.name AS name").rows)
    }

    @Test
    fun `unsupported string operator remains a residual method predicate`() {
        val node = PatternElement.NodePattern("m", listOf("Method"))
        val expression = CypherExpr.StringOp("LIKE", CypherExpr.Property(CypherExpr.Variable("m"), "name"), CypherExpr.Literal("read"))
        assertFalse(assertNotNull(methodScanPlan(node, "m", CypherClause.Where(expression))).fullyPushed)
    }
}
