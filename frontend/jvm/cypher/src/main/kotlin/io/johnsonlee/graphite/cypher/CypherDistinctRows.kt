package io.johnsonlee.graphite.cypher

/** Bounded distinct admission merges complete graph provenance for every selected value. */
internal object CypherDistinctRows {
    @Suppress("UNCHECKED_CAST")
    fun addDistinctRow(
        rows: LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>,
        row: Map<String, Any?>,
        limit: Int
    ) {
        val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
        val existing = rows[visible]
        if (existing != null) {
            val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
            if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
        } else if (rows.size < limit) {
            rows[visible] = row.toMutableMap()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun addCypherDistinctRow(
        rows: LinkedHashMap<Any, MutableMap<String, Any?>>,
        row: Map<String, Any?>,
        limit: Int
    ) {
        val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
        val key = cypherValueKey(visible)
        val existing = rows[key]
        if (existing != null) {
            val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
            if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
        } else if (rows.size < limit) {
            rows[key] = row.toMutableMap()
        }
    }

    fun mergeDirectStringBatch(
        target: LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>,
        rows: List<Map<String, Any?>>,
        limit: Int
    ) {
        rows.forEach { row -> addDistinctRow(target, row, limit) }
    }
}
