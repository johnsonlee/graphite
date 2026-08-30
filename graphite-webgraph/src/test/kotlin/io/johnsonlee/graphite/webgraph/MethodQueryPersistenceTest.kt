package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `general mapped Method queries stream without initializing full metadata`() {
        val builder = DefaultGraph.Builder()
        builder.addMethod(
            MethodDescriptor(
                TypeDescriptor("com.example.Alpha"),
                "alpha",
                listOf(TypeDescriptor("java.lang.String")),
                TypeDescriptor("void")
            )
        )
        builder.addMethod(
            MethodDescriptor(
                TypeDescriptor("com.example.Beta"),
                "beta",
                listOf(TypeDescriptor("int"), TypeDescriptor("long")),
                TypeDescriptor("int")
            )
        )
        val directory = Files.createTempDirectory("general-method-query-persistence")
        try {
            GraphStore.save(builder.build(), directory)
            val graph = GraphStore.loadMapped(directory) as MappedWebGraphBackedGraph
            try {
                assertFalse(graph.isMetadataInitialized())
                assertEquals(1, graph.methods(MethodPattern()).take(1).count())
                assertFalse(graph.isMetadataInitialized())

                val count = CypherExecutor(
                    graph,
                    CypherExecutionBudget(maxWorkUnits = 1)
                ).execute("MATCH (m:Method) RETURN count(m) AS total")
                assertEquals(2L, count.rows.single()["total"])
                assertFalse(graph.isMetadataInitialized())

                val distinctOrdered = CypherExecutor(graph).execute(
                    "MATCH (m:Method) RETURN DISTINCT m.class AS owner ORDER BY owner DESC"
                )
                assertEquals(
                    listOf("com.example.Beta", "com.example.Alpha"),
                    distinctOrdered.rows.map { it["owner"] }
                )
                assertFalse(graph.isMetadataInitialized())

                val withUnwind = CypherExecutor(graph).execute(
                    "MATCH (m:Method) WITH m UNWIND m.parameter_types AS parameter " +
                        "RETURN parameter ORDER BY parameter"
                )
                assertEquals(listOf("int", "java.lang.String", "long"), withUnwind.rows.map { it["parameter"] })
                assertFalse(graph.isMetadataInitialized())
            } finally {
                graph.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `large mapped method scans ignore graph work budgets but honor cancellation`() {
        val owner = TypeDescriptor("com.example.Generated")
        val returnType = TypeDescriptor("void")
        val builder = DefaultGraph.Builder()
        repeat(LARGE_METHOD_COUNT) { index ->
            builder.addMethod(MethodDescriptor(owner, "method$index", emptyList(), returnType))
        }
        val directory = Files.createTempDirectory("large-method-query-persistence")
        try {
            GraphStore.save(builder.build(), directory)
            val graph = GraphStore.loadMapped(directory) as MappedWebGraphBackedGraph
            try {
                val missing = CypherExecutor(
                    graph,
                    CypherExecutionBudget(maxWorkUnits = 1)
                ).execute(MISSING_METHOD_QUERY)
                assertTrue(missing.rows.isEmpty())
                assertFalse(graph.isMetadataInitialized())

                val late = CypherExecutor(
                    graph,
                    CypherExecutionBudget(maxWorkUnits = 1)
                ).execute(
                    "MATCH (m:Method) WHERE m.name = 'method${LARGE_METHOD_COUNT - 1}' " +
                        "RETURN m.signature LIMIT 1"
                )
                assertEquals(
                    "com.example.Generated.method${LARGE_METHOD_COUNT - 1}()",
                    late.rows.single()["m.signature"]
                )
                assertFalse(graph.isMetadataInitialized())

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
                    ).execute(GENERAL_METHOD_QUERY)
                }
                assertTrue(cancellationChecks <= CANCELLATION_CHECK_LIMIT)
                assertFalse(graph.isMetadataInitialized())
            } finally {
                graph.close()
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
        const val GENERAL_METHOD_QUERY =
            "MATCH (m:Method) RETURN DISTINCT m.name AS name ORDER BY name"
    }
}
