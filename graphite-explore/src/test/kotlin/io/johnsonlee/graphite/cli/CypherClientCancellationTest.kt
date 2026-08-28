package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import jakarta.servlet.AsyncContext
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import org.eclipse.jetty.io.Connection
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CypherClientCancellationTest {

    @Test
    fun `resetting a client connection stops its query and releases the concurrency permit`() {
        verifyDisconnect(resetConnection = true)
    }

    @Test
    fun `closing a client connection stops its query and releases the concurrency permit`() {
        verifyDisconnect(resetConnection = false)
    }

    @Test
    fun `resetting a client connection stops graph-free query work`() {
        verifyGraphFreeDisconnect(resetConnection = true)
    }

    @Test
    fun `closing a client connection stops graph-free query work`() {
        verifyGraphFreeDisconnect(resetConnection = false)
    }

    @Test
    fun `slow Cypher query preserves a healthy keep-alive connection`() {
        val routes = ExploreRoutes(CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 1))
        val app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)

        try {
            routes.register(app, DefaultGraph.Builder().build())
            Socket("127.0.0.1", app.port()).use { socket ->
                socket.soTimeout = 5_000
                writeRequest(
                    socket,
                    "POST",
                    "/api/cypher",
                    """{"query":"RETURN size(range(1, 5000000)) AS n"}"""
                )
                val first = readResponse(socket)
                assertEquals(200, first.status)
                assertTrue(first.body.contains("5000000"), first.body)

                writeRequest(socket, "GET", "/openapi.json")
                val second = readResponse(socket)
                assertEquals(200, second.status)
                assertTrue(second.body.contains("\"openapi\""), second.body)
            }
        } finally {
            app.stop()
        }
    }

    @Test
    fun `servlet failures and timeouts cancel the query`() {
        val cancellations = AtomicInteger()
        val listener = CypherCancellationAsyncListener(cancellations::incrementAndGet)
        val registered = arrayListOf<AsyncListener>()
        val asyncContext = proxy<AsyncContext> { methodName, arguments ->
            if (methodName == "addListener") registered += arguments.single() as AsyncListener
            null
        }
        val event = AsyncEvent(asyncContext)

        listener.onStartAsync(event)
        listener.onError(event)
        listener.onTimeout(event)

        assertEquals(listOf<AsyncListener>(listener), registered)
        assertEquals(2, cancellations.get())
    }

    @Test
    fun `closing the Jetty connection cancels the query`() {
        val cancellations = AtomicInteger()
        val listener = CypherCancellationConnectionListener(cancellations::incrementAndGet)

        listener.onClosed(proxy<Connection> { _, _ -> null })

        assertEquals(1, cancellations.get())
    }

    private fun verifyDisconnect(resetConnection: Boolean) {
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

            if (resetConnection) socket.setSoLinger(true, 0)
            val disconnectedAt = System.nanoTime()
            socket.close()

            assertTrue(
                waitUntil(timeoutMillis = MAX_DISCONNECT_LATENCY_MILLIS) { queryCanRun(app.port()) },
                "The disconnected query kept the only concurrency permit"
            )
            val cancellationLatencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - disconnectedAt)
            assertTrue(
                cancellationLatencyMillis < MAX_DISCONNECT_LATENCY_MILLIS,
                "Cancellation took ${cancellationLatencyMillis}ms"
            )
            val stoppedAt = visited.get()
            Thread.sleep(100)
            assertEquals(stoppedAt, visited.get(), "The disconnected query kept scanning graph candidates")
        } finally {
            app.stop()
        }
    }

    private fun verifyGraphFreeDisconnect(resetConnection: Boolean) {
        val performance = DisconnectPerformanceRecorder()
        val routes = ExploreRoutes(
            CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 1, performance = performance)
        )
        val app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)

        try {
            routes.register(app, DefaultGraph.Builder().build())
            val socket = Socket("127.0.0.1", app.port())
            writeRequest(
                socket,
                "POST",
                "/api/cypher",
                """{"query":"UNWIND range(1, 10000000) AS x RETURN x LIMIT 1"}"""
            )
            assertTrue(performance.started.await(1, TimeUnit.SECONDS), "The graph-free query was not accepted")

            if (resetConnection) socket.setSoLinger(true, 0)
            socket.close()

            assertEquals(
                CypherQueryOutcome.CANCELLED,
                performance.outcomes.poll(MAX_DISCONNECT_LATENCY_MILLIS, TimeUnit.MILLISECONDS),
                "The disconnected graph-free query was not cancelled"
            )
            assertTrue(
                waitUntil(timeoutMillis = MAX_DISCONNECT_LATENCY_MILLIS) { queryCanRun(app.port()) },
                "The disconnected graph-free query kept the concurrency permit"
            )
        } finally {
            app.stop()
        }
    }

    private fun openBroadQuery(port: Int): Socket {
        val body = """{"query":"MATCH (n:IntConstant) WHERE n.value = -1 RETURN n.value LIMIT 1"}"""
        return Socket("127.0.0.1", port).apply {
            writeRequest(this, "POST", "/api/cypher", body)
        }
    }

    private fun writeRequest(socket: Socket, method: String, path: String, body: String? = null) {
        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        socket.getOutputStream().write(
            buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:${socket.port}\r\n")
                if (body != null) append("Content-Type: application/json\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: keep-alive\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
        )
        socket.getOutputStream().write(bodyBytes)
        socket.getOutputStream().flush()
    }

    private fun readResponse(socket: Socket): RawHttpResponse {
        val input = socket.getInputStream()
        val statusLine = readAsciiLine(input) ?: error("Connection closed before HTTP response")
        val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid status line: $statusLine")
        val headers = buildMap {
            while (true) {
                val line = readAsciiLine(input) ?: error("Connection closed while reading HTTP headers")
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                require(separator > 0) { "Invalid HTTP header: $line" }
                put(line.substring(0, separator).lowercase(), line.substring(separator + 1).trim())
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull()
            ?: error("Response has no Content-Length: $headers")
        val body = input.readNBytes(contentLength)
        check(body.size == contentLength) { "Connection closed while reading HTTP body" }
        return RawHttpResponse(status, body.toString(StandardCharsets.UTF_8))
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.US_ASCII)
            if (value == '\n'.code) {
                val line = bytes.toByteArray()
                val length = if (line.lastOrNull() == '\r'.code.toByte()) line.size - 1 else line.size
                return line.copyOf(length).toString(StandardCharsets.US_ASCII)
            }
            bytes.write(value)
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

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline invoke: (methodName: String, arguments: List<Any?>) -> Any?
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
        invoke(method.name, arguments?.toList().orEmpty())
    } as T
}

private data class RawHttpResponse(val status: Int, val body: String)

private class DisconnectPerformanceRecorder : CypherPerformanceRecorder {
    val started = CountDownLatch(1)
    val outcomes = LinkedBlockingQueue<CypherQueryOutcome>()

    override fun start(): Long {
        started.countDown()
        return System.nanoTime()
    }

    override fun stop(startedAtNanos: Long, outcome: CypherQueryOutcome) {
        outcomes.offer(outcome)
    }

    override fun reject() = Unit
}

private const val MAX_DISCONNECT_LATENCY_MILLIS = 2_000L
