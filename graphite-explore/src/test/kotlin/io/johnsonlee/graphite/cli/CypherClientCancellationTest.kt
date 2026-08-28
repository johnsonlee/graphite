package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import org.junit.Ignore
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CypherClientCancellationTest {

    @Test
    @Ignore("Attempt 1: Jetty does not observe an idle async HTTP/1.1 disconnect")
    fun `disconnecting a client stops its query and releases the concurrency permit`() {
        val started = CountDownLatch(1)
        val visited = AtomicInteger()
        val node = IntConstant(NodeId.next(), 1)
        val backing = DefaultGraph.Builder().addNode(node).build()
        val scanningGraph = object : Graph by backing {
            override fun <T : Node> nodes(type: Class<T>): Sequence<T> = sequence {
                if (type.isAssignableFrom(IntConstant::class.java)) {
                    started.countDown()
                    while (true) {
                        visited.incrementAndGet()
                        @Suppress("UNCHECKED_CAST")
                        yield(node as T)
                    }
                }
            }
        }
        val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = Long.MAX_VALUE))
        val app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)

        try {
            routes.register(app, scanningGraph)
            val socket = openBroadQuery(app.port())
            assertTrue(started.await(5, TimeUnit.SECONDS), "The broad query did not start")
            assertTrue(waitUntil { visited.get() >= 1_000 }, "The broad query did not scan candidates")

            socket.setSoLinger(true, 0)
            socket.close()

            assertTrue(
                waitUntil(timeoutMillis = 5_000) { queryCanRun(app.port()) },
                "The disconnected query kept the only concurrency permit"
            )
            val stoppedAt = visited.get()
            Thread.sleep(100)
            assertEquals(stoppedAt, visited.get(), "The disconnected query kept scanning graph candidates")
        } finally {
            app.stop()
        }
    }

    private fun openBroadQuery(port: Int): Socket {
        val body = """{"query":"MATCH (n:IntConstant) WHERE n.value = -1 RETURN n.value LIMIT 1"}"""
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        return Socket("127.0.0.1", port).apply {
            getOutputStream().write(
                buildString {
                    append("POST /api/cypher HTTP/1.1\r\n")
                    append("Host: 127.0.0.1:$port\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Connection: keep-alive\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.US_ASCII)
            )
            getOutputStream().write(bodyBytes)
            getOutputStream().flush()
        }
    }

    private fun queryCanRun(port: Int): Boolean {
        val connection = URI("http://127.0.0.1:$port/api/cypher?query=RETURN%201").toURL()
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        return try {
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            input?.let { InputStreamReader(it).use(InputStreamReader::readText) }
            code == 200
        } finally {
            connection.disconnect()
        }
    }

    private fun waitUntil(timeoutMillis: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
