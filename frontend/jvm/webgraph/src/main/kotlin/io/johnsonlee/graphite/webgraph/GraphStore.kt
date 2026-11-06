package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.graph.Graph
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.webgraph.BVGraph
import it.unimi.dsi.webgraph.ImmutableGraph
import it.unimi.dsi.webgraph.LazyIntIterator
import it.unimi.dsi.webgraph.LazyIntIterators
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Arrays

internal const val NODE_OFFSETS_FILE = "graph.nodeoffsets"
internal const val NODE_INDEX_FILE = "graph.nodeindex"
internal const val TYPE_INDEX_FILE = "graph.typeindex"
internal const val LABEL_PREFIX_FILE = "graph.labelprefix"
internal const val BACKWARD_GRAPH = "backward"
internal const val NODE_OFFSET_DATA_START = Int.SIZE_BYTES + Int.SIZE_BYTES
internal const val TYPE_INDEX_TABLE_ENTRY_BYTES = Byte.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES
internal const val BACKWARD_COMPRESSION_THREADS = 2

internal fun mappedCallSiteStringIndexPreparationEnabled(): Boolean =
    System.getProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)?.equals("true", ignoreCase = true) == true

internal fun mappedCallSiteStringIndexPersistenceEnabled(): Boolean =
    System.getProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)?.equals("false", ignoreCase = true) != true

/** OutputStream wrapper that tracks total bytes written. */
internal class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        bytesWritten += len
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()
}

internal class NodeOffsetIndexWriter(path: Path, private val size: Int) : Closeable {
    private val channel: FileChannel
    private val buffer: java.nio.MappedByteBuffer

    init {
        require(size >= 0) { "Invalid node offset index size: $size" }
        val totalBytes = NODE_OFFSET_DATA_START.toLong() + size.toLong() * Long.SIZE_BYTES
        require(totalBytes <= Int.MAX_VALUE) { "Mapped node offset index is too large to map as a single buffer: $totalBytes bytes" }
        channel =
            FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE)
        if (totalBytes > 0) {
            channel.write(ByteBuffer.wrap(byteArrayOf(0)), totalBytes - 1)
        }
        buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalBytes)
        buffer.putInt(0, NodeSerializer.MAGIC_NODEOFFSETS or NodeSerializer.FORMAT_VERSION)
        buffer.putInt(Int.SIZE_BYTES, size)
    }

    fun write(nodeId: Int, offset: Long) {
        require(nodeId in 0 until size) { "Node id $nodeId outside mapped offset index size $size" }
        require(offset < Long.MAX_VALUE) { "Node data offset is too large: $offset" }
        buffer.putLong(NODE_OFFSET_DATA_START + nodeId * Long.SIZE_BYTES, offset + 1L)
    }

    override fun close() {
        buffer.force()
        channel.close()
    }
}

internal class TypeIndexWriter(path: Path, private val counts: IntArray) : Closeable {
    private val tags = nodeTagEntries()
    private val offsets = IntArray(UByte.MAX_VALUE.toInt() + 1) { -1 }
    private val fillPositions = IntArray(UByte.MAX_VALUE.toInt() + 1)
    private val channel: FileChannel
    private val buffer: java.nio.MappedByteBuffer

    init {
        var totalBytesLong = (Int.SIZE_BYTES + Int.SIZE_BYTES + tags.size * TYPE_INDEX_TABLE_ENTRY_BYTES).toLong()
        for (tag in tags) {
            require(totalBytesLong <= Int.MAX_VALUE) {
                "Mapped node type index is too large to map as a single buffer: $totalBytesLong bytes"
            }
            offsets[tag] = totalBytesLong.toInt()
            totalBytesLong += counts[tag].toLong() * Int.SIZE_BYTES
        }
        require(totalBytesLong <= Int.MAX_VALUE) { "Mapped node type index is too large to map as a single buffer: $totalBytesLong bytes" }
        val totalBytes = totalBytesLong.toInt()

        channel =
            FileChannel.open(
                path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.READ, StandardOpenOption.WRITE)
        if (totalBytes > 0) {
            channel.write(ByteBuffer.wrap(byteArrayOf(0)), totalBytesLong - 1L)
        }
        buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalBytesLong)
        buffer.putInt(0, NodeSerializer.MAGIC_TYPEINDEX or NodeSerializer.FORMAT_VERSION)
        buffer.putInt(Int.SIZE_BYTES, tags.size)

        var tableOffset = Int.SIZE_BYTES + Int.SIZE_BYTES
        for (tag in tags) {
            buffer.put(tableOffset, tag.toByte())
            buffer.putInt(tableOffset + Byte.SIZE_BYTES, counts[tag])
            buffer.putLong(tableOffset + Byte.SIZE_BYTES + Int.SIZE_BYTES, offsets[tag].toLong())
            tableOffset += TYPE_INDEX_TABLE_ENTRY_BYTES
        }
    }

    fun write(tag: Int, nodeId: Int) {
        val offset = offsets[tag]
        require(offset >= 0) { "Unknown node tag for mapped type index: $tag" }
        val index = fillPositions[tag]++
        require(index < counts[tag]) { "Too many nodes for tag $tag: ${index + 1} > ${counts[tag]}" }
        buffer.putInt(offset + index * Int.SIZE_BYTES, nodeId)
    }

    override fun close() {
        buffer.force()
        channel.close()
    }
}

internal fun readMappedNodeIndex(dir: Path): NodeIndexData {
    val nodeIndexFile = dir.resolve(NODE_INDEX_FILE).toFile()
    require(nodeIndexFile.exists()) { "Node index file not found: $nodeIndexFile. Re-save the graph to generate it." }
    ensureMappedNodeIndexes(dir)
    return NodeIndexData(
        nodeOffsets = MappedNodeOffsetIndex.load(dir.resolve(NODE_OFFSETS_FILE)),
        nodeTypeIndex = MappedNodeTypeIndex.load(dir.resolve(TYPE_INDEX_FILE)))
}

internal fun ensureMappedNodeIndexes(dir: Path) {
    val offsetsFile = dir.resolve(NODE_OFFSETS_FILE)
    val typeIndexFile = dir.resolve(TYPE_INDEX_FILE)
    if (Files.exists(offsetsFile) && Files.exists(typeIndexFile)) return
    buildMappedNodeIndexesFromNodeIndex(dir.resolve(NODE_INDEX_FILE), offsetsFile, dir)
}

internal fun buildMappedNodeIndexesFromNodeIndex(nodeIndexPath: Path, nodeOffsetsPath: Path, dir: Path) {
    val indexStats = readLegacyNodeIndexStats(nodeIndexPath)
    val offsetsTemp = Files.createTempFile(dir, "$NODE_OFFSETS_FILE.", ".tmp")
    val typeIndexTemp = Files.createTempFile(dir, "$TYPE_INDEX_FILE.", ".tmp")
    try {
        NodeOffsetIndexWriter(offsetsTemp, indexStats.maxNodeId + 1).use { offsetWriter ->
            TypeIndexWriter(typeIndexTemp, indexStats.typeCounts).use { typeIndexWriter ->
                copyLegacyNodeIndexEntries(nodeIndexPath, offsetWriter, typeIndexWriter)
            }
        }
        Files.move(typeIndexTemp, dir.resolve(TYPE_INDEX_FILE), StandardCopyOption.REPLACE_EXISTING)
        Files.move(offsetsTemp, nodeOffsetsPath, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(offsetsTemp)
        Files.deleteIfExists(typeIndexTemp)
    }
}

internal fun readLegacyNodeIndexStats(nodeIndexPath: Path): LegacyNodeIndexStats {
    var maxNodeId = -1
    val nodeTypeCounts = IntArray(UByte.MAX_VALUE.toInt() + 1)
    DataInputStream(BufferedInputStream(nodeIndexPath.toFile().inputStream())).use { dis ->
        NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEINDEX)
        val nodeCount = dis.readInt()
        repeat(nodeCount) {
            val nodeId = dis.readInt()
            val tag = dis.readByte().toInt()
            dis.readLong()
            if (nodeId > maxNodeId) maxNodeId = nodeId
            nodeTypeCounts[tag]++
        }
    }
    return LegacyNodeIndexStats(maxNodeId, nodeTypeCounts)
}

internal fun copyLegacyNodeIndexEntries(nodeIndexPath: Path, offsetWriter: NodeOffsetIndexWriter, typeIndexWriter: TypeIndexWriter) {
    DataInputStream(BufferedInputStream(nodeIndexPath.toFile().inputStream())).use { dis ->
        NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEINDEX)
        val nodeCount = dis.readInt()
        repeat(nodeCount) {
            val nodeId = dis.readInt()
            val tag = dis.readByte().toInt()
            val offset = dis.readLong()
            offsetWriter.write(nodeId, offset)
            typeIndexWriter.write(tag, nodeId)
        }
    }
}

internal data class LegacyNodeIndexStats(val maxNodeId: Int, val typeCounts: IntArray)

internal fun loadPersistedBackwardGraph(dir: Path): ImmutableGraph? {
    if (!hasPersistedBackwardGraph(dir)) return null
    return try {
        BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())
    } catch (_: Exception) {
        null
    }
}

internal fun persistAndLoadBackwardGraph(dir: Path, backward: ImmutableGraph): ImmutableGraph? {
    var tempDir: Path? = null
    return try {
        tempDir = Files.createTempDirectory(dir, "$BACKWARD_GRAPH.")
        val loaded = storeAndLoadBackwardGraph(tempDir, backward)
        try {
            moveGraphFiles(tempDir, dir)
        } catch (_: Exception) {
            // The current process can still use the compressed in-memory graph.
        }
        loaded
    } catch (_: Exception) {
        null
    } finally {
        tempDir?.toFile()?.deleteRecursively()
    }
}

internal fun compressAndLoadTransientBackwardGraph(backward: ImmutableGraph): ImmutableGraph? {
    var tempDir: Path? = null
    return try {
        tempDir = Files.createTempDirectory("$BACKWARD_GRAPH.")
        storeAndLoadBackwardGraph(tempDir, backward)
    } catch (_: Exception) {
        null
    } finally {
        tempDir?.toFile()?.deleteRecursively()
    }
}

internal fun storeAndLoadBackwardGraph(dir: Path, backward: ImmutableGraph): ImmutableGraph {
    BVGraph.store(
        backward,
        dir.resolve(BACKWARD_GRAPH).toString(),
        BVGraph.DEFAULT_WINDOW_SIZE,
        BVGraph.DEFAULT_MAX_REF_COUNT,
        BVGraph.DEFAULT_MIN_INTERVAL_LENGTH,
        BVGraph.DEFAULT_ZETA_K,
        0,
        BACKWARD_COMPRESSION_THREADS)
    stripPropertiesComments(dir.resolve("$BACKWARD_GRAPH.properties"))
    return BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())
}

/**
 * Drops the comment lines `java.util.Properties.store` puts at the top of a `.properties` file. One of them is the wall-clock time of the
 * write, so two builds of the same graph differed in that line and nothing else, which changed the container fingerprint of every rebuild.
 * No reader depends on the comments: `Properties.load` and the Rust parser skip them.
 */
internal fun stripPropertiesComments(properties: Path) {
    val lines = Files.readAllLines(properties, Charsets.ISO_8859_1)
    val kept = lines.filterNot { it.startsWith("#") || it.startsWith("!") }
    if (kept.size != lines.size) {
        Files.write(properties, kept, Charsets.ISO_8859_1)
    }
}

internal fun hasPersistedBackwardGraph(dir: Path): Boolean =
    Files.isRegularFile(dir.resolve("$BACKWARD_GRAPH.graph")) && Files.isRegularFile(dir.resolve("$BACKWARD_GRAPH.properties"))

internal fun moveGraphFiles(sourceDir: Path, targetDir: Path) {
    Files.list(sourceDir).use { paths ->
        paths.forEach { source -> Files.move(source, targetDir.resolve(source.fileName), StandardCopyOption.REPLACE_EXISTING) }
    }
}

/**
 * Build a cumulative outdegree array for O(1) label offset lookup. `cumulativeOutdeg[i]` = sum of outdegree(0..i-1), so the labels for node
 * `i` start at `forwardLabels[cumulativeOutdeg[i]]`.
 */
internal fun buildCumulativeOutdeg(forward: ImmutableGraph): IntArray {
    val numNodes = forward.numNodes()
    val cumOutdeg = IntArray(numNodes + 1)
    for (i in 0 until numNodes) {
        val next = cumOutdeg[i].toLong() + forward.outdegree(i).toLong()
        require(next <= Int.MAX_VALUE) { "Graph has too many edges for byte-addressed labels: $next" }
        cumOutdeg[i + 1] = next.toInt()
    }
    return cumOutdeg
}

internal fun loadCumulativeOutdeg(dir: Path, forward: ImmutableGraph): IntArray {
    val path = dir.resolve(LABEL_PREFIX_FILE)
    if (Files.isRegularFile(path)) {
        try {
            val persisted = BinIO.loadInts(path.toString())
            if (persisted.size == forward.numNodes() + 1 && persisted.last().toLong() == forward.numArcs()) {
                return persisted
            }
        } catch (_: Exception) {
            // Older or corrupt auxiliary files can be ignored; forward.* remains authoritative.
        }
    }
    return buildCumulativeOutdeg(forward)
}

internal class NodeDataWriteContext(
    val dataDos: DataOutputStream,
    val idxDos: DataOutputStream,
    val offsetWriter: NodeOffsetIndexWriter,
    val typeIndexWriter: TypeIndexWriter,
    val countingOutput: CountingOutputStream,
    val stringTable: StringTable,
    val classOverviewEdges: ClassOverviewEdgeBuilder,
    val callSites: CallSiteWriteContext,
    val declaredKeys: DeclaredKeySummaryBuilder?
)

internal class CallSiteWriteContext(
    val identity: MessageDigest,
    val indexInput: CallSiteIndexPersistenceInput?,
    val ordinalInput: CallSiteOrdinalPersistenceInput
)

internal fun MessageDigest.updateContentInt(value: Int) {
    for (byteIndex in Int.SIZE_BYTES - 1 downTo 0) {
        update((value ushr (byteIndex * Byte.SIZE_BITS)).toByte())
    }
}

internal fun MessageDigest.updateContentLong(value: Long) {
    for (byteIndex in Long.SIZE_BYTES - 1 downTo 0) {
        update((value ushr (byteIndex * Byte.SIZE_BITS)).toByte())
    }
}

internal class ForwardDataScratch {
    private var targets = IntArray(INITIAL_TARGET_CAPACITY)
    private var targetCount = 0
    private var edgeKeys = LongArray(INITIAL_TARGET_CAPACITY)
    private var edgeLabels = ByteArray(INITIAL_TARGET_CAPACITY)
    private var edgeComparisons = arrayOfNulls<BranchComparison>(INITIAL_TARGET_CAPACITY)
    private var edgeCount = 0

    fun resetTargets() {
        targetCount = 0
    }

    fun addTarget(target: Int) {
        ensureTargetCapacity(targetCount + 1)
        targets[targetCount++] = target
    }

    fun uniqueTargetCount(): Int {
        if (targetCount < 2) return targetCount
        Arrays.sort(targets, 0, targetCount)
        var unique = 1
        var previous = targets[0]
        for (i in 1 until targetCount) {
            val current = targets[i]
            if (current != previous) {
                unique++
                previous = current
            }
        }
        return unique
    }

    fun resetEdges() {
        edgeCount = 0
    }

    fun addEdge(edge: Edge) {
        val ordinal = edgeCount
        ensureEdgeCapacity(ordinal + 1)
        edgeKeys[ordinal] = edgeSortKey(edge.to.value, ordinal)
        edgeLabels[ordinal] = NodeSerializer.encodeEdge(edge).toByte()
        edgeComparisons[ordinal] = (edge as? ControlFlowEdge)?.comparison
        edgeCount++
    }

    fun emitSortedEdges(
        fromNode: Int,
        startOffset: Int,
        outputTargets: IntArray,
        outputLabels: ByteArray,
        comparisonMap: MutableMap<Long, BranchComparison>
    ): Int {
        if (edgeCount == 0) return 0
        Arrays.sort(edgeKeys, 0, edgeCount)
        var emitted = 0
        var groupTarget = targetFromSortKey(edgeKeys[0])
        var chosenOrdinal = ordinalFromSortKey(edgeKeys[0])
        for (i in 1 until edgeCount) {
            val key = edgeKeys[i]
            val target = targetFromSortKey(key)
            if (target != groupTarget) {
                writeEdge(fromNode, groupTarget, chosenOrdinal, startOffset + emitted, outputTargets, outputLabels, comparisonMap)
                emitted++
                groupTarget = target
            }
            chosenOrdinal = ordinalFromSortKey(key)
        }
        writeEdge(fromNode, groupTarget, chosenOrdinal, startOffset + emitted, outputTargets, outputLabels, comparisonMap)
        return emitted + 1
    }

    private fun writeEdge(
        fromNode: Int,
        target: Int,
        ordinal: Int,
        outputOffset: Int,
        outputTargets: IntArray,
        outputLabels: ByteArray,
        comparisonMap: MutableMap<Long, BranchComparison>
    ) {
        outputTargets[outputOffset] = target
        outputLabels[outputOffset] = edgeLabels[ordinal]
        edgeComparisons[ordinal]?.let { comparison ->
            val key = fromNode.toLong() shl INT_BITS or (target.toLong() and UNSIGNED_INT_MASK)
            comparisonMap[key] = comparison
        }
    }

    private fun ensureTargetCapacity(required: Int) {
        if (required <= targets.size) return
        targets = targets.copyOf(grownCapacity(targets.size, required))
    }

    private fun ensureEdgeCapacity(required: Int) {
        if (required <= edgeKeys.size) return
        val newSize = grownCapacity(edgeKeys.size, required)
        edgeKeys = edgeKeys.copyOf(newSize)
        edgeLabels = edgeLabels.copyOf(newSize)
        edgeComparisons = edgeComparisons.copyOf(newSize)
    }

    private fun grownCapacity(current: Int, required: Int): Int {
        var next = current
        while (next < required) {
            next = next * 2
        }
        return next
    }

    private fun edgeSortKey(target: Int, ordinal: Int): Long = target.toLong() shl INT_BITS or (ordinal.toLong() and UNSIGNED_INT_MASK)

    private fun targetFromSortKey(key: Long): Int = (key ushr INT_BITS).toInt()

    private fun ordinalFromSortKey(key: Long): Int = key.toInt()

    private companion object {
        private const val INITIAL_TARGET_CAPACITY = 8
    }
}

internal data class NodeIndexData(val nodeOffsets: NodeOffsetIndex, val nodeTypeIndex: NodeTypeIndex)

/**
 * Save and load [Graph] instances using WebGraph compression with native LAW ecosystem tools (dsiutils + sux4j + fastutil).
 *
 * Storage layout:
 * - `forward.*` -- BVGraph compressed forward adjacency
 * - `backward.*` -- optional BVGraph compressed backward adjacency, created after first incoming query
 * - `graph.strings` -- [StringTable] (FrontCodedStringList via BinIO)
 * - `graph.strings.identity` -- semantic SHA-256 identity of the ordered string table
 * - `graph.labels` -- byte[] via [BinIO.storeBytes], 1 byte per arc in BVGraph successor order
 * - `graph.labelprefix` -- int[] cumulative outdegree values for label lookup
 * - `graph.comparisons` -- [BranchComparison] data for [ControlFlowEdge]s that carry one
 * - `graph.nodedata` -- sequential binary node data with string table indices
 * - `graph.metadata` -- methods, type hierarchy, enums, annotations, branch scopes (string table indices)
 * - `graph.classoverview` -- class-level call counts and dependency weights
 * - `graph.resources` -- persisted text resources, including an explicit empty store when none exist
 * - `graph.callsite-string-content.identity` -- CallSite fields + node offsets identity for index ownership
 * - `graph.callsite-string-index` -- optional persisted CSR/trigram search index for mapped CallSites
 * - `graph.branchdefs` -- optional branch-side local definitions, aligned with and bound to the metadata branch scopes
 */
object GraphStore {

    /**
     * Graphs with >= 1M nodes use memory-mapped loading to avoid heap pressure. Below this threshold, eager loading provides faster queries
     * at acceptable memory cost.
     */
    internal const val MAPPED_THRESHOLD = 1_000_000

    internal const val FORWARD_GRAPH = "forward"
    internal const val LABELS_FILE = "graph.labels"
    internal const val COMPARISONS_FILE = "graph.comparisons"
    internal const val NODE_DATA_FILE = "graph.nodedata"
    internal const val METADATA_FILE = "graph.metadata"
    internal const val CALL_SITE_STRING_INDEX_FILE = "graph.callsite-string-index"
    internal const val BRANCH_DEFINITIONS_FILE = "graph.branchdefs"
    internal const val CALL_SITE_ORDINALS_FILE = "graph.callsite-ordinals"
    internal const val NOT_A_DIRECTORY_PREFIX = "Not a directory:"
    internal const val MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY = "graphite.webgraph.prepareCallSiteStringIndexOnLoad"

    /**
     * Save a graph to disk in WebGraph + native LAW format.
     *
     * Uses a streaming [ImmutableGraph] wrapper over the source [Graph] to avoid copying all edge data into a second adjacency structure
     * ([ArrayListMutableGraph][it.unimi.dsi.webgraph.ArrayListMutableGraph]). For large graphs (millions of nodes) this eliminates the OOM
     * caused by duplicating the entire adjacency list in memory.
     */
    fun save(graph: Graph, dir: Path, compressionThreads: Int = 2) {
        save(graph, dir, compressionThreads, prepareCallSiteStringIndex = false)
    }

    fun save(graph: Graph, dir: Path, compressionThreads: Int = 2, prepareCallSiteStringIndex: Boolean) =
        GraphStoreWriter.saveGraph(
            graph, dir, compressionThreads, prepareCallSiteStringIndex, declaredTypeVersion = CURRENT_DECLARED_TYPE_VERSION)

    /** Explicit compatibility fixture path; ordinary saves always choose the current representation. */
    internal fun saveLegacyDeclaredTypesV3(graph: Graph, dir: Path, compressionThreads: Int = 2) =
        GraphStoreWriter.saveGraph(
            graph, dir, compressionThreads, prepareCallSiteStringIndex = false, declaredTypeVersion = SHARED_DECLARED_TYPE_VERSION)

    internal fun saveLegacyDeclaredTypesV4(graph: Graph, dir: Path, compressionThreads: Int = 2) =
        GraphStoreWriter.saveGraph(
            graph, dir, compressionThreads, prepareCallSiteStringIndex = false, declaredTypeVersion = STRUCTURAL_DECLARED_TYPE_VERSION)

    internal const val CURRENT_DECLARED_TYPE_VERSION = 5
    internal const val STRUCTURAL_DECLARED_TYPE_VERSION = 4
    internal const val SHARED_DECLARED_TYPE_VERSION = 3

    /**
     * Load a graph from disk.
     *
     * @param mode loading strategy; defaults to [LoadMode.AUTO] which selects based on graph size (< 1M nodes → eager, >= 1M → mapped).
     */
    fun load(dir: Path, mode: LoadMode = LoadMode.AUTO): Graph {
        require(Files.isDirectory(dir)) { GraphStoreLoader.notDirectoryMessage(dir) }
        return when (mode) {
            LoadMode.EAGER -> GraphStoreLoader.loadEager(dir)
            LoadMode.MAPPED -> {
                GraphNodeIndexStorage.ensureNodeIndex(dir)
                loadMapped(dir)
            }
            LoadMode.AUTO -> {
                val (_, nodeCount) = GraphNodeIndexStorage.readNodeDataHeader(dir)
                if (nodeCount < MAPPED_THRESHOLD) {
                    GraphStoreLoader.loadEager(dir)
                } else {
                    GraphNodeIndexStorage.ensureNodeIndex(dir)
                    loadMapped(dir)
                }
            }
        }
    }

    /** Loading strategy for [load]. */
    enum class LoadMode {
        /** All nodes deserialized into JVM heap. Fastest queries, highest memory. */
        EAGER,
        /** Node data memory-mapped via OS page cache. 75% less heap, slightly slower queries. */
        MAPPED,
        /** Auto-select based on graph size (< 1M nodes → [EAGER], >= 1M → [MAPPED]). */
        AUTO
    }

    /**
     * Load a graph with memory-mapped node data. Forward edge structures and edge labels are opened during load so the returned graph is
     * ready for forward traversal without hidden first-query initialization.
     *
     * The OS page cache manages which node pages are in physical RAM. No JVM heap allocation for node data, and no system calls per node
     * access after the initial page fault.
     *
     * Persisted CallSite string and trigram indexes are restored lazily on the first relevant query, so unrelated mapped-graph queries do
     * not retain their memory. Set `graphite.webgraph.prepareCallSiteStringIndexOnLoad=true` to prepare before this method returns, or
     * `false` to disable persisted restore while retaining the in-memory lazy-build fallback.
     */
    fun loadMapped(dir: Path): Graph =
        GraphStoreLoader.loadMapped(
            dir,
            prepareCallSiteStringIndex = mappedCallSiteStringIndexPreparationEnabled(),
            persistentCallSiteStringIndex = mappedCallSiteStringIndexPersistenceEnabled())

    /**
     * Flat sorted adjacency: targets[offsets[node]..offsets[node+1]] are the sorted, deduplicated successors of node. Zero per-node
     * allocation on access.
     */
    internal class PrecomputedAdjacency(val numNodes: Int, val targets: IntArray, val offsets: IntArray) {
        fun outdegree(node: Int): Int = offsets[node + 1] - offsets[node]

        fun successorArray(node: Int): IntArray {
            val start = offsets[node]
            val end = offsets[node + 1]
            return targets.copyOfRange(start, end)
        }
    }

    /**
     * Wraps a [PrecomputedAdjacency] as a WebGraph [ImmutableGraph] for BVGraph storage. Zero allocation per node access -- successorArray
     * returns a copy of the pre-sorted slice.
     */
    internal class PrecomputedImmutableGraph(private val adj: PrecomputedAdjacency) : ImmutableGraph() {
        override fun numNodes(): Int = adj.numNodes

        override fun numArcs(): Long = adj.offsets[adj.numNodes].toLong()

        override fun randomAccess(): Boolean = true

        override fun outdegree(x: Int): Int = adj.outdegree(x)

        override fun successorArray(x: Int): IntArray = adj.successorArray(x)

        override fun successors(x: Int): LazyIntIterator = LazyIntIterators.wrap(successorArray(x))

        override fun copy(): ImmutableGraph = this
    }

    internal fun collectMetadata(graph: Graph): GraphMetadata = GraphMetadataCollector(graph).collect()

    fun ensureNodeIndex(dir: Path) = GraphNodeIndexStorage.ensureNodeIndex(dir)

    internal fun buildNodeIndex(nodeDataPath: Path, nodeIndexPath: Path, stringTable: StringTable) =
        GraphNodeIndexStorage.buildNodeIndex(nodeDataPath, nodeIndexPath, stringTable)

    internal fun loadBranchDefinitions(
        dir: Path,
        metadata: GraphMetadata,
        nodeCount: Int,
        nodeTags: BranchDefinitionCodec.NodeTagLookup
    ): PersistedBranchDefinitions = BranchDefinitionStorage.loadBranchDefinitions(dir, metadata, nodeCount, nodeTags)
}
