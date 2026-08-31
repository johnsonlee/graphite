package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarFile
import org.junit.Test
import kotlin.io.path.fileSize
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FOUR_GIB_BYTES = 4L * 1024L * 1024L * 1024L
private const val RECORD_PROPERTY = "large.corpus.record"
private const val MAPPED_LOAD_SAMPLE_COUNT = 5

data class CorpusBaseline(
    val id: String,
    val coordinate: String,
    val jarProperty: String,
    val sha256: String,
    val jarBytes: Long,
    val classCount: Long,
    val nodeCount: Long,
    val sourceEdgeCount: Long,
    val persistedEdgeCount: Long,
    val methodCount: Long,
    val callSiteCount: Long,
    val resourcePath: String,
    val maxPipelineMillis: Long
)

private object CorpusBaselines {
    val tika = CorpusBaseline(
        id = "tika",
        coordinate = "org.apache.tika:tika-app:2.9.2",
        jarProperty = "tika.jar.path",
        sha256 = "87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da",
        jarBytes = 60_900_523,
        classCount = 33_128,
        nodeCount = 3_897_012,
        sourceEdgeCount = 4_497_723,
        persistedEdgeCount = 4_342_382,
        methodCount = 312_788,
        callSiteCount = 1_002_088,
        resourcePath = "log4j2_batch_process.properties",
        maxPipelineMillis = 120_000
    )
    val hive = CorpusBaseline(
        id = "hive",
        coordinate = "org.apache.hive:hive-exec:4.0.0",
        jarProperty = "hive.jar.path",
        sha256 = "232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad",
        jarBytes = 84_163_106,
        classCount = 38_999,
        nodeCount = 5_986_673,
        sourceEdgeCount = 6_378_063,
        persistedEdgeCount = 6_161_463,
        methodCount = 404_016,
        callSiteCount = 1_437_647,
        resourcePath = "hive-exec-log4j2.properties",
        maxPipelineMillis = 180_000
    )
    val kotlinCompiler = CorpusBaseline(
        id = "kotlin-compiler",
        coordinate = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21",
        jarProperty = "kotlin.compiler.jar.path",
        sha256 = "9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81",
        jarBytes = 58_272_093,
        classCount = 24_941,
        nodeCount = 3_268_537,
        sourceEdgeCount = 3_674_711,
        persistedEdgeCount = 3_559_500,
        methodCount = 249_669,
        callSiteCount = 900_366,
        resourcePath = "kotlinManifest.properties",
        maxPipelineMillis = 120_000
    )
}

private data class GateMeasurement(
    val nodes: Long,
    val sourceEdges: Long,
    val persistedEdges: Long,
    val methods: Long,
    val callSites: Long,
    val persistedBytes: Long,
    val buildMillis: Long,
    val saveMillis: Long,
    val mappedLoadSampleCount: Int,
    val mappedLoadMinMillis: Long,
    val mappedLoadMillis: Long,
    val mappedLoadMaxMillis: Long,
    val queryMillis: Long,
    val pipelineMillis: Long,
    val peakHeapBytes: Long
)

abstract class LargeCorpusGate(private val baseline: CorpusBaseline) {
    @Test(timeout = 240_000)
    fun `build save mapped load and query stay within the large corpus gate`() {
        val jar = fixtureJar()
        assertEquals(baseline.jarBytes, jar.fileSize(), "Unexpected artifact size for ${baseline.coordinate}")
        assertEquals(baseline.sha256, sha256(jar), "Unexpected artifact checksum for ${baseline.coordinate}")
        val expectedResource = JarFile(jar.toFile()).use { archive ->
            assertEquals(baseline.classCount, archive.entries().asSequence().count { it.name.endsWith(".class") }.toLong())
            val resource = requireNotNull(archive.getJarEntry(baseline.resourcePath)) {
                "Fixture resource not found in ${baseline.coordinate}: ${baseline.resourcePath}"
            }
            archive.getInputStream(resource).use { it.readBytes() }
        }
        assertTrue(
            Runtime.getRuntime().maxMemory() <= FOUR_GIB_BYTES,
            "Gate must run with at most 4 GiB heap; maxMemory=${Runtime.getRuntime().maxMemory()}"
        )

        val measurement = PeakHeapSampler().use { sampler -> runPipeline(jar, expectedResource, sampler) }
        println(measurement.baselineLine(baseline))

        assertEquals(baseline.nodeCount, measurement.nodes, "Node baseline changed for ${baseline.id}")
        assertEquals(baseline.sourceEdgeCount, measurement.sourceEdges, "Source edge baseline changed for ${baseline.id}")
        assertEquals(
            baseline.persistedEdgeCount,
            measurement.persistedEdges,
            "Persisted edge baseline changed for ${baseline.id}"
        )
        assertEquals(baseline.methodCount, measurement.methods, "Method baseline changed for ${baseline.id}")
        assertEquals(baseline.callSiteCount, measurement.callSites, "Call-site baseline changed for ${baseline.id}")
        if (!java.lang.Boolean.getBoolean(RECORD_PROPERTY)) {
            assertTrue(
                measurement.pipelineMillis <= baseline.maxPipelineMillis,
                "${baseline.id} pipeline took ${measurement.pipelineMillis} ms; ceiling=${baseline.maxPipelineMillis} ms"
            )
        }
    }

    private fun fixtureJar(): Path {
        val configured = System.getProperty(baseline.jarProperty)
        require(!configured.isNullOrBlank()) { "Missing -D${baseline.jarProperty}=<path>" }
        return Path.of(configured).also { require(Files.isRegularFile(it)) { "Fixture JAR not found at $it" } }
    }

    private fun runPipeline(jar: Path, expectedResource: ByteArray, sampler: PeakHeapSampler): GateMeasurement {
        val output = Files.createTempDirectory("graphite-${baseline.id}-gate")
        var sourceGraph: Graph? = null
        var loadedGraph: Graph? = null
        try {
            val buildStart = System.nanoTime()
            sourceGraph = JavaProjectLoader(
                LoaderConfig(
                    buildCallGraph = false,
                    extractAnnotations = false,
                    trackCrossMethodFunctionalDispatch = false
                )
            ).load(jar)
            val buildMillis = elapsedMillis(buildStart)
            assertContentEquals(
                expectedResource,
                sourceGraph.resources.open(baseline.resourcePath).use { it.readBytes() },
                "Source graph must expose ${baseline.resourcePath} from ${baseline.coordinate}"
            )

            val saveStart = System.nanoTime()
            GraphStore.save(sourceGraph, output)
            val saveMillis = elapsedMillis(saveStart)
            assertTrue(Files.isRegularFile(output.resolve("graph.resources")), "Persisted resource store must exist")

            val nodes = sourceGraph.nodes(Node::class.java).count().toLong()
            val edgeCounts = sourceEdgeCounts(sourceGraph)
            val methods = sourceGraph.methodCount() ?: sourceGraph.methods(MethodPattern()).count().toLong()
            val callSites = sourceGraph.nodes(CallSiteNode::class.java).count().toLong()
            val expectedPropertyRows = sourceGraph.query(PROPERTY_QUERY).rows
            val expectedRelationshipRows = sourceGraph.query(RELATIONSHIP_QUERY).rows
            assertTrue(expectedPropertyRows.isNotEmpty(), "Property query must cover ${baseline.id}")
            assertTrue(expectedRelationshipRows.isNotEmpty(), "Relationship query must cover ${baseline.id}")
            closeQuietly(sourceGraph)
            sourceGraph = null

            val mappedLoadSamples = LongArray(MAPPED_LOAD_SAMPLE_COUNT)
            repeat(MAPPED_LOAD_SAMPLE_COUNT) { index ->
                val loadStart = System.nanoTime()
                val sampleGraph = GraphStore.loadMapped(output)
                mappedLoadSamples[index] = elapsedMillis(loadStart)
                if (index == mappedLoadSamples.lastIndex) {
                    loadedGraph = sampleGraph
                } else {
                    closeQuietly(sampleGraph)
                }
            }
            val mappedLoadMillis = mappedLoadSamples.sorted()[mappedLoadSamples.size / 2]
            val queryGraph = checkNotNull(loadedGraph) { "Mapped graph sample was not retained" }
            assertContentEquals(
                expectedResource,
                queryGraph.resources.open(baseline.resourcePath).use { it.readBytes() },
                "Mapped graph must preserve ${baseline.resourcePath} from ${baseline.coordinate}"
            )

            val queryStart = System.nanoTime()
            val loadedCallSites = queryGraph.query(CALL_SITE_COUNT_QUERY)
            val loadedPropertyRows = queryGraph.query(PROPERTY_QUERY).rows
            val loadedRelationshipRows = queryGraph.query(RELATIONSHIP_QUERY).rows
            val queryMillis = elapsedMillis(queryStart)
            val mappedCallSites = (loadedCallSites.rows.single()["count"] as Number).toLong()
            val mappedNodes = queryGraph.nodeCount(Node::class.java)
                ?: queryGraph.nodes(Node::class.java).count().toLong()
            val mappedMethods = queryGraph.methodCount() ?: queryGraph.methods(MethodPattern()).count().toLong()
            val mappedEdges = queryGraph.edgeCount()
                ?: queryGraph.nodes(Node::class.java).sumOf { node -> queryGraph.outgoing(node.id).count().toLong() }
            assertEquals(nodes, mappedNodes, "Mapped graph must preserve node count for ${baseline.id}")
            assertEquals(callSites, mappedCallSites, "Mapped query must preserve call-site count for ${baseline.id}")
            assertEquals(methods, mappedMethods, "Mapped graph must preserve method count for ${baseline.id}")
            assertEquals(expectedPropertyRows, loadedPropertyRows, "Mapped graph must preserve node properties")
            assertEquals(expectedRelationshipRows, loadedRelationshipRows, "Mapped graph must preserve relationships")
            assertEquals(
                edgeCounts.persisted,
                mappedEdges,
                "Mapped graph must preserve the source graph's persistable edge count for ${baseline.id}"
            )

            val persistedBytes = Files.walk(output).use { entries ->
                entries.filter(Files::isRegularFile).mapToLong(Files::size).sum()
            }
            sampler.sample()
            return GateMeasurement(
                nodes = nodes,
                sourceEdges = edgeCounts.logical,
                persistedEdges = mappedEdges,
                methods = methods,
                callSites = callSites,
                persistedBytes = persistedBytes,
                buildMillis = buildMillis,
                saveMillis = saveMillis,
                mappedLoadSampleCount = mappedLoadSamples.size,
                mappedLoadMinMillis = mappedLoadSamples.min(),
                mappedLoadMillis = mappedLoadMillis,
                mappedLoadMaxMillis = mappedLoadSamples.max(),
                queryMillis = queryMillis,
                pipelineMillis = buildMillis + saveMillis + mappedLoadMillis + queryMillis,
                peakHeapBytes = sampler.peakBytes()
            )
        } finally {
            closeQuietly(loadedGraph)
            closeQuietly(sourceGraph)
            output.toFile().deleteRecursively()
        }
    }

    private fun GateMeasurement.baselineLine(baseline: CorpusBaseline): String = listOf(
        "LARGE_CORPUS_BASELINE",
        baseline.id,
        "nodes=$nodes",
        "sourceEdges=$sourceEdges",
        "persistedEdges=$persistedEdges",
        "methods=$methods",
        "callSites=$callSites",
        "persistedBytes=$persistedBytes",
        "buildMs=$buildMillis",
        "saveMs=$saveMillis",
        "mappedLoadSamples=$mappedLoadSampleCount",
        "mappedLoadMinMs=$mappedLoadMinMillis",
        "mappedLoadMs=$mappedLoadMillis",
        "mappedLoadMaxMs=$mappedLoadMaxMillis",
        "queryMs=$queryMillis",
        "pipelineMs=$pipelineMillis",
        "peakHeapBytes=$peakHeapBytes"
    ).joinToString("\t")

    private fun elapsedMillis(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    private fun sourceEdgeCounts(graph: Graph): EdgeCounts {
        var logical = 0L
        var persisted = 0L
        graph.nodes(Node::class.java).forEach { node ->
            var firstTarget: Int? = null
            var additionalTargets: MutableSet<Int>? = null
            graph.outgoing(node.id).forEach { edge ->
                logical++
                val target = edge.to.value
                if (firstTarget == null) {
                    firstTarget = target
                } else {
                    val targets = additionalTargets ?: mutableSetOf(firstTarget!!).also {
                        additionalTargets = it
                    }
                    targets += target
                }
            }
            persisted += additionalTargets?.size ?: if (firstTarget == null) 0 else 1
        }
        return EdgeCounts(logical, persisted)
    }

    private fun closeQuietly(graph: Graph?) {
        runCatching { (graph as? Closeable)?.close() }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CALL_SITE_COUNT_QUERY = "MATCH (n:CallSiteNode) RETURN count(*) AS count"
        const val PROPERTY_QUERY =
            "MATCH (n:CallSiteNode) " +
                "RETURN n.callee_class AS className, n.callee_name AS methodName " +
                "ORDER BY className, methodName LIMIT 20"
        const val RELATIONSHIP_QUERY =
            "MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) " +
                "RETURN DISTINCT c.value AS value, cs.callee_class AS className, cs.callee_name AS methodName " +
                "ORDER BY value, className, methodName LIMIT 20"
    }
}

private data class EdgeCounts(val logical: Long, val persisted: Long)

class TikaCorpusPerformanceGateTest : LargeCorpusGate(CorpusBaselines.tika)

class HiveCorpusPerformanceGateTest : LargeCorpusGate(CorpusBaselines.hive)

class KotlinCompilerCorpusPerformanceGateTest : LargeCorpusGate(CorpusBaselines.kotlinCompiler)

private class PeakHeapSampler : Closeable {
    private val running = AtomicBoolean(true)
    private val peak = AtomicLong(0)
    private val thread = Thread({
        while (running.get()) {
            sample()
            Thread.sleep(10)
        }
    }, "large-corpus-heap-sampler").apply {
        isDaemon = true
        start()
    }

    fun sample() {
        val runtime = Runtime.getRuntime()
        peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
    }

    fun peakBytes(): Long = peak.get()

    override fun close() {
        running.set(false)
        thread.join()
        sample()
    }
}
