package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.input.LoaderConfig
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Benchmark tests to measure memory efficiency of the graph data structures.
 *
 * Key optimizations measured:
 * 1. NodeId: String -> Int (saves ~36 bytes per node)
 * 2. Graph storage: HashMap -> Int2ObjectOpenHashMap (~40% memory reduction)
 */
class MemoryBenchmarkTest {

    /**
     * Benchmark: Measure actual object sizes using instrumentation-free estimation.
     * This test creates large arrays to minimize GC noise.
     */
    @Test
    fun `benchmark NodeId memory - Int vs String`() {
        val count = 500_000

        println("=== NodeId Memory Benchmark (Int vs String) ===")
        println("Creating $count node IDs...")
        println()

        // Force GC and get baseline
        forceGc()
        val baseline = getUsedMemory()

        // Create Int-based NodeIds (current implementation)
        NodeId.reset()
        val intIds = Array(count) { NodeId.next() }
        forceGc()
        val afterIntIds = getUsedMemory()
        val intMemory = afterIntIds - baseline

        println("Int-based NodeId (current):")
        println("  Total memory: ${formatBytes(intMemory)}")
        println("  Per node: ${String.format("%.1f", intMemory.toDouble() / count)} bytes")
        println()

        // Clear and measure String-based (simulated old implementation)
        @Suppress("UNUSED_VARIABLE")
        val keepIntIds = intIds // Keep reference to prevent GC
        forceGc()
        val beforeStringIds = getUsedMemory()

        val stringIds = Array(count) { i -> "node-$i" }
        forceGc()
        val afterStringIds = getUsedMemory()
        val stringMemory = afterStringIds - beforeStringIds

        println("String-based NodeId (old):")
        println("  Total memory: ${formatBytes(stringMemory)}")
        println("  Per node: ${String.format("%.1f", stringMemory.toDouble() / count)} bytes")
        println()

        val savings = stringMemory - intMemory
        val savingsPercent = (savings.toDouble() / stringMemory * 100)
        println("SAVINGS:")
        println("  Total: ${formatBytes(savings)}")
        println("  Percent: ${String.format("%.1f", savingsPercent)}%")
        println("  Per 100K nodes: ${formatBytes(savings / 5)}")
        println()

        assertTrue(intMemory < stringMemory, "Int NodeId should use less memory")
    }

    /**
     * Benchmark: Compare Int2ObjectOpenHashMap vs HashMap for graph storage.
     */
    @Test
    fun `benchmark graph map memory - Fastutil vs HashMap`() {
        val nodeCount = 200_000

        println("=== Graph Map Memory Benchmark (Fastutil vs HashMap) ===")
        println("Creating $nodeCount nodes...")
        println()

        // Create test nodes first
        NodeId.reset()
        val nodes = Array(nodeCount) { i ->
            IntConstant(NodeId.next(), i)
        }

        // Measure Int2ObjectOpenHashMap (current implementation)
        forceGc()
        val baseline1 = getUsedMemory()

        val fastutilMap = Int2ObjectOpenHashMap<Node>(nodeCount)
        nodes.forEach { node ->
            fastutilMap.put(node.id.value, node)
        }
        fastutilMap.trim()

        forceGc()
        val afterFastutil = getUsedMemory()
        val fastutilMemory = afterFastutil - baseline1

        println("Int2ObjectOpenHashMap (current):")
        println("  Total memory: ${formatBytes(fastutilMemory)}")
        println("  Per entry: ${String.format("%.1f", fastutilMemory.toDouble() / nodeCount)} bytes")
        println()

        // Keep reference and measure HashMap
        @Suppress("UNUSED_VARIABLE")
        val keepFastutil = fastutilMap
        forceGc()
        val baseline2 = getUsedMemory()

        val hashMap = HashMap<Int, Node>(nodeCount)
        nodes.forEach { node ->
            hashMap[node.id.value] = node
        }

        forceGc()
        val afterHashMap = getUsedMemory()
        val hashMapMemory = afterHashMap - baseline2

        println("HashMap<Int, Node> (old):")
        println("  Total memory: ${formatBytes(hashMapMemory)}")
        println("  Per entry: ${String.format("%.1f", hashMapMemory.toDouble() / nodeCount)} bytes")
        println()

        val savings = hashMapMemory - fastutilMemory
        val savingsPercent = if (hashMapMemory > 0) (savings.toDouble() / hashMapMemory * 100) else 0.0
        println("SAVINGS:")
        println("  Total: ${formatBytes(savings)}")
        println("  Percent: ${String.format("%.1f", savingsPercent)}%")
        println()
    }

    /**
     * Benchmark: Full graph construction with real test classes.
     */
    @Test
    fun `benchmark real graph construction`() {
        val testClassesDir = findTestClassesDir()
        if (!testClassesDir.exists()) {
            println("Test classes directory not found, skipping")
            return
        }

        println("=== Real Graph Memory Benchmark ===")
        println("Loading test classes from: $testClassesDir")
        println()

        // Reset NodeId counter for clean measurement
        NodeId.reset()
        forceGc()
        val startMem = getUsedMemory()

        val loader = JavaProjectLoader(LoaderConfig(
            includePackages = listOf("sample"),
            buildCallGraph = false
        ))
        val graph = loader.load(testClassesDir)

        forceGc()
        val endMem = getUsedMemory()
        val graphMemory = endMem - startMem

        // Count nodes by type
        val totalNodes = graph.nodes(Node::class.java).count()
        val callSites = graph.nodes(CallSiteNode::class.java).count()
        val localVars = graph.nodes(LocalVariable::class.java).count()
        val constants = graph.nodes(ConstantNode::class.java).count()
        val fields = graph.nodes(FieldNode::class.java).count()
        val params = graph.nodes(ParameterNode::class.java).count()
        val returns = graph.nodes(ReturnNode::class.java).count()

        println("Graph Statistics:")
        println("  Total nodes: $totalNodes")
        println("    - CallSiteNode: $callSites")
        println("    - LocalVariable: $localVars")
        println("    - ConstantNode: $constants")
        println("    - FieldNode: $fields")
        println("    - ParameterNode: $params")
        println("    - ReturnNode: $returns")
        println()
        println("Memory Usage:")
        println("  Total graph memory: ${formatBytes(graphMemory)}")
        println("  Memory per node: ${String.format("%.1f", graphMemory.toDouble() / totalNodes)} bytes")
        println()

        // Estimate savings
        val oldNodeIdOverhead = totalNodes * 40L // ~40 bytes per String
        val newNodeIdOverhead = totalNodes * 4L  // 4 bytes per Int
        val nodeIdSavings = oldNodeIdOverhead - newNodeIdOverhead

        println("Estimated Savings (NodeId only):")
        println("  Old String NodeIds would use: ${formatBytes(oldNodeIdOverhead)}")
        println("  New Int NodeIds use: ${formatBytes(newNodeIdOverhead)}")
        println("  Savings: ${formatBytes(nodeIdSavings)} (${String.format("%.0f", nodeIdSavings * 100.0 / oldNodeIdOverhead)}%)")
    }

    /**
     * Benchmark: Simulate large-scale application.
     */
    @Test
    fun `benchmark large scale simulation`() {
        println("=== Large Scale Simulation ===")
        println("Simulating a 10,000-method application...")
        println()

        // Typical ratios from real applications
        val methodCount = 10_000
        val nodesPerMethod = 50  // locals, params, calls, etc.
        val edgesPerMethod = 30
        val totalNodes = methodCount * nodesPerMethod
        val totalEdges = methodCount * edgesPerMethod

        println("Simulated application:")
        println("  Methods: $methodCount")
        println("  Nodes: $totalNodes")
        println("  Edges: $totalEdges")
        println()

        // Calculate memory for old implementation
        val oldNodeIdMemory = totalNodes * 40L  // String ~40 bytes
        val oldMapOverhead = totalNodes * 48L   // HashMap entry ~48 bytes
        val oldEdgeListOverhead = totalEdges * 24L // ArrayList entry ~24 bytes
        val oldTotal = oldNodeIdMemory + oldMapOverhead + oldEdgeListOverhead

        // Calculate memory for new implementation
        val newNodeIdMemory = totalNodes * 4L   // Int = 4 bytes
        val newMapOverhead = totalNodes * 24L   // Int2ObjectOpenHashMap ~24 bytes
        val newEdgeListOverhead = totalEdges * 16L // ObjectArrayList ~16 bytes
        val newTotal = newNodeIdMemory + newMapOverhead + newEdgeListOverhead

        println("Old Implementation (HashMap + String NodeId):")
        println("  NodeId storage: ${formatBytes(oldNodeIdMemory)}")
        println("  Map overhead: ${formatBytes(oldMapOverhead)}")
        println("  Edge lists: ${formatBytes(oldEdgeListOverhead)}")
        println("  TOTAL: ${formatBytes(oldTotal)}")
        println()

        println("New Implementation (Fastutil + Int NodeId):")
        println("  NodeId storage: ${formatBytes(newNodeIdMemory)}")
        println("  Map overhead: ${formatBytes(newMapOverhead)}")
        println("  Edge lists: ${formatBytes(newEdgeListOverhead)}")
        println("  TOTAL: ${formatBytes(newTotal)}")
        println()

        val savings = oldTotal - newTotal
        println("TOTAL SAVINGS:")
        println("  ${formatBytes(savings)} (${String.format("%.1f", savings * 100.0 / oldTotal)}%)")
        println()
        println("For a 100K-method enterprise application:")
        println("  Old: ${formatBytes(oldTotal * 10)}")
        println("  New: ${formatBytes(newTotal * 10)}")
        println("  Savings: ${formatBytes(savings * 10)}")
    }

    /**
     * Test NodeId sequential generation.
     */
    @Test
    fun `should generate sequential NodeIds`() {
        NodeId.reset()

        val id1 = NodeId.next()
        val id2 = NodeId.next()
        val id3 = NodeId.next()

        assertTrue(id1.value == 1)
        assertTrue(id2.value == 2)
        assertTrue(id3.value == 3)

        NodeId.reset()
        val id4 = NodeId.next()
        assertTrue(id4.value == 1)
    }

    private fun forceGc() {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
    }

    private fun getUsedMemory(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
            bytes < 0 -> "-${formatBytes(-bytes)}"
            else -> "$bytes bytes"
        }
    }

    private fun findTestClassesDir(): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test")
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test")
        return if (submodulePath.exists()) submodulePath else rootPath
    }
}
