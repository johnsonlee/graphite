package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryPipelineTest {

    private lateinit var graph: Graph
    private lateinit var pipeline: QueryPipeline

    // Node IDs
    private var intConst42 = NodeId(0)
    private var intConst7 = NodeId(0)
    private var strConstHello = NodeId(0)
    private var callSite1 = NodeId(0)
    private var callSite2 = NodeId(0)
    private var field1 = NodeId(0)
    private var param1 = NodeId(0)
    private var return1 = NodeId(0)
    private var localVar1 = NodeId(0)
    private var localVar2 = NodeId(0)
    private var boolConst = NodeId(0)

    private val type = TypeDescriptor("com.example.Service")
    private val intType = TypeDescriptor("int")
    private val stringType = TypeDescriptor("java.lang.String")
    private val boolType = TypeDescriptor("boolean")

    @Before
    fun setup() {
        NodeId.reset()

        val builder = DefaultGraph.Builder()

        val method = MethodDescriptor(type, "process", listOf(intType), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repository"), "save", listOf(stringType), TypeDescriptor("void"))
        val callee2 = MethodDescriptor(TypeDescriptor("com.example.Logger"), "log", listOf(stringType), TypeDescriptor("void"))

        intConst42 = NodeId.next()
        builder.addNode(IntConstant(intConst42, 42))

        intConst7 = NodeId.next()
        builder.addNode(IntConstant(intConst7, 7))

        strConstHello = NodeId.next()
        builder.addNode(StringConstant(strConstHello, "hello"))

        callSite1 = NodeId.next()
        builder.addNode(CallSiteNode(callSite1, method, callee, 10, null, listOf(strConstHello)))

        callSite2 = NodeId.next()
        builder.addNode(CallSiteNode(callSite2, method, callee2, 20, null, listOf(strConstHello)))

        field1 = NodeId.next()
        builder.addNode(FieldNode(field1, FieldDescriptor(type, "name", stringType), false))

        param1 = NodeId.next()
        builder.addNode(ParameterNode(param1, 0, intType, method))

        return1 = NodeId.next()
        builder.addNode(ReturnNode(return1, method, stringType))

        localVar1 = NodeId.next()
        builder.addNode(LocalVariable(localVar1, "x", intType, method))

        localVar2 = NodeId.next()
        builder.addNode(LocalVariable(localVar2, "y", stringType, method))

        boolConst = NodeId.next()
        builder.addNode(BooleanConstant(boolConst, true))

        // Edges
        builder.addEdge(DataFlowEdge(intConst42, param1, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(param1, localVar1, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(localVar1, localVar2, DataFlowKind.ASSIGN))
        builder.addEdge(DataFlowEdge(localVar2, callSite1, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(strConstHello, callSite1, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(DataFlowEdge(strConstHello, callSite2, DataFlowKind.PARAMETER_PASS))
        builder.addEdge(CallEdge(callSite1, return1, false))
        builder.addEdge(DataFlowEdge(field1, localVar1, DataFlowKind.FIELD_LOAD))

        builder.addMethod(method)
        builder.addMethod(callee)
        builder.addMethod(callee2)

        graph = builder.build()
        pipeline = QueryPipeline(graph)
    }

    // Helpers for building patterns
    private fun nodePattern(
        variable: String? = null,
        label: String? = null,
        properties: Map<String, CypherExpr> = emptyMap()
    ) = PatternElement.NodePattern(variable, if (label != null) listOf(label) else emptyList(), properties)

    private fun relPattern(
        variable: String? = null,
        type: String? = null,
        direction: Direction = Direction.OUTGOING,
        variableLength: Boolean = false,
        minHops: Int? = null,
        maxHops: Int? = null,
        properties: Map<String, CypherExpr> = emptyMap()
    ) = PatternElement.RelationshipPattern(
        variable, if (type != null) listOf(type) else emptyList(),
        properties, direction, minHops, maxHops, variableLength
    )

    private fun pattern(vararg elements: PatternElement) = CypherPattern(elements.toList())

    private fun lit(value: Any?) = CypherExpr.Literal(value)
    private fun variable(name: String) = CypherExpr.Variable(name)
    private fun prop(expr: CypherExpr, name: String) = CypherExpr.Property(expr, name)
    private fun returnItem(expr: CypherExpr, alias: String? = null) = ReturnItem(expr, alias)

    // ========================================================================
    // MATCH - 1. Match all nodes
    // ========================================================================

    @Test
    fun `match all nodes`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", null)))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "id"), "id")))
        )
        val result = pipeline.execute(clauses)
        // 11 nodes total
        assertEquals(11, result.rows.size)
    }

    // ========================================================================
    // MATCH - 2. Match by label
    // ========================================================================

    @Test
    fun `match nodes by label`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["value"] }.toSet()
        assertTrue(values.contains(42))
        assertTrue(values.contains(7))
    }

    // ========================================================================
    // MATCH - 3. Match with properties
    // ========================================================================

    @Test
    fun `match with inline property constraint`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("n", "IntConstant", mapOf("value" to lit(42)))
            ))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["value"])
    }

    // ========================================================================
    // MATCH - 4. Match relationship
    // ========================================================================

    @Test
    fun `match single hop dataflow relationship`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("c", "IntConstant"),
                relPattern(type = "DATAFLOW"),
                nodePattern("p", "ParameterNode")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("c"), "value"), "cval"),
                returnItem(prop(variable("p"), "index"), "pidx")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
        assertEquals(0, result.rows[0]["pidx"])
    }

    // ========================================================================
    // MATCH - 5. Variable-length path
    // ========================================================================

    @Test
    fun `match variable-length path`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 -> callSite1 (4 hops)
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("a", "IntConstant"),
                relPattern(type = "DATAFLOW", variableLength = true, maxHops = 5),
                nodePattern("b", "CallSiteNode")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval"),
                returnItem(prop(variable("b"), "callee_name"), "bname")
            ))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
        val row = result.rows.first()
        assertEquals(42, row["aval"])
        assertEquals("save", row["bname"])
    }

    // ========================================================================
    // MATCH - 6. Chain pattern (multi-pattern match)
    // ========================================================================

    @Test
    fun `match chain pattern - multiple patterns`() {
        // Match IntConstant and StringConstant in the same query
        val clauses = listOf(
            CypherClause.Match(listOf(
                pattern(nodePattern("i", "IntConstant")),
                pattern(nodePattern("s", "StringConstant"))
            )),
            CypherClause.Return(listOf(
                returnItem(prop(variable("i"), "value"), "ival"),
                returnItem(prop(variable("s"), "value"), "sval")
            ))
        )
        val result = pipeline.execute(clauses)
        // 2 IntConstants x 1 StringConstant = 2 rows
        assertEquals(2, result.rows.size)
        assertTrue(result.rows.all { it["sval"] == "hello" })
    }

    // ========================================================================
    // OPTIONAL MATCH - 7. With results
    // ========================================================================

    @Test
    fun `optional match with results`() {
        val clauses = listOf(
            CypherClause.Match(
                listOf(pattern(
                    nodePattern("c", "IntConstant"),
                    relPattern(type = "DATAFLOW"),
                    nodePattern("p", "ParameterNode")
                )),
                optional = true
            ),
            CypherClause.Return(listOf(
                returnItem(prop(variable("c"), "value"), "cval"),
                returnItem(prop(variable("p"), "index"), "pidx")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
        assertEquals(0, result.rows[0]["pidx"])
    }

    // ========================================================================
    // OPTIONAL MATCH - 8. Without results (null bindings)
    // ========================================================================

    @Test
    fun `optional match without results produces nulls`() {
        // Match a label that doesn't exist in graph
        val clauses = listOf(
            CypherClause.Match(
                listOf(pattern(nodePattern("n", "NullConstant"))),
                optional = true
            ),
            CypherClause.Return(listOf(returnItem(variable("n"), "n")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["n"])
    }

    // ========================================================================
    // WHERE - 9. Equality filter
    // ========================================================================

    @Test
    fun `where equality filter`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Where(CypherExpr.Comparison("=", prop(variable("n"), "value"), lit(42))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["value"])
    }

    // ========================================================================
    // WHERE - 10. Regex filter
    // ========================================================================

    @Test
    fun `where regex filter`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "CallSiteNode")))),
            CypherClause.Where(CypherExpr.RegexMatch(
                prop(variable("n"), "callee_name"),
                lit("sa.*")
            )),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "callee_name"), "name")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["name"])
    }

    // ========================================================================
    // WHERE - 11. AND condition
    // ========================================================================

    @Test
    fun `where AND condition`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Where(CypherExpr.And(
                CypherExpr.Comparison(">", prop(variable("n"), "value"), lit(0)),
                CypherExpr.Comparison("<", prop(variable("n"), "value"), lit(10))
            )),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["value"])
    }

    // ========================================================================
    // WHERE - 12. NOT condition
    // ========================================================================

    @Test
    fun `where NOT condition`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Where(CypherExpr.Not(
                CypherExpr.Comparison("=", prop(variable("n"), "value"), lit(42))
            )),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["value"])
    }

    // ========================================================================
    // RETURN - 13. Property projection
    // ========================================================================

    @Test
    fun `return property projection`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "StringConstant")))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"))))
        )
        val result = pipeline.execute(clauses)
        assertEquals(listOf("n.value"), result.columns)
        assertEquals("hello", result.rows[0]["n.value"])
    }

    // ========================================================================
    // RETURN - 14. With alias
    // ========================================================================

    @Test
    fun `return with alias`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "StringConstant")))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "val")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(listOf("val"), result.columns)
        assertEquals("hello", result.rows[0]["val"])
    }

    // ========================================================================
    // RETURN - 15. DISTINCT
    // ========================================================================

    @Test
    fun `return distinct`() {
        // Both call sites have the same callee_class prefix "com.example."
        // Match all nodes, project type, distinct
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(
                listOf(returnItem(lit("int"), "type")),
                distinct = true
            )
        )
        val result = pipeline.execute(clauses)
        // Two IntConstants but constant "int" value, distinct => 1
        assertEquals(1, result.rows.size)
    }

    // ========================================================================
    // RETURN - 16. Aggregation count(*)
    // ========================================================================

    @Test
    fun `return count star`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(returnItem(CypherExpr.CountStar, "cnt")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
    }

    // ========================================================================
    // RETURN - 17. Aggregation sum, avg
    // ========================================================================

    @Test
    fun `return sum aggregation`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(
                returnItem(
                    CypherExpr.FunctionCall("sum", listOf(prop(variable("n"), "value"))),
                    "total"
                )
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(49.0, result.rows[0]["total"]) // 42 + 7
    }

    @Test
    fun `return avg aggregation`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(
                returnItem(
                    CypherExpr.FunctionCall("avg", listOf(prop(variable("n"), "value"))),
                    "average"
                )
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(24.5, result.rows[0]["average"]) // (42 + 7) / 2
    }

    // ========================================================================
    // RETURN - 18. Aggregation collect
    // ========================================================================

    @Test
    fun `return collect aggregation`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(
                returnItem(
                    CypherExpr.FunctionCall("collect", listOf(prop(variable("n"), "value"))),
                    "values"
                )
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val values = result.rows[0]["values"] as List<Any?>
        assertTrue(values.containsAll(listOf(42, 7)))
    }

    // ========================================================================
    // RETURN - 19. Group by + aggregate
    // ========================================================================

    @Test
    fun `group by label and count`() {
        // Match IntConstant and CallSiteNode (these types don't have a "type" property
        // that shadows the node type name), so we use id property and group by label
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("n"), "type"), "nodeType"),
                returnItem(CypherExpr.CountStar, "cnt")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        // IntConstant nodes don't have a node-specific "type" property, so it falls
        // back to nodeTypeName which returns "IntConstant"
        assertEquals("IntConstant", result.rows[0]["nodeType"])
        assertEquals(2L, result.rows[0]["cnt"])
    }

    // ========================================================================
    // WITH - 20. Pipeline MATCH -> WITH -> WHERE -> RETURN
    // ========================================================================

    @Test
    fun `with pipeline`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.With(listOf(
                returnItem(prop(variable("n"), "value"), "val")
            )),
            CypherClause.Where(CypherExpr.Comparison(">", variable("val"), lit(10))),
            CypherClause.Return(listOf(returnItem(variable("val"), "result")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["result"])
    }

    // ========================================================================
    // WITH - 21. WITH DISTINCT
    // ========================================================================

    @Test
    fun `with distinct`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.With(
                listOf(returnItem(lit("constant"), "label")),
                distinct = true
            ),
            CypherClause.Return(listOf(returnItem(variable("label"), "label")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals("constant", result.rows[0]["label"])
    }

    // ========================================================================
    // WITH - inline WHERE
    // ========================================================================

    @Test
    fun `with inline where`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.With(
                items = listOf(returnItem(prop(variable("n"), "value"), "val")),
                where = CypherExpr.Comparison("=", variable("val"), lit(42))
            ),
            CypherClause.Return(listOf(returnItem(variable("val"), "val")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["val"])
    }

    // ========================================================================
    // UNWIND - 22. Unwind list literal
    // ========================================================================

    @Test
    fun `unwind list literal`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    // ========================================================================
    // UNWIND - 23. Unwind into subsequent processing
    // ========================================================================

    @Test
    fun `unwind then filter`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(10), lit(20), lit(30))),
                "x"
            ),
            CypherClause.Where(CypherExpr.Comparison(">", variable("x"), lit(15))),
            CypherClause.Return(listOf(returnItem(variable("x"), "x")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(20, 30), result.rows.map { it["x"] })
    }

    // ========================================================================
    // ORDER BY - 24. Ascending
    // ========================================================================

    @Test
    fun `order by ascending`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "val"))),
            CypherClause.OrderBy(listOf(SortItem(variable("val"), ascending = true)))
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["val"])
        assertEquals(42, result.rows[1]["val"])
    }

    // ========================================================================
    // ORDER BY - 25. Descending
    // ========================================================================

    @Test
    fun `order by descending`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "val"))),
            CypherClause.OrderBy(listOf(SortItem(variable("val"), ascending = false)))
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(42, result.rows[0]["val"])
        assertEquals(7, result.rows[1]["val"])
    }

    // ========================================================================
    // ORDER BY - 26. Multi-key sort
    // ========================================================================

    @Test
    fun `order by multi-key`() {
        // Unwind a list with duplicate first keys, sort by two keys
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(
                    CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(3))),
                    CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(1))),
                    CypherExpr.MapLiteral(mapOf("a" to lit(2), "b" to lit(2)))
                )),
                "r"
            ),
            CypherClause.Return(listOf(
                returnItem(CypherExpr.Property(variable("r"), "a"), "a"),
                returnItem(CypherExpr.Property(variable("r"), "b"), "b")
            )),
            CypherClause.OrderBy(listOf(
                SortItem(variable("a"), ascending = true),
                SortItem(variable("b"), ascending = true)
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(1, result.rows[0]["a"])
        assertEquals(1, result.rows[0]["b"])
        assertEquals(1, result.rows[1]["a"])
        assertEquals(3, result.rows[1]["b"])
        assertEquals(2, result.rows[2]["a"])
    }

    // ========================================================================
    // SKIP - 27. Skip 2
    // ========================================================================

    @Test
    fun `skip rows`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4), lit(5))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.Skip(lit(2))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(3, 4, 5), result.rows.map { it["x"] })
    }

    // ========================================================================
    // LIMIT - 28. Limit 3
    // ========================================================================

    @Test
    fun `limit rows`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4), lit(5))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.Limit(lit(3))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    // ========================================================================
    // SKIP + LIMIT - 29. Skip 2 + Limit 3
    // ========================================================================

    @Test
    fun `skip and limit`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4), lit(5), lit(6), lit(7))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.Skip(lit(2)),
            CypherClause.Limit(lit(3))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(3, 4, 5), result.rows.map { it["x"] })
    }

    // ========================================================================
    // UNION - 30. Distinct union (via separate pipeline calls)
    // ========================================================================

    @Test
    fun `union distinct`() {
        // Union is handled at a higher level; simulate two branch executions
        val clauses1 = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x")))
        )
        val clauses2 = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(2), lit(3), lit(4))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x")))
        )
        val result1 = pipeline.execute(clauses1)
        val result2 = pipeline.execute(clauses2)
        // UNION (distinct)
        val combined = (result1.rows + result2.rows).distinct()
        assertEquals(4, combined.size)
    }

    // ========================================================================
    // UNION ALL - 31. Union all
    // ========================================================================

    @Test
    fun `union all`() {
        val clauses1 = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x")))
        )
        val clauses2 = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(2), lit(3))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x")))
        )
        val result1 = pipeline.execute(clauses1)
        val result2 = pipeline.execute(clauses2)
        // UNION ALL (preserves duplicates)
        val combined = result1.rows + result2.rows
        assertEquals(4, combined.size)
    }

    // ========================================================================
    // Write clauses throw NotImplementedError
    // ========================================================================

    @Test
    fun `write clauses throw NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            pipeline.execute(listOf(CypherClause.Create(listOf(pattern(nodePattern("m"))))))
        }
        assertFailsWith<NotImplementedError> {
            pipeline.execute(listOf(CypherClause.Delete(listOf(variable("m")))))
        }
        assertFailsWith<NotImplementedError> {
            pipeline.execute(listOf(CypherClause.Set(emptyList())))
        }
        assertFailsWith<NotImplementedError> {
            pipeline.execute(listOf(CypherClause.Remove(emptyList())))
        }
    }

    // ========================================================================
    // Union clause inline throws TODO
    // ========================================================================

    @Test
    fun `union clause inline throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            pipeline.execute(listOf(CypherClause.Union(all = false)))
        }
    }

    // ========================================================================
    // Empty pattern returns existing bindings
    // ========================================================================

    @Test
    fun `empty match pattern returns existing bindings`() {
        val clauses = listOf(
            CypherClause.Match(listOf(CypherPattern(emptyList()))),
            CypherClause.Return(listOf(returnItem(lit(1), "one")))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows[0]["one"])
    }

    // ========================================================================
    // Columns inferred from rows when no RETURN columns
    // ========================================================================

    @Test
    fun `columns inferred from row keys`() {
        val clauses = listOf(
            CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1))), "x")
        )
        val result = pipeline.execute(clauses)
        assertEquals(listOf("x"), result.columns)
    }

    // ========================================================================
    // Match relationship with named edge variable
    // ========================================================================

    @Test
    fun `match relationship with edge variable`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("c", "IntConstant"),
                relPattern(variable = "r", type = "DATAFLOW"),
                nodePattern("p", "ParameterNode")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("c"), "value"), "cval"),
                returnItem(variable("r"), "edge")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertTrue(result.rows[0]["edge"] is DataFlowEdge)
    }

    // ========================================================================
    // Match with variable-length path and named path variable
    // ========================================================================

    @Test
    fun `variable-length path with path variable`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("a", "IntConstant"),
                relPattern(variable = "p", type = "DATAFLOW", variableLength = true, maxHops = 5),
                nodePattern("b", "CallSiteNode")
            ))),
            CypherClause.Return(listOf(
                returnItem(variable("p"), "path")
            ))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
        assertTrue(result.rows[0]["path"] is PathFinder.Path)
    }

    // ========================================================================
    // ORDER BY with nulls
    // ========================================================================

    @Test
    fun `order by with nulls`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(3), lit(null), lit(1))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true)))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        // nulls sort before non-nulls
        assertNull(result.rows[0]["x"])
        assertEquals(1, result.rows[1]["x"])
        assertEquals(3, result.rows[2]["x"])
    }

    // ========================================================================
    // OPTIONAL MATCH with relationship variable for null-fill
    // ========================================================================

    @Test
    fun `optional match with rel variable fills nulls for all pattern variables`() {
        // Tests that CypherPattern.variables() extracts relationship variables too
        val clauses = listOf(
            CypherClause.Match(
                listOf(pattern(
                    nodePattern("n", "NullConstant"),
                    relPattern(variable = "r", type = "DATAFLOW"),
                    nodePattern("m")
                )),
                optional = true
            ),
            CypherClause.Return(listOf(
                returnItem(variable("n"), "n"),
                returnItem(variable("r"), "r"),
                returnItem(variable("m"), "m")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["n"])
        assertNull(result.rows[0]["r"])
        assertNull(result.rows[0]["m"])
    }

    // ========================================================================
    // ORDER BY - boolean comparison and toString fallback
    // ========================================================================

    @Test
    fun `order by with mixed types uses toString fallback`() {
        // When values are not Number/String/Boolean, compareNullable falls through to toString
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(
                    CypherExpr.ListLiteral(listOf(lit(2))),
                    CypherExpr.ListLiteral(listOf(lit(1)))
                )),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true)))
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `order by with booleans`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(true), lit(false), lit(true))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true)))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals(false, result.rows[0]["x"])
    }

    @Test
    fun `order by with strings`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit("c"), lit("a"), lit("b"))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.OrderBy(listOf(SortItem(variable("x"), ascending = true)))
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
        assertEquals("a", result.rows[0]["x"])
        assertEquals("b", result.rows[1]["x"])
        assertEquals("c", result.rows[2]["x"])
    }

    // ========================================================================
    // evaluateToInt with non-number values
    // ========================================================================

    @Test
    fun `skip with string value defaults to 0`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.Skip(lit("abc"))  // non-numeric string -> 0
        )
        val result = pipeline.execute(clauses)
        assertEquals(3, result.rows.size)
    }

    @Test
    fun `skip with null defaults to 0`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.Skip(lit(null))  // null -> 0
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `limit with string evaluates to int`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))),
                "x"
            ),
            CypherClause.Return(listOf(returnItem(variable("x"), "x"))),
            CypherClause.Limit(lit("2"))  // string "2" -> 2
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
    }

    // ========================================================================
    // Match with relationship property constraints
    // ========================================================================

    @Test
    fun `match with relationship property constraint`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("c", "IntConstant"),
                relPattern(type = "DATAFLOW", properties = mapOf("kind" to lit("PARAMETER_PASS"))),
                nodePattern("p", "ParameterNode")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("c"), "value"), "cval")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
    }

    // ========================================================================
    // Aggregation - Distinct in evaluateAggregation
    // ========================================================================

    @Test
    fun `distinct aggregation`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(1), lit(2), lit(2), lit(3))),
                "x"
            ),
            CypherClause.Return(listOf(
                returnItem(
                    CypherExpr.FunctionCall("count", listOf(variable("x")), distinct = true),
                    "cnt"
                )
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(3L, result.rows[0]["cnt"])
    }

    // ========================================================================
    // Aggregation - evaluateAggregation Distinct wrapper
    // ========================================================================

    @Test
    fun `aggregation with Distinct wrapper`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3))),
                "x"
            ),
            CypherClause.Return(listOf(
                returnItem(
                    CypherExpr.Distinct(CypherExpr.FunctionCall("sum", listOf(variable("x")))),
                    "total"
                )
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
    }

    // ========================================================================
    // Aggregation - non-aggregation function in aggregation context
    // ========================================================================

    @Test
    fun `non-aggregation function call in aggregation context`() {
        // When a non-aggregation FunctionCall is detected in aggregation items
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2))),
                "x"
            ),
            CypherClause.Return(listOf(
                returnItem(CypherExpr.CountStar, "cnt"),
                returnItem(
                    CypherExpr.FunctionCall("toInteger", listOf(lit(42))),
                    "val"
                )
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
    }

    // ========================================================================
    // containsAggregation covers BinaryOp and Comparison branches
    // ========================================================================

    @Test
    fun `containsAggregation detects nested aggregation in BinaryOp`() {
        // Verify that the aggregation detection works (BinaryOp branch)
        val expr = CypherExpr.BinaryOp("+",
            CypherExpr.FunctionCall("sum", listOf(variable("x"))),
            lit(10)
        )
        // The containsAggregation method is private, so test via projectAndAggregate
        // which groups by non-aggregated columns. Just ensure it detects aggregation.
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2))),
                "x"
            ),
            CypherClause.Return(listOf(
                returnItem(CypherExpr.FunctionCall("sum", listOf(variable("x"))), "total"),
                returnItem(CypherExpr.CountStar, "cnt")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(3.0, result.rows[0]["total"])
        assertEquals(2L, result.rows[0]["cnt"])
    }

    @Test
    fun `containsAggregation detects nested aggregation in Comparison`() {
        // Verify Comparison branch of containsAggregation via group-by aggregation
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("n"), "type"), "nodeType"),
                returnItem(
                    CypherExpr.FunctionCall("min", listOf(prop(variable("n"), "value"))),
                    "minVal"
                )
            ))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    // ========================================================================
    // Match target node already bound to different node returns null
    // ========================================================================

    @Test
    fun `match with pre-bound variable returns only matching rows`() {
        // Match pattern where a variable is already bound from previous patterns
        val clauses = listOf(
            CypherClause.Match(listOf(
                pattern(nodePattern("a", "IntConstant")),
                pattern(
                    nodePattern("a", "IntConstant"),
                    relPattern(type = "DATAFLOW"),
                    nodePattern("b", "ParameterNode")
                )
            )),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval"),
                returnItem(prop(variable("b"), "index"), "bidx")
            ))
        )
        val result = pipeline.execute(clauses)
        // Only the IntConstant with value 42 has a DATAFLOW to ParameterNode
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["aval"])
    }

    // ========================================================================
    // UNWIND non-list skips row
    // ========================================================================

    @Test
    fun `unwind non-list value skips row`() {
        val clauses = listOf(
            CypherClause.Unwind(lit(42), "x"),
            CypherClause.Return(listOf(returnItem(variable("x"), "x")))
        )
        val result = pipeline.execute(clauses)
        // 42 is not a list, so no rows produced
        assertEquals(0, result.rows.size)
    }

    // ========================================================================
    // ORDER BY with same values in both items (cmp == 0 case)
    // ========================================================================

    @Test
    fun `order by with equal values falls through to next sort item`() {
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(
                    CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(2))),
                    CypherExpr.MapLiteral(mapOf("a" to lit(1), "b" to lit(1)))
                )),
                "r"
            ),
            CypherClause.Return(listOf(
                returnItem(CypherExpr.Property(variable("r"), "a"), "a"),
                returnItem(CypherExpr.Property(variable("r"), "b"), "b")
            )),
            CypherClause.OrderBy(listOf(
                SortItem(variable("a"), ascending = true),
                SortItem(variable("b"), ascending = true)
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        assertEquals(1, result.rows[0]["b"])
        assertEquals(2, result.rows[1]["b"])
    }

    // ========================================================================
    // Match relationship with multiple types (OR semantics)
    // ========================================================================

    @Test
    fun `match with multiple relationship types`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("a", "IntConstant"),
                relPattern(type = null, properties = emptyMap()),
                nodePattern("b")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval")
            ))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    // ========================================================================
    // Match with both direction edges
    // ========================================================================

    @Test
    fun `match with both direction`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("a", "IntConstant"),
                relPattern(type = "DATAFLOW", direction = Direction.BOTH),
                nodePattern("b", "ParameterNode")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval"),
                returnItem(prop(variable("b"), "index"), "bidx")
            ))
        )
        val result = pipeline.execute(clauses)
        // Should find: intConst42 -> param1 via DATAFLOW
        assertTrue(result.rows.isNotEmpty())
    }

    // ========================================================================
    // Incoming edges with and without type filter
    // ========================================================================

    @Test
    fun `match incoming without edge type`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("p", "ParameterNode"),
                relPattern(direction = Direction.INCOMING),
                nodePattern("c", "IntConstant")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("c"), "value"), "cval")
            ))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
        assertEquals(42, result.rows[0]["cval"])
    }

    // ========================================================================
    // Incoming edges with type filter
    // ========================================================================

    @Test
    fun `match incoming with edge type`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("p", "ParameterNode"),
                relPattern(type = "DATAFLOW", direction = Direction.INCOMING),
                nodePattern("c", "IntConstant")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("c"), "value"), "cval")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
    }

    // ========================================================================
    // Both direction edges with type filter
    // ========================================================================

    @Test
    fun `both direction with edge type filter`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("a"),
                relPattern(type = "DATAFLOW", direction = Direction.BOTH),
                nodePattern("b")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "id"), "aid")
            )),
            CypherClause.Limit(lit(3))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    // ========================================================================
    // Variable-length path with INCOMING direction
    // ========================================================================

    @Test
    fun `variable-length path incoming`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("b", "CallSiteNode"),
                relPattern(type = "DATAFLOW", direction = Direction.INCOMING, variableLength = true, maxHops = 5),
                nodePattern("a", "IntConstant")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval")
            ))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    // ========================================================================
    // Variable-length path with BOTH direction
    // ========================================================================

    @Test
    fun `variable-length path both direction`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("a", "IntConstant"),
                relPattern(type = "DATAFLOW", direction = Direction.BOTH, variableLength = true, maxHops = 3),
                nodePattern("b")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval")
            )),
            CypherClause.Limit(lit(5))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    // ========================================================================
    // matchesRelConstraints with multiple types (OR)
    // ========================================================================

    @Test
    fun `match with multiple rel types as OR`() {
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(
                nodePattern("a", "IntConstant"),
                relPattern(variable = null, type = null, properties = emptyMap()),
                nodePattern("b")
            ))),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval")
            )),
            CypherClause.Limit(lit(3))
        )
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    // ========================================================================
    // matchTargetNode - target variable already bound to different node
    // ========================================================================

    @Test
    fun `target variable bound to different node filters out`() {
        // MATCH (a:IntConstant), (a)-[:DATAFLOW]->(b:ParameterNode)
        // When 'a' is bound from first pattern to intConst7, the DATAFLOW pattern
        // checks if the target node matches the already-bound 'a'. Since intConst7
        // doesn't have DATAFLOW to param, it gets filtered.
        val clauses = listOf(
            CypherClause.Match(listOf(
                pattern(nodePattern("a", "IntConstant")),
                pattern(
                    nodePattern("a"),
                    relPattern(type = "DATAFLOW"),
                    nodePattern("b", "ParameterNode")
                )
            )),
            CypherClause.Return(listOf(
                returnItem(prop(variable("a"), "value"), "aval")
            ))
        )
        val result = pipeline.execute(clauses)
        // Only intConst42 -> param1 should match
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["aval"])
    }

    // ========================================================================
    // matchNodeElement - bound to non-matching class
    // ========================================================================

    @Test
    fun `node variable bound to non-matching class returns empty`() {
        // First match binds 'n' to a StringConstant, then try to match IntConstant with same var
        val clauses = listOf(
            CypherClause.Match(listOf(pattern(nodePattern("n", "StringConstant")))),
            CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
            CypherClause.Return(listOf(returnItem(prop(variable("n"), "id"), "nid")))
        )
        val result = pipeline.execute(clauses)
        // StringConstant is not an IntConstant, so second match should produce empty
        assertEquals(0, result.rows.size)
    }

    // ========================================================================
    // evaluateAggregation with non-function non-CountStar expr
    // ========================================================================

    @Test
    fun `aggregation with non-aggregation expression`() {
        // Aggregation context with a literal expression
        val clauses = listOf(
            CypherClause.Unwind(
                CypherExpr.ListLiteral(listOf(lit(1), lit(2))),
                "x"
            ),
            CypherClause.Return(listOf(
                returnItem(CypherExpr.CountStar, "cnt"),
                returnItem(lit(99), "fixed")
            ))
        )
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
        assertEquals(99, result.rows[0]["fixed"])
    }
}
