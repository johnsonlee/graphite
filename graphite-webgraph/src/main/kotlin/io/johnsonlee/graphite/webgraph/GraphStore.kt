package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.BranchScope
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ControlFlowEdge
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.ValueNode
import io.johnsonlee.graphite.graph.ClassOverview
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.MmapGraph
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.webgraph.BVGraph
import it.unimi.dsi.webgraph.ImmutableGraph
import it.unimi.dsi.webgraph.LazyIntIterator
import it.unimi.dsi.webgraph.LazyIntIterators
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Arrays
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

private const val NODE_OFFSETS_FILE = "graph.nodeoffsets"
private const val NODE_INDEX_FILE = "graph.nodeindex"
private const val TYPE_INDEX_FILE = "graph.typeindex"
private const val LABEL_PREFIX_FILE = "graph.labelprefix"
private const val BACKWARD_GRAPH = "backward"
private const val NODE_OFFSET_DATA_START = Int.SIZE_BYTES + Int.SIZE_BYTES
private const val TYPE_INDEX_TABLE_ENTRY_BYTES = Byte.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES
private const val BACKWARD_COMPRESSION_THREADS = 2

private fun mappedCallSiteStringIndexPreparationEnabled(): Boolean =
    System.getProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
        ?.equals("true", ignoreCase = true) == true

private fun mappedCallSiteStringIndexPersistenceEnabled(): Boolean =
    System.getProperty(GraphStore.MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY)
        ?.equals("false", ignoreCase = true) != true

/** OutputStream wrapper that tracks total bytes written. */
private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
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

private class NodeOffsetIndexWriter(path: Path, private val size: Int) : Closeable {
    private val channel: FileChannel
    private val buffer: java.nio.MappedByteBuffer

    init {
        require(size >= 0) { "Invalid node offset index size: $size" }
        val totalBytes = NODE_OFFSET_DATA_START.toLong() + size.toLong() * Long.SIZE_BYTES
        require(totalBytes <= Int.MAX_VALUE) {
            "Mapped node offset index is too large to map as a single buffer: $totalBytes bytes"
        }
        channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        )
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

private class TypeIndexWriter(path: Path, private val counts: IntArray) : Closeable {
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
        require(totalBytesLong <= Int.MAX_VALUE) {
            "Mapped node type index is too large to map as a single buffer: $totalBytesLong bytes"
        }
        val totalBytes = totalBytesLong.toInt()

        channel = FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        )
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

private fun readMappedNodeIndex(dir: Path): NodeIndexData {
    val nodeIndexFile = dir.resolve(NODE_INDEX_FILE).toFile()
    require(nodeIndexFile.exists()) {
        "Node index file not found: $nodeIndexFile. Re-save the graph to generate it."
    }
    ensureMappedNodeIndexes(dir)
    return NodeIndexData(
        nodeOffsets = MappedNodeOffsetIndex.load(dir.resolve(NODE_OFFSETS_FILE)),
        nodeTypeIndex = MappedNodeTypeIndex.load(dir.resolve(TYPE_INDEX_FILE))
    )
}

private fun ensureMappedNodeIndexes(dir: Path) {
    val offsetsFile = dir.resolve(NODE_OFFSETS_FILE)
    val typeIndexFile = dir.resolve(TYPE_INDEX_FILE)
    if (Files.exists(offsetsFile) && Files.exists(typeIndexFile)) return
    buildMappedNodeIndexesFromNodeIndex(dir.resolve(NODE_INDEX_FILE), offsetsFile, dir)
}

private fun buildMappedNodeIndexesFromNodeIndex(nodeIndexPath: Path, nodeOffsetsPath: Path, dir: Path) {
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

private fun readLegacyNodeIndexStats(nodeIndexPath: Path): LegacyNodeIndexStats {
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

private fun copyLegacyNodeIndexEntries(
    nodeIndexPath: Path,
    offsetWriter: NodeOffsetIndexWriter,
    typeIndexWriter: TypeIndexWriter
) {
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

private data class LegacyNodeIndexStats(
    val maxNodeId: Int,
    val typeCounts: IntArray
)

private fun loadPersistedBackwardGraph(dir: Path): ImmutableGraph? {
    if (!hasPersistedBackwardGraph(dir)) return null
    return try {
        BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())
    } catch (_: Exception) {
        null
    }
}

private fun persistAndLoadBackwardGraph(dir: Path, backward: ImmutableGraph): ImmutableGraph? {
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

private fun compressAndLoadTransientBackwardGraph(backward: ImmutableGraph): ImmutableGraph? {
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

private fun storeAndLoadBackwardGraph(dir: Path, backward: ImmutableGraph): ImmutableGraph {
    BVGraph.store(
        backward,
        dir.resolve(BACKWARD_GRAPH).toString(),
        BVGraph.DEFAULT_WINDOW_SIZE,
        BVGraph.DEFAULT_MAX_REF_COUNT,
        BVGraph.DEFAULT_MIN_INTERVAL_LENGTH,
        BVGraph.DEFAULT_ZETA_K,
        0,
        BACKWARD_COMPRESSION_THREADS
    )
    return BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())
}

private fun hasPersistedBackwardGraph(dir: Path): Boolean =
    Files.isRegularFile(dir.resolve("$BACKWARD_GRAPH.graph")) &&
        Files.isRegularFile(dir.resolve("$BACKWARD_GRAPH.properties"))

private fun moveGraphFiles(sourceDir: Path, targetDir: Path) {
    Files.list(sourceDir).use { paths ->
        paths.forEach { source ->
            Files.move(source, targetDir.resolve(source.fileName), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * Build a cumulative outdegree array for O(1) label offset lookup.
 * `cumulativeOutdeg[i]` = sum of outdegree(0..i-1), so the labels for
 * node `i` start at `forwardLabels[cumulativeOutdeg[i]]`.
 */
private fun buildCumulativeOutdeg(forward: ImmutableGraph): IntArray {
    val numNodes = forward.numNodes()
    val cumOutdeg = IntArray(numNodes + 1)
    for (i in 0 until numNodes) {
        val next = cumOutdeg[i].toLong() + forward.outdegree(i).toLong()
        require(next <= Int.MAX_VALUE) {
            "Graph has too many edges for byte-addressed labels: $next"
        }
        cumOutdeg[i + 1] = next.toInt()
    }
    return cumOutdeg
}

private fun loadCumulativeOutdeg(dir: Path, forward: ImmutableGraph): IntArray {
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

private class NodeDataWriteContext(
    val dataDos: DataOutputStream,
    val idxDos: DataOutputStream,
    val offsetWriter: NodeOffsetIndexWriter,
    val typeIndexWriter: TypeIndexWriter,
    val countingOutput: CountingOutputStream,
    val stringTable: StringTable,
    val classOverviewEdges: ClassOverviewEdgeBuilder
)

private class ForwardDataScratch {
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

    private fun edgeSortKey(target: Int, ordinal: Int): Long =
        target.toLong() shl INT_BITS or (ordinal.toLong() and UNSIGNED_INT_MASK)

    private fun targetFromSortKey(key: Long): Int = (key ushr INT_BITS).toInt()

    private fun ordinalFromSortKey(key: Long): Int = key.toInt()

    private companion object {
        private const val INITIAL_TARGET_CAPACITY = 8
    }
}

internal data class NodeIndexData(
    val nodeOffsets: NodeOffsetIndex,
    val nodeTypeIndex: NodeTypeIndex
)

/**
 * Save and load [Graph] instances using WebGraph compression with native LAW
 * ecosystem tools (dsiutils + sux4j + fastutil).
 *
 * Storage layout:
 * - `forward.*`                -- BVGraph compressed forward adjacency
 * - `backward.*`               -- optional BVGraph compressed backward adjacency, created after first incoming query
 * - `graph.strings`            -- [StringTable] (FrontCodedStringList via BinIO)
 * - `graph.labels`             -- byte[] via [BinIO.storeBytes], 1 byte per arc in BVGraph successor order
 * - `graph.labelprefix`        -- int[] cumulative outdegree values for label lookup
 * - `graph.comparisons`        -- [BranchComparison] data for [ControlFlowEdge]s that carry one
 * - `graph.nodedata`           -- sequential binary node data with string table indices
 * - `graph.metadata`           -- methods, type hierarchy, enums, annotations, branch scopes (string table indices)
 * - `graph.classoverview`      -- class-level call counts and dependency weights
 * - `graph.resources`          -- persisted text resources, including an explicit empty store when none exist
 * - `graph.callsite-string-index` -- optional persisted CSR/trigram search index for mapped CallSites
 */
@Suppress("TooManyFunctions")
object GraphStore {

    /**
     * Graphs with >= 1M nodes use memory-mapped loading to avoid heap pressure.
     * Below this threshold, eager loading provides faster queries at acceptable memory cost.
     */
    private const val MAPPED_THRESHOLD = 1_000_000

    private const val FORWARD_GRAPH = "forward"
    private const val LABELS_FILE = "graph.labels"
    private const val COMPARISONS_FILE = "graph.comparisons"
    private const val NODE_DATA_FILE = "graph.nodedata"
    private const val METADATA_FILE = "graph.metadata"
    internal const val CALL_SITE_STRING_INDEX_FILE = "graph.callsite-string-index"
    private const val NOT_A_DIRECTORY_PREFIX = "Not a directory:"
    internal const val MAPPED_CALL_SITE_INDEX_PREPARATION_PROPERTY =
        "graphite.webgraph.prepareCallSiteStringIndexOnLoad"

    private fun notDirectoryMessage(dir: Path): String = "$NOT_A_DIRECTORY_PREFIX $dir"

    private fun readMetadataMethodCount(metadataFile: Path): Long =
        DataInputStream(BufferedInputStream(metadataFile.toFile().inputStream())).use { dis ->
            NodeSerializer.readMetadataMethodCount(dis).toLong()
        }

    private fun <T> joinLoad(future: CompletableFuture<T>): T =
        try {
            future.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }

    /**
     * Save a graph to disk in WebGraph + native LAW format.
     *
     * Uses a streaming [ImmutableGraph] wrapper over the source [Graph] to avoid
     * copying all edge data into a second adjacency structure
     * ([ArrayListMutableGraph][it.unimi.dsi.webgraph.ArrayListMutableGraph]).
     * For large graphs (millions of nodes) this eliminates the OOM caused by
     * duplicating the entire adjacency list in memory.
     */
    fun save(graph: Graph, dir: Path, compressionThreads: Int = 2) {
        Files.createDirectories(dir)
        Files.deleteIfExists(dir.resolve(CALL_SITE_STRING_INDEX_FILE))

        // 1. Stream nodes: find maxNodeId, count nodes, collect strings
        var maxNodeId = 0
        var nodeCount = 0
        val allStrings = mutableSetOf<String>()
        val nodeTypeCounts = IntArray(UByte.MAX_VALUE.toInt() + 1)
        val classOverviewBuilder = ClassOverviewBuilder()
        for (node in graph.nodes(Node::class.java)) {
            if (node.id.value > maxNodeId) maxNodeId = node.id.value
            nodeCount++
            nodeTypeCounts[NodeSerializer.tagOf(node)]++
            collectSingleNodeStrings(node, allStrings)
            if (node is CallSiteNode) {
                classOverviewBuilder.add(node)
            }
        }
        val classOverviewCounts = classOverviewBuilder.topClassCounts(ClassOverviewStore.MAX_PERSISTED_CLASSES)
        val classOverviewEdges = ClassOverviewEdgeBuilder(classOverviewCounts.keys)

        // 2. Collect metadata
        val metadata = collectMetadata(graph)
        NodeSerializer.collectMetadataStrings(metadata, allStrings)
        ClassOverviewStore.collectStrings(
            ClassOverview(
                classCounts = classOverviewCounts,
                classEdges = emptyMap(),
                callSiteCount = classOverviewBuilder.callSiteCount()
            ),
            allStrings
        )
        val stringTable = StringTable.build(allStrings, dir)
        allStrings.clear()

        // 3. Build forward adjacency + labels + comparisons
        val numNodes = maxNodeId + 1
        val comparisonMap = mutableMapOf<Long, BranchComparison>()
        val (forwardAdj, labelArray) = if (graph is MmapGraph) {
            buildForwardData(graph, numNodes, comparisonMap)
        } else {
            buildForwardData(graph, numNodes, comparisonMap)
        }

        // 4. Store BVGraph (forward only)
        val forwardGraph = PrecomputedImmutableGraph(forwardAdj)
        BVGraph.store(
            forwardGraph, dir.resolve(FORWARD_GRAPH).toString(),
            BVGraph.DEFAULT_WINDOW_SIZE, BVGraph.DEFAULT_MAX_REF_COUNT,
            BVGraph.DEFAULT_MIN_INTERVAL_LENGTH, BVGraph.DEFAULT_ZETA_K,
            0, compressionThreads
        )

        // 5. Store labels + comparisons
        BinIO.storeBytes(labelArray, dir.resolve(LABELS_FILE).toString())
        BinIO.storeInts(forwardAdj.offsets, dir.resolve(LABEL_PREFIX_FILE).toString())
        DataOutputStream(BufferedOutputStream(dir.resolve(COMPARISONS_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeComparisons(dos, comparisonMap)
        }

        // 6. Write nodedata + nodeindex simultaneously
        writeNodeDataAndIndex(graph, dir, nodeCount, maxNodeId, nodeTypeCounts, stringTable, classOverviewEdges)

        // 7. Save metadata
        DataOutputStream(BufferedOutputStream(dir.resolve(METADATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.saveMetadata(metadata, dos, stringTable)
        }

        // 8. Save class-level overview summary for explorer routes
        ClassOverviewStore.save(
            ClassOverview(
                classCounts = classOverviewCounts,
                classEdges = classOverviewEdges.build(),
                callSiteCount = classOverviewBuilder.callSiteCount()
            ),
            dir,
            stringTable
        )

        // 9. Save persisted text resources for loaded-graph access
        PersistedResourceStore.save(graph, dir)

        // 10. Build the query-only CallSite index once and persist it for mapped loads.
        if (classOverviewBuilder.callSiteCount() > 0L && mappedCallSiteStringIndexPreparationEnabled()) {
            (loadMapped(
                dir,
                prepareCallSiteStringIndex = true,
                persistentCallSiteStringIndex = true
            ) as Closeable).use { }
        }
    }

    private fun writeNodeDataAndIndex(
        graph: Graph,
        dir: Path,
        nodeCount: Int,
        maxNodeId: Int,
        nodeTypeCounts: IntArray,
        stringTable: StringTable,
        classOverviewEdges: ClassOverviewEdgeBuilder
    ) {
        CountingOutputStream(BufferedOutputStream(dir.resolve(NODE_DATA_FILE).toFile().outputStream())).use { cos ->
            val dataDos = DataOutputStream(cos)
            DataOutputStream(BufferedOutputStream(dir.resolve(NODE_INDEX_FILE).toFile().outputStream())).use { idxDos ->
                writeNodeDataAndIndex(
                    graph,
                    dir,
                    nodeCount,
                    maxNodeId,
                    nodeTypeCounts,
                    dataDos,
                    idxDos,
                    cos,
                    stringTable,
                    classOverviewEdges
                )
            }
        }
    }

    @Suppress("LongParameterList")
    private fun writeNodeDataAndIndex(
        graph: Graph,
        dir: Path,
        nodeCount: Int,
        maxNodeId: Int,
        nodeTypeCounts: IntArray,
        dataDos: DataOutputStream,
        idxDos: DataOutputStream,
        cos: CountingOutputStream,
        stringTable: StringTable,
        classOverviewEdges: ClassOverviewEdgeBuilder
    ) {
        NodeOffsetIndexWriter(dir.resolve(NODE_OFFSETS_FILE), maxNodeId + 1).use { offsetWriter ->
            TypeIndexWriter(dir.resolve(TYPE_INDEX_FILE), nodeTypeCounts).use { typeIndexWriter ->
                NodeSerializer.writeHeader(dataDos, NodeSerializer.MAGIC_NODEDATA)
                dataDos.writeInt(nodeCount)
                NodeSerializer.writeHeader(idxDos, NodeSerializer.MAGIC_NODEINDEX)
                idxDos.writeInt(nodeCount)
                writeNodes(
                    graph,
                    NodeDataWriteContext(dataDos, idxDos, offsetWriter, typeIndexWriter, cos, stringTable, classOverviewEdges)
                )
            }
        }
    }

    private fun writeNodes(graph: Graph, context: NodeDataWriteContext) {
        for (node in graph.nodes(Node::class.java)) {
            val offset = context.countingOutput.bytesWritten
            val tag = NodeSerializer.writeNode(context.dataDos, node, context.stringTable)
            context.idxDos.writeInt(node.id.value)
            context.idxDos.writeByte(tag)
            context.idxDos.writeLong(offset)
            context.offsetWriter.write(node.id.value, offset)
            context.typeIndexWriter.write(tag, node.id.value)
            if (node is CallSiteNode) {
                context.classOverviewEdges.add(node)
            }
        }
    }

    /**
     * Load a graph from disk.
     *
     * @param mode loading strategy; defaults to [LoadMode.AUTO] which selects
     *   based on graph size (< 1M nodes → eager, >= 1M → mapped).
     */
    fun load(dir: Path, mode: LoadMode = LoadMode.AUTO): Graph {
        require(Files.isDirectory(dir)) { notDirectoryMessage(dir) }
        return when (mode) {
            LoadMode.EAGER -> loadEager(dir)
            LoadMode.MAPPED -> { ensureNodeIndex(dir); loadMapped(dir) }
            LoadMode.AUTO -> {
                val (_, nodeCount) = readNodeDataHeader(dir)
                if (nodeCount < MAPPED_THRESHOLD) {
                    loadEager(dir)
                } else {
                    ensureNodeIndex(dir); loadMapped(dir)
                }
            }
        }
    }

    /**
     * Loading strategy for [load].
     */
    enum class LoadMode {
        /** All nodes deserialized into JVM heap. Fastest queries, highest memory. */
        EAGER,
        /** Node data memory-mapped via OS page cache. 75% less heap, slightly slower queries. */
        MAPPED,
        /** Auto-select based on graph size (< 1M nodes → [EAGER], >= 1M → [MAPPED]). */
        AUTO
    }

    /**
     * Load all nodes eagerly into JVM heap. Best for graphs < 1M nodes.
     */
    private fun loadEager(dir: Path): Graph {
        val (nodeDataVersion, _) = readNodeDataHeader(dir)
        val forwardFuture = CompletableFuture.supplyAsync { BVGraph.load(dir.resolve(FORWARD_GRAPH).toString()) }
        val stringTableFuture = CompletableFuture.supplyAsync { StringTable.load(dir) }
        val labelsFuture = CompletableFuture.supplyAsync { BinIO.loadBytes(dir.resolve(LABELS_FILE).toString()) }

        val forward = forwardFuture.join()
        val stringTable = stringTableFuture.join()
        val labelBytes = labelsFuture.join()

        val cumulativeOutdeg = loadCumulativeOutdeg(dir, forward)
        val backward = lazy { loadBackward(dir, forward) }

        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }
        val comparisonLookup = MapBranchComparisonLookup(comparisonMap)

        val nodesById = mutableMapOf<Int, Node>()
        DataInputStream(BufferedInputStream(dir.resolve(NODE_DATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA)
            val count = dis.readInt()
            repeat(count) {
                val node = NodeSerializer.readNode(dis, stringTable, nodeDataVersion)
                nodesById[node.id.value] = node
            }
        }

        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }
        val classOverview = PersistedClassOverviewProvider(dir, stringTable)::load

        return WebGraphBackedGraph(
            forward,
            backward,
            nodesById,
            nodeDataVersion,
            labelBytes,
            cumulativeOutdeg,
            comparisonLookup,
            metadata,
            classOverview,
            PersistedResourceStore.load(dir)
        )
    }

    /**
     * Load a graph with memory-mapped node data. Forward edge structures and
     * edge labels are opened during load so the returned graph is ready for
     * forward traversal without hidden first-query initialization.
     *
     * The OS page cache manages which node pages are in physical RAM.
     * No JVM heap allocation for node data, and no system calls per node access
     * after the initial page fault.
     *
     * Persisted CallSite string and trigram indexes are restored lazily on the first relevant query,
     * so unrelated mapped-graph queries do not retain their memory. Set
     * `graphite.webgraph.prepareCallSiteStringIndexOnLoad=true` to prepare before this method returns,
     * or `false` to disable persisted restore while retaining the in-memory lazy-build fallback.
     */
    fun loadMapped(dir: Path): Graph = loadMapped(
        dir,
        prepareCallSiteStringIndex = mappedCallSiteStringIndexPreparationEnabled(),
        persistentCallSiteStringIndex = mappedCallSiteStringIndexPersistenceEnabled()
    )

    @Suppress("TooGenericExceptionCaught")
    private fun loadMapped(
        dir: Path,
        prepareCallSiteStringIndex: Boolean,
        persistentCallSiteStringIndex: Boolean
    ): Graph {
        require(Files.isDirectory(dir)) { notDirectoryMessage(dir) }

        val (nodeDataVersion, _) = readNodeDataHeader(dir)
        val metadataFile = dir.resolve(METADATA_FILE)
        val forwardFuture = CompletableFuture.supplyAsync { BVGraph.load(dir.resolve(FORWARD_GRAPH).toString()) }
        val stringTableFuture = CompletableFuture.supplyAsync { StringTable.load(dir) }
        val nodeIndexFuture = CompletableFuture.supplyAsync { readMappedNodeIndex(dir) }
        val labelsFuture = CompletableFuture.supplyAsync { BinIO.loadBytes(dir.resolve(LABELS_FILE).toString()) }
        val methodCountFuture = CompletableFuture.supplyAsync { readMetadataMethodCount(metadataFile) }
        val comparisonFuture = CompletableFuture.supplyAsync {
            MappedBranchComparisonLookup.load(dir.resolve(COMPARISONS_FILE))
        }

        val nodeDataPath = dir.resolve(NODE_DATA_FILE)
        val channel = FileChannel.open(nodeDataPath, StandardOpenOption.READ)
        val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        channel.close()

        val forward = joinLoad(forwardFuture)
        val labelBytes = joinLoad(labelsFuture)
        val stringTable = joinLoad(stringTableFuture)
        val nodeIndex = joinLoad(nodeIndexFuture)
        val methodCount = joinLoad(methodCountFuture)
        val comparisonLookup = joinLoad(comparisonFuture)
        val backward = lazy { loadBackward(dir, forward) }
        val cumulativeOutdeg = loadCumulativeOutdeg(dir, forward)
        val metadata = lazy {
            DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
                NodeSerializer.loadMetadata(dis, stringTable)
            }
        }
        val classOverview = PersistedClassOverviewProvider(dir, stringTable)::load

        val graph = MappedWebGraphBackedGraph(
            forward = forward,
            backward = backward,
            mappedNodeData = mappedBuffer,
            nodeDataVersion = nodeDataVersion,
            stringTable = stringTable,
            nodeOffsets = nodeIndex.nodeOffsets,
            nodeTypeIndex = nodeIndex.nodeTypeIndex,
            forwardLabels = labelBytes,
            cumulativeOutdeg = cumulativeOutdeg,
            edgeCount = labelBytes.size.toLong(),
            metadataFile = metadataFile.toFile(),
            callSiteStringIndexFile = dir.resolve(CALL_SITE_STRING_INDEX_FILE),
            persistentCallSiteStringIndexEnabled = persistentCallSiteStringIndex,
            methodCount = methodCount,
            comparisonLookup = comparisonLookup,
            metadata = metadata,
            classOverviewProvider = classOverview,
            resourceAccessor = lazy { PersistedResourceStore.load(dir) }
        )
        if (prepareCallSiteStringIndex) {
            try {
                if (graph.prepareCallSiteStringIndex()) graph.persistPreparedCallSiteStringIndex()
            } catch (error: Exception) {
                graph.close()
                throw error
            }
        }
        return graph
    }

    /**
     * Build forward adjacency, labels, and comparisons in 2 sequential passes.
     *
     * Pass 1: count unique outdegree per node.
     * Pass 2: fill sorted targets + encode labels + extract comparisons.
     */
    private fun buildForwardData(
        graph: Graph,
        numNodes: Int,
        comparisonMap: MutableMap<Long, BranchComparison>
    ): Pair<PrecomputedAdjacency, ByteArray> {
        val scratch = ForwardDataScratch()
        val outdeg = IntArray(numNodes)
        for (node in 0 until numNodes) {
            scratch.resetTargets()
            for (edge in graph.outgoing(NodeId(node))) {
                scratch.addTarget(edge.to.value)
            }
            outdeg[node] = scratch.uniqueTargetCount()
        }

        val offsets = IntArray(numNodes + 1)
        for (i in 0 until numNodes) {
            val next = offsets[i].toLong() + outdeg[i].toLong()
            require(next <= Int.MAX_VALUE) {
                "Graph has too many edges for in-memory adjacency: $next"
            }
            offsets[i + 1] = next.toInt()
        }
        val totalArcs = offsets[numNodes]
        val targets = IntArray(totalArcs)
        val labels = ByteArray(totalArcs)

        for (node in 0 until numNodes) {
            scratch.resetEdges()
            for (edge in graph.outgoing(NodeId(node))) {
                scratch.addEdge(edge)
            }
            val written = scratch.emitSortedEdges(node, offsets[node], targets, labels, comparisonMap)
            check(written == outdeg[node]) {
                "Forward adjacency mismatch for node $node: wrote $written edges, expected ${outdeg[node]}"
            }
        }

        return PrecomputedAdjacency(numNodes, targets, offsets) to labels
    }

    private fun buildForwardData(
        graph: MmapGraph,
        numNodes: Int,
        comparisonMap: MutableMap<Long, BranchComparison>
    ): Pair<PrecomputedAdjacency, ByteArray> {
        val scratch = ForwardDataScratch()
        val outdeg = IntArray(numNodes)
        for (node in 0 until numNodes) {
            scratch.resetTargets()
            graph.forEachOutgoingTarget(node) { target ->
                scratch.addTarget(target)
            }
            outdeg[node] = scratch.uniqueTargetCount()
        }

        val offsets = IntArray(numNodes + 1)
        for (i in 0 until numNodes) {
            val next = offsets[i].toLong() + outdeg[i].toLong()
            require(next <= Int.MAX_VALUE) {
                "Graph has too many edges for in-memory adjacency: $next"
            }
            offsets[i + 1] = next.toInt()
        }
        val totalArcs = offsets[numNodes]
        val targets = IntArray(totalArcs)
        val labels = ByteArray(totalArcs)

        for (node in 0 until numNodes) {
            scratch.resetEdges()
            graph.forEachOutgoingEdge(node) { edge ->
                scratch.addEdge(edge)
            }
            val written = scratch.emitSortedEdges(node, offsets[node], targets, labels, comparisonMap)
            check(written == outdeg[node]) {
                "Forward adjacency mismatch for node $node: wrote $written edges, expected ${outdeg[node]}"
            }
        }

        return PrecomputedAdjacency(numNodes, targets, offsets) to labels
    }

    /**
     * Collect strings from a single node (avoids building a list of all nodes).
     */
    private fun collectSingleNodeStrings(node: Node, dest: MutableSet<String>) {
        NodeSerializer.collectNodeStrings(node, dest)
    }

    /**
     * Flat sorted adjacency: targets[offsets[node]..offsets[node+1]] are the
     * sorted, deduplicated successors of node. Zero per-node allocation on access.
     */
    internal class PrecomputedAdjacency(
        val numNodes: Int,
        val targets: IntArray,
        val offsets: IntArray
    ) {
        fun outdegree(node: Int): Int = offsets[node + 1] - offsets[node]
        fun successorArray(node: Int): IntArray {
            val start = offsets[node]
            val end = offsets[node + 1]
            return targets.copyOfRange(start, end)
        }
    }

    /**
     * Wraps a [PrecomputedAdjacency] as a WebGraph [ImmutableGraph] for BVGraph storage.
     * Zero allocation per node access -- successorArray returns a copy of the pre-sorted slice.
     */
    internal class PrecomputedImmutableGraph(
        private val adj: PrecomputedAdjacency
    ) : ImmutableGraph() {
        override fun numNodes(): Int = adj.numNodes
        override fun numArcs(): Long = adj.offsets[adj.numNodes].toLong()
        override fun randomAccess(): Boolean = true
        override fun outdegree(x: Int): Int = adj.outdegree(x)
        override fun successorArray(x: Int): IntArray = adj.successorArray(x)
        override fun successors(x: Int): LazyIntIterator = LazyIntIterators.wrap(successorArray(x))
        override fun copy(): ImmutableGraph = this
    }

    /** Build backward adjacency from forward BVGraph. */
    private fun loadBackward(dir: Path, forward: ImmutableGraph): ImmutableGraph =
        loadPersistedBackwardGraph(dir) ?: run {
            val backward = buildBackwardFromForward(forward)
            persistAndLoadBackwardGraph(dir, backward) ?: compressAndLoadTransientBackwardGraph(backward) ?: backward
        }

    /**
     * Build backward (transpose) adjacency from forward BVGraph.
     * Two passes over the compressed forward graph -- no intermediate collections.
     * Memory: IntArray(totalEdges) + IntArray(numNodes+1) + IntArray(numNodes) work array.
     */
    private fun buildBackwardFromForward(forward: ImmutableGraph): PrecomputedImmutableGraph {
        val numNodes = forward.numNodes()

        // Pass 1: count indegree
        val backwardDeg = IntArray(numNodes)
        for (node in 0 until numNodes) {
            val succs = forward.successorArray(node)
            val outdeg = forward.outdegree(node)
            for (i in 0 until outdeg) {
                backwardDeg[succs[i]]++
            }
        }

        // Build offsets
        val offsets = IntArray(numNodes + 1)
        for (i in 0 until numNodes) {
            val next = offsets[i].toLong() + backwardDeg[i].toLong()
            require(next <= Int.MAX_VALUE) {
                "Graph has too many edges for in-memory transpose: $next"
            }
            offsets[i + 1] = next.toInt()
        }

        // Pass 2: fill targets
        val targets = IntArray(offsets[numNodes])
        val fillPos = IntArray(numNodes)
        for (node in 0 until numNodes) {
            val succs = forward.successorArray(node)
            val outdeg = forward.outdegree(node)
            for (i in 0 until outdeg) {
                val to = succs[i]
                targets[offsets[to] + fillPos[to]] = node
                fillPos[to]++
            }
        }

        // Sort each node's predecessors
        for (node in 0 until numNodes) {
            val start = offsets[node]
            val end = offsets[node + 1]
            java.util.Arrays.sort(targets, start, end)
        }

        return PrecomputedImmutableGraph(PrecomputedAdjacency(numNodes, targets, offsets))
    }

    /**
     * Ensure the `graph.nodeindex` file exists for the given graph directory.
     * If it doesn't exist, scans `graph.nodedata` to build it.
     * This is idempotent — safe to call on graphs that already have the index.
     */
    fun ensureNodeIndex(dir: Path) {
        val indexFile = dir.resolve(NODE_INDEX_FILE)
        if (Files.exists(indexFile)) return
        val stringTable = StringTable.load(dir)
        buildNodeIndex(dir.resolve(NODE_DATA_FILE), indexFile, stringTable)
    }

    internal fun buildNodeIndex(nodeDataPath: Path, nodeIndexPath: Path, stringTable: StringTable) {
        // Stream directly: read nodedata, write nodeindex entry by entry (no intermediate list)
        RandomAccessFile(nodeDataPath.toFile(), "r").use { raf ->
            val nodeDataVersion = NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
            val count = raf.readInt()
            DataOutputStream(BufferedOutputStream(nodeIndexPath.toFile().outputStream())).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEINDEX)
                dos.writeInt(count)
                repeat(count) {
                    val offset = raf.filePointer
                    val nodeId = raf.readInt()
                    val tag = raf.readByte().toInt()
                    dos.writeInt(nodeId)
                    dos.writeByte(tag)
                    dos.writeLong(offset)
                    // Seek back and read full node to advance raf past the record
                    raf.seek(offset)
                    val dis = object : DataInputStream(object : InputStream() {
                        override fun read(): Int = raf.read()
                        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
                    }) {}
                    NodeSerializer.readNode(dis, stringTable, nodeDataVersion)
                }
            }
        }
    }

    private fun readNodeDataHeader(dir: Path): Pair<Int, Int> {
        return DataInputStream(BufferedInputStream(dir.resolve(NODE_DATA_FILE).toFile().inputStream())).use { dis ->
            val version = NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA)
            version to dis.readInt()
        }
    }

    internal fun collectMetadata(graph: Graph): GraphMetadata {
        fun collectReferencedTypes(): MutableSet<TypeDescriptor> {
            val allTypes = mutableSetOf<TypeDescriptor>()
            graph.nodes(Node::class.java).forEach { node ->
                when (node) {
                    is LocalVariable -> {
                        allTypes.add(node.type)
                        allTypes.add(node.method.declaringClass)
                    }
                    is FieldNode -> {
                        allTypes.add(node.descriptor.declaringClass)
                        allTypes.add(node.descriptor.type)
                    }
                    is ParameterNode -> {
                        allTypes.add(node.type)
                        allTypes.add(node.method.declaringClass)
                    }
                    is ReturnNode -> {
                        node.actualType?.let { allTypes.add(it) }
                        allTypes.add(node.method.declaringClass)
                        allTypes.add(node.method.returnType)
                    }
                    is CallSiteNode -> {
                        allTypes.add(node.callee.declaringClass)
                        allTypes.add(node.callee.returnType)
                        allTypes.add(node.caller.declaringClass)
                    }
                    is EnumConstant -> allTypes.add(node.enumType)
                    is AnnotationNode -> {}
                    else -> {}
                }
            }
            graph.methods(MethodPattern()).forEach { method ->
                allTypes.add(method.declaringClass)
                allTypes.add(method.returnType)
                method.parameterTypes.forEach { allTypes.add(it) }
            }
            return allTypes
        }

        fun collectMemberAnnotations(): Map<String, Map<String, Map<String, Any?>>> {
            val memberAnnotations = mutableMapOf<String, Map<String, Map<String, Any?>>>()
            val classMembers = mutableSetOf<Pair<String, String>>()
            graph.nodes(Node::class.java).forEach { node ->
                when (node) {
                    is FieldNode -> classMembers.add(node.descriptor.declaringClass.className to node.descriptor.name)
                    is CallSiteNode -> classMembers.add(node.callee.declaringClass.className to node.callee.name)
                    is ParameterNode -> classMembers.add(node.method.declaringClass.className to node.method.name)
                    is ReturnNode -> classMembers.add(node.method.declaringClass.className to node.method.name)
                    is AnnotationNode -> {}
                    else -> {}
                }
            }
            // Also add <class> level annotations
            val allClasses = classMembers.map { it.first }.toSet()
            for (className in allClasses) {
                classMembers.add(className to "<class>")
            }
            for ((className, memberName) in classMembers) {
                val annotations = graph.memberAnnotations(className, memberName)
                if (annotations.isNotEmpty()) {
                    memberAnnotations["$className#$memberName"] = annotations
                }
            }
            return memberAnnotations
        }

        // Collect type hierarchy
        val supertypes = mutableMapOf<String, Set<TypeDescriptor>>()
        val subtypes = mutableMapOf<String, Set<TypeDescriptor>>()
        val hierarchyTypeNames = graph.typeHierarchyTypes()
        val allTypes = if (hierarchyTypeNames.isNotEmpty()) {
            hierarchyTypeNames.mapTo(mutableSetOf()) { TypeDescriptor(it) }
        } else {
            collectReferencedTypes()
        }

        for (type in allTypes) {
            val sups = graph.supertypes(type).toSet()
            if (sups.isNotEmpty()) supertypes[type.className] = sups
            val subs = graph.subtypes(type).toSet()
            if (subs.isNotEmpty()) subtypes[type.className] = subs
        }

        // Collect methods
        val methods = graph.methods(MethodPattern())
            .associateBy { it.signature }

        // Collect enum values - we can't enumerate all enum keys from Graph interface,
        // so we extract from EnumConstant nodes
        val enumValues = mutableMapOf<String, List<Any?>>()
        graph.nodes(EnumConstant::class.java).forEach { ec ->
            val key = "${ec.enumType.className}#${ec.enumName}"
            if (key !in enumValues) {
                val values = graph.enumValues(ec.enumType.className, ec.enumName)
                if (values != null) enumValues[key] = values
            }
        }

        val memberAnnotations = graph.memberAnnotationIndex()?.toMap()
            ?: collectMemberAnnotations()

        // Collect branch scopes
        val branchScopes = graph.branchScopes().map { bs ->
            BranchScopeData(
                conditionNodeId = bs.conditionNodeId.value,
                method = bs.method,
                comparison = bs.comparison,
                trueBranchNodeIds = bs.trueBranchNodeIds.toIntArray(),
                falseBranchNodeIds = bs.falseBranchNodeIds.toIntArray()
            )
        }.toList()

        return GraphMetadata(
            methods = methods,
            supertypes = supertypes,
            subtypes = subtypes,
            enumValues = enumValues,
            classOrigins = graph.classOrigins(),
            artifactDependencies = graph.artifactDependencies(),
            memberAnnotations = memberAnnotations,
            branchScopes = branchScopes
        )
    }

}
