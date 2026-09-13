package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicPropertyAccessTest {

    @Test
    fun `projected any exposes true false and unknown rather than hiding them in WHERE`() {
        val executor = CypherExecutor(fixture())
        val query = "MATCH (n) RETURN n.id AS id, " +
            "any(k IN keys(n) WHERE toString(n[k]) CONTAINS \$term) AS matched ORDER BY id"

        assertEquals(
            listOf(
                mapOf("id" to 1, "matched" to true),
                mapOf("id" to 2, "matched" to false),
                mapOf("id" to 3, "matched" to null),
                mapOf("id" to 4, "matched" to true)
            ),
            executor.execute(query, mapOf("term" to "Voucher")).rows
        )
        assertEquals(
            (1..4).map { id -> mapOf("id" to id, "matched" to null) },
            executor.execute(query, mapOf("term" to null)).rows
        )
    }

    @Test
    fun `any keys reads node properties and converts numeric values`() {
        val executor = CypherExecutor(fixture())
        val query = "MATCH (n) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS \$term) " +
            "RETURN n.id AS id ORDER BY id"

        assertEquals(
            listOf(mapOf("id" to 1), mapOf("id" to 4)),
            executor.execute(query, mapOf("term" to "Voucher")).rows
        )
        assertEquals(listOf(mapOf("id" to 2)), executor.execute(query, mapOf("term" to "105873")).rows)
        assertEquals(emptyList(), executor.execute(query, mapOf("term" to "not-present")).rows)
        assertEquals(emptyList(), executor.execute(query, mapOf("term" to null)).rows)
    }

    @Test
    fun `parameter keys preserve native values and missing properties`() {
        val executor = CypherExecutor(fixture())
        val query = "MATCH (n:IntConstant) RETURN n[\$key] AS value"

        assertEquals(listOf(mapOf("value" to 105873)), executor.execute(query, mapOf("key" to "value")).rows)
        for (key in listOf("missing", null, 0, true)) {
            assertEquals(listOf(mapOf("value" to null)), executor.execute(query, mapOf("key" to key)).rows)
        }
        assertEquals(
            listOf(mapOf("value" to null, "text" to null)),
            executor.execute("MATCH (n:NullConstant) RETURN n['value'] AS value, toString(n['value']) AS text").rows
        )
    }

    @Test
    fun `any keys on a qualified node can match graph metadata`() {
        val executor = CrossGraphCypherExecutor(
            listOf(CypherGraph("first", fixture()), CypherGraph("second", fixture()))
        )
        val result = executor.execute(
            "MATCH (n:StringConstant) WHERE any(k IN keys(n) WHERE toString(n[k]) = 'second') " +
                "RETURN n['graphId'] AS graph, n['qualifiedId'] AS id, n['value'] AS value"
        )

        assertEquals(
            listOf(
                mapOf(
                    "graph" to "second", "id" to "second:1", "value" to "WOWVoucher_FreeMonth",
                    RESULT_METADATA_KEY to mapOf(RESULT_GRAPH_IDS_KEY to listOf("second"))
                )
            ),
            result.rows
        )
    }

    @Test
    fun `dynamic method properties match the same values as static properties`() {
        val result = CypherExecutor(fixture()).execute(
            "MATCH (m:Method) WHERE any(k IN keys(m) WHERE toString(m[k]) CONTAINS 'Voucher') " +
                "RETURN m['class'] AS owner, m['name'] AS name, m['missing'] AS missing"
        )

        assertEquals(
            listOf(mapOf("owner" to "example.VoucherService", "name" to "apply", "missing" to null)),
            result.rows
        )
    }

    @Test
    fun `map subscripts preserve scalar values and null keys do not match`() {
        val result = CypherExecutor(fixture()).execute(
            "WITH {text: 'Voucher', count: 42, enabled: true, absent: null} AS m " +
                "RETURN m['text'] AS text, m['count'] AS count, m['enabled'] AS enabled, " +
                "m['absent'] AS absent, m['missing'] AS missing, m[0] AS numericKey, m[null] AS nullKey"
        )

        assertEquals(
            listOf(
                mapOf(
                    "text" to "Voucher", "count" to 42, "enabled" to true, "absent" to null,
                    "missing" to null, "numericKey" to null, "nullKey" to null
                )
            ),
            result.rows
        )
    }

    @Test
    fun `numeric list and string indexing retains negative and out of bounds behavior`() {
        val evaluator = ExpressionEvaluator()
        fun subscript(value: Any?, key: Any?): Any? = evaluator.evaluate(
            CypherExpr.Subscript(CypherExpr.Literal(value), CypherExpr.Literal(key)), emptyMap()
        )

        assertEquals(10, subscript(listOf(10, 20), 0))
        assertEquals(20, subscript(listOf(10, 20), -1))
        assertEquals("V", subscript("Voucher", 0))
        assertEquals("r", subscript("Voucher", -1))
        assertNull(subscript(listOf(10, 20), 2))
        assertNull(subscript("Voucher", -8))
        assertNull(subscript(listOf(10, 20), "0"))
        assertNull(subscript("Voucher", "0"))
        assertNull(subscript(42, "value"))
        assertNull(subscript(null, "value"))
    }

    private fun fixture(): Graph {
        val method = MethodDescriptor(
            TypeDescriptor("example.VoucherService"), "apply", emptyList(), TypeDescriptor("void")
        )
        return DefaultGraph.Builder().apply {
            addNode(StringConstant(NodeId(1), "WOWVoucher_FreeMonth"))
            addNode(IntConstant(NodeId(2), 105873))
            addNode(NullConstant(NodeId(3)))
            addNode(CallSiteNode(NodeId(4), method, method, 10, null, emptyList()))
            addMethod(method)
        }.build()
    }
}
