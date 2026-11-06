package io.johnsonlee.graphite.cypher

/** RETURN and WITH projection, aggregation and grouping semantics. */
internal class CypherProjection(private val context: PipelineExecutionContext, private val rowOperations: CypherRowOperations) {
    // ========================================================================
    // RETURN / WITH (projection + optional aggregation)
    // ========================================================================
    fun projectAndAggregate(
        items: List<ReturnItem>,
        rows: List<Map<String, Any?>>,
        distinct: Boolean,
        orderBy: CypherClause.OrderBy? = null
    ): Pair<List<Map<String, Any?>>, List<String>> {
        // Expand RETURN * into individual variable references for all bound names
        val expandedItems = if (items.size == 1 &&
            items[0].expression is CypherExpr.Variable &&
            (items[0].expression as CypherExpr.Variable).name == "*"
        ) {
            val allKeys = rows.firstOrNull()?.keys?.filter { it != INTERNAL_PROVENANCE_KEY } ?: emptyList()
            allKeys.map { key -> ReturnItem(CypherExpr.Variable(key)) }
        } else {
            items
        }

        val columns = expandedItems.map { it.alias ?: it.expression.toCypherString() }

        // Check if any item uses aggregation
        val hasAggregation = expandedItems.any { containsAggregation(it.expression) }

        val resultRows = when {
            hasAggregation -> aggregateRows(expandedItems, columns, rows, orderBy)
            context.workTrackingEnabled -> projectTrackedRows(expandedItems, columns, rows, distinct, orderBy)
            else -> projectUntrackedRows(expandedItems, columns, rows, distinct, orderBy)
        }

        val finalRows = if (distinct) rowOperations.distinctByVisibleValues(resultRows) else resultRows
        return finalRows to columns
    }

    private fun aggregateRows(
        items: List<ReturnItem>,
        columns: List<String>,
        rows: List<Map<String, Any?>>,
        orderBy: CypherClause.OrderBy?
    ): List<Map<String, Any?>> {
        val groupByIndices = items.indices.filter { !containsAggregation(items[it].expression) }
        val aggregateIndices = items.indices.filter { containsAggregation(items[it].expression) }.toSet()
        return when {
            groupByIndices.isEmpty() -> listOf(projectGroup(items, columns, aggregateIndices, rows))
            context.workTrackingEnabled -> projectTrackedGroups(items, columns, groupByIndices, aggregateIndices, rows, orderBy)
            else -> groupRowsByCypherValue(items, groupByIndices, rows, trackWork = false).map { group ->
                projectGroup(items, columns, aggregateIndices, group).also { attachGroupOrderValues(it, group, orderBy) }
            }
        }
    }

    private fun projectGroup(
        items: List<ReturnItem>,
        columns: List<String>,
        aggregateIndices: Set<Int>,
        rows: List<Map<String, Any?>>
    ): MutableMap<String, Any?> {
        val row = mutableMapOf<String, Any?>()
        for (index in items.indices) {
            row[columns[index]] = if (index in aggregateIndices) {
                evaluateAggregation(items[index].expression, rows)
            } else {
                context.evaluator.evaluate(items[index].expression, rows.firstOrNull() ?: emptyMap())
            }
        }
        rowOperations.copyProvenance(row, rows)
        return row
    }

    private fun projectUntrackedRows(
        items: List<ReturnItem>,
        columns: List<String>,
        rows: List<Map<String, Any?>>,
        distinct: Boolean,
        orderBy: CypherClause.OrderBy?
    ): List<Map<String, Any?>> = rows.map { row ->
        val projected = mutableMapOf<String, Any?>()
        for (index in items.indices) projected[columns[index]] = context.evaluator.evaluate(items[index].expression, row)
        rowOperations.copyProvenance(projected, listOf(row))
        attachOrderValues(projected, row, distinct, orderBy)
        projected
    }

    private fun projectTrackedGroups(
        items: List<ReturnItem>,
        columns: List<String>,
        groupByIndices: List<Int>,
        aggregateIndices: Set<Int>,
        rows: List<Map<String, Any?>>,
        orderBy: CypherClause.OrderBy?
    ): List<Map<String, Any?>> {
        val groups = groupRowsByCypherValue(items, groupByIndices, rows, trackWork = true)
        return groups.mapIndexed { index, groupRows ->
            context.pollCancellation(index)
            val row = mutableMapOf<String, Any?>()
            for (i in items.indices) {
                row[columns[i]] = if (i in aggregateIndices) {
                    evaluateAggregation(items[i].expression, groupRows)
                } else {
                    context.evaluator.evaluate(items[i].expression, groupRows.first())
                }
            }
            rowOperations.copyProvenance(row, groupRows)
            attachGroupOrderValues(row, groupRows, orderBy)
            row
        }
    }

    /**
     * After `RETURN n.type, count(*)` the variable `n` is gone, so `ORDER BY n.type` is
     * evaluated on the group's first source row, as the Rust engine does; an aggregate in
     * the sort key is still rejected there.
     */
    private fun attachGroupOrderValues(
        projected: MutableMap<String, Any?>,
        groupRows: List<Map<String, Any?>>,
        orderBy: CypherClause.OrderBy?
    ) = attachOrderValues(projected, groupRows.first(), distinct = false, orderBy)

    private fun groupRowsByCypherValue(
        items: List<ReturnItem>,
        groupByIndices: List<Int>,
        rows: List<Map<String, Any?>>,
        trackWork: Boolean
    ): List<List<Map<String, Any?>>> {
        if (groupByIndices.size == 1) {
            return groupRowsBySingleCypherValue(items[groupByIndices.single()], rows, trackWork)
        }
        val exactGroups = LinkedHashMap<List<Any?>, MutableList<Map<String, Any?>>>()
        val normalizedGroups = HashMap<Any, List<Any?>>()
        for ((rowIndex, row) in rows.withIndex()) {
            if (trackWork) context.pollCancellation(rowIndex)
            val exactKey = groupByIndices.map { index -> context.evaluator.evaluate(items[index].expression, row) }
            val exactGroup = exactGroups[exactKey]
            val normalizedKey = if (exactGroup == null && requiresCypherNormalization(exactKey)) {
                cypherValueKey(exactKey)
            } else {
                null
            }
            val representative = normalizedKey?.let(normalizedGroups::get)
            val group = exactGroup ?: representative?.let(exactGroups::get)
            if (group != null) {
                group.add(row)
            } else {
                exactGroups[exactKey] = mutableListOf(row)
                if (normalizedKey != null) normalizedGroups[normalizedKey] = exactKey
            }
        }
        return exactGroups.values.toList()
    }

    private fun groupRowsBySingleCypherValue(
        item: ReturnItem,
        rows: List<Map<String, Any?>>,
        trackWork: Boolean
    ): List<List<Map<String, Any?>>> {
        val exactGroups = LinkedHashMap<Any?, MutableList<Map<String, Any?>>>()
        val normalizedGroups = HashMap<Any, Any?>()
        for ((rowIndex, row) in rows.withIndex()) {
            if (trackWork) context.pollCancellation(rowIndex)
            val exactKey = context.evaluator.evaluate(item.expression, row)
            val exactGroup = exactGroups[exactKey]
            val normalizedKey = if (exactGroup == null && requiresCypherNormalization(exactKey)) {
                cypherValueKey(exactKey)
            } else {
                null
            }
            val representative = normalizedKey?.let(normalizedGroups::get)
            val group = exactGroup ?: representative?.let(exactGroups::get)
            if (group != null) {
                group.add(row)
            } else {
                exactGroups[exactKey] = mutableListOf(row)
                if (normalizedKey != null) normalizedGroups[normalizedKey] = exactKey
            }
        }
        return exactGroups.values.toList()
    }

    private fun projectTrackedRows(
        items: List<ReturnItem>,
        columns: List<String>,
        rows: List<Map<String, Any?>>,
        distinct: Boolean,
        orderBy: CypherClause.OrderBy?
    ): List<Map<String, Any?>> = rows.mapIndexed { rowIndex, row ->
        context.pollCancellation(rowIndex)
        val projected = mutableMapOf<String, Any?>()
        for (i in items.indices) {
            projected[columns[i]] = context.evaluator.evaluate(items[i].expression, row)
        }
        rowOperations.copyProvenance(projected, listOf(row))
        attachOrderValues(projected, row, distinct, orderBy)
        projected
    }

    private fun attachOrderValues(
        projected: MutableMap<String, Any?>,
        source: Map<String, Any?>,
        distinct: Boolean,
        orderBy: CypherClause.OrderBy?
    ) {
        if (!distinct && orderBy != null && requiresPreProjectionOrderValues(orderBy, projected.keys)) {
            projected[INTERNAL_ORDER_VALUES_KEY] = rowOperations.evaluateOrderValues(orderBy, source, projected)
        }
    }

    private fun requiresPreProjectionOrderValues(
        orderBy: CypherClause.OrderBy,
        projectedColumns: Set<String>
    ): Boolean = orderBy.items.any { item ->
        val variable = item.expression as? CypherExpr.Variable
        variable == null || variable.name !in projectedColumns
    }

    fun containsAggregation(expr: CypherExpr): Boolean = when (expr) {
        is CypherExpr.FunctionCall ->
            CypherFunctions.isAggregation(expr.name) || expr.args.any { containsAggregation(it) }
        is CypherExpr.CountStar -> true
        is CypherExpr.Property -> containsAggregation(expr.expression)
        is CypherExpr.PredicateFunction -> containsAggregation(expr.listExpr) ||
            expr.predicate?.let { containsAggregation(it) } == true
        is CypherExpr.BinaryOp -> containsAggregation(expr.left) || containsAggregation(expr.right)
        is CypherExpr.Comparison -> containsAggregation(expr.left) || containsAggregation(expr.right)
        is CypherExpr.Distinct -> containsAggregation(expr.expression)
        else -> false
    }

    private fun evaluateAggregation(
        expr: CypherExpr,
        rows: List<Map<String, Any?>>
    ): Any? = when (expr) {
        is CypherExpr.CountStar -> rows.size.toLong()
        is CypherExpr.FunctionCall -> {
            if (CypherFunctions.isAggregation(expr.name)) {
                val values = if (context.workTrackingEnabled) {
                    rows.mapIndexed { index, row ->
                        context.pollCancellation(index)
                        if (expr.args.isEmpty()) row else context.evaluator.evaluate(expr.args[0], row)
                    }
                } else {
                    rows.map { row ->
                        if (expr.args.isEmpty()) row else context.evaluator.evaluate(expr.args[0], row)
                    }
                }
                val filtered = if (expr.distinct) rowOperations.distinctAggregationValues(values) else values
                if (context.workTrackingEnabled) {
                    CypherFunctions.aggregate(expr.name, filtered, context::checkCancelled)
                } else {
                    CypherFunctions.aggregate(expr.name, filtered)
                }
            } else {
                context.evaluator.evaluate(expr, rows.firstOrNull() ?: emptyMap())
            }
        }
        is CypherExpr.Distinct -> evaluateAggregation(expr.expression, rows)
        else -> context.evaluator.evaluate(expr, rows.firstOrNull() ?: emptyMap())
    }

    fun projectRow(
        items: List<ReturnItem>,
        columns: List<String>,
        bindings: Map<String, Any?>
    ): MutableMap<String, Any?> {
        val projected = mutableMapOf<String, Any?>()
        for (i in items.indices) {
            projected[columns[i]] = context.evaluator.evaluate(items[i].expression, bindings)
        }
        rowOperations.copyProvenance(projected, listOf(bindings))
        return projected
    }

}
