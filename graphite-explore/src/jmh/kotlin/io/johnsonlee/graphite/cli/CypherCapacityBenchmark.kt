package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.webgraph.GraphStore
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Fail-closed capacity evidence for the default four-query, one-million-work-unit envelope.
 *
 * Four real Android graph scans are admitted together. While all permits are held, a fifth
 * request must be rejected. One admitted scan is then disconnected mid-flight, the other three
 * must reach the work ceiling, and a small query must succeed through the released permit.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class CypherCapacityBenchmark {
    private lateinit var root: Path
    private lateinit var registry: GraphRegistry
    private lateinit var topology: TopologyService
    private lateinit var app: Javalin
    private lateinit var guard: CypherQueryGuard
    private lateinit var recorder: CypherCapacityBenchmarkRecorder
    private var port: Int = 0

    @Setup
    fun setup() {
        root = Files.createTempDirectory("graphite-cypher-capacity")
        registry = GraphRegistry(root, GraphStore.LoadMode.MAPPED).also {
            it.load(GRAPH_ID, ExplorerBenchmarkCorpus.persistedAndroidGraph(), GraphStore.LoadMode.MAPPED)
        }
        topology = TopologyService(registry, emptyList(), root).also { it.rebuild() }
        recorder = CypherCapacityBenchmarkRecorder()
        guard = CypherQueryGuard(TARGET_CONCURRENCY, TARGET_WORK_BUDGET, recorder)
        app = Javalin.create { config ->
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)
        ExploreRoutes(guard).register(app, registry, topology)
        port = app.port()
    }

    @TearDown
    fun tearDown() {
        runCatching { app.stop() }
        runCatching { guard.close() }
        runCatching { topology.close() }
        runCatching { registry.close() }
        runCatching { root.toFile().deleteRecursively() }
    }

    @Benchmark
    fun fourWorstCaseQueriesRejectCancelAndRecover(counters: CypherCapacityBenchmarkCounters): Long {
        val executor = Executors.newFixedThreadPool(TARGET_CONCURRENCY)
        val sockets = mutableListOf<Socket>()
        val beforeCpu = processCpuTimeNanos()
        val beforeRss = residentSetBytes()
        val startedAt = System.nanoTime()
        try {
            val requests = List(TARGET_CONCURRENCY) {
                openHeavyRequest().also(sockets::add)
            }
            val responses = requests.map { socket ->
                executor.submit(Callable { readResponse(socket) })
            }

            check(waitUntil { recorder.active.get() == TARGET_CONCURRENCY }) {
                "four worst-case queries did not hold all concurrency permits; " +
                    "peak=${recorder.peak.get()} outcomes=${recorder.outcomeSnapshot()}"
            }

            val fifth = request("RETURN 1 AS ok")
            check(fifth.status == HTTP_TOO_MANY_REQUESTS &&
                fifth.body.contains(CYPHER_CONCURRENCY_LIMIT_CODE)) {
                "fifth query was not rejected by concurrency admission: $fifth"
            }

            val cancellationStarted = System.nanoTime()
            sockets.first().setSoLinger(true, 0)
            sockets.first().close()
            check(waitUntil { recorder.count(CypherQueryOutcome.CANCELLED) == 1 }) {
                "disconnected worst-case query was not cancelled: ${recorder.outcomeSnapshot()}"
            }
            counters.cancellationLatencyNanos = System.nanoTime() - cancellationStarted

            val recovery = waitForRecovery()
            validateRecovery(recovery)

            val completed = responses.drop(1).map(Future<CapacityHttpResponse>::get)
            check(completed.size == TARGET_CONCURRENCY - 1 && completed.all {
                it.status == HTTP_TOO_MANY_REQUESTS && it.body.contains(CYPHER_WORK_BUDGET_CODE)
            }) { "worst-case queries did not fail closed at the work budget: $completed" }
            check(recorder.count(CypherQueryOutcome.BUDGET_EXCEEDED) == TARGET_CONCURRENCY - 1) {
                "unexpected query outcomes: ${recorder.outcomeSnapshot()}"
            }

            val afterRss = residentSetBytes()
            counters.processCpuNanos = (processCpuTimeNanos() - beforeCpu).coerceAtLeast(0L)
            counters.residentSetBeforeBytes = beforeRss
            counters.residentSetAfterBytes = afterRss
            counters.residentSetDeltaBytes = (afterRss - beforeRss).coerceAtLeast(0L)
            counters.tailLatencyNanos = recorder.maximumHeavyDurationNanos.get()
            counters.concurrentWallNanos = System.nanoTime() - startedAt
            counters.activePeak = recorder.peak.get().toLong()
            counters.concurrencyRejected = recorder.rejected.get().toLong()
            counters.budgetExceeded = recorder.count(CypherQueryOutcome.BUDGET_EXCEEDED).toLong()
            counters.cancelled = recorder.count(CypherQueryOutcome.CANCELLED).toLong()
            counters.recoverySucceeded = 1
            counters.targetConcurrency = TARGET_CONCURRENCY.toLong()
            counters.targetWorkBudget = TARGET_WORK_BUDGET
            return completed.sumOf { it.body.length.toLong() } + recovery.body.length
        } finally {
            sockets.forEach { socket -> runCatching { socket.close() } }
            executor.shutdownNow()
        }
    }

    private fun openHeavyRequest(): Socket {
        val body = "{\"query\":\"$WORST_CASE_QUERY\"}"
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return Socket("127.0.0.1", port).apply {
            soTimeout = HTTP_TIMEOUT_MS
            getOutputStream().apply {
                write(
                    buildString {
                        append("POST /api/graphs/$GRAPH_ID/cypher HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:$port\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(StandardCharsets.US_ASCII)
                )
                write(bytes)
                flush()
            }
        }
    }

    private fun readResponse(socket: Socket): CapacityHttpResponse {
        val raw = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        val status = raw.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            ?: error("capacity response omitted an HTTP status: $raw")
        return CapacityHttpResponse(status, raw.substringAfter("\r\n\r\n"))
    }

    private fun request(query: String): CapacityHttpResponse {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val connection = URI(
            "http://127.0.0.1:$port/api/graphs/$GRAPH_ID/cypher?query=$encoded"
        ).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = HTTP_TIMEOUT_MS
        connection.readTimeout = HTTP_TIMEOUT_MS
        return try {
            val status = connection.responseCode
            val stream = if (status in HTTP_SUCCESS_RANGE) connection.inputStream else connection.errorStream
            CapacityHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun waitForRecovery(): CapacityHttpResponse {
        var last = CapacityHttpResponse(0, "not attempted")
        check(waitUntil {
            last = request("RETURN 1 AS ok")
            last.status != HTTP_TOO_MANY_REQUESTS || !last.body.contains(CYPHER_CONCURRENCY_LIMIT_CODE)
        }) { "cancelled query did not release its permit: $last" }
        return last
    }

    private fun validateRecovery(response: CapacityHttpResponse) {
        check(response.status == HTTP_OK) { "recovery query returned HTTP ${response.status}: ${response.body}" }
        val body = JsonParser.parseString(response.body).asJsonObject
        check(body.getAsJsonArray("columns").map { it.asString } == listOf("ok")) {
            "recovery query returned unexpected columns: $body"
        }
        val rows = body.getAsJsonArray("rows")
        check(rows.size() == 1 && rows[0].asJsonObject.get("ok").asInt == 1) {
            "recovery query returned unexpected rows: $body"
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ADMISSION_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return condition()
    }

    private fun processCpuTimeNanos(): Long =
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            as? com.sun.management.OperatingSystemMXBean)?.processCpuTime ?: 0L

    private fun residentSetBytes(): Long {
        val status = Path.of("/proc/self/status")
        if (!Files.isRegularFile(status)) return Runtime.getRuntime().totalMemory()
        return Files.readAllLines(status).firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.firstNotNullOfOrNull { it.toLongOrNull() }
            ?.times(BYTES_PER_KIB)
            ?: Runtime.getRuntime().totalMemory()
    }

    private companion object {
        private const val GRAPH_ID = "capacity"
        private const val TARGET_CONCURRENCY = 4
        private const val TARGET_WORK_BUDGET = 1_000_000L
        private const val HTTP_OK = 200
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_TIMEOUT_MS = 120_000
        private const val ADMISSION_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 5L
        private const val BYTES_PER_KIB = 1_024L
        private const val CYPHER_CONCURRENCY_LIMIT_CODE = "cypher_concurrency_limit"
        private const val CYPHER_WORK_BUDGET_CODE = "cypher_work_budget_exceeded"
        private const val WORST_CASE_QUERY =
            "MATCH (n) WHERE n.__graphite_capacity_probe__ IS NOT NULL RETURN n"
        private val HTTP_SUCCESS_RANGE = 200..299
    }
}

@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
open class CypherCapacityBenchmarkCounters {
    @JvmField var processCpuNanos: Long = 0
    @JvmField var residentSetBeforeBytes: Long = 0
    @JvmField var residentSetAfterBytes: Long = 0
    @JvmField var residentSetDeltaBytes: Long = 0
    @JvmField var tailLatencyNanos: Long = 0
    @JvmField var cancellationLatencyNanos: Long = 0
    @JvmField var concurrentWallNanos: Long = 0
    @JvmField var activePeak: Long = 0
    @JvmField var concurrencyRejected: Long = 0
    @JvmField var budgetExceeded: Long = 0
    @JvmField var cancelled: Long = 0
    @JvmField var recoverySucceeded: Long = 0
    @JvmField var targetConcurrency: Long = 0
    @JvmField var targetWorkBudget: Long = 0
}

private class CypherCapacityBenchmarkRecorder : CypherPerformanceRecorder {
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val rejected = AtomicInteger()
    val maximumHeavyDurationNanos = AtomicLong()
    private val outcomes = EnumMap<CypherQueryOutcome, AtomicInteger>(CypherQueryOutcome::class.java).apply {
        CypherQueryOutcome.entries.forEach { put(it, AtomicInteger()) }
    }

    override fun start(): Long {
        val current = active.incrementAndGet()
        peak.accumulateAndGet(current, ::maxOf)
        return System.nanoTime()
    }

    override fun stop(startedAtNanos: Long, outcome: CypherQueryOutcome) {
        val elapsed = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
        if (outcome == CypherQueryOutcome.BUDGET_EXCEEDED || outcome == CypherQueryOutcome.CANCELLED) {
            maximumHeavyDurationNanos.accumulateAndGet(elapsed, ::maxOf)
        }
        checkNotNull(outcomes[outcome]).incrementAndGet()
        active.decrementAndGet()
    }

    override fun reject() {
        rejected.incrementAndGet()
    }

    fun count(outcome: CypherQueryOutcome): Int = checkNotNull(outcomes[outcome]).get()

    fun outcomeSnapshot(): Map<CypherQueryOutcome, Int> = outcomes.mapValues { it.value.get() }
}

private data class CapacityHttpResponse(val status: Int, val body: String)
