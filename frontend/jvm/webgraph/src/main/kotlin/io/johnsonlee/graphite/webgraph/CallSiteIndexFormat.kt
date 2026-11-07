package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.checkThreadInterrupted
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

internal fun callSiteStringPropertyIndex(property: String): Int = when (property) {
    CALLER_CLASS_PROPERTY -> CALLER_CLASS_PROPERTY_INDEX
    CALLER_NAME_PROPERTY -> CALLER_NAME_PROPERTY_INDEX
    CALLEE_CLASS_PROPERTY -> CALLEE_CLASS_PROPERTY_INDEX
    CALLEE_NAME_PROPERTY -> CALLEE_NAME_PROPERTY_INDEX
    else -> -1
}

internal fun checkCallSiteIndexInterrupted() {
    checkThreadInterrupted { CancellationException("Mapped CallSite string index work interrupted") }
}

internal fun lowerBound(values: LongArray, target: Long): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (values[middle] < target) low = middle + 1 else high = middle
    }
    return low
}

internal fun upperBound(values: LongArray, target: Long): Int {
    var low = 0
    var high = values.size
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (values[middle] <= target) low = middle + 1 else high = middle
    }
    return low
}

internal fun CRC32.updateInt(value: Int) {
    repeat(Int.SIZE_BYTES) { byteIndex ->
        update(value ushr (byteIndex * Byte.SIZE_BITS) and CALL_SITE_INDEX_BYTE_MASK)
    }
}

internal fun CRC32.updateLong(value: Long) {
    repeat(Long.SIZE_BYTES) { byteIndex ->
        update(((value ushr (byteIndex * Byte.SIZE_BITS)) and CALL_SITE_INDEX_BYTE_MASK.toLong()).toInt())
    }
}

internal const val CALLER_CLASS_PROPERTY = "caller_class"

internal const val CALLER_NAME_PROPERTY = "caller_name"

internal const val CALLEE_CLASS_PROPERTY = "callee_class"

internal const val CALLEE_NAME_PROPERTY = "callee_name"

internal const val CALL_SITE_STRING_PROPERTY_COUNT = 4

internal const val CALLER_CLASS_PROPERTY_INDEX = 0

internal const val CALLER_NAME_PROPERTY_INDEX = 1

internal const val CALLEE_CLASS_PROPERTY_INDEX = 2

internal const val CALLEE_NAME_PROPERTY_INDEX = 3

internal const val MAPPED_CALL_SITE_STRING_INDEX_RETAINED_ARRAYS = 3L * CALL_SITE_STRING_PROPERTY_COUNT + 1L

internal const val MAPPED_CALL_SITE_STRING_INDEX_OBJECT_ESTIMATED_BYTES = 256L

internal const val CALL_SITE_STRING_INDEX_MAGIC = 0x47524353

internal const val CALL_SITE_STRING_INDEX_VERSION = 2

internal const val CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES = 32

internal const val CALL_SITE_STRING_INDEX_HEADER_BYTES =
    4 * Int.SIZE_BYTES + CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES +
        CALL_SITE_STRING_PROPERTY_COUNT * Int.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES

internal const val CALL_SITE_INDEX_PERSISTENCE_POLL_MASK = 1_023

internal fun checkCallSiteIndexPersistenceInterrupted() {
    checkThreadInterrupted { CancellationException("Mapped CallSite index persistence interrupted") }
}

internal const val CALL_SITE_STRING_INDEX_INTERRUPTION_POLL_MASK = 1_023

internal const val MIN_PARALLEL_CALL_SITE_TRIGRAM_SORT_SIZE = 1 shl 20

internal const val RADIX_BUCKET_COUNT = 1 shl Byte.SIZE_BITS

internal const val RADIX_BUCKET_MASK = RADIX_BUCKET_COUNT - 1L

internal const val SIGNED_LONG_RADIX_SHIFT = (Long.SIZE_BYTES - 1) * Byte.SIZE_BITS

internal const val SIGNED_RADIX_BUCKET_FLIP = 1 shl (Byte.SIZE_BITS - 1)

internal const val MAX_SERIAL_CALL_SITE_STRING_INDEX_SCAN_BYTES = 1024L * 1024

internal const val MAX_CALL_SITE_STRING_MATCH_CACHE_ENTRIES = 32

internal const val MAX_CALL_SITE_STRING_MATCH_CACHE_BYTES = 2L * 1024 * 1024

internal const val CALL_SITE_STRING_MATCH_CACHE_LOAD_FACTOR = 0.75f

internal const val CALL_SITE_STRING_MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 64L

internal const val CALL_SITE_STRING_MATCH_CACHE_STRING_HEADER_ESTIMATED_BYTES = 24L

internal const val MAX_CALL_SITE_NODE_MATCH_CACHE_ENTRIES = 32

internal const val MAX_CALL_SITE_NODE_MATCH_CACHE_BYTES = 2L * 1024 * 1024

internal const val MAX_CALL_SITE_NODE_MATCH_CACHE_LIMIT = 200

internal const val CALL_SITE_NODE_MATCH_CACHE_ENTRY_ESTIMATED_BYTES = 128L

internal const val MAX_CALL_SITE_PROJECTION_CACHE_ENTRIES = 32

internal const val MAX_CALL_SITE_PROJECTION_CACHE_BYTES = 2L * 1024 * 1024

internal const val CALL_SITE_PROJECTION_CACHE_ENTRY_ESTIMATED_BYTES = 128L

internal const val CALL_SITE_PROJECTION_ROW_ESTIMATED_BYTES = 64L

internal const val CALL_SITE_PROJECTION_STRING_ESTIMATED_BYTES = 24L

internal const val REFERENCE_ESTIMATED_BYTES = 8L

internal const val INITIAL_POSTING_RANGES = 16

internal const val BITSET_WORD_SHIFT = 6

internal const val BITSET_WORD_MASK = Long.SIZE_BITS - 1

internal const val SPARSE_DISTINCT_RANDOM_READ_FACTOR = 4L

internal const val MIN_CALL_SITE_TRIGRAM_LENGTH = 3

internal const val MIN_PARALLEL_CALL_SITE_MATCH_CANDIDATES = 4_096

internal const val MIN_PARALLEL_CALL_SITE_TRIGRAM_STRINGS = 4_096

internal const val MIN_EXACT_CALL_SITE_PROJECTION_TUPLES = 4_096

internal const val MIN_SELECTED_VALUES_FOR_EXACT_PROJECTION_INDEX = 256

internal const val EXACT_CALL_SITE_PROJECTION_TUPLE_INDEX_ESTIMATED_BYTES = 128L

internal const val CALL_SITE_PROJECTION_TUPLE_HASH_SEED = -3750763034362895579L

internal const val CALL_SITE_PROJECTION_TUPLE_HASH_FACTOR = 1099511628211L

internal const val CALL_SITE_STRING_MATCH_INTERRUPTED = "CallSite string candidate match interrupted"

internal const val CALL_SITE_TRIGRAM_BUILD_ABORTED = "CallSite trigram metadata build aborted"

internal const val CALL_SITE_TRIGRAM_BUILD_INTERRUPTED = "CallSite trigram metadata build interrupted"

internal const val CALL_SITE_TRIGRAM_SORT_ABORTED = "CallSite trigram posting sort aborted"

internal const val ASCII_LIMIT = 128

internal const val CALL_SITE_STRING_HASH_FACTOR = 31

internal const val TRIGRAM_SIGNATURE_MASK = Long.SIZE_BITS - 1

internal const val INT_UNSIGNED_MASK = 0xffff_ffffL

internal const val CALL_SITE_TRIGRAM_INDEX_BUDGET_PROPERTY = "graphite.webgraph.callSiteTrigramIndexBudgetBytes"

internal const val DEFAULT_CALL_SITE_TRIGRAM_HEAP_DIVISOR = 8L

internal const val CALL_SITE_INDEX_BYTE_MASK = 0xff
internal const val CALL_SITE_TRIGRAM_MIX_RIGHT_SHIFT = 11
internal const val CALL_SITE_TRIGRAM_MIX_LEFT_SHIFT = 7
internal const val CALL_SITE_TUPLE_MIX_SHIFT = 33
internal const val CALL_SITE_TUPLE_MIX_FIRST_FACTOR = -49064778989728563L
internal const val CALL_SITE_TUPLE_MIX_SECOND_FACTOR = -4265267296055464877L
