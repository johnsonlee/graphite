package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * JMH benchmarks for graph building from JAR files.
 *
 * Requires test JARs:
 * - Elasticsearch at path from system property `elasticsearch.jar.path`
 * - Android SDK at path from system property `android.jar.path`
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1, jvmArgs = ["-Xmx8g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class GraphBuildBenchmark {

    private var esJarPath: String? = null
    private var androidJarPath: String? = null

    @Setup
    fun setup() {
        esJarPath = System.getProperty("elasticsearch.jar.path")
        androidJarPath = System.getProperty("android.jar.path")

        // Fall back to finding in gradle cache
        if (esJarPath == null) {
            val cache = Path.of(System.getProperty("user.home"), ".gradle", "caches")
            Files.walk(cache).filter { it.fileName.toString() == "elasticsearch-8.17.0.jar" }.findFirst().ifPresent {
                esJarPath = it.toString()
            }
        }
        if (androidJarPath == null) {
            val cache = Path.of(System.getProperty("user.home"), ".gradle", "caches")
            Files.walk(cache).filter { it.fileName.toString().startsWith("android-all-") && it.fileName.toString().endsWith(".jar") }.findFirst().ifPresent {
                androidJarPath = it.toString()
            }
        }
    }

    @Benchmark
    fun buildElasticsearchGraph(): Graph {
        val jar = esJarPath ?: throw IllegalStateException("ES JAR not found")
        return JavaProjectLoader(LoaderConfig(buildCallGraph = false)).load(Path.of(jar))
    }

    @Benchmark
    fun buildAndroidSdkGraph(): Graph {
        val jar = androidJarPath ?: throw IllegalStateException("Android JAR not found")
        return JavaProjectLoader(LoaderConfig(buildCallGraph = false)).load(Path.of(jar))
    }
}
