package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.MethodPattern

private fun MethodPattern.isUnfiltered(): Boolean =
    declaringClass == null && name == null && parameterTypes == null && returnType == null && annotations.isEmpty()

private data class CountProjection(val aggregateIndex: Int, val grouping: List<IndexedValue<ReturnItem>>)

internal fun executeMethodCount(execution: MethodExecution, skipCount: Int, limitCount: Int): CypherResult? =
    compileProjection(execution)?.let { projection ->
        val grouping = projection.grouping
        val groups =
            if (execution.where == null && grouping.isEmpty() && execution.scan.pattern.isUnfiltered()) {
                countAllMethods(execution)
            } else countFilteredMethods(execution, grouping, saturatedMethodAdd(skipCount, limitCount))
        groups?.let { render(execution, projection, it, skipCount, limitCount) }
    }

private fun compileProjection(execution: MethodExecution): CountProjection? =
    execution.ret.items
        .withIndex()
        .filter { containsMethodAggregation(it.value.expression) }
        .singleOrNull()
        ?.takeIf { supportedCount(it.value.expression, execution.variable) }
        ?.let { aggregate -> CountProjection(aggregate.index, execution.ret.items.withIndex().filter { it.index != aggregate.index }) }

private fun supportedCount(expression: CypherExpr, variable: String): Boolean =
    when (expression) {
        is CypherExpr.CountStar -> true
        is CypherExpr.FunctionCall ->
            expression.name.equals("count", ignoreCase = true) &&
                !expression.distinct &&
                expression.args.singleOrNull() == CypherExpr.Variable(variable)
        else -> false
    }

private fun render(
    execution: MethodExecution,
    projection: CountProjection,
    groups: LinkedHashMap<List<Any?>, MethodCountGroup>,
    skip: Int,
    limit: Int
): CypherResult {
    if (projection.grouping.isEmpty() && groups.isEmpty()) groups[emptyList()] = MethodCountGroup()
    val columns = execution.columns
    val rows =
        groups.map { (key, group) ->
            var groupIndex = 0
            mutableMapOf<String, Any?>().apply {
                execution.ret.items.forEachIndexed { index, _ ->
                    put(columns[index], if (index == projection.aggregateIndex) group.count else key[groupIndex++])
                }
                if (group.graphIds.isNotEmpty()) put(INTERNAL_PROVENANCE_KEY, group.graphIds)
            }
        }
    return CypherResult(columns, rows.drop(skip).take(limit))
}

private fun countAllMethods(execution: MethodExecution): LinkedHashMap<List<Any?>, MethodCountGroup>? {
    val group = MethodCountGroup()
    var valid = true
    for (source in execution.sources) {
        execution.checkCancelled()
        val count = source.graph.methodCount()
        val total = count?.let { addCount(group.count, it) }
        if (count == null || total == null) {
            valid = false
            break
        }
        group.count = total
        if (execution.qualified && count > 0L) group.graphIds += source.id
    }
    return if (valid) linkedMapOf(emptyList<Any?>() to group) else null
}

private fun countFilteredMethods(
    execution: MethodExecution,
    groupItems: List<IndexedValue<ReturnItem>>,
    groupLimit: Int
): LinkedHashMap<List<Any?>, MethodCountGroup>? =
    if (groupItems.isNotEmpty() && !hasSourceBoundedGroups(groupItems, execution.variable)) {
        countGroupsSequentially(execution, groupItems, groupLimit)
    } else countGroupsInParallel(execution, groupItems)

private fun countGroupsSequentially(
    execution: MethodExecution,
    groupItems: List<IndexedValue<ReturnItem>>,
    groupLimit: Int
): LinkedHashMap<List<Any?>, MethodCountGroup>? {
    val groups = linkedMapOf<List<Any?>, MethodCountGroup>()
    for (source in execution.sources) {
        execution.checkCancelled()
        if (!countGroupedSourceMethods(execution, source, groupItems, groups, groupLimit)) return null
    }
    return groups
}

private fun countGroupsInParallel(
    execution: MethodExecution,
    groupItems: List<IndexedValue<ReturnItem>>
): LinkedHashMap<List<Any?>, MethodCountGroup>? {
    val groups = linkedMapOf<List<Any?>, MethodCountGroup>()
    for (wave in execution.sources.chunked(METHOD_GRAPH_SCAN_PARALLELISM)) {
        execution.checkCancelled()
        val batches =
            runMethodGraphTasks(
                wave.map { source -> { checkPeerCancelled -> countSourceMethods(execution, source, groupItems, checkPeerCancelled) } })
        if (batches.any { !mergeMethodCountGroups(groups, it) }) return null
    }
    return groups
}

internal fun mergeMethodCountGroups(
    groups: LinkedHashMap<List<Any?>, MethodCountGroup>, batch: LinkedHashMap<List<Any?>, MethodCountGroup>?
): Boolean =
    batch != null &&
        batch.all { (key, source) ->
            val group = groups.getOrPut(key) { MethodCountGroup() }
            addCount(group.count, source.count)?.let { total ->
                group.count = total
                group.graphIds += source.graphIds
                true
            } ?: false
        }

private fun hasSourceBoundedGroups(groupItems: List<IndexedValue<ReturnItem>>, variable: String): Boolean =
    groupItems.all { (_, item) ->
        when (val expression = item.expression) {
            is CypherExpr.Literal -> true
            is CypherExpr.FunctionCall ->
                expression.name.equals("graphId", ignoreCase = true) &&
                    !expression.distinct &&
                    expression.args == listOf(CypherExpr.Variable(variable))
            else -> false
        }
    }

private fun countSourceMethods(
    execution: MethodExecution,
    source: CypherGraph,
    groupItems: List<IndexedValue<ReturnItem>>,
    checkPeerCancelled: () -> Unit
): LinkedHashMap<List<Any?>, MethodCountGroup>? {
    val groups = linkedMapOf<List<Any?>, MethodCountGroup>()
    val evaluator = execution.newEvaluator()
    for (method in scanMethods(source, execution.scan, Int.MAX_VALUE, execution.workTracker, checkPeerCancelled)) {
        checkPeerCancelled()
        execution.checkCancelled()
        val binding = execution.binding(source, method)
        if (!execution.matches(binding, evaluator)) continue
        val key = groupItems.map { evaluator.evaluate(it.value.expression, binding) }
        val group = groups.getOrPut(key) { MethodCountGroup() }
        group.count = addCount(group.count, 1L) ?: return null
        if (execution.qualified) group.graphIds += source.id
    }
    return groups
}

private fun countGroupedSourceMethods(
    execution: MethodExecution,
    source: CypherGraph,
    groupItems: List<IndexedValue<ReturnItem>>,
    groups: LinkedHashMap<List<Any?>, MethodCountGroup>,
    groupLimit: Int
): Boolean {
    val evaluator = execution.newEvaluator()
    for (method in scanMethods(source, execution.scan, Int.MAX_VALUE, execution.workTracker)) {
        execution.checkCancelled()
        val binding = execution.binding(source, method)
        if (execution.matches(binding, evaluator)) {
            val key = groupItems.map { evaluator.evaluate(it.value.expression, binding) }
            if (!countBoundedGroup(execution, source.id, groups, key, groupLimit)) return false
        }
    }
    return true
}

private fun countBoundedGroup(
    execution: MethodExecution,
    graphId: String,
    groups: LinkedHashMap<List<Any?>, MethodCountGroup>,
    key: List<Any?>,
    groupLimit: Int
): Boolean {
    val group =
        groups[key]
            ?: if (groups.size < groupLimit) {
                MethodCountGroup().also { groups[key] = it }
            } else null
    if (group != null) {
        group.count = addCount(group.count, 1L) ?: return false
        if (execution.qualified) group.graphIds += graphId
    }
    return true
}

private fun addCount(current: Long, increment: Long): Long? =
    try {
        Math.addExact(current, increment)
    } catch (_: ArithmeticException) {
        null
    }

internal data class MethodCountGroup(var count: Long = 0L, val graphIds: LinkedHashSet<String> = linkedSetOf())
