package io.johnsonlee.graphite.cli

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/** One measured window of the request-serving CPU accounting. */
internal data class RequestCpuSample(
    val processCpuNanos: Long,
    val jvmInternalCpuNanos: Long,
    val jvmInternalThreadsEnded: Long,
    val javaThreadCpuNanos: Long,
    val underflowNanos: Long
)

/**
 * CPU accounting for a measured request window: the process figure counts every thread that ran
 * in the window, including one created and joined inside it, and the CPU of the JVM's own native
 * threads (collectors, compilers, the VM and service threads), read per thread from the kernel's
 * scheduler accounting, is subtracted. The remainder is the CPU of the Java threads that served
 * the request, whatever their lifetime.
 *
 * The subtracted internal interval is nested inside the process interval: the process figure is
 * read first before the action and last after it, so internal CPU spent in the snapshot gaps is
 * charged to the request rather than removed. The remainder can then fall below zero only by the
 * process clock's tick, and that shortfall is reported rather than hidden.
 */
internal object RequestCpuAccounting {
    /** Fails closed, without touching the heap or starting threads, when the accounting is unavailable. */
    fun requireAvailable() {
        internalThreadCpuNanos()
    }

    fun <T> measure(action: () -> T): Pair<T, RequestCpuSample> {
        val beforeCpu = processCpuTimeNanos()
        val beforeInternal = internalThreadCpuNanos()
        val result = action()
        val afterInternal = internalThreadCpuNanos()
        val afterCpu = processCpuTimeNanos()
        val processCpu = (afterCpu - beforeCpu).coerceAtLeast(0L)
        // An internal thread that appears inside the window counts in full; one that disappears
        // inside it was idle before the JVM retired it, so its unread share is bounded by the
        // retirement idle time and is reported as a count rather than dropped silently.
        val internalCpu = afterInternal.entries.sumOf { (tid, cpu) -> cpu - (beforeInternal[tid] ?: 0L) }
            .coerceAtLeast(0L)
        val ended = beforeInternal.keys.count { it !in afterInternal }.toLong()
        val remainder = processCpu - internalCpu
        return result to RequestCpuSample(
            processCpuNanos = processCpu,
            jvmInternalCpuNanos = internalCpu,
            jvmInternalThreadsEnded = ended,
            javaThreadCpuNanos = remainder.coerceAtLeast(0L),
            underflowNanos = (-remainder).coerceAtLeast(0L)
        )
    }

    fun processCpuTimeNanos(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.processCpuTime ?: error("Process CPU time is unavailable on this JVM")

    /**
     * CPU time of the JVM's internal native threads, keyed by native thread id. Fails closed when
     * the accounting is unavailable, since the request-serving CPU row cannot be derived without it.
     */
    private fun internalThreadCpuNanos(): Map<Long, Long> {
        val tasks = Path.of("/proc/self/task")
        check(Files.isDirectory(tasks)) { "Per-thread CPU accounting is unavailable: $tasks is missing" }
        val result = HashMap<Long, Long>()
        Files.newDirectoryStream(tasks).use { entries ->
            for (task in entries) {
                val tid = task.fileName.toString().toLongOrNull() ?: continue
                val name = runCatching { Files.readString(task.resolve("comm")).trim() }.getOrNull() ?: continue
                if (!isJvmInternalThread(name)) continue
                val schedstat = runCatching { Files.readString(task.resolve("schedstat")) }.getOrNull() ?: continue
                val onCpuNanos = schedstat.trim().split(' ').firstOrNull()?.toLongOrNull()
                    ?: error("Unreadable scheduler accounting for thread $tid ($name): $schedstat")
                result[tid] = onCpuNanos
            }
        }
        check(result.isNotEmpty()) { "No JVM-internal thread found under $tasks; the accounting contract does not hold" }
        return result
    }

    private fun isJvmInternalThread(name: String): Boolean =
        JVM_INTERNAL_THREAD_NAMES.any { prefix -> name.startsWith(prefix) }
}

/**
 * Contract checks for [RequestCpuAccounting], run in a JVM of their own by the workflow before the
 * method-compatibility shards, never inside a benchmark trial: the collector-loaded scenario
 * allocates hundreds of megabytes and would otherwise perturb the fork it ran in. Exits non-zero
 * when the accounting does not hold on this JVM and kernel.
 */
object MethodCompatibilityCpuAccountingContract {
    @JvmStatic
    fun main(args: Array<String>) {
        val failures = ArrayList<String>()
        try {
            RequestCpuAccounting.requireAvailable()
            checkShortLivedWorker(failures)
            checkSnapshotNesting("idle action", failures) { 0L }
            checkSnapshotNesting("collector-loaded action", failures) { collectorLoad() }
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            failures.add(failure.message ?: failure.toString())
        }
        if (failures.isNotEmpty()) {
            failures.forEach { println("cpu-accounting-contract: FAIL $it") }
            exitProcess(1)
        }
        println("cpu-accounting-contract: PASS")
    }

    /** A worker created, run and joined inside the window must be charged to the row. */
    private fun checkShortLivedWorker(failures: MutableList<String>) {
        val workerCpu = AtomicLong()
        val (_, sample) = RequestCpuAccounting.measure {
            val worker = Thread {
                val start = System.nanoTime()
                var sink = 0L
                while (System.nanoTime() - start < CPU_ACCOUNTING_WORKER_NANOS) sink += sink xor System.nanoTime()
                workerCpu.set(ManagementFactory.getThreadMXBean().currentThreadCpuTime)
                if (sink == Long.MIN_VALUE) println(sink)
            }
            worker.start()
            worker.join()
        }
        val expected = workerCpu.get()
        println("cpu-accounting-contract: short-lived worker ${expected} ns, accounted ${sample.javaThreadCpuNanos} ns, " +
            "process ${sample.processCpuNanos} ns, internal ${sample.jvmInternalCpuNanos} ns")
        if (expected <= 0L) failures.add("the short-lived worker recorded no CPU time")
        if (sample.javaThreadCpuNanos < expected * CPU_ACCOUNTING_MIN_SHARE_PERCENT / PERCENT) {
            failures.add("a worker that lived inside the window was not charged: worker ${expected} ns, " +
                "accounted ${sample.javaThreadCpuNanos} ns")
        }
    }

    /**
     * Because the internal interval is read inside the process interval, the subtracted internal
     * CPU can exceed the process figure only by the process clock's tick, whatever the JVM's
     * threads did around the action; a larger shortfall means the intervals are not nested.
     */
    private fun checkSnapshotNesting(label: String, failures: MutableList<String>, action: () -> Long) {
        val (_, sample) = RequestCpuAccounting.measure(action)
        println("cpu-accounting-contract: $label process ${sample.processCpuNanos} ns, " +
            "internal ${sample.jvmInternalCpuNanos} ns, shortfall ${sample.underflowNanos} ns")
        if (sample.underflowNanos > CPU_ACCOUNTING_MAX_UNDERFLOW_NANOS) {
            failures.add("internal CPU exceeded the process interval on the $label by ${sample.underflowNanos} ns")
        }
    }

    /** Enough short-lived garbage to run the collector's threads inside the window. */
    private fun collectorLoad(): Long {
        val retained = ArrayList<ByteArray>(CPU_ACCOUNTING_GARBAGE_RETAINED)
        repeat(CPU_ACCOUNTING_GARBAGE_CHUNKS) { index ->
            val chunk = ByteArray(CPU_ACCOUNTING_GARBAGE_CHUNK_BYTES)
            chunk[index % chunk.size] = index.toByte()
            if (retained.size >= CPU_ACCOUNTING_GARBAGE_RETAINED) retained.removeAt(0)
            retained.add(chunk)
        }
        return retained.size.toLong()
    }
}

private const val CPU_ACCOUNTING_WORKER_NANOS = 200_000_000L
private const val CPU_ACCOUNTING_GARBAGE_CHUNKS = 512
private const val CPU_ACCOUNTING_GARBAGE_CHUNK_BYTES = 1 shl 20
private const val CPU_ACCOUNTING_GARBAGE_RETAINED = 64

/** Two ticks of the process CPU clock (10 ms on Linux), the only slack the nesting leaves. */
private const val CPU_ACCOUNTING_MAX_UNDERFLOW_NANOS = 20_000_000L
private const val CPU_ACCOUNTING_MIN_SHARE_PERCENT = 90L
private const val PERCENT = 100L

/** Native names of HotSpot's own threads: collectors, compilers, the VM thread and its services. */
private val JVM_INTERNAL_THREAD_NAMES = listOf(
    "VM Thread",
    "VM Periodic Tas",
    "Service Thread",
    "Monitor Deflati",
    "Sweeper thread",
    "Attach Listener",
    "C1 CompilerThre",
    "C2 CompilerThre",
    "GC Thread#",
    "G1 ",
    "ZWorker",
    "ZDirector",
    "ZDriver",
    "ZStat",
    "ZUncommitter",
    "Shenandoah",
    "Parallel GC",
    "Concurrent Mark"
)
