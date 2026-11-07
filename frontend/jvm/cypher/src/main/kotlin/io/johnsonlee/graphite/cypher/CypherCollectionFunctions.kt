package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node

internal fun collectionSize(value: Any?): Int? =
    when (value) {
        is String -> value.length
        is List<*> -> value.size
        is QualifiedPath -> value.edges.size
        is PathFinder.Path -> value.edges.size
        else -> null
    }

internal fun substringValue(args: List<Any?>): String? {
    val str = args[0] as? String ?: return null
    val start = (args[1] as Number).toInt()
    return if (args.size > 2) {
        val length = (args[2] as Number).toInt()
        str.substring(start, minOf(start + length, str.length))
    } else {
        str.substring(start)
    }
}

internal fun reverseValue(value: Any?, checkCancelled: (() -> Unit)?): Any? =
    when (value) {
        is String -> reverseString(value, checkCancelled)
        is List<*> -> reverseList(value, checkCancelled)
        else -> null
    }

private fun reverseString(value: String, checkCancelled: (() -> Unit)?): String {
    if (checkCancelled == null) return value.reversed()
    val result = StringBuilder(value.length)
    var sourceIndex = value.length
    var copied = 0
    while (sourceIndex > 0) {
        if ((copied and CANCELLATION_POLL_MASK) == 0) checkCancelled()
        val trailing = value[--sourceIndex]
        if (Character.isLowSurrogate(trailing) && sourceIndex > 0) {
            val leading = value[sourceIndex - 1]
            if (Character.isHighSurrogate(leading)) {
                result.append(leading).append(trailing)
                sourceIndex--
                copied += 2
                continue
            }
        }
        result.append(trailing)
        copied++
    }
    checkCancelled()
    return result.toString()
}

private fun reverseList(value: List<*>, checkCancelled: (() -> Unit)?): List<*> {
    if (checkCancelled == null) return value.reversed()
    val result = ArrayList<Any?>(value.size)
    val iterator = value.listIterator(value.size)
    var copied = 0
    while (iterator.hasPrevious()) {
        if ((copied and CANCELLATION_POLL_MASK) == 0) checkCancelled()
        result.add(iterator.previous())
        copied++
    }
    checkCancelled()
    return result
}

internal fun tailValues(value: Any?, checkCancelled: (() -> Unit)?): List<*>? =
    when {
        value !is List<*> -> null
        checkCancelled == null -> value.drop(1)
        else -> copyTail(value, checkCancelled)
    }

private fun copyTail(list: List<*>, checkCancelled: () -> Unit): List<*> {
    val result = ArrayList<Any?>(maxOf(list.size - 1, 0))
    val iterator = list.iterator()
    if (iterator.hasNext()) iterator.next()
    var copied = 0
    while (iterator.hasNext()) {
        if ((copied and CANCELLATION_POLL_MASK) == 0) checkCancelled()
        result.add(iterator.next())
        copied++
    }
    checkCancelled()
    return result
}

internal fun rangeValues(args: List<Any?>, checkCancelled: (() -> Unit)?): List<Long> {
    val start = (args[0] as Number).toLong()
    val end = (args[1] as Number).toLong()
    val step = if (args.size > 2) (args[2] as Number).toLong() else 1L
    if (step == 0L) throw CypherException("Step cannot be zero in range()")
    val values = CypherRangeValues()
    var index = 0
    for (value in LongProgression.fromClosedRange(start, end, step)) {
        if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled?.invoke()
        values.add(value)
        index++
    }
    checkCancelled?.invoke()
    return values
}

internal fun pathNodes(value: Any?, checkCancelled: (() -> Unit)?): List<*>? =
    when (value) {
        is QualifiedPath -> value.nodes
        is PathFinder.Path -> value.nodes
        is List<*> -> filterList(value, checkCancelled) { it is Node || it is QualifiedNode }
        else -> null
    }

internal fun pathRelationships(value: Any?, checkCancelled: (() -> Unit)?): List<*>? =
    when (value) {
        is QualifiedPath -> value.edges
        is PathFinder.Path -> value.edges
        is List<*> -> filterList(value, checkCancelled) { it is Edge || it is QualifiedEdge }
        else -> null
    }

private fun filterList(values: List<*>, checkCancelled: (() -> Unit)?, predicate: (Any?) -> Boolean): List<*> {
    if (checkCancelled == null) return values.filter(predicate)
    val result = ArrayList<Any?>()
    for ((index, value) in values.withIndex()) {
        if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
        if (predicate(value)) result.add(value)
    }
    checkCancelled()
    return result
}
