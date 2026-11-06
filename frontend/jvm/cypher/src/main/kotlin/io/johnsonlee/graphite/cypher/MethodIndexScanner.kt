package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.methodSlice
import io.johnsonlee.graphite.graph.methods

internal fun scanMethods(
    source: CypherGraph,
    scan: MethodScanPlan,
    limit: Int,
    workTracker: CypherWorkTracker?,
    checkPeerCancelled: (() -> Unit)? = null
): Sequence<MethodDescriptor> {
    var inspected = 0
    val cancellationConsumer =
        if (workTracker != null || checkPeerCancelled != null) {
            MethodMetadataScanConsumer {
                if ((inspected++ and CANCELLATION_POLL_MASK) == 0) {
                    checkPeerCancelled?.invoke()
                    workTracker?.checkCancelled()
                }
            }
        } else null
    if (scan.fullyPushed && limit < Int.MAX_VALUE) {
        val slice =
            if (cancellationConsumer == null) {
                source.graph.methodSlice(scan.pattern, limit)
            } else {
                source.graph.methodSlice(scan.pattern, limit, cancellationConsumer)
            }
        if (slice != null) return slice.asSequence()
    }
    return cancellationConsumer?.let { source.graph.methods(scan.pattern, it) } ?: source.graph.methods(scan.pattern)
}
