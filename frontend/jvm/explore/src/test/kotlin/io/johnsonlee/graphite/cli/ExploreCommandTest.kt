package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.cli.ExploreCommandTestSupport.Companion.port
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("DEPRECATION")
internal class ExploreCommandTest : ExploreCommandTestSupport() {
    @Test
    fun `cypher endpoint returns 429 when work budget is exceeded`() {
        val budgetGraph = DefaultGraph.Builder().addNode(IntConstant(NodeId.next(), 1)).addNode(IntConstant(NodeId.next(), 2)).build()
        val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 1))

        withExploreApp(budgetGraph, routes) { targetPort ->
            val (limitedCode, limitedBody) = post(targetPort, "/api/cypher", """{"query":"MATCH (n) RETURN n.id"}""")
            assertEquals(429, limitedCode, limitedBody)
            assertTrue(limitedBody.contains("cypher_work_budget_exceeded"), limitedBody)

            val (metadataCode, metadataBody) =
                post(
                    targetPort,
                    "/api/cypher",
                    """{"query":"MATCH (n) UNWIND labels(n) AS label RETURN label, count(*) AS c ORDER BY c DESC LIMIT 50"}""")
            assertEquals(200, metadataCode, metadataBody)
        }
    }

    @Test
    fun `serve command accepts but ignores deprecated cypher work budget`() {
        val graph = DefaultGraph.Builder().addNode(IntConstant(NodeId.next(), 1)).addNode(IntConstant(NodeId.next(), 2)).build()
        val localApp = Javalin.create { config -> config.jsonMapper(JavalinGson(GsonBuilder().setPrettyPrinting().create())) }.start(0)
        try {
            ServeCommand().apply {
                cypherWorkBudget = 1
                registerApiRoutes(localApp, graph)
            }
            val (code, body) = post(localApp.port(), "/api/cypher", """{"query":"MATCH (n) RETURN n.id"}""")
            assertEquals(200, code, body)
            val response: Map<String, Any?> = parseJson(body)
            assertEquals(2.0, response[API_FIELD_ROW_COUNT])
        } finally {
            localApp.stop()
        }
    }

    @Test
    fun `cypher endpoint caps client timeout cancels work and releases capacity`() {
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val blockFirstScan = AtomicBoolean(true)
        val backing = DefaultGraph.Builder().addNode(IntConstant(NodeId.next(), 1)).build()
        val blockingGraph =
            object : Graph by backing {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = sequence {
                    if (blockFirstScan.compareAndSet(true, false)) {
                        started.countDown()
                        try {
                            CountDownLatch(1).await()
                        } catch (error: InterruptedException) {
                            interrupted.countDown()
                            throw error
                        }
                    }
                    yieldAll(backing.nodes(type))
                }
            }
        val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = Long.MAX_VALUE, maxTimeoutMillis = 50))

        withExploreApp(blockingGraph, routes) { targetPort ->
            val (timeoutCode, timeoutBody) =
                post(targetPort, "/api/cypher", """{"query":"MATCH (n) WHERE n.missing IS NOT NULL RETURN n.id","timeoutMs":500}""")
            assertEquals(504, timeoutCode, timeoutBody)
            assertTrue(timeoutBody.contains("cypher_query_timeout"), timeoutBody)
            val timeoutResponse: Map<String, Any?> = parseJson(timeoutBody)
            assertEquals(50.0, timeoutResponse[API_PARAM_TIMEOUT_MILLIS])
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))

            val (nextCode, nextBody) = post(targetPort, "/api/cypher", """{"query":"MATCH (n) RETURN n.id LIMIT 1"}""")
            assertEquals(200, nextCode, nextBody)
        }
    }

    @Test
    fun `cypher endpoint rejects invalid timeout before consuming capacity`() {
        val (invalidCode, invalidBody) = post("/api/cypher", """{"query":"RETURN 1","timeoutMs":0}""")
        assertEquals(400, invalidCode, invalidBody)
        assertTrue(invalidBody.contains("timeoutMs"), invalidBody)

        val (nextCode, nextBody) = post("/api/cypher", """{"query":"RETURN 1"}""")
        assertEquals(200, nextCode, nextBody)
    }

    @Test
    fun `cypher endpoint rejects excess concurrent queries`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backing = DefaultGraph.Builder().addNode(IntConstant(NodeId.next(), 1)).build()
        val blockingGraph =
            object : Graph by backing {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = sequence {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    yieldAll(backing.nodes(type))
                }
            }
        val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10))
        val executor = Executors.newSingleThreadExecutor()

        try {
            withExploreApp(blockingGraph, routes) { targetPort ->
                val first =
                    executor.submit<Pair<Int, String>> { post(targetPort, "/api/cypher", """{"query":"MATCH (n) RETURN n.id LIMIT 1"}""") }
                assertTrue(started.await(5, TimeUnit.SECONDS))

                val (rejectedCode, rejectedBody) = post(targetPort, "/api/cypher", """{"query":"MATCH (n) RETURN n.id LIMIT 1"}""")
                assertEquals(429, rejectedCode, rejectedBody)
                assertTrue(rejectedBody.contains("cypher_concurrency_limit"), rejectedBody)

                release.countDown()
                val (firstCode, firstBody) = first.get(5, TimeUnit.SECONDS)
                assertEquals(200, firstCode, firstBody)
            }
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `POST api cypher missing query returns 400`() {
        val (code, _) = post("/api/cypher", """{}""")
        assertEquals(400, code)
    }

    @Test
    fun `GET api cypher missing query returns 400`() {
        val (code, _) = get("/api/cypher")
        assertEquals(400, code)
    }
}
