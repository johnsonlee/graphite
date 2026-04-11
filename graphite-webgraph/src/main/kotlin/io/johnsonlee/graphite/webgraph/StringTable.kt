package io.johnsonlee.graphite.webgraph

import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.util.FrontCodedStringList
import java.nio.file.Path

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
internal class StringTable internal constructor(
    private val list: FrontCodedStringList,
    private val indexMap: Map<String, Int>
) {

    /**
     * Returns the index of [s] in the string table, or -1 if not found.
     */
    fun indexOf(s: String): Int = indexMap[s] ?: -1

    /**
     * Returns the string at the given [index].
     */
    fun get(index: Int): String = list.get(index).toString()

    /**
     * Returns the number of strings in the table.
     */
    fun size(): Int = list.size

    companion object {

        private const val FILE_NAME = "graph.strings"

        /**
         * Build a [StringTable] from a collection of strings and persist it to disk.
         *
         * Strings are sorted and deduplicated before building the front-coded list.
         * The ratio parameter (8) controls the trade-off between compression and
         * random access speed.
         */
        fun build(strings: Collection<String>, dir: Path): StringTable {
            val sorted = strings.toSortedSet().toList()
            val fcl = FrontCodedStringList(sorted.iterator(), 8, false)
            BinIO.storeObject(fcl, dir.resolve(FILE_NAME).toString())
            val indexMap = HashMap<String, Int>(sorted.size)
            for (i in sorted.indices) {
                indexMap[sorted[i]] = i
            }
            return StringTable(fcl, indexMap)
        }

        /**
         * Load a previously persisted [StringTable] from disk.
         */
        fun load(dir: Path): StringTable {
            @Suppress("UNCHECKED_CAST")
            val fcl = BinIO.loadObject(dir.resolve(FILE_NAME).toString()) as FrontCodedStringList
            return fromFrontCodedStringList(fcl)
        }

        /**
         * Build a [StringTable] from a [FrontCodedStringList] (e.g. deserialized from bytes).
         */
        internal fun fromFrontCodedStringList(fcl: FrontCodedStringList): StringTable {
            val sz = fcl.size
            val indexMap = HashMap<String, Int>(sz)
            for (i in 0 until sz) {
                indexMap[fcl.get(i).toString()] = i
            }
            return StringTable(fcl, indexMap)
        }

        internal const val STRINGS_FILE_NAME = FILE_NAME
    }
}
