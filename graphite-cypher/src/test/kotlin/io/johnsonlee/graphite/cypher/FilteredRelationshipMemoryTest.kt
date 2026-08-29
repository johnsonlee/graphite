package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilteredRelationshipMemoryTest {

    @Test
    fun `zero-hit ordered filtered relationship scan does not retain intermediate rows`() {
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
            "MATCH (a:IntConstant)-[r:DATAFLOW]->(b:IntConstant) " +
                "WHERE b.value = -1 RETURN r ORDER BY b.value LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `data flow alias produces a matching relationship binding`() {
        val source = IntConstant(NodeId(3), 3)
        val target = IntConstant(NodeId(4), 4)
        val graph = DefaultGraph.Builder()
            .addNode(source)
            .addNode(target)
            .addEdge(DataFlowEdge(source.id, target.id, DataFlowKind.ASSIGN))
            .build()

        val result = CypherExecutor(graph).execute(
            "MATCH (a:IntConstant)-[r:DATA_FLOW]->(b:IntConstant) " +
                "WHERE b.value = 4 RETURN r.kind AS kind LIMIT 1"
        )

        assertEquals(listOf(mapOf("kind" to "ASSIGN")), result.rows)
    }

    @Test
    fun `zero-hit filtered variable path does not retain a distinct wide traversal`() {
        val source = IntConstant(NodeId(5), 5)
        val base = DefaultGraph.Builder()
            .addNode(source)
            .build()
        val virtualGraph = object : Graph by base {
            override fun node(id: NodeId) = when {
                id == source.id -> source
                id.value in TARGET_ID_OFFSET until TARGET_ID_OFFSET + VARIABLE_PATH_EDGE_COUNT ->
                    IntConstant(id, id.value)
                else -> null
            }

            override fun outgoing(id: NodeId): Sequence<Edge> =
                if (id == source.id) {
                    sequence {
                        repeat(VARIABLE_PATH_EDGE_COUNT) { index ->
                            yield(
                                DataFlowEdge(
                                    source.id,
                                    NodeId(TARGET_ID_OFFSET + index),
                                    DataFlowKind.ASSIGN
                                )
                            )
                        }
                    }
                } else {
                    emptySequence()
                }
        }

        val result = CypherExecutor(virtualGraph).execute(
            "MATCH (a:IntConstant)-[:DATAFLOW*1..2]->(b:IntConstant) " +
                "WHERE b.value = -1 RETURN b.id ORDER BY b.id LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `zero-hit reconvergent variable path stays within the default work budget`() {
        val graph = reconvergentGraph()

        val result = CypherExecutor(
            graph,
            CypherExecutionBudget(maxWorkUnits = DEFAULT_SERVER_WORK_BUDGET)
        ).execute(
            "MATCH (a:BooleanConstant)-[:DATAFLOW*1..8]->(b:IntConstant) " +
                "WHERE b.value = -1 RETURN b.id ORDER BY b.id LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `absent target label on reconvergent variable path stays within the default work budget`() {
        val graph = reconvergentGraph(allDescendantsAreInt = true)

        val result = CypherExecutor(
            graph,
            CypherExecutionBudget(maxWorkUnits = DEFAULT_SERVER_WORK_BUDGET)
        ).execute(
            "MATCH (a:BooleanConstant)-[:DATAFLOW*1..8]->(b:CallSiteNode) " +
                "RETURN b.id LIMIT 1"
        )

        assertEquals(listOf("b.id"), result.columns)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `absent target property on reconvergent variable path stays within the default work budget`() {
        val graph = reconvergentGraph(allDescendantsAreInt = true)

        val result = CypherExecutor(
            graph,
            CypherExecutionBudget(maxWorkUnits = DEFAULT_SERVER_WORK_BUDGET)
        ).execute(
            "MATCH (a:BooleanConstant)-[:DATAFLOW*1..8]->(b:IntConstant {value: -1}) " +
                "RETURN b.id LIMIT 1"
        )

        assertEquals(listOf("b.id"), result.columns)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `deep cyclic path does not retain quadratic blocker state`() {
        val source = IntConstant(NodeId(DEEP_CYCLE_ID_OFFSET), 0)
        val terminalId = NodeId(DEEP_CYCLE_ID_OFFSET + DEEP_CYCLE_NODE_COUNT - 1)
        val base = DefaultGraph.Builder()
            .addNode(source)
            .build()
        val virtualGraph = object : Graph by base {
            override fun node(id: NodeId): Node? =
                if (id.value in DEEP_CYCLE_ID_OFFSET until DEEP_CYCLE_ID_OFFSET + DEEP_CYCLE_NODE_COUNT) {
                    IntConstant(id, id.value - DEEP_CYCLE_ID_OFFSET)
                } else {
                    null
                }

            override fun outgoing(id: NodeId): Sequence<Edge> = when {
                id.value in DEEP_CYCLE_ID_OFFSET until terminalId.value -> sequenceOf(
                    DataFlowEdge(id, NodeId(id.value + 1), DataFlowKind.ASSIGN)
                )
                id == terminalId -> (0 until DEEP_CYCLE_NODE_COUNT - 1).asSequence().map { index ->
                    DataFlowEdge(id, NodeId(DEEP_CYCLE_ID_OFFSET + index), DataFlowKind.ASSIGN)
                }
                else -> emptySequence()
            }
        }

        val matches = PathFinder.findPathMatches(
            virtualGraph,
            setOf(source.id),
            PathFinder.SearchOptions(
                targets = emptySet(),
                edgeType = DataFlowEdge::class.java,
                minDepth = 1,
                maxDepth = DEEP_CYCLE_NODE_COUNT + 1,
                direction = PathFinder.Direction.OUTGOING,
                workTracker = CypherWorkTracker(CypherExecutionBudget(DEFAULT_SERVER_WORK_BUDGET))
            )
        ).count()

        assertEquals(0, matches)
    }

    private fun reconvergentGraph(allDescendantsAreInt: Boolean = false): Graph {
        val source = BooleanConstant(NodeId(20), true)
        val layers = (0 until RECONVERGENT_LAYER_COUNT).map { layer ->
            (0 until RECONVERGENT_LAYER_WIDTH).map { index ->
                val id = NodeId(21 + layer * RECONVERGENT_LAYER_WIDTH + index)
                if (allDescendantsAreInt || layer == RECONVERGENT_LAYER_COUNT - 1) {
                    IntConstant(id, index)
                } else {
                    BooleanConstant(id, true)
                }
            }
        }
        val builder = DefaultGraph.Builder().addNode(source)
        layers.flatten().forEach(builder::addNode)
        layers.first().forEach { target ->
            builder.addEdge(DataFlowEdge(source.id, target.id, DataFlowKind.ASSIGN))
        }
        layers.zipWithNext().forEach { (fromLayer, toLayer) ->
            fromLayer.forEach { from ->
                toLayer.forEach { to ->
                    builder.addEdge(DataFlowEdge(from.id, to.id, DataFlowKind.ASSIGN))
                }
            }
        }
        return builder.build()
    }

    private companion object {
        const val EDGE_COUNT = 4_000_000
        const val VARIABLE_PATH_EDGE_COUNT = 10_000_000
        const val TARGET_ID_OFFSET = 1_000_000
        const val RECONVERGENT_LAYER_COUNT = 8
        const val RECONVERGENT_LAYER_WIDTH = 8
        const val DEEP_CYCLE_NODE_COUNT = 3_000
        const val DEEP_CYCLE_ID_OFFSET = 300_000
        const val DEFAULT_SERVER_WORK_BUDGET = 250_000L
    }
}
