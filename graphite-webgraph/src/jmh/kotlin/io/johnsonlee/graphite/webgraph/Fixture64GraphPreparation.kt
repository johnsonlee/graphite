@file:Suppress("MagicNumber", "NestedBlockDepth", "TooManyFunctions")

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.input.LoaderConfig
import io.johnsonlee.graphite.input.ResourceAccessor
import io.johnsonlee.graphite.sootup.JavaProjectLoader
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile

/**
 * Builds 64 distinct persisted graphs from the four pinned, production-sized fixture JARs.
 *
 * Each source JAR is deterministically partitioned into 16 class-and-resource shards. This
 * preserves real bytecode/resources and heterogeneous corpus coverage without claiming that the
 * repository contains 64 independent production applications. Synthetic nodes are never used.
 */
internal object Fixture64GraphPreparation {
    @JvmStatic
    fun main(args: Array<String>) {
        when {
            args.size == 1 && args[0] == ORDER_FINGERPRINT_SELF_TEST -> verifyOrderSensitiveFingerprint()
            args.size == 1 -> prepare(Path.of(args.single()))
            args.size == 3 && args[0] == VERIFY_COMMAND -> verifyPreparedCorpus(
                Path.of(args[1]),
                Path.of(args[2])
            )
            else -> error(
                "Usage: Fixture64GraphPreparation <output-directory> | " +
                    "Fixture64GraphPreparation --verify <graphs.tsv> <fixture-provenance.tsv> | " +
                    "Fixture64GraphPreparation $ORDER_FINGERPRINT_SELF_TEST"
            )
        }
    }

    private fun prepare(outputArgument: Path) {
        val output = outputArgument.toAbsolutePath().normalize()
        require(Files.notExists(output)) { "Fixture64 output already exists: $output" }
        output.createDirectories()

        val manifest = mutableListOf(
            "# fixture64: 64 distinct class shards from 4 pinned real fixture JARs"
        )
        val provenance = mutableListOf(
            PROVENANCE_HEADER
        )
        val graphFingerprints = mutableSetOf<String>()
        BenchmarkCorpusKind.entries.forEach { kind ->
            prepareCorpus(kind, output, manifest, provenance, graphFingerprints)
        }
        check(manifest.size == FIXTURE_GRAPH_COUNT + 1)
        check(provenance.size == FIXTURE_GRAPH_COUNT + 1)
        check(graphFingerprints.size == FIXTURE_GRAPH_COUNT) {
            "Fixture partitioning produced duplicate query-semantic graph content"
        }
        val manifestRows = manifest.drop(1).map(::parseManifestLine)
        manifest += deriveGlobalWideDistributions(manifestRows).map(FixtureWideDistribution::manifestLine)
        Files.write(output.resolve(MANIFEST_FILE), manifest)
        Files.write(output.resolve(PROVENANCE_FILE), provenance)
        verifyPreparedCorpus(output.resolve(MANIFEST_FILE), output.resolve(PROVENANCE_FILE))
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
            val shards = splitFixtureJar(sourceJar, shardRoot)
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
                    val querySemanticSha256 = querySemanticSha256(nodeCount, callSites)
                    val sourceResources = resourceSemanticSummary(graph.resources)
                    require(sourceResources == shard.resources && sourceResources.count > 0) {
                        "$graphId source resources do not match its fixture JAR shard"
                    }
                    val workloadIdentity = workloadIdentitySha256(
                        graphId,
                        kind.id,
                        shardIndex,
                        terms,
                        querySemanticSha256,
                        sourceResources
                    )
                    GraphStore.save(graph, graphPath, prepareCallSiteStringIndex = true)
                    val callSiteIndex = graphPath.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
                    require(Files.isRegularFile(callSiteIndex)) {
                        "$graphId did not persist its CallSite query index during fixture construction"
                    }
                    val callSiteIndexBytes = Files.size(callSiteIndex)
                    val callSiteIndexSha256 = sha256(callSiteIndex)
                    val persistedPath = graphPath.toRealPath()
                    verifyMappedGraph(
                        persistedPath,
                        nodeCount,
                        callSites.size.toLong(),
                        querySemanticSha256,
                        sourceResources,
                        callSiteIndexBytes,
                        callSiteIndexSha256
                    )
                    check(graphFingerprints.add(querySemanticSha256)) {
                        "$graphId duplicates query-semantic graph content $querySemanticSha256"
                    }
                    manifest += listOf(
                        graphId,
                        persistedPath,
                        terms.zero,
                        terms.targeted,
                        terms.dense,
                        workloadIdentity
                    ).joinToString("\t")
                    provenance += listOf(
                        graphId,
                        kind.id,
                        shardIndex,
                        sourceJar.fileName,
                        sourceHash,
                        shard.bytecodeSha256,
                        shard.classCount,
                        nodeCount,
                        callSites.size,
                        terms.zero,
                        terms.targeted,
                        terms.dense,
                        querySemanticSha256,
                        sourceResources.count,
                        sourceResources.sha256,
                        workloadIdentity,
                        callSiteIndexBytes,
                        callSiteIndexSha256,
                        persistedPath
                    ).joinToString("\t")
                }
                shard.path.deleteIfExists()
            }
        } finally {
            shardRoot.toFile().deleteRecursively()
        }
    }

    private fun splitFixtureJar(source: Path, output: Path): List<FixtureJarShard> {
        val shardPaths = (0 until SHARDS_PER_CORPUS).map { index ->
            output.resolve("shard-${index.toString().padStart(2, '0')}.jar")
        }
        val streams = shardPaths.map { path -> JarOutputStream(Files.newOutputStream(path)) }
        val counts = IntArray(SHARDS_PER_CORPUS)
        val digests = Array(SHARDS_PER_CORPUS) { newShardDigest() }
        val resourceCounts = IntArray(SHARDS_PER_CORPUS)
        val resourceDigests = Array(SHARDS_PER_CORPUS) { newResourceDigest() }
        try {
            JarFile(source.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.endsWith(CLASS_SUFFIX) }
                    .sortedBy { entry -> entry.name }
                    .forEach { entry ->
                        val shard = stableShard(entry.name)
                        val bytes = jar.getInputStream(entry).use { input -> input.readBytes() }
                        updateFrame(digests[shard], entry.name.toByteArray(Charsets.UTF_8))
                        updateFrame(digests[shard], bytes)
                        val target = JarEntry(entry.name).apply { time = DETERMINISTIC_ZIP_TIME_MILLIS }
                        streams[shard].putNextEntry(target)
                        streams[shard].write(bytes)
                        streams[shard].closeEntry()
                        counts[shard]++
                    }
                jar.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && shouldPersistResource(entry.name) }
                    .sortedBy(JarEntry::getName)
                    .distinctBy(JarEntry::getName)
                    .forEachIndexed { resourceIndex, entry ->
                        val shard = resourceIndex % SHARDS_PER_CORPUS
                        val bytes = jar.getInputStream(entry).use { input -> input.readBytes() }
                        val sourceName = shardPaths[shard].fileName.toString()
                        updateResourceDigest(resourceDigests[shard], entry.name, sourceName, bytes)
                        val target = JarEntry(entry.name).apply { time = DETERMINISTIC_ZIP_TIME_MILLIS }
                        streams[shard].putNextEntry(target)
                        streams[shard].write(bytes)
                        streams[shard].closeEntry()
                        resourceCounts[shard]++
                    }
            }
        } finally {
            streams.forEach { stream -> runCatching { stream.close() } }
        }
        counts.forEachIndexed { index, count ->
            require(count > 0) { "$source produced an empty fixture shard $index" }
            require(resourceCounts[index] > 0) { "$source produced a fixture shard without resources: $index" }
        }
        return shardPaths.indices.map { index ->
            FixtureJarShard(
                shardPaths[index],
                counts[index],
                digests[index].hexDigest(),
                ResourceSemanticSummary(resourceCounts[index], resourceDigests[index].hexDigest())
            )
        }
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
        val lowercaseValuesByNode = valuesByNode.map { values ->
            values.mapTo(linkedSetOf(), String::lowercase)
        }
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
            .firstOrNull { term ->
                matchingNodeCount(valuesByNode, term, DENSE_RESULT_LIMIT) in TARGETED_RESULT_RANGE &&
                    matchingNodeCount(lowercaseValuesByNode, term.lowercase(), DENSE_RESULT_LIMIT) in
                    TARGETED_RESULT_RANGE
            }
            ?: error("Unable to derive a 1..199-row targeted term for $graphId")

        val dense = DENSE_TERM_CANDIDATES.firstOrNull { term ->
            term != targeted && matchingNodeCount(valuesByNode, term, DENSE_RESULT_LIMIT) == DENSE_RESULT_LIMIT &&
                matchingNodeCount(lowercaseValuesByNode, term.lowercase(), DENSE_RESULT_LIMIT) == DENSE_RESULT_LIMIT
        } ?: frequencies.entries.asSequence()
            .filter { (value, count) ->
                value != targeted && count >= DENSE_RESULT_LIMIT && isManifestSafe(value)
            }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map(Map.Entry<String, Int>::key)
            .firstOrNull { term ->
                matchingNodeCount(valuesByNode, term, DENSE_RESULT_LIMIT) == DENSE_RESULT_LIMIT &&
                    matchingNodeCount(lowercaseValuesByNode, term.lowercase(), DENSE_RESULT_LIMIT) == DENSE_RESULT_LIMIT
            }
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
    )

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

    /**
     * Derive distribution cases from the persisted fixture graphs themselves. These records are
     * comments so the six-column graph manifest remains backwards compatible, while benchmark
     * and verification code can require their exact contents.
     */
    private fun deriveGlobalWideDistributions(
        rows: List<FixtureManifestRow>
    ): List<FixtureWideDistribution> {
        require(rows.size == FIXTURE_GRAPH_COUNT)
        val summaries = rows.map { row ->
            GraphStore.loadMapped(row.graphPath).useGraph { graph ->
                val frequencies = HashMap<String, Int>()
                val lowercaseValues = linkedSetOf<String>()
                graph.nodes(CallSiteNode::class.java).forEach { node ->
                    searchableValues(node).forEach { value ->
                        frequencies.compute(value) { _, count -> (count ?: 0) + 1 }
                        lowercaseValues += value.lowercase()
                    }
                }
                FixtureSearchSummary(
                    row.graphId,
                    lowercaseValues,
                    frequencies.entries.asSequence()
                        .filter { (value, count) ->
                            value.length >= MIN_LOCALIZED_DENSE_TERM_LENGTH &&
                                count >= DENSE_RESULT_LIMIT && isManifestSafe(value)
                        }
                        .sortedWith(
                            compareByDescending<Map.Entry<String, Int>> { entry -> entry.key.length }
                                .thenBy { entry -> entry.key }
                        )
                        .take(MAX_LOCALIZED_DENSE_CANDIDATES)
                        .map { entry -> entry.key }
                        .toList()
                )
            }
        }
        fun hitGraphIds(term: String): List<String> {
            val expected = term.lowercase()
            return summaries.filter { summary ->
                summary.lowercaseValues.any { value -> expected in value }
            }.map(FixtureSearchSummary::graphId)
        }
        fun localized(id: String, targetIndex: Int): FixtureWideDistribution {
            val target = summaries[targetIndex]
            val term = target.localizedDenseCandidates.firstOrNull { candidate ->
                hitGraphIds(candidate) == listOf(target.graphId)
            } ?: error("Unable to derive a 200-row globally localized term for ${target.graphId}")
            return FixtureWideDistribution(id, target.graphId, term, listOf(target.graphId))
        }

        val broadTerm = rows.first().dense
        val broadHits = hitGraphIds(broadTerm)
        require(broadHits == rows.map(FixtureManifestRow::graphId)) {
            "Fixture64 broad term '$broadTerm' must hit every persisted graph; hit ${broadHits.size}"
        }
        return listOf(
            localized(LOCALIZED_EARLY_DISTRIBUTION, 0),
            localized(LOCALIZED_MIDDLE_DISTRIBUTION, (FIXTURE_GRAPH_COUNT - 1) / 2),
            localized(LOCALIZED_LATE_DISTRIBUTION, FIXTURE_GRAPH_COUNT - 1),
            FixtureWideDistribution(
                BROAD_DISTRIBUTION,
                rows.first().graphId,
                broadTerm,
                broadHits
            )
        )
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

    private fun verifyPreparedCorpus(manifestPath: Path, provenancePath: Path) {
        val manifestRows = parseManifest(manifestPath)
        val recordedDistributions = parseWideDistributions(manifestPath)
        val provenanceRows = parseProvenance(provenancePath)
        require(manifestRows.size == FIXTURE_GRAPH_COUNT) {
            "Expected $FIXTURE_GRAPH_COUNT manifest rows, found ${manifestRows.size}"
        }
        require(provenanceRows.size == FIXTURE_GRAPH_COUNT) {
            "Expected $FIXTURE_GRAPH_COUNT provenance rows, found ${provenanceRows.size}"
        }
        require(manifestRows.map(FixtureManifestRow::graphId).distinct().size == FIXTURE_GRAPH_COUNT) {
            "Manifest graph ids must be unique"
        }
        require(provenanceRows.map(FixtureProvenanceRow::graphId).distinct().size == FIXTURE_GRAPH_COUNT) {
            "Provenance graph ids must be unique"
        }

        val manifestById = manifestRows.associateBy(FixtureManifestRow::graphId)
        val provenanceById = provenanceRows.associateBy(FixtureProvenanceRow::graphId)
        require(manifestById.keys == provenanceById.keys) { "Manifest and provenance graph ids differ" }
        val sources = BenchmarkCorpusKind.entries.associate { kind ->
            val sourceJar = BenchmarkCorpus.resolveJar(kind).toRealPath()
            kind.id to FixtureSourceIdentity(
                fileName = sourceJar.fileName.toString(),
                sha256 = sha256(sourceJar),
                shards = summarizeClassShards(sourceJar)
            )
        }
        val graphPaths = mutableSetOf<Path>()
        val semanticFingerprints = mutableSetOf<String>()
        BenchmarkCorpusKind.entries.forEach { kind ->
            val source = checkNotNull(sources[kind.id])
            repeat(SHARDS_PER_CORPUS) { shardIndex ->
                val graphId = "fixture-${kind.id}-${shardIndex.toString().padStart(2, '0')}"
                val manifest = checkNotNull(manifestById[graphId]) { "Missing manifest row $graphId" }
                val provenance = checkNotNull(provenanceById[graphId]) { "Missing provenance row $graphId" }
                val shard = source.shards[shardIndex]
                require(provenance.corpus == kind.id && provenance.shard == shardIndex) {
                    "$graphId corpus/shard provenance mismatch"
                }
                require(provenance.sourceJar == source.fileName && provenance.sourceJarSha256 == source.sha256) {
                    "$graphId source JAR provenance mismatch"
                }
                require(provenance.shardBytecodeSha256 == shard.bytecodeSha256 &&
                    provenance.classCount == shard.classCount &&
                    provenance.resourceCount == shard.resources.count &&
                    provenance.resourceSemanticSha256 == shard.resources.sha256
                ) {
                    "$graphId source shard provenance mismatch"
                }
                val manifestPathValue = manifest.graphPath.toRealPath()
                val provenancePathValue = provenance.graphPath.toRealPath()
                require(manifestPathValue == provenancePathValue && graphPaths.add(manifestPathValue)) {
                    "$graphId graph path is mismatched or repeated"
                }
                val callSiteIndex = manifestPathValue.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
                require(Files.isRegularFile(callSiteIndex) &&
                    Files.size(callSiteIndex) == provenance.callSiteIndexBytes &&
                    sha256(callSiteIndex) == provenance.callSiteIndexSha256
                ) {
                    "$graphId production-built CallSite index content does not match provenance"
                }
                require(manifest.zero == provenance.zero && manifest.targeted == provenance.targeted &&
                    manifest.dense == provenance.dense &&
                    manifest.workloadIdentity == provenance.workloadIdentity
                ) {
                    "$graphId search-term provenance mismatch"
                }
                GraphStore.loadMapped(manifestPathValue).useGraph { graph ->
                    val mapped = graph as? MappedWebGraphBackedGraph
                        ?: error("$graphId did not load as a mapped graph")
                    require(mapped.prepareCallSiteStringIndex() &&
                        mapped.isCallSiteStringIndexLoadedFromPersistence()
                    ) {
                        "$graphId did not restore its production-built CallSite index"
                    }
                    val callSites = graph.nodes(CallSiteNode::class.java).toList()
                    val nodeCount = graph.nodes(Node::class.java).count().toLong()
                    val terms = selectTerms(graphId, callSites)
                    val semanticSha256 = querySemanticSha256(nodeCount, callSites)
                    val resources = resourceSemanticSummary(graph.resources)
                    require(nodeCount == provenance.nodeCount &&
                        callSites.size.toLong() == provenance.callSiteCount
                    ) {
                        "$graphId persisted graph counts do not match provenance"
                    }
                    require(terms == FixtureSearchTerms(manifest.zero, manifest.targeted, manifest.dense)) {
                        "$graphId persisted graph does not reproduce the manifest search terms"
                    }
                    require(semanticSha256 == provenance.querySemanticSha256) {
                        "$graphId query-semantic fingerprint mismatch"
                    }
                    require(resources.count > 0 && resources.count == provenance.resourceCount &&
                        resources.sha256 == provenance.resourceSemanticSha256
                    ) {
                        "$graphId persisted resource semantics mismatch"
                    }
                    val expectedWorkloadIdentity = workloadIdentitySha256(
                        graphId,
                        kind.id,
                        shardIndex,
                        terms,
                        semanticSha256,
                        resources
                    )
                    require(expectedWorkloadIdentity == provenance.workloadIdentity) {
                        "$graphId workload identity mismatch"
                    }
                    require(semanticFingerprints.add(semanticSha256)) {
                        "$graphId duplicates query-semantic graph content $semanticSha256"
                    }
                }
            }
        }
        require(recordedDistributions == deriveGlobalWideDistributions(manifestRows)) {
            "Fixture64 global-wide distribution records do not reproduce from the persisted graphs"
        }
    }

    private fun parseManifest(path: Path): List<FixtureManifestRow> = Files.readAllLines(path)
        .filterNot { line -> line.isBlank() || line.startsWith("#") }
        .mapIndexed { index, line -> parseManifestLine(line, "$path:${index + 1}") }

    private fun parseManifestLine(line: String, source: String = MANIFEST_FILE): FixtureManifestRow {
        val fields = line.split('\t')
        require(fields.size == MANIFEST_FIELD_COUNT) { "$source: expected 6 fields" }
        return FixtureManifestRow(fields[0], Path.of(fields[1]), fields[2], fields[3], fields[4], fields[5])
    }

    private fun parseWideDistributions(path: Path): List<FixtureWideDistribution> =
        Files.readAllLines(path).filter { line -> line.startsWith(WIDE_DISTRIBUTION_PREFIX) }.map { line ->
            val fields = line.split('\t')
            require(fields.size == WIDE_DISTRIBUTION_FIELD_COUNT) {
                "$path: malformed fixture64 global-wide distribution record"
            }
            FixtureWideDistribution(
                fields[1],
                fields[2],
                fields[3],
                fields[4].split(',')
            )
        }

    private fun parseProvenance(path: Path): List<FixtureProvenanceRow> {
        val lines = Files.readAllLines(path)
        require(lines.firstOrNull() == PROVENANCE_HEADER) { "$path has an unexpected provenance header" }
        return lines.drop(1).filter(String::isNotBlank).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == PROVENANCE_FIELD_COUNT) { "$path:${index + 2}: expected 19 fields" }
            FixtureProvenanceRow(
                graphId = fields[0],
                corpus = fields[1],
                shard = fields[2].toInt(),
                sourceJar = fields[3],
                sourceJarSha256 = fields[4],
                shardBytecodeSha256 = fields[5],
                classCount = fields[6].toInt(),
                nodeCount = fields[7].toLong(),
                callSiteCount = fields[8].toLong(),
                zero = fields[9],
                targeted = fields[10],
                dense = fields[11],
                querySemanticSha256 = fields[12],
                resourceCount = fields[13].toInt(),
                resourceSemanticSha256 = fields[14],
                workloadIdentity = fields[15],
                callSiteIndexBytes = fields[16].toLong(),
                callSiteIndexSha256 = fields[17],
                graphPath = Path.of(fields[18])
            )
        }
    }

    private fun summarizeClassShards(source: Path): List<FixtureJarShardSummary> {
        val counts = IntArray(SHARDS_PER_CORPUS)
        val digests = Array(SHARDS_PER_CORPUS) { newShardDigest() }
        val resourceCounts = IntArray(SHARDS_PER_CORPUS)
        val resourceDigests = Array(SHARDS_PER_CORPUS) { newResourceDigest() }
        JarFile(source.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.endsWith(CLASS_SUFFIX) }
                .sortedBy(JarEntry::getName)
                .forEach { entry ->
                    val shard = stableShard(entry.name)
                    val bytes = jar.getInputStream(entry).use { input -> input.readBytes() }
                    updateFrame(digests[shard], entry.name.toByteArray(Charsets.UTF_8))
                    updateFrame(digests[shard], bytes)
                    counts[shard]++
                }
            jar.entries().asSequence()
                .filter { entry -> !entry.isDirectory && shouldPersistResource(entry.name) }
                .sortedBy(JarEntry::getName)
                .distinctBy(JarEntry::getName)
                .forEachIndexed { resourceIndex, entry ->
                    val shard = resourceIndex % SHARDS_PER_CORPUS
                    val bytes = jar.getInputStream(entry).use { input -> input.readBytes() }
                    val sourceName = "shard-${shard.toString().padStart(2, '0')}.jar"
                    updateResourceDigest(resourceDigests[shard], entry.name, sourceName, bytes)
                    resourceCounts[shard]++
                }
        }
        return counts.indices.map { index ->
            require(counts[index] > 0) { "$source produced an empty fixture shard $index" }
            require(resourceCounts[index] > 0) { "$source produced a fixture shard without resources: $index" }
            FixtureJarShardSummary(
                counts[index],
                digests[index].hexDigest(),
                ResourceSemanticSummary(resourceCounts[index], resourceDigests[index].hexDigest())
            )
        }
    }

    private fun verifyMappedGraph(
        path: Path,
        expectedNodes: Long,
        expectedCallSites: Long,
        expectedSemanticSha256: String,
        expectedResources: ResourceSemanticSummary,
        expectedCallSiteIndexBytes: Long,
        expectedCallSiteIndexSha256: String
    ) {
        GraphStore.loadMapped(path).useGraph { graph ->
            val callSiteIndex = path.resolve(GraphStore.CALL_SITE_STRING_INDEX_FILE)
            check(Files.size(callSiteIndex) == expectedCallSiteIndexBytes &&
                sha256(callSiteIndex) == expectedCallSiteIndexSha256
            ) {
                "$path mapped CallSite index content differs from the production-built sidecar"
            }
            val mapped = graph as? MappedWebGraphBackedGraph
                ?: error("$path did not load as a mapped graph")
            check(mapped.prepareCallSiteStringIndex() && mapped.isCallSiteStringIndexLoadedFromPersistence()) {
                "$path did not restore the production-built CallSite index"
            }
            val callSites = graph.nodes(CallSiteNode::class.java).toList()
            val nodeCount = graph.nodes(Node::class.java).count().toLong()
            check(nodeCount == expectedNodes) {
                "$path mapped node count differs from the source graph"
            }
            check(callSites.size.toLong() == expectedCallSites) {
                "$path mapped CallSite count differs from the source graph"
            }
            check(querySemanticSha256(nodeCount, callSites) == expectedSemanticSha256) {
                "$path mapped query semantics differ from the source graph"
            }
            check(resourceSemanticSummary(graph.resources) == expectedResources) {
                "$path mapped resource paths/content differ from the source fixture JAR shard"
            }
        }
    }

    private fun resourceSemanticSummary(resources: ResourceAccessor): ResourceSemanticSummary {
        val entries = resources.list("**")
            .filter { entry -> shouldPersistResource(entry.path) }
            .sortedWith(compareBy({ it.path }, { it.source }))
            .toList()
        val digest = newResourceDigest()
        entries.forEach { entry ->
            val bytes = resources.open(entry.path).use { input -> input.readBytes() }
            updateResourceDigest(digest, entry.path, entry.source, bytes)
        }
        return ResourceSemanticSummary(entries.size, digest.hexDigest())
    }

    private fun shouldPersistResource(path: String): Boolean =
        PERSISTED_RESOURCE_SUFFIXES.any { suffix -> path.endsWith(suffix, ignoreCase = true) }

    private fun updateResourceDigest(digest: MessageDigest, path: String, source: String, bytes: ByteArray) {
        updateFrame(digest, path.toByteArray(Charsets.UTF_8))
        updateFrame(digest, source.toByteArray(Charsets.UTF_8))
        updateFrame(digest, bytes)
    }

    private fun querySemanticSha256(nodeCount: Long, callSites: List<CallSiteNode>): String {
        val records = callSites.map { callSite ->
            listOf(
                callSite.caller.declaringClass.className,
                callSite.caller.name,
                callSite.callee.declaringClass.className,
                callSite.callee.name
            )
        }
        return queryRecordSha256(nodeCount, records)
    }

    private fun queryRecordSha256(nodeCount: Long, records: List<List<String>>): String {
        val digest = MessageDigest.getInstance(SHA_256)
        updateFrame(digest, QUERY_SEMANTIC_VERSION.toByteArray(Charsets.UTF_8))
        updateFrame(digest, nodeCount.toString().toByteArray(Charsets.UTF_8))
        updateFrame(digest, records.size.toString().toByteArray(Charsets.UTF_8))
        records.forEach { record ->
            record.forEach { value -> updateFrame(digest, value.toByteArray(Charsets.UTF_8)) }
        }
        return digest.hexDigest()
    }

    private fun verifyOrderSensitiveFingerprint() {
        val first = listOf("caller.A", "first", "callee.A", "target")
        val second = listOf("caller.B", "second", "callee.B", "target")
        check(queryRecordSha256(2, listOf(first, second)) != queryRecordSha256(2, listOf(second, first))) {
            "Fixture workload identity must distinguish mapped CallSite traversal order"
        }
        println("Fixture64 workload identity is traversal-order-sensitive")
    }

    private fun workloadIdentitySha256(
        graphId: String,
        corpus: String,
        shard: Int,
        terms: FixtureSearchTerms,
        querySemanticSha256: String,
        resources: ResourceSemanticSummary
    ): String {
        val digest = MessageDigest.getInstance(SHA_256)
        listOf(
            WORKLOAD_IDENTITY_VERSION,
            graphId,
            corpus,
            shard.toString(),
            terms.zero,
            terms.targeted,
            terms.dense,
            querySemanticSha256,
            resources.count.toString(),
            resources.sha256
        ).forEach { value -> updateFrame(digest, value.toByteArray(Charsets.UTF_8)) }
        return digest.hexDigest()
    }

    private fun newShardDigest(): MessageDigest = MessageDigest.getInstance(SHA_256).also { digest ->
        updateFrame(digest, SHARD_SEMANTIC_VERSION.toByteArray(Charsets.UTF_8))
    }

    private fun newResourceDigest(): MessageDigest = MessageDigest.getInstance(SHA_256).also { digest ->
        updateFrame(digest, RESOURCE_SEMANTIC_VERSION.toByteArray(Charsets.UTF_8))
    }

    private fun updateFrame(digest: MessageDigest, bytes: ByteArray) {
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte()
            )
        )
        digest.update(bytes)
    }

    private fun MessageDigest.hexDigest(): String = digest().joinToString("") { byte -> "%02x".format(byte) }

    private inline fun <T> Graph.useGraph(block: (Graph) -> T): T = try {
        block(this)
    } finally {
        (this as? Closeable)?.close()
    }

    private data class FixtureJarShard(
        val path: Path,
        val classCount: Int,
        val bytecodeSha256: String,
        val resources: ResourceSemanticSummary
    )
    private data class FixtureJarShardSummary(
        val classCount: Int,
        val bytecodeSha256: String,
        val resources: ResourceSemanticSummary
    )
    private data class ResourceSemanticSummary(val count: Int, val sha256: String)
    private data class FixtureSourceIdentity(
        val fileName: String,
        val sha256: String,
        val shards: List<FixtureJarShardSummary>
    )
    private data class FixtureManifestRow(
        val graphId: String,
        val graphPath: Path,
        val zero: String,
        val targeted: String,
        val dense: String,
        val workloadIdentity: String
    )
    private data class FixtureProvenanceRow(
        val graphId: String,
        val corpus: String,
        val shard: Int,
        val sourceJar: String,
        val sourceJarSha256: String,
        val shardBytecodeSha256: String,
        val classCount: Int,
        val nodeCount: Long,
        val callSiteCount: Long,
        val zero: String,
        val targeted: String,
        val dense: String,
        val querySemanticSha256: String,
        val resourceCount: Int,
        val resourceSemanticSha256: String,
        val workloadIdentity: String,
        val callSiteIndexBytes: Long,
        val callSiteIndexSha256: String,
        val graphPath: Path
    )
    private data class FixtureSearchTerms(val zero: String, val targeted: String, val dense: String)
    private data class FixtureSearchSummary(
        val graphId: String,
        val lowercaseValues: Set<String>,
        val localizedDenseCandidates: List<String>
    )
    private data class FixtureWideDistribution(
        val id: String,
        val targetGraphId: String,
        val term: String,
        val hitGraphIds: List<String>
    ) {
        fun manifestLine(): String = listOf(
            WIDE_DISTRIBUTION_PREFIX,
            id,
            targetGraphId,
            term,
            hitGraphIds.joinToString(",")
        ).joinToString("\t")
    }

    private val TARGETED_RESULT_RANGE = 1 until 200
    private val DENSE_TERM_CANDIDATES = listOf("get", "java", "org", "com", "set", "invoke")
    private val PERSISTED_RESOURCE_SUFFIXES = setOf(".properties", ".yml", ".yaml", ".json", ".xml", ".txt")
    private const val FIXTURE_GRAPH_COUNT = 64
    private const val SHARDS_PER_CORPUS = 16
    private const val DENSE_RESULT_LIMIT = 200
    private const val MIN_TARGETED_TERM_LENGTH = 12
    private const val MIN_LOCALIZED_DENSE_TERM_LENGTH = 8
    private const val MAX_LOCALIZED_DENSE_CANDIDATES = 500
    private const val HASH_BUFFER_BYTES = 64 * 1024
    private const val DETERMINISTIC_ZIP_TIME_MILLIS = 0L
    private const val CLASS_SUFFIX = ".class"
    private const val SHA_256 = "SHA-256"
    private const val MANIFEST_FILE = "graphs.tsv"
    private const val PROVENANCE_FILE = "fixture-provenance.tsv"
    private const val MANIFEST_FIELD_COUNT = 6
    private const val WIDE_DISTRIBUTION_FIELD_COUNT = 5
    private const val WIDE_DISTRIBUTION_PREFIX = "# global-wide-distribution-v1"
    private const val LOCALIZED_EARLY_DISTRIBUTION = "localized-early"
    private const val LOCALIZED_MIDDLE_DISTRIBUTION = "localized-middle"
    private const val LOCALIZED_LATE_DISTRIBUTION = "localized-late"
    private const val BROAD_DISTRIBUTION = "broad-all-64"
    private const val PROVENANCE_FIELD_COUNT = 19
    private const val VERIFY_COMMAND = "--verify"
    private const val ORDER_FINGERPRINT_SELF_TEST = "--self-test-order-fingerprint"
    private const val QUERY_SEMANTIC_VERSION = "fixture64-query-semantics-v2-ordered"
    private const val SHARD_SEMANTIC_VERSION = "fixture64-shard-bytecode-v1"
    private const val RESOURCE_SEMANTIC_VERSION = "fixture64-resource-semantics-v1"
    private const val WORKLOAD_IDENTITY_VERSION = "fixture64-workload-identity-v4"
    private const val PROVENANCE_HEADER =
        "graphId\tcorpus\tshard\tsourceJar\tsourceJarSha256\tshardBytecodeSha256\tclassCount\tnodeCount" +
            "\tcallSiteCount\tzeroTerm\ttargetedTerm\tdenseTerm\tquerySemanticSha256\tresourceCount" +
            "\tresourceSemanticSha256\tworkloadIdentity\tcallSiteIndexBytes\tcallSiteIndexSha256\tgraphPath"
}
