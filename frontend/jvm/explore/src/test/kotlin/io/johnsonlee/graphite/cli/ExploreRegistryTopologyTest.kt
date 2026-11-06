package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.graphDir
import io.johnsonlee.graphite.webgraph.GraphStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class ExploreRegistryTopologyTest : ExploreCommandTestSupport() {
    @Test
    fun `registry topology endpoint returns the materialized cross graph calls`() {
        val registry = GraphRegistry(graphDir.parent, GraphStore.LoadMode.MAPPED)
        registry.load("consumer", graphDir)
        registry.load("provider", graphDir)
        val topology =
            TopologyService(
                    registry,
                    listOf(
                        TopologyQuery(
                            "test.cypher",
                            """
                        UNWIND [1, 2] AS match
                        RETURN 'consumer' AS source, 'provider' AS target,
                               'rpc' AS protocol, 2 AS weight
                        """
                                .trimIndent())))
                .also { it.rebuild() }

        withRegistryApp(registry, topology) { targetPort ->
            val (code, body) = get(targetPort, "/api/topology")
            assertEquals(200, code, body)
            val result: Map<String, Any?> = parseJson(body)
            assertEquals(2.0, result["graphCount"])
            assertEquals(1.0, result["relationCount"])
            @Suppress("UNCHECKED_CAST") val edges = result[API_FIELD_EDGES] as List<Map<String, Any?>>
            assertEquals("consumer", edges.single()[API_FIELD_FROM])
            assertEquals("provider", edges.single()[API_FIELD_TO])
            assertEquals(4.0, edges.single()[TOPOLOGY_WEIGHT])
        }
    }

    @Test
    fun `registry load rolls back when topology rebuild fails`() {
        val root = Files.createTempDirectory("explore-registry-load-rollback")
        try {
            saveConstantGraph(root, "anchor", 1)
            saveConstantGraph(root, "candidate", 2)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            registry.load("anchor", Path.of("anchor"))
            val topology =
                TopologyService(
                        registry,
                        listOf(
                            TopologyQuery(
                                "load-rollback.cypher",
                                """
                            MATCH (n:IntConstant)
                            WHERE graphId(n) = 'candidate'
                            RETURN graphId(n) AS source, 'missing' AS target
                            """
                                    .trimIndent())),
                        root)
                    .also { it.rebuild() }

            withRegistryApp(registry, topology) { targetPort ->
                val (loadCode, loadBody) = put(targetPort, "/api/graphs/candidate", """{"path":"candidate"}""")
                assertEquals(400, loadCode, loadBody)
                assertTrue(loadBody.contains("unknown graph") && loadBody.contains("missing"), loadBody)
                assertEquals(listOf("anchor"), registry.ids())
                assertEquals(404, get(targetPort, "/api/graphs/candidate").first)
                assertEquals(200, get(targetPort, "/api/topology").first)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registry replacement rolls back when topology rebuild fails`() {
        val root = Files.createTempDirectory("explore-registry-replace-rollback")
        try {
            saveConstantGraph(root, "service-v1", 1)
            saveConstantGraph(root, "service-v2", 2)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            registry.load("service", Path.of("service-v1"))
            registry.load("1", Path.of("service-v1"))
            val topology =
                TopologyService(
                        registry,
                        listOf(
                            TopologyQuery(
                                "replace-rollback.cypher",
                                """
                            MATCH (n:IntConstant)
                            WHERE graphId(n) = 'service'
                            RETURN graphId(n) AS source, n.value AS target
                            """
                                    .trimIndent())),
                        root)
                    .also { it.rebuild() }

            withRegistryApp(registry, topology) { targetPort ->
                val (replaceCode, replaceBody) = put(targetPort, "/api/graphs/service", """{"path":"service-v2"}""")
                assertEquals(400, replaceCode, replaceBody)
                assertTrue(replaceBody.contains("unknown graph") && replaceBody.contains("2"), replaceBody)

                val (queryCode, queryBody) =
                    post(targetPort, "/api/graphs/service/cypher", """{"query":"MATCH (n:IntConstant) RETURN n.value"}""")
                assertEquals(200, queryCode, queryBody)
                assertEquals(1.0, singleCypherValue(queryBody), queryBody)
                assertEquals(200, get(targetPort, "/api/topology").first)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `registry unload rolls back when topology rebuild fails`() {
        val root = Files.createTempDirectory("explore-registry-unload-rollback")
        try {
            saveConstantGraph(root, "consumer", 1)
            saveConstantGraph(root, "provider", 2)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)
            registry.load("consumer", Path.of("consumer"))
            registry.load("provider", Path.of("provider"))
            val topology =
                TopologyService(
                        registry,
                        listOf(TopologyQuery("unload-rollback.cypher", "RETURN 'consumer' AS source, 'provider' AS target")),
                        root)
                    .also { it.rebuild() }

            withRegistryApp(registry, topology) { targetPort ->
                val (deleteCode, deleteBody) = delete(targetPort, "/api/graphs/provider")
                assertEquals(400, deleteCode, deleteBody)
                assertTrue(deleteBody.contains("unknown graph") && deleteBody.contains("provider"), deleteBody)
                assertEquals(listOf("consumer", "provider"), registry.ids())
                assertEquals(200, get(targetPort, "/api/graphs/provider").first)
                assertEquals(200, get(targetPort, "/api/topology").first)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `GET api graphs returns cached stats and totals`() {
        val (code, body) = get("/api/graphs")
        assertEquals(200, code, "Expected 200 but got $code, body: $body")
        val response: Map<String, Any?> = parseJson(body)
        @Suppress("UNCHECKED_CAST") val totals = response["totals"] as Map<String, Any?>
        assertEquals(10.0, totals["nodes"])
        assertEquals(5.0, totals["edges"])
        assertEquals(3.0, totals["methods"])
        assertEquals(1.0, totals["callSites"])
        @Suppress("UNCHECKED_CAST") val graphs = response[API_FIELD_GRAPHS] as List<Map<String, Any?>>
        assertEquals(STANDALONE_GRAPH_ID, graphs.single()[API_FIELD_ID])
        assertEquals(10.0, graphs.single()[API_FIELD_NODES])

        val (detailCode, detailBody) = get("/api/graphs/$STANDALONE_GRAPH_ID")
        assertEquals(200, detailCode, detailBody)
        val detail: Map<String, Any?> = parseJson(detailBody)
        @Suppress("UNCHECKED_CAST") val graph = detail["graph"] as Map<String, Any?>
        assertEquals(5.0, graph[API_FIELD_EDGES])
        assertEquals(404, get("/api/graphs/missing").first)
        assertEquals(400, get("/api/graphs/bad%20id").first)
        assertEquals(404, get("/api/info").first)
    }

    @Test
    fun `registry routes support empty startup and loading graph by id`() {
        val root = Files.createTempDirectory("explore-registry-empty")
        try {
            saveConstantGraph(root, "service-a", 101)
            saveConstantGraph(root, "service-b", 202)
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)

            withRegistryApp(registry) { targetPort ->
                val (emptyCode, emptyBody) = get(targetPort, "/api/graphs")
                assertEquals(200, emptyCode, "Expected 200, body: $emptyBody")
                val emptyResult: Map<String, Any?> = parseJson(emptyBody)
                assertEquals(0.0, emptyResult["count"])
                assertEquals(root.toString(), emptyResult["data"])
                @Suppress("UNCHECKED_CAST") val emptyTotals = emptyResult["totals"] as Map<String, Any?>
                assertEquals(0.0, emptyTotals[API_FIELD_NODES])

                val (loadCode, loadBody) = put(targetPort, "/api/graphs/service-a", """{"path":"service-a"}""")
                assertEquals(200, loadCode, "Expected 200, body: $loadBody")

                val (detailCode, detailBody) = get(targetPort, "/api/graphs/service-a")
                assertEquals(200, detailCode, "Expected 200, body: $detailBody")
                val detail: Map<String, Any?> = parseJson(detailBody)
                @Suppress("UNCHECKED_CAST") val graph = detail["graph"] as Map<String, Any?>
                assertEquals("service-a", graph[API_FIELD_ID])
                assertEquals(1.0, graph[API_FIELD_NODES])
                assertEquals(0.0, graph[API_FIELD_METHODS])

                val (cypherCode, cypherBody) =
                    post(targetPort, "/api/graphs/service-a/cypher", """{"query":"MATCH (n:IntConstant) RETURN n.value"}""")
                assertEquals(200, cypherCode, "Expected 200, body: $cypherBody")
                assertTrue(cypherBody.contains("101"), "Expected scoped query to hit service-a graph, body: $cypherBody")

                val (queryLoadCode, queryLoadBody) = put(targetPort, "/api/graphs/service-b?path=service-b&loadMode=eager", "")
                assertEquals(200, queryLoadCode, "Expected 200, body: $queryLoadBody")
                assertTrue(queryLoadBody.contains("EAGER"), "Expected query load mode to be honored, body: $queryLoadBody")

                val (invalidScopedCode, invalidScopedBody) = get(targetPort, "/api/graphs/bad%20id")
                assertEquals(400, invalidScopedCode, "Expected 400, body: $invalidScopedBody")
                assertTrue(invalidScopedBody.contains("Invalid graph id"), "Expected invalid id error, body: $invalidScopedBody")

                val (deleteCode, deleteBody) = delete(targetPort, "/api/graphs/service-a")
                assertEquals(204, deleteCode, "Expected 204, body: $deleteBody")
                val (missingAfterDeleteCode, _) = get(targetPort, "/api/graphs/service-a")
                assertEquals(404, missingAfterDeleteCode)

                val (missingDeleteCode, missingDeleteBody) = delete(targetPort, "/api/graphs/service-a")
                assertEquals(404, missingDeleteCode, "Expected 404, body: $missingDeleteBody")

                val (invalidDeleteCode, invalidDeleteBody) = delete(targetPort, "/api/graphs/bad%20id")
                assertEquals(400, invalidDeleteCode, "Expected 400, body: $invalidDeleteBody")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `root graph APIs query all graphs without collisions and preserve graph identity`() {
        val root = Files.createTempDirectory("explore-registry-root-all")
        try {
            saveCollidingGraph(root, "service-a", 101, "from-a")
            saveCollidingGraph(root, "service-b", 202, "from-b")
            val registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED)

            withRegistryApp(registry) { targetPort ->
                registry.load("service-a", Path.of("service-a"))
                registry.load("service-b", Path.of("service-b"))

                val (graphsCode, graphsBody) = get(targetPort, "/api/graphs")
                assertEquals(200, graphsCode, graphsBody)
                val graphRegistry: Map<String, Any?> = parseJson(graphsBody)
                assertEquals(2.0, graphRegistry[API_FIELD_COUNT])
                @Suppress("UNCHECKED_CAST") val totals = graphRegistry["totals"] as Map<String, Any?>
                assertEquals(4.0, totals[API_FIELD_NODES])

                val (resourceCode, resourceBody) = get(targetPort, "/api/resources/shared/config.txt")
                assertEquals(200, resourceCode, resourceBody)
                val resourceEnvelope: Map<String, Any?> = parseJson(resourceBody)
                @Suppress("UNCHECKED_CAST") val resourceGroups = resourceEnvelope[API_FIELD_RESULTS] as List<Map<String, Any?>>
                val resourcesByGraph =
                    resourceGroups.associate { group ->
                        @Suppress("UNCHECKED_CAST") val data = group[API_FIELD_DATA] as Map<String, Any?>
                        group[API_FIELD_GRAPH_ID] as String to data[API_FIELD_CONTENT]
                    }
                assertEquals(mapOf("service-a" to "from-a", "service-b" to "from-b"), resourcesByGraph)

                val query =
                    "MATCH (a:IntConstant), (b:IntConstant) " +
                        "WHERE graphId(a) <> graphId(b) " +
                        "RETURN a.value, b.value, elementId(a), elementId(b)"
                val (cypherCode, cypherBody) = post(targetPort, "/api/cypher", """{"query":"$query"}""")
                assertEquals(200, cypherCode, cypherBody)
                val cypherResult: Map<String, Any?> = parseJson(cypherBody)
                @Suppress("UNCHECKED_CAST") val rows = cypherResult[API_FIELD_ROWS] as List<Map<String, Any?>>
                assertEquals(2, rows.size)
                assertTrue(
                    rows.all {
                        @Suppress("UNCHECKED_CAST") val metadata = it["\$metadata"] as Map<String, Any?>
                        metadata["graphIds"] == listOf("service-a", "service-b")
                    })
                assertEquals(
                    setOf("service-a:7" to "service-b:7", "service-b:7" to "service-a:7"),
                    rows.map { it["elementId(a)"] as String to it["elementId(b)"] as String }.toSet())

                val (selectedCode, selectedBody) =
                    post(targetPort, "/api/cypher/graphs", """{"query":"$query","graphs":["service-a","service-b"]}""")
                assertEquals(200, selectedCode, selectedBody)
                val selectedResult: Map<String, Any?> = parseJson(selectedBody)
                assertEquals("cross-graph", selectedResult[API_PARAM_MODE])
                assertEquals(2.0, selectedResult[API_FIELD_ROW_COUNT])

                val (unscopedCode, unscopedBody) = post(targetPort, "/api/cypher/graphs", """{"query":"MATCH (n) RETURN n"}""")
                assertEquals(400, unscopedCode, unscopedBody)
                assertTrue(unscopedBody.contains("Specify exactly one"), unscopedBody)

                val (crossFanoutOptionCode, crossFanoutOptionBody) =
                    post(targetPort, "/api/cypher/graphs", """{"query":"MATCH (n) RETURN n","allGraphs":true,"includeGraphRows":true}""")
                assertEquals(400, crossFanoutOptionCode, crossFanoutOptionBody)
                assertTrue(crossFanoutOptionBody.contains("fanout mode"), crossFanoutOptionBody)

                val (scopedCode, scopedBody) = get(targetPort, "/api/graphs/service-a/node/7")
                assertEquals(200, scopedCode, scopedBody)
                val scopedNode: Map<String, Any?> = parseJson(scopedBody)
                assertEquals(101.0, scopedNode[API_FIELD_VALUE])
                assertFalse(API_FIELD_GRAPH_ID in scopedNode, "Scoped response remains the direct single-graph shape")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `root all-graph surface returns grouped results for every graph-bound API`() {
        val registry = GraphRegistry(graphDir.parent, GraphStore.LoadMode.MAPPED)

        withRegistryApp(registry) { targetPort ->
            registry.load("service-a", graphDir)
            registry.load("service-b", graphDir)

            val groupedPaths =
                listOf(
                    "/api/annotations?class=com.example.Foo&member=bar",
                    "/api/resources?pattern=**&limit=10",
                    "/api/endpoints?class=com.example.Foo&limit=10",
                    "/api/overview?limit=10")
            groupedPaths.forEach { path ->
                val (code, body) = get(targetPort, path)
                assertEquals(200, code, "$path failed: $body")
                val envelope: Map<String, Any?> = parseJson(body)
                assertEquals(2.0, envelope["graphCount"], "$path was not grouped: $body")
                @Suppress("UNCHECKED_CAST") val groups = envelope[API_FIELD_RESULTS] as List<Map<String, Any?>>
                assertEquals(listOf("service-a", "service-b"), groups.map { it[API_FIELD_GRAPH_ID] })
            }

            listOf("nodes", "call-sites", "methods").forEach { route ->
                assertEquals(404, get(targetPort, "/api/$route").first)
                assertEquals(404, get(targetPort, "/api/graphs/service-a/$route").first)
            }

            val (scopedEndpointsCode, scopedEndpointsBody) = get(targetPort, "/api/graphs/service-a/endpoints?class=com.example.Foo")
            assertEquals(200, scopedEndpointsCode, scopedEndpointsBody)
            val scopedEndpoints: Map<String, Any?> = parseJson(scopedEndpointsBody)
            assertEquals("spring-web", scopedEndpoints["framework"])
            assertFalse("graphCount" in scopedEndpoints)

            listOf("json", "mermaid", "plantuml", "dsl").forEach { format ->
                val (code, body) = get(targetPort, "/api/architecture/c4?level=all&format=$format")
                assertEquals(200, code, "C4 $format failed: $body")
                assertTrue(body.contains("service-a"), "C4 $format must identify service-a")
                assertTrue(body.contains("service-b"), "C4 $format must identify service-b")
            }

            val encodedQuery = java.net.URLEncoder.encode("MATCH (n:IntConstant) RETURN elementId(n)", Charsets.UTF_8)
            val (cypherCode, cypherBody) = get(targetPort, "/api/cypher?query=$encodedQuery&limit=2")
            assertEquals(200, cypherCode, cypherBody)
            val cypher: Map<String, Any?> = parseJson(cypherBody)
            assertEquals(2.0, cypher[API_FIELD_ROW_COUNT])

            assertEquals(404, get(targetPort, "/api/node/1").first)
            assertEquals(404, get(targetPort, "/api/node/1/outgoing").first)
            assertEquals(404, get(targetPort, "/api/node/1/incoming").first)
            assertEquals(404, get(targetPort, "/api/subgraph?center=1").first)
            assertEquals(400, get(targetPort, "/api/annotations?class=com.example.Foo").first)
            assertEquals(404, get(targetPort, "/api/resources/missing.txt").first)
            assertEquals(400, get(targetPort, "/api/architecture/c4?level=invalid").first)
            assertEquals(400, get(targetPort, "/api/architecture/c4?format=invalid").first)
            assertEquals(400, get(targetPort, "/api/cypher").first)

            val (postLoadCode, postLoadBody) = post(targetPort, "/api/graphs/service-c", """{"path":"$graphDir"}""")
            assertEquals(200, postLoadCode, postLoadBody)
        }
    }
}
