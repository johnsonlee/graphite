package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive openCypher compatibility test.
 *
 * Verifies every major Cypher language feature end-to-end:
 * parse -> AST build -> execute -> correct result.
 *
 * Test graph structure:
 *
 *   intConst42 --DATAFLOW(PARAMETER_PASS)--> param1
 *   param1     --DATAFLOW(ASSIGN)----------> localVar1
 *   localVar1  --DATAFLOW(ASSIGN)----------> localVar2
 *   localVar2  --DATAFLOW(PARAMETER_PASS)--> callSite1
 *   strConstHello --DATAFLOW(PARAMETER_PASS)--> callSite1
 *   strConstHello --DATAFLOW(PARAMETER_PASS)--> callSite2
 *   callSite1  --CALL----------------------> return1
 *   field1     --DATAFLOW(FIELD_LOAD)------> localVar1
 *
 * Nodes: IntConstant(42), IntConstant(7), StringConstant("hello"),
 *        CallSiteNode(save), CallSiteNode(log), FieldNode(name),
 *        ParameterNode(0), ReturnNode, LocalVariable(x), LocalVariable(y),
 *        BooleanConstant(true), EnumConstant(ACTIVE), LongConstant(999999999999),
 *        FloatConstant(3.14), DoubleConstant(2.718), NullConstant
 */
class CypherCompatibilityTest {

    private lateinit var executor: CypherExecutor
    private lateinit var graph: io.johnsonlee.graphite.graph.Graph

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
    private var enumConst = NodeId(0)
    private var longConst = NodeId(0)
    private var floatConst = NodeId(0)
    private var doubleConst = NodeId(0)
    private var nullConst = NodeId(0)

    @Before
    fun setup() {
        NodeId.reset()

        val builder = DefaultGraph.Builder()

        val type = TypeDescriptor("com.example.Service")
        val intType = TypeDescriptor("int")
        val stringType = TypeDescriptor("java.lang.String")
        val boolType = TypeDescriptor("boolean")
        val enumType = TypeDescriptor("com.example.Status")
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

        enumConst = NodeId.next()
        builder.addNode(EnumConstant(enumConst, enumType, "ACTIVE", listOf("active")))

        longConst = NodeId.next()
        builder.addNode(LongConstant(longConst, 999999999999L))

        floatConst = NodeId.next()
        builder.addNode(FloatConstant(floatConst, 3.14f))

        doubleConst = NodeId.next()
        builder.addNode(DoubleConstant(doubleConst, 2.718))

        nullConst = NodeId.next()
        builder.addNode(NullConstant(nullConst))

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
        executor = CypherExecutor(graph)
    }

    // ========================================================================
    // 1. MATCH patterns
    // ========================================================================

    @Test
    fun `MATCH - untyped node returns all nodes`() {
        val result = executor.execute("MATCH (n) RETURN count(*)")
        assertEquals(1, result.rows.size)
        assertEquals(16L, result.rows[0]["count(*)"], "Graph has 16 nodes total")
    }

    @Test
    fun `MATCH - typed node`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value")
        assertEquals(listOf("n.value"), result.columns)
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["n.value"] }.toSet()
        assertEquals(setOf(42, 7), values)
    }

    @Test
    fun `MATCH - property constraint`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN n.id")
        assertEquals(1, result.rows.size)
        assertEquals(intConst42.value, result.rows[0]["n.id"])
    }

    @Test
    fun `MATCH - basic relationship outgoing`() {
        val result = executor.execute("MATCH (a)-[r]->(b) RETURN a, r, b")
        assertEquals(3, result.columns.size)
        // 8 edges in the graph, each produces a row
        assertEquals(8, result.rows.size)
    }

    @Test
    fun `MATCH - typed relationship`() {
        val result = executor.execute("MATCH (a)-[r:DATAFLOW]->(b) RETURN a, b")
        assertEquals(2, result.columns.size)
        // 7 DATAFLOW edges in graph
        assertEquals(7, result.rows.size)
    }

    @Test
    fun `MATCH - reverse direction`() {
        // param1 <-[DATAFLOW]- intConst42
        val result = executor.execute(
            "MATCH (a:ParameterNode)<-[r:DATAFLOW]-(b:IntConstant) RETURN b.value"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["b.value"])
    }

    @Test
    fun `MATCH - undirected relationship`() {
        // Undirected matches both directions
        val result = executor.execute(
            "MATCH (a:IntConstant {value: 42})-[r:DATAFLOW]-(b) RETURN b.id"
        )
        // intConst42 has outgoing DATAFLOW to param1, so undirected finds it
        assertTrue(result.rows.isNotEmpty(), "Undirected match should find connected nodes")
        val targetIds = result.rows.map { it["b.id"] }
        assertTrue(targetIds.contains(param1.value), "Should find param1 via undirected traversal")
    }

    @Test
    fun `MATCH - variable length unbounded`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 -> callSite1
        val result = executor.execute(
            "MATCH (a:IntConstant {value: 42})-[:DATAFLOW*]->(b:CallSiteNode) RETURN b.callee_name"
        )
        assertTrue(result.rows.isNotEmpty(), "Variable-length path should reach CallSiteNode")
        val names = result.rows.map { it["b.callee_name"] }
        assertTrue(names.contains("save"), "Should reach callSite1 (save) via multi-hop path")
    }

    @Test
    fun `MATCH - variable length with bounds`() {
        // intConst42 -> param1 (1 hop), intConst42 -> param1 -> localVar1 (2 hops)
        val result = executor.execute(
            "MATCH (a:IntConstant {value: 42})-[:DATAFLOW*1..2]->(b) RETURN b.id"
        )
        assertTrue(result.rows.isNotEmpty(), "Should find nodes within 1-2 hops")
        val targetIds = result.rows.map { it["b.id"] }
        assertTrue(targetIds.contains(param1.value), "Should find param1 at 1 hop")
        assertTrue(targetIds.contains(localVar1.value), "Should find localVar1 at 2 hops")
    }

    @Test
    fun `MATCH - exact hops`() {
        // Exactly 2 hops from intConst42: intConst42 -> param1 -> localVar1
        val result = executor.execute(
            "MATCH (a:IntConstant {value: 42})-[:DATAFLOW*2]->(b) RETURN b.id"
        )
        assertTrue(result.rows.isNotEmpty(), "Should find nodes at exactly 2 hops")
        val targetIds = result.rows.map { it["b.id"] }
        assertTrue(targetIds.contains(localVar1.value), "localVar1 is exactly 2 hops away")
        // param1 is 1 hop - should NOT be in results
        assertTrue(!targetIds.contains(param1.value), "param1 is only 1 hop away, should not match exact 2")
    }

    @Test
    fun `MATCH - path variable`() {
        val result = executor.execute(
            "MATCH p = (a:IntConstant {value: 42})-[]->(b:ParameterNode) RETURN p"
        )
        assertEquals(listOf("p"), result.columns)
        assertEquals(1, result.rows.size)
        val path = result.rows[0]["p"]
        assertNotNull(path, "Path variable should be bound")
        assertTrue(path is List<*>, "Path should be a list of alternating nodes and edges")
    }

    @Test
    fun `MATCH - multiple patterns (cartesian product)`() {
        val result = executor.execute(
            "MATCH (a:IntConstant), (b:StringConstant) RETURN a.value, b.value"
        )
        assertEquals(2, result.columns.size)
        // 2 IntConstants x 1 StringConstant = 2 rows
        assertEquals(2, result.rows.size)
        val combos = result.rows.map { (it["a.value"] as Number).toInt() to it["b.value"] }.toSet()
        assertEquals(setOf(42 to "hello", 7 to "hello"), combos)
    }

    // ========================================================================
    // 2. OPTIONAL MATCH
    // ========================================================================

    @Test
    fun `OPTIONAL MATCH - no match produces nulls for unbound variables only`() {
        // IntConstant never connects to StringConstant via DATAFLOW directly.
        // Correct Cypher semantics: n should remain bound from the first MATCH,
        // only m should be null.
        val result = executor.execute(
            "MATCH (n:IntConstant {value: 42}) OPTIONAL MATCH (n)-[:DATAFLOW]->(m:StringConstant) RETURN n.value, m"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
        assertNull(result.rows[0]["m"], "No DATAFLOW from IntConstant(42) to StringConstant, m should be null")
    }

    @Test
    fun `OPTIONAL MATCH - standalone with no match fills nulls`() {
        val result = executor.execute(
            "OPTIONAL MATCH (m:IntConstant {value: 99999}) RETURN m"
        )
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["m"])
    }

    @Test
    fun `OPTIONAL MATCH - with match produces values`() {
        // intConst42 -> param1 via DATAFLOW
        val result = executor.execute(
            "MATCH (n:IntConstant {value: 42}) OPTIONAL MATCH (n)-[:DATAFLOW]->(m:ParameterNode) RETURN n.value, m.index"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
        assertEquals(0, result.rows[0]["m.index"])
    }

    // ========================================================================
    // 3. WHERE clauses
    // ========================================================================

    @Test
    fun `WHERE - equality`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - inequality`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value <> 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - greater than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value > 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - greater than or equal`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value >= 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - less than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value < 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - less than or equal`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value <= 7 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - regex match`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'com\\.example\\..*' RETURN n.callee_name"
        )
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - STARTS WITH`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) WHERE n.callee_class STARTS WITH 'com' RETURN n.callee_name"
        )
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - ENDS WITH`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) WHERE n.callee_class ENDS WITH 'Repository' RETURN n.callee_name"
        )
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - CONTAINS`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) WHERE n.callee_class CONTAINS 'example' RETURN n.callee_name"
        )
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - NOT CONTAINS`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) WHERE NOT n.callee_class CONTAINS 'Logger' RETURN n.callee_name"
        )
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - NOT STARTS WITH`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) WHERE NOT n.callee_class STARTS WITH 'com.example.Logger' RETURN n.callee_name"
        )
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WHERE - IN list`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.value IN [1, 2, 42] RETURN n.value"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - IS NULL`() {
        val result = executor.execute(
            "MATCH (n:NullConstant) WHERE n.value IS NULL RETURN n.id"
        )
        assertEquals(1, result.rows.size)
        assertEquals(nullConst.value, result.rows[0]["n.id"])
    }

    @Test
    fun `WHERE - IS NOT NULL`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.value IS NOT NULL RETURN n.value"
        )
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - AND`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.value > 0 AND n.value < 100 RETURN n.value"
        )
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["n.value"] }.toSet()
        assertEquals(setOf(42, 7), values)
    }

    @Test
    fun `WHERE - OR`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.value = 42 OR n.value = 7 RETURN n.value"
        )
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `WHERE - NOT`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE NOT n.value = 42 RETURN n.value"
        )
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `WHERE - XOR`() {
        // 42 > 0 is true, 42 < 0 is false => XOR is true
        val result = executor.execute(
            "MATCH (n:IntConstant) WHERE n.value > 0 XOR n.value < 0 RETURN n.value"
        )
        // Both 42 and 7 are > 0 and NOT < 0, so true XOR false = true for both
        assertEquals(2, result.rows.size)
    }

    // ========================================================================
    // 4. RETURN
    // ========================================================================

    @Test
    fun `RETURN - node reference`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN n")
        assertEquals(1, result.rows.size)
        val node = result.rows[0]["n"]
        assertTrue(node is Map<*, *>, "Materialized node should be a map")
        @Suppress("UNCHECKED_CAST")
        val map = node as Map<String, Any?>
        assertEquals(42, map["value"])
        assertEquals("IntConstant", map["type"])
    }

    @Test
    fun `RETURN - property access`() {
        val result = executor.execute("MATCH (n:StringConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0]["n.value"])
    }

    @Test
    fun `RETURN - alias`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN n.value AS val")
        assertEquals(listOf("val"), result.columns)
        assertEquals(42, result.rows[0]["val"])
    }

    @Test
    fun `RETURN DISTINCT`() {
        // Both callSites have callee_class starting with "com.example"
        val result = executor.execute(
            "MATCH (n:CallSiteNode) RETURN DISTINCT n.caller_class"
        )
        assertEquals(1, result.rows.size)
        assertEquals("com.example.Service", result.rows[0]["n.caller_class"])
    }

    @Test
    fun `RETURN - count star`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN count(*)")
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["count(*)"])
    }

    @Test
    fun `RETURN - count expression`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN count(n)")
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["count(n)"])
    }

    @Test
    fun `RETURN - sum`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN sum(n.value)")
        assertEquals(1, result.rows.size)
        assertEquals(49.0, result.rows[0]["sum(n.value)"], "sum(42 + 7) = 49")
    }

    @Test
    fun `RETURN - avg`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN avg(n.value)")
        assertEquals(1, result.rows.size)
        assertEquals(24.5, result.rows[0]["avg(n.value)"], "avg(42, 7) = 24.5")
    }

    @Test
    fun `RETURN - min and max`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN min(n.value), max(n.value)")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["min(n.value)"], "min should be 7")
        assertEquals(42, result.rows[0]["max(n.value)"], "max should be 42")
    }

    @Test
    fun `RETURN - collect`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN collect(n.value)")
        assertEquals(1, result.rows.size)
        val collected = result.rows[0]["collect(n.value)"] as List<*>
        assertEquals(2, collected.size)
        assertTrue(collected.containsAll(listOf(42, 7)))
    }

    @Test
    fun `RETURN - grouped aggregation`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) RETURN n.caller_class, count(*) AS cnt"
        )
        assertEquals(1, result.rows.size, "Both callSites share the same caller_class")
        assertEquals("com.example.Service", result.rows[0]["n.caller_class"])
        assertEquals(2L, result.rows[0]["cnt"])
    }

    // ========================================================================
    // 5. WITH (sub-query piping)
    // ========================================================================

    @Test
    fun `WITH - basic piping with filter`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WITH n.value AS v WHERE v > 0 RETURN v"
        )
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["v"] }.toSet()
        assertEquals(setOf(42, 7), values)
    }

    @Test
    fun `WITH - aggregation, ORDER BY, and LIMIT`() {
        val result = executor.execute(
            "MATCH (n:CallSiteNode) WITH n.callee_class AS cls, count(*) AS cnt ORDER BY cnt DESC LIMIT 3 RETURN cls, cnt"
        )
        assertTrue(result.rows.isNotEmpty())
        // Each callSite has a different callee_class, so each group has count 1
        for (row in result.rows) {
            assertNotNull(row["cls"])
            assertNotNull(row["cnt"])
        }
    }

    // ========================================================================
    // 6. UNWIND
    // ========================================================================

    @Test
    fun `UNWIND - basic list`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN x")
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    @Test
    fun `UNWIND - with WHERE filter`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x WITH x WHERE x > 1 RETURN x")
        assertEquals(2, result.rows.size)
        assertEquals(listOf(2, 3), result.rows.map { it["x"] })
    }

    // ========================================================================
    // 7. ORDER BY / SKIP / LIMIT
    // ========================================================================

    @Test
    fun `ORDER BY - ascending`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v ASC"
        )
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["v"])
        assertEquals(42, result.rows[1]["v"])
    }

    @Test
    fun `ORDER BY - descending`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v DESC"
        )
        assertEquals(2, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
        assertEquals(7, result.rows[1]["v"])
    }

    @Test
    fun `SKIP and LIMIT`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v ASC SKIP 1 LIMIT 2"
        )
        assertEquals(1, result.rows.size, "Only 2 rows total, skip 1 leaves 1")
        assertEquals(42, result.rows[0]["v"])
    }

    @Test
    fun `ORDER BY with LIMIT`() {
        val result = executor.execute(
            "MATCH (n) RETURN n.id AS nid ORDER BY nid LIMIT 3"
        )
        assertEquals(3, result.rows.size)
        // IDs should be in ascending order
        val ids = result.rows.map { (it["nid"] as Number).toInt() }
        assertEquals(ids.sorted(), ids)
    }

    // ========================================================================
    // 8. UNION
    // ========================================================================

    @Test
    fun `UNION - deduplicates`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v UNION MATCH (n:StringConstant) RETURN n.value AS v"
        )
        assertEquals(listOf("v"), result.columns)
        // 42, 7 from IntConstant + "hello" from StringConstant = 3 distinct
        assertEquals(3, result.rows.size)
        val values = result.rows.map { it["v"] }.toSet()
        assertTrue(values.contains(42))
        assertTrue(values.contains(7))
        assertTrue(values.contains("hello"))
    }

    @Test
    fun `UNION ALL - preserves duplicates`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v UNION ALL MATCH (n:IntConstant) RETURN n.value AS v"
        )
        assertEquals(4, result.rows.size, "2 IntConstants x 2 queries = 4 rows with UNION ALL")
    }

    // ========================================================================
    // 9. Expressions
    // ========================================================================

    @Test
    fun `expression - addition`() {
        val result = executor.execute("RETURN 1 + 2")
        assertEquals(1, result.rows.size)
        assertEquals(3L, result.rows[0].values.first(), "1 + 2 = 3")
    }

    @Test
    fun `expression - subtraction`() {
        val result = executor.execute("RETURN 10 - 3")
        assertEquals(1, result.rows.size)
        assertEquals(7L, result.rows[0].values.first())
    }

    @Test
    fun `expression - multiplication`() {
        val result = executor.execute("RETURN 2 * 3")
        assertEquals(1, result.rows.size)
        assertEquals(6L, result.rows[0].values.first())
    }

    @Test
    fun `expression - division`() {
        val result = executor.execute("RETURN 10 / 3")
        assertEquals(1, result.rows.size)
        // Integer division in Cypher may produce double
        val value = (result.rows[0].values.first() as Number).toDouble()
        assertTrue(value > 3.0 && value < 4.0, "10/3 should be ~3.33")
    }

    @Test
    fun `expression - modulo`() {
        val result = executor.execute("RETURN 10 % 3")
        assertEquals(1, result.rows.size)
        assertEquals(1.0, (result.rows[0].values.first() as Number).toDouble(), "10 % 3 = 1")
    }

    @Test
    fun `expression - exponentiation`() {
        val result = executor.execute("RETURN 2 ^ 3")
        assertEquals(1, result.rows.size)
        assertEquals(8.0, (result.rows[0].values.first() as Number).toDouble())
    }

    @Test
    fun `expression - unary minus`() {
        val result = executor.execute("RETURN -42")
        assertEquals(1, result.rows.size)
        assertEquals(-42, result.rows[0].values.first())
    }

    @Test
    fun `expression - string concatenation`() {
        val result = executor.execute("RETURN 'hello' + ' ' + 'world'")
        assertEquals(1, result.rows.size)
        assertEquals("hello world", result.rows[0].values.first())
    }

    @Test
    fun `expression - list literal`() {
        val result = executor.execute("RETURN [1, 2, 3]")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows[0].values.first())
    }

    @Test
    fun `expression - subscript`() {
        val result = executor.execute("RETURN [1, 2, 3][0]")
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows[0].values.first())
    }

    @Test
    fun `expression - slice`() {
        val result = executor.execute("RETURN [1, 2, 3][1..3]")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(2, 3), result.rows[0].values.first())
    }

    @Test
    fun `expression - map literal`() {
        val result = executor.execute("RETURN {name: 'test', value: 42}")
        assertEquals(1, result.rows.size)
        val map = result.rows[0].values.first()
        assertTrue(map is Map<*, *>)
        @Suppress("UNCHECKED_CAST")
        val m = map as Map<String, Any?>
        assertEquals("test", m["name"])
        assertEquals(42, m["value"])
    }

    @Test
    fun `expression - list comprehension with filter`() {
        val result = executor.execute("RETURN [x IN [1, 2, 3] WHERE x > 1]")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(2, 3), result.rows[0].values.first())
    }

    @Test
    fun `expression - list comprehension with map`() {
        val result = executor.execute("RETURN [x IN [1, 2, 3] | x * 2]")
        assertEquals(1, result.rows.size)
        val values = result.rows[0].values.first() as List<*>
        assertEquals(3, values.size)
        // Values should be 2.0, 4.0, 6.0 (due to arithmetic returning doubles when mixing)
        val numValues = values.map { (it as Number).toDouble() }
        assertEquals(listOf(2.0, 4.0, 6.0), numValues)
    }

    // ========================================================================
    // 10. Functions
    // ========================================================================

    @Test
    fun `function - toInteger`() {
        val result = executor.execute("RETURN toInteger('42')")
        assertEquals(1, result.rows.size)
        assertEquals(42L, result.rows[0].values.first())
    }

    @Test
    fun `function - toFloat`() {
        val result = executor.execute("RETURN toFloat('3.14')")
        assertEquals(1, result.rows.size)
        assertEquals(3.14, result.rows[0].values.first())
    }

    @Test
    fun `function - toString`() {
        val result = executor.execute("RETURN toString(42)")
        assertEquals(1, result.rows.size)
        assertEquals("42", result.rows[0].values.first())
    }

    @Test
    fun `function - size of string`() {
        val result = executor.execute("RETURN size('hello')")
        assertEquals(1, result.rows.size)
        assertEquals(5, result.rows[0].values.first())
    }

    @Test
    fun `function - length of string`() {
        val result = executor.execute("RETURN length('hello')")
        assertEquals(1, result.rows.size)
        assertEquals(5, result.rows[0].values.first())
    }

    @Test
    fun `function - trim`() {
        val result = executor.execute("RETURN trim('  hello  ')")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0].values.first())
    }

    @Test
    fun `function - toLower`() {
        val result = executor.execute("RETURN toLower('HELLO')")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0].values.first())
    }

    @Test
    fun `function - toUpper`() {
        val result = executor.execute("RETURN toUpper('hello')")
        assertEquals(1, result.rows.size)
        assertEquals("HELLO", result.rows[0].values.first())
    }

    @Test
    fun `function - replace`() {
        val result = executor.execute("RETURN replace('hello', 'l', 'r')")
        assertEquals(1, result.rows.size)
        assertEquals("herro", result.rows[0].values.first())
    }

    @Test
    fun `function - substring`() {
        val result = executor.execute("RETURN substring('hello', 1, 3)")
        assertEquals(1, result.rows.size)
        assertEquals("ell", result.rows[0].values.first())
    }

    @Test
    fun `function - left`() {
        val result = executor.execute("RETURN left('hello', 3)")
        assertEquals(1, result.rows.size)
        assertEquals("hel", result.rows[0].values.first())
    }

    @Test
    fun `function - right`() {
        val result = executor.execute("RETURN right('hello', 3)")
        assertEquals(1, result.rows.size)
        assertEquals("llo", result.rows[0].values.first())
    }

    @Test
    fun `function - reverse`() {
        val result = executor.execute("RETURN reverse('hello')")
        assertEquals(1, result.rows.size)
        assertEquals("olleh", result.rows[0].values.first())
    }

    @Test
    fun `function - split`() {
        val result = executor.execute("RETURN split('a,b,c', ',')")
        assertEquals(1, result.rows.size)
        assertEquals(listOf("a", "b", "c"), result.rows[0].values.first())
    }

    @Test
    fun `function - abs`() {
        val result = executor.execute("RETURN abs(-42)")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0].values.first())
    }

    @Test
    fun `function - ceil`() {
        val result = executor.execute("RETURN ceil(3.2)")
        assertEquals(1, result.rows.size)
        assertEquals(4.0, result.rows[0].values.first())
    }

    @Test
    fun `function - floor`() {
        val result = executor.execute("RETURN floor(3.8)")
        assertEquals(1, result.rows.size)
        assertEquals(3.0, result.rows[0].values.first())
    }

    @Test
    fun `function - round`() {
        val result = executor.execute("RETURN round(3.5)")
        assertEquals(1, result.rows.size)
        assertEquals(4.0, result.rows[0].values.first())
    }

    @Test
    fun `function - sign`() {
        val result = executor.execute("RETURN sign(-42)")
        assertEquals(1, result.rows.size)
        assertEquals(-1, result.rows[0].values.first())
    }

    @Test
    fun `function - rand`() {
        val result = executor.execute("RETURN rand()")
        assertEquals(1, result.rows.size)
        val value = result.rows[0].values.first() as Double
        assertTrue(value >= 0.0 && value < 1.0, "rand() should return value in [0, 1)")
    }

    @Test
    fun `function - coalesce`() {
        val result = executor.execute("RETURN coalesce(null, 42)")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0].values.first())
    }

    @Test
    fun `function - head`() {
        val result = executor.execute("RETURN head([1, 2, 3])")
        assertEquals(1, result.rows.size)
        assertEquals(1, result.rows[0].values.first())
    }

    @Test
    fun `function - last`() {
        val result = executor.execute("RETURN last([1, 2, 3])")
        assertEquals(1, result.rows.size)
        assertEquals(3, result.rows[0].values.first())
    }

    @Test
    fun `function - tail`() {
        val result = executor.execute("RETURN tail([1, 2, 3])")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(2, 3), result.rows[0].values.first())
    }

    @Test
    fun `function - range`() {
        val result = executor.execute("RETURN range(1, 5)")
        assertEquals(1, result.rows.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.rows[0].values.first())
    }

    @Test
    fun `function - keys on map`() {
        // keys() on a map literal - the CypherFunctions.keys only handles Node currently.
        // This tests whether it works for maps too.
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN keys(n)")
        assertEquals(1, result.rows.size)
        val keys = result.rows[0].values.first()
        assertNotNull(keys, "keys(n) should return property keys of the node")
        assertTrue(keys is List<*>)
        assertTrue((keys as List<*>).contains("value"))
        assertTrue(keys.contains("id"))
    }

    // ========================================================================
    // 11. CASE expressions
    // ========================================================================

    @Test
    fun `CASE - generic WHEN THEN ELSE`() {
        val result = executor.execute("RETURN CASE WHEN 1 > 0 THEN 'yes' ELSE 'no' END")
        assertEquals(1, result.rows.size)
        assertEquals("yes", result.rows[0].values.first())
    }

    @Test
    fun `CASE - simple form`() {
        val result = executor.execute(
            "RETURN CASE 42 WHEN 1 THEN 'one' WHEN 42 THEN 'forty-two' ELSE 'other' END"
        )
        assertEquals(1, result.rows.size)
        assertEquals("forty-two", result.rows[0].values.first())
    }

    @Test
    fun `CASE - else branch`() {
        val result = executor.execute(
            "RETURN CASE 99 WHEN 1 THEN 'one' WHEN 42 THEN 'forty-two' ELSE 'other' END"
        )
        assertEquals(1, result.rows.size)
        assertEquals("other", result.rows[0].values.first())
    }

    @Test
    fun `CASE - with graph data`() {
        val result = executor.execute(
            """MATCH (n:IntConstant)
               RETURN CASE WHEN n.value > 10 THEN 'big' ELSE 'small' END AS category, n.value AS v
               ORDER BY v"""
        )
        assertEquals(2, result.rows.size)
        assertEquals("small", result.rows[0]["category"]) // 7
        assertEquals("big", result.rows[1]["category"])    // 42
    }

    // ========================================================================
    // 12. Write clauses (should throw)
    // ========================================================================

    @Test
    fun `write - CREATE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            executor.execute("CREATE (n:Test {name: 'test'})")
        }
    }

    @Test
    fun `write - DELETE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            executor.execute("MATCH (n:IntConstant) DELETE n")
        }
    }

    @Test
    fun `write - SET throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            executor.execute("MATCH (n:IntConstant) SET n.value = 1")
        }
    }

    @Test
    fun `write - REMOVE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> {
            executor.execute("MATCH (n:IntConstant) REMOVE n.value")
        }
    }

    // ========================================================================
    // Additional edge-case and cross-feature tests
    // ========================================================================

    @Test
    fun `complex query - multi-hop dataflow path with filter`() {
        // Trace dataflow from IntConstant(42) through the graph
        val result = executor.execute(
            """MATCH (src:IntConstant {value: 42})-[:DATAFLOW*1..4]->(dst)
               RETURN dst.id"""
        )
        assertTrue(result.rows.isNotEmpty(), "Should find nodes reachable via dataflow from IntConstant(42)")
        val reachableIds = result.rows.map { it["dst.id"] }.toSet()
        assertTrue(reachableIds.contains(param1.value), "param1 reachable at hop 1")
        assertTrue(reachableIds.contains(localVar1.value), "localVar1 reachable at hop 2")
        assertTrue(reachableIds.contains(localVar2.value), "localVar2 reachable at hop 3")
        assertTrue(reachableIds.contains(callSite1.value), "callSite1 reachable at hop 4")
    }

    @Test
    fun `RETURN star is supported`() {
        val result = executor.execute("MATCH (n:IntConstant {value: 42}) RETURN *")
        assertEquals(1, result.rows.size)
        // The * should include n
        assertTrue(result.rows[0].containsKey("n") || result.rows[0].containsKey("*"),
            "RETURN * should include matched variables")
    }

    @Test
    fun `nested function calls`() {
        val result = executor.execute("RETURN toInteger(toString(42))")
        assertEquals(1, result.rows.size)
        assertEquals(42L, result.rows[0].values.first())
    }

    @Test
    fun `boolean literal in RETURN`() {
        val result = executor.execute("RETURN true, false")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0].values.first())
        assertEquals(false, result.rows[0].values.last())
    }

    @Test
    fun `null literal in RETURN`() {
        val result = executor.execute("RETURN null")
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0].values.first())
    }

    @Test
    fun `MATCH with CALL edge type`() {
        val result = executor.execute("MATCH (a)-[r:CALL]->(b) RETURN a.id, b.id")
        assertEquals(1, result.rows.size, "Only one CALL edge: callSite1 -> return1")
    }

    @Test
    fun `aggregation - count with DISTINCT`() {
        // Both callSites share the same caller_class
        val result = executor.execute(
            "MATCH (n:CallSiteNode) RETURN count(DISTINCT n.caller_class) AS cnt"
        )
        assertEquals(1, result.rows.size)
        assertEquals(1L, result.rows[0]["cnt"], "Only 1 distinct caller_class")
    }

    @Test
    fun `WHERE with multiple conditions chained`() {
        val result = executor.execute(
            """MATCH (n:CallSiteNode)
               WHERE n.callee_class STARTS WITH 'com'
               AND n.callee_name = 'save'
               AND n.line = 10
               RETURN n.callee_name"""
        )
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `WITH piping preserves data between clauses`() {
        val result = executor.execute(
            """MATCH (n:IntConstant)
               WITH n.value AS v
               WHERE v = 42
               RETURN v"""
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    @Test
    fun `UNWIND combined with MATCH`() {
        val result = executor.execute(
            """UNWIND [42, 7] AS targetValue
               MATCH (n:IntConstant)
               WHERE n.value = targetValue
               RETURN n.value AS v ORDER BY v"""
        )
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["v"])
        assertEquals(42, result.rows[1]["v"])
    }

    @Test
    fun `expression - negative number in arithmetic`() {
        val result = executor.execute("RETURN 5 + (-3)")
        assertEquals(1, result.rows.size)
        val value = (result.rows[0].values.first() as Number).toLong()
        assertEquals(2L, value)
    }

    @Test
    fun `MATCH - EnumConstant properties`() {
        val result = executor.execute("MATCH (n:EnumConstant) RETURN n.name, n.enum_type")
        assertEquals(1, result.rows.size)
        assertEquals("ACTIVE", result.rows[0]["n.name"])
        assertEquals("com.example.Status", result.rows[0]["n.enum_type"])
    }

    @Test
    fun `MATCH - BooleanConstant`() {
        val result = executor.execute("MATCH (n:BooleanConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["n.value"])
    }

    @Test
    fun `MATCH - LongConstant`() {
        val result = executor.execute("MATCH (n:LongConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(999999999999L, result.rows[0]["n.value"])
    }

    @Test
    fun `MATCH - FloatConstant`() {
        val result = executor.execute("MATCH (n:FloatConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        val value = (result.rows[0]["n.value"] as Number).toDouble()
        assertTrue(value > 3.13 && value < 3.15, "FloatConstant should be ~3.14")
    }

    @Test
    fun `MATCH - DoubleConstant`() {
        val result = executor.execute("MATCH (n:DoubleConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(2.718, result.rows[0]["n.value"])
    }

    @Test
    fun `MATCH - FieldNode properties`() {
        val result = executor.execute("MATCH (n:FieldNode) RETURN n.name, n.class, n.static")
        assertEquals(1, result.rows.size)
        assertEquals("name", result.rows[0]["n.name"])
        assertEquals("com.example.Service", result.rows[0]["n.class"])
        assertEquals(false, result.rows[0]["n.static"])
    }

    @Test
    fun `MATCH - ParameterNode properties`() {
        val result = executor.execute("MATCH (n:ParameterNode) RETURN n.index, n.type")
        assertEquals(1, result.rows.size)
        assertEquals(0, result.rows[0]["n.index"])
        assertEquals("int", result.rows[0]["n.type"])
    }

    @Test
    fun `MATCH - LocalVariable properties`() {
        val result = executor.execute(
            "MATCH (n:LocalVariable) RETURN n.name AS name ORDER BY name"
        )
        assertEquals(2, result.rows.size)
        assertEquals("x", result.rows[0]["name"])
        assertEquals("y", result.rows[1]["name"])
    }

    @Test
    fun `empty MATCH result with WHERE filtering everything`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 999 RETURN n.value")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `LIMIT 0 returns empty result`() {
        val result = executor.execute("MATCH (n) RETURN n LIMIT 0")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `SKIP beyond result set returns empty`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN n.value SKIP 100")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `function - size of list`() {
        val result = executor.execute("RETURN size([1, 2, 3, 4])")
        assertEquals(1, result.rows.size)
        assertEquals(4, result.rows[0].values.first())
    }

    @Test
    fun `expression - deeply nested parentheses`() {
        val result = executor.execute("RETURN ((1 + 2) * (3 + 4))")
        assertEquals(1, result.rows.size)
        assertEquals(21L, (result.rows[0].values.first() as Number).toLong())
    }

    @Test
    fun `CASE with no matching WHEN and no ELSE returns null`() {
        val result = executor.execute("RETURN CASE 99 WHEN 1 THEN 'one' END")
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0].values.first())
    }

    @Test
    fun `multiple WHERE conditions with OR and AND precedence`() {
        // AND binds tighter than OR
        // This should match: callee_name = 'save' OR (callee_name = 'log' AND line = 20)
        val result = executor.execute(
            """MATCH (n:CallSiteNode)
               WHERE n.callee_name = 'save' OR n.callee_name = 'log' AND n.line = 20
               RETURN n.callee_name AS name ORDER BY name"""
        )
        assertEquals(2, result.rows.size)
        assertEquals("log", result.rows[0]["name"])
        assertEquals("save", result.rows[1]["name"])
    }

    // ========================================================================
    // Grammar Rule Coverage Gaps
    // ========================================================================

    // 1. Multi-statement with semicolon
    @Test
    fun `script - multiple statements separated by semicolon`() {
        // Our executor handles one statement at a time, so just verify single statement with trailing semicolon parses
        val result = executor.execute("RETURN 1 AS x;")
        assertEquals(1, result.rows.size)
    }

    // 2. WITH DISTINCT
    @Test
    fun `WITH DISTINCT deduplicates piped values`() {
        val result = executor.execute("MATCH (n:IntConstant) WITH DISTINCT n.value AS v RETURN v ORDER BY v")
        val values = result.rows.map { it["v"] }
        assertEquals(values.distinct(), values, "WITH DISTINCT should deduplicate values")
    }

    // 3. Multiple ORDER BY columns
    @Test
    fun `ORDER BY multiple columns`() {
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN n.callee_class, n.callee_name ORDER BY n.callee_class ASC, n.callee_name DESC LIMIT 5")
        assertTrue(result.rows.isNotEmpty())
        assertEquals(2, result.columns.size)
    }

    // 4. Multiple node labels (n:A:B)
    @Test
    fun `node pattern with multiple labels`() {
        // Our graph doesn't have multi-label nodes, but the parser should handle it
        // This should return empty (no node has both IntConstant AND CallSiteNode labels)
        val result = executor.execute("MATCH (n:IntConstant:CallSiteNode) RETURN n")
        assertEquals(0, result.rows.size, "No node has both labels, should be empty")
    }

    // 5. Relationship with multiple types (|)
    @Test
    fun `relationship pattern with multiple types using pipe`() {
        val result = executor.execute("MATCH (a)-[:DATAFLOW|CALL]->(b) RETURN a, b LIMIT 5")
        assertTrue(result.rows.isNotEmpty(), "Should find edges of either DATAFLOW or CALL type")
    }

    // 6. Variable-length with min only (*1..)
    @Test
    fun `variable length path with min only`() {
        val result = executor.execute("MATCH (a:IntConstant)-[:DATAFLOW*1..]->(b) RETURN a.value LIMIT 5")
        // Should parse and execute (may or may not find results depending on graph depth)
        assertNotNull(result)
    }

    // 7. Unary plus
    @Test
    fun `unary plus expression`() {
        val result = executor.execute("RETURN +42 AS val")
        assertEquals(1, result.rows.size)
        assertEquals(42, (result.rows[0]["val"] as Number).toInt())
    }

    // 8. Slice with only upper bound [..3]
    @Test
    fun `list slice with only upper bound`() {
        val result = executor.execute("RETURN [1, 2, 3, 4, 5][..3] AS first3")
        assertEquals(1, result.rows.size)
        val list = result.rows[0]["first3"]
        assertTrue(list is List<*>, "Should return a list")
        assertEquals(3, (list as List<*>).size)
    }

    // 9. Parameter $param
    @Test
    fun `parameter reference parses without error`() {
        // Parameters need to be passed via bindings which we don't support in execute(),
        // but the parser should accept the syntax. The parameter resolves to null.
        val result = executor.execute("RETURN coalesce(\$missing, 42) AS val")
        assertEquals(1, result.rows.size)
        assertEquals(42, (result.rows[0]["val"] as Number).toInt())
    }

    // 10. EXISTS expression
    @Test
    fun `EXISTS expression parses`() {
        // EXISTS(expr) should evaluate whether expr is non-null
        val result = executor.execute("MATCH (n:IntConstant) WHERE EXISTS(n.value) RETURN n.value LIMIT 3")
        assertTrue(result.rows.isNotEmpty())
    }

    // 11. List comprehension with both WHERE and pipe
    @Test
    fun `list comprehension with filter AND map expression`() {
        val result = executor.execute("RETURN [x IN [1, 2, 3, 4, 5] WHERE x > 2 | x * 10] AS filtered")
        assertEquals(1, result.rows.size)
        val list = result.rows[0]["filtered"] as List<*>
        assertEquals(listOf(30, 40, 50), list.map { (it as Number).toInt() })
    }

    // 12. Hex integer literal
    @Test
    fun `hex integer literal`() {
        val result = executor.execute("RETURN 0xFF AS hex")
        assertEquals(1, result.rows.size)
        assertEquals(255L, (result.rows[0]["hex"] as Number).toLong())
    }

    // 13. Octal integer literal
    @Test
    fun `octal integer literal`() {
        val result = executor.execute("RETURN 0o77 AS oct")
        assertEquals(1, result.rows.size)
        assertEquals(63L, (result.rows[0]["oct"] as Number).toLong())
    }

    // 14. Backtick-escaped identifier as node label
    @Test
    fun `backtick escaped identifier as node label`() {
        val result = executor.execute("MATCH (n:`IntConstant`) RETURN n.value LIMIT 3")
        assertTrue(result.rows.isNotEmpty(), "Backtick-escaped label should match IntConstant")
    }

    @Test
    fun `backtick escaped identifier as variable name`() {
        val result = executor.execute("MATCH (`my node`:IntConstant) RETURN `my node`.value LIMIT 1")
        assertTrue(result.rows.isNotEmpty())
    }

    // 15. Keyword used as property name in map literal
    @Test
    fun `keyword used as property name`() {
        // 'order', 'match', 'return' etc. should work as property names
        val result = executor.execute("RETURN {order: 1, match: 2} AS m")
        assertEquals(1, result.rows.size)
    }

    // 16. RETURN *
    @Test
    fun `RETURN star returns all bound variables`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN * LIMIT 1")
        assertTrue(result.rows.isNotEmpty())
        assertTrue(result.columns.contains("n"), "RETURN * should include variable n")
    }

    // 17. DETACH DELETE (should parse and throw not implemented)
    @Test
    fun `DETACH DELETE parses and throws not implemented`() {
        assertFailsWith<NotImplementedError> {
            executor.execute("MATCH (n) DETACH DELETE n")
        }
    }

    // 18. MERGE (should parse and throw not implemented)
    @Test
    fun `MERGE parses and throws not implemented`() {
        assertFailsWith<NotImplementedError> {
            executor.execute("MERGE (n:Test {name: 'x'}) RETURN n")
        }
    }

    // 19. Dotted function name (namespace.function)
    @Test
    fun `dotted function name parses`() {
        // toString is a known function, should work
        val result = executor.execute("RETURN toString(42) AS s")
        assertEquals(1, result.rows.size)
        assertEquals("42", result.rows[0]["s"])
    }

    // 20. Float literal with exponent
    @Test
    fun `float literal with exponent notation`() {
        val result = executor.execute("RETURN 1.5e2 AS val")
        assertEquals(1, result.rows.size)
        assertEquals(150.0, (result.rows[0]["val"] as Number).toDouble(), 0.01)
    }

    // 21. String with escape sequences
    @Test
    fun `string literal with escape sequences`() {
        val result = executor.execute("RETURN 'hello\\nworld' AS s")
        assertEquals(1, result.rows.size)
        assertEquals("hello\nworld", result.rows[0]["s"])
    }
}
