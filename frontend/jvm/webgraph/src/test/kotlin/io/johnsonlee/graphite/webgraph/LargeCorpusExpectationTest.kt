package io.johnsonlee.graphite.webgraph

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LargeCorpusExpectationTest {
    @Test
    fun `approval requires the exact product identity and corpus and changes only graph totals`() {
        val identity = "a".repeat(64)
        val original = LargeCorpusBaselines.tika
        val approved = ApprovedCorpusCounts(1, 2, 3, 4, 5)
        val approvals = mapOf((identity to "tika") to approved)
        assertSame(original, applyApprovedCounts(original, "b".repeat(64), approvals))
        assertSame(original, applyApprovedCounts(original, null, approvals))
        assertSame(LargeCorpusBaselines.hive, applyApprovedCounts(LargeCorpusBaselines.hive, identity, approvals))
        assertEquals(original.copy(nodeCount = 1, sourceEdgeCount = 2, persistedEdgeCount = 3,
            methodCount = 4, callSiteCount = 5), applyApprovedCounts(original, identity, approvals))
    }

    @Test
    fun `unapproved products retain historical baselines and invalid identities fail`() {
        assertEquals(LargeCorpusBaselines.tika, LargeCorpusBaselines.forProduct("tika", "a".repeat(64)))
        for (invalid in listOf("arbitrary", "a".repeat(40), "a".repeat(63), "a".repeat(65), "A".repeat(64))) {
            assertFailsWith<IllegalArgumentException> { LargeCorpusBaselines.forProduct("tika", invalid) }
        }
        assertFailsWith<IllegalArgumentException> { ApprovedCorpusCounts(-1, 2, 3, 4, 5) }
    }
}
