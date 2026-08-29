package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
