package io.johnsonlee.graphite.webgraph

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LargeCorpusExpectationTest {
    @Test
    fun `approval requires the exact source tree and corpus and changes only graph totals`() {
        val tree = "a".repeat(40)
        val original = LargeCorpusBaselines.tika
        val approved = ApprovedCorpusCounts(1, 2, 3, 4, 5)
        val approvals = mapOf((tree to "tika") to approved)
        assertSame(original, applyApprovedCounts(original, "b".repeat(40), approvals))
        assertSame(original, applyApprovedCounts(original, null, approvals))
        assertSame(LargeCorpusBaselines.hive, applyApprovedCounts(LargeCorpusBaselines.hive, tree, approvals))
        assertEquals(original.copy(nodeCount = 1, sourceEdgeCount = 2, persistedEdgeCount = 3,
            methodCount = 4, callSiteCount = 5), applyApprovedCounts(original, tree, approvals))
    }

    @Test
    fun `unapproved trees retain historical baselines and invalid identities fail`() {
        assertEquals(LargeCorpusBaselines.tika, LargeCorpusBaselines.forSource("tika", "a".repeat(40)))
        assertFailsWith<IllegalArgumentException> { LargeCorpusBaselines.forSource("tika", "arbitrary") }
        assertFailsWith<IllegalArgumentException> { ApprovedCorpusCounts(-1, 2, 3, 4, 5) }
    }
}
