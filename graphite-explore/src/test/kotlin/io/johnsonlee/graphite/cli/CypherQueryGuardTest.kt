package io.johnsonlee.graphite.cli

import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherQueryCancelledException
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CypherQueryGuardTest {

    @Test
    fun `guard validates concurrency and work limits`() {
        assertFailsWith<IllegalArgumentException> {
            CypherQueryGuard(maxConcurrent = 0, maxWorkUnits = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 0)
        }
    }

    @Test
    fun `guard releases permit when query fails`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)

        assertFailsWith<IllegalStateException> {
            guard.execute { error("query failed") }
        }

        assertEquals(10L, guard.execute { it.executionBudget.maxWorkUnits })
    }

    @Test
    fun `cancelling an async query interrupts blocking work and releases its permit`() {
        val guard = CypherQueryGuard(maxConcurrent = 1, maxWorkUnits = 10)
        val cancellation = CypherCancellationSignal()
        val started = CountDownLatch(1)
        val task = guard.submit(cancellation) {
            started.countDown()
            CountDownLatch(1).await()
        }

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            task.cancel()
            assertFailsWith<CypherQueryCancelledException> { task.completion.get(5, TimeUnit.SECONDS) }
            assertEquals(10L, guard.execute { it.executionBudget.maxWorkUnits })
        } finally {
            guard.close()
        }
    }
}
