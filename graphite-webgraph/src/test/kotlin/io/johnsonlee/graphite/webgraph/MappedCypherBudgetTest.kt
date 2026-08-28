package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.DefaultGraph
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MappedCypherBudgetTest {

    @Test
    fun `mapped raw string miss charges every inspected node`() = withMappedStrings { graph ->
        val matched = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1)).execute(
            "MATCH (n:StringConstant) WHERE n.value CONTAINS 'value-0' RETURN n.value LIMIT 1"
        )
        assertEquals("value-0", matched.rows.single()["n.value"])

        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1)).execute(
                "MATCH (n:StringConstant) WHERE n.value CONTAINS 'raw-never-present' " +
                    "RETURN n.value LIMIT 1"
            )
        }
        assertEquals(0, graph.stringPropertyIndexCount())
    }

    @Test
    fun `mapped first string index build charges every inspected node`() = withMappedStrings { graph ->
        val query = "MATCH (n:StringConstant) WHERE n.value CONTAINS 'build-never-present' " +
            "RETURN n.value LIMIT 1"
        CypherExecutor(graph).execute(query)
        assertEquals(0, graph.stringPropertyIndexCount())

        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1)).execute(query)
        }
        assertEquals(0, graph.stringPropertyIndexCount())
    }

    @Test
    fun `mapped existing string index charges internal candidate scans`() = withMappedStrings { graph ->
        val admissionQuery = "MATCH (n:StringConstant) WHERE n.value CONTAINS 'index-never-present' " +
            "RETURN n.value LIMIT 1"
        val cachedLateMatchQuery = "MATCH (n:StringConstant) WHERE n.value STARTS WITH 'value-299' " +
            "RETURN n.value LIMIT 1"
        val unbudgeted = CypherExecutor(graph)
        unbudgeted.execute(admissionQuery)
        unbudgeted.execute(admissionQuery)
        assertEquals(1, graph.stringPropertyIndexCount())

        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1)).execute(
                "MATCH (n:StringConstant) WHERE n.value STARTS WITH 'other-never-present' " +
                "RETURN n.value LIMIT 1"
            )
        }

        assertEquals("value-299", unbudgeted.execute(cachedLateMatchQuery).rows.single()["n.value"])
        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1)).execute(cachedLateMatchQuery)
        }
    }

    private fun withMappedStrings(block: (MappedWebGraphBackedGraph) -> Unit) {
        val graph = DefaultGraph.Builder().apply {
            repeat(300) { index -> addNode(StringConstant(NodeId(index), "value-$index")) }
        }.build()
        val directory = Files.createTempDirectory("mapped-cypher-budget")
        try {
            GraphStore.save(graph, directory)
            val mapped = GraphStore.loadMapped(directory) as MappedWebGraphBackedGraph
            try {
                block(mapped)
            } finally {
                mapped.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
