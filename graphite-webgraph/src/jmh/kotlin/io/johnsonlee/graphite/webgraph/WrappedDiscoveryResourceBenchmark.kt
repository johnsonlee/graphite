package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherResult
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.io.Closeable
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Resource counters are sampled outside the timed latency benchmarks. */
@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
open class WrappedDiscoveryResourceCounters {
    @JvmField var maxHeapBytes: Long = 0
    @JvmField var loadedHeapBytes: Long = 0
    @JvmField var peakUsedHeapBytes: Long = 0
    @JvmField var retainedHeapBytes: Long = 0
    @JvmField var retainedHeapDeltaBytes: Long = 0
    @JvmField var queryGcCount: Long = 0
    @JvmField var queryGcTimeMs: Long = 0
}

abstract class WrappedDiscoveryResourceState {
    protected lateinit var executor: CrossGraphCypherExecutor
    protected val loadedGraphs = mutableListOf<Graph>()
    protected val clearIndexMethods = mutableListOf<Method?>()
    private lateinit var sampler: WrappedDiscoveryHeapSampler
    private var loadedHeapBytes = 0L
    private var gcCountBefore = 0L
    private var gcTimeBefore = 0L
    private var activeCounters: WrappedDiscoveryResourceCounters? = null

    protected fun finishSetup(graphs: List<CypherGraph>) {
        executor = budgetedLatencyExecutor(graphs)
        sampler = WrappedDiscoveryHeapSampler()
    }

    @Setup(Level.Invocation)
    fun setupInvocation() {
        loadedGraphs.indices.forEach { index -> clearIndexMethods[index]?.invoke(loadedGraphs[index]) }
        forceWrappedDiscoveryGc()
        loadedHeapBytes = usedHeapBytes()
        gcCountBefore = gcCount()
        gcTimeBefore = gcTimeMs()
        sampler.start(loadedHeapBytes)
    }

    protected fun finishQuery(counters: WrappedDiscoveryResourceCounters) {
        activeCounters = counters
        counters.maxHeapBytes = Runtime.getRuntime().maxMemory()
        counters.loadedHeapBytes = loadedHeapBytes
        counters.peakUsedHeapBytes = sampler.stop()
        counters.queryGcCount = (gcCount() - gcCountBefore).coerceAtLeast(0)
        counters.queryGcTimeMs = (gcTimeMs() - gcTimeBefore).coerceAtLeast(0)
    }

    @TearDown(Level.Invocation)
    fun tearDownInvocation() {
        val counters = checkNotNull(activeCounters) { "Resource benchmark did not publish counters" }
        sampler.stop()
        forceWrappedDiscoveryGc()
        counters.retainedHeapBytes = usedHeapBytes()
        counters.retainedHeapDeltaBytes =
            (counters.retainedHeapBytes - counters.loadedHeapBytes).coerceAtLeast(0)
        activeCounters = null
    }

    protected fun closeResources(root: Path? = null) {
        loadedGraphs.asReversed().forEach { (it as? Closeable)?.close() }
        sampler.close()
        root?.toFile()?.deleteRecursively()
    }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1, jvmArgs = ["-Xmx4g"])
open class SingleGraphWrappedDiscoveryResourceBenchmark : WrappedDiscoveryResourceState() {
    private lateinit var root: Path

    @Setup(Level.Trial)
    fun setupTrial() {
        root = Files.createTempDirectory("graphite-single-resource")
        val builder = DefaultGraph.Builder()
        repeat(5_000) { index -> builder.addNode(StringConstant(NodeId(index), "symbol_$index")) }
        repeat(2_000) { index ->
            val caller = MethodDescriptor(
                TypeDescriptor("com.example.${if (index % 1_000 == 0) "Voucher" else "Feature"}Service$index"),
                "create$index",
                emptyList(),
                TypeDescriptor("void")
            )
            val callee = MethodDescriptor(
                TypeDescriptor("com.example.Dependency${index % 20}"),
                "invoke${index % 100}",
                emptyList(),
                TypeDescriptor("void")
            )
            builder.addNode(CallSiteNode(NodeId(5_000 + index), caller, callee, index, null, emptyList()))
        }
        val directory = root.resolve("graph")
        GraphStore.save(builder.build(), directory)
        val graph = GraphStore.loadMapped(directory)
        loadedGraphs += graph
        clearIndexMethods += clearMethod(graph)
        finishSetup(listOf(CypherGraph("graph-0", graph)))
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() = closeResources(root)

    @Benchmark
    fun singleGraphFootprint(counters: WrappedDiscoveryResourceCounters): CypherResult =
        executor.execute(WRAPPED_DISCOVERY_QUERY).also { result ->
            check(result.rows.size == 2)
            finishQuery(counters)
        }
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1, jvmArgs = ["-Xmx8g"])
open class AllFixtureWrappedDiscoveryResourceBenchmark : WrappedDiscoveryResourceState() {
    @Setup(Level.Trial)
    fun setupTrial() {
        val kinds = BenchmarkCorpusKind.entries
        val graphs = (0 until 36).map { graphIndex ->
            val kind = kinds[graphIndex % kinds.size]
            val graph = GraphStore.loadMapped(BenchmarkCorpus.persistedGraph(kind))
            check(graph.nodeCount(Node::class.java) == kind.expectedNodeCount)
            loadedGraphs += graph
            clearIndexMethods += clearMethod(graph)
            CypherGraph("fixture-$graphIndex-${kind.id}", graph)
        }
        check(graphs.size == 36)
        finishSetup(graphs)
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() = closeResources()

    @Benchmark
    fun allFixtureThirtySixGraphFootprint(counters: WrappedDiscoveryResourceCounters): CypherResult =
        executor.execute(ZERO_HIT_QUERY).also { result ->
            check(result.rows.isEmpty())
            finishQuery(counters)
        }
}

private fun clearMethod(graph: Graph): Method? = graph.javaClass.declaredMethods
    .firstOrNull { it.name.startsWith("clearStringPropertyIndexes") }
    ?.also { it.isAccessible = true }

private class WrappedDiscoveryHeapSampler : Closeable {
    private val running = AtomicBoolean(true)
    private val sampling = AtomicBoolean(false)
    private val maximum = AtomicLong(0)
    private val thread = Thread({ loop() }, "wrapped-discovery-heap-sampler").apply {
        isDaemon = true
        start()
    }

    fun start(baseline: Long) {
        maximum.set(baseline)
        sampling.set(true)
    }

    fun stop(): Long {
        sampling.set(false)
        maximum.accumulateAndGet(usedHeapBytes(), ::maxOf)
        return maximum.get()
    }

    override fun close() {
        sampling.set(false)
        running.set(false)
        thread.join(5_000)
    }

    private fun loop() {
        while (running.get()) {
            if (sampling.get()) maximum.accumulateAndGet(usedHeapBytes(), ::maxOf)
            LockSupport.parkNanos(1_000_000)
        }
    }
}

private fun usedHeapBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

private fun gcCount(): Long = ManagementFactory.getGarbageCollectorMXBeans()
    .sumOf { it.collectionCount.coerceAtLeast(0) }

private fun gcTimeMs(): Long = ManagementFactory.getGarbageCollectorMXBeans()
    .sumOf { it.collectionTime.coerceAtLeast(0) }

private fun forceWrappedDiscoveryGc() {
    repeat(3) {
        System.gc()
        System.runFinalization()
        Thread.sleep(100)
    }
}
