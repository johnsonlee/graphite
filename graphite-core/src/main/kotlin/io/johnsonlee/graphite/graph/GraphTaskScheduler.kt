package io.johnsonlee.graphite.graph

import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

enum class GraphTaskRole { REQUEST, GRAPH_SOURCE, STORAGE }

/** One fixed execution resource. Waiting owners may execute only tasks in their awaited group. */
class GraphTaskScheduler internal constructor(val parallelism: Int) : AutoCloseable {
    internal val monitor = Object()
    private val pending = ArrayDeque<GraphTask<*>>()
    private val workers = mutableListOf<Thread>()
    private var closed = false
    private val lanes = mutableMapOf<String, Pair<Int, Int>>()
    private val number = schedulerNumbers.incrementAndGet()
    val threadNamePrefix: String get() = "graphite-task-$number-"

    init {
        require(parallelism > 0)
    }

    @Suppress("LongParameterList")
    fun <T> newGroup(
        backgroundParallelism: Int = parallelism,
        sharedLane: String? = null,
        helpWhileWaiting: Boolean = true,
        parentContext: GraphTaskContext? = GraphTaskContext.current,
        role: GraphTaskRole = GraphTaskRole.STORAGE,
        maxConcurrentTasks: Int = Int.MAX_VALUE
    ): GraphTaskGroup<T> {
        require(backgroundParallelism in 0..parallelism)
        require(maxConcurrentTasks > 0)
        return synchronized(monitor) {
            check(!closed) { "Graph scheduler is closed" }
            if (sharedLane != null) {
                val existing = lanes[sharedLane]
                require(existing == null || existing.first == backgroundParallelism)
                lanes.putIfAbsent(sharedLane, backgroundParallelism to 0)
            }
            require(parentContext == null || parentContext.scheduler === this)
            GraphTaskGroup<T>(
                this, backgroundParallelism, sharedLane, helpWhileWaiting, parentContext, role, maxConcurrentTasks
            ).also {
                parentContext?.register(it)
            }
        }
    }

    /**
     * Root admission leaves one background slot for storage. The group limit includes helpers
     * and is released only after the callable and its descendants have actually finished.
     */
    fun <T> newRootGroup(maxConcurrentTasks: Int = parallelism): GraphTaskGroup<T> = newGroup(
        backgroundParallelism = (parallelism - 1).coerceAtLeast(1),
        sharedLane = "graph-root",
        helpWhileWaiting = true,
        role = GraphTaskRole.GRAPH_SOURCE,
        maxConcurrentTasks = maxConcurrentTasks
    )

    fun <T> newRequestGroup(): GraphTaskGroup<T> = newGroup(
        backgroundParallelism = (parallelism - 1).coerceAtLeast(1),
        sharedLane = "graph-root",
        helpWhileWaiting = false,
        parentContext = null,
        role = GraphTaskRole.REQUEST
    )

    internal fun canHelp(): Boolean = workerScheduler.get() === this

    internal fun laneAvailable(group: GraphTaskGroup<*>): Boolean = group.sharedLane?.let {
        val lane = checkNotNull(lanes[it])
        lane.second < lane.first
    } ?: true

    internal fun changeLane(group: GraphTaskGroup<*>, delta: Int) {
        group.sharedLane?.let {
            val lane = checkNotNull(lanes[it])
            lanes[it] = lane.first to lane.second + delta
        }
    }

    internal fun enqueue(task: GraphTask<*>) = synchronized(monitor) {
        check(!closed) { "Graph scheduler is closed" }
        pending.addLast(task)
        if (workers.isEmpty()) {
            repeat(parallelism) { index ->
                Thread({ work() }, "graphite-task-$number-${index + 1}").apply {
                    isDaemon = true
                    workers.add(this)
                    start()
                }
            }
        }
        monitor.notifyAll()
    }

    internal fun discardQueued(task: GraphTask<*>) = synchronized(monitor) {
        pending.remove(task)
    }

    private fun work() {
        workerScheduler.set(this)
        while (true) {
            val task = synchronized(monitor) {
                var selected: GraphTask<*>? = null
                while (selected == null) {
                    if (closed && pending.isEmpty()) return
                    val iterator = pending.iterator()
                    while (iterator.hasNext()) {
                        val next = iterator.next()
                        if (next.finished || next.running) {
                            iterator.remove()
                        } else if (next.group.canRunInBackground()) {
                            iterator.remove()
                            next.claim(background = true)
                            selected = next
                            break
                        }
                    }
                    if (selected == null) {
                        if (closed && pending.isEmpty()) return
                        try {
                            monitor.wait()
                        } catch (_: InterruptedException) {
                            // No task owns this worker while it waits for its next lease.
                        }
                    }
                }
                checkNotNull(selected)
            }
            task.execute()
            // A completed lease cannot interrupt this thread after execute releases the monitor.
            Thread.interrupted()
        }
    }

    override fun close() {
        val threads = synchronized(monitor) {
            closed = true
            pending.toList().forEach { it.cancel(false) }
            monitor.notifyAll()
            workers.toList()
        }
        var interrupted = false
        threads.forEach { thread ->
            check(thread !== Thread.currentThread()) { "Cannot close scheduler from its worker" }
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        private val schedulerNumbers = AtomicInteger()
        private val workerScheduler = ThreadLocal<GraphTaskScheduler>()
        val shared: GraphTaskScheduler by lazy { GraphTaskScheduler(Runtime.getRuntime().availableProcessors()) }
    }
}

/** Cancellation is task-local; a helper never clears or synthesizes its owner's interruption. */
class GraphTaskContext internal constructor(private val task: GraphTask<*>) {
    internal val scheduler: GraphTaskScheduler get() = task.group.scheduler
    internal fun register(group: GraphTaskGroup<*>) {
        check(task.running && task.acceptingChildren) { "Graph task context is not running" }
        task.children.add(group)
        if (isCancelled) group.cancelFromParent(true)
    }
    internal fun unregister(group: GraphTaskGroup<*>) = synchronized(scheduler.monitor) {
        task.children.remove(group)
    }

    val isCancelled: Boolean get() = task.isCancelled || task.group.parentContext?.isCancelled == true
    val isHelper: Boolean get() = !task.isBackground
    val role: GraphTaskRole get() = task.group.role
    val isAcceptingChildren: Boolean get() = synchronized(scheduler.monitor) {
        task.running && task.acceptingChildren
    }

    /** Closes descendants before an owner publishes its externally visible completion. */
    fun finishChildren(primaryFailure: Throwable? = null): Throwable? = task.finishChildren(primaryFailure)

    fun checkCancelled() {
        if (isCancelled || Thread.currentThread().isInterrupted) throw CancellationException(GRAPH_TASK_CANCELLED)
    }

    companion object {
        internal val executing = ThreadLocal<GraphTaskContext?>()
        val current: GraphTaskContext? get() = executing.get()
    }
}

class GraphTaskGroup<T> @Suppress("LongParameterList") internal constructor(
    internal val scheduler: GraphTaskScheduler,
    internal val backgroundParallelism: Int,
    internal val sharedLane: String?,
    private val helpWhileWaiting: Boolean,
    internal val parentContext: GraphTaskContext?,
    internal val role: GraphTaskRole,
    private val maxConcurrentTasks: Int
) : AutoCloseable {
    internal var backgroundRunning = 0
    // Includes inline/helper execution and descendant cleanup; guarded by scheduler.monitor.
    internal var runningTasks = 0
    internal fun hasTaskSlot(): Boolean = runningTasks < maxConcurrentTasks
    internal fun canRunInBackground(): Boolean =
        hasTaskSlot() && backgroundRunning < backgroundParallelism && scheduler.laneAvailable(this)
    internal val tasks = mutableListOf<GraphTask<T>>()
    private val completed = ArrayDeque<GraphTask<T>>()
    private var closed = false
    private var cancellationRequested = false

    private fun checkOpen() {
        if (cancellationRequested) throw CancellationException("Graph task group cancelled")
        check(!closed) { "Graph task group is closed" }
    }

    internal fun cancelFromParent(mayInterruptIfRunning: Boolean) {
        closed = true
        cancellationRequested = true
        tasks.toList().forEach { it.cancel(mayInterruptIfRunning) }
    }

    @Suppress("TooGenericExceptionCaught")
    fun submit(callable: Callable<T>): GraphTask<T> = synchronized(scheduler.monitor) {
        checkOpen()
        val task = GraphTask(this, callable)
        tasks.add(task)
        try {
            scheduler.enqueue(task)
        } catch (error: Throwable) {
            task.cancel(false)
            throw error
        }
        task
    }

    fun poll(): GraphTask<T>? = synchronized(scheduler.monitor) { completed.pollFirst() }

    @Throws(InterruptedException::class)
    fun awaitNext(): GraphTask<T> {
        while (true) {
            val helper = synchronized(scheduler.monitor) {
                if (Thread.interrupted()) throw InterruptedException()
                completed.pollFirst()?.let { return it }
                check(tasks.any { !it.finished }) { "No outstanding graph tasks" }
                claimHelper().also { if (it == null) scheduler.monitor.wait() }
            }
            helper?.execute()
        }
    }

    @Throws(InterruptedException::class)
    fun awaitAll() {
        while (true) {
            val helper = synchronized(scheduler.monitor) {
                if (Thread.interrupted()) throw InterruptedException()
                if (tasks.all { it.finished }) return
                claimHelper().also { if (it == null) scheduler.monitor.wait() }
            }
            helper?.execute()
        }
    }

    internal fun claimHelper(): GraphTask<T>? {
        val roleAllowsHelp = role != GraphTaskRole.REQUEST &&
            (role != GraphTaskRole.GRAPH_SOURCE || GraphTaskContext.current?.role == GraphTaskRole.REQUEST)
        val helpAllowed = helpWhileWaiting && scheduler.canHelp() && roleAllowsHelp
        if (!helpAllowed || !hasTaskSlot()) return null
        return tasks.firstOrNull { !it.running && !it.finished }?.also { it.claim(background = false) }
    }

    /** Executes at most one queued task from this group without waiting for a completion. */
    fun helpOne(): Boolean {
        val task = synchronized(scheduler.monitor) { claimHelper() } ?: return false
        task.execute()
        return true
    }

    /** Runs synchronously; a full explicitly bounded group fails instead of blocking its caller. */
    fun runInline(callable: Callable<T>): T {
        val task = synchronized(scheduler.monitor) {
            checkOpen()
            check(hasTaskSlot()) { "Graph task group concurrency limit reached" }
            GraphTask(this, callable, publishCompletion = false).also {
                tasks.add(it)
                it.claim(background = false)
            }
        }
        task.execute()
        return task.inlineResult()
    }

    internal fun completed(task: GraphTask<T>) {
        completed.addLast(task)
        scheduler.monitor.notifyAll()
    }

    fun cancelAndJoin(mayInterruptIfRunning: Boolean = true) {
        synchronized(scheduler.monitor) {
            cancelFromParent(mayInterruptIfRunning)
        }
        var interrupted = Thread.interrupted()
        while (true) {
            try {
                awaitAll()
                break
            } catch (_: InterruptedException) {
                interrupted = true
                Thread.interrupted()
            }
        }
        parentContext?.unregister(this)
        if (interrupted) Thread.currentThread().interrupt()
    }

    internal fun closeFromOwner(failed: Boolean) {
        synchronized(scheduler.monitor) {
            if (!closed) {
                if (failed) cancelFromParent(true) else closed = true
            }
        }
        var interrupted = Thread.interrupted()
        while (true) {
            try {
                awaitAll()
                break
            } catch (_: InterruptedException) {
                interrupted = true
                synchronized(scheduler.monitor) {
                    if (!cancellationRequested) cancelFromParent(true)
                }
            }
        }
        parentContext?.unregister(this)
        if (interrupted) Thread.currentThread().interrupt()
    }

    override fun close() {
        synchronized(scheduler.monitor) { closed = true }
        try {
            awaitAll()
        } catch (_: InterruptedException) {
            cancelAndJoin()
            Thread.currentThread().interrupt()
        }
        parentContext?.unregister(this)
    }
}

class GraphTask<T> internal constructor(
    internal val group: GraphTaskGroup<T>,
    private val callable: Callable<T>,
    private val publishCompletion: Boolean = true
) : Future<T> {
    internal var running = false
    internal var finished = false
    @Volatile private var cancelled = false
    internal val children = mutableListOf<GraphTaskGroup<*>>()
    internal var acceptingChildren = true
    internal var isBackground = false
        private set
    private var runner: Thread? = null
    private var value: T? = null
    private var failure: Throwable? = null
    val context = GraphTaskContext(this)

    internal fun claim(background: Boolean) {
        check(!running && !finished)
        check(group.hasTaskSlot()) { "Graph task group concurrency limit reached" }
        group.runningTasks++
        running = true
        this.isBackground = background
        if (!background) group.scheduler.discardQueued(this)
        runner = Thread.currentThread()
        if (background) {
            group.backgroundRunning++
            group.scheduler.changeLane(group, 1)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun execute() {
        val previous = GraphTaskContext.executing.get()
        GraphTaskContext.executing.set(context)
        try {
            context.checkCancelled()
            value = callable.call()
        } catch (error: Throwable) {
            failure = error
        } finally {
            failure = finishChildren(failure)
            GraphTaskContext.executing.set(previous)
            synchronized(group.scheduler.monitor) {
                runner = null
                children.clear()
                if (isBackground) {
                    group.backgroundRunning--
                    group.scheduler.changeLane(group, -1)
                }
                group.runningTasks--
                running = false
                finished = true
                if (publishCompletion) group.completed(this)
                group.scheduler.monitor.notifyAll()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun finishChildren(primaryFailure: Throwable?): Throwable? {
        check(GraphTaskContext.current === context) { "Only the executing owner may finish its children" }
        val registeredChildren = synchronized(group.scheduler.monitor) {
            acceptingChildren = false
            children.toList()
        }
        var outcome = primaryFailure
        registeredChildren.forEach { child ->
            try {
                child.closeFromOwner(outcome != null)
            } catch (cleanupFailure: Throwable) {
                if (outcome == null) {
                    outcome = cleanupFailure
                } else if (outcome !== cleanupFailure) {
                    outcome?.addSuppressed(cleanupFailure)
                }
            }
        }
        synchronized(group.scheduler.monitor) { children.clear() }
        return outcome
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean = synchronized(group.scheduler.monitor) {
        if (finished || cancelled) return false
        cancelled = true
        children.toList().forEach { it.cancelFromParent(mayInterruptIfRunning) }
        if (!running) {
            group.scheduler.discardQueued(this)
            acceptingChildren = false
            finished = true
            if (publishCompletion) group.completed(this)
            group.scheduler.monitor.notifyAll()
        } else if (mayInterruptIfRunning && isBackground) {
            runner?.interrupt()
        }
        true
    }

    override fun isCancelled(): Boolean = cancelled
    override fun isDone(): Boolean = synchronized(group.scheduler.monitor) { finished }

    @Throws(InterruptedException::class, ExecutionException::class)
    override fun get(): T {
        while (true) {
            val helper = synchronized(group.scheduler.monitor) {
                if (finished) return result()
                if (Thread.interrupted()) throw InterruptedException()
                group.claimHelper().also { if (it == null) group.scheduler.monitor.wait() }
            }
            helper?.execute()
        }
    }

    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    override fun get(timeout: Long, unit: TimeUnit): T = synchronized(group.scheduler.monitor) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (!finished) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw TimeoutException()
            TimeUnit.NANOSECONDS.timedWait(group.scheduler.monitor, remaining)
        }
        result()
    }

    internal fun inlineResult(): T {
        if (cancelled) throw CancellationException(GRAPH_TASK_CANCELLED)
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun result(): T {
        if (cancelled) throw CancellationException(GRAPH_TASK_CANCELLED)
        failure?.let { throw ExecutionException(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

private const val GRAPH_TASK_CANCELLED = "Graph task cancelled"
