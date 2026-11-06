package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import java.util.Collections

/** Cache admission and ownership accounting. All entrypoints run under the owning index's monitor. */
internal object CallSiteIndexCacheWriter {
    fun cacheProjectedRows(
        index: MappedCallSiteStringIndex,
        key: CallSiteProjectionKey,
        rows: List<StringPropertyProjectionRow>
    ): List<StringPropertyProjectionRow> = index.projectedRows[key]?.rows ?: freezeRows(rows).also { retained ->
        admitProjectionRows(index, key, retained)
    }

    fun cacheMatchingNodeIds(index: MappedCallSiteStringIndex, key: CallSiteNodeMatchKey, nodeIds: IntArray) {
        if (!index.matchingNodeIds.containsKey(key)) admitNodeIds(index, key, nodeIds)
    }

    fun cacheMatchingStringIds(index: MappedCallSiteStringIndex, key: CallSitePredicateKey, stringIds: IntArray) {
        if (!index.matchingStringIds.containsKey(key)) admitStringIds(index, key, stringIds)
    }

    private fun freezeRows(rows: List<StringPropertyProjectionRow>): List<StringPropertyProjectionRow> =
        Collections.unmodifiableList(rows.map { row ->
            StringPropertyProjectionRow(Collections.unmodifiableList(row.values.toList()))
        })

    private fun admitProjectionRows(index: MappedCallSiteStringIndex, key: CallSiteProjectionKey, rows: List<StringPropertyProjectionRow>) {
        val entryBytes = estimatedCallSiteProjectionCacheBytes(key, rows)
        if (entryBytes > MAX_CALL_SITE_PROJECTION_CACHE_BYTES) return
        while (index.projectedRows.isNotEmpty() &&
            (index.projectedRows.size >= MAX_CALL_SITE_PROJECTION_CACHE_ENTRIES ||
                index.projectedRowCacheBytes > MAX_CALL_SITE_PROJECTION_CACHE_BYTES - entryBytes)
        ) {
            val eldest = index.projectedRows.entries.iterator().next()
            index.projectedRows.remove(eldest.key)
            index.projectedRowCacheBytes -= eldest.value.retainedBytes
            index.reservation.shrinkTo(index.reservation.bytes - eldest.value.retainedBytes)
        }
        if (growReservation(index, entryBytes)) {
            index.projectedRows[key] = CachedProjectionRows(rows, entryBytes)
            index.projectedRowCacheBytes += entryBytes
        }
    }

    private fun admitNodeIds(index: MappedCallSiteStringIndex, key: CallSiteNodeMatchKey, nodeIds: IntArray) {
        val entryBytes = estimatedCallSiteNodeMatchCacheBytes(key, nodeIds)
        if (entryBytes > MAX_CALL_SITE_NODE_MATCH_CACHE_BYTES) return
        while (index.matchingNodeIds.isNotEmpty() &&
            (index.matchingNodeIds.size >= MAX_CALL_SITE_NODE_MATCH_CACHE_ENTRIES ||
                index.matchingNodeCacheBytes > MAX_CALL_SITE_NODE_MATCH_CACHE_BYTES - entryBytes)
        ) {
            val eldest = index.matchingNodeIds.entries.iterator().next()
            index.matchingNodeIds.remove(eldest.key)
            index.matchingNodeCacheBytes -= eldest.value.retainedBytes
            index.reservation.shrinkTo(index.reservation.bytes - eldest.value.retainedBytes)
        }
        if (growReservation(index, entryBytes)) {
            index.matchingNodeIds[key] = CachedMatchingNodeIds(nodeIds, entryBytes)
            index.matchingNodeCacheBytes += entryBytes
        }
    }

    private fun admitStringIds(index: MappedCallSiteStringIndex, key: CallSitePredicateKey, stringIds: IntArray) {
        val entryBytes = estimatedCallSiteStringMatchCacheBytes(key, stringIds)
        if (entryBytes > MAX_CALL_SITE_STRING_MATCH_CACHE_BYTES) return
        while (index.matchingStringIds.isNotEmpty() &&
            (index.matchingStringIds.size >= MAX_CALL_SITE_STRING_MATCH_CACHE_ENTRIES ||
                index.matchingStringCacheBytes > MAX_CALL_SITE_STRING_MATCH_CACHE_BYTES - entryBytes)
        ) {
            val eldest = index.matchingStringIds.entries.iterator().next()
            index.matchingStringIds.remove(eldest.key)
            index.matchingStringCacheBytes -= eldest.value.retainedBytes
            index.reservation.shrinkTo(index.reservation.bytes - eldest.value.retainedBytes)
        }
        if (growReservation(index, entryBytes)) {
            index.matchingStringIds[key] = CachedMatchingStringIds(stringIds, entryBytes)
            index.matchingStringCacheBytes += entryBytes
        }
    }

    private fun growReservation(index: MappedCallSiteStringIndex, entryBytes: Long): Boolean {
        val required = try {
            Math.addExact(index.reservation.bytes, entryBytes)
        } catch (_: ArithmeticException) { null }
        return required != null && index.reservation.tryGrowTo(required)
    }
}
