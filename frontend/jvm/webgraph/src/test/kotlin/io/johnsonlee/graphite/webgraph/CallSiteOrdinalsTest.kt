package io.johnsonlee.graphite.webgraph

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The sidecar's lookup finds every id it holds across block boundaries and none it does not. */
class CallSiteOrdinalsTest {

    @Test
    fun `every recorded id resolves across blocks and unknown ids do not`() {
        // 1000 ids spaced by 3 span four blocks of 256, the last one partial.
        val ids = IntArray(1000) { 7 + it * 3 }
        val ordinals = IntArray(1000) { it - 500 }
        val sidecar = CallSiteOrdinals(IntBuffer.wrap(IntArray(2000) { if (it % 2 == 0) ids[it / 2] else ordinals[it / 2] }))
        assertEquals(1000, sidecar.size)
        for (index in ids.indices) assertEquals(ordinals[index], sidecar[ids[index]], "id ${ids[index]}")
        assertNull(sidecar[6], "below the first id")
        assertNull(sidecar[8], "between two ids")
        assertNull(sidecar[ids[255] + 1], "just past a block head's neighbour")
        assertNull(sidecar[ids[256] - 1], "just before a block head")
        assertNull(sidecar[ids.last() + 1], "past the last id")
        assertNull(CallSiteOrdinals.EMPTY[0])
    }

    @Test
    fun `a sidecar whose length disagrees with its count is ignored`() {
        val dir = Files.createTempDirectory("call-site-ordinals")
        try {
            val file = dir.resolve("graph.callsite-ordinals")
            // The digest in the header and the binding are the SHA-256 of the entries, as the writer computes it.
            val digest = MessageDigest.getInstance("SHA-256").digest(ByteBuffer.allocate(8).putInt(42).putInt(3).array())
            fun write(count: Int, vararg entries: Int) {
                val bytes = ByteArrayOutputStream()
                DataOutputStream(bytes).use { out ->
                    out.writeInt(NodeSerializer.MAGIC_CALL_SITE_ORDINALS or NodeSerializer.CALL_SITE_ORDINALS_VERSION)
                    out.writeInt(count)
                    out.write(digest)
                    entries.forEach(out::writeInt)
                }
                Files.write(file, bytes.toByteArray())
            }
            write(1, 42, 3)
            val loaded = CallSiteOrdinals.load(file, digest)
            assertEquals(1, loaded.size)
            assertEquals(3, loaded[42])
            assertEquals(0, CallSiteOrdinals.load(file, ByteArray(32)).size, "bound to another graph")
            assertEquals(0, CallSiteOrdinals.load(file, null).size, "no binding")
            write(2, 42, 3)
            assertEquals(0, CallSiteOrdinals.load(file, digest).size, "cut short")
            write(0, 42, 3)
            assertEquals(0, CallSiteOrdinals.load(file, digest).size, "trailing bytes")
            write(-1)
            assertEquals(0, CallSiteOrdinals.load(file, digest).size, "negative count")
            assertEquals(0, CallSiteOrdinals.load(dir.resolve("missing"), digest).size)
            // The encoder writes what the loader binds: the digest covers the entries.
            val encoded = NodeSerializer.encodeCallSiteOrdinals(intArrayOf(4, 9), intArrayOf(0, -1))
            Files.write(file, encoded.bytes)
            val bound = CallSiteOrdinals.load(file, encoded.digest)
            assertEquals(2, bound.size)
            assertEquals(-1, bound[9])
            // The entries are hashed, not the digest the header copies: an ordinal byte flipped
            // behind an intact header is not the sidecar the metadata binds.
            val tampered = dir.resolve("tampered")
            val flipped = encoded.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            Files.write(tampered, flipped)
            assertEquals(0, CallSiteOrdinals.load(tampered, encoded.digest).size, "entry changed behind the header")
            // Every mapping is hashed: the same file replaced by other bytes of the same length
            // and the same modification time is not the file that was loaded before.
            val modified = Files.getLastModifiedTime(file)
            Files.write(file, flipped)
            Files.setLastModifiedTime(file, modified)
            assertEquals(0, CallSiteOrdinals.load(file, encoded.digest).size, "replaced in place")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
