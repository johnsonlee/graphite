package io.johnsonlee.graphite.webgraph

import java.io.Closeable

internal object MappedCallSiteStringIndexMemoryBudget {
    internal const val BUDGET_PROPERTY = "graphite.webgraph.callSiteStringIndexBudgetBytes"
    private const val DEFAULT_MAX_HEAP_FRACTION = 2L
    private var retainedBytes = 0L

    val maxBytes: Long by lazy {
        System.getProperty(BUDGET_PROPERTY)?.toLongOrNull()?.coerceAtLeast(0L)
            ?: (Runtime.getRuntime().maxMemory() / DEFAULT_MAX_HEAP_FRACTION)
    }

    @Synchronized
    fun tryReserve(bytes: Long): Reservation? {
        if (bytes < 0L || bytes > maxBytes - retainedBytes) return null
        retainedBytes += bytes
        return Reservation(bytes)
    }

    @Synchronized
    internal fun retainedBytes(): Long = retainedBytes

    internal class Reservation internal constructor(initialBytes: Long) : Closeable {
        var bytes: Long = initialBytes
            private set
        private var closed = false

        fun tryGrowTo(requiredBytes: Long): Boolean = synchronized(MappedCallSiteStringIndexMemoryBudget) {
            check(!closed) { "Cannot grow a closed mapped CallSite string-index reservation" }
            if (requiredBytes <= bytes) return@synchronized true
            val added = requiredBytes - bytes
            if (added > maxBytes - retainedBytes) return@synchronized false
            retainedBytes += added
            bytes = requiredBytes
            true
        }

        fun shrinkTo(retained: Long) = synchronized(MappedCallSiteStringIndexMemoryBudget) {
            check(!closed && retained in 0L..bytes)
            retainedBytes -= bytes - retained
            bytes = retained
        }

        override fun close() {
            synchronized(MappedCallSiteStringIndexMemoryBudget) {
                if (closed) return
                closed = true
                retainedBytes -= bytes
                check(retainedBytes >= 0L) { "Mapped CallSite string-index budget underflow" }
            }
        }
    }
}

internal fun estimatedMappedCallSiteStringIndexCountBytes(stringCount: Int): Long? =
    estimatedPrimitiveArraysBytes(CALL_SITE_STRING_PROPERTY_COUNT, stringCount.toLong())

internal fun estimatedMappedCallSiteStringIndexRetainedBytes(
    nodeCount: Long,
    stringCount: Int,
    uniqueCounts: IntArray
): Long? {
    val validCounts = nodeCount in 0L..Int.MAX_VALUE && stringCount >= 0
    if (!validCounts || uniqueCounts.size != CALL_SITE_STRING_PROPERTY_COUNT) {
        return null
    }
    return try {
        val postingBytes = Math.multiplyExact(
            nodeCount,
            CALL_SITE_STRING_PROPERTY_COUNT.toLong() * Int.SIZE_BYTES
        )
        val compactCsrBytes = Math.multiplyExact(
            uniqueCounts.sumOf(Int::toLong),
            2L * Int.SIZE_BYTES
        )
        val trigramSignatureBytes = Math.multiplyExact(stringCount.toLong(), Long.SIZE_BYTES.toLong())
        Math.addExact(
            MAPPED_CALL_SITE_STRING_INDEX_OBJECT_ESTIMATED_BYTES +
                MAPPED_CALL_SITE_STRING_INDEX_RETAINED_ARRAYS * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
            Math.addExact(
                Math.addExact(postingBytes, compactCsrBytes),
                trigramSignatureBytes
            )
        )
    } catch (_: ArithmeticException) {
        null
    }
}

internal fun estimatedPrimitiveArraysBytes(arrayCount: Int, entriesPerArray: Long): Long? = try {
    Math.addExact(
        arrayCount * PRIMITIVE_ARRAY_HEADER_ESTIMATED_BYTES,
        Math.multiplyExact(arrayCount.toLong() * entriesPerArray, Int.SIZE_BYTES.toLong())
    )
} catch (_: ArithmeticException) {
    null
}
