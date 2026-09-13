package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.Node
import java.util.function.IntPredicate

/** Optional candidate selection over graph-local IDs without materializing rejected nodes. */
interface NodeIdCandidateLookup {
    /**
     * Visits IDs in the same order as nodes(type), tests each once, and materializes only matches.
     * Charge each tested ID once to workConsumer and propagate cancellation or predicate failures.
     * The predicate must be invoked serially: callers may reuse local predicate state.
     * Returning null requests the caller's ordinary node scan. No output limit is implied.
     */
    fun <T : Node> nodesMatchingId(
        type: Class<T>,
        predicate: IntPredicate,
        workConsumer: GraphWorkConsumer?
    ): Sequence<T>?
}
