package io.johnsonlee.graphite.graph

/** Shared projection limits for persisted tables and optional bytecode signature ingestion. */
object DeclaredTypeExpansionBudget {
    private const val MAX_NODES = 100_000
    private const val MAX_BYTES = 1_000_000

    fun addNodes(left: Int, right: Int): Int = saturatedAdd(left, right, MAX_NODES)
    fun addBytes(left: Int, right: Int): Int = saturatedAdd(left, right, MAX_BYTES)

    inline fun textBytes(length: (DeclaredTypeTextField) -> Int): Int =
        DeclaredTypeTextField.entries.fold(0) { total, field -> addBytes(total, length(field)) }

    fun textBytes(type: DeclaredType): Int = textBytes { field ->
        when (field) {
            DeclaredTypeTextField.KIND -> type.kind
            DeclaredTypeTextField.NAME -> type.name
            DeclaredTypeTextField.SCOPE -> type.scope
            DeclaredTypeTextField.VARIANCE -> type.variance
        }.toByteArray(Charsets.UTF_8).size
    }

    fun validate(nodes: Int, bytes: Int) {
        require(nodes <= MAX_NODES && bytes <= MAX_BYTES) { "Excessive graph.types projection expansion" }
    }

    private fun saturatedAdd(left: Int, right: Int, limit: Int): Int =
        (left.toLong() + right).coerceAtMost(limit.toLong() + 1).toInt()
}
