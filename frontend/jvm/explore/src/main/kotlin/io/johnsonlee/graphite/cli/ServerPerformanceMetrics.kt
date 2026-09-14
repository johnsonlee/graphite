package io.johnsonlee.graphite.cli

import io.javalin.Javalin
import io.javalin.config.JavalinConfig
import io.javalin.micrometer.MicrometerPlugin
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmCompilationMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmInfoMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal const val METRICS_PATH = "/metrics"
private const val PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
private const val MAX_HTTP_URI_TAG_VALUES = 64
private val PERFORMANCE_TIMER_NAMES = setOf("jetty.server.requests", "graphite.cypher.query.duration")

internal class ServerPerformanceMetrics : Closeable {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val gcMetrics = JvmGcMetrics()

    init {
        registry.config()
            .meterFilter(
                MeterFilter.maximumAllowableTags(
                    "jetty.server.requests",
                    "uri",
                    MAX_HTTP_URI_TAG_VALUES,
                    MeterFilter.deny()
                )
            )
            .meterFilter(PerformanceHistogramFilter)

        ClassLoaderMetrics().bindTo(registry)
        JvmCompilationMetrics().bindTo(registry)
        gcMetrics.bindTo(registry)
        JvmInfoMetrics().bindTo(registry)
        JvmMemoryMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        FileDescriptorMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
    }

    fun configure(config: JavalinConfig) {
        config.registerPlugin(
            MicrometerPlugin { plugin ->
                plugin.registry = registry
                plugin.tagExceptionName = false
                plugin.tagRedirectPaths = false
                plugin.tagNotFoundMappedPaths = false
            }
        )
    }

    fun registerRoutes(app: Javalin) {
        app.get(METRICS_PATH) { ctx ->
            ctx.contentType(PROMETHEUS_CONTENT_TYPE).result(registry.scrape())
        }
    }

    fun cypherRecorder(maxConcurrent: Int): CypherPerformanceRecorder =
        MicrometerCypherPerformanceRecorder(registry, maxConcurrent)

    override fun close() {
        gcMetrics.close()
        registry.close()
    }
}

@Suppress("MagicNumber")
private object PerformanceHistogramFilter : MeterFilter {
    override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig {
        if (id.type != Meter.Type.TIMER || id.name !in PERFORMANCE_TIMER_NAMES) return config
        return DistributionStatisticConfig.builder()
            .serviceLevelObjectives(
                Duration.ofMillis(10).toNanos().toDouble(),
                Duration.ofMillis(50).toNanos().toDouble(),
                Duration.ofMillis(100).toNanos().toDouble(),
                Duration.ofMillis(500).toNanos().toDouble(),
                Duration.ofSeconds(1).toNanos().toDouble(),
                Duration.ofSeconds(5).toNanos().toDouble(),
                Duration.ofSeconds(30).toNanos().toDouble(),
                Duration.ofMinutes(2).toNanos().toDouble()
            )
            .build()
            .merge(config)
    }
}

internal enum class CypherQueryOutcome(val tagValue: String) {
    SUCCESS("success"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout"),
    BUDGET_EXCEEDED("budget_exceeded"),
    FAILED("failed")
}

internal interface CypherPerformanceRecorder {
    fun start(): Long

    fun stop(startedAtNanos: Long, outcome: CypherQueryOutcome)

    fun reject()
}

internal object NoOpCypherPerformanceRecorder : CypherPerformanceRecorder {
    override fun start(): Long = 0L

    override fun stop(startedAtNanos: Long, outcome: CypherQueryOutcome) = Unit

    override fun reject() = Unit
}

private class MicrometerCypherPerformanceRecorder(
    registry: MeterRegistry,
    maxConcurrent: Int
) : CypherPerformanceRecorder {
    private val active = AtomicInteger()
    private val limit = AtomicInteger(maxConcurrent)
    private val rejected = Counter.builder("graphite.cypher.queries.rejected")
        .description("Cypher queries rejected by the concurrency guard")
        .register(registry)
    private val timers = CypherQueryOutcome.entries.associateWith { outcome ->
        Timer.builder("graphite.cypher.query.duration")
            .description("Cypher query execution time")
            .tag("outcome", outcome.tagValue)
            .register(registry)
    }

    init {
        Gauge.builder("graphite.cypher.queries.active", active) { it.get().toDouble() }
            .description("Accepted Cypher queries that have not completed")
            .register(registry)
        Gauge.builder("graphite.cypher.queries.limit", limit) { it.get().toDouble() }
            .description("Maximum concurrent Cypher queries")
            .register(registry)
    }

    override fun start(): Long {
        active.incrementAndGet()
        return System.nanoTime()
    }

    override fun stop(startedAtNanos: Long, outcome: CypherQueryOutcome) {
        val elapsed = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
        requireNotNull(timers[outcome]).record(elapsed, TimeUnit.NANOSECONDS)
        active.decrementAndGet()
    }

    override fun reject() {
        rejected.increment()
    }
}
