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

/**
 * Compact mapped directory over the trigram postings in [MappedCallSiteStringIndex]. It validates
 * the small directory eagerly and only the exact posting range used by a query, so a cold lookup
 * never walks every posting or duplicates the posting data on disk.
 */
internal class MappedCallSiteTrigramPrefilter private constructor(
    private val ranges: IntBuffer,
    private val trigramPostings: LongBuffer,
    private val propertyStringIds: Array<IntBuffer>,
    private val stringCount: Int,
    private val stringTable: StringTable
) {
    fun containsPropertyStringId(propertyIndex: Int, stringId: Int): Boolean =
        propertyIndex in propertyStringIds.indices && binarySearch(propertyStringIds[propertyIndex], stringId)

    fun exactMatchingStringIds(
        predicates: List<StringPropertyPredicate>,
        workConsumer: GraphWorkConsumer?
    ): List<IntArray>? {
        if (predicates.any { predicate -> !predicate.canUseMappedTrigramPrefilter() }) return null
        val matchesByPredicate = mutableMapOf<PrefilterPredicateKey, IntArray>()
        return predicates.map { predicate ->
            if (callSiteStringPropertyIndex(predicate.property) < 0) return null
            val key = PrefilterPredicateKey(predicate.transform, predicate.mode, predicate.expected)
            matchesByPredicate[key] ?: exactMatchingStringIds(predicate, workConsumer)
                ?.also { matches -> matchesByPredicate[key] = matches }
                ?: return null
        }
    }

    private fun exactMatchingStringIds(
        predicate: StringPropertyPredicate,
        workConsumer: GraphWorkConsumer?
    ): IntArray? {
        if (callSiteStringPropertyIndex(predicate.property) < 0) return null
        val expected = if (predicate.transform == null) predicate.expected.lowercase() else predicate.expected
        val matchingRanges = mutableListOf<TrigramRange>()
        val seen = HashSet<Int>()
        val accounting = BufferedGraphWorkConsumer(workConsumer)
        try {
            for (position in 0..expected.length - TRIGRAM_LENGTH) {
                val trigram = trigramHash(expected, position)
                if (!seen.add(trigram)) continue
                accounting.consume()
                val range = findRange(trigram) ?: return IntArray(0)
                matchingRanges += range
            }
            if (matchingRanges.isEmpty()) return null
            val anchor = matchingRanges.minBy(TrigramRange::size)
            val checksum = CRC32()
            val actual = MutableString()
            val matches = IntArray(anchor.size)
            var matchCount = 0
            for (postingIndex in anchor.start until anchor.end) {
                if ((postingIndex and PREFILTER_INTERRUPTION_POLL_MASK) == 0) checkInterrupted()
                accounting.consume()
                val posting = trigramPostings.get(postingIndex)
                checksum.updatePrefilterLongBigEndian(posting)
                if ((posting ushr Int.SIZE_BITS).toInt() != anchor.trigram) return null
                val stringId = posting.toInt()
                if (stringId !in 0 until stringCount) return null
                stringTable.get(stringId, actual)
                val value = actual.toString()
                val compared = if (predicate.transform == StringValueTransform.LOWERCASE) value.lowercase() else value
                if (compared.contains(predicate.expected)) matches[matchCount++] = stringId
            }
            if (checksum.value != anchor.checksum) return null
            return matches.copyOf(matchCount)
        } finally {
            accounting.flush()
        }
    }

    private fun findRange(trigram: Int): TrigramRange? {
        var low = 0
        var high = ranges.limit() / RANGE_ENTRY_INTS - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val offset = middle * RANGE_ENTRY_INTS
            val value = ranges.get(offset)
            when {
                value < trigram -> low = middle + 1
                value > trigram -> high = middle - 1
                else -> {
                    val end = ranges.get(offset + RANGE_END_INDEX)
                    val start = if (middle == 0) 0 else ranges.get(offset - RANGE_ENTRY_INTS + RANGE_END_INDEX)
                    val checksum = ranges.get(offset + RANGE_CHECKSUM_INDEX).toLong() and PREFILTER_UNSIGNED_INT_MASK
                    return TrigramRange(trigram, start, end, checksum)
                }
            }
        }
        return null
    }

    companion object {
        @Suppress("LongMethod", "TooGenericExceptionCaught")
        fun load(
            directoryPath: Path,
            exactIndexPath: Path,
            expectedStringCount: Int,
            expectedCallSiteCount: Int,
            expectedContentIdentity: ByteArray,
            stringTable: StringTable,
            workConsumer: GraphWorkConsumer?
        ): MappedCallSiteTrigramPrefilter? {
            if (!Files.isRegularFile(directoryPath) || !Files.isRegularFile(exactIndexPath) ||
                expectedContentIdentity.size != CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES
            ) return null
            checkInterrupted()
            return try {
                FileChannel.open(directoryPath, StandardOpenOption.READ).use { directoryChannel ->
                    val directoryBytes = directoryChannel.size()
                    require(directoryBytes in MIN_DIRECTORY_BYTES..Int.MAX_VALUE.toLong())
                    val directory = directoryChannel.map(FileChannel.MapMode.READ_ONLY, 0L, directoryBytes)
                        .order(ByteOrder.BIG_ENDIAN)
                    require(directory.int == CALL_SITE_TRIGRAM_PREFILTER_MAGIC)
                    require(directory.int == CALL_SITE_TRIGRAM_PREFILTER_VERSION)
                    val stringCount = directory.int
                    val callSiteCount = directory.int
                    val postingCount = directory.int
                    val rangeCount = directory.int
                    val exactIndexBytes = directory.long
                    val postingsOffset = directory.long
                    require(stringCount == expectedStringCount && callSiteCount == expectedCallSiteCount)
                    require(postingCount > 0 && rangeCount in 1..postingCount)
                    val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
                    directory.get(identity)
                    require(identity.contentEquals(expectedContentIdentity))
                    val propertyChecksums = LongArray(CALL_SITE_STRING_PROPERTY_COUNT) {
                        directory.int.toLong() and PREFILTER_UNSIGNED_INT_MASK
                    }
                    val expectedDirectoryBytes = DIRECTORY_HEADER_BYTES.toLong() +
                        rangeCount.toLong() * RANGE_ENTRY_BYTES + Long.SIZE_BYTES
                    require(directoryBytes == expectedDirectoryBytes)
                    require(validateDirectoryChecksum(directory, directoryBytes.toInt(), workConsumer))

                    directory.position(DIRECTORY_HEADER_BYTES)
                    val rangeValues = directory.slice().order(ByteOrder.BIG_ENDIAN).asIntBuffer().apply {
                        limit(rangeCount * RANGE_ENTRY_INTS)
                    }
                    validateRanges(rangeValues, postingCount, workConsumer)

                    FileChannel.open(exactIndexPath, StandardOpenOption.READ).use { indexChannel ->
                        require(indexChannel.size() == exactIndexBytes)
                        val uniqueCounts = validateExactIndexHeader(
                            indexChannel,
                            exactIndexBytes,
                            postingsOffset,
                            stringCount,
                            callSiteCount,
                            postingCount,
                            expectedContentIdentity
                        )
                        val propertyStrings = mapPropertyStringIds(
                            indexChannel,
                            uniqueCounts,
                            callSiteCount,
                            stringCount,
                            propertyChecksums,
                            workConsumer
                        )
                        val postingBytes = postingCount.toLong() * Long.SIZE_BYTES
                        val postings = indexChannel.map(
                            FileChannel.MapMode.READ_ONLY,
                            postingsOffset,
                            postingBytes
                        ).order(ByteOrder.BIG_ENDIAN).asLongBuffer()
                        MappedCallSiteTrigramPrefilter(
                            rangeValues.asReadOnlyBuffer(),
                            postings.asReadOnlyBuffer(),
                            propertyStrings,
                            stringCount,
                            stringTable
                        )
                    }
                }
            } catch (error: Exception) {
                when (error) {
                    is CancellationException -> throw error
                    is IOException, is IllegalArgumentException, is BufferUnderflowException -> null
                    else -> throw error
                }
            }
        }

        private fun validateDirectoryChecksum(
            directory: ByteBuffer,
            directoryBytes: Int,
            workConsumer: GraphWorkConsumer?
        ): Boolean {
            val dataBytes = directoryBytes - Long.SIZE_BYTES
            val expected = directory.getLong(dataBytes)
            val checksum = CRC32()
            val input = directory.duplicate().apply { position(0); limit(dataBytes) }
            while (input.hasRemaining()) {
                checkInterrupted()
                val bytes = minOf(DIRECTORY_CHECKSUM_CHUNK_BYTES, input.remaining())
                val chunk = input.slice().apply { limit(bytes) }
                checksum.update(chunk)
                input.position(input.position() + bytes)
                consumeGraphWork(workConsumer, (bytes + Long.SIZE_BYTES - 1L) / Long.SIZE_BYTES)
            }
            return checksum.value == expected
        }

        private fun validateRanges(ranges: IntBuffer, postingCount: Int, workConsumer: GraphWorkConsumer?) {
            var previousTrigram: Int? = null
            var previousEnd = 0
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            try {
                var offset = 0
                while (offset < ranges.limit()) {
                    if ((offset and PREFILTER_INTERRUPTION_POLL_MASK) == 0) checkInterrupted()
                    accounting.consume()
                    val trigram = ranges.get(offset)
                    val end = ranges.get(offset + RANGE_END_INDEX)
                    require(previousTrigram == null || trigram > checkNotNull(previousTrigram))
                    require(end in (previousEnd + 1)..postingCount)
                    previousTrigram = trigram
                    previousEnd = end
                    offset += RANGE_ENTRY_INTS
                }
                require(previousEnd == postingCount)
            } finally {
                accounting.flush()
            }
        }

        private fun validateExactIndexHeader(
            channel: FileChannel,
            exactIndexBytes: Long,
            postingsOffset: Long,
            stringCount: Int,
            callSiteCount: Int,
            postingCount: Int,
            expectedContentIdentity: ByteArray
        ): IntArray {
            require(postingsOffset >= CALL_SITE_STRING_INDEX_HEADER_BYTES)
            require(exactIndexBytes == postingsOffset + postingCount.toLong() * Long.SIZE_BYTES + Long.SIZE_BYTES)
            val header = channel.map(
                FileChannel.MapMode.READ_ONLY,
                0L,
                CALL_SITE_STRING_INDEX_HEADER_BYTES.toLong()
            ).order(ByteOrder.BIG_ENDIAN)
            require(header.int == CALL_SITE_STRING_INDEX_MAGIC)
            require(header.int == CALL_SITE_STRING_INDEX_VERSION)
            require(header.int == stringCount)
            require(header.int == callSiteCount)
            val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
            header.get(identity)
            require(identity.contentEquals(expectedContentIdentity))
            val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { header.int }
            require(uniqueCounts.all { count -> count in 0..stringCount })
            require(header.int == postingCount)
            require(header.long > 0L)
            val expectedOffset = CALL_SITE_STRING_INDEX_HEADER_BYTES.toLong() +
                uniqueCounts.sumOf { count -> count.toLong() * 2L * Int.SIZE_BYTES } +
                callSiteCount.toLong() * CALL_SITE_STRING_PROPERTY_COUNT * Int.SIZE_BYTES +
                stringCount.toLong() * Long.SIZE_BYTES
            require(postingsOffset == expectedOffset)
            return uniqueCounts
        }

        private fun mapPropertyStringIds(
            channel: FileChannel,
            uniqueCounts: IntArray,
            callSiteCount: Int,
            stringCount: Int,
            expectedChecksums: LongArray,
            workConsumer: GraphWorkConsumer?
        ): Array<IntBuffer> {
            var offset = CALL_SITE_STRING_INDEX_HEADER_BYTES.toLong()
            return Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                val count = uniqueCounts[propertyIndex]
                val bytes = count.toLong() * Int.SIZE_BYTES
                val values = if (count == 0) {
                    IntBuffer.allocate(0)
                } else {
                    channel.map(FileChannel.MapMode.READ_ONLY, offset, bytes)
                        .order(ByteOrder.BIG_ENDIAN).asIntBuffer()
                }
                validatePropertyStringIds(values, stringCount, expectedChecksums[propertyIndex], workConsumer)
                offset += bytes * 2L + callSiteCount.toLong() * Int.SIZE_BYTES
                values.asReadOnlyBuffer()
            }
        }

        private fun validatePropertyStringIds(
            values: IntBuffer,
            stringCount: Int,
            expectedChecksum: Long,
            workConsumer: GraphWorkConsumer?
        ) {
            val checksum = CRC32()
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            var previous = -1
            try {
                for (index in 0 until values.limit()) {
                    if ((index and PREFILTER_INTERRUPTION_POLL_MASK) == 0) checkInterrupted()
                    accounting.consume()
                    val value = values.get(index)
                    require(value in 0 until stringCount && value > previous)
                    checksum.updatePrefilterIntBigEndian(value)
                    previous = value
                }
                require(checksum.value == expectedChecksum)
            } finally {
                accounting.flush()
            }
        }
    }
}

internal fun persistCallSiteTrigramPrefilter(
    index: MappedCallSiteStringIndex,
    exactIndexPath: Path,
    directoryPath: Path
): Boolean {
    var temporary: Path? = null
    return try {
        val exactIndexBytes = Files.size(exactIndexPath)
        temporary = Files.createTempFile(directoryPath.parent, directoryPath.fileName.toString(), ".tmp")
        BufferedOutputStream(Files.newOutputStream(checkNotNull(temporary)), DIRECTORY_IO_BUFFER_BYTES).use { output ->
            val checksum = CRC32()
            val data = DataOutputStream(CheckedOutputStream(output, checksum))
            index.writePersistentTrigramDirectory(data, exactIndexBytes)
            data.flush()
            ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).putLong(checksum.value).also {
                output.write(it.array())
            }
        }
        replaceCallSiteTrigramPrefilter(checkNotNull(temporary), directoryPath)
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

private fun binarySearch(values: IntBuffer, target: Int): Boolean {
    var low = 0
    var high = values.limit() - 1
    while (low <= high) {
        val middle = (low + high).ushr(1)
        val value = values.get(middle)
        when {
            value < target -> low = middle + 1
            value > target -> high = middle - 1
            else -> return true
        }
    }
    return false
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

private fun CRC32.updatePrefilterLongBigEndian(value: Long) {
    repeat(Long.SIZE_BYTES) { byteIndex ->
        update(((value ushr ((Long.SIZE_BYTES - 1 - byteIndex) * Byte.SIZE_BITS)) and 0xffL).toInt())
    }
}

private fun CRC32.updatePrefilterIntBigEndian(value: Int) {
    repeat(Int.SIZE_BYTES) { byteIndex ->
        update(value ushr ((Int.SIZE_BYTES - 1 - byteIndex) * Byte.SIZE_BITS) and 0xff)
    }
}

private data class TrigramRange(
    val trigram: Int,
    val start: Int,
    val end: Int,
    val checksum: Long
) {
    val size: Int get() = end - start
}

private data class PrefilterPredicateKey(
    val transform: StringValueTransform?,
    val mode: StringMatchMode,
    val expected: String
)

internal const val CALL_SITE_TRIGRAM_PREFILTER_MAGIC = 0x47525450
internal const val CALL_SITE_TRIGRAM_PREFILTER_VERSION = 3
private const val TRIGRAM_LENGTH = 3
private const val DIRECTORY_HEADER_BYTES =
    (6 + CALL_SITE_STRING_PROPERTY_COUNT) * Int.SIZE_BYTES + 2 * Long.SIZE_BYTES +
        CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES
private const val RANGE_ENTRY_INTS = 3
private const val RANGE_ENTRY_BYTES = RANGE_ENTRY_INTS * Int.SIZE_BYTES
private const val RANGE_END_INDEX = 1
private const val RANGE_CHECKSUM_INDEX = 2
private const val MIN_DIRECTORY_BYTES = DIRECTORY_HEADER_BYTES + RANGE_ENTRY_BYTES + Long.SIZE_BYTES
private const val DIRECTORY_IO_BUFFER_BYTES = 1 shl 20
private const val DIRECTORY_CHECKSUM_CHUNK_BYTES = 8 * 1024
private const val PREFILTER_INTERRUPTION_POLL_MASK = 1_023
private const val STRING_HASH_FACTOR = 31
private const val ASCII_MAX = 0x7f
private const val PREFILTER_UNSIGNED_INT_MASK = 0xffff_ffffL
