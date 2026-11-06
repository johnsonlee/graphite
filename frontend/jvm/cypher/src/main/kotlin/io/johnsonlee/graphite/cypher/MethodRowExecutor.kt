package io.johnsonlee.graphite.cypher

import java.util.PriorityQueue

private const val INITIAL_METHOD_RESULT_CAPACITY = 1_024

private const val MAX_PARALLEL_METHOD_ROWS = 5_000

private const val MAX_BUFFERED_PARALLEL_METHOD_ROWS = 20_000

private data class RowOrder(val comparator: Comparator<Map<String, Any?>>?)

internal fun executeMethodRows(execution: MethodExecution, skip: Int, limit: Int, orderBy: CypherClause.OrderBy?): CypherResult? {
    val requested = saturatedMethodAdd(skip, limit)
    if (requested == 0) return CypherResult(methodColumns(execution.ret), emptyList())
    return compileOrder(execution, orderBy, requested)?.let { order ->
        val rows =
            when {
                execution.ret.distinct -> executeDistinctRows(execution, skip, limit, requested)
                order.comparator == null -> executeStreamingRows(execution, skip, limit)
                else -> executeOrderedRows(execution, skip, limit, requested, order.comparator)
            }
        execution.checkCancellation()
        CypherResult(execution.columns, rows)
    }
}

private fun compileOrder(execution: MethodExecution, orderBy: CypherClause.OrderBy?, requested: Int): RowOrder? {
    val comparator = orderBy?.let { methodRowComparator(it, execution.columns) ?: return null }
    return when {
        execution.ret.distinct && comparator != null -> null
        comparator != null && requested > MAX_ORDERED_TOP_K_ROWS -> null
        else -> RowOrder(comparator)
    }
}

private fun executeStreamingRows(execution: MethodExecution, skipCount: Int, limitCount: Int): List<Map<String, Any?>> =
    if (execution.sources.size == 1 ||
        saturatedMethodAdd(skipCount, limitCount) <= METHOD_GRAPH_SCAN_PARALLELISM ||
        saturatedMethodAdd(skipCount, limitCount) > MAX_PARALLEL_METHOD_ROWS) {
        executeSequentialRows(execution, skipCount, limitCount)
    } else {
        executeParallelRows(execution, skipCount, limitCount)
    }

private fun executeSequentialRows(execution: MethodExecution, skipCount: Int, limitCount: Int): List<Map<String, Any?>> {
    val rows = ArrayList<Map<String, Any?>>(minOf(limitCount, INITIAL_METHOD_RESULT_CAPACITY))
    var matched = 0
    val evaluator = execution.newEvaluator()
    for (source in execution.sources) {
        execution.checkCancelled()
        if (rows.size >= limitCount) break
        val remaining = saturatedMethodAdd(skipCount - minOf(skipCount, matched), limitCount - rows.size)
        for (method in scanMethods(source, execution.scan, remaining, execution.workTracker)) {
            execution.checkCancelled()
            val binding = execution.binding(source, method)
            val matches = execution.matches(binding, evaluator)
            if (matches && matched++ >= skipCount) {
                rows += execution.project(binding, evaluator)
            }
            if (rows.size >= limitCount) break
        }
    }
    return rows
}

private fun executeParallelRows(execution: MethodExecution, skipCount: Int, limitCount: Int): List<Map<String, Any?>> {
    val requested = saturatedMethodAdd(skipCount, limitCount)
    val page = ParallelPage(skipCount, limitCount)
    for (wave in execution.sources.chunked(methodGraphWaveSize(requested))) {
        execution.checkCancelled()
        val batches =
            runMethodGraphTasks(
                wave.map { source -> { checkPeerCancelled -> executeSourceRows(execution, source, requested, checkPeerCancelled) } })
        if (page.accept(batches)) return page.rows
    }
    return page.rows
}

private class ParallelPage(private val skip: Int, private val limit: Int) {
    val rows = ArrayList<Map<String, Any?>>(minOf(limit, INITIAL_METHOD_RESULT_CAPACITY))
    private var matched = 0

    fun accept(batches: List<List<Map<String, Any?>>>): Boolean {
        for (batch in batches) {
            for (row in batch) {
                if (matched++ >= skip) rows += row
                if (rows.size >= limit) return true
            }
        }
        return false
    }
}

private fun executeSourceRows(
    execution: MethodExecution,
    source: CypherGraph,
    limit: Int,
    checkPeerCancelled: () -> Unit
): List<Map<String, Any?>> {
    val rows = ArrayList<Map<String, Any?>>(minOf(limit, INITIAL_METHOD_RESULT_CAPACITY))
    val evaluator = execution.newEvaluator()
    for (method in scanMethods(source, execution.scan, limit, execution.workTracker, checkPeerCancelled)) {
        checkPeerCancelled()
        execution.checkCancelled()
        val binding = execution.binding(source, method)
        if (execution.matches(binding, evaluator)) {
            rows += execution.project(binding, evaluator)
            if (rows.size >= limit) break
        }
    }
    return rows
}

private fun executeOrderedRows(
    execution: MethodExecution,
    skipCount: Int,
    limitCount: Int,
    requested: Int,
    comparator: Comparator<Map<String, Any?>>
): List<Map<String, Any?>> {
    if (execution.sources.size == 1 || requested > MAX_PARALLEL_METHOD_ROWS) {
        return executeOrderedRowsSequential(execution, skipCount, limitCount, requested, comparator)
    }
    val topRows = PriorityQueue<Map<String, Any?>>(requested, comparator.reversed())
    for (wave in execution.sources.chunked(methodGraphWaveSize(requested))) {
        execution.checkCancelled()
        val batches =
            runMethodGraphTasks(
                wave.map { source ->
                    { checkPeerCancelled -> executeOrderedSourceRows(execution, source, requested, comparator, checkPeerCancelled) }
                })
        for (batch in batches) {
            batch.forEach { row -> addOrderedRow(topRows, row, requested, comparator) }
        }
    }
    return topRows.sortedWith(comparator).drop(skipCount).take(limitCount)
}

private fun executeOrderedRowsSequential(
    execution: MethodExecution,
    skipCount: Int,
    limitCount: Int,
    requested: Int,
    comparator: Comparator<Map<String, Any?>>
): List<Map<String, Any?>> {
    val topRows = PriorityQueue<Map<String, Any?>>(requested, comparator.reversed())
    val evaluator = execution.newEvaluator()
    for (source in execution.sources) {
        execution.checkCancelled()
        for (method in scanMethods(source, execution.scan, Int.MAX_VALUE, execution.workTracker)) {
            execution.checkCancelled()
            val binding = execution.binding(source, method)
            if (!execution.matches(binding, evaluator)) continue
            addOrderedRow(topRows, execution.project(binding, evaluator), requested, comparator)
        }
    }
    return topRows.sortedWith(comparator).drop(skipCount).take(limitCount)
}

private fun executeOrderedSourceRows(
    execution: MethodExecution,
    source: CypherGraph,
    requested: Int,
    comparator: Comparator<Map<String, Any?>>,
    checkPeerCancelled: () -> Unit
): List<Map<String, Any?>> {
    val topRows = PriorityQueue<Map<String, Any?>>(requested, comparator.reversed())
    val evaluator = execution.newEvaluator()
    for (method in scanMethods(source, execution.scan, Int.MAX_VALUE, execution.workTracker, checkPeerCancelled)) {
        checkPeerCancelled()
        execution.checkCancelled()
        val binding = execution.binding(source, method)
        if (!execution.matches(binding, evaluator)) continue
        addOrderedRow(topRows, execution.project(binding, evaluator), requested, comparator)
    }
    return topRows.toList()
}

private fun addOrderedRow(
    rows: PriorityQueue<Map<String, Any?>>,
    row: Map<String, Any?>,
    requested: Int,
    comparator: Comparator<Map<String, Any?>>
) {
    if (rows.size < requested) {
        rows += row
    } else if (comparator.compare(row, rows.peek()) < 0) {
        rows.poll()
        rows += row
    }
}

private fun executeDistinctRows(execution: MethodExecution, skipCount: Int, limitCount: Int, requested: Int): List<Map<String, Any?>> {
    val distinctRows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
    val evaluator = execution.newEvaluator()
    sourceLoop@ for (source in execution.sources) {
        execution.checkCancelled()
        for (method in scanMethods(source, execution.scan, Int.MAX_VALUE, execution.workTracker)) {
            execution.checkCancelled()
            val binding = execution.binding(source, method)
            if (execution.matches(binding, evaluator) && admitDistinct(execution, binding, evaluator, distinctRows, requested)) {
                break@sourceLoop
            }
        }
    }
    return distinctRows.values.drop(skipCount).take(limitCount)
}

private fun admitDistinct(
    execution: MethodExecution,
    binding: Map<String, Any?>,
    evaluator: ExpressionEvaluator,
    rows: LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>,
    requested: Int
): Boolean {
    mergeDistinctRow(rows, execution.project(binding, evaluator), requested)
    return !execution.qualified && rows.size >= requested
}

@Suppress("UNCHECKED_CAST")
private fun mergeDistinctRow(rows: LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>, row: MutableMap<String, Any?>, limit: Int) {
    val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
    val existing = rows[visible]
    if (existing != null) {
        val graphIds =
            (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() + (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
        if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
    } else if (rows.size < limit) {
        rows[visible] = row
    }
}

private fun methodRowComparator(orderBy: CypherClause.OrderBy, columns: List<String>): Comparator<Map<String, Any?>>? {
    val ordered =
        orderBy.items.map { item ->
            val variable = item.expression as? CypherExpr.Variable
            if (variable == null || variable.name !in columns) return null
            variable.name to item.ascending
        }
    return Comparator { left, right ->
        for ((column, ascending) in ordered) {
            val comparison = compareNullable(left[column], right[column])
            if (comparison != 0) return@Comparator if (ascending) comparison else -comparison
        }
        0
    }
}

private fun compareNullable(left: Any?, right: Any?): Int =
    when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
        left is String && right is String -> left.compareTo(right)
        left is Boolean && right is Boolean -> left.compareTo(right)
        else -> left.toString().compareTo(right.toString())
    }

private fun methodGraphWaveSize(requested: Int): Int =
    minOf(METHOD_GRAPH_SCAN_PARALLELISM, (MAX_BUFFERED_PARALLEL_METHOD_ROWS / requested.coerceAtLeast(1)).coerceAtLeast(1))
