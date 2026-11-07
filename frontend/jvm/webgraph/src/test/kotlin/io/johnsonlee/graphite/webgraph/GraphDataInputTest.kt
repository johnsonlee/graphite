package io.johnsonlee.graphite.webgraph

import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Verifies graph data input. */
class GraphDataInputTest : GraphStoreTestFixture() {

    @Test
    fun `byte buffer data input reads primitives and counting output stream flush delegate correctly`() {
        val byteBufferDataInputClass = Class.forName("io.johnsonlee.graphite.webgraph.ByteBufferDataInput")
        val inputCtor =
            byteBufferDataInputClass.getDeclaredConstructor(ByteBuffer::class.java, Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        val source = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 7, 1, 2, 3))
        val input = inputCtor.newInstance(source, 0) as DataInput

        assertEquals(7, input.readInt())
        assertTrue(input.readBoolean())
        assertEquals(2, input.readUnsignedByte())
        val buffer = ByteArray(1)
        input.readFully(buffer)
        assertEquals(byteArrayOf(3).toList(), buffer.toList())
        assertFailsWith<java.io.EOFException> { input.readByte() }
        assertEquals(0, source.position())

        val offsetInput = inputCtor.newInstance(source, 4) as DataInput
        assertTrue(offsetInput.readBoolean())
        assertEquals(0, offsetInput.skipBytes(-1))

        val skipInput = inputCtor.newInstance(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 0) as DataInput
        assertEquals(2, skipInput.skipBytes(2))
        assertEquals(3, skipInput.readUnsignedByte())
        assertEquals(0, skipInput.skipBytes(1))

        val primitives = ByteBuffer.allocate(Short.SIZE_BYTES + Char.SIZE_BYTES + Long.SIZE_BYTES + Float.SIZE_BYTES + Double.SIZE_BYTES)
        primitives.putShort(7)
        primitives.putChar('g')
        primitives.putLong(11L)
        primitives.putFloat(1.25f)
        primitives.putDouble(2.5)
        primitives.flip()
        val primitiveInput = inputCtor.newInstance(primitives, 0) as DataInput
        assertEquals(7, primitiveInput.readShort().toInt())
        assertEquals('g', primitiveInput.readChar())
        assertEquals(11L, primitiveInput.readLong())
        assertEquals(1.25f, primitiveInput.readFloat())
        assertEquals(2.5, primitiveInput.readDouble())

        val utfOut = ByteArrayOutputStream()
        DataOutputStream(utfOut).use { it.writeUTF("ok") }
        val utfInput = inputCtor.newInstance(ByteBuffer.wrap(utfOut.toByteArray()), 0) as DataInput
        assertEquals("ok", utfInput.readUTF())
        assertFailsWith<UnsupportedOperationException> { utfInput.readLine() }

        val flushed = mutableListOf<Boolean>()
        val delegate =
            object : OutputStream() {
                override fun write(b: Int) = Unit

                override fun flush() {
                    flushed += true
                }
            }
        val countingOutputStreamClass = Class.forName("io.johnsonlee.graphite.webgraph.CountingOutputStream")
        val outputCtor = countingOutputStreamClass.getDeclaredConstructor(OutputStream::class.java).apply { isAccessible = true }
        val output = outputCtor.newInstance(delegate) as OutputStream
        output.write(byteArrayOf(1, 2, 3))
        output.flush()
        val bytesWritten = countingOutputStreamClass.getDeclaredMethod("getBytesWritten").invoke(output) as Long
        assertEquals(3L, bytesWritten)
        assertEquals(listOf(true), flushed)
    }
}
