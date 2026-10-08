package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.BranchScope
import it.unimi.dsi.fastutil.ints.IntOpenHashSet

/** Mirrors public-view grouping and validation without unpacking definition triples. */
internal fun Iterable<DefaultGraph.RawBranchScope>.packedBranchScopeSnapshot(): Sequence<PackedBranchScope> =
    groupBy { scope ->
        validatePackedDefinitions(scope.trueDefinitions)
        validatePackedDefinitions(scope.falseDefinitions)
        scope.conditionNodeId
    }.values.asSequence().flatMap { group ->
        group.asSequence().map { scope ->
            PackedBranchScope(
                scope.conditionNodeId, scope.method, scope.comparison,
                IntOpenHashSet(scope.trueBranchNodeIds).toIntArray(),
                IntOpenHashSet(scope.falseBranchNodeIds).toIntArray(),
                copyPackedDefinitions(scope.trueDefinitions), copyPackedDefinitions(scope.falseDefinitions)
            )
        }
    }

internal fun Map<Int, IntArray>.packedLocalDefinitionSnapshot(): Map<Int, IntArray> =
    mapValues { (_, definitions) -> copyPackedDefinitions(definitions) }

private fun copyPackedDefinitions(definitions: IntArray): IntArray {
    validatePackedDefinitions(definitions)
    return if (definitions.isEmpty()) BranchScope.EMPTY_DEFINITIONS else definitions.copyOf()
}

private fun validatePackedDefinitions(definitions: IntArray) {
    require(definitions.size % BranchScope.DEFINITION_STRIDE == 0) {
        "Packed definitions length ${definitions.size} is not a multiple of ${BranchScope.DEFINITION_STRIDE}"
    }
}
