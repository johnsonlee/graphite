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
 * - Android SDK at path from system property `android.jar.path`
 * - Apache Tika at path from system property `tika.jar.path`
 * - Apache Hive at path from system property `hive.jar.path`
 * - Kotlin compiler at path from system property `kotlin.compiler.jar.path`
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1, jvmArgs = ["-Xmx8g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class GraphBuildBenchmark {

    private lateinit var fixturePaths: Map<String, Path>

    @Setup
    fun setup() {
        fixturePaths = mapOf(
            ANDROID_PROPERTY to resolveFixture(ANDROID_PROPERTY) {
                it.startsWith("android-all-") && it.endsWith(".jar")
            },
            TIKA_PROPERTY to resolveFixture(TIKA_PROPERTY) { it == "tika-app-2.9.2.jar" },
            HIVE_PROPERTY to resolveFixture(HIVE_PROPERTY) { it == "hive-exec-4.0.0.jar" },
            KOTLIN_COMPILER_PROPERTY to resolveFixture(KOTLIN_COMPILER_PROPERTY) {
                it == "kotlin-compiler-embeddable-2.0.21.jar"
            }
        )
    }

    @Benchmark
    fun buildAndroidSdkGraph(): Graph {
        return buildGraph(ANDROID_PROPERTY, LoaderConfig(buildCallGraph = false))
    }

    @Benchmark
    fun buildAndroidSdkGraphEndToEndConfig(): Graph {
        return buildGraph(ANDROID_PROPERTY, endToEndConfig())
    }

    @Benchmark
    fun buildTikaGraphEndToEndConfig(): Graph = buildGraph(TIKA_PROPERTY, endToEndConfig())

    @Benchmark
    fun buildHiveGraphEndToEndConfig(): Graph = buildGraph(HIVE_PROPERTY, endToEndConfig())

    @Benchmark
    fun buildKotlinCompilerGraphEndToEndConfig(): Graph = buildGraph(KOTLIN_COMPILER_PROPERTY, endToEndConfig())

    private fun buildGraph(property: String, config: LoaderConfig): Graph {
        return JavaProjectLoader(config).load(fixturePaths.getValue(property))
    }

    private fun endToEndConfig(): LoaderConfig = LoaderConfig(
        buildCallGraph = false,
        extractAnnotations = false,
        trackCrossMethodFunctionalDispatch = false
    )

    private fun resolveFixture(property: String, matcher: (String) -> Boolean): Path {
        System.getProperty(property)?.let { configured ->
            return Path.of(configured).also { path ->
                require(Files.isRegularFile(path)) { "Fixture JAR not found at $path" }
            }
        }
        val cache = Path.of(System.getProperty("user.home"), ".gradle", "caches")
        require(Files.isDirectory(cache)) { "Gradle cache not found at $cache" }
        Files.walk(cache).use { entries ->
            return entries
                .filter { Files.isRegularFile(it) && matcher(it.fileName.toString()) }
                .findFirst()
                .orElseThrow { IllegalStateException("Fixture JAR for -D$property not found") }
        }
    }

    private companion object {
        const val ANDROID_PROPERTY = "android.jar.path"
        const val TIKA_PROPERTY = "tika.jar.path"
        const val HIVE_PROPERTY = "hive.jar.path"
        const val KOTLIN_COMPILER_PROPERTY = "kotlin.compiler.jar.path"
    }
}
