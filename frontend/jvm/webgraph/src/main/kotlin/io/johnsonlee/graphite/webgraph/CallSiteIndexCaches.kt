package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import io.johnsonlee.graphite.graph.StringValueTransform

internal data class CallSitePredicateKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

internal data class CachedMatchingStringIds(
    val stringIds: IntArray,
    val retainedBytes: Long
)

internal data class CallSiteNodeMatchKey(
    val predicates: List<StringPropertyPredicate>,
    val limit: Int
)

internal data class CachedMatchingNodeIds(
    val nodeIds: IntArray,
    val retainedBytes: Long
)

internal data class CallSiteProjectionKey(
    val predicates: List<StringPropertyPredicate>,
    val projectedProperties: List<String>,
    val limit: Int
)

internal data class CachedProjectionRows(
    val rows: List<StringPropertyProjectionRow>,
    val retainedBytes: Long
)

internal fun estimatedCallSiteStringMatchCacheBytes(
    key: CallSitePredicateKey,
    stringIds: IntArray
): Long = try {
    Math.addExact(
        CALL_SITE_STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES +
            CALL_SITE_STRING_MATCH_CACHE_STRING_HEADER_ESTIMATED_BYTES +
            PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.addExact(
            Math.multiplyExact(key.expected.length.toLong(), Char.SIZE_BYTES.toLong()),
            Math.multiplyExact(stringIds.size.toLong(), Int.SIZE_BYTES.toLong())
        )
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

internal fun estimatedCallSiteNodeMatchCacheBytes(
    key: CallSiteNodeMatchKey,
    nodeIds: IntArray
): Long = try {
    val keyCharacters = key.predicates.sumOf { predicate ->
        predicate.property.length.toLong() + predicate.expected.length
    }
    Math.addExact(
        CALL_SITE_NODE_MATCH_CACHE_ENTRY_ESTIMATED_BYTES + PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.addExact(
            Math.multiplyExact(keyCharacters, Char.SIZE_BYTES.toLong()),
            Math.multiplyExact(nodeIds.size.toLong(), Int.SIZE_BYTES.toLong())
        )
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

internal fun estimatedCallSiteProjectionCacheBytes(
    key: CallSiteProjectionKey,
    rows: List<StringPropertyProjectionRow>
): Long = try {
    val keyCharacters = key.predicates.sumOf { predicate ->
        predicate.property.length.toLong() + predicate.expected.length
    } + key.projectedProperties.sumOf { property -> property.length.toLong() }
    val valueReferences = rows.sumOf { row -> row.values.size.toLong() }
    var decodedStringBytes = 0L
    for (row in rows) decodedStringBytes = Math.addExact(decodedStringBytes, decodedProjectionRowBytes(row))
    Math.addExact(
        CALL_SITE_PROJECTION_CACHE_ENTRY_ESTIMATED_BYTES,
        Math.addExact(
            Math.multiplyExact(keyCharacters, Char.SIZE_BYTES.toLong()),
            Math.addExact(
                Math.multiplyExact(rows.size.toLong(), CALL_SITE_PROJECTION_ROW_ESTIMATED_BYTES),
                Math.addExact(
                    Math.multiplyExact(valueReferences, REFERENCE_ESTIMATED_BYTES),
                    decodedStringBytes
                )
            )
        )
    )
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

private fun decodedProjectionRowBytes(row: StringPropertyProjectionRow): Long {
    var bytes = 0L
    for (value in row.values) {
        if (value != null) bytes = Math.addExact(bytes, Math.addExact(
            CALL_SITE_PROJECTION_STRING_ESTIMATED_BYTES + PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
            Math.multiplyExact(value.length.toLong(), Char.SIZE_BYTES.toLong())
        ))
    }
    return bytes
}
