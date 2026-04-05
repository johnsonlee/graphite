package io.johnsonlee.graphite.cypher

/**
 * Result of executing a Cypher query against a Graphite graph.
 *
 * @property columns Ordered list of column names in the result set
 * @property rows List of result rows, each mapping column names to values
 */
data class CypherResult(
    val columns: List<String>,
    val rows: List<Map<String, Any?>>
)
