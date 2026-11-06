package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.graph.Graph
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherClauseExecutionTest : CypherExecutorTestFixture() {

    @Test
    fun `WITH inline WHERE`() {
        val result = executor.execute("MATCH (n:IntConstant) WITH n.value AS v WHERE v > 10 RETURN v")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    @Test
    fun `UNWIND and RETURN`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN x")
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    @Test
    fun `ORDER BY ASCENDING keyword`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v ASCENDING")
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["v"])
        assertEquals(42, result.rows[1]["v"])
    }

    @Test
    fun `SKIP rows`() {
        val result = executor.execute("UNWIND [1, 2, 3, 4, 5] AS x RETURN x SKIP 3")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `count star aggregation`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN count(*) AS cnt")
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
    }

    @Test
    fun `label histogram uses node count metadata without scanning nodes`() {
        var countCalls = 0
        val metadataGraph =
            object : Graph by graph {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = error("label histogram must not scan nodes")

                override fun nodeCount(type: Class<out Node>): Long? {
                    countCalls++
                    return graph.nodeCount(type)
                }
            }

        val result =
            CypherExecutor(metadataGraph)
                .execute(
                    """
            MATCH (n)
            UNWIND labels(n) AS label
            RETURN label, count(*) AS c
            ORDER BY c DESC
            LIMIT 50
            """
                        .trimIndent())
        val counts = result.rows.associate { it["label"] as String to it["c"] as Long }

        assertEquals(listOf("label", "c"), result.columns)
        assertEquals("Constant", result.rows.first()["label"])
        assertEquals(9L, counts["Constant"])
        assertEquals(2L, counts["CallSiteNode"])
        assertEquals(2L, counts["Annotation"])
        assertEquals(1L, counts["Resource"])
        assertEquals(nodeLabelDescriptors.size, countCalls)
    }

    @Test
    fun `label histogram supports aliases and limit`() {
        val result =
            executor.execute(
                """
            MATCH (node)
            UNWIND labels(node) AS rawLabel
            RETURN rawLabel AS kind, count(*) AS total
            ORDER BY total DESC
            LIMIT 1
            """
                    .trimIndent())

        assertEquals(listOf("kind", "total"), result.columns)
        assertEquals(listOf(mapOf("kind" to "Constant", "total" to 9L)), result.rows)
    }

    @Test
    fun `label histogram with label ordering falls back without changing results`() {
        val result =
            executor.execute(
                """
            MATCH (n)
            UNWIND labels(n) AS label
            RETURN label, count(*) AS c
            ORDER BY label
            LIMIT 50
            """
                    .trimIndent())

        assertTrue(result.rows.isNotEmpty())
        assertEquals(result.rows.map { it["label"] }.sortedBy(Any?::toString), result.rows.map { it["label"] })
    }

    @Test
    fun `label histogram preserves literal limit above Int range`() {
        val query =
            """
            MATCH (n)
            UNWIND labels(n) AS label
            RETURN label, count(*) AS c
            ORDER BY c DESC
        """
                .trimIndent()

        val expected = executor.execute("$query LIMIT 2147483647")
        val result = executor.execute("$query LIMIT 2147483648")

        assertTrue(result.rows.isNotEmpty())
        assertEquals(expected, result)
    }

    @Test
    fun `label histogram falls back when node counts are unavailable`() {
        var scanned = false
        val scanningGraph =
            object : Graph by graph {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> = graph.nodes(type).onEach { scanned = true }

                override fun nodeCount(type: Class<out Node>): Long? = null
            }

        val result =
            CypherExecutor(scanningGraph)
                .execute(
                    """
            MATCH (n)
            UNWIND labels(n) AS label
            RETURN label, count(*) AS c
            ORDER BY c DESC
            LIMIT 1
            """
                        .trimIndent())

        assertTrue(scanned)
        assertEquals(listOf(mapOf("label" to "Constant", "c" to 9L)), result.rows)
    }

    @Test
    fun `semicolons between clauses`() {
        val result = executor.execute("RETURN 1 AS x;")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `CASE expression in query`() {
        val result =
            executor.execute("MATCH (n:IntConstant) RETURN CASE WHEN n.value > 10 THEN 'big' ELSE 'small' END AS size ORDER BY size")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `list comprehension in query`() {
        val result = executor.execute("RETURN [x IN [1,2,3,4] WHERE x > 2 | x * 10] AS r")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val r = result.rows[0]["r"] as List<*>
        assertEquals(2, r.size)
    }

    @Test
    fun `subscript in query`() {
        val result = executor.execute("RETURN [1,2,3][1] AS v")
        assertEquals(1, result.rows.size)
        assertEquals(2, result.rows[0]["v"])
    }

    @Test
    fun `slice in query`() {
        val result = executor.execute("RETURN [1,2,3,4][1..3] AS v")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") assertEquals(listOf(2, 3), result.rows[0]["v"])
    }

    @Test
    fun `map literal in query`() {
        val result = executor.execute("RETURN {name: 'test', value: 42} AS m")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") val m = result.rows[0]["m"] as Map<String, Any?>
        assertEquals("test", m["name"])
        assertEquals(42, m["value"])
    }

    @Test
    fun `XOR in query`() {
        val result = executor.execute("RETURN true XOR false AS v")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["v"])
    }

    @Test
    fun `IS NULL in query`() {
        val result = executor.execute("RETURN null IS NULL AS v")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["v"])
    }

    @Test
    fun `IS NOT NULL in query`() {
        val result = executor.execute("RETURN 1 IS NOT NULL AS v")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["v"])
    }

    @Test
    fun `regex match in query`() {
        val result = executor.execute("RETURN 'hello' =~ 'hel.*' AS v")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["v"])
    }

    @Test
    fun `relationship with pipe-separated types`() {
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW|CALL]->(b) RETURN a.value, b.id LIMIT 5")
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `incoming relationship direction`() {
        val result = executor.execute("MATCH (p:ParameterNode)<-[:DATAFLOW]-(c:IntConstant) RETURN c.value, p.index")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["c.value"])
    }

    @Test
    fun `exponentiation operator in query`() {
        val result = executor.execute("RETURN 2 ^ 3 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(8L, result.rows[0]["v"])
    }

    @Test
    fun `modulo operator in query`() {
        val result = executor.execute("RETURN 10 % 3 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(1L, result.rows[0]["v"])
    }

    @Test
    fun `number starting with dot`() {
        val result = executor.execute("RETURN .5 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(0.5, result.rows[0]["v"])
    }

    @Test
    fun `large integer becomes long`() {
        val result = executor.execute("RETURN 3000000000 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(3000000000L, result.rows[0]["v"])
    }

    @Test
    fun `backtick-escaped identifier`() {
        val result = executor.execute("MATCH (n:`IntConstant`) RETURN n.value LIMIT 1")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `parameter in expression`() {
        // Parameters resolve from bindings which are empty, so they return null
        val result = executor.execute("RETURN \$param AS v")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `WITH with ORDER BY SKIP LIMIT`() {
        val result = executor.execute("MATCH (n:IntConstant) WITH n.value AS v ORDER BY v SKIP 1 LIMIT 1 RETURN v")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    @Test
    fun `ORDER BY with boolean values`() {
        val result = executor.execute("UNWIND [true, false, true] AS x RETURN x ORDER BY x")
        assertEquals(3, result.rows.size)
        assertEquals(false, result.rows[0]["x"])
    }

    @Test
    fun `ORDER BY with string values`() {
        val result = executor.execute("UNWIND ['c', 'a', 'b'] AS x RETURN x ORDER BY x")
        assertEquals(3, result.rows.size)
        assertEquals("a", result.rows[0]["x"])
    }

    @Test
    fun `aggregation with DISTINCT`() {
        val result = executor.execute("UNWIND [1, 1, 2, 2, 3] AS x RETURN count(DISTINCT x) AS cnt")
        assertEquals(1, result.rows.size)
        assertEquals(3L, result.rows[0]["cnt"])
    }

    @Test
    fun `OPTIONAL MATCH with no match fills nulls`() {
        // Use an IntConstant filter that won't match to trigger null-fill
        val result = executor.execute("OPTIONAL MATCH (m:IntConstant {value: 99999}) RETURN m")
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["m"])
    }

    @Test
    fun `match node with multiple labels`() {
        // IntConstant has labels IntConstant + Constant
        val result = executor.execute("MATCH (n:IntConstant:Constant) RETURN n.value")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `IN operator in WHERE`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value IN [7, 99] RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `SKIP with string evaluates to 0`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN x SKIP 'abc'")
        // 'abc' cannot be converted to int, defaults to 0
        assertEquals(3, result.rows.size)
    }

    @Test
    fun `no results returns empty columns`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 99999 RETURN n.id")
        assertEquals(0, result.rows.size)
        // columns still populated from RETURN items
        assertEquals(listOf("n.id"), result.columns)
    }

    @Test
    fun `incoming relationship without edge type`() {
        val result = executor.execute("MATCH (p:ParameterNode)<-[]-(c:IntConstant) RETURN c.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["c.value"])
    }

    @Test
    fun `both direction relationship`() {
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW]-(b:ParameterNode) RETURN a.value, b.index")
        // IntConstant -> ParameterNode via DATAFLOW (outgoing)
        assertTrue(result.rows.isNotEmpty())
    }
}
