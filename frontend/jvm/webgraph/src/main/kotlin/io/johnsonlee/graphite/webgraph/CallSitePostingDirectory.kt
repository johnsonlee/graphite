package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import java.io.DataOutput
import java.util.zip.CRC32

internal open class CallSitePostingDirectory(
    private val postingEnds: IntArray,
    private val usedStringIds: IntArray,
    private val postingNodeIds: IntArray,
    prevalidated: Boolean = false
) {
    init {
        require(postingEnds.size == usedStringIds.size)
        if (!prevalidated) {
            require((1 until usedStringIds.size).all { index ->
                usedStringIds[index - 1] < usedStringIds[index]
            })
        }
        require(postingEnds.lastOrNull() ?: 0 == postingNodeIds.size)
    }

    val postingCount: Int
        get() = postingNodeIds.size

    val uniqueStringCount: Int
        get() = usedStringIds.size

    fun writePersistent(output: DataOutput) {
        writePersistentInts(usedStringIds, output)
        writePersistentInts(postingEnds, output)
        writePersistentInts(postingNodeIds, output)
    }

    fun updatePersistentChecksum(checksum: CRC32) {
        updatePersistentIntChecksum(usedStringIds, checksum)
        updatePersistentIntChecksum(postingEnds, checksum)
        updatePersistentIntChecksum(postingNodeIds, checksum)
    }

    private fun writePersistentInts(values: IntArray, output: DataOutput) {
        values.forEachIndexed { index, value ->
            if ((index and CALL_SITE_INDEX_PERSISTENCE_POLL_MASK) == 0) {
                checkCallSiteIndexPersistenceInterrupted()
            }
            output.writeInt(value)
        }
    }

    private fun updatePersistentIntChecksum(values: IntArray, checksum: CRC32) {
        values.forEachIndexed { index, value ->
            if ((index and CALL_SITE_INDEX_PERSISTENCE_POLL_MASK) == 0) {
                checkCallSiteIndexPersistenceInterrupted()
            }
            checksum.updateInt(value)
        }
    }

    fun collectMatchingRanges(
        propertyIndex: Int,
        runtime: CallSitePredicateRuntime,
        candidateStringIds: IntArray?,
        candidatesAreKnownMatches: Boolean,
        target: CallSitePostingRanges,
        workConsumer: GraphWorkConsumer?
    ) {
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        val matcher = RangeMatcher(propertyIndex, runtime, target, accounting)
        try {
            val exact = runtime.exactStringId
            when {
                exact != null -> matcher.exact(exact)
                candidateStringIds == null -> matcher.allStrings()
                candidatesAreKnownMatches -> matcher.knownCandidates(candidateStringIds)
                else -> matcher.verifyCandidates(candidateStringIds)
            }
        } finally { accounting.flush() }
    }

    private inner class RangeMatcher(
        private val propertyIndex: Int,
        private val runtime: CallSitePredicateRuntime,
        private val target: CallSitePostingRanges,
        private val accounting: BufferedGraphWorkConsumer
    ) {
        fun exact(stringId: Int) {
            if (stringId >= 0) {
                val row = findStringRow(stringId, accounting)
                if (row >= 0) addRange(propertyIndex, row, target)
            }
        }

        fun knownCandidates(candidates: IntArray) {
            var candidateIndex = 0
            var row = 0
            while (candidateIndex < candidates.size && row < usedStringIds.size) {
                if (((candidateIndex + row) and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                    checkCallSiteIndexInterrupted()
                }
                when {
                    candidates[candidateIndex] < usedStringIds[row] -> {
                        candidateIndex = gallopLowerBound(candidates, candidateIndex, usedStringIds[row], accounting)
                    }
                    candidates[candidateIndex] > usedStringIds[row] -> {
                        row = gallopLowerBound(usedStringIds, row, candidates[candidateIndex], accounting)
                    }
                    else -> {
                        accounting.consume()
                        addRange(propertyIndex, row, target)
                        candidateIndex++
                        row++
                    }
                }
            }
        }

        fun verifyCandidates(candidates: IntArray) {
            for (stringId in candidates) {
                accounting.consume()
                val row = findStringRow(stringId, accounting)
                if (row >= 0 && runtime.matches(stringId)) addRange(propertyIndex, row, target)
            }
        }

        fun allStrings() {
            for (row in usedStringIds.indices) {
                if ((row and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) checkCallSiteIndexInterrupted()
                accounting.consume()
                if (runtime.matches(usedStringIds[row])) addRange(propertyIndex, row, target)
            }
        }
    }

    private fun gallopLowerBound(
        values: IntArray,
        start: Int,
        target: Int,
        accounting: BufferedGraphWorkConsumer
    ): Int {
        var bound = 1
        while (bound < values.size - start) {
            accounting.consume()
            if (values[start + bound] >= target) break
            if ((bound and CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK) == 0) {
                checkCallSiteIndexInterrupted()
            }
            bound = (bound shl 1).coerceAtMost(values.size - start)
        }
        var low = start + (bound ushr 1) + 1
        var high = minOf(values.size, start + bound + 1)
        while (low < high) {
            accounting.consume()
            val middle = (low + high).ushr(1)
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    private fun findStringRow(stringId: Int, accounting: BufferedGraphWorkConsumer): Int {
        var low = 0
        var high = usedStringIds.lastIndex
        while (low <= high) {
            accounting.consume()
            val middle = (low + high).ushr(1)
            when {
                usedStringIds[middle] < stringId -> low = middle + 1
                usedStringIds[middle] > stringId -> high = middle - 1
                else -> return middle
            }
        }
        return -1
    }

    fun postingRange(stringId: Int, accounting: BufferedGraphWorkConsumer): IntRange? {
        val row = findStringRow(stringId, accounting)
        if (row < 0) return null
        val start = if (row == 0) 0 else postingEnds[row - 1]
        return start until postingEnds[row]
    }

    fun containsStringId(stringId: Int, workConsumer: GraphWorkConsumer?): Boolean {
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        return try {
            findStringRow(stringId, accounting) >= 0
        } finally {
            accounting.flush()
        }
    }

    fun postingNodeId(position: Int): Int = postingNodeIds[position]

    fun forEachNodeId(action: (Int) -> Unit) = postingNodeIds.forEach(action)

    fun forEachUsedStringId(action: (Int) -> Unit) = usedStringIds.forEach(action)

    fun collectDistinctValues(
        matchedNodeIds: LongArray,
        stringTable: StringTable,
        workConsumer: GraphWorkConsumer?
    ): Set<String> {
        val values = linkedSetOf<String>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        var start = 0
        try {
            for (row in usedStringIds.indices) {
                val end = postingEnds[row]
                if (rowHasMatchedNode(start, end, matchedNodeIds, accounting)) {
                    values += stringTable.get(usedStringIds[row])
                }
                start = end
            }
        } finally {
            accounting.flush()
        }
        return values
    }

    private fun rowHasMatchedNode(start: Int, end: Int, matchedNodeIds: LongArray, accounting: BufferedGraphWorkConsumer): Boolean {
        var position = start
        while (position < end) {
            accounting.consume()
            val nodeId = postingNodeIds[position++]
            if (matchedNodeIds[nodeId ushr BITSET_WORD_SHIFT] and (1L shl (nodeId and BITSET_WORD_MASK)) != 0L) return true
        }
        return false
    }

    private fun addRange(propertyIndex: Int, row: Int, target: CallSitePostingRanges) {
        val start = if (row == 0) 0 else postingEnds[row - 1]
        target.add(propertyIndex, start, postingEnds[row])
    }
}
