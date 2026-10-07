package io.johnsonlee.graphite.webgraph

import java.util.concurrent.CancellationException

/** Checks the interrupt flag without clearing it; keeps each storage operation's diagnostic. */
@Suppress("NOTHING_TO_INLINE") // Keep hot-loop checkpoints allocation-free and at their call sites.
internal inline fun checkThreadInterrupted(message: String) {
    if (Thread.currentThread().isInterrupted) throw CancellationException(message)
}
