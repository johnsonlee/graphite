package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
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

internal enum class BenchmarkCorpusKind(
    val id: String,
    val jarPathProperty: String,
    val graphPathProperty: String,
    val expectedNodeCount: Long,
    private val matcher: (String) -> Boolean
) {
    ANDROID(
        id = "android",
        jarPathProperty = "android.jar.path",
        graphPathProperty = "android.graph.path",
        expectedNodeCount = 5_938_826,
        matcher = { it.startsWith("android-all-") && it.endsWith(".jar") }
    ),
    TIKA(
        id = "tika",
        jarPathProperty = "tika.jar.path",
        graphPathProperty = "tika.graph.path",
        expectedNodeCount = 3_897_012,
        matcher = { it == "tika-app-2.9.2.jar" }
    ),
    HIVE(
        id = "hive",
        jarPathProperty = "hive.jar.path",
        graphPathProperty = "hive.graph.path",
        expectedNodeCount = 5_986_673,
        matcher = { it == "hive-exec-4.0.0.jar" }
    ),
    KOTLIN_COMPILER(
        id = "kotlin-compiler",
        jarPathProperty = "kotlin.compiler.jar.path",
        graphPathProperty = "kotlin.compiler.graph.path",
        expectedNodeCount = 3_268_537,
        matcher = { it == "kotlin-compiler-embeddable-2.0.21.jar" }
    );

    fun matches(fileName: String): Boolean = matcher(fileName)
}

internal object BenchmarkCorpus {
    private val preparedGraphs = ConcurrentHashMap<BenchmarkCorpusKind, Path>()
    private val resolvedJars = ConcurrentHashMap<BenchmarkCorpusKind, Path>()

    fun resolveJar(kind: BenchmarkCorpusKind): Path = resolvedJars.computeIfAbsent(kind, ::findJar)

    fun persistedGraph(kind: BenchmarkCorpusKind): Path = preparedGraphs.computeIfAbsent(kind, ::preparePersistedGraph)

    private fun preparePersistedGraph(kind: BenchmarkCorpusKind): Path {
        System.getProperty(kind.graphPathProperty)?.let { configured ->
            val graphPath = Path.of(configured)
            require(graphPath.isDirectory() && hasPersistedGraph(graphPath)) {
                "Persisted graph not found at $graphPath"
            }
            validateGraphIdentity(kind, graphPath)
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

    private fun findJar(kind: BenchmarkCorpusKind): Path {
        System.getProperty(kind.jarPathProperty)?.let { configured ->
            val jarPath = Path.of(configured)
            require(jarPath.isRegularFile()) { "Fixture JAR not found at $jarPath" }
            return jarPath
        }

        findJarOnClasspath(kind)?.let { return it }

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

    private fun findJarOnClasspath(kind: BenchmarkCorpusKind): Path? {
        return System.getProperty("java.class.path")
            .split(System.getProperty("path.separator").toRegex())
            .asSequence()
            .mapNotNull { entry -> entry.takeIf { it.isNotBlank() }?.let(Path::of) }
            .filter { it.isRegularFile() && kind.matches(it.fileName.toString()) }
            .firstOrNull()
    }

    private fun hasPersistedGraph(dir: Path): Boolean {
        return dir.resolve("graph.nodedata").exists() &&
            dir.resolve("graph.metadata").exists() &&
            dir.resolve("graph.nodeindex").exists()
    }

    private fun validateGraphIdentity(kind: BenchmarkCorpusKind, graphPath: Path) {
        val graph = GraphStore.loadMapped(graphPath)
        try {
            val actualNodeCount = graph.nodeCount(Node::class.java)
                ?: graph.nodes(Node::class.java).count().toLong()
            require(actualNodeCount == kind.expectedNodeCount) {
                "${kind.graphPathProperty} has $actualNodeCount nodes; " +
                    "expected ${kind.expectedNodeCount} for ${kind.id}"
            }
        } finally {
            closeQuietly(graph)
        }
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

internal object BenchmarkCorpusPreflight {
    @JvmStatic
    fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
        BenchmarkCorpusKind.entries
            .filter { kind -> System.getProperty(kind.graphPathProperty) != null }
            .forEach(BenchmarkCorpus::persistedGraph)
    }
}
