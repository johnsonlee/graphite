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

/** Immutable public row reused by the bounded direct-projection result cache. */
internal class DirectProjectionCypherRow(
    private val delegate: Map<String, Any?>,
    internal val graphIds: Set<String>
) : AbstractMap<String, Any?>() {
    override val entries: Set<Map.Entry<String, Any?>>
        get() = delegate.entries
}
