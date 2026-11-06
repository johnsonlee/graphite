package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class ExploreRegistryQueryTest : ExploreCommandTestSupport() {
    @Test
    fun `registry cypher endpoint fans out across loaded graphs`() {
        val root = Files.createTempDirectory("explore-registry-fanout")
        try {
            saveConstantGraph(root, "service-a", 101)
            saveConstantGraph(root, "service-b", 202)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)

            withRegistryApp(registry) { targetPort ->
                registry.load("service-a", Path.of("service-a"))
                registry.load("service-b", Path.of("service-b"))

                val query = "MATCH (n:IntConstant) RETURN n.value"
                val (allCode, allBody) = post(targetPort, "/api/cypher/graphs", """{"query":"$query","allGraphs":true,"mode":"fanout"}""")
                assertEquals(200, allCode, "Expected 200, body: $allBody")
                val allResult: Map<String, Any?> = parseJson(allBody)
                assertEquals(2.0, allResult["graphCount"])
                assertEquals(2.0, allResult[API_FIELD_QUERIED_GRAPH_COUNT])
                assertEquals(false, allResult[API_FIELD_TRUNCATED])
                assertEquals(2.0, allResult[API_FIELD_ROW_COUNT])

                @Suppress("UNCHECKED_CAST") val rows = allResult[API_FIELD_ROWS] as List<Map<String, Any?>>
                val valuesByGraph = rows.associate { it[API_FIELD_GRAPH_ID] as String to it["n.value"] }
                assertEquals(101.0, valuesByGraph["service-a"])
                assertEquals(202.0, valuesByGraph["service-b"])

                @Suppress("UNCHECKED_CAST") val columns = allResult[API_FIELD_COLUMNS] as List<String>
                assertEquals(API_FIELD_GRAPH_ID, columns.first())
                assertTrue(columns.contains("n.value"))
                @Suppress("UNCHECKED_CAST") val graphSummaries = allResult[API_FIELD_GRAPHS] as List<Map<String, Any?>>
                assertTrue(graphSummaries.none { API_FIELD_ROWS in it }, "Grouped rows should be opt-in")

                val (subsetCode, subsetBody) =
                    post(targetPort, "/api/cypher/graphs", """{"query":"$query","graphs":["service-b"],"mode":"fanout"}""")
                assertEquals(200, subsetCode, "Expected 200, body: $subsetBody")
                val subsetResult: Map<String, Any?> = parseJson(subsetBody)
                @Suppress("UNCHECKED_CAST") val subsetRows = subsetResult[API_FIELD_ROWS] as List<Map<String, Any?>>
                assertEquals(1, subsetRows.size)
                assertEquals("service-b", subsetRows.single()[API_FIELD_GRAPH_ID])
                assertEquals(202.0, subsetRows.single()["n.value"])

                val (scopedCrossGraphCode, scopedCrossGraphBody) =
                    post(targetPort, "/api/cypher/graphs", """{"query":"$query","graph":"service-b"}""")
                assertEquals(200, scopedCrossGraphCode, "Expected 200, body: $scopedCrossGraphBody")
                val scopedCrossGraph: Map<String, Any?> = parseJson(scopedCrossGraphBody)
                assertEquals("cross-graph", scopedCrossGraph[API_PARAM_MODE])
                assertEquals(listOf("service-b"), scopedCrossGraph[API_FIELD_GRAPHS])
                assertEquals(1.0, scopedCrossGraph["graphCount"])
                @Suppress("UNCHECKED_CAST") val scopedCrossGraphRows = scopedCrossGraph[API_FIELD_ROWS] as List<Map<String, Any?>>
                assertEquals(listOf(202.0), scopedCrossGraphRows.map { it["n.value"] })

                val encodedQuery = java.net.URLEncoder.encode(query, Charsets.UTF_8)
                val (getSubsetCode, getSubsetBody) = get(targetPort, "/api/cypher/graphs?query=$encodedQuery&graph=service-a&mode=fanout")
                assertEquals(200, getSubsetCode, "Expected 200, body: $getSubsetBody")
                val getSubsetResult: Map<String, Any?> = parseJson(getSubsetBody)
                @Suppress("UNCHECKED_CAST") val getSubsetRows = getSubsetResult[API_FIELD_ROWS] as List<Map<String, Any?>>
                assertEquals(1, getSubsetRows.size)
                assertEquals("service-a", getSubsetRows.single()[API_FIELD_GRAPH_ID])
                assertEquals(101.0, getSubsetRows.single()["n.value"])

                val (missingCode, missingBody) = post(targetPort, "/api/cypher/graphs", """{"query":"$query","graphs":["missing"]}""")
                assertEquals(404, missingCode, "Expected 404, body: $missingBody")
                assertEquals(mapOf(API_FIELD_ERROR to "Graph not loaded: missing"), parseJson<Map<String, Any?>>(missingBody))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scoped cypher preserves missing graph response contract`() {
        withExploreApp(DefaultGraph.Builder().build()) { targetPort ->
            val (code, body) = get(targetPort, "/api/graphs/missing/cypher?query=RETURN%201")

            assertEquals(404, code)
            assertEquals(mapOf(API_FIELD_ERROR to "Graph not loaded"), parseJson<Map<String, Any?>>(body))
        }
    }

    @Test
    fun `registry cypher endpoint caps total rows and can opt into grouped rows`() {
        val root = Files.createTempDirectory("explore-registry-capped-fanout")
        try {
            saveConstantGraph(root, "service-a", 101, 102, 103)
            saveConstantGraph(root, "service-b", 201, 202, 203)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)

            withRegistryApp(registry) { targetPort ->
                registry.load("service-a", Path.of("service-a"))
                registry.load("service-b", Path.of("service-b"))

                val query = "MATCH (n:IntConstant) RETURN n.value"
                val (cappedCode, cappedBody) =
                    post(
                        targetPort,
                        "/api/cypher/graphs",
                        """{"query":"$query","graphs":["service-a","service-b"],"mode":"fanout","limit":4,"perGraphLimit":3}""")
                assertEquals(200, cappedCode, "Expected 200, body: $cappedBody")
                val cappedResult: Map<String, Any?> = parseJson(cappedBody)
                assertEquals(2.0, cappedResult["graphCount"])
                assertEquals(2.0, cappedResult[API_FIELD_QUERIED_GRAPH_COUNT])
                assertEquals(3.0, cappedResult[API_FIELD_PER_GRAPH_LIMIT])
                assertEquals(4.0, cappedResult[API_PARAM_LIMIT])
                assertEquals(true, cappedResult[API_FIELD_TRUNCATED])
                @Suppress("UNCHECKED_CAST") val cappedRows = cappedResult[API_FIELD_ROWS] as List<Map<String, Any?>>
                assertEquals(4, cappedRows.size)
                @Suppress("UNCHECKED_CAST") val cappedGraphs = cappedResult[API_FIELD_GRAPHS] as List<Map<String, Any?>>
                assertTrue(cappedGraphs.none { API_FIELD_ROWS in it }, "Grouped rows should be omitted by default")

                val (groupedCode, groupedBody) =
                    post(
                        targetPort,
                        "/api/cypher/graphs?includeGraphRows=true",
                        """{"query":"$query","graphs":["service-a","service-b"],"mode":"fanout","limit":2,"perGraphLimit":1}""")
                assertEquals(200, groupedCode, "Expected 200, body: $groupedBody")
                val groupedResult: Map<String, Any?> = parseJson(groupedBody)
                @Suppress("UNCHECKED_CAST") val groupedGraphs = groupedResult[API_FIELD_GRAPHS] as List<Map<String, Any?>>
                assertTrue(groupedGraphs.all { API_FIELD_ROWS in it }, "Grouped rows should be included on request")
                assertEquals(2.0, groupedResult[API_FIELD_ROW_COUNT])
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fanout shares one request work budget without fixed graph shares`() {
        val root = Files.createTempDirectory("explore-registry-shared-fanout-budget")
        try {
            saveConstantGraph(root, "service-a", 101)
            saveConstantGraph(root, "service-b", 202)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 1))

            withRegistryApp(registry, routes) { targetPort ->
                registry.load("service-a", Path.of("service-a"))
                registry.load("service-b", Path.of("service-b"))
                val query = "MATCH (n:IntConstant) RETURN n.value"

                val (withinCode, withinBody) =
                    post(
                        targetPort,
                        "/api/cypher/graphs",
                        """{"query":"$query","allGraphs":true,"mode":"fanout","limit":1,"perGraphLimit":1}""")
                assertEquals(200, withinCode, withinBody)
                val within: Map<String, Any?> = parseJson(withinBody)
                assertEquals(1.0, within[API_FIELD_ROW_COUNT])

                val (exceededCode, exceededBody) =
                    post(
                        targetPort,
                        "/api/cypher/graphs",
                        """{"query":"$query","allGraphs":true,"mode":"fanout","limit":2,"perGraphLimit":1}""")
                assertEquals(429, exceededCode, exceededBody)
                assertTrue(exceededBody.contains("cypher_work_budget_exceeded"), exceededBody)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `fanout lets the first graph use the remaining request budget`() {
        val root = Files.createTempDirectory("explore-registry-fanout-budget-remainder")
        try {
            saveConstantGraph(root, "service-a", 101, 102, 103, 104, 105, 106)
            saveConstantGraph(root, "service-b", 202)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10))

            withRegistryApp(registry, routes) { targetPort ->
                registry.load("service-a", Path.of("service-a"))
                registry.load("service-b", Path.of("service-b"))
                val query = "MATCH (n:IntConstant) WHERE n.value = 106 RETURN n.value"
                val (code, body) =
                    post(
                        targetPort,
                        "/api/cypher/graphs",
                        """{"query":"$query","allGraphs":true,"mode":"fanout","limit":1,"perGraphLimit":1}""")

                assertEquals(200, code, body)
                val result: Map<String, Any?> = parseJson(body)
                assertEquals(1.0, result[API_FIELD_ROW_COUNT])
                @Suppress("UNCHECKED_CAST") val rows = result[API_FIELD_ROWS] as List<Map<String, Any?>>
                assertEquals(106.0, rows.single()["n.value"])
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registry cypher endpoint keeps default limit global across twenty one graphs`() {
        val root = Files.createTempDirectory("explore-registry-21-fanout")
        val graphCount = 21
        try {
            repeat(graphCount) { graphIndex ->
                val values = IntArray(60) { valueIndex -> graphIndex * 1_000 + valueIndex }
                saveConstantGraph(root, "service-$graphIndex", *values)
            }
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)

            withRegistryApp(registry) { targetPort ->
                repeat(graphCount) { graphIndex -> registry.load("service-$graphIndex", Path.of("service-$graphIndex")) }

                val query = "MATCH (n:IntConstant) RETURN n.value"
                val (code, body) = post(targetPort, "/api/cypher/graphs", """{"query":"$query","allGraphs":true,"mode":"fanout"}""")
                assertEquals(200, code, "Expected 200, body: $body")
                val result: Map<String, Any?> = parseJson(body)
                assertEquals(graphCount.toDouble(), result["graphCount"])
                assertEquals(graphCount.toDouble(), result[API_FIELD_QUERIED_GRAPH_COUNT])
                assertEquals(48.0, result[API_FIELD_PER_GRAPH_LIMIT])
                assertEquals(1_000.0, result[API_PARAM_LIMIT])
                assertEquals(1_000.0, result[API_FIELD_ROW_COUNT])
                assertEquals(true, result[API_FIELD_TRUNCATED])
                @Suppress("UNCHECKED_CAST") val graphSummaries = result[API_FIELD_GRAPHS] as List<Map<String, Any?>>
                assertTrue(graphSummaries.none { API_FIELD_ROWS in it }, "Grouped rows should stay opt-in")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registry hot update swaps graph path for same id`() {
        val root = Files.createTempDirectory("explore-registry-update")
        try {
            saveConstantGraph(root, "service-v1", 1)
            saveConstantGraph(root, "service-v2", 2)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)

            withRegistryApp(registry) { targetPort ->
                val (firstLoadCode, firstLoadBody) = put(targetPort, "/api/graphs/service", """{"path":"service-v1"}""")
                assertEquals(200, firstLoadCode, "Expected 200, body: $firstLoadBody")
                val (firstQueryCode, firstQueryBody) =
                    post(targetPort, "/api/graphs/service/cypher", """{"query":"MATCH (n:IntConstant) RETURN n.value"}""")
                assertEquals(200, firstQueryCode, "Expected 200, body: $firstQueryBody")
                assertEquals(1.0, singleCypherValue(firstQueryBody), "Expected v1 value, body: $firstQueryBody")

                registry.acquire("service")!!.use { oldLease ->
                    val (secondLoadCode, secondLoadBody) = put(targetPort, "/api/graphs/service", """{"path":"service-v2"}""")
                    assertEquals(200, secondLoadCode, "Expected 200, body: $secondLoadBody")

                    val oldValues = oldLease.graph.nodes(IntConstant::class.java).map { it.value }.toList()
                    assertEquals(listOf(1), oldValues, "In-flight lease must retain the old graph snapshot")

                    val (secondQueryCode, secondQueryBody) =
                        post(targetPort, "/api/graphs/service/cypher", """{"query":"MATCH (n:IntConstant) RETURN n.value"}""")
                    assertEquals(200, secondQueryCode, "Expected 200, body: $secondQueryBody")
                    assertEquals(2.0, singleCypherValue(secondQueryBody), "Expected v2 value, body: $secondQueryBody")
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registry refresh never exposes an acquire gap`() {
        val root = Files.createTempDirectory("explore-registry-refresh-race")
        try {
            saveConstantGraph(root, "service-v1", 1)
            saveConstantGraph(root, "service-v2", 2)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            val executor = Executors.newFixedThreadPool(5)
            val start = CountDownLatch(1)
            val refreshComplete = AtomicBoolean()
            val failures = ConcurrentLinkedQueue<String>()
            try {
                registry.load("service", Path.of("service-v1"))
                val refresher =
                    executor.submit {
                        start.await()
                        try {
                            repeat(1_000) { index -> registry.load("service", Path.of("service-v${index % 2 + 1}")) }
                        } finally {
                            refreshComplete.set(true)
                        }
                    }
                val readers =
                    List(4) { readerIndex ->
                        executor.submit {
                            start.await()
                            var reads = 0
                            while (!refreshComplete.get() || reads < 2_000) {
                                if (readerIndex % 2 == 0) {
                                    val lease = registry.acquire("service")
                                    if (lease == null) {
                                        failures += "scoped acquire returned null"
                                    } else {
                                        lease.use { assertRefreshValue(it.graph, failures) }
                                    }
                                } else {
                                    val leases = registry.acquireAll()
                                    if (leases.size != 1) {
                                        failures += "all-graph acquire returned ${leases.size} graphs"
                                    }
                                    leases.forEach { lease -> lease.use { assertRefreshValue(it.graph, failures) } }
                                }
                                reads++
                            }
                        }
                    }

                start.countDown()
                refresher.get(30, TimeUnit.SECONDS)
                readers.forEach { it.get(30, TimeUnit.SECONDS) }

                assertTrue(failures.isEmpty(), failures.firstOrNull() ?: "Unexpected refresh failure")
            } finally {
                executor.shutdownNow()
                registry.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registry keeps retired graph alive until checked out lease closes`() {
        val root = Files.createTempDirectory("explore-registry-lease")
        try {
            saveConstantGraph(root, "service-a", 1)
            saveConstantGraph(root, "service-b", 2)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            try {
                val first = registry.load(" service-a ", Path.of("service-a"))
                val second = registry.load("service-b", Path.of("service-b"))
                assertEquals("service-a", first.id)
                assertEquals("service-b", second.id)

                val heldLease = registry.acquire("service-a")
                assertEquals("service-a", heldLease?.id)

                assertTrue(registry.unload("service-a"))
                assertNull(registry.acquire("service-a"))
                registry.acquire("service-b")?.use { lease -> assertEquals("service-b", lease.id) }
                assertFalse(registry.unload("missing"))

                val invalid = runCatching { registry.acquire("bad id") }.exceptionOrNull()
                assertTrue(invalid is IllegalArgumentException, "Expected invalid graph id error, got: $invalid")

                heldLease?.close()
                heldLease?.close()
            } finally {
                registry.close()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
