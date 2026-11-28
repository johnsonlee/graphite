package io.johnsonlee.graphite.webgraph

import java.lang.ref.Cleaner
import java.nio.file.Files
import java.nio.file.Path

/**
 * The file mappings of closed graphs that the collector has not released yet, held to a budget.
 *
 * A mapped buffer is unmapped when the collector finds it unreachable. The JDK this project targets has no supported way to unmap one,
 * and unmapping it by hand behind a reader that still holds it would read freed memory, so closing a graph cannot release its
 * mappings: they stay until a collection reaches the generation the buffers were promoted to. A process that opens and closes graphs
 * while allocating little runs young collections only, and every graph it has closed stays mapped: the 64-graph fixture build held
 * 0.83 GiB of them in its read-back phase, on top of the heap (Attempt327).
 *
 * So a closed graph is counted here until the collector releases it, and once more than [budgetBytes] of closed graphs are still
 * counted the collector is asked to run, as `java.nio` asks it when direct buffers exceed their limit. A process whose own collections
 * keep up never asks; one that keeps closed graphs reachable asks once per [budgetBytes] it closes, never once per close. Under
 * `-XX:+DisableExplicitGC` the request does nothing and mappings are released as they were before.
 */
internal class ClosedGraphMappings(private val budgetBytes: Long, private val collect: () -> Unit) {
    private val cleaner: Cleaner by lazy { Cleaner.create() }
    private var unreleased = 0L
    private var closedSinceRequest = 0L

    /** Counts [bytes] of mappings of a closed graph until [owner], a buffer that maps one of its files, is collected. */
    fun closed(owner: Any, bytes: Long) {
        if (bytes <= 0L) return
        cleaner.register(owner, Released(this, bytes))
        if (overBudget(bytes)) collect()
    }

    /** The mapped bytes of closed graphs the collector has not released. */
    @Synchronized fun unreleasedBytes(): Long = unreleased

    @Synchronized
    private fun overBudget(bytes: Long): Boolean {
        unreleased += bytes
        closedSinceRequest += bytes
        if (unreleased <= budgetBytes || closedSinceRequest <= budgetBytes) return false
        closedSinceRequest = 0L
        return true
    }

    @Synchronized
    private fun released(bytes: Long) {
        unreleased -= bytes
    }

    /** Runs when the collector releases a closed graph; holds no reference to it. */
    internal class Released(private val mappings: ClosedGraphMappings, private val bytes: Long) : Runnable {
        override fun run() = mappings.released(bytes)
    }

    companion object {
        /** A quarter of a gibibyte: several large graphs, and a small share of any heap that maps them. */
        internal const val PROCESS_BUDGET_BYTES = 256L shl 20

        /** The mappings of every graph this process closes. */
        val process = ClosedGraphMappings(PROCESS_BUDGET_BYTES, System::gc)

        /** The size of the files in [dir], the bound of what a graph loaded from it maps. */
        fun mappableBytes(dir: Path): Long =
            Files.list(dir).use { files -> files.filter(Files::isRegularFile).mapToLong(Files::size).sum() }
    }
}
