package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

/** Reads only possible string references. The caller must evaluate the exact predicate on survivors. */
internal class MappedPropertyTextCandidates(
    private val data: ByteBuffer,
    private val offsets: NodeOffsetIndex,
    private val types: NodeTypeIndex,
    private val strings: StringTable
) {
    fun ids(type: Class<out Node>, fragments: List<String>, work: GraphWorkConsumer?): Sequence<Int> = sequence {
        val matchers = fragments.map { fragment ->
            BoundedStringMatcher(strings, StringPredicateKey(null, StringMatchMode.CONTAINS, fragment))
        }.toTypedArray()
        var inspected = 0
        for (id in types.ids(type)) {
            if ((inspected++ and CANCELLATION_MASK) == 0 && Thread.currentThread().isInterrupted) {
                throw CancellationException("Property text scan interrupted")
            }
            work?.consume()
            val offset = offsets.offset(id).toInt()
            if (matchers.all { matcher -> mightMatch(offset, matcher) }) yield(id)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun mightMatch(offset: Int, matcher: BoundedStringMatcher): Boolean {
        val fields = offset + NODE_HEADER_BYTES
        return when (data.get(offset + Int.SIZE_BYTES).toInt()) {
            NodeSerializer.TAG_STRING_CONSTANT -> matches(fields, matcher)
            NodeSerializer.TAG_LOCAL_VARIABLE, NodeSerializer.TAG_PARAMETER_NODE ->
                matchesRange(fields, methodEnd(fields + 2 * Int.SIZE_BYTES), matcher)
            NodeSerializer.TAG_FIELD_NODE -> matchesRange(fields, fields + FIELD_STRING_BYTES, matcher)
            NodeSerializer.TAG_CALL_SITE_NODE -> matchesRange(fields, methodEnd(methodEnd(fields)), matcher)
            NodeSerializer.TAG_RETURN_NODE -> {
                val end = methodEnd(fields)
                matchesRange(fields, end, matcher) || optionalStringMatches(end, matcher)
            }
            NodeSerializer.TAG_RESOURCE_FILE_NODE ->
                matchesRange(fields, fields + RESOURCE_FILE_STRING_BYTES, matcher) ||
                    optionalStringMatches(fields + RESOURCE_FILE_STRING_BYTES, matcher)
            NodeSerializer.TAG_INT_CONSTANT, NodeSerializer.TAG_LONG_CONSTANT, NodeSerializer.TAG_FLOAT_CONSTANT,
            NodeSerializer.TAG_DOUBLE_CONSTANT, NodeSerializer.TAG_BOOLEAN_CONSTANT, NodeSerializer.TAG_NULL_CONSTANT -> false
            // Heterogeneous values and dynamic annotation accessors need their complete semantics.
            else -> true
        }
    }

    private fun optionalStringMatches(position: Int, matcher: BoundedStringMatcher): Boolean =
        data.get(position).toInt() != 0 && matches(position + 1, matcher)

    private fun methodEnd(position: Int): Int =
        position + (METHOD_FIXED_INTS + data.getInt(position + 2 * Int.SIZE_BYTES)) * Int.SIZE_BYTES

    private fun matchesRange(start: Int, end: Int, matcher: BoundedStringMatcher): Boolean {
        var position = start
        while (position < end) {
            // Counts and indexes inside this range may be mistaken for string IDs. That
            // creates only false positives, removed by the authoritative predicate.
            if (matches(position, matcher)) return true
            position += Int.SIZE_BYTES
        }
        return false
    }

    private fun matches(position: Int, matcher: BoundedStringMatcher): Boolean {
        val stringId = data.getInt(position)
        return stringId >= 0 && stringId < strings.size() && matcher.matches(stringId)
    }

    private companion object {
        const val NODE_HEADER_BYTES = Int.SIZE_BYTES + 1
        const val METHOD_FIXED_INTS = 4
        const val FIELD_STRING_BYTES = 3 * Int.SIZE_BYTES
        const val RESOURCE_FILE_STRING_BYTES = 3 * Int.SIZE_BYTES
        const val CANCELLATION_MASK = 255
    }
}
