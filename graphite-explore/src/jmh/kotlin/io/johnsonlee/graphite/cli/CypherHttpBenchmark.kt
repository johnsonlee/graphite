package io.johnsonlee.graphite.cli

import com.google.gson.GsonBuilder
import io.javalin.Javalin
import io.javalin.json.JavalinGson
import io.johnsonlee.graphite.graph.DefaultGraph
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class CypherHttpBenchmark {
    private lateinit var app: Javalin
    private lateinit var client: HttpClient
    private lateinit var request: HttpRequest

    @Setup(Level.Trial)
    fun setup() {
        app = Javalin.create { config ->
            config.showJavalinBanner = false
            config.jsonMapper(JavalinGson(GsonBuilder().create()))
        }.start(0)
        ExploreRoutes().register(app, DefaultGraph.Builder().build())
        client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build()
        request = HttpRequest.newBuilder()
            .uri(URI("http://127.0.0.1:${app.port()}/api/cypher?query=RETURN%201"))
            .GET()
            .build()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        app.stop()
    }

    @Benchmark
    fun connectedScalarQuery(): Int =
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
}

