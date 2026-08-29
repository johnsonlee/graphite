package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MethodQueryPersistenceTest {

    @Test
    fun `persisted and mapped graphs expose indexed-only methods through Cypher`() {
        val method = MethodDescriptor(
            TypeDescriptor("com.example.IndexedOnly"),
            "load",
            listOf(TypeDescriptor("java.lang.String")),
            TypeDescriptor("java.lang.Object")
        )
        val graph = DefaultGraph.Builder().apply { addMethod(method) }.build()
        val directory = Files.createTempDirectory("method-query-persistence")
        try {
            GraphStore.save(graph, directory)
            listOf<() -> Graph>(
                { GraphStore.load(directory) },
                { GraphStore.loadMapped(directory) }
            ).forEach { load ->
                val loaded = load()
                try {
                    val result = CypherExecutor(loaded).execute(
                        "MATCH (m:Method) WHERE m.signature = '${method.signature}' " +
                            "RETURN m.signature, m.class, m.name, m.parameter_types, m.return_type LIMIT 1"
                    )

                    assertEquals(method.signature, result.rows.single()["m.signature"])
                    assertEquals("com.example.IndexedOnly", result.rows.single()["m.class"])
                    assertEquals("load", result.rows.single()["m.name"])
                    assertEquals(listOf("java.lang.String"), result.rows.single()["m.parameter_types"])
                    assertEquals("java.lang.Object", result.rows.single()["m.return_type"])
                } finally {
                    (loaded as? AutoCloseable)?.close()
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `large mapped method scans honor work budgets and cancellation`() {
        val owner = TypeDescriptor("com.example.Generated")
        val returnType = TypeDescriptor("void")
        val builder = DefaultGraph.Builder()
        repeat(LARGE_METHOD_COUNT) { index ->
            builder.addMethod(MethodDescriptor(owner, "method$index", emptyList(), returnType))
        }
        val directory = Files.createTempDirectory("large-method-query-persistence")
        try {
            GraphStore.save(builder.build(), directory)
            val graph = GraphStore.loadMapped(directory)
            try {
                assertFailsWith<CypherBudgetExceededException> {
                    CypherExecutor(
                        graph,
                        CypherExecutionBudget(maxWorkUnits = LARGE_METHOD_COUNT.toLong() - 1)
                    ).execute(MISSING_METHOD_QUERY)
                }

                val late = CypherExecutor(
                    graph,
                    CypherExecutionBudget(maxWorkUnits = LARGE_METHOD_COUNT.toLong())
                ).execute(
                    "MATCH (m:Method) WHERE m.name = 'method${LARGE_METHOD_COUNT - 1}' " +
                        "RETURN m.signature LIMIT 1"
                )
                assertEquals(
                    "com.example.Generated.method${LARGE_METHOD_COUNT - 1}()",
                    late.rows.single()["m.signature"]
                )

                var cancellationChecks = 0
                lateinit var cancellation: CypherCancellationSignal
                cancellation = CypherCancellationSignal {
                    cancellationChecks++
                    if (cancellationChecks == CANCELLATION_CHECK_LIMIT) cancellation.cancel()
                }
                assertFailsWith<CypherQueryCancelledException> {
                    CypherExecutor(
                        graph,
                        CypherExecutionContext(CypherExecutionBudget(Long.MAX_VALUE), cancellation)
                    ).execute(MISSING_METHOD_QUERY)
                }
                assertTrue(cancellationChecks <= CANCELLATION_CHECK_LIMIT)
            } finally {
                (graph as? AutoCloseable)?.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val LARGE_METHOD_COUNT = 300_001
        const val CANCELLATION_CHECK_LIMIT = 1_024
        const val MISSING_METHOD_QUERY =
            "MATCH (m:Method) WHERE m.name = 'neverPresent' RETURN m.signature LIMIT 1"
    }
}
