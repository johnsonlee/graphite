package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.webgraph.BVGraph
import it.unimi.dsi.webgraph.ImmutableGraph
import it.unimi.dsi.webgraph.LazyIntIterator
import it.unimi.dsi.webgraph.LazyIntIterators
import it.unimi.dsi.webgraph.Transform
import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Save and load [Graph] instances using WebGraph compression with native LAW
 * ecosystem tools (dsiutils + sux4j + fastutil).
 *
 * Storage layout:
 * - `forward.*` / `backward.*` -- BVGraph adjacency (forward + transpose)
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
    private const val BACKWARD_GRAPH = "backward"
    private const val LABELS_FILE = "graph.labels"
    private const val COMPARISONS_FILE = "graph.comparisons"
    private const val NODE_DATA_FILE = "graph.nodedata"
    private const val NODE_INDEX_FILE = "graph.nodeindex"
    private const val METADATA_FILE = "graph.metadata"

    /**
     * Save a graph to disk in WebGraph + native LAW format.
     *
     * Uses a streaming [ImmutableGraph] wrapper over the source [Graph] to avoid
     * copying all edge data into a second adjacency structure
     * ([ArrayListMutableGraph][it.unimi.dsi.webgraph.ArrayListMutableGraph]).
     * For large graphs (millions of nodes) this eliminates the OOM caused by
     * duplicating the entire adjacency list in memory.
     */
    fun save(graph: Graph, dir: Path) {
        Files.createDirectories(dir)

        // 1. Collect all nodes and find max node ID for graph sizing
        val allNodes = mutableListOf<Node>()
        val maxNodeId = collectNodes(graph, allNodes)

        // 2. Collect metadata
        val metadata = collectMetadata(graph)

        // 3. Collect all unique strings and build the string table
        val allStrings = mutableSetOf<String>()
        NodeSerializer.collectNodeStrings(allNodes, allStrings)
        NodeSerializer.collectMetadataStrings(metadata, allStrings)
        val stringTable = StringTable.build(allStrings, dir)
        allStrings.clear() // free the string collection set

        // 4. Build edge label + comparison maps (needed for label storage)
        //    and wrap the graph as an ImmutableGraph for BVGraph (no adjacency copy)
        val edgeLabelMap = Long2IntOpenHashMap()
        val comparisonMap = mutableMapOf<Long, BranchComparison>()

        for (node in allNodes) {
            for (edge in graph.outgoing(node.id)) {
                val key = edge.from.value.toLong() shl 32 or (edge.to.value.toLong() and 0xFFFFFFFFL)
                edgeLabelMap.put(key, NodeSerializer.encodeEdge(edge))
                if (edge is ControlFlowEdge && edge.comparison != null) {
                    comparisonMap[key] = edge.comparison!!
                }
            }
        }

        val forwardView = GraphImmutableView(graph, maxNodeId + 1)

        // 5. Store BVGraph (forward)
        BVGraph.store(forwardView, dir.resolve(FORWARD_GRAPH).toString())

        // 6. Store BVGraph (backward = transpose)
        val backward = Transform.transpose(forwardView)
        BVGraph.store(backward, dir.resolve(BACKWARD_GRAPH).toString())

        // 7. Store edge labels -- byte[] via BinIO, one byte per arc in BVGraph successor order
        val labelArray = buildLabelArray(forwardView, edgeLabelMap)
        BinIO.storeBytes(labelArray, dir.resolve(LABELS_FILE).toString())

        // 8. Store comparisons for ControlFlowEdges
        DataOutputStream(BufferedOutputStream(dir.resolve(COMPARISONS_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.writeComparisons(dos, comparisonMap)
        }

        // 9. Save nodes using DataOutputStream with string table indices
        DataOutputStream(BufferedOutputStream(dir.resolve(NODE_DATA_FILE).toFile().outputStream())).use { dos ->
            dos.writeInt(allNodes.size)
            for (node in allNodes) {
                NodeSerializer.writeNode(dos, node, stringTable)
            }
        }

        // 10. Build and save node index for lazy loading (nodeId + tag + offset into nodedata)
        buildNodeIndex(dir.resolve(NODE_DATA_FILE), dir.resolve(NODE_INDEX_FILE), stringTable)

        // 11. Save metadata with string table indices
        DataOutputStream(BufferedOutputStream(dir.resolve(METADATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.saveMetadata(metadata, dos, stringTable)
        }
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
        val stringTable = StringTable.load(dir)
        val forward = BVGraph.load(dir.resolve(FORWARD_GRAPH).toString())
        val backward = BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())
        val labelBytes = BinIO.loadBytes(dir.resolve(LABELS_FILE).toString())
        val edgeLabelMap = buildEdgeLabelMap(forward, labelBytes)
        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }
        val nodesById = mutableMapOf<Int, Node>()
        DataInputStream(BufferedInputStream(dir.resolve(NODE_DATA_FILE).toFile().inputStream())).use { dis ->
            val count = dis.readInt()
            repeat(count) {
                val node = NodeSerializer.readNode(dis, stringTable)
                nodesById[node.id.value] = node
            }
        }
        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }
        return WebGraphBackedGraph(forward, backward, nodesById, edgeLabelMap, comparisonMap, metadata)
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

        val stringTable = StringTable.load(dir)
        val forward = BVGraph.load(dir.resolve(FORWARD_GRAPH).toString())
        val backward = BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())

        val labelBytes = BinIO.loadBytes(dir.resolve(LABELS_FILE).toString())
        val edgeLabelMap = buildEdgeLabelMap(forward, labelBytes)

        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }

        // Load node index from graph.nodeindex
        val nodeDataFile = dir.resolve(NODE_DATA_FILE).toFile()
        val nodeIndexFile = dir.resolve(NODE_INDEX_FILE).toFile()
        require(nodeIndexFile.exists()) {
            "Node index file not found: $nodeIndexFile. Re-save the graph to generate it."
        }
        val nodeIndex = Int2LongOpenHashMap()
        val nodeTypeByTag = HashMap<Int, MutableList<Int>>()

        DataInputStream(BufferedInputStream(nodeIndexFile.inputStream())).use { dis ->
            val count = dis.readInt()
            repeat(count) {
                val nodeId = dis.readInt()
                val tag = dis.readByte().toInt()
                val offset = dis.readLong()
                nodeIndex.put(nodeId, offset)
                nodeTypeByTag.getOrPut(tag) { mutableListOf() }.add(nodeId)
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
            12 to CallSiteNode::class.java
        )
        for ((tag, ids) in nodeTypeByTag) {
            val cls = tagToClass[tag] ?: continue
            nodeTypeIndex[cls] = ids
        }

        // Load metadata eagerly (small)
        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }

        return LazyWebGraphBackedGraph(
            forward = forward,
            backward = backward,
            nodeDataFile = nodeDataFile,
            stringTable = stringTable,
            nodeIndex = nodeIndex,
            nodeTypeIndex = nodeTypeIndex,
            edgeLabelMap = edgeLabelMap,
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

        val stringTable = StringTable.load(dir)
        val forward = BVGraph.load(dir.resolve(FORWARD_GRAPH).toString())
        val backward = BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())

        val labelBytes = BinIO.loadBytes(dir.resolve(LABELS_FILE).toString())
        val edgeLabelMap = buildEdgeLabelMap(forward, labelBytes)

        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }

        // Load node index from graph.nodeindex
        val nodeIndexFile = dir.resolve(NODE_INDEX_FILE).toFile()
        require(nodeIndexFile.exists()) {
            "Node index file not found: $nodeIndexFile. Re-save the graph or call ensureNodeIndex()."
        }
        val nodeIndex = Int2LongOpenHashMap()
        val nodeTypeByTag = HashMap<Int, MutableList<Int>>()

        DataInputStream(BufferedInputStream(nodeIndexFile.inputStream())).use { dis ->
            val count = dis.readInt()
            repeat(count) {
                val nodeId = dis.readInt()
                val tag = dis.readByte().toInt()
                val offset = dis.readLong()
                nodeIndex.put(nodeId, offset)
                nodeTypeByTag.getOrPut(tag) { mutableListOf() }.add(nodeId)
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
            12 to CallSiteNode::class.java
        )
        for ((tag, ids) in nodeTypeByTag) {
            val cls = tagToClass[tag] ?: continue
            nodeTypeIndex[cls] = ids
        }

        // Memory-map the node data file
        val nodeDataPath = dir.resolve(NODE_DATA_FILE)
        val channel = FileChannel.open(nodeDataPath, StandardOpenOption.READ)
        val mappedBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
        channel.close()

        // Load metadata eagerly (small)
        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }

        return MappedWebGraphBackedGraph(
            forward = forward,
            backward = backward,
            mappedNodeData = mappedBuffer,
            stringTable = stringTable,
            nodeIndex = nodeIndex,
            nodeTypeIndex = nodeTypeIndex,
            edgeLabelMap = edgeLabelMap,
            comparisonMap = comparisonMap,
            metadata = metadata
        )
    }

    /**
     * Build a byte array of edge labels in BVGraph successor order.
     */
    private fun buildLabelArray(
        graph: it.unimi.dsi.webgraph.ImmutableGraph,
        edgeLabelMap: Long2IntOpenHashMap
    ): ByteArray {
        var totalEdges = 0
        for (node in 0 until graph.numNodes()) {
            totalEdges += graph.outdegree(node)
        }
        val labels = ByteArray(totalEdges)
        var idx = 0
        for (node in 0 until graph.numNodes()) {
            val succs = graph.successorArray(node)
            val outdeg = graph.outdegree(node)
            for (i in 0 until outdeg) {
                val key = node.toLong() shl 32 or (succs[i].toLong() and 0xFFFFFFFFL)
                labels[idx++] = edgeLabelMap.get(key).toByte()
            }
        }
        return labels
    }

    /**
     * Reconstruct the edge label map from a byte array loaded via BinIO.
     */
    private fun buildEdgeLabelMap(
        forward: it.unimi.dsi.webgraph.ImmutableGraph,
        labelBytes: ByteArray
    ): Long2IntOpenHashMap {
        val edgeLabelMap = Long2IntOpenHashMap(labelBytes.size)
        var idx = 0
        for (node in 0 until forward.numNodes()) {
            val succs = forward.successorArray(node)
            val outdeg = forward.outdegree(node)
            for (i in 0 until outdeg) {
                val key = node.toLong() shl 32 or (succs[i].toLong() and 0xFFFFFFFFL)
                edgeLabelMap.put(key, labelBytes[idx++].toInt() and 0xFF)
            }
        }
        return edgeLabelMap
    }

    private fun collectNodes(graph: Graph, result: MutableList<Node>): Int {
        var maxId = 0
        for (node in graph.nodes(Node::class.java)) {
            result.add(node)
            if (node.id.value > maxId) maxId = node.id.value
        }
        return maxId
    }

    /**
     * Wraps a Graphite [Graph] as a WebGraph [ImmutableGraph] for BVGraph storage.
     *
     * Delegates successor iteration to [Graph.outgoing] -- no data is copied into a
     * second adjacency structure. This avoids the OOM that
     * [ArrayListMutableGraph][it.unimi.dsi.webgraph.ArrayListMutableGraph] causes on
     * large graphs (e.g. 5.9M nodes, 8GB heap).
     *
     * BVGraph requires successors in strictly increasing order with no duplicates.
     * This wrapper sorts and deduplicates the successor list for each node on every
     * call. The overhead is negligible compared to BVGraph compression I/O.
     */
    private class GraphImmutableView(
        private val graph: Graph,
        private val numNodes: Int
    ) : ImmutableGraph() {

        override fun numNodes(): Int = numNodes

        override fun randomAccess(): Boolean = true

        override fun outdegree(x: Int): Int = successorArray(x).size

        override fun successors(x: Int): LazyIntIterator {
            return LazyIntIterators.wrap(successorArray(x))
        }

        /**
         * Returns the sorted, deduplicated successor array for node [x].
         *
         * The returned array length equals the outdegree (no trailing slack),
         * so callers can use `array.size` directly.
         */
        override fun successorArray(x: Int): IntArray {
            val succs = mutableSetOf<Int>()
            for (edge in graph.outgoing(NodeId(x))) {
                succs.add(edge.to.value)
            }
            val arr = succs.toIntArray()
            arr.sort()
            return arr
        }

        override fun copy(): ImmutableGraph = this
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

    private fun buildNodeIndex(nodeDataPath: Path, nodeIndexPath: Path, stringTable: StringTable) {
        val indexEntries = mutableListOf<Triple<Int, Int, Long>>()  // (nodeId, tag, offset)

        RandomAccessFile(nodeDataPath.toFile(), "r").use { raf ->
            val count = raf.readInt()  // skip node count header
            repeat(count) {
                val offset = raf.filePointer
                // Read nodeId (first 4 bytes) and tag (next byte) from the node record
                val nodeId = raf.readInt()
                val tag = raf.readByte().toInt()
                indexEntries.add(Triple(nodeId, tag, offset))
                // Seek back to start of record and read full node to advance file pointer
                raf.seek(offset)
                val dis = object : DataInputStream(object : InputStream() {
                    override fun read(): Int = raf.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
                }) {}
                NodeSerializer.readNode(dis, stringTable)  // advances raf past the full record
            }
        }

        DataOutputStream(BufferedOutputStream(nodeIndexPath.toFile().outputStream())).use { dos ->
            dos.writeInt(indexEntries.size)
            for ((nodeId, tag, offset) in indexEntries) {
                dos.writeInt(nodeId)
                dos.writeByte(tag)
                dos.writeLong(offset)
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
