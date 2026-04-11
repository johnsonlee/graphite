package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.webgraph.BVGraph
import org.openjdk.jmh.annotations.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

// ============================================================================
//  Save phase breakdown — isolate each phase to find the real bottleneck
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.filter='SavePhaseBreakdown'
// ============================================================================

/**
 * Breaks down GraphStore.save() into individual phases, each measured
 * independently by JMH. Prerequisites are prepared in @Setup.
 *
 * Phases:
 * 1. stringCollection — iterate nodes, collect unique strings
 * 2. metadataCollection — build metadata from graph
 * 3. stringTableBuild — sort + compress strings into FrontCodedStringList
 * 4. labelEncoding — encode edge labels via per-node outgoing iteration
 * 5. bvgraphStore — BVGraph compression of precomputed forward adjacency
 * 6. nodedataWrite — serialize all nodes to binary format
 * 7. nodeindexBuild — scan nodedata, write index entries
 * 8. metadataWrite — serialize metadata to binary format
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = ["-Xmx4g"])
@Warmup(iterations = 0)
@Measurement(iterations = 1)
open class SavePhaseBreakdownBenchmark {

    @Param("10000000")
    var nodeCount: Int = 0

    // Shared state built in setup
    private lateinit var graph: Graph
    private var maxNodeId: Int = 0
    private var graphNodeCount: Int = 0
    private lateinit var allStrings: Set<String>
    private lateinit var metadata: GraphMetadata
    private lateinit var stringTable: StringTable
    private lateinit var forwardAdj: GraphStore.PrecomputedAdjacency
    private lateinit var labelArray: ByteArray
    private lateinit var comparisonMap: Map<Long, BranchComparison>
    private lateinit var tmpDir: Path

    @Setup(Level.Trial)
    fun setup() {
        // Build graph
        NodeId.reset()
        val builder = DefaultGraph.Builder()
        val annotationInterval = 10
        for (i in 1..nodeCount) {
            if (i % annotationInterval == 0) {
                builder.addNode(AnnotationNode(
                    id = NodeId.next(),
                    name = "org.example.Ann${i % 100}",
                    className = "com.example.Class${i % 1000}",
                    memberName = "method${i % 500}",
                    values = mapOf("value" to "/path/$i")
                ))
            } else {
                builder.addNode(IntConstant(NodeId.next(), i))
            }
        }
        for (i in 1 until nodeCount) {
            builder.addEdge(DataFlowEdge(NodeId(i), NodeId(i + 1), DataFlowKind.ASSIGN))
        }
        graph = builder.build()

        // Precompute all prerequisites so each benchmark only measures its phase
        maxNodeId = 0
        graphNodeCount = 0
        val strings = mutableSetOf<String>()
        for (node in graph.nodes(Node::class.java)) {
            if (node.id.value > maxNodeId) maxNodeId = node.id.value
            graphNodeCount++
            NodeSerializer.collectNodeStrings(listOf(node), strings)
        }

        metadata = GraphStore.collectMetadata(graph)
        NodeSerializer.collectMetadataStrings(metadata, strings)
        allStrings = strings.toSet()

        tmpDir = Files.createTempDirectory("phase-bench")
        stringTable = StringTable.build(allStrings, tmpDir)

        // Build forward adjacency + labels via per-node outgoing iteration (slow path)
        val numNodes = maxNodeId + 1
        forwardAdj = buildForwardAdjacencyForBench(graph, numNodes)
        val comps = mutableMapOf<Long, BranchComparison>()
        labelArray = buildLabelArrayForBench(graph, numNodes, comps)
        comparisonMap = comps

        // Pre-write nodedata for nodeindex benchmark
        DataOutputStream(BufferedOutputStream(tmpDir.resolve("graph.nodedata").toFile().outputStream())).use { dos ->
            NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEDATA)
            dos.writeInt(graphNodeCount)
            for (node in graph.nodes(Node::class.java)) {
                NodeSerializer.writeNode(dos, node, stringTable)
            }
        }
    }

    @TearDown(Level.Trial)
    fun teardown() {
        tmpDir.toFile().deleteRecursively()
    }

    // === Phase benchmarks ===

    @Benchmark
    fun phase1_stringCollection(): Int {
        val strings = mutableSetOf<String>()
        var count = 0
        for (node in graph.nodes(Node::class.java)) {
            NodeSerializer.collectNodeStrings(listOf(node), strings)
            count++
        }
        return strings.size
    }

    @Benchmark
    fun phase2_metadataCollection(): GraphMetadata {
        return GraphStore.collectMetadata(graph)
    }

    @Benchmark
    fun phase3_stringTableBuild(): Any {
        val dir = Files.createTempDirectory("strbuild")
        try {
            return StringTable.build(allStrings, dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Benchmark
    fun phase4_labelEncoding(): ByteArray {
        val numNodes = maxNodeId + 1
        val comps = mutableMapOf<Long, BranchComparison>()
        return buildLabelArrayForBench(graph, numNodes, comps)
    }

    @Benchmark
    fun phase5_bvgraphStore() {
        val dir = Files.createTempDirectory("bvg")
        try {
            BVGraph.store(
                GraphStore.PrecomputedImmutableGraph(forwardAdj),
                dir.resolve("forward").toString(),
                BVGraph.DEFAULT_WINDOW_SIZE, BVGraph.DEFAULT_MAX_REF_COUNT,
                BVGraph.DEFAULT_MIN_INTERVAL_LENGTH, BVGraph.DEFAULT_ZETA_K,
                0, 2
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Benchmark
    fun phase6_nodedataWrite() {
        val dir = Files.createTempDirectory("nd")
        try {
            DataOutputStream(BufferedOutputStream(dir.resolve("graph.nodedata").toFile().outputStream())).use { dos ->
                NodeSerializer.writeHeader(dos, NodeSerializer.MAGIC_NODEDATA)
                dos.writeInt(graphNodeCount)
                for (node in graph.nodes(Node::class.java)) {
                    NodeSerializer.writeNode(dos, node, stringTable)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Benchmark
    fun phase7_nodeindexBuild() {
        val indexFile = tmpDir.resolve("graph.nodeindex.tmp")
        try {
            GraphStore.buildNodeIndex(
                tmpDir.resolve("graph.nodedata"), indexFile, stringTable
            )
        } finally {
            indexFile.toFile().delete()
        }
    }

    @Benchmark
    fun phase8_metadataWrite() {
        val dir = Files.createTempDirectory("meta")
        try {
            DataOutputStream(BufferedOutputStream(dir.resolve("graph.metadata").toFile().outputStream())).use { dos ->
                NodeSerializer.saveMetadata(metadata, dos, stringTable)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // === Helper methods (mirror GraphStore private methods for benchmark access) ===

    private fun buildForwardAdjacencyForBench(graph: Graph, numNodes: Int): GraphStore.PrecomputedAdjacency {
        val forwardDeg = IntArray(numNodes)
        for (node in 0 until numNodes) {
            val targets = mutableSetOf<Int>()
            for (edge in graph.outgoing(NodeId(node))) {
                targets.add(edge.to.value)
            }
            forwardDeg[node] = targets.size
        }
        val forwardOffsets = LongArray(numNodes + 1)
        for (i in 0 until numNodes) {
            forwardOffsets[i + 1] = forwardOffsets[i] + forwardDeg[i]
        }
        val forwardTargets = IntArray(forwardOffsets[numNodes].toInt())
        for (node in 0 until numNodes) {
            val targets = mutableSetOf<Int>()
            for (edge in graph.outgoing(NodeId(node))) {
                targets.add(edge.to.value)
            }
            val sorted = targets.toIntArray().also { it.sort() }
            System.arraycopy(sorted, 0, forwardTargets, forwardOffsets[node].toInt(), sorted.size)
        }
        return GraphStore.PrecomputedAdjacency(numNodes, forwardTargets, forwardOffsets)
    }

    private fun buildLabelArrayForBench(
        graph: Graph,
        numNodes: Int,
        comparisonMap: MutableMap<Long, BranchComparison>
    ): ByteArray {
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
}
