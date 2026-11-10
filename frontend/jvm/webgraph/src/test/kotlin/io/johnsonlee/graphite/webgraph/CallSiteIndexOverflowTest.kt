package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.graph.StringMatchMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The standard webgraph test task isolates each class in its own JVM, including this budget configuration. */
class CallSiteIndexOverflowTest {
    @Test
    fun `cache admission rejects accounting overflow without corrupting retained ownership`() {
        val property = MappedCallSiteStringIndexMemoryBudget.BUDGET_PROPERTY
        val original = System.getProperty(property)
        System.setProperty(property, Long.MAX_VALUE.toString())
        val dir = Files.createTempDirectory("callsite-accounting-overflow")
        try {
            assertEquals(Long.MAX_VALUE, MappedCallSiteStringIndexMemoryBudget.maxBytes)
            val before = MappedCallSiteStringIndexMemoryBudget.retainedBytes()
            val reserved = Long.MAX_VALUE - Int.SIZE_BYTES
            val reservation = assertNotNull(MappedCallSiteStringIndexMemoryBudget.tryReserve(reserved))
            val strings = StringTable.build(listOf("value"), dir)
            MappedCallSiteStringIndex(
                Array(CALL_SITE_STRING_PROPERTY_COUNT) {
                    MappedCallSiteStringIndex.PropertyCsr(intArrayOf(), intArrayOf(), intArrayOf())
                }, strings, { it.toLong() }, 0, { _, _ -> error("empty postings have no properties") },
                { ByteArray(CALL_SITE_STRING_INDEX_CONTENT_IDENTITY_BYTES) }, reservation
            ).use { index ->
                val key = CallSitePredicateKey(null, StringMatchMode.EQUALS, "value")
                cacheCallSiteMatchingStringIds(index, key, intArrayOf(0))
                assertTrue(index.matchingStringIds.isEmpty(), "overflow must refuse admission, not wrap into a smaller budget")
                assertEquals(reserved, index.retainedBytes)
            }
            assertEquals(before, MappedCallSiteStringIndexMemoryBudget.retainedBytes())
        } finally {
            if (original == null) System.clearProperty(property) else System.setProperty(property, original)
            dir.toFile().deleteRecursively()
        }
    }
}
