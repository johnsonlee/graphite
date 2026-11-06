package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node

/** Converts storage-selected values to visible columns while retaining graph provenance. */
internal object StringProjectionRows {
    fun rawProjectionRow(
        values: List<String?>,
        projectedProperties: List<String>,
        columns: List<String>,
        graphId: String
    ): MutableMap<String, Any?> = linkedMapOf<String, Any?>().apply {
        columns.indices.forEach { index ->
            this[columns[index]] = if (projectedProperties[index] == GRAPH_ID_PROPERTY) graphId else values[index]
        }
        put(INTERNAL_PROVENANCE_KEY, setOf(graphId))
    }

    fun storageSelectedValues(
        selectedValues: Set<List<String?>>,
        projectedProperties: List<String>,
        graphId: String
    ): Set<List<String?>> {
        val graphIdIndexes = projectedProperties.indices.filter { index ->
            projectedProperties[index] == GRAPH_ID_PROPERTY
        }
        if (graphIdIndexes.isEmpty()) return selectedValues
        return selectedValues.mapNotNullTo(linkedSetOf()) { values ->
            if (graphIdIndexes.any { index -> values[index] != graphId }) {
                null
            } else {
                values.mapIndexed { index, value ->
                    if (index in graphIdIndexes) null else value
                }
            }
        }
    }

    fun projectedNodeRow(
        source: CypherGraph,
        node: Node,
        projectedProperties: List<String>,
        columns: List<String>
    ): MutableMap<String, Any?> = linkedMapOf<String, Any?>().apply {
        columns.indices.forEach { index ->
            this[columns[index]] = if (projectedProperties[index] == GRAPH_ID_PROPERTY) {
                source.id
            } else {
                NodePropertyAccessor.getProperty(node, projectedProperties[index], source.graph)
            }
        }
        put(INTERNAL_PROVENANCE_KEY, setOf(source.id))
    }
}
