package io.johnsonlee.graphite.cli

import io.javalin.Javalin
import io.johnsonlee.graphite.cypher.CypherBudgetExceededException
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerPerformanceMetricsTest {

    @Test
    fun `metrics endpoint exposes runtime and route-template HTTP metrics`() {
        val metrics = ServerPerformanceMetrics()
        val app = Javalin.create { config ->
            config.showJavalinBanner = false
            metrics.configure(config)
        }
        metrics.registerRoutes(app)
        app.get("/work/{id}") { ctx -> ctx.result("ok") }
        app.start(0)

        try {
            val client = HttpClient.newHttpClient()
            val workResponse = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${app.port()}/work/dynamic-id")).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            val metricsResponse = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${app.port()}$METRICS_PATH")).build(),
                HttpResponse.BodyHandlers.ofString()
            )

            assertEquals(200, workResponse.statusCode())
            assertEquals("ok", workResponse.body())
            assertEquals(200, metricsResponse.statusCode())
            assertTrue(metricsResponse.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
            assertTrue(metricsResponse.body().contains("jvm_memory_used_bytes"))
            assertTrue(metricsResponse.body().contains("process_uptime_seconds"))
            assertTrue(metricsResponse.body().contains("jetty_threads_busy"))
            assertTrue(metricsResponse.body().contains("jetty_server_requests_seconds_count"))
            assertTrue(metricsResponse.body().contains("jetty_server_requests_seconds_bucket"))
            assertTrue(metricsResponse.body().contains("uri=\"/work/{id}\""))
            assertFalse(metricsResponse.body().contains("dynamic-id"))
        } finally {
            app.stop()
            metrics.close()
        }
    }

    @Test
    fun `cypher metrics record bounded performance outcomes`() {
        val metrics = ServerPerformanceMetrics()
        val guard = CypherQueryGuard(
            maxConcurrent = 1,
            maxWorkUnits = 10,
            performance = metrics.cypherRecorder(maxConcurrent = 1)
        )
        val cancellation = CypherCancellationSignal()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val task = guard.submit(cancellation) {
            started.countDown()
            release.await()
        }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(1.0, metrics.registry.get("graphite.cypher.queries.active").gauge().value())
            assertFailsWith<CypherConcurrencyLimitException> {
                guard.execute { error("must not run") }
            }

            task.cancel()
            release.countDown()
            assertFailsWith<CypherQueryCancelledException> { task.completion.get(5, TimeUnit.SECONDS) }
            assertEquals("ok", guard.execute { "ok" })
            assertFailsWith<CypherBudgetExceededException> {
                guard.execute { throw CypherBudgetExceededException(it.executionBudget.maxWorkUnits) }
            }
            assertFailsWith<IllegalStateException> {
                guard.execute { error("query failed") }
            }

            assertEquals(0.0, metrics.registry.get("graphite.cypher.queries.active").gauge().value())
            assertEquals(1.0, metrics.registry.get("graphite.cypher.queries.limit").gauge().value())
            assertEquals(1.0, metrics.registry.get("graphite.cypher.queries.rejected").counter().count())
            assertTimerCount(metrics, CypherQueryOutcome.SUCCESS, 1)
            assertTimerCount(metrics, CypherQueryOutcome.CANCELLED, 1)
            assertTimerCount(metrics, CypherQueryOutcome.BUDGET_EXCEEDED, 1)
            assertTimerCount(metrics, CypherQueryOutcome.FAILED, 1)
            assertTrue(metrics.registry.scrape().contains("graphite_cypher_query_duration_seconds_bucket"))
        } finally {
            release.countDown()
            guard.close()
            metrics.close()
        }
    }

    private fun assertTimerCount(metrics: ServerPerformanceMetrics, outcome: CypherQueryOutcome, expected: Long) {
        val count = metrics.registry.get("graphite.cypher.query.duration")
            .tag("outcome", outcome.tagValue)
            .timer()
            .count()
        assertEquals(expected, count)
    }
}
