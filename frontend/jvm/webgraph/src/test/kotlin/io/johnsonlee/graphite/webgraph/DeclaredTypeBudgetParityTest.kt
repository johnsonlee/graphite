package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.MemberTypeKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** A correctness gate: adding unrelated type metadata must not exhaust an existing query budget. */
class DeclaredTypeBudgetParityTest {
    @Test
    fun `unrelated declarations preserve the budget of an existing dynamic miss`() {
        val root = Files.createTempDirectory("declared-budget-parity")
        try {
            val owner = TypeDescriptor("sample.Owner")
            val erased = TypeDescriptor("java.lang.Object")
            val table = DeclaredTypeTable(
                List(1_000) { DeclaredType("class", "sample.Unrelated$it") },
                mapOf(MemberTypeKey(owner.className, "value", "Ljava/lang/Object;") to 0),
                emptyMap(), emptyMap()
            )
            val query = "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'MissingNeedle') " +
                "RETURN n.id AS id LIMIT 1"
            for ((label, metadata) in listOf("legacy" to DeclaredTypeTable.EMPTY, "declared" to table)) {
                val graph = DefaultGraph.Builder().apply {
                    addNode(FieldNode(NodeId(0), FieldDescriptor(owner, "value", erased), false))
                    setDeclaredTypes(metadata)
                }.build()
                val directory = root.resolve(label)
                GraphStore.save(graph, directory)
                val mapped = GraphStore.loadMapped(directory)
                try {
                    val rows = CypherExecutor(mapped, CypherExecutionBudget(100)).execute(query).rows
                    assertEquals(emptyList(), rows, label)
                    val fullScan = CypherExecutor(mapped, CypherExecutionBudget(100))
                        .execute(query.removeSuffix(" LIMIT 1")).rows
                    assertEquals(emptyList(), fullScan, "$label without LIMIT")
                } finally {
                    (mapped as? AutoCloseable)?.close()
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
