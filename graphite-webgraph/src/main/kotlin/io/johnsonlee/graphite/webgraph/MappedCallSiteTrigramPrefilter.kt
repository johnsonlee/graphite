@file:Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount"
)

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.StringValueTransform
import it.unimi.dsi.lang.MutableString
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream

/** Persisted string dictionary lookup that avoids restoring node postings before segmented raw scans. */
internal class MappedCallSiteTrigramPrefilter private constructor(
    private val usedStringIds: Array<IntBuffer>,
    private val trigramPostings: LongBuffer,
    private val stringTable: StringTable
) {
    fun exactMatchingStringIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): List<IntArray>? {
        if (predicates.any { predicate -> !predicate.canUseMappedTrigramPrefilter() }) return null
        return predicates.map { predicate -> exactMatchingStringIds(predicate, workConsumer) ?: return null }
    }

    private fun exactMatchingStringIds(
        predicate: StringPropertyPredicate,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        val propertyIndex = callSiteStringPropertyIndex(predicate.property)
        if (propertyIndex < 0) return null
        val expected = if (predicate.transform == null) predicate.expected.lowercase() else predicate.expected
        val ranges = mutableListOf<TrigramRange>()
        val seen = HashSet<Int>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (position in 0..expected.length - TRIGRAM_LENGTH) {
                val trigram = trigramHash(expected, position)
                if (!seen.add(trigram)) continue
                accounting.consume()
                val start = lowerBound(trigramPostings, trigramKey(trigram, 0))
                accounting.consume()
                val end = upperBound(trigramPostings, trigramKey(trigram, -1))
                if (start == end) return IntArray(0)
                ranges += TrigramRange(start, end)
            }
            if (ranges.isEmpty()) return null
            ranges.sortBy(TrigramRange::size)
            val anchor = ranges.first()
            val propertyStrings = usedStringIds[propertyIndex]
            val actual = MutableString()
            val matches = IntArray(anchor.size)
            var matchCount = 0
            for (postingIndex in anchor.start until anchor.end) {
                if ((postingIndex and PREFILTER_INTERRUPTION_POLL_MASK) == 0) checkInterrupted()
                accounting.consume()
                val stringId = trigramPostings.get(postingIndex).toInt()
                if (binarySearch(propertyStrings, stringId) < 0) continue
                stringTable.get(stringId, actual)
                val value = actual.toString()
                val compared = if (predicate.transform == StringValueTransform.LOWERCASE) value.lowercase() else value
                if (compared.contains(predicate.expected)) matches[matchCount++] = stringId
            }
            return matches.copyOf(matchCount)
        } finally {
            accounting.flush()
        }
    }

    companion object {
        @Suppress("LongMethod", "TooGenericExceptionCaught")
        fun load(
            path: Path,
            expectedStringCount: Int,
            expectedContentIdentity: ByteArray,
            stringTable: StringTable,
            workConsumer: GraphWorkConsumer?
        ): MappedCallSiteTrigramPrefilter? {
            if (!Files.isRegularFile(path) ||
                expectedContentIdentity.size != CALL_SITE_TRIGRAM_PREFILTER_CONTENT_IDENTITY_BYTES
            ) return null
            checkInterrupted()
            return try {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val size = channel.size()
                    require(size in MIN_FILE_BYTES..Int.MAX_VALUE.toLong())
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size).order(ByteOrder.BIG_ENDIAN)
                    val magic = mapped.int
                    val version = mapped.int
                    val stringCount = mapped.int
                    require(magic == CALL_SITE_TRIGRAM_PREFILTER_MAGIC &&
                        version == CALL_SITE_TRIGRAM_PREFILTER_VERSION)
                    require(stringCount == expectedStringCount)
                    val identity = ByteArray(CALL_SITE_TRIGRAM_PREFILTER_CONTENT_IDENTITY_BYTES)
                    mapped.get(identity)
                    require(identity.contentEquals(expectedContentIdentity))
                    val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { mapped.int }
                    require(uniqueCounts.all { count -> count in 0..stringCount })
                    val postingCount = mapped.int
                    require(postingCount > 0)
                    val expectedBytes = expectedFileBytes(uniqueCounts, postingCount)
                    require(expectedBytes == size)
                    val checksumInput = mapped.duplicate().order(ByteOrder.BIG_ENDIAN)
                    checksumInput.position(0)
                    checksumInput.limit((size - Long.SIZE_BYTES).toInt())
                    val checksum = CRC32().apply { update(checksumInput) }
                    require(mapped.getLong((size - Long.SIZE_BYTES).toInt()) == checksum.value)
                    consumeGraphWork(workConsumer, (size + Long.SIZE_BYTES - 1L) / Long.SIZE_BYTES)
                    checkInterrupted()
                    mapped.position(HEADER_BYTES)
                    val used = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                        val count = uniqueCounts[propertyIndex]
                        val values = mapped.slice().order(ByteOrder.BIG_ENDIAN).asIntBuffer().apply { limit(count) }
                        validateSortedStringIds(values, stringCount)
                        mapped.position(mapped.position() + count * Int.SIZE_BYTES)
                        values.asReadOnlyBuffer()
                    }
                    val postings = mapped.slice().order(ByteOrder.BIG_ENDIAN).asLongBuffer().apply {
                        limit(postingCount)
                    }
                    MappedCallSiteTrigramPrefilter(used, postings.asReadOnlyBuffer(), stringTable)
                }
            } catch (error: Exception) {
                when (error) {
                    is CancellationException -> throw error
                    is IOException, is IllegalArgumentException, is BufferUnderflowException -> null
                    else -> throw error
                }
            }
        }
        private fun expectedFileBytes(uniqueCounts: IntArray, postingCount: Int): Long =
            HEADER_BYTES.toLong() + uniqueCounts.sumOf { it.toLong() } * Int.SIZE_BYTES +
                postingCount.toLong() * Long.SIZE_BYTES + Long.SIZE_BYTES

        private fun validateSortedStringIds(values: IntBuffer, stringCount: Int) {
            var previous = -1
            for (index in 0 until values.limit()) {
                val value = values.get(index)
                require(value in 0 until stringCount && value > previous)
                previous = value
            }
        }

    }
}

internal fun persistCallSiteTrigramPrefilter(index: MappedCallSiteStringIndex, path: Path): Boolean {
    var temporary: Path? = null
    return try {
        temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
        BufferedOutputStream(Files.newOutputStream(checkNotNull(temporary)), PREFILTER_IO_BUFFER_BYTES).use { output ->
            val checksum = CRC32()
            val data = DataOutputStream(CheckedOutputStream(output, checksum))
            index.writePersistentTrigramPrefilter(data)
            data.flush()
            ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(checksum.value).also {
                output.write(it.array())
            }
        }
        replaceCallSiteTrigramPrefilter(checkNotNull(temporary), path)
        temporary = null
        true
    } catch (_: Exception) {
        false
    } finally {
        temporary?.let { candidate -> runCatching { Files.deleteIfExists(candidate) } }
    }
}

private fun StringPropertyPredicate.canUseMappedTrigramPrefilter(): Boolean =
    mode == StringMatchMode.CONTAINS && expected.length >= TRIGRAM_LENGTH &&
        (transform == StringValueTransform.LOWERCASE || transform == null && expected.all { it.code <= ASCII_MAX })

private fun trigramHash(value: String, position: Int): Int {
    var hash = 0
    repeat(TRIGRAM_LENGTH) { offset -> hash = hash * STRING_HASH_FACTOR + value[position + offset].code }
    return hash
}

private fun trigramKey(trigram: Int, stringId: Int): Long =
    (trigram.toLong() shl Int.SIZE_BITS) or (stringId.toLong() and PREFILTER_UNSIGNED_INT_MASK)

private fun lowerBound(values: LongBuffer, target: Long): Int {
    var low = 0
    var high = values.limit()
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (values.get(middle) < target) low = middle + 1 else high = middle
    }
    return low
}

private fun upperBound(values: LongBuffer, target: Long): Int {
    var low = 0
    var high = values.limit()
    while (low < high) {
        val middle = (low + high).ushr(1)
        if (values.get(middle) <= target) low = middle + 1 else high = middle
    }
    return low
}

private fun binarySearch(values: IntBuffer, target: Int): Int {
    var low = 0
    var high = values.limit() - 1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        val value = values.get(middle)
        if (value < target) low = middle + 1 else if (value > target) high = middle - 1 else return middle
    }
    return -1
}

private fun replaceCallSiteTrigramPrefilter(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) {
        throw CancellationException("Mapped CallSite trigram prefilter interrupted")
    }
}

private data class TrigramRange(val start: Int, val end: Int) {
    val size: Int get() = end - start
}

internal const val CALL_SITE_TRIGRAM_PREFILTER_MAGIC = 0x47525450
internal const val CALL_SITE_TRIGRAM_PREFILTER_VERSION = 1
internal const val CALL_SITE_TRIGRAM_PREFILTER_CONTENT_IDENTITY_BYTES = 32
private const val TRIGRAM_LENGTH = 3
private const val HEADER_BYTES =
    3 * Int.SIZE_BYTES + CALL_SITE_TRIGRAM_PREFILTER_CONTENT_IDENTITY_BYTES +
        CALL_SITE_STRING_PROPERTY_COUNT * Int.SIZE_BYTES + Int.SIZE_BYTES
private const val MIN_FILE_BYTES = HEADER_BYTES + Long.SIZE_BYTES
private const val PREFILTER_IO_BUFFER_BYTES = 1 shl 20
private const val PREFILTER_INTERRUPTION_POLL_MASK = 1_023
private const val STRING_HASH_FACTOR = 31
private const val ASCII_MAX = 0x7f
private const val PREFILTER_UNSIGNED_INT_MASK = 0xffffffffL
