package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.util.FrontCodedStringList
import it.unimi.dsi.webgraph.BVGraph
import it.unimi.dsi.webgraph.ImmutableGraph
import it.unimi.dsi.webgraph.LazyIntIterator
import it.unimi.dsi.webgraph.LazyIntIterators
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture

/**
 * Storage format for persisted graphs.
 */
enum class StorageFormat {
    /** Raw flat arrays in a single file. Fast save, mmap-friendly load. */
    FLAT,
    /** BVGraph compressed in a directory. Small disk, slower save. Default. */
    COMPRESSED
}

/**
 * Save and load [Graph] instances using either a flat single-file format or
 * BVGraph compression with native LAW ecosystem tools (dsiutils + sux4j + fastutil).
 *
 * ### Flat format (single file `graph.dat`)
 *
 * ```
 * [magic: int = 0x47524654]   // "GRFT"
 * [version: int = 1]
 * [sectionCount: int = 8]
 * [index: 8 x (offset: long, length: long)]  = 128 bytes
 * [section 0: forward.targets]
 * [section 1: forward.offsets]
 * [section 2: graph.labels]
 * [section 3: graph.nodedata]
 * [section 4: graph.nodeindex]
 * [section 5: graph.metadata]
 * [section 6: graph.comparisons]
 * [section 7: graph.strings]
 * ```
 *
 * ### Compressed format (directory layout)
 *
 * - `forward.*`                -- BVGraph adjacency (forward only; backward is rebuilt at load time)
 * - `graph.strings`            -- [StringTable] (FrontCodedStringList via BinIO)
 * - `graph.labels`             -- byte[] via [BinIO.storeBytes], 1 byte per arc in BVGraph successor order
 * - `graph.comparisons`        -- [BranchComparison] data for [ControlFlowEdge]s that carry one
 * - `graph.nodedata`           -- sequential binary node data with string table indices
 * - `graph.metadata`           -- methods, type hierarchy, enums, annotations, branch scopes (string table indices)
 */
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
    private const val NODE_INDEX_FILE = "graph.nodeindex"
    private const val METADATA_FILE = "graph.metadata"

    // Flat file format constants
    private const val FLAT_MAGIC = 0x47524654          // "GRFT"
    private const val FLAT_VERSION = 1
    private const val SECTION_FORWARD_TARGETS = 0
    private const val SECTION_FORWARD_OFFSETS = 1
    private const val SECTION_LABELS = 2
    private const val SECTION_NODEDATA = 3
    private const val SECTION_NODEINDEX = 4
    private const val SECTION_METADATA = 5
    private const val SECTION_COMPARISONS = 6
    private const val SECTION_STRINGS = 7
    private const val SECTION_COUNT = 8

    /**
     * Save a graph to disk.
     *
     * When [format] is [StorageFormat.FLAT], the graph is written as a single flat file
     * at [path]. Raw int/long arrays are used for adjacency -- no BVGraph compression.
     *
     * When [format] is [StorageFormat.COMPRESSED] (default), the graph is written as a directory
     * at [path] using BVGraph compression. [compressionThreads] controls BVGraph's internal
     * parallelism.
     */
    fun save(graph: Graph, path: Path, format: StorageFormat = StorageFormat.COMPRESSED, compressionThreads: Int = 2) {
        when (format) {
            StorageFormat.FLAT -> saveFlat(graph, path)
            StorageFormat.COMPRESSED -> saveCompressed(graph, path, compressionThreads)
        }
    }

    /**
     * Load a graph from disk.
     *
     * Auto-detects the storage format:
     * - If [path] is a regular file with the flat format magic header, loads as flat.
     * - If [path] is a directory, loads as compressed (BVGraph) format.
     *
     * @param mode loading strategy for compressed format; defaults to [LoadMode.AUTO] which selects
     *   based on graph size (< 1M nodes -> eager, >= 1M -> mapped). Ignored for flat format.
     */
    fun load(path: Path, mode: LoadMode = LoadMode.AUTO): Graph {
        return if (Files.isRegularFile(path) && isFlatFormat(path)) {
            loadFlat(path)
        } else if (Files.isDirectory(path)) {
            loadFromDirectory(path, mode)
        } else {
            throw IllegalArgumentException("Not a graph file or directory: $path")
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
        /** Auto-select based on graph size (< 1M nodes -> [EAGER], >= 1M -> [MAPPED]). */
        AUTO
    }

    // ========================================================================
    // Flat format: save
    // ========================================================================

    private fun saveFlat(graph: Graph, path: Path) {
        val tmpDir = Files.createTempDirectory("graphite-flat")
        try {
            saveFlatToDirectory(graph, tmpDir)
            packageToFlatFile(tmpDir, path)
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Write graph data as individual files in a temp directory using raw flat adjacency
     * (no BVGraph compression). The files are later packaged into a single flat file.
     */
    private fun saveFlatToDirectory(graph: Graph, dir: Path) {
        Files.createDirectories(dir)

        // 1. Stream nodes: find maxNodeId, count nodes, collect strings
        var maxNodeId = 0
        var nodeCount = 0
        val allStrings = mutableSetOf<String>()
        for (node in graph.nodes(Node::class.java)) {
            if (node.id.value > maxNodeId) maxNodeId = node.id.value
            nodeCount++
            collectSingleNodeStrings(node, allStrings)
        }

        // 2. Collect metadata
        val metadata = collectMetadata(graph)
        NodeSerializer.collectMetadataStrings(metadata, allStrings)
        val stringTable = StringTable.build(allStrings, dir)
        allStrings.clear()

        // 3. Build forward adjacency + labels + comparisons in 2 passes
        val numNodes = maxNodeId + 1
        val forwardData = buildForwardData(graph, numNodes)
        val forwardAdj = forwardData.adjacency
        val labelArray = forwardData.labels
        val comparisonMap = forwardData.comparisons

        // 4. Write raw flat adjacency (instead of BVGraph)
        DataOutputStream(BufferedOutputStream(dir.resolve("forward.targets").toFile().outputStream())).use { dos ->
            dos.writeInt(forwardAdj.targets.size)
            for (t in forwardAdj.targets) dos.writeInt(t)
        }
        DataOutputStream(BufferedOutputStream(dir.resolve("forward.offsets").toFile().outputStream())).use { dos ->
            dos.writeInt(forwardAdj.offsets.size)
            for (o in forwardAdj.offsets) dos.writeLong(o)
        }
        // 5. Write labels as raw: count (int) + bytes
        DataOutputStream(BufferedOutputStream(dir.resolve(LABELS_FILE).toFile().outputStream())).use { dos ->
            dos.writeInt(labelArray.size)
            dos.write(labelArray)
        }

        // 6. Store comparisons
        DataOutputStream(BufferedOutputStream(dir.resolve(COMPARISONS_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeComparisons(dos, comparisonMap)
        }

        // 7. Write nodes
        DataOutputStream(BufferedOutputStream(dir.resolve(NODE_DATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEDATA)
            dos.writeInt(nodeCount)
            for (node in graph.nodes(Node::class.java)) {
                NodeSerializer.writeNode(dos, node, stringTable)
            }
        }

        // 8. Build node index
        buildNodeIndex(dir.resolve(NODE_DATA_FILE), dir.resolve(NODE_INDEX_FILE), stringTable)

        // 9. Save metadata
        DataOutputStream(BufferedOutputStream(dir.resolve(METADATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.saveMetadata(metadata, dos, stringTable)
        }
    }

    /**
     * Package individual section files from a temp directory into a single flat file.
     */
    private fun packageToFlatFile(dir: Path, outputPath: Path) {
        // Ensure parent directory exists for the output file
        outputPath.parent?.let { Files.createDirectories(it) }

        val sectionFiles = listOf(
            "forward.targets",
            "forward.offsets",
            LABELS_FILE,
            NODE_DATA_FILE,
            NODE_INDEX_FILE,
            METADATA_FILE,
            COMPARISONS_FILE,
            StringTable.STRINGS_FILE_NAME
        )

        RandomAccessFile(outputPath.toFile(), "rw").use { raf ->
            // Write header
            raf.writeInt(FLAT_MAGIC)
            raf.writeInt(FLAT_VERSION)
            raf.writeInt(SECTION_COUNT)

            // Reserve space for the section index (8 sections x 2 longs = 128 bytes)
            val indexPos = raf.filePointer
            repeat(SECTION_COUNT * 2) { raf.writeLong(0) }

            val offsets = LongArray(SECTION_COUNT)
            val lengths = LongArray(SECTION_COUNT)

            // Write each section's content
            for (i in sectionFiles.indices) {
                val file = dir.resolve(sectionFiles[i]).toFile()
                offsets[i] = raf.filePointer
                lengths[i] = file.length()
                file.inputStream().buffered().use { input ->
                    val buf = ByteArray(65536)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        raf.write(buf, 0, read)
                    }
                }
            }

            // Go back and fill in the section index
            raf.seek(indexPos)
            for (i in 0 until SECTION_COUNT) {
                raf.writeLong(offsets[i])
                raf.writeLong(lengths[i])
            }
        }
    }

    // ========================================================================
    // Flat format: load
    // ========================================================================

    /**
     * Check if the file at [path] starts with the flat format magic header.
     */
    private fun isFlatFormat(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        return try {
            DataInputStream(BufferedInputStream(path.toFile().inputStream())).use { dis ->
                dis.readInt() == FLAT_MAGIC
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Load a graph from a flat single-file format.
     * Mmaps the entire file and reads sections from their indexed offsets.
     */
    private fun loadFlat(path: Path): Graph {
        val channel = FileChannel.open(path, StandardOpenOption.READ)
        val fileSize = channel.size()
        val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize)
        channel.close()

        // Read header
        val magic = mapped.getInt()
        require(magic == FLAT_MAGIC) { "Not a flat graph file: $path (magic=0x${magic.toString(16)})" }
        @Suppress("UNUSED_VARIABLE")
        val version = mapped.getInt()
        val sectionCount = mapped.getInt()
        require(sectionCount == SECTION_COUNT) { "Expected $SECTION_COUNT sections, got $sectionCount" }

        // Read section index (interleaved: offset, length, offset, length, ...)
        val sectionOffsets = LongArray(sectionCount)
        val sectionLengths = LongArray(sectionCount)
        for (i in 0 until sectionCount) {
            sectionOffsets[i] = mapped.getLong()
            sectionLengths[i] = mapped.getLong()
        }

        // Helper to extract a section as a byte array
        fun sectionBytes(index: Int): ByteArray {
            val off = sectionOffsets[index].toInt()
            val len = sectionLengths[index].toInt()
            val bytes = ByteArray(len)
            val dup = mapped.duplicate()
            dup.position(off)
            dup.get(bytes)
            return bytes
        }

        // Section 0: forward targets
        val targetsBuf = ByteBuffer.wrap(sectionBytes(SECTION_FORWARD_TARGETS))
        val targetCount = targetsBuf.getInt()
        val forwardTargets = IntArray(targetCount) { targetsBuf.getInt() }

        // Section 1: forward offsets
        val offsetsBuf = ByteBuffer.wrap(sectionBytes(SECTION_FORWARD_OFFSETS))
        val offsetCount = offsetsBuf.getInt()
        val forwardOffsets = LongArray(offsetCount) { offsetsBuf.getLong() }

        val numNodes = forwardOffsets.size - 1
        val forward = PrecomputedImmutableGraph(PrecomputedAdjacency(numNodes, forwardTargets, forwardOffsets))
        val backward = buildBackwardFromForward(forward)
        val cumulativeOutdeg = forwardOffsets  // same data

        // Section 2: labels (raw: count + bytes)
        val labelsBuf = ByteBuffer.wrap(sectionBytes(SECTION_LABELS))
        val labelCount = labelsBuf.getInt()
        val forwardLabels = ByteArray(labelCount)
        labelsBuf.get(forwardLabels)

        // Section 7: strings (needed before nodedata and metadata)
        val stringsBytes = sectionBytes(SECTION_STRINGS)
        @Suppress("UNCHECKED_CAST")
        val fcl = ObjectInputStream(ByteArrayInputStream(stringsBytes)).use {
            it.readObject() as FrontCodedStringList
        }
        val stringTable = StringTable.fromFrontCodedStringList(fcl)

        // Section 6: comparisons
        val compDis = DataInputStream(ByteArrayInputStream(sectionBytes(SECTION_COMPARISONS)))
        val comparisonMap = NodeSerializer.readComparisons(compDis)

        // Section 3: nodedata
        val nodeDis = DataInputStream(ByteArrayInputStream(sectionBytes(SECTION_NODEDATA)))
        NodeSerializer.readHeader(nodeDis, NodeSerializer.MAGIC_NODEDATA)
        val nodeCount = nodeDis.readInt()
        val nodesById = mutableMapOf<Int, Node>()
        repeat(nodeCount) {
            val node = NodeSerializer.readNode(nodeDis, stringTable)
            nodesById[node.id.value] = node
        }

        // Section 5: metadata
        val metaDis = DataInputStream(ByteArrayInputStream(sectionBytes(SECTION_METADATA)))
        val metadata = NodeSerializer.loadMetadata(metaDis, stringTable)

        return WebGraphBackedGraph(forward, backward, nodesById, forwardLabels, cumulativeOutdeg, comparisonMap, metadata)
    }

    // ========================================================================
    // Compressed format: save
    // ========================================================================

    /**
     * Save a graph to disk in BVGraph compressed directory format.
     *
     * Uses a streaming [ImmutableGraph] wrapper over the source [Graph] to avoid
     * copying all edge data into a second adjacency structure.
     */
    private fun saveCompressed(graph: Graph, dir: Path, compressionThreads: Int) {
        Files.createDirectories(dir)

        // 1. Stream nodes: find maxNodeId, count nodes, collect strings
        var maxNodeId = 0
        var nodeCount = 0
        val allStrings = mutableSetOf<String>()
        for (node in graph.nodes(Node::class.java)) {
            if (node.id.value > maxNodeId) maxNodeId = node.id.value
            nodeCount++
            collectSingleNodeStrings(node, allStrings)
        }

        // 2. Collect metadata (iterates graph again but doesn't store node list)
        val metadata = collectMetadata(graph)
        NodeSerializer.collectMetadataStrings(metadata, allStrings)
        val stringTable = StringTable.build(allStrings, dir)
        allStrings.clear()

        // 3. Build forward adjacency + labels + comparisons in 2 passes
        val numNodes = maxNodeId + 1
        val forwardData = buildForwardData(graph, numNodes)
        val forwardAdj = forwardData.adjacency
        val labelArray = forwardData.labels
        val comparisonMap = forwardData.comparisons
        val forwardGraph = PrecomputedImmutableGraph(forwardAdj)

        // 4. Store BVGraph (forward only, limited threads to control memory)
        BVGraph.store(
            forwardGraph, dir.resolve(FORWARD_GRAPH).toString(),
            BVGraph.DEFAULT_WINDOW_SIZE, BVGraph.DEFAULT_MAX_REF_COUNT,
            BVGraph.DEFAULT_MIN_INTERVAL_LENGTH, BVGraph.DEFAULT_ZETA_K,
            0, // flags
            compressionThreads
        )

        // 5. Store labels
        BinIO.storeBytes(labelArray, dir.resolve(LABELS_FILE).toString())

        // 6. Store comparisons
        DataOutputStream(BufferedOutputStream(dir.resolve(COMPARISONS_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeComparisons(dos, comparisonMap)
        }

        // 7. Write nodes (stream graph.nodes again, no list)
        DataOutputStream(BufferedOutputStream(dir.resolve(NODE_DATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEDATA)
            dos.writeInt(nodeCount)
            for (node in graph.nodes(Node::class.java)) {
                NodeSerializer.writeNode(dos, node, stringTable)
            }
        }

        // 8. Build node index
        buildNodeIndex(dir.resolve(NODE_DATA_FILE), dir.resolve(NODE_INDEX_FILE), stringTable)

        // 9. Save metadata
        DataOutputStream(BufferedOutputStream(dir.resolve(METADATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.saveMetadata(metadata, dos, stringTable)
        }
    }

    // ========================================================================
    // Compressed format: load
    // ========================================================================

    /**
     * Load a graph from a compressed (BVGraph) directory.
     */
    private fun loadFromDirectory(dir: Path, mode: LoadMode): Graph {
        return when (mode) {
            LoadMode.EAGER -> loadEager(dir)
            LoadMode.MAPPED -> {
                ensureNodeIndex(dir)
                loadMapped(dir)
            }
            LoadMode.AUTO -> {
                val nodeCount = DataInputStream(BufferedInputStream(dir.resolve(NODE_DATA_FILE).toFile().inputStream())).use { dis ->
                    NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA)
                    dis.readInt()
                }
                if (nodeCount < MAPPED_THRESHOLD) {
                    loadEager(dir)
                } else {
                    ensureNodeIndex(dir)
                    loadMapped(dir)
                }
            }
        }
    }

    /**
     * Load all nodes eagerly into JVM heap. Best for graphs < 1M nodes.
     */
    private fun loadEager(dir: Path): Graph {
        val forwardFuture = CompletableFuture.supplyAsync { BVGraph.load(dir.resolve(FORWARD_GRAPH).toString()) }
        val stringTableFuture = CompletableFuture.supplyAsync { StringTable.load(dir) }
        val labelsFuture = CompletableFuture.supplyAsync { BinIO.loadBytes(dir.resolve(LABELS_FILE).toString()) }

        val forward = forwardFuture.join()
        val stringTable = stringTableFuture.join()
        val labelBytes = labelsFuture.join()

        val cumulativeOutdeg = buildCumulativeOutdeg(forward)
        val backward = loadBackward(forward)

        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }

        val nodesById = mutableMapOf<Int, Node>()
        DataInputStream(BufferedInputStream(dir.resolve(NODE_DATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEDATA)
            val count = dis.readInt()
            repeat(count) {
                val node = NodeSerializer.readNode(dis, stringTable)
                nodesById[node.id.value] = node
            }
        }

        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }

        return WebGraphBackedGraph(forward, backward, nodesById, labelBytes, cumulativeOutdeg, comparisonMap, metadata)
    }

    /**
     * Load a graph lazily -- BVGraph adjacency and edge labels are loaded into
     * memory, but node data stays on disk and is read on demand.
     *
     * Memory savings for Android SDK (5.9M nodes): ~500 MB vs ~4 GB eager.
     * Query speed for LIMIT queries is similar; full-scan queries pay ~5-10x
     * for on-demand node deserialization.
     */
    fun loadLazy(dir: Path): Graph {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }

        val nodeIndex = readNodeIndex(dir)

        val forwardFuture = CompletableFuture.supplyAsync { BVGraph.load(dir.resolve(FORWARD_GRAPH).toString()) }
        val stringTableFuture = CompletableFuture.supplyAsync { StringTable.load(dir) }
        val labelsFuture = CompletableFuture.supplyAsync { BinIO.loadBytes(dir.resolve(LABELS_FILE).toString()) }

        val forward = forwardFuture.join()
        val stringTable = stringTableFuture.join()
        val labelBytes = labelsFuture.join()

        val cumulativeOutdeg = buildCumulativeOutdeg(forward)
        val backward = loadBackward(forward)

        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }

        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }

        return LazyWebGraphBackedGraph(
            forward = forward,
            backward = backward,
            nodeDataFile = dir.resolve(NODE_DATA_FILE).toFile(),
            stringTable = stringTable,
            nodeOffsets = nodeIndex.nodeOffsets,
            nodeTypeIndex = nodeIndex.nodeTypeIndex,
            forwardLabels = labelBytes,
            cumulativeOutdeg = cumulativeOutdeg,
            comparisonMap = comparisonMap,
            metadata = metadata
        )
    }

    /**
     * Load a graph with memory-mapped node data -- BVGraph adjacency and edge
     * labels are loaded into JVM heap, but node data is memory-mapped (mmap).
     *
     * The OS page cache manages which node pages are in physical RAM.
     * No JVM heap allocation for node data, and no system calls per node access
     * (unlike [loadLazy] which uses [RandomAccessFile.seek]).
     */
    fun loadMapped(dir: Path): Graph {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }

        val nodeIndex = readNodeIndex(dir)

        val forwardFuture = CompletableFuture.supplyAsync { BVGraph.load(dir.resolve(FORWARD_GRAPH).toString()) }
        val stringTableFuture = CompletableFuture.supplyAsync { StringTable.load(dir) }
        val labelsFuture = CompletableFuture.supplyAsync { BinIO.loadBytes(dir.resolve(LABELS_FILE).toString()) }

        val forward = forwardFuture.join()
        val stringTable = stringTableFuture.join()
        val labelBytes = labelsFuture.join()

        val cumulativeOutdeg = buildCumulativeOutdeg(forward)
        val backward = loadBackward(forward)

        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }

        val nodeDataPath = dir.resolve(NODE_DATA_FILE)
        val nodeChannel = FileChannel.open(nodeDataPath, StandardOpenOption.READ)
        val mappedBuffer = nodeChannel.map(FileChannel.MapMode.READ_ONLY, 0, nodeChannel.size())
        nodeChannel.close()

        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }

        return MappedWebGraphBackedGraph(
            forward = forward,
            backward = backward,
            mappedNodeData = mappedBuffer,
            stringTable = stringTable,
            nodeOffsets = nodeIndex.nodeOffsets,
            nodeTypeIndex = nodeIndex.nodeTypeIndex,
            forwardLabels = labelBytes,
            cumulativeOutdeg = cumulativeOutdeg,
            comparisonMap = comparisonMap,
            metadata = metadata
        )
    }

    // ========================================================================
    // Shared helpers
    // ========================================================================

    /**
     * Result of building forward adjacency, labels, and comparisons together.
     */
    private data class ForwardData(
        val adjacency: PrecomputedAdjacency,
        val labels: ByteArray,
        val comparisons: Map<Long, BranchComparison>
    )

    /**
     * Build forward adjacency, labels, and comparisons in 2 passes over graph edges.
     * Pass 1: count outdegree per node.
     * Pass 2: fill sorted successor arrays, labels, and comparisons.
     */
    private fun buildForwardData(graph: Graph, numNodes: Int): ForwardData {
        // Pass 1: count outdegree
        val outdeg = IntArray(numNodes)
        for (node in 0 until numNodes) {
            val targets = mutableSetOf<Int>()
            for (edge in graph.outgoing(NodeId(node))) {
                targets.add(edge.to.value)
            }
            outdeg[node] = targets.size
        }

        // Build offsets
        val offsets = LongArray(numNodes + 1)
        for (i in 0 until numNodes) {
            offsets[i + 1] = offsets[i] + outdeg[i]
        }
        val totalArcs = offsets[numNodes].toInt()
        val targets = IntArray(totalArcs)
        val labels = ByteArray(totalArcs)
        val comparisons = mutableMapOf<Long, BranchComparison>()

        // Pass 2: fill targets + labels + comparisons together
        for (node in 0 until numNodes) {
            val edgesByTarget = mutableMapOf<Int, Edge>()
            for (edge in graph.outgoing(NodeId(node))) {
                edgesByTarget[edge.to.value] = edge
            }
            val sortedTargets = edgesByTarget.keys.sorted()
            val start = offsets[node].toInt()
            for ((i, to) in sortedTargets.withIndex()) {
                targets[start + i] = to
                val edge = edgesByTarget[to]!!
                labels[start + i] = NodeSerializer.encodeEdge(edge).toByte()
                if (edge is ControlFlowEdge && edge.comparison != null) {
                    val key = node.toLong() shl 32 or (to.toLong() and 0xFFFFFFFFL)
                    comparisons[key] = edge.comparison!!
                }
            }
        }

        return ForwardData(
            PrecomputedAdjacency(numNodes, targets, offsets),
            labels,
            comparisons
        )
    }

    /**
     * Collect strings from a single node (avoids building a list of all nodes).
     */
    private fun collectSingleNodeStrings(node: Node, dest: MutableSet<String>) {
        NodeSerializer.collectNodeStrings(listOf(node), dest)
    }

    /**
     * Build a cumulative outdegree array for O(1) label offset lookup.
     * `cumulativeOutdeg[i]` = sum of outdegree(0..i-1), so the labels for
     * node `i` start at `forwardLabels[cumulativeOutdeg[i]]`.
     */
    private fun buildCumulativeOutdeg(forward: ImmutableGraph): LongArray {
        val numNodes = forward.numNodes()
        val cumOutdeg = LongArray(numNodes + 1)
        for (i in 0 until numNodes) {
            cumOutdeg[i + 1] = cumOutdeg[i] + forward.outdegree(i)
        }
        return cumOutdeg
    }

    /**
     * Flat sorted adjacency: targets[offsets[node]..offsets[node+1]] are the
     * sorted, deduplicated successors of node. Zero per-node allocation on access.
     */
    private class PrecomputedAdjacency(
        val numNodes: Int,
        val targets: IntArray,
        val offsets: LongArray
    ) {
        fun outdegree(node: Int): Int = (offsets[node + 1] - offsets[node]).toInt()
        fun successorArray(node: Int): IntArray {
            val start = offsets[node].toInt()
            val end = offsets[node + 1].toInt()
            return targets.copyOfRange(start, end)
        }
    }

    /**
     * Wraps a [PrecomputedAdjacency] as a WebGraph [ImmutableGraph] for BVGraph storage.
     * Zero allocation per node access -- successorArray returns a copy of the pre-sorted slice.
     */
    private class PrecomputedImmutableGraph(
        private val adj: PrecomputedAdjacency
    ) : ImmutableGraph() {
        override fun numNodes(): Int = adj.numNodes
        override fun randomAccess(): Boolean = true
        override fun outdegree(x: Int): Int = adj.outdegree(x)
        override fun successorArray(x: Int): IntArray = adj.successorArray(x)
        override fun successors(x: Int): LazyIntIterator = LazyIntIterators.wrap(successorArray(x))
        override fun copy(): ImmutableGraph = this
    }


    /** Build backward adjacency from forward BVGraph. */
    private fun loadBackward(forward: ImmutableGraph): ImmutableGraph =
        buildBackwardFromForward(forward)

    /**
     * Build backward (transpose) adjacency from forward BVGraph.
     * Two passes over the compressed forward graph -- no intermediate collections.
     * Memory: IntArray(totalEdges) + LongArray(numNodes+1) + IntArray(numNodes) work array.
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
        val offsets = LongArray(numNodes + 1)
        for (i in 0 until numNodes) {
            offsets[i + 1] = offsets[i] + backwardDeg[i]
        }

        // Pass 2: fill targets
        val targets = IntArray(offsets[numNodes].toInt())
        val fillPos = IntArray(numNodes)
        for (node in 0 until numNodes) {
            val succs = forward.successorArray(node)
            val outdeg = forward.outdegree(node)
            for (i in 0 until outdeg) {
                val to = succs[i]
                targets[(offsets[to] + fillPos[to]).toInt()] = node
                fillPos[to]++
            }
        }

        // Sort each node's predecessors
        for (node in 0 until numNodes) {
            val start = offsets[node].toInt()
            val end = offsets[node + 1].toInt()
            java.util.Arrays.sort(targets, start, end)
        }

        return PrecomputedImmutableGraph(PrecomputedAdjacency(numNodes, targets, offsets))
    }

    /**
     * Result of reading and parsing the node index file.
     */
    private class NodeIndexData(
        val nodeOffsets: LongArray,
        val nodeTypeIndex: HashMap<Class<out Node>, List<Int>>
    )

    /**
     * Read the node index file and return parsed offsets and type index.
     */
    private fun readNodeIndex(dir: Path): NodeIndexData {
        val nodeIndexFile = dir.resolve(NODE_INDEX_FILE).toFile()
        require(nodeIndexFile.exists()) {
            "Node index file not found: $nodeIndexFile. Re-save the graph to generate it."
        }

        // Pass 1: find maxNodeId and collect type info
        var maxNodeId = 0
        val nodeTypeByTag = HashMap<Int, MutableList<Int>>()
        var nodeCount = 0
        DataInputStream(BufferedInputStream(nodeIndexFile.inputStream())).use { dis ->
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEINDEX)
            nodeCount = dis.readInt()
            repeat(nodeCount) {
                val nodeId = dis.readInt()
                val tag = dis.readByte().toInt()
                dis.readLong() // skip offset
                if (nodeId > maxNodeId) maxNodeId = nodeId
                nodeTypeByTag.getOrPut(tag) { mutableListOf() }.add(nodeId)
            }
        }

        // Pass 2: fill nodeOffsets directly
        val nodeOffsets = LongArray(maxNodeId + 1) { -1L }
        DataInputStream(BufferedInputStream(nodeIndexFile.inputStream())).use { dis ->
            NodeSerializer.readHeader(dis, NodeSerializer.MAGIC_NODEINDEX)
            dis.readInt() // skip count
            repeat(nodeCount) {
                val nodeId = dis.readInt()
                dis.readByte() // skip tag
                val offset = dis.readLong()
                nodeOffsets[nodeId] = offset
            }
        }

        // Map tags to concrete Node classes
        val nodeTypeIndex = HashMap<Class<out Node>, List<Int>>()
        val tagToClass = mapOf(
            0 to IntConstant::class.java,
            1 to StringConstant::class.java,
            2 to LongConstant::class.java,
            3 to FloatConstant::class.java,
            4 to DoubleConstant::class.java,
            5 to BooleanConstant::class.java,
            6 to NullConstant::class.java,
            7 to EnumConstant::class.java,
            8 to LocalVariable::class.java,
            9 to FieldNode::class.java,
            10 to ParameterNode::class.java,
            11 to ReturnNode::class.java,
            12 to CallSiteNode::class.java,
            13 to AnnotationNode::class.java
        )
        for ((tag, ids) in nodeTypeByTag) {
            val cls = tagToClass[tag] ?: continue
            nodeTypeIndex[cls] = ids
        }

        return NodeIndexData(nodeOffsets, nodeTypeIndex)
    }

    /**
     * Ensure the `graph.nodeindex` file exists for the given graph directory.
     * If it doesn't exist, scans `graph.nodedata` to build it.
     * This is idempotent -- safe to call on graphs that already have the index.
     */
    fun ensureNodeIndex(dir: Path) {
        val indexFile = dir.resolve(NODE_INDEX_FILE)
        if (Files.exists(indexFile)) return
        val stringTable = StringTable.load(dir)
        buildNodeIndex(dir.resolve(NODE_DATA_FILE), indexFile, stringTable)
    }

    private fun buildNodeIndex(nodeDataPath: Path, nodeIndexPath: Path, stringTable: StringTable) {
        // Stream directly: read nodedata, write nodeindex entry by entry (no intermediate list)
        RandomAccessFile(nodeDataPath.toFile(), "r").use { raf ->
            NodeSerializer.readHeader(raf, NodeSerializer.MAGIC_NODEDATA)
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
                    NodeSerializer.readNode(dis, stringTable)
                }
            }
        }
    }

    private fun collectMetadata(graph: Graph): GraphMetadata {
        // Collect type hierarchy
        val supertypes = mutableMapOf<String, Set<TypeDescriptor>>()
        val subtypes = mutableMapOf<String, Set<TypeDescriptor>>()

        // Walk all types referenced in the graph (nodes + methods)
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
        // Also collect types from registered methods
        graph.methods(MethodPattern()).forEach { method ->
            allTypes.add(method.declaringClass)
            allTypes.add(method.returnType)
            method.parameterTypes.forEach { allTypes.add(it) }
        }

        // Include all types that have hierarchy info (covers types not referenced by nodes)
        graph.typeHierarchyTypes().forEach { allTypes.add(TypeDescriptor(it)) }

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

        // Collect member annotations - extract from all classes referenced in nodes
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
            memberAnnotations = memberAnnotations,
            branchScopes = branchScopes
        )
    }
}
