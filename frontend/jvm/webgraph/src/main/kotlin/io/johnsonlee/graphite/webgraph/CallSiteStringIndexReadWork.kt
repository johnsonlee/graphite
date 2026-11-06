package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer

internal class MappedCallSiteStringIndexPersistenceBudgetDeniedException : Exception()

@Suppress("TooGenericExceptionCaught")
internal class PersistentIndexReadWork(workConsumer: GraphWorkConsumer?) {
    internal val accounting = BufferedGraphWorkConsumer(workConsumer)
    private var inspected = 0

    fun consume() {
        if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
            checkCallSiteIndexInterrupted()
        }
        try {
            accounting.consume()
        } catch (error: Throwable) {
            throw MappedCallSiteStringIndexReadAbortedException(error)
        }
    }

    fun flush() {
        try {
            accounting.flush()
        } catch (error: Throwable) {
            throw MappedCallSiteStringIndexReadAbortedException(error)
        }
    }
}

internal class MappedCallSiteStringIndexReadAbortedException(cause: Throwable) : RuntimeException(cause)
