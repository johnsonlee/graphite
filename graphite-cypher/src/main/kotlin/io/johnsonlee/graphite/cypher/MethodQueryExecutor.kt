package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.methodSlice
import io.johnsonlee.graphite.graph.methods
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val INITIAL_METHOD_RESULT_CAPACITY = 1_024

internal val METHOD_GRAPH_SCAN_PARALLELISM: Int =
    Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

private val methodGraphScanPool = ForkJoinPool(METHOD_GRAPH_SCAN_PARALLELISM)

@Suppress("ThrowsCount", "TooGenericExceptionCaught")
private fun <T> runMethodGraphTasks(tasks: List<(() -> Unit) -> T>): List<T> {
    if (tasks.size == 1) return listOf(tasks.single().invoke({}))
    val cancellation = MethodTaskCancellation()
    val remaining = CountDownLatch(tasks.size)
    val handles = tasks.map { task ->
        val state = AtomicInteger(METHOD_TASK_PENDING)
        val future = methodGraphScanPool.submit(Callable {
            if (!state.compareAndSet(METHOD_TASK_PENDING, METHOD_TASK_RUNNING)) {
                throw ParallelMethodTaskCancelledException()
            }
            try {
                cancellation.check()
                task(cancellation::check)
            } catch (error: Throwable) {
                cancellation.fail(error)
                throw error
            } finally {
                state.set(METHOD_TASK_FINISHED)
                remaining.countDown()
            }
        })
        MethodTaskHandle(state, future)
    }
    return try {
        handles.map { it.future.get() }
    } catch (_: InterruptedException) {
        cancellation.fail(CypherQueryCancelledException())
        cancelPendingAndAwait(handles, remaining)
        Thread.currentThread().interrupt()
        throw CypherQueryCancelledException()
    } catch (error: ExecutionException) {
        cancellation.fail(error.cause ?: error)
        cancelPendingAndAwait(handles, remaining)
        val outer = cancellation.failure() ?: error.cause ?: error
        val nested = outer.cause
        val cause = if (nested != null && nested.javaClass == outer.javaClass && outer.message == nested.toString()) {
            nested
        } else {
            outer
        }
        when (cause) {
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw IllegalStateException("Parallel Method scan failed", cause)
        }
    }
}

private fun <T> cancelPendingAndAwait(handles: List<MethodTaskHandle<T>>, remaining: CountDownLatch) {
    handles.forEach { handle ->
        if (handle.state.compareAndSet(METHOD_TASK_PENDING, METHOD_TASK_CANCELLED)) {
            handle.future.cancel(false)
            remaining.countDown()
        }
    }
    var interrupted = false
    while (remaining.count > 0L) {
        try {
            remaining.await()
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private class MethodTaskCancellation {
    private val cause = AtomicReference<Throwable?>()

    fun fail(error: Throwable) {
        if (error !is ParallelMethodTaskCancelledException) cause.compareAndSet(null, error)
    }

    fun check() {
        if (cause.get() != null) throw ParallelMethodTaskCancelledException()
    }

    fun failure(): Throwable? = cause.get()
}

private data class MethodTaskHandle<T>(
    val state: AtomicInteger,
    val future: ForkJoinTask<T>
)

private class ParallelMethodTaskCancelledException : RuntimeException()

private const val METHOD_TASK_PENDING = 0
private const val METHOD_TASK_RUNNING = 1
private const val METHOD_TASK_FINISHED = 2
private const val METHOD_TASK_CANCELLED = 3

/** Executes bounded Cypher reads over the graph's method metadata index. */
@Suppress("TooManyFunctions")
internal object MethodQueryExecutor {

    fun referencesMethod(clauses: List<CypherClause>): Boolean = clauses.any { clause ->
        clause is CypherClause.Match && clause.patterns.any { pattern ->
            pattern.elements.any { element ->
                element is PatternElement.NodePattern && element.labels.any(::isMethodLabel)
            }
        }
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun tryExecute(
        clauses: List<CypherClause>,
        sources: List<CypherGraph>,
        qualified: Boolean,
        checkCancelled: () -> Unit,
        workTracker: CypherWorkTracker?
    ): CypherResult? {
        val match = clauses.firstOrNull() as? CypherClause.Match
            ?: return null
        if (match.optional || match.patterns.size != 1) return null
        val pattern = match.patterns.single()
        if (pattern.pathVariable != null || pattern.elements.size != 1) return null
        val nodePattern = pattern.elements.single() as? PatternElement.NodePattern
            ?: return null
        if (nodePattern.labels.size != 1 || !isMethodLabel(nodePattern.labels.single())) return null
        val variable = nodePattern.variable ?: return null

        var clauseIndex = 1
        val where = (clauses.getOrNull(clauseIndex) as? CypherClause.Where)?.also { clauseIndex++ }
        val ret = clauses.getOrNull(clauseIndex) as? CypherClause.Return ?: return null
        clauseIndex++
        val orderBy = (clauses.getOrNull(clauseIndex) as? CypherClause.OrderBy)?.also { clauseIndex++ }
        val skip = (clauses.getOrNull(clauseIndex) as? CypherClause.Skip)?.also { clauseIndex++ }
        val limit = (clauses.getOrNull(clauseIndex) as? CypherClause.Limit)?.also { clauseIndex++ }
        if (clauseIndex != clauses.size) return null

        val skipCount = literalCount(skip?.count, default = 0) ?: return null
        val limitCount = literalCount(limit?.count, default = Int.MAX_VALUE) ?: return null
        val requested = saturatedAdd(skipCount, limitCount)
        val scan = scanPlan(nodePattern, variable, where) ?: return null
        val execution = MethodExecution(
            ret,
            variable,
            where,
            scan,
            sources,
            qualified,
            checkCancelled,
            workTracker
        )
        if (ret.items.any { containsAggregation(it.expression) }) {
            if (ret.distinct || orderBy != null) return null
            return tryExecuteCount(execution, skipCount, limitCount)
        }
        if (requested == 0) return CypherResult(columns(ret), emptyList())
        val columns = execution.columns
        val comparator = orderBy?.let { methodRowComparator(it, columns) ?: return null }
        if (ret.distinct && comparator != null) return null
        if (comparator != null && requested > MAX_ORDERED_TOP_K_ROWS) return null
        val rows = if (ret.distinct) {
            executeDistinctRows(execution, skipCount, limitCount, requested)
        } else if (comparator == null) {
            executeStreamingRows(execution, skipCount, limitCount)
        } else {
            executeOrderedRows(execution, skipCount, limitCount, requested, comparator)
        }
        checkCancelled()
        return CypherResult(columns, rows)
    }

    private fun scanPlan(
        nodePattern: PatternElement.NodePattern,
        variable: String,
        where: CypherClause.Where?
    ): MethodScanPlan? {
        val predicate = MethodPredicate(variable)
        nodePattern.properties.forEach { (property, value) ->
            if (!predicate.addEquality(property, value)) return null
        }
        val fullyPushed = where == null || predicate.addConjuncts(where.condition)
        return MethodScanPlan(predicate.pattern(), fullyPushed)
    }

    private fun executeStreamingRows(
        execution: MethodExecution,
        skipCount: Int,
        limitCount: Int
    ): List<Map<String, Any?>> = if (
        execution.sources.size == 1 ||
        saturatedAdd(skipCount, limitCount) <= METHOD_GRAPH_SCAN_PARALLELISM ||
        saturatedAdd(skipCount, limitCount) > MAX_ORDERED_TOP_K_ROWS
    ) {
        executeSequentialRows(execution, skipCount, limitCount)
    } else {
        executeParallelRows(execution, skipCount, limitCount)
    }

    private fun executeSequentialRows(
        execution: MethodExecution,
        skipCount: Int,
        limitCount: Int
    ): List<Map<String, Any?>> {
        val rows = ArrayList<Map<String, Any?>>(minOf(limitCount, INITIAL_METHOD_RESULT_CAPACITY))
        var matched = 0
        val evaluator = execution.newEvaluator()
        for (source in execution.sources) {
            execution.checkCancelled()
            if (rows.size >= limitCount) break
            val remaining = saturatedAdd(skipCount - minOf(skipCount, matched), limitCount - rows.size)
            for (method in methods(source, execution.scan, remaining, execution.workTracker)) {
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

    @Suppress("NestedBlockDepth")
    private fun executeParallelRows(
        execution: MethodExecution,
        skipCount: Int,
        limitCount: Int
    ): List<Map<String, Any?>> {
        val requested = saturatedAdd(skipCount, limitCount)
        val rows = ArrayList<Map<String, Any?>>(minOf(limitCount, INITIAL_METHOD_RESULT_CAPACITY))
        var matched = 0
        for (wave in execution.sources.chunked(METHOD_GRAPH_SCAN_PARALLELISM)) {
            execution.checkCancelled()
            val batches = runMethodGraphTasks(wave.map { source ->
                { checkPeerCancelled -> executeSourceRows(execution, source, requested, checkPeerCancelled) }
            })
            for (batch in batches) {
                for (row in batch) {
                    if (matched++ >= skipCount) rows += row
                    if (rows.size >= limitCount) return rows
                }
            }
        }
        return rows
    }

    private fun executeSourceRows(
        execution: MethodExecution,
        source: CypherGraph,
        limit: Int,
        checkPeerCancelled: () -> Unit
    ): List<Map<String, Any?>> {
        val rows = ArrayList<Map<String, Any?>>(minOf(limit, INITIAL_METHOD_RESULT_CAPACITY))
        val evaluator = execution.newEvaluator()
        for (method in methods(source, execution.scan, limit, execution.workTracker, checkPeerCancelled)) {
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
        val topRows = PriorityQueue<Map<String, Any?>>(requested, comparator.reversed())
        for (wave in execution.sources.chunked(METHOD_GRAPH_SCAN_PARALLELISM)) {
            execution.checkCancelled()
            val batches = runMethodGraphTasks(wave.map { source ->
                { checkPeerCancelled ->
                    executeOrderedSourceRows(execution, source, requested, comparator, checkPeerCancelled)
                }
            })
            for (batch in batches) {
                batch.forEach { row -> addOrderedRow(topRows, row, requested, comparator) }
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
        for (method in methods(source, execution.scan, Int.MAX_VALUE, execution.workTracker, checkPeerCancelled)) {
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

    private fun methods(
        source: CypherGraph,
        scan: MethodScanPlan,
        limit: Int,
        workTracker: CypherWorkTracker?,
        checkPeerCancelled: (() -> Unit)? = null
    ): Sequence<MethodDescriptor> {
        var inspected = 0
        val cancellationConsumer = if (workTracker != null || checkPeerCancelled != null) {
            MethodMetadataScanConsumer {
                if ((inspected++ and CANCELLATION_POLL_MASK) == 0) {
                    checkPeerCancelled?.invoke()
                    workTracker?.checkCancelled()
                }
            }
        } else null
        if (scan.fullyPushed && limit < Int.MAX_VALUE) {
            val slice = if (cancellationConsumer == null) {
                source.graph.methodSlice(scan.pattern, limit)
            } else {
                source.graph.methodSlice(scan.pattern, limit, cancellationConsumer)
            }
            if (slice != null) return slice.asSequence()
        }
        return cancellationConsumer?.let { source.graph.methods(scan.pattern, it) }
            ?: source.graph.methods(scan.pattern)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun executeDistinctRows(
        execution: MethodExecution,
        skipCount: Int,
        limitCount: Int,
        requested: Int
    ): List<Map<String, Any?>> {
        val distinctRows = LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>()
        val evaluator = execution.newEvaluator()
        sourceLoop@ for (source in execution.sources) {
            execution.checkCancelled()
            for (method in methods(source, execution.scan, Int.MAX_VALUE, execution.workTracker)) {
                execution.checkCancelled()
                val binding = execution.binding(source, method)
                if (!execution.matches(binding, evaluator)) continue
                mergeDistinctRow(distinctRows, execution.project(binding, evaluator), requested)
                if (!execution.qualified && distinctRows.size >= requested) break@sourceLoop
            }
        }
        return distinctRows.values.drop(skipCount).take(limitCount)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeDistinctRow(
        rows: LinkedHashMap<Map<String, Any?>, MutableMap<String, Any?>>,
        row: MutableMap<String, Any?>,
        limit: Int
    ) {
        val visible = row.filterKeys { it != INTERNAL_PROVENANCE_KEY }
        val existing = rows[visible]
        if (existing != null) {
            val graphIds = (existing[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty() +
                (row[INTERNAL_PROVENANCE_KEY] as? Set<String>).orEmpty()
            if (graphIds.isNotEmpty()) existing[INTERNAL_PROVENANCE_KEY] = graphIds
        } else if (rows.size < limit) {
            rows[visible] = row
        }
    }

    private fun methodRowComparator(
        orderBy: CypherClause.OrderBy,
        columns: List<String>
    ): Comparator<Map<String, Any?>>? {
        val ordered = orderBy.items.map { item ->
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

    private fun compareNullable(left: Any?, right: Any?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        left is Number && right is Number -> left.toDouble().compareTo(right.toDouble())
        left is String && right is String -> left.compareTo(right)
        left is Boolean && right is Boolean -> left.compareTo(right)
        else -> left.toString().compareTo(right.toString())
    }

    private fun MethodPattern.isUnfiltered(): Boolean = declaringClass == null &&
        name == null && parameterTypes == null && returnType == null && annotations.isEmpty()

    private fun columns(ret: CypherClause.Return): List<String> =
        ret.items.map { it.alias ?: it.expression.toCypherString() }

    private fun literalCount(expression: CypherExpr?, default: Int): Int? = if (expression == null) {
        default
    } else {
        ((expression as? CypherExpr.Literal)?.value as? Number)
            ?.toLong()
            ?.coerceIn(0, Int.MAX_VALUE.toLong())
            ?.toInt()
    }

    private fun saturatedAdd(left: Int, right: Int): Int =
        (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun containsAggregation(expression: CypherExpr): Boolean = when (expression) {
        is CypherExpr.CountStar -> true
        is CypherExpr.FunctionCall -> CypherFunctions.isAggregation(expression.name) ||
            expression.args.any(::containsAggregation)
        is CypherExpr.Property -> containsAggregation(expression.expression)
        is CypherExpr.BinaryOp -> containsAggregation(expression.left) || containsAggregation(expression.right)
        is CypherExpr.Comparison -> containsAggregation(expression.left) || containsAggregation(expression.right)
        is CypherExpr.Distinct -> containsAggregation(expression.expression)
        else -> false
    }

    @Suppress("ComplexCondition", "ReturnCount")
    private fun tryExecuteCount(
        execution: MethodExecution,
        skipCount: Int,
        limitCount: Int
    ): CypherResult? {
        val aggregateItems = execution.ret.items.withIndex().filter { containsAggregation(it.value.expression) }
        val aggregate = aggregateItems.singleOrNull() ?: return null
        val countExpression = aggregate.value.expression
        val supported = countExpression is CypherExpr.CountStar ||
            countExpression is CypherExpr.FunctionCall &&
            countExpression.name.equals("count", ignoreCase = true) &&
            !countExpression.distinct &&
            countExpression.args.singleOrNull() == CypherExpr.Variable(execution.variable)
        if (!supported) return null

        val groupItems = execution.ret.items.withIndex().filter { it.index != aggregate.index }
        val groups = if (execution.where == null && groupItems.isEmpty() && execution.scan.pattern.isUnfiltered()) {
            countAllMethods(execution) ?: return null
        } else {
            countFilteredMethods(execution, groupItems, saturatedAdd(skipCount, limitCount)) ?: return null
        }
        if (groupItems.isEmpty() && groups.isEmpty()) groups[emptyList()] = MethodCountGroup()

        val columns = execution.columns
        val rows = groups.map { (key, group) ->
            var groupIndex = 0
            mutableMapOf<String, Any?>().apply {
                execution.ret.items.forEachIndexed { index, _ ->
                    put(columns[index], if (index == aggregate.index) group.count else key[groupIndex++])
                }
                if (group.graphIds.isNotEmpty()) put(INTERNAL_PROVENANCE_KEY, group.graphIds)
            }
        }
        return CypherResult(columns, rows.drop(skipCount).take(limitCount))
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

    @Suppress("ReturnCount")
    private fun countFilteredMethods(
        execution: MethodExecution,
        groupItems: List<IndexedValue<ReturnItem>>,
        groupLimit: Int
    ): LinkedHashMap<List<Any?>, MethodCountGroup>? {
        val groups = linkedMapOf<List<Any?>, MethodCountGroup>()
        if (groupItems.isNotEmpty() && !hasSourceBoundedGroups(groupItems, execution.variable)) {
            for (source in execution.sources) {
                execution.checkCancelled()
                if (!countGroupedSourceMethods(execution, source, groupItems, groups, groupLimit)) return null
            }
            return groups
        }
        for (wave in execution.sources.chunked(METHOD_GRAPH_SCAN_PARALLELISM)) {
            execution.checkCancelled()
            val batches = runMethodGraphTasks(wave.map { source ->
                { checkPeerCancelled ->
                    countSourceMethods(execution, source, groupItems, checkPeerCancelled)
                }
            })
            for (batch in batches) {
                batch ?: return null
                for ((key, sourceGroup) in batch) {
                    val group = groups.getOrPut(key) { MethodCountGroup() }
                    group.count = addCount(group.count, sourceGroup.count) ?: return null
                    group.graphIds += sourceGroup.graphIds
                }
            }
        }
        return groups
    }

    private fun hasSourceBoundedGroups(
        groupItems: List<IndexedValue<ReturnItem>>,
        variable: String
    ): Boolean = groupItems.all { (_, item) ->
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
        for (method in methods(source, execution.scan, Int.MAX_VALUE, execution.workTracker, checkPeerCancelled)) {
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

    @Suppress("LoopWithTooManyJumpStatements")
    private fun countGroupedSourceMethods(
        execution: MethodExecution,
        source: CypherGraph,
        groupItems: List<IndexedValue<ReturnItem>>,
        groups: LinkedHashMap<List<Any?>, MethodCountGroup>,
        groupLimit: Int
    ): Boolean {
        val evaluator = execution.newEvaluator()
        for (method in methods(source, execution.scan, Int.MAX_VALUE, execution.workTracker)) {
            execution.checkCancelled()
            val binding = execution.binding(source, method)
            if (!execution.matches(binding, evaluator)) continue
            val key = groupItems.map { evaluator.evaluate(it.value.expression, binding) }
            val group = groups[key] ?: if (groups.size < groupLimit) {
                MethodCountGroup().also { groups[key] = it }
            } else {
                continue
            }
            group.count = addCount(group.count, 1L) ?: return false
            if (execution.qualified) group.graphIds += source.id
        }
        return true
    }

    private fun addCount(current: Long, increment: Long): Long? = try {
        Math.addExact(current, increment)
    } catch (_: ArithmeticException) {
        null
    }

    fun isMethodLabel(label: String): Boolean = label.equals(METHOD_LABEL, ignoreCase = true)

    private data class MethodScanPlan(val pattern: MethodPattern, val fullyPushed: Boolean)

    private data class MethodExecution(
        val ret: CypherClause.Return,
        val variable: String,
        val where: CypherClause.Where?,
        val scan: MethodScanPlan,
        val sources: List<CypherGraph>,
        val qualified: Boolean,
        val checkCancellation: () -> Unit,
        val workTracker: CypherWorkTracker?
    ) {
        val columns = ret.items.map { it.alias ?: it.expression.toCypherString() }

        fun checkCancelled() {
            if (workTracker == null) checkCancellation() else workTracker.checkCancelled()
        }

        fun newEvaluator(): ExpressionEvaluator = ExpressionEvaluator(::checkCancelled)

        fun binding(source: CypherGraph, method: MethodDescriptor): MutableMap<String, Any?> =
            mutableMapOf<String, Any?>(
                variable to MethodValue(source.id.takeIf { qualified }, method)
            ).apply {
                if (qualified) put(INTERNAL_PROVENANCE_KEY, setOf(source.id))
            }

        fun matches(binding: Map<String, Any?>, evaluator: ExpressionEvaluator): Boolean =
            scan.fullyPushed || where == null || evaluator.evaluate(where.condition, binding) == true

        fun project(
            binding: Map<String, Any?>,
            evaluator: ExpressionEvaluator
        ): MutableMap<String, Any?> =
            mutableMapOf<String, Any?>().apply {
                ret.items.forEachIndexed { index, item ->
                    put(columns[index], evaluator.evaluate(item.expression, binding))
                }
                binding[INTERNAL_PROVENANCE_KEY]?.let { put(INTERNAL_PROVENANCE_KEY, it) }
            }
    }

    private data class MethodCountGroup(
        var count: Long = 0L,
        val graphIds: LinkedHashSet<String> = linkedSetOf()
    )

}

@Suppress("ReturnCount")
private class MethodPredicate(private val variable: String) {
    private var declaringClass: String? = null
    private var name: String? = null
    private var parameterTypes: List<String>? = null
    private var returnType: String? = null

    fun pattern(): MethodPattern = MethodPattern(
        declaringClass = declaringClass,
        name = name,
        parameterTypes = parameterTypes,
        returnType = returnType,
        useRegex = true
    )

    fun addConjuncts(expression: CypherExpr): Boolean = if (expression is CypherExpr.And) {
        val left = addConjuncts(expression.left)
        val right = addConjuncts(expression.right)
        left && right
    } else {
        add(expression)
    }

    fun add(expression: CypherExpr): Boolean = when (expression) {
        is CypherExpr.And -> add(expression.left) && add(expression.right)
        is CypherExpr.Comparison -> expression.op == "=" && addComparison(expression.left, expression.right)
        is CypherExpr.StringOp -> addStringOperation(expression)
        is CypherExpr.RegexMatch -> addRegex(expression)
        else -> false
    }

    fun addEquality(property: String, value: CypherExpr): Boolean {
        val raw = literal(value) ?: return false
        return when (property) {
            METHOD_SIGNATURE_PROPERTY -> addSignature(raw as? String)
            METHOD_PARAMETER_TYPES_PROPERTY -> {
                val parameters = (raw as? List<*>)?.map { it as? String ?: return false } ?: return false
                setOnce(parameterTypes, parameters.map(Regex::escape)) { parameterTypes = it }
            }
            else -> addProperty(property, (raw as? String)?.let(Regex::escape))
        }
    }

    private fun addComparison(left: CypherExpr, right: CypherExpr): Boolean {
        val leftProperty = property(left)
        val rightProperty = property(right)
        return when {
            leftProperty != null -> addEquality(leftProperty, right)
            rightProperty != null -> addEquality(rightProperty, left)
            else -> false
        }
    }

    private fun addStringOperation(expression: CypherExpr.StringOp): Boolean {
        val property = property(expression.left) ?: return false
        val value = (literal(expression.right) as? String) ?: return false
        val escaped = Regex.escape(value)
        val regex = when (expression.op) {
            "STARTS WITH" -> "$escaped.*"
            "ENDS WITH" -> ".*$escaped"
            "CONTAINS" -> ".*$escaped.*"
            else -> return false
        }
        return addProperty(property, regex)
    }

    private fun addRegex(expression: CypherExpr.RegexMatch): Boolean {
        val property = property(expression.left) ?: return false
        val regex = literal(expression.right) as? String ?: return false
        return addProperty(property, regex)
    }

    private fun property(expression: CypherExpr): String? {
        val property = expression as? CypherExpr.Property ?: return null
        val owner = property.expression as? CypherExpr.Variable ?: return null
        return property.propertyName.takeIf { owner.name == variable }
    }

    private fun addProperty(property: String, regex: String?): Boolean = when (property) {
        METHOD_CLASS_PROPERTY -> setOnce(declaringClass, regex) { declaringClass = it }
        METHOD_NAME_PROPERTY -> setOnce(name, regex) { name = it }
        METHOD_RETURN_TYPE_PROPERTY -> setOnce(returnType, regex) { returnType = it }
        else -> false
    }

    private fun addSignature(signature: String?): Boolean {
        signature ?: return false
        val open = signature.lastIndexOf('(')
        val close = signature.lastIndexOf(')')
        val separator = signature.lastIndexOf('.', startIndex = open - 1)
        if (open <= 0 || close != signature.lastIndex || separator <= 0) return false
        val parameters = signature.substring(open + 1, close)
            .takeIf(String::isNotEmpty)
            ?.split(',')
            .orEmpty()
            .map(Regex::escape)
        return setOnce(declaringClass, Regex.escape(signature.substring(0, separator))) { declaringClass = it } &&
            setOnce(name, Regex.escape(signature.substring(separator + 1, open))) { name = it } &&
            setOnce(parameterTypes, parameters) { parameterTypes = it }
    }

    private fun literal(expression: CypherExpr): Any? = when (expression) {
        is CypherExpr.Literal -> expression.value
        is CypherExpr.ListLiteral -> expression.elements.map { literal(it) ?: return null }
        else -> null
    }

    private fun <T> setOnce(current: T?, candidate: T?, assign: (T) -> Unit): Boolean {
        if (candidate == null || current != null && current != candidate) return false
        if (current == null) assign(candidate)
        return true
    }
}
