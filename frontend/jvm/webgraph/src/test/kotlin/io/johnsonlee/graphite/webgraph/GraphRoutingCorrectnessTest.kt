package io.johnsonlee.graphite.webgraph

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Suppress("MagicNumber", "StringLiteralDuplication")
class GraphRoutingCorrectnessTest {
    @Test
    fun `correctness artifact preserves structural counters without performance observations`() {
        // This independent schema is the correctness artifact contract, including zero-valued fields.
        val expected = linkedMapOf(
            "graphCount" to 64L,
            "distinctGraphPathCount" to 64L,
            "queryCount" to 39L,
            "successCount" to 39L,
            "timeoutCount" to 0L,
            "failureCount" to 0L,
            "totalRows" to 6522L,
            "graphIdTargetCount" to 1L,
            "graphParameterTargetCount" to 2L,
            "coverageShapeCount" to 3L,
            "coverageFamilyCount" to 4L,
            "coverageSelectivityCount" to 5L,
            "coverageProjectionCount" to 6L,
            "coverageOperatorCount" to 7L,
            "coverageBoundaryCount" to 8L,
            "rawStringMatchStateBytes" to 9L,
            "callSiteIndexAdmittedGraphs" to 10L,
            "callSiteIndexRetainedBytes" to 11L,
            "callSiteTrigramIndexedGraphs" to 12L,
            "requestSelectedSourceQueryCount" to 13L,
            "cypherGraphIdPredicateQueryCount" to 14L,
            "inputSourceCount" to 15L,
            "accessedGraphCount" to 16L,
            "targetGraphAccessCount" to 17L,
            "nonTargetGraphAccessCount" to 0L,
            "graphWorkUnits" to 18L,
            "graphIdSourceSelections" to 19L,
            "graphIdSourcePruningExecutions" to 20L,
            "graphIdSourcesPruned" to 21L,
            "filteredNodeLimitFastPathExecutions" to 22L,
            "generalFallbackExecutions" to 23L,
            "callSiteParallelScanCount" to 24L,
            "callSiteParallelScanGraphCount" to 25L,
            "callSiteStringIndexLookupCount" to 26L,
            "callSiteStringIndexLookupGraphCount" to 27L,
            "callSiteStringIndexLookupMinPerGraph" to 28L,
            "callSiteStringIndexLookupMaxPerGraph" to 29L,
            "callSiteScanPeakActiveWorkers" to 30L,
            "graphScanPeakActiveWorkers" to 31L,
            "segmentScanPeakActiveWorkers" to 32L
        )
        val counters = LargeBroadQueryPressureCounters().apply {
            wallNanos = 999L
            processCpuNanos = 998L
            peakUsedHeapBytes = 997L
            peakResidentSetBytes = 996L
        }
        expected.forEach { (name, value) -> counters.javaClass.getField(name).setLong(counters, value) }
        val output = Files.createTempFile("routing-correctness", ".json")
        try {
            for (state in listOf("cold", "warm", "startup-prepared")) {
                GraphRoutingCorrectness.writeMetrics(output, state, "all", counters)
                val fields = expected.entries.joinToString(",") { (name, value) -> "\"$name\":$value" }
                assertEquals(
                    "{\"scope\":\"correctness-only\",\"indexState\":\"$state\",\"coverageFamily\":\"all\"," +
                        "\"metrics\":{$fields}}\n",
                    Files.readString(output)
                )
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun `artifact write failure propagates instead of silently losing correctness evidence`() {
        val directory = Files.createTempDirectory("routing-correctness-unwritable")
        try {
            assertFailsWith<IOException> {
                GraphRoutingCorrectness.writeMetrics(directory, "cold", "all", LargeBroadQueryPressureCounters())
            }
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `invalid arguments fail before graph preparation`() {
        assertFailsWith<IllegalArgumentException> { GraphRoutingCorrectness.main(emptyArray()) }
        assertFailsWith<NumberFormatException> {
            GraphRoutingCorrectness.main(arrayOf("cold", "all", "invalid-timeout", "unused.json"))
        }
        assertFailsWith<IllegalArgumentException> { MappedAdmissionCorrectness.main(arrayOf("unexpected")) }
    }
}
