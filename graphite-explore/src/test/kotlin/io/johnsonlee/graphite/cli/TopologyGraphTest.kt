package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TopologyGraphTest {

    private val graphs = listOf(
        CypherGraph("consumer", DefaultGraph.Builder().build()),
        CypherGraph("provider", DefaultGraph.Builder().build()),
        CypherGraph("isolated", DefaultGraph.Builder().build())
    )
    private val stats = graphs.associate { it.id to GraphStats.EMPTY }

    @Test
    fun `one cross graph query builds and aggregates the complete topology`() {
        val topology = TopologyGraphBuilder.build(
            graphs,
            stats,
            listOf(
                TopologyQuery(
                    "rpc.cypher",
                    """
                    UNWIND [1, 2] AS match
                    RETURN 'consumer' AS source,
                           'provider' AS target,
                           'rpc' AS protocol,
                           'Catalog.find' AS operation,
                           2 AS weight,
                           'GeneratedAdapter' AS evidence
                    """.trimIndent()
                )
            )
        )

        assertEquals(listOf("consumer", "isolated", "provider"), topology.nodes.map { it.id })
        assertEquals(2, topology.matchedRows)
        assertEquals(1, topology.edges.size)
        assertEquals(
            TopologyEdge(
                from = "consumer",
                to = "provider",
                protocol = "rpc",
                weight = 4,
                operations = listOf("Catalog.find"),
                evidence = listOf("GeneratedAdapter")
            ),
            topology.edges.single()
        )
        val api = topology.toApiMap(listOf("consumer", "isolated", "provider"))
        assertEquals(false, api["stale"])
        @Suppress("UNCHECKED_CAST")
        val apiEdge = (api[API_FIELD_EDGES] as List<Map<String, Any?>>).single()
        assertEquals("TopologyCall", apiEdge[API_FIELD_TYPE])
        assertEquals(4L, apiEdge[TOPOLOGY_WEIGHT])
    }

    @Test
    fun `topology query must use loaded graph ids`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TopologyGraphBuilder.build(
                graphs,
                stats,
                listOf(
                    TopologyQuery(
                        "bad.cypher",
                        "RETURN 'consumer' AS source, 'missing' AS target"
                    )
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("unknown graph 'missing'"))
    }

    @Test
    fun `cypher can derive a provider graph id from a generated adapter package`() {
        val caller = MethodDescriptor(TypeDescriptor("shop.Client"), "buy", emptyList(), TypeDescriptor("void"))
        val adapter = MethodDescriptor(
            TypeDescriptor("rpc.adapters.provider.Adapter"),
            "invoke",
            emptyList(),
            TypeDescriptor("void")
        )
        val consumer = DefaultGraph.Builder()
            .addNode(CallSiteNode(NodeId.next(), caller, adapter, 42, null, emptyList()))
            .build()
        val adapterGraphs = listOf(
            CypherGraph("consumer", consumer),
            CypherGraph("provider", DefaultGraph.Builder().build())
        )

        val topology = TopologyGraphBuilder.build(
            adapterGraphs,
            adapterGraphs.associate { it.id to GraphStats.EMPTY },
            listOf(
                TopologyQuery(
                    "adapter.cypher",
                    """
                    MATCH (call:CallSiteNode)
                    WHERE graphId(call) = 'consumer'
                      AND call.callee_class =~ 'rpc\\.adapters\\..*\\.Adapter'
                    RETURN graphId(call) AS source,
                           split(call.callee_class, '.')[2] AS target,
                           'generated-rpc' AS protocol,
                           call.callee_name AS operation
                    """.trimIndent()
                )
            )
        )

        assertEquals("consumer", topology.edges.single().from)
        assertEquals("provider", topology.edges.single().to)
        assertEquals("generated-rpc", topology.edges.single().protocol)
        assertEquals(listOf("invoke"), topology.edges.single().operations)
    }

    @Test
    fun `topology query requires source and target aliases`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TopologyGraphBuilder.build(
                graphs,
                stats,
                listOf(TopologyQuery("bad.cypher", "RETURN 'consumer' AS source"))
            )
        }

        assertTrue(error.message.orEmpty().contains("must return 'source' and 'target'"))
    }

    @Test
    fun `topology query path accepts one file or a sorted directory`() {
        val directory = Files.createTempDirectory("topology-query-test")
        try {
            Files.writeString(directory.resolve("02-mq.cypher"), "RETURN 'a' AS source, 'b' AS target")
            Files.writeString(directory.resolve("01-rpc.cypher"), "RETURN 'a' AS source, 'b' AS target")
            Files.writeString(directory.resolve("README.md"), "ignored")

            assertEquals(listOf("01-rpc.cypher", "02-mq.cypher"), TopologyQuerySource.load(directory).map { it.name })
            assertEquals(listOf("01-rpc.cypher"), TopologyQuerySource.load(directory.resolve("01-rpc.cypher")).map { it.name })
            assertTrue(TopologyQuerySource.load(null).isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `topology service exposes a current nodes-only snapshot`() {
        val root = Files.createTempDirectory("topology-service-test")
        val registry = GraphRegistry(root, io.johnsonlee.graphite.webgraph.GraphStore.LoadMode.MAPPED)
        try {
            val service = TopologyService(registry, emptyList())
            service.rebuild()

            assertEquals(0, service.toApiMap()["graphCount"])
            assertEquals(false, service.toApiMap()["stale"])
        } finally {
            registry.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `topology builder validates catalog and weights`() {
        assertEquals(3, TopologyGraphBuilder.build(graphs, stats, emptyList()).nodes.size)
        assertFailsWith<IllegalArgumentException> {
            TopologyGraphBuilder.build(
                graphs,
                stats - "isolated",
                listOf(TopologyQuery("catalog.cypher", "RETURN 'consumer' AS source, 'provider' AS target"))
            )
        }
        val badWeight = assertFailsWith<IllegalArgumentException> {
            TopologyGraphBuilder.build(
                graphs,
                stats,
                listOf(
                    TopologyQuery(
                        "weight.cypher",
                        "RETURN 'consumer' AS source, 'provider' AS target, 0.5 AS weight"
                    )
                )
            )
        }
        assertTrue(badWeight.message.orEmpty().contains("fractional weight"))

        val stringWeight = TopologyGraphBuilder.build(
            graphs,
            stats,
            listOf(
                TopologyQuery(
                    "string-weight.cypher",
                    "RETURN 'consumer' AS source, 'provider' AS target, '3' AS weight"
                )
            )
        )
        assertEquals(3, stringWeight.edges.single().weight)

        val blankGraph = assertFailsWith<IllegalStateException> {
            TopologyGraphBuilder.build(
                graphs,
                stats,
                listOf(
                    TopologyQuery(
                        "blank.cypher",
                        "RETURN '' AS source, 'provider' AS target"
                    )
                )
            )
        }
        assertTrue(blankGraph.message.orEmpty().contains("blank 'source'"))
    }
}
