package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal enum class CorpusKind(
    val id: String,
    val jarPathProperty: String,
    val graphPathProperty: String,
    private val matcher: (String) -> Boolean
) {
    ELASTICSEARCH(
        id = "elasticsearch",
        jarPathProperty = "elasticsearch.jar.path",
        graphPathProperty = "elasticsearch.graph.path",
        matcher = { it == "elasticsearch-8.17.0.jar" }
    ),
    ANDROID(
        id = "android",
        jarPathProperty = "android.jar.path",
        graphPathProperty = "android.graph.path",
        matcher = { it.startsWith("android-all-") && it.endsWith(".jar") }
    );

    fun matches(fileName: String): Boolean = matcher(fileName)
}

internal object BenchmarkCorpus {
    private val preparedGraphs = ConcurrentHashMap<CorpusKind, Path>()
    private val resolvedJars = ConcurrentHashMap<CorpusKind, Path>()

    fun resolveJar(kind: CorpusKind): Path = resolvedJars.computeIfAbsent(kind, ::findJar)

    fun persistedGraph(kind: CorpusKind): Path = preparedGraphs.computeIfAbsent(kind, ::preparePersistedGraph)

    private fun preparePersistedGraph(kind: CorpusKind): Path {
        System.getProperty(kind.graphPathProperty)?.let { configured ->
            val graphPath = Path.of(configured)
            require(graphPath.isDirectory() && hasPersistedGraph(graphPath)) {
                "Persisted graph not found at $graphPath"
            }
            return graphPath
        }

        val tempDir = Files.createTempDirectory("graphite-${kind.id}-graph")
        registerCleanup(tempDir)
        buildPersistedGraph(resolveJar(kind), tempDir)
        return tempDir
    }

    private fun buildPersistedGraph(jarPath: Path, outputDir: Path) {
        if (hasPersistedGraph(outputDir)) return
        outputDir.toFile().deleteRecursively()
        Files.createDirectories(outputDir)

        val graph = JavaProjectLoader(
            LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )
        ).load(jarPath)
        try {
            GraphStore.save(graph, outputDir)
        } finally {
            closeQuietly(graph)
        }
    }

    private fun findJar(kind: CorpusKind): Path {
        System.getProperty(kind.jarPathProperty)?.let { configured ->
            val jarPath = Path.of(configured)
            require(jarPath.isRegularFile()) { "Fixture JAR not found at $jarPath" }
            return jarPath
        }

        val cacheDir = Path.of(System.getProperty("user.home"), ".gradle", "caches")
        require(cacheDir.isDirectory()) { "Gradle cache not found at $cacheDir" }

        Files.walk(cacheDir, 12).use { matches ->
            return matches
                .filter { Files.isRegularFile(it) && kind.matches(it.fileName.toString()) }
                .sorted(Comparator.comparing<Path, String> { it.toString() }.reversed())
                .findFirst()
                .orElseThrow {
                    IllegalStateException(
                        "Unable to locate ${kind.id} fixture JAR. " +
                            "Set -D${kind.jarPathProperty}=<path> or resolve integration fixtures first."
                    )
                }
        }
    }

    private fun hasPersistedGraph(dir: Path): Boolean {
        return dir.resolve("graph.nodedata").exists() &&
            dir.resolve("graph.metadata").exists() &&
            dir.resolve("graph.nodeindex").exists()
    }

    private fun registerCleanup(dir: Path) {
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { dir.toFile().deleteRecursively() }
        })
    }

    private fun closeQuietly(graph: Graph) {
        runCatching { (graph as? Closeable)?.close() }
    }
}
