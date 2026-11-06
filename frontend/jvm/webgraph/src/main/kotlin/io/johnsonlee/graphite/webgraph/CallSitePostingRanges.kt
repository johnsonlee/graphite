package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringPropertyDisjunctionAggregate
import io.johnsonlee.graphite.webgraph.MappedCallSiteStringIndex.PropertyCsr

internal class CallSitePostingRanges(private val properties: Array<PropertyCsr>) {
    private var propertyIndexes = IntArray(INITIAL_POSTING_RANGES)
    private var positions = IntArray(INITIAL_POSTING_RANGES)
    private var ends = IntArray(INITIAL_POSTING_RANGES)
    private var size = 0

    fun isEmpty(): Boolean = size == 0

    fun add(propertyIndex: Int, start: Int, end: Int) {
        if (start >= end) return
        ensureCapacity(size + 1)
        propertyIndexes[size] = propertyIndex
        positions[size] = start
        ends[size] = end
        size++
    }

    fun addAll(other: CallSitePostingRanges) {
        for (index in 0 until other.size) {
            add(other.propertyIndexes[index], other.positions[index], other.ends[index])
        }
    }

    fun mergedNodeIds(
        nodeOrder: (Int) -> Long,
        workConsumer: GraphWorkConsumer?,
        limit: Int = Int.MAX_VALUE
    ): Sequence<Int> = sequence {
        if (limit <= 0) return@sequence
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val heap = IntArray(size) { it }
        for (index in size / 2 - 1 downTo 0) siftDown(heap, index, size, nodeOrder)
        var heapSize = size
        var previousNodeId = -1
        var inspected = 0
        var yielded = 0
        try {
            while (heapSize > 0) {
                if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                    checkCallSiteIndexInterrupted()
                }
                val range = heap[0]
                val nodeId = currentNodeId(range)
                if (nodeId != previousNodeId) {
                    accounting.consume()
                    yielded++
                    previousNodeId = nodeId
                    accounting.flush()
                    yield(nodeId)
                    if (yielded >= limit) break
                }
                positions[range]++
                if (positions[range] >= ends[range]) {
                    heapSize--
                    if (heapSize > 0) heap[0] = heap[heapSize]
                }
                if (heapSize > 0) siftDown(heap, 0, heapSize, nodeOrder)
            }
        } finally {
            accounting.flush()
        }
    }

    fun aggregate(
        nodeIdCapacity: Int,
        distinctPropertyIndex: Int?,
        rawStringPropertyId: (Int, Int) -> Int,
        stringTable: StringTable,
        workConsumer: GraphWorkConsumer?
    ): StringPropertyDisjunctionAggregate {
        val matchedNodeIds = matchedNodeBitSet(nodeIdCapacity, workConsumer)
        val count = matchedNodeIds.sumOf { word -> java.lang.Long.bitCount(word).toLong() }
        val distinctValues = distinctPropertyIndex?.let { propertyIndex ->
            val property = properties[propertyIndex]
            if (count * SPARSE_DISTINCT_RANDOM_READ_FACTOR <= property.postingCount) {
                collectSparseDistinctValues(
                    matchedNodeIds,
                    propertyIndex,
                    rawStringPropertyId,
                    stringTable,
                    workConsumer
                )
            } else {
                property.collectDistinctValues(matchedNodeIds, stringTable, workConsumer)
            }
        }
        return StringPropertyDisjunctionAggregate(count, distinctValues?.takeIf { it.isNotEmpty() })
    }

    fun matchedNodeBitSet(nodeIdCapacity: Int, workConsumer: GraphWorkConsumer?): LongArray {
        val wordCount = ((nodeIdCapacity.toLong() + BITSET_WORD_MASK) ushr BITSET_WORD_SHIFT).toInt()
        val matchedNodeIds = LongArray(wordCount)
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            MatchedNodeCollector(matchedNodeIds, accounting).collect()
        } finally { accounting.flush() }
        return matchedNodeIds
    }

    private inner class MatchedNodeCollector(
        private val matched: LongArray,
        private val accounting: BufferedGraphWorkConsumer
    ) {
        private var inspected = 0

        fun collect() {
            for (range in 0 until size) collectRange(range)
        }

        private fun collectRange(range: Int) {
            var position = positions[range]
            val end = ends[range]
            val property = properties[propertyIndexes[range]]
            while (position < end) {
                if ((inspected++ and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) checkCallSiteIndexInterrupted()
                accounting.consume()
                val nodeId = property.postingNodeId(position++)
                val word = nodeId ushr BITSET_WORD_SHIFT
                matched[word] = matched[word] or (1L shl (nodeId and BITSET_WORD_MASK))
            }
        }
    }

    private fun collectSparseDistinctValues(
        matchedNodeIds: LongArray,
        propertyIndex: Int,
        rawStringPropertyId: (Int, Int) -> Int,
        stringTable: StringTable,
        workConsumer: GraphWorkConsumer?
    ): Set<String> {
        val values = linkedSetOf<String>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (wordIndex in matchedNodeIds.indices) {
                var remaining = matchedNodeIds[wordIndex]
                while (remaining != 0L) {
                    accounting.consume()
                    val bit = java.lang.Long.numberOfTrailingZeros(remaining)
                    val nodeId = (wordIndex shl BITSET_WORD_SHIFT) + bit
                    values += stringTable.get(rawStringPropertyId(nodeId, propertyIndex))
                    remaining = remaining and (remaining - 1L)
                }
            }
        } finally {
            accounting.flush()
        }
        return values
    }

    private fun siftDown(heap: IntArray, start: Int, heapSize: Int, nodeOrder: (Int) -> Long) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= heapSize) return
            val right = left + 1
            val child = if (right < heapSize &&
                nodeOrder(currentNodeId(heap[right])) < nodeOrder(currentNodeId(heap[left]))) {
                right
            } else {
                left
            }
            if (nodeOrder(currentNodeId(heap[parent])) <= nodeOrder(currentNodeId(heap[child]))) return
            val swapped = heap[parent]
            heap[parent] = heap[child]
            heap[child] = swapped
            parent = child
        }
    }

    private fun currentNodeId(range: Int): Int =
        properties[propertyIndexes[range]].postingNodeId(positions[range])

    private fun ensureCapacity(required: Int) {
        if (required <= propertyIndexes.size) return
        val capacity = maxOf(required, propertyIndexes.size * 2)
        propertyIndexes = propertyIndexes.copyOf(capacity)
        positions = positions.copyOf(capacity)
        ends = ends.copyOf(capacity)
    }
}
