package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchComparison
import io.johnsonlee.graphite.core.MethodDescriptor

/** Optional persistence snapshots that avoid materializing query-only branch metadata. */
interface PackedBranchMetadataSource {
    /**
     * Returns scopes in the same grouped encounter order as [Graph.branchScopes], or null when
     * persistence must consult the public query view. Arrays belong to the consumer: modifying them
     * must not alter the graph. Side node ids retain the public set's deduplication and iteration order.
     */
    fun packedBranchScopes(): Sequence<PackedBranchScope>?

    /** Returns owned packed definition arrays, or null when the public view must be consulted. */
    fun packedLocalDefinitions(): Map<Int, IntArray>?
}

/** A persistence snapshot, with the same packed definition triples used by [FullGraphBuilder]. */
class PackedBranchScope(
    val conditionNodeId: Int,
    val method: MethodDescriptor,
    val comparison: BranchComparison,
    val trueBranchNodeIds: IntArray,
    val falseBranchNodeIds: IntArray,
    val trueDefinitions: IntArray,
    val falseDefinitions: IntArray
)
