package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopologyStoreTest {

    @Test
    fun `mapped topology round trips internal files and streamed API JSON`() {
        val tempRoot = Files.createTempDirectory("topology-store-test")
        val builtAt = Instant.parse("2026-08-10T12:34:56.123456789Z")
        val longEvidence = "证据/Adapter#invoke:" + "x".repeat(1_024)
        val topology = TopologyGraph(
            nodes = listOf(
                TopologyNode("billing", GraphStats(11, 12, 13, 14)),
                TopologyNode("orders", GraphStats(21, 22, 23, 24))
            ),
            edges = listOf(
                TopologyEdge(
                    from = "orders",
                    to = "billing",
                    protocol = "公司-rpc",
                    weight = 7,
                    operations = listOf("Billing.charge", "Billing.refund"),
                    evidence = listOf(longEvidence)
                )
            ),
            builtAt = builtAt,
            rules = listOf("rpc.cypher", "mq.cypher"),
            matchedRows = 7
        )

        val mapped = TopologyStore.writeAndOpen(tempRoot, topology)
        val buildDir = mapped.buildDir
        try {
            assertEquals(tempRoot.toAbsolutePath().resolve("graphite"), buildDir.parent)
            assertEquals(buildDir.fileName.toString(), UUID.fromString(buildDir.fileName.toString()).toString())
            assertEquals(
                setOf(
                    "manifest",
                    "topology.strings",
                    "topology.nodes",
                    "topology.relations",
                    "topology.operations",
                    "topology.evidence",
                    "topology.json"
                ),
                Files.list(buildDir).use { files -> files.map { it.fileName.toString() }.toList().toSet() }
            )
            assertEquals(topology, mapped.materialize())
            assertEquals(TopologySummary(2, 1, 7, builtAt, topology.rules), mapped.summary())

            val api = mapped.openApiStream {}
            val bytes = api.input.use { it.readBytes() }
            assertEquals(api.contentLength, bytes.size.toLong())
            val json = JsonParser.parseString(String(bytes, StandardCharsets.UTF_8)).asJsonObject
            assertEquals(2, json["graphCount"].asInt)
            assertEquals(1, json["relationCount"].asInt)
            assertEquals(longEvidence, json[API_FIELD_EDGES].asJsonArray[0].asJsonObject[TOPOLOGY_EVIDENCE].asJsonArray[0].asString)
            assertFalse(json["stale"].asBoolean)
        } finally {
            mapped.close()
        }

        assertFalse(buildDir.exists())
        assertTrue(tempRoot.resolve("graphite").exists())
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `service keeps retired UUID directory until mapped response lease closes`() {
        val tempRoot = Files.createTempDirectory("topology-lease-test")
        val registry = GraphRegistry(tempRoot, GraphStore.LoadMode.MAPPED)
        val service = TopologyService(registry, emptyList(), tempRoot)
        try {
            service.rebuild()
            val first = service.currentBuildDir()
            val response = requireNotNull(service.openApiStream())

            service.rebuild()
            val second = service.currentBuildDir()
            assertNotEquals(first, second)
            assertTrue(first.exists())
            assertTrue(second.exists())

            response.input.use { it.readBytes() }
            assertFalse(first.exists())
            assertTrue(second.exists())
        } finally {
            service.close()
            registry.close()
        }

        assertFalse(Files.list(tempRoot.resolve("graphite")).use { it.findAny().isPresent })
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `service falls back to a stale materialized response when graph catalog changes`() {
        val tempRoot = Files.createTempDirectory("topology-stale-test")
        val registry = GraphRegistry(tempRoot, GraphStore.LoadMode.MAPPED)
        val service = TopologyService(registry, emptyList(), tempRoot)
        try {
            service.rebuild()
            val graphDir = tempRoot.resolve("service-graph")
            GraphStore.save(DefaultGraph.Builder().build(), graphDir)
            registry.load("service", graphDir)

            assertNull(service.openApiStream())
            assertEquals(true, service.toApiMap()["stale"])
            assertEquals(0, service.toApiMap()["graphCount"])
        } finally {
            service.close()
            registry.close()
            tempRoot.toFile().deleteRecursively()
        }
    }
}
