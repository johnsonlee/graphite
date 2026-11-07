package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.Edge
import io.johnsonlee.graphite.core.Node
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PipelineOrderingTest : QueryPipelineTestFixture() {

    @Test
    fun `order by ascending`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "val"))),
                CypherClause.OrderBy(listOf(SortItem(variable("val"), ascending = true))))
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["val"])
        assertEquals(42, result.rows[1]["val"])
    }

    @Test
    fun `order by descending`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "val"))),
                CypherClause.OrderBy(listOf(SortItem(variable("val"), ascending = false))))
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(42, result.rows[0]["val"])
        assertEquals(7, result.rows[1]["val"])
    }

    @Test
    fun `order by multi-key`() {
        // Unwind a list with duplicate first keys, sort by two keys
        val clauses =
            listOf(
                CypherClause.Unwind(
                    CypherExpr.ListLiteral(
                        listOf(
                            CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(3))),
                            CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(1))),
                            CypherExpr.MapLiteral(mapOf("a" to lit(2), "b" to lit(2))))),
                    "r"),
                CypherClause.Return(
                    listOf(
                        returnItem(CypherExpr.Property(variable("r"), "a"), "a"),
                        returnItem(CypherExpr.Property(variable("r"), "b"), "b"))),
                CypherClause.OrderBy(listOf(SortItem(variable("a"), ascending = true), SortItem(variable("b"), ascending = true))))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(1, result.rows[0]["a"])
        assertEquals(1, result.rows[0]["b"])
        assertEquals(1, result.rows[1]["a"])
        assertEquals(3, result.rows[1]["b"])
        assertEquals(2, result.rows[2]["a"])
    }

    @Test
    fun `ordered direct property limit preserves multi-key and stable ordering`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "CallSiteNode")))),
                CypherClause.Return(
                    listOf(
                        returnItem(prop(variable("n"), "caller_class"), "caller"),
                        returnItem(prop(variable("n"), "callee_class"), "callee"),
                        returnItem(prop(variable("n"), "line"), "line"))),
                CypherClause.OrderBy(
                    listOf(SortItem(variable("caller"), ascending = true), SortItem(variable("callee"), ascending = true))),
                CypherClause.Limit(lit(1)))

        val result = pipeline.execute(clauses)

        assertEquals(listOf("caller", "callee", "line"), result.columns)
        assertEquals(1, result.rows.size)
        assertEquals("com.example.Logger", result.rows.single()["callee"])
        assertEquals(20, result.rows.single()["line"])

        val stableResult =
            pipeline.execute(
                clauses.toMutableList().apply { this[2] = CypherClause.OrderBy(listOf(SortItem(variable("caller"), ascending = true))) })
        assertEquals(10, stableResult.rows.single()["line"])

        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1))
                .execute(
                    "MATCH (n:CallSiteNode) " +
                        "RETURN n.caller_class AS caller, n.callee_class AS callee, n.line AS line " +
                        "ORDER BY caller, callee LIMIT 1")
        }
    }

    @Test
    fun `skip rows`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4), lit(5))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.Skip(lit(2)))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(3, 4, 5), result.rows.map { it["x"] })
    }

    @Test
    fun `limit rows`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4), lit(5))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.Limit(lit(3)))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    @Test
    fun `skip and limit`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4), lit(5), lit(6), lit(7))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.Skip(lit(2)),
                CypherClause.Limit(lit(3)))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(3, 4, 5), result.rows.map { it["x"] })
    }

    @Test
    fun `order by ascending places nulls last`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(3), lit(null), lit(1))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(1, result.rows[0]["x"])
        assertEquals(3, result.rows[1]["x"])
        assertNull(result.rows[2]["x"])
    }

    @Test
    fun `order by descending places nulls first`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(3), lit(null), lit(1))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = false))))
        val result = pipeline.execute(clauses)
        assertEquals(listOf(null, 3, 1), result.rows.map { it["x"] })
    }

    @Test
    fun `ordered property limit fast path uses Cypher type and null ordering`() {
        fun query(ascending: Boolean, limit: Int) =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending))),
                CypherClause.Limit(lit(limit)))

        val ascending = pipeline.execute(query(ascending = true, limit = 3))
        assertEquals(listOf("hello", true, 7), ascending.rows.map { it["x"] })

        val descending = pipeline.execute(query(ascending = false, limit = 1))
        assertEquals(listOf(null), descending.rows.map { it["x"] })
    }

    @Test
    fun `order by uses Cypher total ordering across value types`() {
        val clauses =
            listOf(
                CypherClause.Unwind(
                    CypherExpr.ListLiteral(
                        listOf(
                            lit(1),
                            lit(true),
                            lit("text"),
                            CypherExpr.ListLiteral(listOf(lit(1))),
                            CypherExpr.MapLiteral(mapOf("a" to lit(1))),
                            lit(null))),
                    "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))
        val result = pipeline.execute(clauses)
        assertEquals(listOf(mapOf("a" to 1), listOf(1), "text", true, 1, null), result.rows.map { it["x"] })
    }

    @Test
    fun `order by places node relationship list and path in Cypher type order`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        CypherPattern(
                            elements =
                                listOf(
                                    nodePattern("n", "IntConstant", mapOf("value" to lit(42))),
                                    relPattern(variable = "r", type = "DATAFLOW"),
                                    nodePattern("m", "ParameterNode")),
                            pathVariable = "p"))),
                CypherClause.Unwind(
                    CypherExpr.ListLiteral(
                        listOf(
                            CypherExpr.MapLiteral(mapOf("a" to lit(1))),
                            variable("n"),
                            variable("r"),
                            CypherExpr.ListLiteral(listOf(lit("list"))),
                            variable("p"),
                            lit("text"),
                            lit(false),
                            lit(1.5),
                            lit(null))),
                    "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))

        val values = pipeline.execute(clauses).rows.map { it["x"] }
        assertEquals(mapOf("a" to 1), values[0])
        assertTrue(values[1] is Node)
        assertTrue(values[2] is Edge)
        assertEquals(listOf("list"), values[3])
        assertTrue(values[4] is PathFinder.Path)
        assertEquals(listOf("text", false, 1.5, null), values.drop(5))
    }

    @Test
    fun `order by compares lists lexicographically using Cypher value ordering`() {
        val lists =
            listOf(emptyList(), listOf("a"), listOf("a", 1), listOf(1), listOf(1, "a"), listOf(1, null), listOf(null, 1), listOf(null, 2))
        val clauses =
            listOf(
                CypherClause.Unwind(
                    CypherExpr.ListLiteral(lists.reversed().map { values -> CypherExpr.ListLiteral(values.map(::lit)) }), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))

        val result = pipeline.execute(clauses)
        assertEquals(lists, result.rows.map { it["x"] })
    }

    @Test
    fun `order by compares maps deterministically`() {
        val maps = listOf(mapOf("a" to 1), mapOf("a" to 2), mapOf("b" to 1), mapOf("a" to 1, "b" to 0))
        val clauses =
            listOf(
                CypherClause.Unwind(
                    CypherExpr.ListLiteral(maps.reversed().map { entries -> CypherExpr.MapLiteral(entries.mapValues { lit(it.value) }) }),
                    "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))

        assertEquals(maps, pipeline.execute(clauses).rows.map { it["x"] })
    }

    @Test
    fun `order by treats NaN as the greatest number`() {
        val clauses =
            listOf(
                CypherClause.Unwind(
                    CypherExpr.ListLiteral(listOf(lit(Double.NaN), lit(1.0), lit(Double.NaN), lit(Double.POSITIVE_INFINITY))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))

        val values = pipeline.execute(clauses).rows.map { it["x"] as Double }
        assertEquals(listOf(1.0, Double.POSITIVE_INFINITY), values.take(2))
        assertTrue(values.drop(2).all(Double::isNaN))
    }

    @Test
    fun `order by preserves numeric ordering across JVM number representations`() {
        fun ordered(values: List<Number>): List<Any?> {
            val clauses =
                listOf(
                    CypherClause.Unwind(CypherExpr.ListLiteral(values.map(::lit)), "x"),
                    CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                    CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))
            return pipeline.execute(clauses).rows.map { it["x"] }
        }

        assertEquals(listOf<Byte>(1, 2, 3), ordered(listOf<Byte>(3, 1, 2)))
        assertEquals(listOf<Short>(1, 2, 3), ordered(listOf<Short>(3, 1, 2)))
        assertEquals(listOf(1L, 2L, 3L), ordered(listOf(3L, 1L, 2L)))
        assertEquals(listOf(1.0f, 2.0f, 3.0f), ordered(listOf(3.0f, 1.0f, 2.0f)))
        assertEquals(
            listOf<Number>(1, 1.5f, 2L, Double.POSITIVE_INFINITY, Double.NaN),
            ordered(listOf(Double.NaN, 2L, 1.5f, Double.POSITIVE_INFINITY, 1)))
    }

    @Test
    fun `order by compares identities within node relationship and path types`() {
        val nodes =
            pipeline.execute(
                listOf(
                    CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                    CypherClause.Return(listOf(returnItem(variable("n"), "value"))),
                    CypherClause.OrderBy(listOf(SortItem(variable("value"), ascending = true)))))
        assertEquals(listOf(intConst42, intConst7), nodes.rows.map { (it["value"] as Node).id })

        val relationships =
            pipeline.execute(
                listOf(
                    CypherClause.Match(listOf(pattern(nodePattern("a"), relPattern(variable = "r", type = "DATAFLOW"), nodePattern("b")))),
                    CypherClause.Return(listOf(returnItem(variable("r"), "value"))),
                    CypherClause.OrderBy(listOf(SortItem(variable("value"), ascending = true)))))
        val orderedEdges = relationships.rows.map { it["value"] as Edge }
        assertEquals(orderedEdges.sortedWith(compareBy({ it.from.value }, { it.to.value }, { it::class.java.name })), orderedEdges)

        val paths =
            pipeline.execute(
                listOf(
                    CypherClause.Match(
                        listOf(
                            CypherPattern(
                                elements = listOf(nodePattern("a"), relPattern(type = "DATAFLOW"), nodePattern("b")), pathVariable = "p"))),
                    CypherClause.Return(listOf(returnItem(variable("p"), "value"))),
                    CypherClause.OrderBy(listOf(SortItem(variable("value"), ascending = true)))))
        val orderedPaths = paths.rows.map { it["value"] as PathFinder.Path }
        assertEquals(
            orderedPaths.sortedWith(
                compareBy({ it.nodes.first().id.value }, { it.edges.first().from.value }, { it.edges.first().to.value })),
            orderedPaths)
    }

    @Test
    fun `order by with booleans`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(true), lit(false), lit(true))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(false, result.rows[0]["x"])
    }

    @Test
    fun `order by with strings`() {
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit("c"), lit("a"), lit("b"))), "x"),
                CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
                CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true))))
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals("a", result.rows[0]["x"])
        assertEquals("b", result.rows[1]["x"])
        assertEquals("c", result.rows[2]["x"])
    }
}
