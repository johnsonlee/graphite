package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.GraphWorkConsumer
import it.unimi.dsi.fastutil.io.BinIO
import it.unimi.dsi.lang.MutableString
import it.unimi.dsi.util.FrontCodedStringList
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
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
    contentIdentity: ByteArray?,
    serializedDigest: ByteArray? = null
) {

    private val serializedIdentity = serializedDigest?.copyOf()
    internal fun serializedDigest(): ByteArray? = serializedIdentity?.copyOf()

    @Volatile
    private var persistedContentIdentity: ByteArray? = contentIdentity?.copyOf()
    private val contentIdentityLock = Any()

    /**
     * Returns the index of [s] in the string table, or -1 if not found.
     */
    fun indexOf(s: String): Int = indexMap?.get(s) ?: -1

    /** Finds an id in both builder and loaded tables; persisted tables remain sorted. */
    @Suppress("ReturnCount")
    internal fun findId(s: String): Int {
        indexMap?.get(s)?.let { return it }
        var low = 0
        var high = list.size - 1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val comparison = list.get(middle).toString().compareTo(s)
            when {
                comparison < 0 -> low = middle + 1
                comparison > 0 -> high = middle - 1
                else -> return middle
            }
        }
        return -1
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

        private const val DIGEST_ALGORITHM = "SHA-256"
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
        fun build(strings: Collection<String>, dir: Path, captureSerializedDigest: Boolean = false): StringTable {
            val sorted = strings.toSortedSet().toList()
            val fcl = FrontCodedStringList(sorted.iterator(), FRONT_CODED_STRING_RATIO, false)
            val serializedHash = if (captureSerializedDigest) MessageDigest.getInstance(DIGEST_ALGORITHM) else null
            if (serializedHash == null) {
                BinIO.storeObject(fcl, dir.resolve(FILE_NAME).toString())
            } else {
                DigestOutputStream(BufferedOutputStream(Files.newOutputStream(dir.resolve(FILE_NAME))), serializedHash).use { raw ->
                    ObjectOutputStream(raw).use { it.writeObject(fcl) }
                }
            }
            val contentIdentity = semanticContentIdentity(sorted)
            Files.write(dir.resolve(CONTENT_IDENTITY_FILE_NAME), contentIdentity)
            val indexMap = HashMap<String, Int>(sorted.size)
            for (i in sorted.indices) {
                indexMap[sorted[i]] = i
            }
            return StringTable(fcl, indexMap, contentIdentity, serializedHash?.digest())
        }

        /**
         * Load a previously persisted [StringTable] from disk.
         */
        fun load(dir: Path, verifySerializedDigest: Boolean = false): StringTable {
            val serializedHash = if (verifySerializedDigest) MessageDigest.getInstance(DIGEST_ALGORITHM) else null
            val fcl = if (serializedHash == null) {
                BinIO.loadObject(dir.resolve(FILE_NAME).toString()) as FrontCodedStringList
            } else {
                SerializedDigestInputStream(BufferedInputStream(Files.newInputStream(dir.resolve(FILE_NAME))), serializedHash).use { raw ->
                    val objects = ObjectInputStream(raw)
                    val decoded = objects.readObject() as FrontCodedStringList
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (raw.read(buffer) >= 0) { /* Bind every serialized byte, including trailing data. */ }
                    decoded
                }
            }
            val contentIdentity = dir.resolve(CONTENT_IDENTITY_FILE_NAME).takeIf(Files::isRegularFile)
                ?.let(Files::readAllBytes)
                ?.takeIf { identity -> identity.size == CONTENT_IDENTITY_BYTES }
            return StringTable(fcl, null, contentIdentity, serializedHash?.digest())
        }

        private fun semanticContentIdentity(strings: List<String>): ByteArray {
            val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
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
            val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
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

/** ObjectInputStream may skip optional data; skipped bytes belong to the serialized identity too. */
internal class SerializedDigestInputStream(input: java.io.InputStream, digest: MessageDigest) :
    DigestInputStream(input, digest) {
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        val buffer = ByteArray(minOf(n, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        var remaining = n
        while (remaining > 0) {
            val readCount = read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            val count = if (readCount == 0) { if (read() < 0) -1 else 1 } else readCount
            if (count < 0) break
            remaining -= count
        }
        return n - remaining
    }
}
