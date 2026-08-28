package io.johnsonlee.graphite.cli

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
