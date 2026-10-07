package io.johnsonlee.graphite.cypher

/** Checks the interrupt flag without clearing it; preserves query cancellation exceptions. */
@Suppress("NOTHING_TO_INLINE") // Keep hot-loop checkpoints allocation-free and at their call sites.
internal inline fun checkQueryThreadInterrupted(message: String? = null) {
    if (Thread.currentThread().isInterrupted) {
        throw if (message == null) CypherQueryCancelledException() else CypherQueryCancelledException(message)
    }
}
