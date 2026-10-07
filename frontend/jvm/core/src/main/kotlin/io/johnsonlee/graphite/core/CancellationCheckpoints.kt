package io.johnsonlee.graphite.core

import java.util.concurrent.CancellationException

/** Checks interruption without clearing the flag or creating an exception on the normal path. */
inline fun checkThreadInterrupted(exception: () -> CancellationException) {
    if (Thread.currentThread().isInterrupted) throw exception()
}
