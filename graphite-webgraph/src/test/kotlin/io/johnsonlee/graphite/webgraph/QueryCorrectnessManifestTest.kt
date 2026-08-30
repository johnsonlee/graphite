package io.johnsonlee.graphite.webgraph

import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QueryCorrectnessManifestTest {
    @Test
    fun `round trip preserves a complete correctness record`() {
        val path = createTempFile("query-correctness", ".manifest")
        val expected = listOf(record("query-a"), record("query-b", digest = "b".repeat(64)))

        QueryCorrectnessManifest.write(path, expected)

        assertEquals(expected, QueryCorrectnessManifest.read(path))
    }

    @Test
    fun `oracle rejects timeout and missing coverage`() {
        val timeout = record("query-a", outcome = "timeout", digest = "timeout")

        assertFailsWith<IllegalArgumentException> {
            QueryCorrectnessManifest.selectCompleteOracle(listOf(timeout), setOf("query-a"), "oracle")
        }
        val missing = assertFailsWith<IllegalArgumentException> {
            QueryCorrectnessManifest.selectCompleteOracle(listOf(record("query-a")), setOf("query-a", "query-b"), "oracle")
        }
        assertTrue(missing.message.orEmpty().contains("query-b"))
    }

    @Test
    fun `verification rejects every observable result difference`() {
        val expected = record("query-a")
        val variants = listOf(
            expected.copy(family = "other"),
            expected.copy(rowCount = 2),
            expected.copy(responseBytes = 20),
            expected.copy(digest = "b".repeat(64)),
            expected.copy(outcome = "timeout", rowCount = 0, responseBytes = 0, digest = "timeout")
        )

        variants.forEach { actual ->
            assertFailsWith<IllegalStateException> {
                QueryCorrectnessManifest.verify(listOf(expected), listOf(actual))
            }
        }
    }

    @Test
    fun `verification rejects missing unexpected and duplicate query ids`() {
        val expected = listOf(record("query-a"), record("query-b"))

        assertFailsWith<IllegalStateException> {
            QueryCorrectnessManifest.verify(expected, listOf(record("query-a")))
        }
        assertFailsWith<IllegalStateException> {
            QueryCorrectnessManifest.verify(expected, expected + record("query-c"))
        }
        assertFailsWith<IllegalArgumentException> {
            QueryCorrectnessManifest.verify(expected, listOf(record("query-a"), record("query-a")))
        }
    }

    @Test
    fun `record mode rejects any incomplete query`() {
        val records = listOf(
            record("query-a"),
            record("query-b", outcome = "failed", digest = "java.lang.IllegalStateException")
        )

        assertFailsWith<IllegalStateException> {
            QueryCorrectnessManifest.requireRecordable(records, setOf("query-a", "query-b"))
        }
    }

    private fun record(
        id: String,
        outcome: String = "success",
        digest: String = "a".repeat(64)
    ) = QueryCorrectnessRecord(
        id = id,
        family = "contains",
        shape = "single",
        selectivity = "targeted",
        operator = "contains",
        boundary = "single-query",
        projection = "property",
        limit = 10,
        outcome = outcome,
        rowCount = 1,
        responseBytes = 10,
        digest = digest
    )
}
