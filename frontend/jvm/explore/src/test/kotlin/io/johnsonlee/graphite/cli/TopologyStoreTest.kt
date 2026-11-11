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
import kotlin.test.assertFailsWith
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
            mapped.openApiStream {}.input.use { input ->
                assertEquals('{'.code, input.read())
                input.readBytes()
                assertEquals(-1, input.read())
            }
        } finally {
            mapped.close()
        }

        assertFalse(buildDir.exists())
        assertTrue(tempRoot.resolve("graphite").exists())
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `unsupported manifest and data versions fail before consuming topology`() {
        for (file in listOf("manifest", "topology.nodes")) {
            val root = Files.createTempDirectory("topology-version")
            val mapped = TopologyStore.writeAndOpen(root, TopologyGraph.nodesOnly(emptyMap()))
            try {
                java.nio.channels.FileChannel.open(mapped.buildDir.resolve(file), java.nio.file.StandardOpenOption.WRITE).use { channel ->
                    channel.write(java.nio.ByteBuffer.allocate(4).putInt(999).flip(), 4)
                }
                assertEquals("Unsupported topology format version 999",
                    assertFailsWith<IllegalArgumentException> { MappedTopologySnapshot.open(mapped.buildDir) }.message)
            } finally {
                mapped.close()
                root.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `unsupported mapped storage removes the partially written topology`() {
        val root = Files.createTempDirectory("topology-unsupported-store")
        try {
            val uri = java.net.URI.create("jar:" + root.resolve("store.zip").toUri())
            java.nio.file.FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { archive ->
                val directory = archive.getPath("/")
                assertFailsWith<UnsupportedOperationException> {
                    TopologyStore.writeAndOpen(directory, TopologyGraph.nodesOnly(emptyMap()))
                }
                assertEquals(0L, Files.list(directory.resolve("graphite")).use { it.count() })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
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

    @Test
    fun `service detects a same id graph replacement as stale`() {
        val tempRoot = Files.createTempDirectory("topology-replacement-stale-test")
        val registry = GraphRegistry(tempRoot, GraphStore.LoadMode.MAPPED)
        val service = TopologyService(registry, emptyList(), tempRoot)
        try {
            val firstGraph = tempRoot.resolve("service-v1")
            val secondGraph = tempRoot.resolve("service-v2")
            GraphStore.save(DefaultGraph.Builder().build(), firstGraph)
            GraphStore.save(DefaultGraph.Builder().build(), secondGraph)
            registry.load("service", firstGraph)
            service.rebuild()

            requireNotNull(service.openApiStream()).input.use { it.readBytes() }
            assertEquals(false, service.toApiMap()["stale"])

            registry.load("service", secondGraph)

            assertNull(service.openApiStream())
            assertEquals(true, service.toApiMap()["stale"])
            assertEquals(1, service.toApiMap()["graphCount"])
        } finally {
            service.close()
            registry.close()
            tempRoot.toFile().deleteRecursively()
        }
    }
}
