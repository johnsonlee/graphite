package io.johnsonlee.graphite.webgraph

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

// ============================================================================
//  Edge label allocation: HashMap vs flat arrays at 5M scale
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='EdgeLabelFlatArrayAlloc' -Pjmh.prof='gc'
// ============================================================================

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 3, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class EdgeLabelFlatArrayAllocBenchmark {

    @Param("10000000")
    var edgeCount: Int = 0

    @Benchmark
    fun hashMap_alloc(): Long2IntOpenHashMap {
        val map = Long2IntOpenHashMap(edgeCount)
        map.defaultReturnValue(-1)
        for (i in 0 until edgeCount) {
            val key = (i / 3).toLong().shl(32) or (i % 10_000_000).toLong().and(0xFFFFFFFFL)
            map.put(key, i and 0xFF)
        }
        map.trim()
        return map
    }

    @Benchmark
    fun flatArray_alloc(): Pair<ByteArray, LongArray> {
        // Simulate: forwardLabels (byte per edge) + cumulativeOutdeg (long per node)
        val nodeCount = edgeCount // 5M nodes with ~1 edge each average
        val forwardLabels = ByteArray(edgeCount)
        val cumulativeOutdeg = LongArray(nodeCount + 1)
        // Populate labels
        for (i in 0 until edgeCount) {
            forwardLabels[i] = (i and 0xFF).toByte()
        }
        // Populate cumulative outdegree (simulate ~1 edge per node)
        for (i in 0 until nodeCount) {
            cumulativeOutdeg[i + 1] = cumulativeOutdeg[i] + 1
        }
        return forwardLabels to cumulativeOutdeg
    }
}

// ============================================================================
//  Node index allocation: HashMap vs flat array at 5M scale
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='NodeIndexFlatArrayAlloc' -Pjmh.prof='gc'
// ============================================================================

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 3, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class NodeIndexFlatArrayAllocBenchmark {

    @Param("10000000")
    var nodeCount: Int = 0

    @Benchmark
    fun hashMap_alloc(): Int2LongOpenHashMap {
        val map = Int2LongOpenHashMap(nodeCount)
        map.defaultReturnValue(-1)
        for (i in 0 until nodeCount) {
            map.put(i, i.toLong() * 128)
        }
        map.trim()
        return map
    }

    @Benchmark
    fun flatArray_alloc(): LongArray {
        val offsets = LongArray(nodeCount)
        for (i in 0 until nodeCount) {
            offsets[i] = i.toLong() * 128
        }
        return offsets
    }
}

// ============================================================================
//  Edge label lookup: HashMap vs flat array
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='EdgeLabelFlatArrayLookup'
// ============================================================================

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class EdgeLabelFlatArrayLookupBenchmark {

    @Param("10000000")
    var edgeCount: Int = 0

    private lateinit var hashMap: Long2IntOpenHashMap
    private lateinit var forwardLabels: ByteArray
    private lateinit var cumulativeOutdeg: LongArray
    // Mock successor arrays: for simplicity, each node has exactly 1 successor (node i -> node i+1)
    // In reality, BVGraph.successorArray provides this
    private lateinit var lookupKeys: LongArray
    private lateinit var lookupFromTo: Array<IntArray> // [from, to] pairs

    @Setup(Level.Trial)
    fun setup() {
        val nodeCount = edgeCount

        // Build HashMap (old approach)
        hashMap = Long2IntOpenHashMap(edgeCount)
        hashMap.defaultReturnValue(0)
        for (i in 0 until edgeCount) {
            val from = i
            val to = (i + 1) % nodeCount
            val key = from.toLong().shl(32) or to.toLong().and(0xFFFFFFFFL)
            hashMap.put(key, i and 0xFF)
        }
        hashMap.trim()

        // Build flat arrays (new approach)
        forwardLabels = ByteArray(edgeCount)
        cumulativeOutdeg = LongArray(nodeCount + 1)
        for (i in 0 until edgeCount) {
            forwardLabels[i] = (i and 0xFF).toByte()
            cumulativeOutdeg[i + 1] = cumulativeOutdeg[i] + 1
        }

        // Pre-generate 10K random lookup keys
        val rng = java.util.Random(42)
        lookupKeys = LongArray(10_000) {
            val from = rng.nextInt(nodeCount)
            val to = (from + 1) % nodeCount
            from.toLong().shl(32) or to.toLong().and(0xFFFFFFFFL)
        }
        lookupFromTo = Array(10_000) {
            val from = rng.nextInt(nodeCount)
            val to = (from + 1) % nodeCount
            intArrayOf(from, to)
        }
    }

    @Benchmark
    fun hashMap_get(): Int {
        var sum = 0
        for (key in lookupKeys) {
            sum += hashMap.get(key)
        }
        return sum
    }

    @Benchmark
    fun flatArray_get(): Int {
        var sum = 0
        for (pair in lookupFromTo) {
            val from = pair[0]
            // In real code: pos = Arrays.binarySearch(successorArray, 0, outdeg, to)
            // Here with 1 edge per node, pos is always 0
            val label = forwardLabels[(cumulativeOutdeg[from]).toInt()].toInt() and 0xFF
            sum += label
        }
        return sum
    }
}

// ============================================================================
//  Node index lookup: HashMap vs flat array
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='NodeIndexFlatArrayLookup'
// ============================================================================

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class NodeIndexFlatArrayLookupBenchmark {

    @Param("10000000")
    var nodeCount: Int = 0

    private lateinit var hashMap: Int2LongOpenHashMap
    private lateinit var offsets: LongArray
    private lateinit var lookupKeys: IntArray

    @Setup(Level.Trial)
    fun setup() {
        hashMap = Int2LongOpenHashMap(nodeCount)
        hashMap.defaultReturnValue(-1)
        offsets = LongArray(nodeCount)
        for (i in 0 until nodeCount) {
            hashMap.put(i, i.toLong() * 128)
            offsets[i] = i.toLong() * 128
        }
        hashMap.trim()

        val rng = java.util.Random(42)
        lookupKeys = IntArray(10_000) { rng.nextInt(nodeCount) }
    }

    @Benchmark
    fun hashMap_get(): Long {
        var sum = 0L
        for (key in lookupKeys) {
            sum += hashMap.get(key)
        }
        return sum
    }

    @Benchmark
    fun flatArray_get(): Long {
        var sum = 0L
        for (key in lookupKeys) {
            sum += offsets[key]
        }
        return sum
    }
}
