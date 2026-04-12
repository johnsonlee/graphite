package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.webgraph.BVGraph
import it.unimi.dsi.webgraph.ImmutableGraph
import it.unimi.dsi.webgraph.LazyIntIterator
import it.unimi.dsi.webgraph.LazyIntIterators
import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture

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

/**
 * Save and load [Graph] instances using WebGraph compression with native LAW
 * ecosystem tools (dsiutils + sux4j + fastutil).
 *
 * Storage layout:
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

    /**
     * Get a [Future]'s result, unwrapping [ExecutionException] so that the
     * original exception propagates with its real type.
     */
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
        val saveStart = System.nanoTime()
        fun elapsed() = (System.nanoTime() - saveStart) / 1_000_000

        // 1. Stream nodes: find maxNodeId, count nodes, collect strings
        var t0 = System.nanoTime()
        var maxNodeId = 0
        var nodeCount = 0
        val allStrings = mutableSetOf<String>()
        for (node in graph.nodes(Node::class.java)) {
            if (node.id.value > maxNodeId) maxNodeId = node.id.value
            nodeCount++
            collectSingleNodeStrings(node, allStrings)
        }
        System.err.println("[save] 1. String collection: ${(System.nanoTime() - t0) / 1_000_000}ms ($nodeCount nodes, ${allStrings.size} strings)")

        // 2. Collect metadata
        t0 = System.nanoTime()
        val metadata = collectMetadata(graph)
        NodeSerializer.collectMetadataStrings(metadata, allStrings)
        val stringTable = StringTable.build(allStrings, dir)
        allStrings.clear()
        System.err.println("[save] 2. Metadata + StringTable: ${(System.nanoTime() - t0) / 1_000_000}ms")

        // 3. Build forward adjacency + labels + comparisons
        t0 = System.nanoTime()
        val numNodes = maxNodeId + 1
        val forwardAdj = buildForwardAdjacency(graph, numNodes)
        val comparisonMap = mutableMapOf<Long, BranchComparison>()
        val labelArray = buildLabelArrayDirect(graph, numNodes, comparisonMap)
        System.err.println("[save] 3. Forward adjacency + labels: ${(System.nanoTime() - t0) / 1_000_000}ms ($numNodes nodes, ${labelArray.size} edges)")

        // 4. Store BVGraph (forward only)
        t0 = System.nanoTime()
        val forwardGraph = PrecomputedImmutableGraph(forwardAdj)
        BVGraph.store(
            forwardGraph, dir.resolve(FORWARD_GRAPH).toString(),
            BVGraph.DEFAULT_WINDOW_SIZE, BVGraph.DEFAULT_MAX_REF_COUNT,
            BVGraph.DEFAULT_MIN_INTERVAL_LENGTH, BVGraph.DEFAULT_ZETA_K,
            0, compressionThreads
        )
        System.err.println("[save] 4. BVGraph.store: ${(System.nanoTime() - t0) / 1_000_000}ms")

        // 5. Store labels
        t0 = System.nanoTime()
        BinIO.storeBytes(labelArray, dir.resolve(LABELS_FILE).toString())
        DataOutputStream(BufferedOutputStream(dir.resolve(COMPARISONS_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeComparisons(dos, comparisonMap)
        }
        System.err.println("[save] 5. Labels + comparisons: ${(System.nanoTime() - t0) / 1_000_000}ms")

        // 6. Write nodedata + nodeindex simultaneously
        t0 = System.nanoTime()
        CountingOutputStream(BufferedOutputStream(dir.resolve(NODE_DATA_FILE).toFile().outputStream())).use { cos ->
            val dataDos = DataOutputStream(cos)
            DataOutputStream(BufferedOutputStream(dir.resolve(NODE_INDEX_FILE).toFile().outputStream())).use { idxDos ->
                NodeSerializer.writeHeader(dataDos, NodeSerializer.MAGIC_NODEDATA)
                dataDos.writeInt(nodeCount)
                NodeSerializer.writeHeader(idxDos, NodeSerializer.MAGIC_NODEINDEX)
                idxDos.writeInt(nodeCount)
                for (node in graph.nodes(Node::class.java)) {
                    val offset = cos.bytesWritten
                    val tag = NodeSerializer.writeNode(dataDos, node, stringTable)
                    idxDos.writeInt(node.id.value)
                    idxDos.writeByte(tag)
                    idxDos.writeLong(offset)
                }
            }
        }
        System.err.println("[save] 6. Nodedata + nodeindex: ${(System.nanoTime() - t0) / 1_000_000}ms")

        // 7. Save metadata
        t0 = System.nanoTime()
        DataOutputStream(BufferedOutputStream(dir.resolve(METADATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.saveMetadata(metadata, dos, stringTable)
        }
        System.err.println("[save] 7. Metadata write: ${(System.nanoTime() - t0) / 1_000_000}ms")
        System.err.println("[save] Total: ${elapsed()}ms")
    }

    /**
     * Load a graph from disk.
     *
     * @param mode loading strategy; defaults to [LoadMode.AUTO] which selects
     *   based on graph size (< 1M nodes → eager, >= 1M → mapped).
     */
    fun load(dir: Path, mode: LoadMode = LoadMode.AUTO): Graph {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }

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
     * Load a graph with memory-mapped node data — BVGraph adjacency and edge
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
        val channel = FileChannel.open(nodeDataPath, StandardOpenOption.READ)
        val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        channel.close()

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

    /**
     * Build label byte array directly in BVGraph successor order without
     * an intermediate HashMap. For each node, collects outgoing edges,
     * groups by target (dedup), sorts by target, and writes labels.
     */
    private fun buildLabelArrayDirect(
        graph: Graph,
        numNodes: Int,
        comparisonMap: MutableMap<Long, BranchComparison>
    ): ByteArray {
        // Count total arcs first
        var totalArcs = 0L
        for (node in 0 until numNodes) {
            val targets = mutableSetOf<Int>()
            for (edge in graph.outgoing(NodeId(node))) {
                targets.add(edge.to.value)
            }
            totalArcs += targets.size
        }

        val labels = ByteArray(totalArcs.toInt())
        var idx = 0
        for (node in 0 until numNodes) {
            val edgesByTarget = mutableMapOf<Int, Edge>()
            for (edge in graph.outgoing(NodeId(node))) {
                edgesByTarget[edge.to.value] = edge
            }
            for (to in edgesByTarget.keys.sorted()) {
                val edge = edgesByTarget[to]!!
                labels[idx++] = NodeSerializer.encodeEdge(edge).toByte()
                if (edge is ControlFlowEdge && edge.comparison != null) {
                    val key = node.toLong() shl 32 or (to.toLong() and 0xFFFFFFFFL)
                    comparisonMap[key] = edge.comparison!!
                }
            }
        }
        return labels
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
    internal class PrecomputedAdjacency(
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
    internal class PrecomputedImmutableGraph(
        private val adj: PrecomputedAdjacency
    ) : ImmutableGraph() {
        override fun numNodes(): Int = adj.numNodes
        override fun randomAccess(): Boolean = true
        override fun outdegree(x: Int): Int = adj.outdegree(x)
        override fun successorArray(x: Int): IntArray = adj.successorArray(x)
        override fun successors(x: Int): LazyIntIterator = LazyIntIterators.wrap(successorArray(x))
        override fun copy(): ImmutableGraph = this
    }

    /**
     * Build forward sorted adjacency in two passes over the graph.
     *
     * Pass 1: count outdegree per node.
     * Pass 2: fill target array and sort each node's successors.
     *
     * Backward adjacency is no longer precomputed at save time; it is
     * rebuilt from the compressed forward BVGraph at load time via
     * [buildBackwardFromForward].
     */
    private fun buildForwardAdjacency(graph: Graph, numNodes: Int): PrecomputedAdjacency {
        // Pass 1: count degrees
        val forwardDeg = IntArray(numNodes)
        for (node in 0 until numNodes) {
            val targets = mutableSetOf<Int>()
            for (edge in graph.outgoing(NodeId(node))) {
                targets.add(edge.to.value)
            }
            forwardDeg[node] = targets.size
        }

        // Build offsets
        val forwardOffsets = LongArray(numNodes + 1)
        for (i in 0 until numNodes) {
            forwardOffsets[i + 1] = forwardOffsets[i] + forwardDeg[i]
        }

        val forwardTargets = IntArray(forwardOffsets[numNodes].toInt())

        // Pass 2: fill targets
        for (node in 0 until numNodes) {
            val targets = mutableSetOf<Int>()
            for (edge in graph.outgoing(NodeId(node))) {
                targets.add(edge.to.value)
            }
            val sorted = targets.toIntArray().also { it.sort() }
            System.arraycopy(sorted, 0, forwardTargets, forwardOffsets[node].toInt(), sorted.size)
        }

        return PrecomputedAdjacency(numNodes, forwardTargets, forwardOffsets)
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
     * Build `graph.nodeindex` by scanning `graph.nodedata` sequentially.
     * Each entry: nodeId (4 bytes) + tag (1 byte) + offset in nodedata (8 bytes).
     */
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

    internal fun collectMetadata(graph: Graph): GraphMetadata {
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
