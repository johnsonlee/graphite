package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class CypherLabelSemanticsTest {
    private lateinit var executor: CypherExecutor

    @Before
    fun setUp() {
        NodeId.reset()
        val builder = DefaultGraph.Builder()
        builder.addNode(IntConstant(NodeId.next(), 42))
        executor = CypherExecutor(builder.build())
    }

    @Test
    fun `unknown label has no node candidates`() {
        val result = executor.execute("MATCH (n:MissingLabel) RETURN n")

        assertEquals(listOf("n"), result.columns)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun `unknown additional label rejects an otherwise known node`() {
        val result = executor.execute("MATCH (n:IntConstant:MissingLabel) RETURN n.value")

        assertEquals(listOf("n.value"), result.columns)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun `unknown label count is zero`() {
        val result = executor.execute("MATCH (n:MissingLabel) RETURN count(*) AS count")

        assertEquals(listOf(mapOf("count" to 0L)), result.rows)
    }

    @Test
    fun `unknown label remains empty through ordered limit optimization`() {
        val result = executor.execute(
            "MATCH (n:MissingLabel) RETURN n.value AS value ORDER BY value LIMIT 1"
        )

        assertEquals(listOf("value"), result.columns)
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun `unknown label rejects an element id seek`() {
        val result = executor.execute(
            "MATCH (n:MissingLabel) WHERE elementId(n) = '0' RETURN n"
        )

        assertEquals(emptyList(), result.rows)
    }
}
