package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.graph.StringPropertyProjectionRow
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DirectProjectionResultCacheTest {

    @After
    fun clearCache() {
        DirectProjectionResultCache.clear()
    }

    @Test
    fun `cache keys projection generations by identity and returns immutable results`() {
        val columns = listOf("caller", "callee")
        val projectedRows = listOf(StringPropertyProjectionRow(listOf("example.Caller", "example.Callee")))

        val first = DirectProjectionResultCache.getOrCreate(projectedRows, columns, "graph-a")
        val repeated = DirectProjectionResultCache.getOrCreate(projectedRows, columns.toList(), "graph-a")
        val nextGeneration = DirectProjectionResultCache.getOrCreate(projectedRows.toList(), columns, "graph-a")

        assertSame(first, repeated)
        assertNotSame(first, nextGeneration)
        assertEquals("example.Caller", first.rows.single()["caller"])
        assertEquals(
            mapOf(RESULT_GRAPH_IDS_KEY to listOf("graph-a")),
            first.rows.single()[RESULT_METADATA_KEY]
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (first.rows as MutableList<Map<String, Any?>>).add(emptyMap())
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (first.rows.single() as java.util.Map<String, Any?>).put("caller", "mutated")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            ((first.rows.single()[RESULT_METADATA_KEY] as Map<String, Any?>)
                .getValue(RESULT_GRAPH_IDS_KEY) as MutableList<String>).add("mutated")
        }
    }

    @Test
    fun `cache stays within global entry and heap derived byte bounds`() {
        val columns = listOf("caller", "callee")
        repeat(64) { index ->
            DirectProjectionResultCache.getOrCreate(
                listOf(StringPropertyProjectionRow(listOf("caller-$index", "callee-$index"))),
                columns,
                "graph-$index"
            )
        }

        assertTrue(DirectProjectionResultCache.entryCount() <= 32)
        assertTrue(DirectProjectionResultCache.retainedBytes() <= DirectProjectionResultCache.maxRetainedBytes())
    }

    @Test
    fun `cache rejects a decoded string payload larger than its byte budget`() {
        val columns = listOf("callerClass", "callerName", "calleeClass", "calleeName")
        val projectedRows = List(200) { row ->
            StringPropertyProjectionRow(
                List(columns.size) { column -> "value-$row-$column-${"x".repeat(1_400)}" }
            )
        }

        val first = DirectProjectionResultCache.getOrCreate(projectedRows, columns, "large-graph")
        val repeated = DirectProjectionResultCache.getOrCreate(projectedRows, columns, "large-graph")

        assertEquals(0, DirectProjectionResultCache.entryCount())
        assertNotSame(first, repeated)
        assertEquals(first, repeated)
    }

    @Test
    fun `cache rejects storage rows whose width differs from projected columns`() {
        val error = assertFailsWith<IllegalStateException> {
            DirectProjectionResultCache.getOrCreate(
                listOf(StringPropertyProjectionRow(listOf("caller-only"))),
                listOf("caller", "callee"),
                "broken-graph"
            )
        }

        assertTrue(error.message.orEmpty().contains("does not match"))
        assertEquals(0, DirectProjectionResultCache.entryCount())
    }
}
