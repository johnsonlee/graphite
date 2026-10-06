package io.johnsonlee.graphite.webgraph

import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sidecar's lookup finds every id it holds across block boundaries and none it does not. */
class CallSiteOrdinalsTest {

    @Test
    fun `every recorded id resolves across blocks and unknown ids do not`() {
        // 1000 ids spaced by 3 span four blocks of 256, the last one partial.
        val ids = IntArray(1000) { 7 + it * 3 }
        val ordinals = IntArray(1000) { it - 500 }
        // Every derived call (ordinal below zero) came from the id before it.
        val origins = IntArray(1000) { if (ordinals[it] < 0) ids[it] - 1 else NO_ORIGIN }
        val encoded = NodeSerializer.encodeCallSiteOrdinals(ids, ordinals, origins)
        val sidecar = NodeSerializer.callSiteOrdinalsOf(ByteBuffer.wrap(encoded.bytes), encoded.digest)!!
        assertEquals(1000, sidecar.size)
        for (index in ids.indices) assertEquals(ordinals[index], sidecar[ids[index]], "id ${ids[index]}")
        assertEquals(ids[0] - 1, sidecar.origin(ids[0])?.value)
        assertEquals(ids[499] - 1, sidecar.origin(ids[499])?.value)
        assertNull(sidecar.origin(ids[999]), "not derived")
        assertNull(sidecar.origin(6), "no entry")
        assertNull(sidecar[6], "below the first id")
        assertNull(sidecar[8], "between two ids")
        assertNull(sidecar[ids[255] + 1], "just past a block head's neighbour")
        assertNull(sidecar[ids[256] - 1], "just before a block head")
        assertNull(sidecar[ids.last() + 1], "past the last id")
        assertNull(CallSiteOrdinals.EMPTY[0])
        assertNull(CallSiteOrdinals.EMPTY.origin(0))
        // The index is a sixty-fourth of the entries plus a head per block: 1000 pairs and 500 origins
        // are 12,000 bytes in six blocks, so six digests and four heads.
        val index = 2 * Int.SIZE_BYTES + 4 * Int.SIZE_BYTES + 6 * NodeSerializer.DIGEST_BYTES
        assertEquals(NodeSerializer.CALL_SITE_ORDINALS_PREAMBLE_BYTES + index + 12_000, encoded.bytes.size)
    }

    @Test
    fun `a sidecar whose length disagrees with its counts or whose index is not the bound one is ignored`() {
        val dir = Files.createTempDirectory("call-site-ordinals")
        try {
            val file = dir.resolve("graph.callsite-ordinals")
            val entries = intArrayOf(42, 3)
            val blockDigest = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(8).putInt(42).putInt(3).array())
            // The binding is the SHA-256 of the index: the counts, the block heads and the block digests.
            fun index(count: Int, originCount: Int, heads: IntArray, digests: List<ByteArray>): ByteArray {
                val buffer = ByteBuffer.allocate(2 * Int.SIZE_BYTES + heads.size * Int.SIZE_BYTES + digests.sumOf { it.size })
                buffer.putInt(count).putInt(originCount)
                heads.forEach { buffer.putInt(it) }
                digests.forEach { buffer.put(it) }
                return buffer.array()
            }
            fun write(index: ByteArray, digest: ByteArray, vararg entries: Int) {
                val length = NodeSerializer.CALL_SITE_ORDINALS_PREAMBLE_BYTES + index.size + entries.size * Int.SIZE_BYTES
                val bytes = ByteBuffer.allocate(length)
                bytes.putInt(NodeSerializer.MAGIC_CALL_SITE_ORDINALS or NodeSerializer.CALL_SITE_ORDINALS_VERSION)
                bytes.put(digest)
                bytes.put(index)
                entries.forEach { bytes.putInt(it) }
                Files.write(file, bytes.array())
            }
            val sound = index(1, 0, intArrayOf(42), listOf(blockDigest))
            val digest = MessageDigest.getInstance("SHA-256").digest(sound)
            write(sound, digest, *entries)
            val loaded = CallSiteOrdinals.load(file, digest)
            assertEquals(1, loaded.size)
            assertEquals(3, loaded[42])
            assertEquals(0, CallSiteOrdinals.load(file, ByteArray(32)).size, "bound to another graph")
            assertEquals(0, CallSiteOrdinals.load(file, null).size, "no binding")
            write(sound, digest, 42, 3, 0)
            assertEquals(0, CallSiteOrdinals.load(file, digest).size, "trailing bytes")
            write(sound, digest, 42)
            assertEquals(0, CallSiteOrdinals.load(file, digest).size, "cut short")
            fun rejected(index: ByteArray, what: String, vararg entries: Int) {
                // The header's copy of the digest matches, so only the hash of the index can refuse it.
                val claimed = MessageDigest.getInstance("SHA-256").digest(index)
                write(index, claimed, *entries)
                assertEquals(0, CallSiteOrdinals.load(file, digest).size, what)
            }
            rejected(index(2, 0, intArrayOf(42), listOf(blockDigest)), "count disagrees with the length", 42, 3, 7, 0)
            rejected(index(1, 1, intArrayOf(42), listOf(blockDigest)), "origin count disagrees with the length", 42, 3)
            rejected(index(1, -1, intArrayOf(42), listOf(blockDigest)), "negative origin count", 42, 3)
            rejected(index(-1, 0, intArrayOf(), listOf()), "negative count")
            rejected(index(1, 0, intArrayOf(43), listOf(blockDigest)), "another head", 42, 3)
            write(index(1, 0, intArrayOf(43), listOf(blockDigest)), digest, 42, 3)
            assertEquals(0, CallSiteOrdinals.load(file, digest).size, "an index that is not the bound one behind a copied digest")
            assertEquals(0, CallSiteOrdinals.load(dir.resolve("missing"), digest).size)
            Files.write(file, byteArrayOf(1, 2, 3))
            assertEquals(0, CallSiteOrdinals.load(file, digest).size, "not a sidecar")
            // The encoder writes what the loader binds.
            val encoded = NodeSerializer.encodeCallSiteOrdinals(intArrayOf(4, 9), intArrayOf(0, -1), intArrayOf(NO_ORIGIN, 4))
            Files.write(file, encoded.bytes)
            val bound = CallSiteOrdinals.load(file, encoded.digest)
            assertEquals(2, bound.size)
            assertEquals(-1, bound[9])
            assertEquals(4, bound.origin(9)?.value)
            assertNull(bound.origin(4))
            // Every mapping hashes the index: the same file replaced by other bytes of the same length
            // and the same modification time is not the file that was loaded before.
            val other = NodeSerializer.encodeCallSiteOrdinals(intArrayOf(4, 9), intArrayOf(0, -1), intArrayOf(NO_ORIGIN, 9))
            assertEquals(encoded.bytes.size, other.bytes.size)
            val modified = Files.getLastModifiedTime(file)
            Files.write(file, other.bytes)
            Files.setLastModifiedTime(file, modified)
            assertEquals(0, CallSiteOrdinals.load(file, encoded.digest).size, "replaced in place")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a block of entries that no longer hashes to its digest is refused on its first touch`() {
        // 600 call sites: three blocks of ordinal pairs, the third holding the origins too.
        val ids = IntArray(600) { it * 2 }
        val ordinals = IntArray(600) { if (it % 100 == 99) -1 else it }
        val origins = IntArray(600) { if (ordinals[it] < 0) ids[it] - 2 else NO_ORIGIN }
        val encoded = NodeSerializer.encodeCallSiteOrdinals(ids, ordinals, origins)
        fun tampered(entryOffset: Int): CallSiteOrdinals {
            val bytes = encoded.bytes.copyOf()
            val at = bytes.size - 606 * NodeSerializer.CALL_SITE_ORDINAL_ENTRY_BYTES + entryOffset
            bytes[at] = (bytes[at].toInt() xor 1).toByte()
            // The index is intact, so the sidecar opens; the block that changed fails when it is read.
            return NodeSerializer.callSiteOrdinalsOf(ByteBuffer.wrap(bytes), encoded.digest)!!
        }
        val secondBlock = tampered(300 * NodeSerializer.CALL_SITE_ORDINAL_ENTRY_BYTES + 5)
        assertEquals(0, secondBlock[0], "the first block is sound")
        assertEquals(598, secondBlock[ids[598]], "the third block is sound")
        val failure = assertFailsWith<IllegalStateException> { secondBlock[ids[300]] }
        assertTrue(failure.message!!.startsWith("graph.callsite-ordinals block 1 does not hash"), failure.message)
        assertFailsWith<IllegalStateException>("verified once, refused every time") { secondBlock[ids[257]] }
        val originsBlock = tampered(601 * NodeSerializer.CALL_SITE_ORDINAL_ENTRY_BYTES)
        assertEquals(1, originsBlock[2], "ordinals do not read the origins' block")
        assertFailsWith<IllegalStateException> { originsBlock.origin(ids[99]) }
        val sound = NodeSerializer.callSiteOrdinalsOf(ByteBuffer.wrap(encoded.bytes), encoded.digest)!!
        assertEquals(ids[99] - 2, sound.origin(ids[99])?.value)
    }
}
