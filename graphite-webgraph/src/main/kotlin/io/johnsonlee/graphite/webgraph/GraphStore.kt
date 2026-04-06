package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.webgraph.BVGraph
import it.unimi.dsi.webgraph.ImmutableGraph
import it.unimi.dsi.webgraph.LazyIntIterator
import it.unimi.dsi.webgraph.LazyIntIterators
import it.unimi.dsi.webgraph.Transform
import java.io.*
import java.nio.file.Files
import java.nio.file.Path

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

    private const val FORWARD_GRAPH = "forward"
    private const val BACKWARD_GRAPH = "backward"
    private const val LABELS_FILE = "graph.labels"
    private const val COMPARISONS_FILE = "graph.comparisons"
    private const val NODE_DATA_FILE = "graph.nodedata"
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
        val edgeLabelMap = mutableMapOf<Long, Int>()
        val comparisonMap = mutableMapOf<Long, BranchComparison>()

        for (node in allNodes) {
            for (edge in graph.outgoing(node.id)) {
                val key = edge.from.value.toLong() shl 32 or (edge.to.value.toLong() and 0xFFFFFFFFL)
                edgeLabelMap[key] = NodeSerializer.encodeEdge(edge)
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

        // 10. Save metadata with string table indices
        DataOutputStream(BufferedOutputStream(dir.resolve(METADATA_FILE).toFile().outputStream())).use { dos ->
            NodeSerializer.saveMetadata(metadata, dos, stringTable)
        }
    }

    /**
     * Load a graph from disk.
     */
    fun load(dir: Path): Graph {
        require(Files.isDirectory(dir)) { "Not a directory: $dir" }

        // 1. Load string table first (needed for node and metadata deserialization)
        val stringTable = StringTable.load(dir)

        val forward = BVGraph.load(dir.resolve(FORWARD_GRAPH).toString())
        val backward = BVGraph.load(dir.resolve(BACKWARD_GRAPH).toString())

        // 2. Load edge labels from byte[] via BinIO
        val labelBytes = BinIO.loadBytes(dir.resolve(LABELS_FILE).toString())
        val edgeLabelMap = buildEdgeLabelMap(forward, labelBytes)

        // 3. Load comparisons
        val comparisonMap = DataInputStream(BufferedInputStream(dir.resolve(COMPARISONS_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.readComparisons(dis)
        }

        // 4. Load nodes with string table
        val nodesById = mutableMapOf<Int, Node>()
        DataInputStream(BufferedInputStream(dir.resolve(NODE_DATA_FILE).toFile().inputStream())).use { dis ->
            val count = dis.readInt()
            repeat(count) {
                val node = NodeSerializer.readNode(dis, stringTable)
                nodesById[node.id.value] = node
            }
        }

        // 5. Load metadata with string table
        val metadata = DataInputStream(BufferedInputStream(dir.resolve(METADATA_FILE).toFile().inputStream())).use { dis ->
            NodeSerializer.loadMetadata(dis, stringTable)
        }

        return WebGraphBackedGraph(forward, backward, nodesById, edgeLabelMap, comparisonMap, metadata)
    }

    /**
     * Build a byte array of edge labels in BVGraph successor order.
     */
    private fun buildLabelArray(
        graph: it.unimi.dsi.webgraph.ImmutableGraph,
        edgeLabelMap: Map<Long, Int>
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
                labels[idx++] = (edgeLabelMap[key] ?: 0).toByte()
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
    ): HashMap<Long, Int> {
        val edgeLabelMap = HashMap<Long, Int>(labelBytes.size)
        var idx = 0
        for (node in 0 until forward.numNodes()) {
            val succs = forward.successorArray(node)
            val outdeg = forward.outdegree(node)
            for (i in 0 until outdeg) {
                val key = node.toLong() shl 32 or (succs[i].toLong() and 0xFFFFFFFFL)
                edgeLabelMap[key] = labelBytes[idx++].toInt() and 0xFF
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
