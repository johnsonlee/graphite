package io.johnsonlee.graphite.webgraph

import it.unimi.dsi.lang.MutableString
import java.io.Closeable
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
 * when the sidecar it mapped carries the same identity and directory sizes. Their exact bytes
 * are reserved from the shared CallSite index budget before anything is allocated, a graph that
 * cannot reserve them keeps the directory search, and the reservation is released with the
 * graph.
 */
internal class CallSiteDirectoryHashes private constructor(
    private val identity: ByteArray,
    private val stringCount: Int,
    private val uniqueCounts: IntArray,
    private val keys: Array<IntArray>,
    private val rows: Array<IntArray>,
    private val stringIds: Array<IntArray>,
    private val reservation: MappedCallSiteStringIndexMemoryBudget.Reservation
) : Closeable {
    /** Heap bytes of the tables, as reserved from the shared index budget. */
    val retainedBytes: Long
        get() = reservation.bytes

    @Volatile
    private var closed = false

    /** True when a view mapped the sidecar these tables were built from. */
    fun matches(identity: ByteArray, stringCount: Int, uniqueCounts: IntArray): Boolean =
        !closed &&
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

    /** Releases the budget reservation; the tables are no longer offered to a view. */
    override fun close() {
        if (closed) return
        closed = true
        reservation.close()
    }

    private fun decodesTo(stringId: Int, value: String, stringTable: StringTable, decoded: MutableString): Boolean {
        stringTable.get(stringId, decoded)
        return decoded.compareTo(value) == 0
    }

    companion object {
        /**
         * Reads the directories of the sidecar at [path] and builds the tables, or returns null
         * when there is no sidecar, its header does not describe [stringTable], a directory is
         * not a strictly ascending id list, or the shared index budget cannot hold the tables.
         * Postings are left to the view's own validation.
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

        @Suppress("TooGenericExceptionCaught")
        private fun readDirectories(mapped: ByteBuffer, stringTable: StringTable): CallSiteDirectoryHashes? {
            require(mapped.int == CALL_SITE_STRING_INDEX_MAGIC)
            require(mapped.int == CALL_SITE_STRING_INDEX_VERSION)
            val stringCount = mapped.int
            val callSiteCount = mapped.int
            require(stringCount == stringTable.size() && callSiteCount > 0)
            val identity = ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES)
            mapped.get(identity)
            val uniqueCounts = IntArray(CALL_SITE_STRING_PROPERTY_COUNT) { mapped.int }
            require(uniqueCounts.all { count -> count in 0..stringCount })
            val reservation = MappedCallSiteStringIndexMemoryBudget.tryReserve(tableBytes(uniqueCounts)) ?: return null
            try {
                return CallSiteDirectoryHashes(
                    identity,
                    stringCount,
                    uniqueCounts,
                    keys = Array(CALL_SITE_STRING_PROPERTY_COUNT) { NO_INTS },
                    rows = Array(CALL_SITE_STRING_PROPERTY_COUNT) { NO_INTS },
                    stringIds = readStringIds(mapped, stringCount, callSiteCount, uniqueCounts),
                    reservation = reservation
                ).also { hashes -> hashes.fillTables(stringTable) }
            } catch (error: Exception) {
                reservation.close()
                throw error
            }
        }

        private fun readStringIds(mapped: ByteBuffer, stringCount: Int, callSiteCount: Int, uniqueCounts: IntArray): Array<IntArray> {
            var offset = CALL_SITE_STRING_INDEX_HEADER_BYTES
            return Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
                val count = uniqueCounts[propertyIndex]
                val ids = IntArray(count)
                var previous = -1
                for (row in 0 until count) {
                    val id = mapped.getInt(offset + row * Int.SIZE_BYTES)
                    require(id > previous && id < stringCount)
                    ids[row] = id
                    previous = id
                }
                // The directory, its posting ends, and the posting node ids of the property.
                offset = Math.addExact(offset, Math.multiplyExact(2 * count + callSiteCount, Int.SIZE_BYTES))
                ids
            }
        }

        /** Exact heap bytes of the arrays the tables allocate for [uniqueCounts] directory rows. */
        internal fun tableBytes(uniqueCounts: IntArray): Long {
            var bytes = OBJECT_ESTIMATED_BYTES + arrayBytes(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES.toLong())
            for (count in uniqueCounts) {
                val capacity = if (count == 0) 0L else tableCapacity(count).toLong()
                bytes += arrayBytes(count.toLong() * Int.SIZE_BYTES) + 2 * arrayBytes(capacity * Int.SIZE_BYTES)
            }
            return bytes
        }

        private fun arrayBytes(payload: Long): Long = ARRAY_HEADER_BYTES + payload

        private fun tableCapacity(count: Int): Int {
            var capacity = MIN_TABLE_CAPACITY
            while (capacity < count * 2) capacity = capacity shl 1
            return capacity
        }

        private fun spread(hash: Int): Int = hash xor (hash ushr HASH_SPREAD_SHIFT)

        private const val MIN_TABLE_CAPACITY = 4
        private const val HASH_SPREAD_SHIFT = 16
        private const val ARRAY_HEADER_BYTES = 16L
        private const val OBJECT_ESTIMATED_BYTES = 128L
        private val NO_INTS = IntArray(0)
    }

    /** Builds the four tables in parallel, one decode per directory row, at most one-half load factor. */
    private fun fillTables(stringTable: StringTable) {
        val tables = Array(CALL_SITE_STRING_PROPERTY_COUNT) { propertyIndex ->
            CompletableFuture.supplyAsync { hashTable(stringIds[propertyIndex], stringTable) }
        }
        for (propertyIndex in tables.indices) {
            val (propertyKeys, propertyRows) = tables[propertyIndex].join()
            keys[propertyIndex] = propertyKeys
            rows[propertyIndex] = propertyRows
        }
    }

    private fun hashTable(ids: IntArray, stringTable: StringTable): Pair<IntArray, IntArray> {
        if (ids.isEmpty()) return NO_INTS to NO_INTS
        val capacity = tableCapacity(ids.size)
        val propertyKeys = IntArray(capacity)
        val propertyRows = IntArray(capacity)
        val mask = capacity - 1
        for (row in ids.indices) {
            val hash = stringTable.get(ids[row]).hashCode()
            var slot = spread(hash) and mask
            while (propertyRows[slot] != 0) slot = (slot + 1) and mask
            propertyKeys[slot] = hash
            propertyRows[slot] = row + 1
        }
        return propertyKeys to propertyRows
    }
}
