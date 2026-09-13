package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.eclipse.jetty.server.AbstractConnector
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CypherCancellationContractTest {

    @Test
    fun `successful response serializer requires rowCount`() {
        val context = Proxy.newProxyInstance(
            Context::class.java.classLoader,
            arrayOf(Context::class.java)
        ) { _, _, _ -> error("Context must not be used before response validation") } as Context

        assertFailsWith<IllegalArgumentException> {
            GsonCypherResponseSerializer().write(
                context,
                mapOf(API_FIELD_ROWS to emptyList<Any>()),
                CypherCancellationSignal()
            )
        }
    }

    @Test
    fun `server-side cancellation is never reported as an empty success`() {
        lateinit var cancellationSignal: CypherCancellationSignal
        cancellationSignal = CypherCancellationSignal { cancellationSignal.cancel() }
        val routes = ExploreRoutes(
            CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = Long.MAX_VALUE),
            cancellationSignalFactory = { cancellationSignal }
        )
        val app = testApp()

        try {
            routes.register(app, DefaultGraph.Builder().build())
            val (status, body) = post(
                app.port(),
                """{"query":"RETURN size(range(1, 5000000)) AS n"}"""
            )

            assertEquals(503, status)
            assertTrue(body.contains("cypher_query_cancelled"), body)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `cancellation during response serialization returns an HTTP error`() {
        lateinit var cancellationSignal: CypherCancellationSignal
        cancellationSignal = CypherCancellationSignal()
        val serializer = GsonCypherResponseSerializer { cancellationSignal.cancel() }
        val routes = ExploreRoutes(
            CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = Long.MAX_VALUE),
            cancellationSignalFactory = { cancellationSignal },
            cypherResponseSerializer = serializer
        )
        val app = testApp()

        try {
            routes.register(app, DefaultGraph.Builder().build())
            val (status, body) = post(
                app.port(),
                """{"query":"RETURN range(1, 10000) AS values"}"""
            )

            assertEquals(503, status)
            assertTrue(body.contains("cypher_query_cancelled"), body)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `active Cypher query outlives the Jetty connection idle timeout`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val node = IntConstant(NodeId.next(), 1)
        val backing = DefaultGraph.Builder().addNode(node).build()
        val blockingGraph = object : Graph by backing {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> = sequence {
                if (type.isAssignableFrom(IntConstant::class.java)) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    @Suppress("UNCHECKED_CAST")
                    yield(node as T)
                }
            }
        }
        val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = Long.MAX_VALUE))
        val app = testApp()
        val executor = Executors.newSingleThreadExecutor()

        try {
            routes.register(app, blockingGraph)
            app.jettyServer().server().connectors
                .filterIsInstance<AbstractConnector>()
                .forEach { it.idleTimeout = TEST_CONNECTION_IDLE_TIMEOUT_MILLIS }
            val response = executor.submit<Pair<Int, String>> {
                post(
                    app.port(),
                    """{"query":"MATCH (n:IntConstant) WHERE n.value + 0 = 1 RETURN n.value LIMIT 1"}"""
                )
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "The query did not start")
            Thread.sleep(TEST_QUERY_DELAY_MILLIS)
            release.countDown()

            val (status, body) = response.get(5, TimeUnit.SECONDS)
            assertEquals(200, status)
            assertTrue(body.contains("\"rowCount\": 1"), body)
        } finally {
            release.countDown()
            executor.shutdownNow()
            app.stop()
        }
    }

    private fun testApp(): Javalin = Javalin.create { config ->
        config.jsonMapper(JavalinGson(GsonBuilder().create()))
    }.start(0)

    private fun post(port: Int, body: String): Pair<Int, String> {
        val connection = URI("http://127.0.0.1:$port/api/cypher").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Connection", "close")
        connection.connectTimeout = 1_000
        connection.readTimeout = 5_000
        connection.doOutput = true
        connection.outputStream.bufferedWriter().use { it.write(body) }

        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
    }
}

private const val TEST_CONNECTION_IDLE_TIMEOUT_MILLIS = 100L
private const val TEST_QUERY_DELAY_MILLIS = 300L
