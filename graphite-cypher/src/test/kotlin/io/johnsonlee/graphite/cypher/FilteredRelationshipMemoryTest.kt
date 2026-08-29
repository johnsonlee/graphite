package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.junit.Test
import kotlin.test.assertTrue

class FilteredRelationshipMemoryTest {

    @Test
    fun `zero-hit filtered relationship scan does not retain intermediate rows`() {
        val source = IntConstant(NodeId(1), 1)
        val target = IntConstant(NodeId(2), 2)
        val base = DefaultGraph.Builder()
            .addNode(source)
            .addNode(target)
            .build()
        val virtualGraph = object : Graph by base {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Edge> outgoing(id: NodeId, type: Class<T>): Sequence<T> =
                if (id == source.id && type == DataFlowEdge::class.java) {
                    generateSequence { DataFlowEdge(source.id, target.id, DataFlowKind.ASSIGN) }
                        .take(EDGE_COUNT) as Sequence<T>
                } else {
                    emptySequence()
                }
        }

        val result = CypherExecutor(virtualGraph).execute(
            "MATCH (a:IntConstant)-[r:DATA_FLOW]->(b:IntConstant) " +
                "WHERE b.value = -1 RETURN r LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
    }

    private companion object {
        const val EDGE_COUNT = 1_000_000
    }
}
