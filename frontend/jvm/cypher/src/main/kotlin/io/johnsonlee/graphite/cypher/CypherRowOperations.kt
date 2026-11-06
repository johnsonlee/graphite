package io.johnsonlee.graphite.cypher

internal const val INTERNAL_ORDER_VALUES_KEY = "\u0000graphite.orderValues"

/** Row filtering, metadata, deduplication and presentation order. */
internal class CypherRowOperations(private val context: PipelineExecutionContext, private val qualified: Boolean) {
    fun visibleRow(row: Map<String, Any?>): Map<String, Any?> = row.filterKeys { key -> key != INTERNAL_PROVENANCE_KEY }

    fun evaluateOrderValues(orderBy: CypherClause.OrderBy, bindings: Map<String, Any?>, projected: Map<String, Any?>): List<Any?> {
        val orderContext = bindings.toMutableMap().apply { putAll(projected) }
        return orderBy.items.map { item -> evaluateOrderValue(item.expression, orderContext) }
    }

    fun provenanceOf(value: Any?): Set<String> =
        when (value) {
            is MethodValue -> value.graphId?.let(::setOf).orEmpty()
            is QualifiedNode -> setOf(value.graphId)
            is QualifiedEdge -> setOf(value.graphId)
            is QualifiedPath -> setOf(value.graphId)
            is Iterable<*> -> value.flatMapTo(linkedSetOf()) { provenanceOf(it) }
            is Map<*, *> -> value.values.flatMapTo(linkedSetOf()) { provenanceOf(it) }
            else -> emptySet()
        }

    @Suppress("UNCHECKED_CAST")
    fun addProvenance(row: MutableMap<String, Any?>, value: Any?) {
        val graphIds = provenanceOf(value)
        if (graphIds.isEmpty()) return
        val existing = row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet()
        row[INTERNAL_PROVENANCE_KEY] = existing + graphIds
    }

    @Suppress("UNCHECKED_CAST")
    fun copyProvenance(target: MutableMap<String, Any?>, inputs: List<Map<String, Any?>>) {
        if (!qualified) return
        val graphIds = linkedSetOf<String>()
        inputs.forEach { row -> graphIds.addAll(row[INTERNAL_PROVENANCE_KEY] as? Set<String> ?: emptySet()) }
        if (graphIds.isNotEmpty()) target[INTERNAL_PROVENANCE_KEY] = graphIds
    }

    @Suppress("UNCHECKED_CAST")
    fun distinctByVisibleValues(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (!context.workTrackingEnabled) return distinctByVisibleValuesUntracked(rows)
        val byVisibleValues = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        val normalizedValues = HashMap<Any, Map<String, Any?>>()
        for ((index, row) in rows.withIndex()) {
            context.pollCancellation(index)
            val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
            val exact = byVisibleValues[visible]
            val normalizedKey =
                if (exact == null && requiresCypherNormalization(visible)) {
                    cypherValueKey(visible)
                } else {
                    null
                }
            val representative = normalizedKey?.let(normalizedValues::get)
            val existing = exact ?: representative?.let(byVisibleValues::get)
            if (existing == null) {
                byVisibleValues[visible] = row.toMutableMap()
                if (normalizedKey != null) normalizedValues[normalizedKey] = visible
            } else {
                val graphIds =
                    (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() + (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
                if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
            }
        }
        context.checkCancelled()
        return byVisibleValues.values.toList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun distinctByVisibleValuesUntracked(rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val byVisibleValues = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        val normalizedValues = HashMap<Any, Map<String, Any?>>()
        for (row in rows) {
            val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
            val exact = byVisibleValues[visible]
            val normalizedKey =
                if (exact == null && requiresCypherNormalization(visible)) {
                    cypherValueKey(visible)
                } else {
                    null
                }
            val representative = normalizedKey?.let(normalizedValues::get)
            val existing = exact ?: representative?.let(byVisibleValues::get)
            if (existing == null) {
                byVisibleValues[visible] = row.toMutableMap()
                if (normalizedKey != null) normalizedValues[normalizedKey] = visible
            } else {
                val graphIds =
                    (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() + (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
                if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
            }
        }
        return byVisibleValues.values.toList()
    }

    fun executeWhere(clause: CypherClause.Where, rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (!context.workTrackingEnabled) return rows.filter { row -> context.evaluator.evaluate(clause.condition, row) == true }
        return rows.filterIndexed { index, row ->
            context.pollCancellation(index)
            context.evaluator.evaluate(clause.condition, row) == true
        }
    }

    fun executeInlineWhere(condition: CypherExpr, rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (!context.workTrackingEnabled) return rows.filter { row -> context.evaluator.evaluate(condition, row) == true }
        return rows.filterIndexed { index, row ->
            context.pollCancellation(index)
            context.evaluator.evaluate(condition, row) == true
        }
    }

    fun executeUnwind(clause: CypherClause.Unwind, rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        if (!context.workTrackingEnabled) return executeUnwindUntracked(clause, rows)
        val results = mutableListOf<Map<String, Any?>>()
        var processed = 0
        for (row in rows) {
            context.pollCancellation(processed++)
            val list = context.evaluator.evaluate(clause.expression, row) as? List<*> ?: continue
            for (element in list) {
                context.pollCancellation(processed++)
                val newRow = row.toMutableMap()
                newRow[clause.variable] = element
                results.add(newRow)
            }
        }
        context.checkCancelled()
        return results
    }

    private fun executeUnwindUntracked(clause: CypherClause.Unwind, rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        for (row in rows) {
            val list = context.evaluator.evaluate(clause.expression, row) as? List<*> ?: continue
            for (element in list) {
                val newRow = row.toMutableMap()
                newRow[clause.variable] = element
                results.add(newRow)
            }
        }
        return results
    }

    fun distinctAggregationValues(values: List<Any?>): List<Any?> {
        if (!context.workTrackingEnabled) return values.distinctBy(::cypherValueKey)
        val seen = LinkedHashSet<Any>()
        val distinct = ArrayList<Any?>()
        for ((index, value) in values.withIndex()) {
            context.pollCancellation(index)
            if (seen.add(cypherValueKey(value))) distinct.add(value)
        }
        context.checkCancelled()
        return distinct
    }

    fun executeOrderBy(clause: CypherClause.OrderBy, rows: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val sorted =
            if (!context.workTrackingEnabled) {
                rows.sortedWith(
                    Comparator { left, right ->
                        for ((itemIndex, item) in clause.items.withIndex()) {
                            val leftValue = orderValue(left, item, itemIndex)
                            val rightValue = orderValue(right, item, itemIndex)
                            val comparison = compareOrderValues(leftValue, rightValue, item.ascending)
                            if (comparison != 0) return@Comparator comparison
                        }
                        0
                    })
            } else {
                var comparisons = 0
                rows.sortedWith(
                    Comparator { left, right ->
                        context.pollCancellation(comparisons++)
                        for ((itemIndex, item) in clause.items.withIndex()) {
                            val leftValue = orderValue(left, item, itemIndex)
                            val rightValue = orderValue(right, item, itemIndex)
                            val comparison = compareOrderValues(leftValue, rightValue, item.ascending)
                            if (comparison != 0) return@Comparator comparison
                        }
                        0
                    })
            }
        return sorted.map(::removeOrderValues)
    }

    @Suppress("UNCHECKED_CAST")
    private fun orderValue(row: Map<String, Any?>, item: SortItem, index: Int): Any? {
        val orderValues = row[INTERNAL_ORDER_VALUES_KEY] as? List<Any?>
        return if (orderValues != null) orderValues[index] else evaluateOrderValue(item.expression, row)
    }

    private fun removeOrderValues(row: Map<String, Any?>): Map<String, Any?> {
        if (INTERNAL_ORDER_VALUES_KEY !in row) return row
        return row.toMutableMap().apply { remove(INTERNAL_ORDER_VALUES_KEY) }
    }

    fun evaluateOrderValue(expression: CypherExpr, row: Map<String, Any?>): Any? =
        if (expression is CypherExpr.Variable) row[expression.name] else context.evaluator.evaluate(expression, row)
}
