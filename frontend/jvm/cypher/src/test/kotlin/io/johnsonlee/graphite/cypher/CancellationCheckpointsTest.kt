package io.johnsonlee.graphite.cypher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.johnsonlee.graphite.core.checkThreadInterrupted

class CancellationCheckpointsTest {
    @Test
    fun `query checkpoints preserve default and explicit reasons without consuming interruption`() {
        checkThreadInterrupted { CypherQueryCancelledException() }
        try {
            Thread.currentThread().interrupt()
            val default = assertFailsWith<CypherQueryCancelledException> { checkThreadInterrupted { CypherQueryCancelledException() } }
            assertEquals(CypherQueryCancelledException::class.java, default.javaClass)
            assertEquals("Cypher query cancelled", default.message)
            assertTrue(Thread.currentThread().isInterrupted)
            val explicit = assertFailsWith<CypherQueryCancelledException> {
                checkThreadInterrupted { CypherQueryCancelledException("String projection interrupted") }
            }
            assertEquals(CypherQueryCancelledException::class.java, explicit.javaClass)
            assertEquals("String projection interrupted", explicit.message)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
