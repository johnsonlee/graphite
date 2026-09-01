@file:Suppress("MagicNumber", "NestedBlockDepth", "TooManyFunctions")

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile

/**
 * Builds 64 distinct persisted graphs from the four pinned, production-sized fixture JARs.
 *
 * Each source JAR is deterministically partitioned into 16 class-entry shards. This preserves
 * real bytecode and heterogeneous corpus coverage without claiming that the repository contains
 * 64 independent production applications. Synthetic nodes are never used by this preparation.
 */
internal object Fixture64GraphPreparation {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: Fixture64GraphPreparation <output-directory>" }
        val output = Path.of(args.single()).toAbsolutePath().normalize()
        require(Files.notExists(output)) { "Fixture64 output already exists: $output" }
        output.createDirectories()

        val manifest = mutableListOf(
            "# fixture64: 64 distinct class shards from 4 pinned real fixture JARs"
        )
        val provenance = mutableListOf(
            "graphId\tcorpus\tshard\tsourceJar\tsourceJarSha256\tclassCount\tnodeCount\tcallSiteCount" +
                "\tzeroTerm\ttargetedTerm\tdenseTerm\tpersistedGraphSha256\tgraphPath"
        )
        val graphFingerprints = mutableSetOf<String>()
        BenchmarkCorpusKind.entries.forEach { kind ->
            prepareCorpus(kind, output, manifest, provenance, graphFingerprints)
        }
        check(manifest.size == FIXTURE_GRAPH_COUNT + 1)
        check(provenance.size == FIXTURE_GRAPH_COUNT + 1)
        check(graphFingerprints.size == FIXTURE_GRAPH_COUNT) {
            "Fixture partitioning produced duplicate persisted graph content"
        }
        Files.write(output.resolve(MANIFEST_FILE), manifest)
        Files.write(output.resolve(PROVENANCE_FILE), provenance)
    }

    private fun prepareCorpus(
        kind: BenchmarkCorpusKind,
        output: Path,
        manifest: MutableList<String>,
        provenance: MutableList<String>,
        graphFingerprints: MutableSet<String>
    ) {
        val sourceJar = BenchmarkCorpus.resolveJar(kind).toRealPath()
        val sourceHash = sha256(sourceJar)
        val shardRoot = Files.createTempDirectory(output, ".${kind.id}-jar-shards-")
        try {
            val shards = splitClasses(sourceJar, shardRoot)
            shards.forEachIndexed { shardIndex, shard ->
                val graphId = "fixture-${kind.id}-${shardIndex.toString().padStart(2, '0')}"
                val graphPath = output.resolve(graphId)
                buildGraph(shard.path, graphPath).useGraph { graph ->
                    val callSites = graph.nodes(CallSiteNode::class.java).toList()
                    require(callSites.size >= DENSE_RESULT_LIMIT) {
                        "$graphId has only ${callSites.size} call sites; fixture shard is not pressure-sized"
                    }
                    val terms = selectTerms(graphId, callSites)
                    val nodeCount = graph.nodes(Node::class.java).count().toLong()
                    GraphStore.save(graph, graphPath)
                    val persistedPath = graphPath.toRealPath()
                    verifyMappedGraph(persistedPath, nodeCount, callSites.size.toLong())
                    val graphFingerprint = sha256Directory(persistedPath)
                    check(graphFingerprints.add(graphFingerprint)) {
                        "$graphId duplicates persisted graph content $graphFingerprint"
                    }
                    manifest += listOf(
                        graphId,
                        persistedPath,
                        terms.zero,
                        terms.targeted,
                        terms.dense
                    ).joinToString("\t")
                    provenance += listOf(
                        graphId,
                        kind.id,
                        shardIndex,
                        sourceJar,
                        sourceHash,
                        shard.classCount,
                        nodeCount,
                        callSites.size,
                        terms.zero,
                        terms.targeted,
                        terms.dense,
                        graphFingerprint,
                        persistedPath
                    ).joinToString("\t")
                }
                shard.path.deleteIfExists()
            }
        } finally {
            shardRoot.toFile().deleteRecursively()
        }
    }

    private fun splitClasses(source: Path, output: Path): List<FixtureJarShard> {
        val shardPaths = (0 until SHARDS_PER_CORPUS).map { index ->
            output.resolve("shard-${index.toString().padStart(2, '0')}.jar")
        }
        val streams = shardPaths.map { path -> JarOutputStream(Files.newOutputStream(path)) }
        val counts = IntArray(SHARDS_PER_CORPUS)
        try {
            JarFile(source.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.endsWith(CLASS_SUFFIX) }
                    .sortedBy { entry -> entry.name }
                    .forEach { entry ->
                        val shard = stableShard(entry.name)
                        val target = JarEntry(entry.name).apply { time = DETERMINISTIC_ZIP_TIME_MILLIS }
                        streams[shard].putNextEntry(target)
                        jar.getInputStream(entry).use { input -> input.copyTo(streams[shard]) }
                        streams[shard].closeEntry()
                        counts[shard]++
                    }
            }
        } finally {
            streams.forEach { stream -> runCatching { stream.close() } }
        }
        counts.forEachIndexed { index, count ->
            require(count > 0) { "$source produced an empty fixture shard $index" }
        }
        return shardPaths.indices.map { index -> FixtureJarShard(shardPaths[index], counts[index]) }
    }

    private fun stableShard(entryName: String): Int {
        val digest = MessageDigest.getInstance(SHA_256).digest(entryName.toByteArray(Charsets.UTF_8))
        val prefix = ((digest[0].toInt() and 0xff) shl 8) or (digest[1].toInt() and 0xff)
        return prefix % SHARDS_PER_CORPUS
    }

    private fun buildGraph(jar: Path, output: Path): Graph {
        require(jar.isRegularFile()) { "Fixture shard JAR not found: $jar" }
        require(Files.notExists(output)) { "Fixture graph output already exists: $output" }
        return JavaProjectLoader(
            LoaderConfig(
                buildCallGraph = false,
                extractAnnotations = false,
                trackCrossMethodFunctionalDispatch = false
            )
        ).load(jar)
    }

    private fun selectTerms(graphId: String, callSites: List<CallSiteNode>): FixtureSearchTerms {
        val valuesByNode = callSites.map(::searchableValues)
        val frequencies = HashMap<String, Int>()
        valuesByNode.forEach { values ->
            values.forEach { value -> frequencies.compute(value) { _, count -> (count ?: 0) + 1 } }
        }

        val targeted = frequencies.entries.asSequence()
            .filter { (value, count) ->
                value.length >= MIN_TARGETED_TERM_LENGTH && count in TARGETED_RESULT_RANGE && isManifestSafe(value)
            }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key.length }.thenBy { it.key })
            .map(Map.Entry<String, Int>::key)
            .firstOrNull { term -> matchingNodeCount(valuesByNode, term, DENSE_RESULT_LIMIT) in TARGETED_RESULT_RANGE }
            ?: error("Unable to derive a 1..199-row targeted term for $graphId")

        val dense = DENSE_TERM_CANDIDATES.firstOrNull { term ->
            term != targeted && matchingNodeCount(valuesByNode, term, DENSE_RESULT_LIMIT) == DENSE_RESULT_LIMIT
        } ?: frequencies.entries.asSequence()
            .filter { (value, count) ->
                value != targeted && count >= DENSE_RESULT_LIMIT && isManifestSafe(value)
            }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map(Map.Entry<String, Int>::key)
            .firstOrNull { term -> matchingNodeCount(valuesByNode, term, DENSE_RESULT_LIMIT) == DENSE_RESULT_LIMIT }
            ?: error("Unable to derive a 200-row dense term for $graphId")

        val zero = "__graphite_fixture64_absent_${graphId}__"
        check(matchingNodeCount(valuesByNode, zero, 1) == 0)
        return FixtureSearchTerms(zero, targeted, dense)
    }

    private fun searchableValues(node: CallSiteNode): Set<String> = setOf(
        node.caller.declaringClass.className,
        node.caller.name,
        node.callee.declaringClass.className,
        node.callee.name
    ).mapTo(linkedSetOf()) { value -> value.lowercase() }

    private fun matchingNodeCount(valuesByNode: List<Set<String>>, term: String, limit: Int): Int {
        var count = 0
        for (values in valuesByNode) {
            if (values.any { value -> value.contains(term) } && ++count == limit) break
        }
        return count
    }

    private fun isManifestSafe(value: String): Boolean = value.none { character ->
        character == '\t' || character == '\n' || character == '\r'
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance(SHA_256)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun sha256Directory(path: Path): String {
        val digest = MessageDigest.getInstance(SHA_256)
        Files.walk(path).use { entries ->
            entries.filter(Files::isRegularFile)
                .sorted(Comparator.comparing { entry -> path.relativize(entry).toString() })
                .forEach { entry ->
                    digest.update(path.relativize(entry).toString().toByteArray(Charsets.UTF_8))
                    digest.update(0.toByte())
                    Files.newInputStream(entry).use { input ->
                        val buffer = ByteArray(HASH_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun verifyMappedGraph(path: Path, expectedNodes: Long, expectedCallSites: Long) {
        GraphStore.loadMapped(path).useGraph { graph ->
            check(graph.nodes(Node::class.java).count().toLong() == expectedNodes) {
                "$path mapped node count differs from the source graph"
            }
            check(graph.nodes(CallSiteNode::class.java).count().toLong() == expectedCallSites) {
                "$path mapped CallSite count differs from the source graph"
            }
        }
    }

    private inline fun <T> Graph.useGraph(block: (Graph) -> T): T = try {
        block(this)
    } finally {
        (this as? Closeable)?.close()
    }

    private data class FixtureJarShard(val path: Path, val classCount: Int)
    private data class FixtureSearchTerms(val zero: String, val targeted: String, val dense: String)

    private val TARGETED_RESULT_RANGE = 1 until 200
    private val DENSE_TERM_CANDIDATES = listOf("get", "java", "org", "com", "set", "invoke")
    private const val FIXTURE_GRAPH_COUNT = 64
    private const val SHARDS_PER_CORPUS = 16
    private const val DENSE_RESULT_LIMIT = 200
    private const val MIN_TARGETED_TERM_LENGTH = 12
    private const val HASH_BUFFER_BYTES = 64 * 1024
    private const val DETERMINISTIC_ZIP_TIME_MILLIS = 0L
    private const val CLASS_SUFFIX = ".class"
    private const val SHA_256 = "SHA-256"
    private const val MANIFEST_FILE = "graphs.tsv"
    private const val PROVENANCE_FILE = "fixture-provenance.tsv"
}
