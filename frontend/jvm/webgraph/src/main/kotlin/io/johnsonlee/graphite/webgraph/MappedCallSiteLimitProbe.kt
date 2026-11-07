package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import java.nio.IntBuffer

/** Counts posting occurrences only until a limit can be filled, preserving query work accounting. */
internal class MappedCallSiteLimitProbe(
    private val propertyStrings: Array<IntBuffer>,
    private val propertyEnds: Array<IntBuffer>,
    private val callSiteCount: Int,
    private val limit: Int,
    private val workConsumer: GraphWorkConsumer?
) {
    private var occurrences = 0L

    fun canFill(predicates: List<StringPropertyPredicate>, exactMatches: List<IntArray>): Boolean {
        occurrences = 0L
        var index = 0
        var valid = true
        while (index < predicates.size && valid && occurrences < limit) {
            valid = inspectPredicate(predicates[index], exactMatches[index])
            index++
        }
        return valid && occurrences >= limit
    }

    private fun inspectPredicate(predicate: StringPropertyPredicate, strings: IntArray): Boolean {
        val propertyIndex = callSiteStringPropertyIndex(predicate.property)
        return propertyIndex >= 0 && inspectProperty(propertyIndex, strings)
    }

    private fun inspectProperty(propertyIndex: Int, strings: IntArray): Boolean {
        for (stringId in strings) {
            if (!inspectPosting(propertyIndex, stringId)) return false
            if (occurrences >= limit) break
        }
        return true
    }

    private fun inspectPosting(propertyIndex: Int, stringId: Int): Boolean {
        val row = mappedCallSiteStringRow(propertyStrings[propertyIndex], stringId, workConsumer)
        if (row < 0) return true
        val end = propertyEnds[propertyIndex].get(row)
        val start = if (row == 0) 0 else propertyEnds[propertyIndex].get(row - 1)
        val valid = start in 0..end && end <= callSiteCount
        if (valid) occurrences += end - start
        return valid
    }
}
