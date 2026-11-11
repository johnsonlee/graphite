package io.johnsonlee.graphite.cli

import com.google.gson.JsonParser
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopologyLeaseContractTest {
    @Test
    fun `retired generations survive until their last response closes even after service shutdown`() {
        val root = Files.createTempDirectory("topology-generation-leases")
        val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        val service = TopologyService(registry, emptyList(), root)
        try {
            service.rebuild()
            val first = service.currentBuildDir()
            val firstReader = requireNotNull(service.openApiStream())
            val lastReader = requireNotNull(service.openApiStream())
            service.rebuild()
            val second = service.currentBuildDir()
            val secondReader = requireNotNull(service.openApiStream())
            firstReader.input.close()
            firstReader.input.close()
            assertTrue(first.exists(), "Another response still owns the retired mapping")

            service.close()
            service.close()
            assertTrue(first.exists())
            assertTrue(second.exists())
            assertResponse(lastReader, emptyList())
            assertFalse(first.exists(), "The last reader releases the retired generation")
            assertTrue(second.exists(), "Closing an old response must not release the next generation")
            assertResponse(secondReader, emptyList())
            assertFalse(second.exists())
        } finally {
            service.close()
            registry.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `concurrent readers consume complete topology while generations are replaced`() {
        val root = Files.createTempDirectory("topology-racing-readers")
        val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
        val service = TopologyService(registry, emptyList(), root)
        val workers = Executors.newFixedThreadPool(3)
        try {
            val graphDir = root.resolve("service")
            GraphStore.save(DefaultGraph.Builder().build(), graphDir)
            registry.load("service", graphDir)
            service.rebuild()
            val start = CyclicBarrier(3)
            val readers = List(2) {
                workers.submit {
                    start.await(10, TimeUnit.SECONDS)
                    repeat(40) { assertResponse(requireNotNull(service.openApiStream()), listOf("service")) }
                }
            }
            val writer = workers.submit {
                start.await(10, TimeUnit.SECONDS)
                repeat(20) { service.rebuild() }
            }
            (readers + writer).forEach { it.get(30, TimeUnit.SECONDS) }
            service.close()
            assertEquals(0L, Files.list(root.resolve("graphite")).use { it.count() })
        } finally {
            workers.shutdownNow()
            workers.awaitTermination(10, TimeUnit.SECONDS)
            service.close()
            registry.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun assertResponse(response: TopologyApiStream, ids: List<String>) {
        val bytes = response.input.use { it.readBytes() }
        assertEquals(response.contentLength, bytes.size.toLong())
        val json = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        assertEquals(ids.size, json["graphCount"].asInt)
        assertEquals(ids, json["nodes"].asJsonArray.map { it.asJsonObject["id"].asString })
        assertEquals(0, json["relationCount"].asInt)
        assertFalse(json["stale"].asBoolean)
    }
}
