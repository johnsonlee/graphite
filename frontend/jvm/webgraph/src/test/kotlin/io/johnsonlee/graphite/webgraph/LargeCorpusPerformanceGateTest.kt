package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.LocalDefinition
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.cypher.query
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.io.Closeable
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import org.junit.Test
import kotlin.io.path.fileSize
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FOUR_GIB_BYTES = 4L * 1024L * 1024L * 1024L
private const val CALL_SITE_INDEX_FILE = "graph.callsite-string-index"
private const val BRANCH_DEFINITIONS_FILE = "graph.branchdefs"
private const val METADATA_FILE = "graph.metadata"

private class DefinitionFingerprint(
    val scopes: Long,
    val sideDefinitions: Long,
    val scopeDigest: ByteArray,
    val tables: Long,
    val tableDefinitions: Long,
    val tableDigest: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is DefinitionFingerprint &&
        scopes == other.scopes && sideDefinitions == other.sideDefinitions && scopeDigest.contentEquals(other.scopeDigest) &&
        tables == other.tables && tableDefinitions == other.tableDefinitions && tableDigest.contentEquals(other.tableDigest)

    override fun hashCode(): Int = scopeDigest.contentHashCode() * 31 + tableDigest.contentHashCode()

    override fun toString(): String =
        "DefinitionFingerprint(scopes=$scopes, sideDefinitions=$sideDefinitions, scopeDigest=${hex(scopeDigest)}, " +
            "tables=$tables, tableDefinitions=$tableDefinitions, tableDigest=${hex(tableDigest)})"

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** Growable list of 64-bit hashes whose sorted content is digested with SHA-256. */
private class LongArrayBuilder {
    private var values = LongArray(1024)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun canonicalDigest(): ByteArray {
        val sorted = values.copyOf(size)
        sorted.sort()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(Long.SIZE_BYTES)
        for (value in sorted) {
            buffer.clear()
            buffer.putLong(value)
            digest.update(buffer.array())
        }
        return digest.digest()
    }
}

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
    val callSiteCount: Long
)

/**
 * Counts measured on SootUp 3.0.1, whose type assigner gives every local a concrete type where 2.0.0
 * left `unknown`: node, edge, method and call-site counts are identical to the 2.0.0 build, and only
 * the string table, hence the persisted size, grows (Tika +6,166, Hive +3,870, Kotlin compiler
 * +10,705 bytes). With call-site ordinals, the `graph.callsite-ordinals` sidecar adds eight bytes
 * per call site, a 44-byte header, 36 bytes of index per block of 256 call sites (the block's first
 * node id and the SHA-256 of its entries, so a mapped graph proves a block on its first touch rather
 * than hash the whole sidecar per mapping) and the 36-byte binding at the end of `graph.metadata`
 * (Tika +8,190,900, Hive +11,754,172, Kotlin compiler +7,512,796 bytes), again with every count unchanged.
 */
private object CorpusBaselines {
    val tika = CorpusBaseline(
        id = "tika",
        coordinate = "org.apache.tika:tika-app:2.9.2",
        jarProperty = "tika.jar.path",
        sha256 = "87e06f88c801fcb2beae5f15e707241edb14da468a154ad78be4e31ff982c3da",
        jarBytes = 60_900_523,
        classCount = 33_128,
        nodeCount = 3_901_103,
        sourceEdgeCount = 4_510_016,
        persistedEdgeCount = 4_353_588,
        methodCount = 312_852,
        callSiteCount = 1_006_172
    )
    val hive = CorpusBaseline(
        id = "hive",
        coordinate = "org.apache.hive:hive-exec:4.0.0",
        jarProperty = "hive.jar.path",
        sha256 = "232d67c5d2ff54806944bb5b7402eaf1ebb81f11dbe4fd51bc5604a8e0c0bdad",
        jarBytes = 84_163_106,
        classCount = 38_999,
        nodeCount = 5_992_914,
        sourceEdgeCount = 6_597_267,
        persistedEdgeCount = 6_376_682,
        methodCount = 404_043,
        callSiteCount = 1_443_886
    )
    val kotlinCompiler = CorpusBaseline(
        id = "kotlin-compiler",
        coordinate = "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21",
        jarProperty = "kotlin.compiler.jar.path",
        sha256 = "9fa8cdd1de0dccffe154c997d423ec6b5f53cd6d9177e3a77a9b0de03fb1bc81",
        jarBytes = 58_272_093,
        classCount = 24_941,
        nodeCount = 3_292_214,
        sourceEdgeCount = 3_906_617,
        persistedEdgeCount = 3_785_858,
        methodCount = 249_669,
        callSiteCount = 922_876
    )
}

private data class GateMeasurement(
    val nodes: Long,
    val sourceEdges: Long,
    val persistedEdges: Long,
    val methods: Long,
    val callSites: Long,
    val persistedBytes: Long,
    val callSiteIndexBytes: Long,
    val branchDefinitionBytes: Long,
    val syntheticIdentities: Long,
    val productionIndexPrepared: Boolean
)


abstract class LargeCorpusGate(private val baseline: CorpusBaseline) {
    @Test(timeout = 240_000)
    fun `build save and mapped reload preserve complete corpus semantics`() {
        val jar = fixtureJar()
        assertEquals(baseline.jarBytes, jar.fileSize(), "Unexpected artifact size for ${baseline.coordinate}")
        assertEquals(baseline.sha256, sha256(jar), "Unexpected artifact checksum for ${baseline.coordinate}")
        JarFile(jar.toFile()).use { archive ->
            assertEquals(baseline.classCount, archive.entries().asSequence().count { it.name.endsWith(".class") }.toLong())
        }
        assertTrue(
            Runtime.getRuntime().maxMemory() <= FOUR_GIB_BYTES,
            "Gate must run with at most 4 GiB heap; maxMemory=${Runtime.getRuntime().maxMemory()}"
        )

        val measurement = runPipeline(jar)
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
    }

    private fun fixtureJar(): Path {
        val configured = System.getProperty(baseline.jarProperty)
        require(!configured.isNullOrBlank()) { "Missing -D${baseline.jarProperty}=<path>" }
        return Path.of(configured).also { require(Files.isRegularFile(it)) { "Fixture JAR not found at $it" } }
    }

    private fun runPipeline(jar: Path): GateMeasurement {
        val output = Files.createTempDirectory("graphite-${baseline.id}-gate")
        var sourceGraph: Graph? = null
        var loadedGraph: Graph? = null
        try {
            sourceGraph = JavaProjectLoader(
                LoaderConfig(
                    buildCallGraph = false,
                    extractAnnotations = false,
                    trackCrossMethodFunctionalDispatch = false
                )
            ).load(jar)

            val productionIndexPrepared = saveWithProductionCallSiteIndex(sourceGraph, output)
            val callSiteIndex = output.resolve(CALL_SITE_INDEX_FILE)
            val callSiteIndexBytes = if (Files.isRegularFile(callSiteIndex)) Files.size(callSiteIndex) else 0L
            if (productionIndexPrepared) {
                assertTrue(callSiteIndexBytes > 0L, "Production save must persist the CallSite index")
            } else {
                assertEquals(0L, callSiteIndexBytes, "Legacy base unexpectedly persisted a CallSite index")
            }

            val nodes = sourceGraph.nodes(Node::class.java).count().toLong()
            val expectedIdentities = sourceGraph.syntheticIdentities()
            assertTrue(expectedIdentities.isNotEmpty(), "A large corpus has synthetic members for ${baseline.id}")
            val edgeCounts = sourceEdgeCounts(sourceGraph)
            val methods = sourceGraph.methodCount() ?: sourceGraph.methods(MethodPattern()).count().toLong()
            val callSites = sourceGraph.nodes(CallSiteNode::class.java).count().toLong()
            val expectedPropertyRows = sourceGraph.query(PROPERTY_QUERY).rows
            val expectedRelationshipRows = sourceGraph.query(RELATIONSHIP_QUERY).rows
            val expectedIndexedRows = sourceGraph.query(CALL_SITE_INDEX_QUERY).rows
            assertTrue(expectedPropertyRows.isNotEmpty(), "Property query must cover ${baseline.id}")
            assertTrue(expectedRelationshipRows.isNotEmpty(), "Relationship query must cover ${baseline.id}")
            val expectedDefinitions = definitionFingerprint(sourceGraph)
            val expectedPositions = positionalDefinitionDigest(
                sourceGraph.branchScopes().map { scope -> scope.trueDefinitions to scope.falseDefinitions }
            )
            closeQuietly(sourceGraph)
            sourceGraph = null

            loadedGraph = GraphStore.loadMapped(output)
            val queryGraph = checkNotNull(loadedGraph)

            val loadedCallSites = queryGraph.query(CALL_SITE_COUNT_QUERY)
            val loadedPropertyRows = queryGraph.query(PROPERTY_QUERY).rows
            val loadedRelationshipRows = queryGraph.query(RELATIONSHIP_QUERY).rows
            val loadedIndexedRows = queryGraph.query(CALL_SITE_INDEX_QUERY).rows
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
            assertEquals(expectedIndexedRows, loadedIndexedRows, "Mapped graph must preserve indexed CallSite results")
            assertEquals(
                edgeCounts.persisted,
                mappedEdges,
                "Mapped graph must preserve the source graph's persistable edge count for ${baseline.id}"
            )
            // First branch-definition access on the mapped graph: reads and verifies the sidecar, materialises
            // every scope and table. This is correctness-only, with no timing or resource sampling.
            val mappedDefinitions = definitionFingerprint(queryGraph)
            assertEquals(expectedDefinitions, mappedDefinitions, "Mapped graph must restore the source graph's definitions")
            val branchDefinitionBytes =
                verifyBranchDefinitions(output, queryGraph, mappedDefinitions, expectedPositions, nodes.toInt())
            assertEquals(expectedIdentities, queryGraph.syntheticIdentities(), "Mapped graph must restore synthetic identities")

            // Keep the individual file sizes in CI evidence before the temporary graph is deleted.
            // A total alone cannot distinguish a changed type table from a changed graph or index.
            val fileSizes = Files.walk(output).use { entries ->
                entries.filter(Files::isRegularFile).toList().associate { path ->
                    output.relativize(path).toString() to Files.size(path)
                }.toSortedMap()
            }
            fileSizes.forEach { (name, size) ->
                println("LARGE_CORPUS_FILE\t${baseline.id}\t$name\t$size")
            }
            val persistedBytes = fileSizes.filterKeys { it != CALL_SITE_INDEX_FILE }.values.sum()
            return GateMeasurement(
                nodes = nodes,
                sourceEdges = edgeCounts.logical,
                persistedEdges = mappedEdges,
                methods = methods,
                callSites = callSites,
                persistedBytes = persistedBytes,
                callSiteIndexBytes = callSiteIndexBytes,
                branchDefinitionBytes = branchDefinitionBytes,
                syntheticIdentities = expectedIdentities.size.toLong(),
                productionIndexPrepared = productionIndexPrepared
            )
        } finally {
            closeQuietly(loadedGraph)
            closeQuietly(sourceGraph)
            output.toFile().deleteRecursively()
        }
    }

    /**
     * Canonical digest of a graph's branch scopes and per-local definition tables: every scope
     * (method, condition, comparison, the true and false branch node sets, ordered true-side writes,
     * ordered false-side writes) and every table (local, ordered writes) is hashed to 64 bits, the
     * hashes are sorted, and SHA-256 is taken over the sorted sequence. Independent of materialisation
     * order, sensitive to a write moved to another scope, side or position, and computable without
     * holding the source and mapped graphs at once. The branch node sets discriminate scopes that test
     * the same local against the same constant in the same method and guard different code; they are
     * hashed order-independently (no sort per scope). Two scopes can still share every key (two
     * sequential `if (p) { x = a } else { x = b }` blocks reuse the same nodes), and the sorted multiset
     * cannot see their side payloads swapped; [positionalDefinitionDigest] covers that case.
     */
    private fun definitionFingerprint(graph: Graph): DefinitionFingerprint {
        val scopeHashes = LongArrayBuilder()
        var sideDefinitions = 0L
        graph.branchScopes().forEach { scope ->
            sideDefinitions += scope.trueDefinitions.size + scope.falseDefinitions.size
            scopeHashes.add(
                mix(
                    methodHash(scope.method),
                    scope.conditionNodeId.value.toLong(),
                    scope.comparison.operator.ordinal.toLong(),
                    scope.comparison.comparandNodeId.value.toLong(),
                    nodeSetHash(scope.trueBranchNodeIds),
                    nodeSetHash(scope.falseBranchNodeIds),
                    definitionsHash(scope.trueDefinitions),
                    definitionsHash(scope.falseDefinitions)
                )
            )
        }
        val tableHashes = LongArrayBuilder()
        var tableDefinitions = 0L
        for ((localId, definitions) in graph.localDefinitions()) {
            tableDefinitions += definitions.size
            tableHashes.add(mix(localId.value.toLong(), definitionsHash(definitions)))
        }
        return DefinitionFingerprint(
            scopes = scopeHashes.size.toLong(),
            sideDefinitions = sideDefinitions,
            scopeDigest = scopeHashes.canonicalDigest(),
            tables = tableHashes.size.toLong(),
            tableDefinitions = tableDefinitions,
            tableDigest = tableHashes.canonicalDigest()
        )
    }

    /**
     * SHA-256 over the side-definition hashes of every branch scope in the order given, mixed with the
     * position. `graph.metadata` is written in the source graph's [Graph.branchScopes] order and the
     * sidecar's scope records are aligned with it by position, so the digest of the source enumeration
     * must equal the digest of the decoded sidecar records: a payload moved between two scopes changes
     * it even when the scopes share every other key, which the sorted multiset in
     * [definitionFingerprint] cannot detect.
     */
    private fun positionalDefinitionDigest(sides: Sequence<Pair<List<LocalDefinition>, List<LocalDefinition>>>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(Long.SIZE_BYTES)
        var position = 0L
        for ((trueSide, falseSide) in sides) {
            buffer.clear()
            buffer.putLong(mix(position++, definitionsHash(trueSide), definitionsHash(falseSide)))
            digest.update(buffer.array())
        }
        return digest.digest()
    }

    private fun methodHash(method: MethodDescriptor): Long =
        mix(method.signature.hashCode().toLong(), method.returnType.className.hashCode().toLong())

    /** Order-independent hash of a node id set: the size and the sum of the mixed ids. */
    private fun nodeSetHash(ids: IntOpenHashSet): Long {
        var sum = 0L
        val iterator = ids.iterator()
        while (iterator.hasNext()) sum += mix(iterator.nextInt().toLong())
        return mix(ids.size.toLong(), sum)
    }

    private fun definitionsHash(definitions: List<LocalDefinition>): Long {
        var hash = 17L
        for (definition in definitions) {
            val constant = (definition.constantNodeId?.value ?: BranchScope.NO_CONSTANT).toLong()
            hash = mix(hash, definition.stmtOrdinal.toLong(), definition.localNodeId.value.toLong(), constant)
        }
        return hash
    }

    private fun mix(vararg values: Long): Long {
        var hash = 0x9E3779B97F4A7C15uL.toLong()
        for (value in values) {
            hash = (hash xor value) * 0xC2B2AE3D27D4EB4FuL.toLong()
            hash = hash xor (hash ushr 29)
        }
        return hash
    }

    /**
     * The sidecar must be written by every save, decode against the trailer `graph.metadata` carries
     * with the exact metadata scope count, hold the source graph's side definitions at the source
     * graph's positions ([expectedPositions]), and be what the mapped graph restored (compared through
     * [fingerprint], already checked against the source graph). It is counted inside the persisted size.
     */
    private fun verifyBranchDefinitions(
        output: Path,
        mapped: Graph,
        fingerprint: DefinitionFingerprint,
        expectedPositions: ByteArray,
        nodeCount: Int
    ): Long {
        val sidecar = output.resolve(BRANCH_DEFINITIONS_FILE)
        assertTrue(Files.isRegularFile(sidecar), "Save must persist the branch-definition sidecar for ${baseline.id}")
        assertTrue(fingerprint.sideDefinitions > 0, "${baseline.id} must persist branch-side definitions")
        assertTrue(fingerprint.tables > 0, "${baseline.id} must persist per-local definition tables")

        // The trailer follows the fixed sections and precedes the synthetic identity section, so it is
        // read the way the loader reads it rather than from the end of the file.
        val trailerDigest = DataInputStream(BufferedInputStream(Files.newInputStream(output.resolve(METADATA_FILE)))).use { metadata ->
            val strings = StringTable.load(output)
            NodeSerializer.readMetadataOptionalSections(metadata, strings, NodeSerializer.loadMetadata(metadata, strings))
                .branchDefinitionDigest
        }
        assertNotNull(trailerDigest, "graph.metadata must carry the branch-definition trailer for ${baseline.id}")
        val bytes = Files.readAllBytes(sidecar)
        val preamble = NodeSerializer.decodeBranchDefinitionPreamble(
            bytes.copyOf(NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES),
            (bytes.size - NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES).toLong(),
            trailerDigest
        )
        val payloadDigest = checkNotNull(preamble.payloadDigest) {
            "Branch-definition sidecar must match graph.metadata for ${baseline.id}: ${preamble.rejection}"
        }
        val decoded = NodeSerializer.decodeBranchDefinitionPayload(
            bytes.copyOfRange(NodeSerializer.BRANCH_DEFINITIONS_PREAMBLE_BYTES, bytes.size),
            fingerprint.scopes.toInt(),
            payloadDigest,
            nodeCount,
            NodeSerializer.NodeTagLookup { nodeId -> mapped.node(NodeId(nodeId))?.let(NodeSerializer::tagOf) ?: NodeSerializer.NO_NODE }
        )
        val definitions = checkNotNull(decoded.definitions) {
            "Branch-definition sidecar must decode for ${baseline.id}: ${decoded.rejection}"
        }
        assertEquals(fingerprint.scopes, definitions.scopes.size.toLong(), "Sidecar scope count for ${baseline.id}")
        val persistedSideDefinitions = definitions.scopes.sumOf { (trueSide, falseSide) ->
            (trueSide.size + falseSide.size) / BranchScope.DEFINITION_STRIDE
        }.toLong()
        assertEquals(fingerprint.sideDefinitions, persistedSideDefinitions, "Sidecar side definitions for ${baseline.id}")
        val persistedPositions = positionalDefinitionDigest(
            definitions.scopes.asSequence().map { (trueSide, falseSide) ->
                BranchScope.unpackDefinitions(trueSide) to BranchScope.unpackDefinitions(falseSide)
            }
        )
        assertTrue(
            expectedPositions.contentEquals(persistedPositions),
            "Sidecar must hold each scope's side definitions at the source graph's position for ${baseline.id}"
        )
        assertEquals(fingerprint.tables, definitions.locals.size.toLong(), "Sidecar definition tables for ${baseline.id}")
        val mappedTables = mapped.localDefinitions()
        definitions.locals.forEach { (localId, packed) ->
            assertEquals(
                BranchScope.unpackDefinitions(packed),
                mappedTables[NodeId(localId)],
                "Mapped definition table of local $localId for ${baseline.id}"
            )
        }
        return Files.size(sidecar)
    }

    /** Uses the shipped production overload when present while remaining source-compatible with main. */
    private fun saveWithProductionCallSiteIndex(graph: Graph, output: Path): Boolean {
        val productionSave = GraphStore::class.java.methods.singleOrNull { method ->
            method.name == "save" && method.parameterTypes.contentEquals(
                arrayOf(Graph::class.java, Path::class.java, Integer.TYPE, java.lang.Boolean.TYPE)
            )
        }
        if (productionSave == null) {
            GraphStore.save(graph, output)
            return false
        }
        productionSave.invoke(GraphStore, graph, output, 2, true)
        return true
    }

    private fun GateMeasurement.baselineLine(baseline: CorpusBaseline): String = listOf(
        "LARGE_CORPUS_CORRECTNESS",
        baseline.id,
        "nodes=$nodes",
        "sourceEdges=$sourceEdges",
        "persistedEdges=$persistedEdges",
        "methods=$methods",
        "callSites=$callSites",
        "persistedBytes=$persistedBytes",
        "callSiteIndexBytes=$callSiteIndexBytes",
        "branchDefinitionBytes=$branchDefinitionBytes",
        "syntheticIdentities=$syntheticIdentities",
        "productionIndexPrepared=${if (productionIndexPrepared) 1 else 0}"
    ).joinToString("\t")

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
        const val CALL_SITE_INDEX_QUERY =
            "MATCH (n:CallSiteNode) WHERE n.caller_class CONTAINS 'java' RETURN count(*) AS count"
    }
}

private data class EdgeCounts(val logical: Long, val persisted: Long)

class TikaCorpusPerformanceGateTest : LargeCorpusGate(CorpusBaselines.tika)

class HiveCorpusPerformanceGateTest : LargeCorpusGate(CorpusBaselines.hive)

class KotlinCompilerCorpusPerformanceGateTest : LargeCorpusGate(CorpusBaselines.kotlinCompiler)
