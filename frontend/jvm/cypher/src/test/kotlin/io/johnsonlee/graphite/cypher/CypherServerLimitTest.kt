package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class CypherServerLimitTest : CypherExecutorTestFixture() {

    @Test
    fun `execution budget is shared by union segments`() {
        val budgeted = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 3))

        assertFailsWith<CypherBudgetExceededException> {
            budgeted.execute("MATCH (n:IntConstant) RETURN n.value AS value " + "UNION ALL MATCH (n:CallSiteNode) RETURN n.line AS value")
        }
    }

    @Test
    fun `execution budget does not charge metadata label histogram`() {
        val budgeted = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1))

        val result =
            budgeted.execute(
                """
            MATCH (n)
            UNWIND labels(n) AS label
            RETURN label, count(*) AS c
            ORDER BY c DESC
            LIMIT 50
            """
                    .trimIndent())

        assertEquals(9L, result.rows.first { it["label"] == "Constant" }["c"])
    }

    @Test
    fun `execute with maxRows caps expression limit`() {
        var consumed = 0
        val counting =
            object : Graph by graph {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = graph.nodes(type).onEach { consumed++ }
            }

        val result = CypherExecutor(counting).execute("MATCH (n:IntConstant) RETURN n.id LIMIT 50 + 50", maxRows = 1)

        assertEquals(1, result.rows.size)
        assertEquals(1, consumed)
    }

    @Test
    fun `execute with maxRows applies to union queries`() {
        val result =
            executor.execute(
                "MATCH (n:IntConstant) RETURN n.value AS v UNION ALL MATCH (m:StringConstant) RETURN m.value AS v", maxRows = 1)
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `execute with maxRows stops union all before later branches`() {
        val visitedTypes = mutableListOf<Class<out Node>>()
        val guarded =
            object : Graph by graph {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> {
                    visitedTypes.add(type)
                    check(type != StringConstant::class.java) { "Later UNION ALL branch must not execute" }
                    return graph.nodes(type)
                }
            }

        val result =
            CypherExecutor(guarded)
                .execute(
                    "MATCH (n:IntConstant) RETURN n.value AS v UNION ALL " + "MATCH (m:StringConstant) RETURN m.value AS v", maxRows = 1)

        assertEquals(listOf(7), result.rows.map { it["v"] })
        assertEquals(listOf<Class<out Node>>(IntConstant::class.java), visitedTypes)
    }

    @Test
    fun `execute with maxRows pushes remaining budget through expression limit in union`() {
        var consumedIntConstants = 0
        val counting =
            object : Graph by graph {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    graph.nodes(type).onEach { if (type == IntConstant::class.java) consumedIntConstants++ }
            }

        val result =
            CypherExecutor(counting)
                .execute(
                    "MATCH (n:StringConstant) WHERE n.value = 'missing' RETURN n.value AS v UNION ALL " +
                        "MATCH (m:IntConstant) RETURN m.value AS v LIMIT 50 + 50",
                    maxRows = 1)

        assertEquals(listOf(7), result.rows.map { it["v"] })
        assertEquals(1, consumedIntConstants)
    }

    @Test
    fun `execute with zero maxRows preserves union columns`() {
        val result =
            executor.execute(
                "MATCH (n:IntConstant) RETURN n.value AS v UNION ALL " + "MATCH (m:StringConstant) RETURN m.value AS v", maxRows = 0)

        assertEquals(listOf("v"), result.columns)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `execute with zero maxRows preserves distinct union columns`() {
        val result =
            executor.execute(
                "MATCH (n:IntConstant) RETURN n.value AS v UNION " + "MATCH (m:StringConstant) RETURN m.value AS v", maxRows = 0)

        assertEquals(listOf("v"), result.columns)
        assertTrue(result.rows.isEmpty())
    }
}
