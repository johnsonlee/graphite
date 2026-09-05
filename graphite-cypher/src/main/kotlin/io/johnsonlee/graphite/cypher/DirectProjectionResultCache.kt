package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import java.util.Collections

/**
 * Reuses immutable public rows for bounded storage projections.
 *
 * The storage projection list is an identity token for one cache generation. When the mapped
 * graph releases its string indexes, a later projection returns a different list and cannot hit
 * stale rows here. The global LRU is additionally bounded by both entries and a heap-derived byte
 * budget so short-lived query executors cannot multiply retained result caches.
 */
internal object DirectProjectionResultCache {

    private class Key(
        val projectedRows: List<StringPropertyProjectionRow>,
        val columns: List<String>,
        val graphId: String
    ) {
        private val hash = (System.identityHashCode(projectedRows) * HASH_FACTOR + columns.hashCode()) *
            HASH_FACTOR + graphId.hashCode()

        override fun equals(other: Any?): Boolean = other is Key &&
            projectedRows === other.projectedRows && columns == other.columns && graphId == other.graphId

        override fun hashCode(): Int = hash
    }

    private data class Entry(
        val result: CypherResult,
        val retainedBytes: Long
    )

    private val results = LinkedHashMap<Key, Entry>(
        MAX_DIRECT_PROJECTION_CACHE_ENTRIES + 1,
        DIRECT_PROJECTION_CACHE_LOAD_FACTOR,
        true
    )
    private var retainedBytes = 0L

    @Suppress("ReturnCount")
    fun getOrCreate(
        projectedRows: List<StringPropertyProjectionRow>,
        columns: List<String>,
        graphId: String
    ): CypherResult {
        val lookupKey = Key(projectedRows, columns, graphId)
        synchronized(results) {
            results[lookupKey]?.let { return it.result }
        }

        val result = immutableResult(projectedRows, columns, graphId)
        val entryBytes = estimatedRetainedBytes(projectedRows, columns, graphId)
        if (entryBytes > maxRetainedBytes) return result

        synchronized(results) {
            results[lookupKey]?.let { return it.result }
            while (results.isNotEmpty() &&
                (results.size >= MAX_DIRECT_PROJECTION_CACHE_ENTRIES ||
                    retainedBytes > maxRetainedBytes - entryBytes)
            ) {
                val eldest = results.entries.iterator().next()
                results.remove(eldest.key)
                retainedBytes -= eldest.value.retainedBytes
            }
            val retainedColumns = result.columns
            results[Key(projectedRows, retainedColumns, graphId)] = Entry(result, entryBytes)
            retainedBytes += entryBytes
        }
        return result
    }

    /**
     * Builds the final immutable public result without retaining it in the cross-query LRU.
     *
     * Cold raw-leading projections are request-local, so their storage row list is not a stable
     * cache-generation token. They can still skip the generic second materialization pass by
     * returning the same public row representation used by cached mapped projections.
     */
    fun createUncached(
        projectedRows: List<StringPropertyProjectionRow>,
        columns: List<String>,
        graphId: String
    ): CypherResult = immutableResult(projectedRows, columns, graphId)

    internal fun clear() = synchronized(results) {
        results.clear()
        retainedBytes = 0L
    }

    internal fun entryCount(): Int = synchronized(results) { results.size }

    internal fun retainedBytes(): Long = synchronized(results) { retainedBytes }

    internal fun maxRetainedBytes(): Long = maxRetainedBytes

    private fun immutableResult(
        projectedRows: List<StringPropertyProjectionRow>,
        columns: List<String>,
        graphId: String
    ): CypherResult {
        val immutableColumns = Collections.unmodifiableList(columns.toList())
        val graphIds = Collections.singleton(graphId)
        val metadata = Collections.singletonMap<String, Any?>(
            RESULT_GRAPH_IDS_KEY,
            Collections.singletonList(graphId)
        )
        val rows = projectedRows.map { projected ->
            check(projected.values.size == immutableColumns.size) {
                "Storage projection width ${projected.values.size} does not match ${immutableColumns.size} columns"
            }
            val values = LinkedHashMap<String, Any?>(immutableColumns.size * 2 + 1)
            immutableColumns.forEachIndexed { index, column -> values[column] = projected.values[index] }
            values[RESULT_METADATA_KEY] = metadata
            DirectProjectionCypherRow(Collections.unmodifiableMap(values), graphIds)
        }
        return CypherResult(immutableColumns, Collections.unmodifiableList(rows))
    }

    private val maxRetainedBytes: Long by lazy {
        System.getProperty(DIRECT_PROJECTION_CACHE_BUDGET_PROPERTY)
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: minOf(
                MAX_DIRECT_PROJECTION_CACHE_BYTES,
                Runtime.getRuntime().maxMemory() / DIRECT_PROJECTION_CACHE_HEAP_DIVISOR
            )
    }

    private fun estimatedRetainedBytes(
        projectedRows: List<StringPropertyProjectionRow>,
        columns: List<String>,
        graphId: String
    ): Long = try {
        val characters = graphId.length.toLong() + columns.sumOf { it.length.toLong() }
        val projectedReferences = projectedRows.sumOf { it.values.size.toLong() }
        val projectedStringBytes = estimatedProjectedStringBytes(projectedRows)
        val publicMapEntries = Math.multiplyExact(
            projectedRows.size.toLong(),
            columns.size.toLong() + 1L
        )
        Math.addExact(
            DIRECT_PROJECTION_CACHE_ENTRY_ESTIMATED_BYTES,
            Math.addExact(
                Math.multiplyExact(characters, Char.SIZE_BYTES.toLong()),
                Math.addExact(
                    Math.multiplyExact(projectedRows.size.toLong(), DIRECT_PROJECTION_ROW_ESTIMATED_BYTES),
                    Math.addExact(
                        Math.multiplyExact(projectedReferences, REFERENCE_ESTIMATED_BYTES),
                        Math.addExact(
                            Math.multiplyExact(publicMapEntries, MAP_ENTRY_ESTIMATED_BYTES),
                            projectedStringBytes
                        )
                    )
                )
            )
        )
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    private fun estimatedProjectedStringBytes(rows: List<StringPropertyProjectionRow>): Long {
        var bytes = 0L
        rows.forEach { row ->
            row.values.filterNotNull().forEach { value ->
                bytes = Math.addExact(
                    bytes,
                    Math.addExact(
                        STRING_OBJECT_ESTIMATED_BYTES + PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
                        Math.multiplyExact(value.length.toLong(), Char.SIZE_BYTES.toLong())
                    )
                )
            }
        }
        return bytes
    }
}

private const val DIRECT_PROJECTION_CACHE_BUDGET_PROPERTY = "graphite.cypher.directProjectionCacheBytes"
private const val MAX_DIRECT_PROJECTION_CACHE_ENTRIES = 32
private const val MAX_DIRECT_PROJECTION_CACHE_BYTES = 2L * 1024 * 1024
private const val DIRECT_PROJECTION_CACHE_HEAP_DIVISOR = 2_048L
private const val DIRECT_PROJECTION_CACHE_LOAD_FACTOR = 0.75f
private const val DIRECT_PROJECTION_CACHE_ENTRY_ESTIMATED_BYTES = 256L
private const val DIRECT_PROJECTION_ROW_ESTIMATED_BYTES = 256L
private const val MAP_ENTRY_ESTIMATED_BYTES = 48L
private const val REFERENCE_ESTIMATED_BYTES = 8L
private const val STRING_OBJECT_ESTIMATED_BYTES = 24L
private const val PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES = 16L
private const val HASH_FACTOR = 31
