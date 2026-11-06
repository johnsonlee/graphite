package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherExtendedExpressionsTest : CypherExecutorTestFixture() {

    @Test
    fun `NOT CONTAINS filters correctly`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_class NOT CONTAINS 'example' RETURN n.callee_class LIMIT 5")
        result.rows.forEach { row ->
            val cls = row["n.callee_class"] as String
            assertFalse(cls.contains("example"))
        }
    }

    @Test
    fun `NOT STARTS WITH filters correctly`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_class NOT STARTS WITH 'java' RETURN n.callee_class LIMIT 5")
        result.rows.forEach { row ->
            val cls = row["n.callee_class"] as String
            assertFalse(cls.startsWith("java"))
        }
    }

    @Test
    fun `NOT ENDS WITH filters correctly`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_class NOT ENDS WITH 'Service' RETURN n.callee_class LIMIT 5")
        result.rows.forEach { row ->
            val cls = row["n.callee_class"] as String
            assertFalse(cls.endsWith("Service"))
        }
    }

    @Test
    fun `Graph query extension function delegates to CypherExecutor`() {
        val result = graph.query("MATCH (n:IntConstant) RETURN n.value LIMIT 1")
        assertEquals(1, result.rows.size)
        assertEquals(listOf("n.value"), result.columns)
    }

    @Test
    fun `DISTINCT as expression atom inside arithmetic`() {
        // Exercises the DISTINCT branch in parseAtom (not the clause-level DISTINCT)
        val result = executor.execute("UNWIND [1, 1, 2, 2, 3] AS x RETURN sum(DISTINCT x) AS total")
        assertEquals(1, result.rows.size)
        assertEquals(6.0, result.rows[0]["total"])
    }

    @Test
    fun `math function with string argument exercises toDouble String branch`() {
        // CypherFunctions.toDouble has an uncovered String branch; ceil('3.7') triggers it
        val result = executor.execute("RETURN ceil('3.7') AS v")
        assertEquals(1, result.rows.size)
        assertEquals(4.0, result.rows[0]["v"])
    }

    @Test
    fun `percentileDisc aggregation`() {
        val result = executor.execute("UNWIND [10, 20, 30, 40, 50] AS x RETURN percentileDisc(x, 0.5) AS v")
        assertEquals(1, result.rows.size)
        // percentileDisc(0.5) on [10,20,30,40,50]: ceil(0.5*5)-1 = 2 -> 30.0
        assertEquals(30.0, result.rows[0]["v"])
    }

    @Test
    fun `arithmetic with boolean coerces to zero via toDouble else branch`() {
        // Boolean is not Number or String, so ExpressionEvaluator.toDouble returns 0.0
        val result = executor.execute("RETURN true + 1 AS v")
        assertEquals(1, result.rows.size)
        // true is not Number/String, toDouble returns 0.0; 0.0 + 1 = 1.0 (double result)
        assertEquals(1.0, result.rows[0]["v"])
    }

    @Test
    fun `arithmetic subtraction via ANTLR parser`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value - 1 AS diff")
        assertEquals(1, result.rows.size)
        assertEquals(41L, result.rows[0]["diff"])
    }

    @Test
    fun `arithmetic multiplication and division`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value * 2 AS mult, n.value / 7 AS div")
        assertEquals(1, result.rows.size)
        assertEquals(84L, result.rows[0]["mult"])
        assertEquals(6L, result.rows[0]["div"])
    }

    @Test
    fun `stdev aggregation in query`() {
        val result = executor.execute("UNWIND [2, 4, 4, 4, 5, 5, 7, 9] AS x RETURN stdev(x) AS v")
        assertEquals(1, result.rows.size)
        assertNotNull(result.rows[0]["v"])
    }

    @Test
    fun `stdevp aggregation in query`() {
        val result = executor.execute("UNWIND [2, 4, 4, 4, 5, 5, 7, 9] AS x RETURN stdevp(x) AS v")
        assertEquals(1, result.rows.size)
        assertNotNull(result.rows[0]["v"])
    }

    @Test
    fun `subscript access at index 0`() {
        val result = executor.execute("RETURN [10, 20, 30][0] AS first")
        assertEquals(1, result.rows.size)
        assertEquals(10, result.rows[0]["first"])
    }

    @Test
    fun `slice to only`() {
        val result = executor.execute("RETURN [1, 2, 3, 4][..2] AS v")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST") assertEquals(listOf(1, 2), result.rows[0]["v"])
    }

    @Test
    fun `relationship property constraint on DataFlowEdge kind`() {
        val result = executor.execute("MATCH (a:IntConstant)-[r:DATAFLOW {kind: 'PARAMETER_PASS'}]->(b:ParameterNode) RETURN a.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["a.value"])
    }

    @Test
    fun `aggregation with grouping key`() {
        // Exercises the grouped aggregation branch (groupByIndices non-empty + aggIndices)
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v, count(*) AS cnt ORDER BY v")
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["v"])
        assertEquals(1L, result.rows[0]["cnt"])
    }

    @Test
    fun `grouped aggregation with non-agg and agg columns`() {
        val result =
            executor.execute("UNWIND [{k: 'a', v: 1}, {k: 'a', v: 2}, {k: 'b', v: 3}] AS item " + "RETURN item.k AS key, count(*) AS cnt")
        // Groups: 'a' -> 2 rows, 'b' -> 1 row
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `string concatenation via plus operator`() {
        val result = executor.execute("RETURN 'hello' + ' ' + 'world' AS greeting")
        assertEquals(1, result.rows.size)
        assertEquals("hello world", result.rows[0]["greeting"])
    }

    @Test
    fun `unary minus operator`() {
        val result = executor.execute("RETURN -42 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(-42, result.rows[0]["v"])
    }

    @Test
    fun `floor with string argument exercises CypherFunctions toDouble String branch`() {
        val result = executor.execute("RETURN floor('3.7') AS v")
        assertEquals(1, result.rows.size)
        assertEquals(3.0, result.rows[0]["v"])
    }

    @Test
    fun `undirected relationship pattern`() {
        // (a)-[r]-(b) uses RelFullPattern or RelBothPattern
        val result = executor.execute("MATCH (a:IntConstant)-[r]-(b) RETURN a.value LIMIT 5")
        assertNotNull(result)
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `RETURN DISTINCT removes duplicates`() {
        val result = executor.execute("UNWIND [1, 1, 2, 2, 3] AS x RETURN DISTINCT x")
        assertEquals(3, result.rows.size)
    }

    @Test
    fun `ORDER BY DESCENDING keyword`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v DESCENDING")
        assertEquals(2, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
        assertEquals(7, result.rows[1]["v"])
    }

    @Test
    fun `ORDER BY DESC keyword`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v DESC")
        assertEquals(2, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
        assertEquals(7, result.rows[1]["v"])
    }

    @Test
    fun `comparison with null returns null`() {
        val result = executor.execute("RETURN null = 1 AS v")
        assertEquals(1, result.rows.size)
        // null = 1 returns null in three-valued logic
        assertNull(result.rows[0]["v"])
    }

    @Test
    fun `less than or equal comparison`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value <= 7 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `greater than or equal comparison`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value >= 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `property filter covers string predicates and numeric coercion branches`() {
        val field =
            FieldNode(
                NodeId.next(),
                FieldDescriptor(TypeDescriptor("com.example.Controller"), "displayName", TypeDescriptor("java.lang.String")),
                false)
        assertTrue(PropertyFilter("name", FilterOperator.STARTS_WITH, "display", emptySet(), "n").matches(field))
        assertTrue(PropertyFilter("name", FilterOperator.ENDS_WITH, "Name", emptySet(), "n").matches(field))
        assertTrue(PropertyFilter("name", FilterOperator.CONTAINS, "play", emptySet(), "n").matches(field))

        val constant = IntConstant(NodeId.next(), 42)
        assertTrue(PropertyFilter("value", FilterOperator.EQUALS, 42L, emptySet(), "n").matches(constant))
        assertFalse(PropertyFilter("value", FilterOperator.LESS_THAN, "x", emptySet(), "n").matches(constant))
    }
}
