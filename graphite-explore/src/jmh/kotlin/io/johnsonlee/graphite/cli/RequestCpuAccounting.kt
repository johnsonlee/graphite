package io.johnsonlee.graphite.cli

import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.util.concurrent.atomic.AtomicBoolean
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
 * CPU accounting for a measured request window, by Java-thread identity rather than by native
 * thread name.
 *
 * `javaThreadCpuNanos` is the sum of each Java thread's own on-CPU time over the window, read from
 * the JVM's own [ThreadMXBean.getThreadCpuTime] keyed by [java.lang.Thread.getId]. The JVM's
 * internal native threads (collectors, compilers, the VM and service threads) have no `Thread`
 * object and no thread id, so they are never in the sum -- no name matching, and a request worker
 * that happens to share a HotSpot thread's name (`/proc/<tid>/comm` is mutable and truncated to
 * 15 bytes) cannot be mistaken for one and dropped.
 *
 * A background sampler refreshes each Java thread's most recent on-CPU time on a fixed interval
 * over the window, so a thread that exits mid-window still contributes its CPU up to its last
 * sample rather than being lost (or forcing the window to fail closed on a benign, incidental
 * thread such as the JDK client's `Keep-Alive-Timer`). At most one sample interval of any single
 * thread's CPU can go unattributed, and that remainder still shows in the process figure. The
 * sampler thread excludes itself from the sum. A thread present at both ends contributes its
 * after-minus-before delta; one born inside the window contributes its whole in-window CPU.
 *
 * `processCpuNanos` is kept as an advisory figure (the whole process, read innermost so its
 * interval is contained in the thread snapshots); `jvmInternalCpuNanos` is the residual
 * `process - java`, the share spent on the JVM's native threads (and on the sampler itself).
 */
internal object RequestCpuAccounting {
    /** Fails closed, without allocating or starting threads, when the accounting is unavailable. */
    fun requireAvailable() {
        processCpuTimeNanos()
        val threads = ManagementFactory.getThreadMXBean()
        check(threads.isThreadCpuTimeSupported) { "Per-thread CPU time is unsupported on this JVM" }
        if (!threads.isThreadCpuTimeEnabled) threads.isThreadCpuTimeEnabled = true
        check(threads.currentThreadCpuTime >= 0L) { "Per-thread CPU time is disabled on this JVM" }
    }

    fun <T> measure(action: () -> T): Pair<T, RequestCpuSample> {
        val threads = ManagementFactory.getThreadMXBean()
        // latestCpu holds each Java thread's most recent successful getThreadCpuTime over the window;
        // it is seeded with the start snapshot and refreshed by the sampler on a fixed interval, so a
        // thread that vanishes mid-window keeps the CPU it accrued up to its last sample. Guarded by
        // `lock` because the sampler thread and this thread both touch it.
        val lock = Any()
        val beforeCpu = threadCpuSnapshot(threads, threads.allThreadIds)
        val latestCpu = HashMap<Long, Long>(beforeCpu.size * 2).apply { putAll(beforeCpu) }

        val sampling = AtomicBoolean(true)
        val sampler = Thread({
            while (sampling.get()) {
                sampleInto(threads, latestCpu, lock)
                try {
                    Thread.sleep(CPU_ACCOUNTING_SAMPLE_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, CPU_ACCOUNTING_SAMPLER_THREAD_NAME).apply { isDaemon = true }
        val samplerId = sampler.id
        sampler.start()

        // The process figure is read innermost so its interval is contained in the sampled window.
        val beforeProcess = processCpuTimeNanos()
        val result = action()
        val afterProcess = processCpuTimeNanos()

        sampling.set(false)
        sampler.join()
        // A final sample after the action closes the window; a thread still alive here is captured at
        // its end-of-window CPU, and one gone by now keeps its last in-window sample.
        sampleInto(threads, latestCpu, lock)

        var javaCpu = 0L
        synchronized(lock) {
            for ((id, latest) in latestCpu) {
                if (id == samplerId) continue
                val delta = latest - (beforeCpu[id] ?: 0L)
                if (delta > 0L) javaCpu += delta
            }
        }
        val processCpu = (afterProcess - beforeProcess).coerceAtLeast(0L)
        return result to RequestCpuSample(
            processCpuNanos = processCpu,
            jvmInternalCpuNanos = (processCpu - javaCpu).coerceAtLeast(0L),
            jvmInternalThreadsEnded = 0L,
            javaThreadCpuNanos = javaCpu,
            underflowNanos = (javaCpu - processCpu).coerceAtLeast(0L)
        )
    }

    fun processCpuTimeNanos(): Long =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.processCpuTime ?: error("Process CPU time is unavailable on this JVM")

    /** Records the current on-CPU time of every live Java thread, keeping the maximum seen per id. */
    private fun sampleInto(threads: ThreadMXBean, latestCpu: MutableMap<Long, Long>, lock: Any) {
        for (id in threads.allThreadIds) {
            val cpu = threads.getThreadCpuTime(id)
            if (cpu < 0L) continue
            synchronized(lock) {
                val prev = latestCpu[id]
                if (prev == null || cpu > prev) latestCpu[id] = cpu
            }
        }
    }

    /** Per-thread on-CPU time keyed by Java thread id, for the ids whose read succeeded. */
    private fun threadCpuSnapshot(threads: ThreadMXBean, ids: LongArray): Map<Long, Long> {
        val snapshot = HashMap<Long, Long>(ids.size * 2)
        for (id in ids) {
            val cpu = threads.getThreadCpuTime(id)
            if (cpu >= 0L) snapshot[id] = cpu
        }
        return snapshot
    }
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
            checkCollidingNameWorkerIsCharged(failures)
            checkTransientWorkerIsAccounted(failures)
            checkPresentThenGoneIsAccounted(failures)
            checkProcessBound("idle action", failures) { 0L }
            checkProcessBound("collector-loaded action", failures) { collectorLoad() }
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            failures.add(failure.message ?: failure.toString())
        }
        if (failures.isNotEmpty()) {
            failures.forEach { println("cpu-accounting-contract: FAIL $it") }
            exitProcess(1)
        }
        println("cpu-accounting-contract: PASS")
    }

    /**
     * A persistent request worker whose native name collides with a HotSpot thread must be charged
     * in full, not mistaken for a JVM-internal thread and subtracted. This is the negative contract
     * for the name-based accounting this replaces, where such a worker hid ~96% of its own CPU.
     */
    private fun checkCollidingNameWorkerIsCharged(failures: MutableList<String>) {
        val go = java.util.concurrent.SynchronousQueue<Unit>()
        val done = java.util.concurrent.SynchronousQueue<Long>()
        val collidingWorker = Runnable {
            while (true) {
                go.take()
                val start = ManagementFactory.getThreadMXBean().currentThreadCpuTime
                burnCpu(CPU_ACCOUNTING_WORKER_NANOS)
                done.put(ManagementFactory.getThreadMXBean().currentThreadCpuTime - start)
            }
        }
        Thread(collidingWorker, COLLIDING_INTERNAL_THREAD_NAME).apply { isDaemon = true; start() }

        val workerCpu = AtomicLong()
        val (_, sample) = RequestCpuAccounting.measure {
            go.put(Unit)
            workerCpu.set(done.take())
        }
        assertAccounted("colliding-name worker '$COLLIDING_INTERNAL_THREAD_NAME'", workerCpu.get(), sample, failures)
    }

    /**
     * A worker created inside the window that does real CPU work must have that CPU accounted, even
     * though it is joined and gone before the window closes: the sampler captures it up to its last
     * sample. Under the old fail-closed accounting this threw; now it must be counted, so a candidate
     * that moves work into a short-lived worker cannot hide it.
     */
    private fun checkTransientWorkerIsAccounted(failures: MutableList<String>) {
        val workerCpu = AtomicLong()
        val (_, sample) = RequestCpuAccounting.measure {
            val worker = Thread {
                val start = ManagementFactory.getThreadMXBean().currentThreadCpuTime
                burnCpu(CPU_ACCOUNTING_WORKER_NANOS)
                workerCpu.set(ManagementFactory.getThreadMXBean().currentThreadCpuTime - start)
            }
            worker.start()
            worker.join()
        }
        assertAccounted("transient worker born and joined inside the window", workerCpu.get(), sample, failures)
    }

    /**
     * A worker present at the window start that does real CPU work and then exits mid-window must
     * have that CPU accounted up to its last sample, not dropped. This is the case a benign
     * incidental thread was tripping under the old fail-closed accounting; the sampler now captures
     * the work instead of aborting.
     */
    private fun checkPresentThenGoneIsAccounted(failures: MutableList<String>) {
        val go = java.util.concurrent.SynchronousQueue<Unit>()
        val started = java.util.concurrent.CountDownLatch(1)
        val workerCpu = AtomicLong()
        // The worker exists before the window opens (present in the start snapshot); inside the window
        // it does its CPU work and then returns, so it is gone before the window closes.
        val worker = Thread {
            started.countDown()
            go.take()
            val start = ManagementFactory.getThreadMXBean().currentThreadCpuTime
            burnCpu(CPU_ACCOUNTING_WORKER_NANOS)
            workerCpu.set(ManagementFactory.getThreadMXBean().currentThreadCpuTime - start)
        }.apply { isDaemon = true; start() }
        started.await()
        val (_, sample) = RequestCpuAccounting.measure {
            go.put(Unit)
            worker.join()
        }
        assertAccounted("worker present at the start that exited mid-window", workerCpu.get(), sample, failures)
    }

    private fun assertAccounted(label: String, expected: Long, sample: RequestCpuSample, failures: MutableList<String>) {
        println("cpu-accounting-contract: $label $expected ns, accounted ${sample.javaThreadCpuNanos} ns, " +
            "process ${sample.processCpuNanos} ns")
        if (expected <= 0L) {
            failures.add("the $label recorded no CPU time")
            return
        }
        if (sample.javaThreadCpuNanos < expected * CPU_ACCOUNTING_MIN_SHARE_PERCENT / PERCENT) {
            failures.add("$label was not accounted: worker $expected ns, accounted ${sample.javaThreadCpuNanos} ns")
        }
    }

    /**
     * The request-serving Java sum is per-thread on-CPU time over an interval the process figure
     * contains, so it can exceed the process figure only by snapshot overhead, whatever the JVM's
     * own threads (and the sampler) did around the action. A larger overshoot means the intervals
     * are not nested.
     */
    private fun checkProcessBound(label: String, failures: MutableList<String>, action: () -> Long) {
        val (_, sample) = RequestCpuAccounting.measure(action)
        println("cpu-accounting-contract: $label process ${sample.processCpuNanos} ns, " +
            "java ${sample.javaThreadCpuNanos} ns, overshoot ${sample.underflowNanos} ns")
        if (sample.underflowNanos > CPU_ACCOUNTING_MAX_OVERSHOOT_NANOS) {
            failures.add("request-serving Java CPU exceeded the process interval on the $label by " +
                "${sample.underflowNanos} ns")
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

    /** Busy-spins on this thread for [nanos] of wall time, accruing on-CPU time. */
    private fun burnCpu(nanos: Long) {
        val until = System.nanoTime() + nanos
        var sink = 0L
        while (System.nanoTime() < until) sink += sink xor System.nanoTime()
        if (sink == Long.MIN_VALUE) println(sink)
    }
}

private const val CPU_ACCOUNTING_WORKER_NANOS = 200_000_000L

/** How often the background sampler refreshes per-thread CPU; the most any one vanishing thread's
 * CPU can go unattributed. Small enough that a 200 ms worker is captured to well over 90%. */
private const val CPU_ACCOUNTING_SAMPLE_INTERVAL_MILLIS = 10L
private const val CPU_ACCOUNTING_SAMPLER_THREAD_NAME = "graphite-cpu-accounting-sampler"

private const val CPU_ACCOUNTING_GARBAGE_CHUNKS = 512
private const val CPU_ACCOUNTING_GARBAGE_CHUNK_BYTES = 1 shl 20
private const val CPU_ACCOUNTING_GARBAGE_RETAINED = 64

/** Two ticks of the process CPU clock (10 ms on Linux), the only slack the nesting leaves. */
private const val CPU_ACCOUNTING_MAX_OVERSHOOT_NANOS = 20_000_000L
private const val CPU_ACCOUNTING_MIN_SHARE_PERCENT = 90L
private const val PERCENT = 100L

/** A HotSpot native thread name, truncated to 15 bytes, that an application worker can collide with. */
private const val COLLIDING_INTERNAL_THREAD_NAME = "Service Thread"
