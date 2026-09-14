@file:Suppress("MagicNumber")

package io.johnsonlee.graphite.cli

import com.google.gson.stream.JsonWriter
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Comparator
import java.util.Objects
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val TOPOLOGY_FORMAT_VERSION = 1
private const val FILE_HEADER_BYTES = 12
private const val STRING_REF_BYTES = 12
private const val NODE_RECORD_BYTES = 44
private const val RELATION_RECORD_BYTES = 60

private const val MANIFEST_MAGIC = 0x4754504D // GTPM
private const val STRINGS_MAGIC = 0x47545053 // GTPS
private const val NODES_MAGIC = 0x4754504E // GTPN
private const val RELATIONS_MAGIC = 0x47545052 // GTPR
private const val OPERATIONS_MAGIC = 0x4754504F // GTPO
private const val EVIDENCE_MAGIC = 0x47545045 // GTPE

private const val MANIFEST_FILE = "manifest"
private const val STRINGS_FILE = "topology.strings"
private const val NODES_FILE = "topology.nodes"
private const val RELATIONS_FILE = "topology.relations"
private const val OPERATIONS_FILE = "topology.operations"
private const val EVIDENCE_FILE = "topology.evidence"
private const val API_FILE = "topology.json"

internal data class TopologySummary(
    val graphCount: Int,
    val relationCount: Int,
    val matchedRows: Int,
    val builtAt: Instant,
    val rules: List<String>
)

internal data class TopologyApiStream(
    val contentLength: Long,
    val input: InputStream
)

/** Internal, Explorer-only topology persistence. It does not use or version the public GraphStore format. */
internal object TopologyStore {

    @Suppress("TooGenericExceptionCaught")
    fun writeAndOpen(tempRoot: Path, topology: TopologyGraph): MappedTopologySnapshot {
        val root = tempRoot.toAbsolutePath().normalize().resolve("graphite")
        Files.createDirectories(root)
        val buildId = UUID.randomUUID()
        val buildDir = root.resolve(buildId.toString())
        Files.createDirectory(buildDir)
        try {
            write(buildDir, buildId, topology)
            return MappedTopologySnapshot.open(buildDir)
        } catch (error: Throwable) {
            deleteBuildDirectory(buildDir)
            throw error
        }
    }

    private fun write(buildDir: Path, buildId: UUID, topology: TopologyGraph) {
        val operationCount = topology.edges.sumOf { it.operations.size }
        val evidenceCount = topology.edges.sumOf { it.evidence.size }
        val stringCount = topology.nodes.size +
            topology.edges.size * 3 + operationCount + evidenceCount + topology.rules.size

        StringSink(buildDir.resolve(STRINGS_FILE), stringCount).use { strings ->
            writeNodes(buildDir, topology.nodes, strings)
            writeRelations(buildDir, topology.edges, operationCount, evidenceCount, strings)
            val ruleRefs = topology.rules.map(strings::write)
            writeManifest(buildDir, buildId, topology, ruleRefs)
        }
        writeApiJson(buildDir.resolve(API_FILE), topology)
    }

    private fun writeNodes(buildDir: Path, topologyNodes: List<TopologyNode>, strings: StringSink) {
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(buildDir.resolve(NODES_FILE)))).use { nodes ->
            nodes.writeHeader(NODES_MAGIC, topologyNodes.size)
            topologyNodes.forEach { node ->
                nodes.writeRef(strings.write(node.id))
                nodes.writeLong(node.stats.nodes)
                nodes.writeLong(node.stats.edges)
                nodes.writeLong(node.stats.methods)
                nodes.writeLong(node.stats.callSites)
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun writeRelations(
        buildDir: Path,
        topologyEdges: List<TopologyEdge>,
        operationCount: Int,
        evidenceCount: Int,
        strings: StringSink
    ) {
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(buildDir.resolve(RELATIONS_FILE)))).use { relations ->
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(buildDir.resolve(OPERATIONS_FILE)))).use { operations ->
                DataOutputStream(BufferedOutputStream(Files.newOutputStream(buildDir.resolve(EVIDENCE_FILE)))).use { evidence ->
                    relations.writeHeader(RELATIONS_MAGIC, topologyEdges.size)
                    operations.writeHeader(OPERATIONS_MAGIC, operationCount)
                    evidence.writeHeader(EVIDENCE_MAGIC, evidenceCount)
                    writeRelations(topologyEdges, strings, relations, operations, evidence)
                }
            }
        }
    }

    private fun writeRelations(
        topologyEdges: List<TopologyEdge>,
        strings: StringSink,
        relations: DataOutputStream,
        operations: DataOutputStream,
        evidence: DataOutputStream
    ) {
        var operationStart = 0
        var evidenceStart = 0
        topologyEdges.forEach { edge ->
            relations.writeRef(strings.write(edge.from))
            relations.writeRef(strings.write(edge.to))
            relations.writeRef(strings.write(edge.protocol))
            relations.writeLong(edge.weight)
            relations.writeInt(operationStart)
            relations.writeInt(edge.operations.size)
            relations.writeInt(evidenceStart)
            relations.writeInt(edge.evidence.size)
            edge.operations.forEach { operations.writeRef(strings.write(it)) }
            edge.evidence.forEach { evidence.writeRef(strings.write(it)) }
            operationStart += edge.operations.size
            evidenceStart += edge.evidence.size
        }
    }

    private fun writeManifest(buildDir: Path, buildId: UUID, topology: TopologyGraph, ruleRefs: List<StringRef>) {
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(buildDir.resolve(MANIFEST_FILE)))).use { manifest ->
            manifest.writeInt(MANIFEST_MAGIC)
            manifest.writeInt(TOPOLOGY_FORMAT_VERSION)
            manifest.writeLong(buildId.mostSignificantBits)
            manifest.writeLong(buildId.leastSignificantBits)
            manifest.writeLong(topology.builtAt.epochSecond)
            manifest.writeInt(topology.builtAt.nano)
            manifest.writeInt(topology.matchedRows)
            manifest.writeInt(topology.nodes.size)
            manifest.writeInt(topology.edges.size)
            manifest.writeInt(ruleRefs.size)
            ruleRefs.forEach(manifest::writeRef)
        }
    }

    private fun writeApiJson(path: Path, topology: TopologyGraph) {
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { output ->
            JsonWriter(output).use { json ->
                json.beginObject()
                json.name(API_FIELD_NODES).beginArray()
                topology.nodes.forEach { node ->
                    json.beginObject()
                    json.name(API_FIELD_ID).value(node.id)
                    json.name(API_FIELD_GRAPH_ID).value(node.id)
                    json.name(API_FIELD_TYPE).value("Graph")
                    json.name(API_FIELD_LABEL).value(node.id)
                    json.name(API_FIELD_NODES).value(node.stats.nodes)
                    json.name(API_FIELD_EDGES).value(node.stats.edges)
                    json.name(API_FIELD_METHODS).value(node.stats.methods)
                    json.name(API_FIELD_CALL_SITES).value(node.stats.callSites)
                    json.endObject()
                }
                json.endArray()
                json.name(API_FIELD_EDGES).beginArray()
                topology.edges.forEach { edge ->
                    json.beginObject()
                    json.name(API_FIELD_FROM).value(edge.from)
                    json.name(API_FIELD_TO).value(edge.to)
                    json.name(API_FIELD_TYPE).value("TopologyCall")
                    json.name(TOPOLOGY_PROTOCOL).value(edge.protocol)
                    json.name(TOPOLOGY_WEIGHT).value(edge.weight)
                    json.name("operations").beginArray()
                    edge.operations.forEach(json::value)
                    json.endArray()
                    json.name(TOPOLOGY_EVIDENCE).beginArray()
                    edge.evidence.forEach(json::value)
                    json.endArray()
                    json.endObject()
                }
                json.endArray()
                json.name("graphCount").value(topology.nodes.size)
                json.name("relationCount").value(topology.edges.size)
                json.name("matchedRows").value(topology.matchedRows)
                json.name("builtAt").value(topology.builtAt.toString())
                json.name("rules").beginArray()
                topology.rules.forEach(json::value)
                json.endArray()
                json.name("stale").value(false)
                json.endObject()
            }
        }
    }

    internal fun deleteBuildDirectory(buildDir: Path) {
        val normalized = buildDir.toAbsolutePath().normalize()
        require(normalized.parent?.fileName?.toString() == "graphite") {
            "Refusing to delete non-topology directory: $normalized"
        }
        UUID.fromString(normalized.fileName.toString())
        if (!Files.exists(normalized)) return
        val paths = Files.walk(normalized).use { stream ->
            stream.sorted(Comparator.reverseOrder()).toList()
        }
        paths.forEach { path ->
            runCatching { Files.deleteIfExists(path) }
                .onFailure { path.toFile().deleteOnExit() }
        }
    }
}

internal class MappedTopologySnapshot private constructor(
    val buildDir: Path,
    private val manifest: TopologyManifest,
    private val buffers: TopologyBuffers
) : Closeable {

    private val closed = AtomicBoolean()

    fun summary(): TopologySummary = TopologySummary(
        graphCount = manifest.nodeCount,
        relationCount = manifest.relationCount,
        matchedRows = manifest.matchedRows,
        builtAt = manifest.builtAt,
        rules = manifest.ruleRefs.map(::readString)
    )

    fun materialize(): TopologyGraph {
        checkOpen()
        val materializedNodes = (0 until manifest.nodeCount).map(::readNode)
        val materializedEdges = (0 until manifest.relationCount).map(::readRelation)
        return TopologyGraph(
            nodes = materializedNodes,
            edges = materializedEdges,
            builtAt = manifest.builtAt,
            rules = manifest.ruleRefs.map(::readString),
            matchedRows = manifest.matchedRows
        )
    }

    fun toApiMap(stale: Boolean): Map<String, Any> = materialize().toApiMap(stale)

    fun openApiStream(onClose: () -> Unit): TopologyApiStream {
        checkOpen()
        val buffer = buffers.apiJson.asReadOnlyBuffer()
        return TopologyApiStream(
            contentLength = buffer.remaining().toLong(),
            input = MappedByteBufferInputStream(buffer, onClose)
        )
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            TopologyStore.deleteBuildDirectory(buildDir)
        }
    }

    private fun readNode(index: Int): TopologyNode {
        var offset = recordOffset(index, NODE_RECORD_BYTES)
        val id = readString(readRef(buffers.nodes, offset))
        offset += STRING_REF_BYTES
        return TopologyNode(
            id = id,
            stats = GraphStats(
                nodes = buffers.nodes.getLong(offset),
                edges = buffers.nodes.getLong(offset + Long.SIZE_BYTES),
                methods = buffers.nodes.getLong(offset + Long.SIZE_BYTES * 2),
                callSites = buffers.nodes.getLong(offset + Long.SIZE_BYTES * 3)
            )
        )
    }

    private fun readRelation(index: Int): TopologyEdge {
        var offset = recordOffset(index, RELATION_RECORD_BYTES)
        val from = readString(readRef(buffers.relations, offset))
        offset += STRING_REF_BYTES
        val to = readString(readRef(buffers.relations, offset))
        offset += STRING_REF_BYTES
        val protocol = readString(readRef(buffers.relations, offset))
        offset += STRING_REF_BYTES
        val weight = buffers.relations.getLong(offset)
        offset += Long.SIZE_BYTES
        val operationStart = buffers.relations.getInt(offset)
        val relationOperationCount = buffers.relations.getInt(offset + Int.SIZE_BYTES)
        val evidenceStart = buffers.relations.getInt(offset + Int.SIZE_BYTES * 2)
        val relationEvidenceCount = buffers.relations.getInt(offset + Int.SIZE_BYTES * 3)
        require(
            operationStart >= 0 && relationOperationCount >= 0 &&
                operationStart + relationOperationCount <= buffers.operationCount
        )
        require(
            evidenceStart >= 0 && relationEvidenceCount >= 0 &&
                evidenceStart + relationEvidenceCount <= buffers.evidenceCount
        )
        return TopologyEdge(
            from = from,
            to = to,
            protocol = protocol,
            weight = weight,
            operations = readDetails(buffers.operations, operationStart, relationOperationCount),
            evidence = readDetails(buffers.evidence, evidenceStart, relationEvidenceCount)
        )
    }

    private fun readDetails(buffer: ByteBuffer, start: Int, count: Int): List<String> =
        (start until start + count).map { index ->
            readString(readRef(buffer, recordOffset(index, STRING_REF_BYTES)))
        }

    private fun readString(ref: StringRef): String {
        checkOpen()
        require(ref.offset >= FILE_HEADER_BYTES && ref.length >= 0)
        val end = Math.addExact(ref.offset, ref.length.toLong())
        require(end <= buffers.strings.limit().toLong()) { "Topology string reference exceeds mapped file: $ref" }
        val bytes = ByteArray(ref.length)
        buffers.strings.asReadOnlyBuffer().apply {
            position(ref.offset.toInt())
            get(bytes)
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun checkOpen() {
        check(!closed.get()) { "Topology snapshot ${manifest.buildId} is closed" }
    }

    companion object {
        fun open(buildDir: Path): MappedTopologySnapshot {
            val manifest = map(buildDir.resolve(MANIFEST_FILE))
            require(manifest.getInt(0) == MANIFEST_MAGIC) { "Invalid topology manifest magic" }
            require(manifest.getInt(Int.SIZE_BYTES) == TOPOLOGY_FORMAT_VERSION) {
                "Unsupported topology format version ${manifest.getInt(Int.SIZE_BYTES)}"
            }
            var offset = Int.SIZE_BYTES * 2
            val buildId = UUID(manifest.getLong(offset), manifest.getLong(offset + Long.SIZE_BYTES))
            require(buildId.toString() == buildDir.fileName.toString()) { "Topology build id does not match its directory" }
            offset += Long.SIZE_BYTES * 2
            val epochSecond = manifest.getLong(offset)
            offset += Long.SIZE_BYTES
            val nano = manifest.getInt(offset)
            offset += Int.SIZE_BYTES
            val matchedRows = manifest.getInt(offset)
            val nodeCount = manifest.getInt(offset + Int.SIZE_BYTES)
            val relationCount = manifest.getInt(offset + Int.SIZE_BYTES * 2)
            val ruleCount = manifest.getInt(offset + Int.SIZE_BYTES * 3)
            offset += Int.SIZE_BYTES * 4
            require(nodeCount >= 0 && relationCount >= 0 && matchedRows >= 0 && ruleCount >= 0)
            requireCapacity(manifest, offset.toLong() + ruleCount.toLong() * STRING_REF_BYTES, MANIFEST_FILE)
            val ruleRefs = (0 until ruleCount).map { index ->
                readRef(manifest, offset + index * STRING_REF_BYTES)
            }

            val strings = map(buildDir.resolve(STRINGS_FILE)).also { requireHeader(it, STRINGS_MAGIC) }
            val nodes = map(buildDir.resolve(NODES_FILE)).also {
                requireHeader(it, NODES_MAGIC, nodeCount)
                requireCapacity(it, FILE_HEADER_BYTES.toLong() + nodeCount.toLong() * NODE_RECORD_BYTES, NODES_FILE)
            }
            val relations = map(buildDir.resolve(RELATIONS_FILE)).also {
                requireHeader(it, RELATIONS_MAGIC, relationCount)
                requireCapacity(
                    it,
                    FILE_HEADER_BYTES.toLong() + relationCount.toLong() * RELATION_RECORD_BYTES,
                    RELATIONS_FILE
                )
            }
            val operations = map(buildDir.resolve(OPERATIONS_FILE)).also { requireHeader(it, OPERATIONS_MAGIC) }
            val evidence = map(buildDir.resolve(EVIDENCE_FILE)).also { requireHeader(it, EVIDENCE_MAGIC) }
            val operationCount = operations.getInt(Int.SIZE_BYTES * 2)
            val evidenceCount = evidence.getInt(Int.SIZE_BYTES * 2)
            require(operationCount >= 0 && evidenceCount >= 0)
            requireCapacity(
                operations,
                FILE_HEADER_BYTES.toLong() + operationCount.toLong() * STRING_REF_BYTES,
                OPERATIONS_FILE
            )
            requireCapacity(
                evidence,
                FILE_HEADER_BYTES.toLong() + evidenceCount.toLong() * STRING_REF_BYTES,
                EVIDENCE_FILE
            )
            val apiJson = map(buildDir.resolve(API_FILE))
            return MappedTopologySnapshot(
                buildDir,
                TopologyManifest(
                    buildId = buildId,
                    nodeCount = nodeCount,
                    relationCount = relationCount,
                    matchedRows = matchedRows,
                    builtAt = Instant.ofEpochSecond(epochSecond, nano.toLong()),
                    ruleRefs = ruleRefs
                ),
                TopologyBuffers(
                    strings = strings,
                    nodes = nodes,
                    relations = relations,
                    operations = operations,
                    evidence = evidence,
                    apiJson = apiJson,
                    operationCount = operationCount,
                    evidenceCount = evidenceCount
                )
            )
        }

        private fun map(path: Path): MappedByteBuffer = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            require(channel.size() in 1..Int.MAX_VALUE.toLong()) { "Topology file is too large to map: $path" }
            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        }

        private fun requireHeader(buffer: ByteBuffer, magic: Int, expectedCount: Int? = null) {
            requireCapacity(buffer, FILE_HEADER_BYTES.toLong(), "header")
            require(buffer.getInt(0) == magic) { "Invalid topology file magic" }
            require(buffer.getInt(Int.SIZE_BYTES) == TOPOLOGY_FORMAT_VERSION) {
                "Unsupported topology format version ${buffer.getInt(Int.SIZE_BYTES)}"
            }
            expectedCount?.let { require(buffer.getInt(Int.SIZE_BYTES * 2) == it) }
        }

        private fun requireCapacity(buffer: ByteBuffer, required: Long, name: String) {
            require(required <= buffer.limit().toLong()) {
                "Truncated topology file '$name': required=$required actual=${buffer.limit()}"
            }
        }
    }
}

private data class TopologyManifest(
    val buildId: UUID,
    val nodeCount: Int,
    val relationCount: Int,
    val matchedRows: Int,
    val builtAt: Instant,
    val ruleRefs: List<StringRef>
)

private data class TopologyBuffers(
    val strings: MappedByteBuffer,
    val nodes: MappedByteBuffer,
    val relations: MappedByteBuffer,
    val operations: MappedByteBuffer,
    val evidence: MappedByteBuffer,
    val apiJson: MappedByteBuffer,
    val operationCount: Int,
    val evidenceCount: Int
)

private data class StringRef(val offset: Long, val length: Int)

private class StringSink(path: Path, count: Int) : Closeable {
    private val output = DataOutputStream(BufferedOutputStream(Files.newOutputStream(path)))
    private var offset = FILE_HEADER_BYTES.toLong()

    init {
        output.writeHeader(STRINGS_MAGIC, count)
    }

    fun write(value: String): StringRef {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val ref = StringRef(offset, bytes.size)
        output.write(bytes)
        offset = Math.addExact(offset, bytes.size.toLong())
        return ref
    }

    override fun close() = output.close()
}

private class MappedByteBufferInputStream(
    private val buffer: ByteBuffer,
    private val onClose: () -> Unit
) : InputStream() {
    private val closed = AtomicBoolean()

    override fun read(): Int = if (buffer.hasRemaining()) buffer.get().toInt() and 0xff else -1

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        Objects.checkFromIndexSize(offset, length, bytes.size)
        return when {
            length == 0 -> 0
            !buffer.hasRemaining() -> -1
            else -> minOf(length, buffer.remaining()).also { count -> buffer.get(bytes, offset, count) }
        }
    }

    override fun available(): Int = buffer.remaining()

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

private fun DataOutputStream.writeHeader(magic: Int, count: Int) {
    writeInt(magic)
    writeInt(TOPOLOGY_FORMAT_VERSION)
    writeInt(count)
}

private fun DataOutputStream.writeRef(ref: StringRef) {
    writeLong(ref.offset)
    writeInt(ref.length)
}

private fun readRef(buffer: ByteBuffer, offset: Int): StringRef =
    StringRef(buffer.getLong(offset), buffer.getInt(offset + Long.SIZE_BYTES))

private fun recordOffset(index: Int, recordBytes: Int): Int =
    Math.addExact(FILE_HEADER_BYTES, Math.multiplyExact(index, recordBytes))
