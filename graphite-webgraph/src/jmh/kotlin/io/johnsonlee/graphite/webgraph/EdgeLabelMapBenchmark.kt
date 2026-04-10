package io.johnsonlee.graphite.webgraph

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import org.openjdk.jmh.annotations.*
import java.util.HashMap
import java.util.concurrent.TimeUnit

// ============================================================================
//  edgeLabelMap allocation: measures total memory to build the map
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='EdgeLabelMapAlloc' -Pjmh.prof='gc'
// ============================================================================

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 3, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class EdgeLabelMapAllocBenchmark {

    @Param("12000000")
    var edgeCount: Int = 0

    @Benchmark
    fun hashMap_alloc(): HashMap<Long, Int> {
        val map = HashMap<Long, Int>(edgeCount, 0.75f)
        for (i in 0 until edgeCount) {
            val key = (i / 3).toLong().shl(32) or (i % 4_000_000).toLong().and(0xFFFFFFFFL)
            map[key] = i and 0xFF
        }
        return map
    }

    @Benchmark
    fun fastutil_alloc(): Long2IntOpenHashMap {
        val map = Long2IntOpenHashMap(edgeCount)
        map.defaultReturnValue(-1)
        for (i in 0 until edgeCount) {
            val key = (i / 3).toLong().shl(32) or (i % 4_000_000).toLong().and(0xFFFFFFFFL)
            map.put(key, i and 0xFF)
        }
        map.trim()
        return map
    }
}

// ============================================================================
//  nodeIndex allocation: measures total memory to build the map
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='NodeIndexAlloc' -Pjmh.prof='gc'
// ============================================================================

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(value = 3, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class NodeIndexAllocBenchmark {

    @Param("4000000")
    var nodeCount: Int = 0

    @Benchmark
    fun hashMap_alloc(): HashMap<Int, Long> {
        val map = HashMap<Int, Long>(nodeCount, 0.75f)
        for (i in 0 until nodeCount) {
            map[i] = i.toLong() * 128
        }
        return map
    }

    @Benchmark
    fun fastutil_alloc(): Int2LongOpenHashMap {
        val map = Int2LongOpenHashMap(nodeCount)
        map.defaultReturnValue(-1)
        for (i in 0 until nodeCount) {
            map.put(i, i.toLong() * 128)
        }
        map.trim()
        return map
    }
}

// ============================================================================
//  edgeLabelMap lookup throughput: random-access get
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='EdgeLabelMapLookup'
// ============================================================================

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class EdgeLabelMapLookupBenchmark {

    @Param("12000000")
    var edgeCount: Int = 0

    private lateinit var hashMap: HashMap<Long, Int>
    private lateinit var fastutilMap: Long2IntOpenHashMap
    private lateinit var lookupKeys: LongArray

    @Setup(Level.Trial)
    fun setup() {
        hashMap = HashMap(edgeCount, 0.75f)
        fastutilMap = Long2IntOpenHashMap(edgeCount)
        fastutilMap.defaultReturnValue(-1)

        for (i in 0 until edgeCount) {
            val key = (i / 3).toLong().shl(32) or (i % 4_000_000).toLong().and(0xFFFFFFFFL)
            hashMap[key] = i and 0xFF
            fastutilMap.put(key, i and 0xFF)
        }
        fastutilMap.trim()

        // Pre-generate 10K random lookup keys (mix of hits and misses)
        val rng = java.util.Random(42)
        lookupKeys = LongArray(10_000) {
            val i = rng.nextInt(edgeCount + edgeCount / 10) // ~10% misses
            (i / 3).toLong().shl(32) or (i % 4_000_000).toLong().and(0xFFFFFFFFL)
        }
    }

    @Benchmark
    fun hashMap_get(): Int {
        var sum = 0
        for (key in lookupKeys) {
            sum += hashMap.getOrDefault(key, 0)
        }
        return sum
    }

    @Benchmark
    fun fastutil_get(): Int {
        var sum = 0
        for (key in lookupKeys) {
            sum += fastutilMap.get(key)
        }
        return sum
    }
}

// ============================================================================
//  nodeIndex lookup throughput: random-access get
//  Run with: ./gradlew :graphite-webgraph:jmh -Pjmh.includes='NodeIndexLookup'
// ============================================================================

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgs = ["-Xmx4g"])
@State(Scope.Benchmark)
open class NodeIndexLookupBenchmark {

    @Param("4000000")
    var nodeCount: Int = 0

    private lateinit var hashMap: HashMap<Int, Long>
    private lateinit var fastutilMap: Int2LongOpenHashMap
    private lateinit var lookupKeys: IntArray

    @Setup(Level.Trial)
    fun setup() {
        hashMap = HashMap(nodeCount, 0.75f)
        fastutilMap = Int2LongOpenHashMap(nodeCount)
        fastutilMap.defaultReturnValue(-1)

        for (i in 0 until nodeCount) {
            hashMap[i] = i.toLong() * 128
            fastutilMap.put(i, i.toLong() * 128)
        }
        fastutilMap.trim()

        val rng = java.util.Random(42)
        lookupKeys = IntArray(10_000) {
            rng.nextInt(nodeCount + nodeCount / 10)
        }
    }

    @Benchmark
    fun hashMap_get(): Long {
        var sum = 0L
        for (key in lookupKeys) {
            sum += hashMap.getOrDefault(key, 0L)
        }
        return sum
    }

    @Benchmark
    fun fastutil_get(): Long {
        var sum = 0L
        for (key in lookupKeys) {
            sum += fastutilMap.get(key)
        }
        return sum
    }
}
