package io.johnsonlee.graphite.webgraph

import it.unimi.dsi.lang.MutableString
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Exact value-to-row tables over the four CallSite property directories of a persisted sidecar,
 * built once while the graph is mapped. A cross-graph DISTINCT request resolves every selected
 * value in every graph; a binary search of a directory decodes a front-coded prefix chain per
 * step, while a table probe hashes the value once per request and decodes only the row it lands
 * on. The tables are graph-owned load-time state: they survive the view being closed between
 * requests, cost the mapped load one decode per directory row, and are adopted by a view only
 * when the sidecar it mapped carries the same identity and directory sizes.
 */
internal class CallSiteDirectoryHashes private constructor(
    private val identity: ByteArray,
    private val stringCount: Int,
    private val uniqueCounts: IntArray,
    private val keys: Array<IntArray>,
    private val rows: Array<IntArray>,
    private val stringIds: Array<IntArray>
) {
    /** Heap bytes of the tables, for diagnostics. */
    val retainedBytes: Long = identity.size.toLong() +
        (keys.sumOf { table -> table.size.toLong() } + rows.sumOf { table -> table.size.toLong() } +
            stringIds.sumOf { ids -> ids.size.toLong() }) * Int.SIZE_BYTES

    /** True when a view mapped the sidecar these tables were built from. */
    fun matches(identity: ByteArray, stringCount: Int, uniqueCounts: IntArray): Boolean =
        this.stringCount == stringCount &&
            this.uniqueCounts.contentEquals(uniqueCounts) &&
            this.identity.contentEquals(identity)

    /**
     * Directory row of [value] in [propertyIndex], or -1 when no CallSite carries it there. A key
     * match decodes its row into [decoded] through [stringTable] and compares the characters, so
     * a hash collision costs one decode and never a wrong row.
     */
    fun row(propertyIndex: Int, value: String, stringTable: StringTable, decoded: MutableString): Int {
        val propertyKeys = keys[propertyIndex]
        if (propertyKeys.isEmpty()) return -1
        val propertyRows = rows[propertyIndex]
        val ids = stringIds[propertyIndex]
        val mask = propertyKeys.size - 1
        val hash = value.hashCode()
        var slot = spread(hash) and mask
        var row = propertyRows[slot] - 1
        while (row >= 0 && !(propertyKeys[slot] == hash && decodesTo(ids[row], value, stringTable, decoded))) {
            slot = (slot + 1) and mask
            row = propertyRows[slot] - 1
        }
        return row
    }

    private fun decodesTo(stringId: Int, value: String, stringTable: StringTable, decoded: MutableString): Boolean {
        stringTable.get(stringId, decoded)
        return decoded.compareTo(value) == 0
    }

    companion object {
        /**
         * Reads the directories of the sidecar at [path] and builds the tables, or returns null
         * when there is no sidecar, its header does not describe [stringTable], or a directory is
         * not a strictly ascending id list. Postings are left to the view's own validation.
         */
        @Suppress("TooGenericExceptionCaught")
        fun load(path: Path, stringTable: StringTable): CallSiteDirectoryHashes? {
            if (!Files.isRegularFile(path)) return null
            return try {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val fileBytes = channel.size()
                    require(fileBytes in CALL_SITE_STRING_INDEX_HEADER_BYTES..Int.MAX_VALUE.toLong())
                    val mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, fileBytes).order(ByteOrder.BIG_ENDIAN)
                    readDirectories(mapped, stringTable)
                }
            } catch (error: Exception) {
                when (error) {
                    is IOException, is IllegalArgumentException, is ArithmeticException,
                    is BufferUnderflowException, is IndexOutOfBoundsException, is CompletionException -> null
                    else -> throw error
                }
            }
        }

        private fun readDirectories(mapped: ByteBuffer, stringTable: StringTable): CallSiteDirectoryHashes {
            require(mapped.int == CALL_SITE_STRING_INDEX_MAGIC)
            require(mapped.int == CALL_SITE_STRING_INDEX_VERSION)
            val stringCount = mapped.int
            val callSiteCount = mapped.int
            require(stringCount == stringTable.size() && callSiteCount > 0)
            val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
            mapped.get(identity)
            val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { mapped.int }
            require(uniqueCounts.all { count -> count in 0..stringCount })
            val stringIds = Array(CALL_SITE_STRING_PROPERTY_COUNT) { IntArray(0) }
            var offset = CALL_SITE_STRING_INDEX_HEADER_BYTES
            repeat(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                val count = uniqueCounts[propertyIndex]
                val ids = IntArray(count)
                var previous = -1
                for (row in 0 until count) {
                    val id = mapped.getInt(offset + row * Int.SIZE_BYTES)
                    require(id > previous && id < stringCount)
                    ids[row] = id
                    previous = id
                }
                stringIds[propertyIndex] = ids
                // The directory, its posting ends, and the posting node ids of the property.
                offset = Math.addExact(offset, Math.multiplyExact(2 * count + callSiteCount, Int.SIZE_BYTES))
            }
            val tables = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                CompletableFuture.supplyAsync { hashTable(stringIds[propertyIndex], stringTable) }
            }
            return CallSiteDirectoryHashes(
                identity,
                stringCount,
                uniqueCounts,
                Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> tables[propertyIndex].join().first },
                Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex -> tables[propertyIndex].join().second },
                stringIds
            )
        }

        /** Open-addressing table of the string hashes of [ids] at a load factor of at most one half. */
        private fun hashTable(ids: IntArray, stringTable: StringTable): Pair<IntArray, IntArray> {
            if (ids.isEmpty()) return NO_INTS to NO_INTS
            var capacity = MIN_TABLE_CAPACITY
            while (capacity < ids.size * 2) capacity = capacity shl 1
            val keys = IntArray(capacity)
            val rows = IntArray(capacity)
            val mask = capacity - 1
            for (row in ids.indices) {
                val hash = stringTable.get(ids[row]).hashCode()
                var slot = spread(hash) and mask
                while (rows[slot] != 0) slot = (slot + 1) and mask
                keys[slot] = hash
                rows[slot] = row + 1
            }
            return keys to rows
        }

        private fun spread(hash: Int): Int = hash xor (hash ushr HASH_SPREAD_SHIFT)

        private const val MIN_TABLE_CAPACITY = 4
        private const val HASH_SPREAD_SHIFT = 16
        private val NO_INTS = IntArray(0)
    }
}
