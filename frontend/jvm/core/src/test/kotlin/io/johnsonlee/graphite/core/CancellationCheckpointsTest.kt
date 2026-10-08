package io.johnsonlee.graphite.core

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CancellationCheckpointsTest {
    @Test
    fun `uninterrupted checks never invoke the exception factory`() {
        assertFalse(Thread.currentThread().isInterrupted)
        var calls = 0
        repeat(2) {
            checkThreadInterrupted {
                calls++
                CancellationException("unused")
            }
        }
        assertEquals(0, calls)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `interrupted checks preserve the supplied exception and interrupt flag`() {
        val cause = IllegalStateException("original cause")
        val expected = CustomCancellation("request cancelled").apply { initCause(cause) }
        var calls = 0
        Thread.currentThread().interrupt()
        try {
            repeat(2) { index ->
                val actual = assertFailsWith<CustomCancellation> {
                    checkThreadInterrupted {
                        calls++
                        expected
                    }
                }
                assertSame(expected, actual)
                assertEquals("request cancelled", actual.message)
                assertSame(cause, actual.cause)
                assertEquals(index + 1, calls)
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
        }
    }

    private class CustomCancellation(message: String) : CancellationException(message)
}
