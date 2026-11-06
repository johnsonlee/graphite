package io.johnsonlee.graphite.cypher

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

internal fun percentileCont(values: List<Any?>, p: Double): Any? {
    val nums = values.filterNotNull().map { numericValue(it) }.sorted()
    if (nums.isEmpty()) return null
    val idx = p * (nums.size - 1)
    val lower = nums[idx.toInt()]
    val upper = nums[minOf(idx.toInt() + 1, nums.size - 1)]
    val frac = idx - idx.toInt()
    return lower + frac * (upper - lower)
}

internal fun percentileDisc(values: List<Any?>, p: Double): Any? {
    val nums = values.filterNotNull().map { numericValue(it) }.sorted()
    if (nums.isEmpty()) return null
    val idx = kotlin.math.ceil(p * nums.size).toInt() - 1
    return nums[maxOf(0, idx)]
}

internal fun stdev(values: List<Any?>, sample: Boolean): Any? {
    val nums = values.filterNotNull().map { numericValue(it) }
    if (nums.size < 2) return null
    val mean = nums.sum() / nums.size
    val divisor = if (sample) (nums.size - 1) else nums.size
    val variance = nums.sumOf { (it - mean).pow(2) } / divisor
    return sqrt(variance)
}

internal fun sumValues(values: List<Any?>, checkCancelled: () -> Unit): Double {
    var sum = 0.0
    for ((index, value) in values.withIndex()) {
        pollCancellation(index, checkCancelled)
        if (value != null) sum += numericValue(value)
    }
    checkCancelled()
    return sum
}

internal fun countValues(values: List<Any?>, checkCancelled: () -> Unit): Long {
    var count = 0L
    for ((index, value) in values.withIndex()) {
        pollCancellation(index, checkCancelled)
        if (value != null) count++
    }
    checkCancelled()
    return count
}

internal fun averageValues(values: List<Any?>, checkCancelled: () -> Unit): Double? {
    var sum = 0.0
    var count = 0
    for ((index, value) in values.withIndex()) {
        pollCancellation(index, checkCancelled)
        if (value != null) {
            sum += numericValue(value)
            count++
        }
    }
    checkCancelled()
    return if (count == 0) null else sum / count
}

internal fun extremeValue(values: List<Any?>, checkCancelled: () -> Unit, minimum: Boolean): Any? {
    var selected: Any? = null
    var selectedNumber = 0.0
    for ((index, value) in values.withIndex()) {
        pollCancellation(index, checkCancelled)
        if (value == null) continue
        val number = numericValue(value)
        val comparison = number.compareTo(selectedNumber)
        if (selected == null || if (minimum) comparison < 0 else comparison > 0) {
            selected = value
            selectedNumber = number
        }
    }
    checkCancelled()
    return selected
}

internal fun collectValues(values: List<Any?>, checkCancelled: () -> Unit): List<Any?> {
    val result = ArrayList<Any?>(values.size)
    for ((index, value) in values.withIndex()) {
        pollCancellation(index, checkCancelled)
        if (value != null) result.add(value)
    }
    checkCancelled()
    return result
}

internal fun percentileCont(values: List<Any?>, p: Double, checkCancelled: () -> Unit): Any? {
    val nums = numericValues(values, checkCancelled)
    sortNumericValues(nums, checkCancelled)
    if (nums.isEmpty()) return null
    val idx = p * (nums.size - 1)
    val lower = nums[idx.toInt()]
    val upper = nums[minOf(idx.toInt() + 1, nums.size - 1)]
    val frac = idx - idx.toInt()
    return lower + frac * (upper - lower)
}

internal fun percentileDisc(values: List<Any?>, p: Double, checkCancelled: () -> Unit): Any? {
    val nums = numericValues(values, checkCancelled)
    sortNumericValues(nums, checkCancelled)
    if (nums.isEmpty()) return null
    val idx = kotlin.math.ceil(p * nums.size).toInt() - 1
    return nums[maxOf(0, idx)]
}

internal fun stdev(values: List<Any?>, sample: Boolean, checkCancelled: () -> Unit): Any? {
    val nums = numericValues(values, checkCancelled)
    if (nums.size < 2) return null
    var sum = 0.0
    for ((index, number) in nums.withIndex()) {
        pollCancellation(index, checkCancelled)
        sum += number
    }
    val mean = sum / nums.size
    var squaredDifferences = 0.0
    for ((index, number) in nums.withIndex()) {
        pollCancellation(index, checkCancelled)
        squaredDifferences += (number - mean).pow(2)
    }
    checkCancelled()
    val divisor = if (sample) nums.size - 1 else nums.size
    return sqrt(squaredDifferences / divisor)
}

private fun numericValues(values: List<Any?>, checkCancelled: () -> Unit): MutableList<Double> {
    val numbers = ArrayList<Double>(values.size)
    for ((index, value) in values.withIndex()) {
        pollCancellation(index, checkCancelled)
        if (value != null) numbers.add(numericValue(value))
    }
    checkCancelled()
    return numbers
}

private fun sortNumericValues(values: MutableList<Double>, checkCancelled: () -> Unit) {
    var comparisons = 0
    values.sortWith { left, right ->
        pollCancellation(comparisons++, checkCancelled)
        left.compareTo(right)
    }
    checkCancelled()
}

private fun pollCancellation(index: Int, checkCancelled: () -> Unit) {
    if ((index and CANCELLATION_POLL_MASK) == 0) checkCancelled()
}
