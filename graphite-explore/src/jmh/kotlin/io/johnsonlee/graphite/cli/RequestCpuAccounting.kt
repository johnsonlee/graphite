package io.johnsonlee.graphite.cli

import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
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
 * A thread present at both ends of the window contributes its after-minus-before delta; one born
 * inside the window and still alive at the end contributes its whole lifetime CPU (its start value
 * is zero). The two cases the two snapshots cannot see are handled by failing closed rather than
 * under-reporting:
 * - a Java thread present at the start that is gone at the end took its in-window CPU with it;
 * - a Java thread created and finished entirely inside the window is in neither snapshot, but the
 *   JVM's [ThreadMXBean.getTotalStartedThreadCount] still counted its start, so more starts than
 *   newly-present threads means such a worker existed.
 *
 * `processCpuNanos` is kept as an advisory figure (the whole process, read innermost so its
 * interval is contained in the thread snapshots); `jvmInternalCpuNanos` is the residual
 * `process - java`, the share spent on the JVM's native threads.
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

    fun <T> measure(action: () -> T): Pair<T, RequestCpuSample> =
        measure(action, contractUnreadableThreadId = null)

    /**
     * [contractUnreadableThreadId] forces one id's end-of-window CPU read to be treated as failed,
     * so the contract can deterministically exercise the race it otherwise cannot hit: a thread
     * present in the end id list that terminates before its `getThreadCpuTime` read. It is null on
     * every real measurement.
     */
    internal fun <T> measure(action: () -> T, contractUnreadableThreadId: Long?): Pair<T, RequestCpuSample> {
        val threads = ManagementFactory.getThreadMXBean()
        val beforeStarted = threads.totalStartedThreadCount
        val beforeCpu = threadCpuSnapshot(threads, threads.allThreadIds, unreadable = null)
        // Names captured at the start so a thread gone by the end can still be identified in the
        // failure message. Names are only ever reported, never used for an accounting decision.
        val beforeNames = threadNames(threads, beforeCpu.keys)
        // The process figure is read innermost so its interval is contained in the thread
        // snapshots; the request-serving Java sum can then exceed it only by snapshot overhead.
        val beforeProcess = processCpuTimeNanos()
        val result = action()
        val afterProcess = processCpuTimeNanos()
        val afterCpu = threadCpuSnapshot(threads, threads.allThreadIds, unreadable = contractUnreadableThreadId)
        val afterStarted = threads.totalStartedThreadCount

        // Liveness is derived from the ids whose CPU read succeeded, not the raw id list: a thread
        // enumerated at the end but gone before its read returns -1 and is absent from afterCpu, so
        // it counts as vanished rather than present-with-no-CPU -- otherwise a worker exiting during
        // the end snapshot would balance the started count and hide its CPU.
        val vanishedIds = beforeCpu.keys.filter { it !in afterCpu }
        check(vanishedIds.isEmpty()) {
            val described = vanishedIds.sorted().joinToString { id -> "${beforeNames[id] ?: "?"}#$id" }
            "${vanishedIds.size} Java thread(s) present at the start of the window were gone at the " +
                "end ($described); their in-window CPU cannot be accounted"
        }
        val appeared = afterCpu.keys.count { it !in beforeCpu }
        val transientWorkers = (afterStarted - beforeStarted) - appeared
        check(transientWorkers <= 0L) {
            "$transientWorkers Java worker(s) were created and finished inside the measured window; " +
                "their CPU cannot be accounted by thread identity"
        }

        var javaCpu = 0L
        for ((id, after) in afterCpu) {
            val delta = after - (beforeCpu[id] ?: 0L)
            if (delta > 0L) javaCpu += delta
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

    /**
     * Per-thread on-CPU time keyed by Java thread id, for the ids whose read succeeded; a thread
     * that died between enumeration and its read returns -1 and is omitted, so a caller keying its
     * liveness on this map's keys treats it as gone. [unreadable] forces one id to be omitted, for
     * the contract that exercises that race.
     */
    private fun threadCpuSnapshot(threads: ThreadMXBean, ids: LongArray, unreadable: Long?): Map<Long, Long> {
        val snapshot = HashMap<Long, Long>(ids.size * 2)
        for (id in ids) {
            if (id == unreadable) continue
            val cpu = threads.getThreadCpuTime(id)
            if (cpu >= 0L) snapshot[id] = cpu
        }
        return snapshot
    }

    /** Thread names for [ids], captured up front so a thread gone by the end can still be named. */
    private fun threadNames(threads: ThreadMXBean, ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val names = HashMap<Long, String>(ids.size * 2)
        for (info in threads.getThreadInfo(ids.toLongArray())) {
            if (info != null) names[info.threadId] = info.threadName
        }
        return names
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
            checkTransientWorkerFailsClosed(failures)
            checkEndSnapshotReadRaceFailsClosed(failures)
            checkPresentThenGoneFailsClosed(failures)
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
                val until = System.nanoTime() + CPU_ACCOUNTING_WORKER_NANOS
                var sink = 0L
                while (System.nanoTime() < until) sink += sink xor System.nanoTime()
                if (sink == Long.MIN_VALUE) println(sink)
                done.put(ManagementFactory.getThreadMXBean().currentThreadCpuTime - start)
            }
        }
        Thread(collidingWorker, COLLIDING_INTERNAL_THREAD_NAME).apply { isDaemon = true; start() }

        val workerCpu = java.util.concurrent.atomic.AtomicLong()
        val (_, sample) = RequestCpuAccounting.measure {
            go.put(Unit)
            workerCpu.set(done.take())
        }
        val expected = workerCpu.get()
        println("cpu-accounting-contract: colliding-name worker '$COLLIDING_INTERNAL_THREAD_NAME' " +
            "$expected ns, accounted ${sample.javaThreadCpuNanos} ns, process ${sample.processCpuNanos} ns")
        if (expected <= 0L) failures.add("the colliding-name worker recorded no CPU time")
        if (sample.javaThreadCpuNanos < expected * CPU_ACCOUNTING_MIN_SHARE_PERCENT / PERCENT) {
            failures.add("a persistent worker named '$COLLIDING_INTERNAL_THREAD_NAME' was not charged: " +
                "worker $expected ns, accounted ${sample.javaThreadCpuNanos} ns")
        }
    }

    /**
     * A worker created and joined entirely inside the window is in neither thread snapshot; the
     * accounting must fail closed rather than silently omit its CPU and let a candidate look cheaper.
     */
    private fun checkTransientWorkerFailsClosed(failures: MutableList<String>) {
        val failedClosed = runCatching {
            RequestCpuAccounting.measure {
                val worker = Thread {
                    val until = System.nanoTime() + CPU_ACCOUNTING_WORKER_NANOS
                    var sink = 0L
                    while (System.nanoTime() < until) sink += sink xor System.nanoTime()
                    if (sink == Long.MIN_VALUE) println(sink)
                }
                worker.start()
                worker.join()
            }
        }.isFailure
        println("cpu-accounting-contract: transient worker failed closed = $failedClosed")
        if (!failedClosed) {
            failures.add("a worker created and joined inside the window was not caught; the row " +
                "would under-report its CPU")
        }
    }

    /**
     * A persistent worker present in the end id list whose CPU read fails (it terminated between
     * enumeration and the read) must make measure() fail closed, not count as present with no CPU.
     * The read failure is forced through the contract seam because the real race window cannot be
     * hit reliably.
     */
    private fun checkEndSnapshotReadRaceFailsClosed(failures: MutableList<String>) {
        val go = java.util.concurrent.SynchronousQueue<Unit>()
        val done = java.util.concurrent.SynchronousQueue<Long>()
        val worker = Thread {
            while (true) {
                go.take()
                val until = System.nanoTime() + CPU_ACCOUNTING_WORKER_NANOS
                var sink = 0L
                while (System.nanoTime() < until) sink += sink xor System.nanoTime()
                if (sink == Long.MIN_VALUE) println(sink)
                done.put(0L)
            }
        }.apply { isDaemon = true; start() }
        val failedClosed = runCatching {
            RequestCpuAccounting.measure({ go.put(Unit); done.take() }, contractUnreadableThreadId = worker.id)
        }.isFailure
        println("cpu-accounting-contract: end-snapshot read race failed closed = $failedClosed")
        if (!failedClosed) {
            failures.add("a worker whose end-of-window CPU read failed was not caught; the row " +
                "would under-report its CPU")
        }
    }

    /**
     * A Java thread present at the window start that exits inside the window is in the before
     * snapshot but not the after snapshot; the accounting must fail closed rather than drop the CPU
     * it accrued before exiting, and the failure must name the vanished thread so the offending
     * lifecycle can be identified (a JDK `Keep-Alive-Timer` from the harness's HttpURLConnection
     * client was the one that tripped it on the longer method scenarios). The repair keeps this
     * tripwire and removes that non-request thread at its source (`http.keepAlive=false`) rather
     * than weakening the guarantee.
     */
    private fun checkPresentThenGoneFailsClosed(failures: MutableList<String>) {
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            runCatching { release.await() }
        }.apply { isDaemon = true; name = PRESENT_THEN_GONE_WORKER_NAME; start() }
        // The worker is parked and present in the before snapshot; releasing and joining it inside
        // the window makes it exit before the end snapshot is taken.
        val outcome = runCatching {
            RequestCpuAccounting.measure {
                release.countDown()
                worker.join()
                0L
            }
        }
        val message = outcome.exceptionOrNull()?.message ?: ""
        val failedClosed = outcome.isFailure
        val named = message.contains(PRESENT_THEN_GONE_WORKER_NAME)
        println("cpu-accounting-contract: present-then-gone worker failed closed = $failedClosed, " +
            "named = $named")
        if (!failedClosed) {
            failures.add("a thread present at the window start that exited inside it was not caught; " +
                "the row would drop its pre-exit CPU")
        } else if (!named) {
            failures.add("the vanished-thread failure did not name the offending thread: $message")
        }
    }

    /**
     * The request-serving Java sum is per-thread on-CPU time over an interval the process figure
     * contains, so it can exceed the process figure only by snapshot overhead, whatever the JVM's
     * own threads did around the action. A larger overshoot means the intervals are not nested.
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
}

private const val CPU_ACCOUNTING_WORKER_NANOS = 200_000_000L

/** A distinctive name the present-then-gone contract asserts appears in the vanished-thread failure. */
private const val PRESENT_THEN_GONE_WORKER_NAME = "cpu-accounting-present-then-gone"

private const val CPU_ACCOUNTING_GARBAGE_CHUNKS = 512
private const val CPU_ACCOUNTING_GARBAGE_CHUNK_BYTES = 1 shl 20
private const val CPU_ACCOUNTING_GARBAGE_RETAINED = 64

/** Two ticks of the process CPU clock (10 ms on Linux), the only slack the nesting leaves. */
private const val CPU_ACCOUNTING_MAX_OVERSHOOT_NANOS = 20_000_000L
private const val CPU_ACCOUNTING_MIN_SHARE_PERCENT = 90L
private const val PERCENT = 100L

/** A HotSpot native thread name, truncated to 15 bytes, that an application worker can collide with. */
private const val COLLIDING_INTERNAL_THREAD_NAME = "Service Thread"
