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

/**
 * Immutable public row of a storage-direct projection. The column names are shared by every row
 * of one result and the values sit in one array, so a row costs one small object instead of a
 * hash map, and a lookup scans the handful of columns, which is cheaper than hashing at that width.
 */
internal class DirectProjectionCypherRow(
    private val names: Array<String>,
    private val cells: Array<Any?>,
    internal val graphIds: Set<String>
) : AbstractMap<String, Any?>() {
    init {
        require(names.size == cells.size) { "Row has ${cells.size} cells for ${names.size} columns" }
    }

    @Volatile
    private var entrySet: Set<Map.Entry<String, Any?>>? = null

    override val size: Int
        get() = names.size

    override fun isEmpty(): Boolean = names.isEmpty()

    override fun containsKey(key: String): Boolean = indexOf(key) >= 0

    override fun get(key: String): Any? {
        val index = indexOf(key)
        return if (index < 0) null else cells[index]
    }

    override val entries: Set<Map.Entry<String, Any?>>
        get() = entrySet ?: buildEntries().also { entrySet = it }

    private fun buildEntries(): Set<Map.Entry<String, Any?>> {
        val built = LinkedHashSet<Map.Entry<String, Any?>>(names.size * 2)
        for (index in names.indices) {
            built += java.util.AbstractMap.SimpleImmutableEntry<String, Any?>(names[index], cells[index])
        }
        return java.util.Collections.unmodifiableSet(built)
    }

    private fun indexOf(key: String): Int {
        for (index in names.indices) {
            if (names[index] == key) return index
        }
        return -1
    }
}

/**
 * Column layout shared by the rows of one direct projection result: duplicate column names
 * collapse to their first position with the last value, exactly as an insertion-ordered map
 * would, and the optional metadata key is appended after the visible columns.
 */
internal class DirectProjectionRowLayout(columns: List<String>, metadataKey: String?) {
    private val names: Array<String>
    private val cellOfColumn: IntArray
    private val metadataCell: Int

    init {
        val order = LinkedHashMap<String, Int>(columns.size * 2 + 2)
        columns.forEach { column -> if (column !in order) order[column] = order.size }
        cellOfColumn = IntArray(columns.size) { index -> order.getValue(columns[index]) }
        metadataCell = if (metadataKey == null) -1 else order.getOrPut(metadataKey) { order.size }
        names = order.keys.toTypedArray()
    }

    /** Builds one row from [values] indexed like the layout's columns. */
    fun row(values: List<Any?>, metadata: Any?, graphIds: Set<String>): DirectProjectionCypherRow {
        val cells = arrayOfNulls<Any?>(names.size)
        for (column in cellOfColumn.indices) cells[cellOfColumn[column]] = values[column]
        if (metadataCell >= 0) cells[metadataCell] = metadata
        return DirectProjectionCypherRow(names, cells, graphIds)
    }
}
