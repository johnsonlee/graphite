package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CypherParametersTest {
    private lateinit var graph: Graph
    private lateinit var executor: CypherExecutor

    @Before
    fun setUp() {
        NodeId.reset()
        val builder = DefaultGraph.Builder()
        builder.addNode(IntConstant(NodeId.next(), 7))
        builder.addNode(IntConstant(NodeId.next(), 42))
        graph = builder.build()
        executor = CypherExecutor(graph)
    }

    @Test
    fun `execute binds a public query parameter`() {
        val result = executor.execute("RETURN \$answer AS value", mapOf("answer" to 42))

        assertEquals(listOf("value"), result.columns)
        assertEquals(listOf(mapOf("value" to 42)), result.rows)
    }

    @Test
    fun `parameters participate in filtering`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.value = \$value RETURN n.value AS value",
            mapOf("value" to 7)
        )

        assertEquals(listOf(mapOf("value" to 7)), result.rows)
    }

    @Test
    fun `missing parameters evaluate to null`() {
        val result = executor.execute("RETURN \$missing AS value", emptyMap())

        assertEquals(listOf(mapOf("value" to null)), result.rows)
    }

    @Test
    fun `parameters stay separate from query variables and return star`() {
        val result = executor.execute(
            "WITH 1 AS value RETURN *",
            mapOf("value" to 2, "hidden" to "secret")
        )

        assertEquals(listOf("value"), result.columns)
        assertEquals(mapOf("value" to 1), result.rows.single())
        assertFalse("hidden" in result.rows.single())
    }

    @Test
    fun `parameters remain available after with without colliding with variables`() {
        val result = executor.execute(
            "WITH 1 AS value RETURN value, \$value AS parameter",
            mapOf("value" to 2)
        )

        assertEquals(mapOf("value" to 1, "parameter" to 2), result.rows.single())
    }

    @Test
    fun `an explicit null parameter does not fall back to a same named variable`() {
        val result = executor.execute(
            "WITH 1 AS value RETURN \$value AS parameter",
            mapOf("value" to null)
        )

        assertEquals(null, result.rows.single()["parameter"])
    }

    @Test
    fun `parameters are preserved across union segments`() {
        val result = executor.execute(
            "RETURN \$value AS value UNION ALL RETURN \$value AS value",
            mapOf("value" to "bound")
        )

        assertEquals(listOf("bound", "bound"), result.rows.map { it["value"] })
    }

    @Test
    fun `parameterized maxRows overload applies the result cap`() {
        val result = executor.execute(
            "UNWIND \$values AS value RETURN value",
            mapOf("values" to listOf(1, 2, 3)),
            maxRows = 2
        )

        assertEquals(listOf(1, 2), result.rows.map { it["value"] })
    }

    @Test
    fun `graph query extension accepts parameters`() {
        val result = graph.query("RETURN \$value AS value", mapOf("value" to "extension"))

        assertEquals("extension", result.rows.single()["value"])
    }

    @Test
    fun `cross graph executor accepts parameters`() {
        val crossGraph = CrossGraphCypherExecutor(listOf(CypherGraph("g", graph)))
        val result = crossGraph.execute("RETURN \$value AS value", mapOf("value" to "cross"))

        assertEquals("cross", result.rows.single()["value"])
    }

    @Test
    fun `cross graph parameterized maxRows overload applies the result cap`() {
        val crossGraph = CrossGraphCypherExecutor(listOf(CypherGraph("g", graph)))
        val result = crossGraph.execute(
            "UNWIND \$values AS value RETURN value",
            mapOf("values" to listOf(1, 2)),
            maxRows = 1
        )

        assertEquals(listOf(1), result.rows.map { it["value"] })
    }
}
