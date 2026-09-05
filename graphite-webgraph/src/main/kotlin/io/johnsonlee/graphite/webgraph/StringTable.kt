package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.lang.MutableString
import it.unimi.dsi.util.FrontCodedStringList
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * A string table backed by [FrontCodedStringList] (LAW/dsiutils).
 *
 * All strings in the graph are collected, sorted, deduplicated, and stored in a
 * front-coded list. Other data structures reference strings by their index in
 * this table, replacing inline UTF strings with 4-byte integer indices.
 *
 * Persistence uses [BinIO.storeObject]/[BinIO.loadObject] which leverages
 * [FrontCodedStringList]'s native Java serialization support (designed by the
 * LAW team for this purpose).
 */
internal class StringTable private constructor(
    private val list: FrontCodedStringList,
    private val indexMap: Map<String, Int>?,
    contentIdentity: ByteArray?
) {

    @Volatile
    private var persistedContentIdentity: ByteArray? = contentIdentity?.copyOf()
    private val contentIdentityLock = Any()

    /**
     * Returns the index of [s] in the string table, or -1 if not found.
     */
    fun indexOf(s: String): Int = indexMap?.get(s) ?: -1

    /** Finds an id in both builder and loaded tables; persisted tables remain sorted. */
    internal fun findId(s: String): Int {
        indexMap?.get(s)?.let { return it }
        return findId(s, 0, list.size)
    }

    /** Finds the id of [s] within the sorted id range `[fromIndex, toIndex)`, or -1. */
    internal fun findId(s: String, fromIndex: Int, toIndex: Int): Int {
        val reusable = MutableString()
        val index = lowerBoundSorted(s, reusable, null, fromIndex, toIndex)
        if (index >= toIndex) return -1
        list.get(index, reusable)
        return if (reusable.toString() == s) index else -1
    }

    /** First index whose string is not lexicographically smaller than [value]; sorted tables only. */
    internal fun lowerBound(value: String, workConsumer: GraphWorkConsumer? = null): Int =
        lowerBoundSorted(value, MutableString(), workConsumer, 0, list.size)

    /** True when the string at [index] starts with [prefix]. */
    internal fun startsWithAt(index: Int, prefix: String): Boolean {
        if (index !in 0 until list.size) return false
        val reusable = MutableString()
        list.get(index, reusable)
        return reusable.toString().startsWith(prefix)
    }

    /**
     * Front-coded blocks store their first string in full, so a search over block heads decodes
     * one string per probe. Only the winning block is then walked in order, which keeps a lookup
     * at roughly `log2(size / ratio) + ratio` decodes instead of `log2(size)` prefix chains.
     * Decoded strings are compared through the JDK's own comparison so a cold request never
     * interprets a character loop of its own.
     */
    @Suppress("LongParameterList")
    private fun lowerBoundSorted(
        value: String,
        reusable: MutableString,
        workConsumer: GraphWorkConsumer?,
        fromIndex: Int,
        toIndex: Int
    ): Int {
        if (fromIndex >= toIndex) return fromIndex
        val ratio = list.ratio()
        var probes = 0L
        // Block heads strictly inside the range; the range start acts as the head of its own block.
        var low = fromIndex / ratio
        var high = (toIndex - 1) / ratio
        while (low < high) {
            probes++
            val middle = (low + high + 1).ushr(1)
            list.get(middle * ratio, reusable)
            if (reusable.toString().compareTo(value) < 0) low = middle else high = middle - 1
        }
        var index = maxOf(low * ratio, fromIndex)
        val end = minOf((low + 1) * ratio, toIndex)
        while (index < end) {
            probes++
            list.get(index, reusable)
            if (reusable.toString().compareTo(value) >= 0) break
            index++
        }
        consumeGraphWork(workConsumer, probes)
        return index
    }

    /**
     * The contiguous id range of every string starting with [prefix] in a sorted table, or an empty
     * range. Strings sharing a prefix are adjacent because the table is sorted by code unit.
     */
    internal fun prefixRange(prefix: String, workConsumer: GraphWorkConsumer? = null): IntRange {
        if (prefix.isEmpty()) return 0 until list.size
        val start = lowerBound(prefix, workConsumer)
        val last = prefix[prefix.length - 1]
        val end = if (last == Char.MAX_VALUE) {
            val reusable = MutableString()
            var index = start
            while (index < list.size) {
                list.get(index, reusable)
                if (!reusable.startsWith(prefix)) break
                index++
            }
            consumeGraphWork(workConsumer, (index - start).toLong())
            index
        } else {
            lowerBound(prefix.substring(0, prefix.length - 1) + (last + 1), workConsumer)
        }
        return start until end
    }

    /**
     * Returns the string at the given [index].
     */
    fun get(index: Int): String = list.get(index).toString()

    /** Decodes into a reusable buffer for allocation-sensitive table scans. */
    internal fun get(index: Int, target: MutableString) = list.get(index, target)

    /**
     * Returns the number of strings in the table.
     */
    fun size(): Int = list.size

    /** Stable semantic identity of every ordered string-table entry. */
    internal fun contentIdentity(workConsumer: GraphWorkConsumer? = null): ByteArray {
        persistedContentIdentity?.let { return it.copyOf() }
        return synchronized(contentIdentityLock) {
            persistedContentIdentity?.let { return@synchronized it.copyOf() }
            semanticContentIdentity(list, workConsumer).also { identity ->
                persistedContentIdentity = identity
            }.copyOf()
        }
    }

    companion object {

        private const val FILE_NAME = "graph.strings"
        internal const val CONTENT_IDENTITY_FILE_NAME = "graph.strings.identity"
        private const val CONTENT_IDENTITY_BYTES = 32

        /**
         * Build a [StringTable] from a collection of strings and persist it to disk.
         *
         * Strings are sorted and deduplicated before building the front-coded list.
         * The ratio parameter controls the trade-off between compression and
         * random access speed.
         */
        fun build(strings: Collection<String>, dir: Path): StringTable {
            val sorted = strings.toSortedSet().toList()
            val fcl = FrontCodedStringList(sorted.iterator(), FRONT_CODED_STRING_RATIO, false)
            BinIO.storeObject(fcl, dir.resolve(FILE_NAME).toString())
            val contentIdentity = semanticContentIdentity(sorted)
            Files.write(dir.resolve(CONTENT_IDENTITY_FILE_NAME), contentIdentity)
            val indexMap = HashMap<String, Int>(sorted.size)
            for (i in sorted.indices) {
                indexMap[sorted[i]] = i
            }
            return StringTable(fcl, indexMap, contentIdentity)
        }

        /**
         * Load a previously persisted [StringTable] from disk.
         */
        fun load(dir: Path): StringTable {
            @Suppress("UNCHECKED_CAST")
            val fcl = BinIO.loadObject(dir.resolve(FILE_NAME).toString()) as FrontCodedStringList
            val contentIdentity = dir.resolve(CONTENT_IDENTITY_FILE_NAME).takeIf(Files::isRegularFile)
                ?.let(Files::readAllBytes)
                ?.takeIf { identity -> identity.size == CONTENT_IDENTITY_BYTES }
            return StringTable(fcl, null, contentIdentity)
        }

        private fun semanticContentIdentity(strings: List<String>): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.updateInt(strings.size)
            strings.forEach { value ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                digest.updateInt(bytes.size)
                digest.update(bytes)
            }
            return digest.digest()
        }

        private fun semanticContentIdentity(
            strings: FrontCodedStringList,
            workConsumer: GraphWorkConsumer?
        ): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.updateInt(strings.size)
            val reusable = MutableString()
            val accounting = BufferedGraphWorkConsumer(workConsumer)
            try {
                for (index in 0 until strings.size) {
                    accounting.consume()
                    strings.get(index, reusable)
                    val bytes = reusable.toString().toByteArray(StandardCharsets.UTF_8)
                    digest.updateInt(bytes.size)
                    digest.update(bytes)
                }
            } finally {
                accounting.flush()
            }
            return digest.digest()
        }
    }
}

private fun MessageDigest.updateInt(value: Int) {
    for (byteIndex in Int.SIZE_BYTES - 1 downTo 0) {
        update((value ushr (byteIndex * Byte.SIZE_BITS)).toByte())
    }
}
