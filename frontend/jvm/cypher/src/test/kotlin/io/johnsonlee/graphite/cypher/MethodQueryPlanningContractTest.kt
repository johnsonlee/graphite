package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MethodQueryPlanningContractTest {
    @Test
    fun `zero row request precedes order validation and scanning`() {
        var checkpoints = 0
        val graph = object : Graph by DefaultGraph.Builder().build() {
            override fun methodCount(): Long = error("Zero row request must not inspect methods")
        }
        val result = MethodQueryExecutor.tryExecute(
            CypherDslAdapter.parse("MATCH (m:Method) RETURN DISTINCT m.name AS name ORDER BY m.name LIMIT 0"),
            listOf(CypherGraph("one", graph)),
            qualified = false,
            checkCancelled = { checkpoints++ },
            workTracker = null
        )

        assertNotNull(result)
        assertEquals(listOf("name"), result.columns)
        assertEquals(emptyList(), result.rows)
        assertEquals(0, checkpoints)
    }

    @Test
    fun `zero aggregate request still checks count support and source cancellation`() {
        var reads = 0
        var checkpoints = 0
        val graph = object : Graph by DefaultGraph.Builder().build() {
            override fun methodCount(): Long {
                reads++
                return 2L
            }
        }
        val sources = listOf(CypherGraph("one", graph))
        fun execute(query: String): CypherResult? = MethodQueryExecutor.tryExecute(
            CypherDslAdapter.parse(query), sources, false, { checkpoints++ }, null
        )

        val result = execute("MATCH (m:Method) RETURN count(*) AS total LIMIT 0")
        assertNotNull(result)
        assertEquals(listOf("total"), result.columns)
        assertEquals(emptyList(), result.rows)
        assertEquals(1, reads)
        assertEquals(1, checkpoints)
        assertNull(execute("MATCH (m:Method) RETURN count(*) AS total ORDER BY total LIMIT 0"))
        assertNull(execute("MATCH (m:Method) RETURN count(DISTINCT m) AS total LIMIT 0"))
        assertEquals(1, reads)
        assertEquals(1, checkpoints)
    }

    @Test
    fun `cross graph count overflow declines fast path without wrapping`() {
        val sources = listOf(Long.MAX_VALUE, 1L).mapIndexed { index, count ->
            val graph = object : Graph by DefaultGraph.Builder().build() {
                override fun methodCount(): Long = count
            }
            CypherGraph("graph-$index", graph)
        }

        assertNull(MethodQueryExecutor.tryExecute(
            CypherDslAdapter.parse("MATCH (m:Method) RETURN count(*) AS total"),
            sources, true, {}, null
        ))
    }

    @Test
    fun `bounded grouped count finishes retained keys across sources before pagination`() {
        val sources = listOf("first", "second").map { id ->
            val graph = DefaultGraph.Builder().apply {
                listOf("alpha", "beta", "gamma").forEach { name ->
                    addMethod(MethodDescriptor(TypeDescriptor("example.Owner"), name, emptyList(), TypeDescriptor("void")))
                }
            }.build()
            CypherGraph(id, graph)
        }
        val result = MethodQueryExecutor.tryExecute(
            CypherDslAdapter.parse("MATCH (m:Method) RETURN m.name AS name, count(*) AS total SKIP 1 LIMIT 1"),
            sources, true, {}, null
        )

        assertNotNull(result)
        assertEquals(listOf("name", "total"), result.columns)
        assertEquals(listOf(mapOf(
            "name" to "beta", "total" to 2L, INTERNAL_PROVENANCE_KEY to linkedSetOf("first", "second")
        )), result.rows)
    }
}
