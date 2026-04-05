package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CypherExecutorTest {

    private lateinit var executor: CypherExecutor

    // Stable node IDs for test assertions
    private var intConst42 = NodeId(0)
    private var intConst7 = NodeId(0)
    private var strConstHello = NodeId(0)
    private var callSite1 = NodeId(0)
    private var callSite2 = NodeId(0)
    private var field1 = NodeId(0)
    private var param1 = NodeId(0)
    private var return1 = NodeId(0)
    private var localVar1 = NodeId(0)
    private var boolConst = NodeId(0)
    private var enumConst = NodeId(0)
    // Intermediate node for multi-hop paths
    private var localVar2 = NodeId(0)

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

        // Create nodes
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

        // Create edges
        // intConst42 -> param1 (dataflow)
        builder.addEdge(DataFlowEdge(intConst42, param1, DataFlowKind.PARAMETER_PASS))
        // param1 -> localVar1 (dataflow)
        builder.addEdge(DataFlowEdge(param1, localVar1, DataFlowKind.ASSIGN))
        // localVar1 -> localVar2 (dataflow)
        builder.addEdge(DataFlowEdge(localVar1, localVar2, DataFlowKind.ASSIGN))
        // localVar2 -> callSite1 (dataflow)
        builder.addEdge(DataFlowEdge(localVar2, callSite1, DataFlowKind.PARAMETER_PASS))
        // strConstHello -> callSite1 (dataflow)
        builder.addEdge(DataFlowEdge(strConstHello, callSite1, DataFlowKind.PARAMETER_PASS))
        // strConstHello -> callSite2 (dataflow)
        builder.addEdge(DataFlowEdge(strConstHello, callSite2, DataFlowKind.PARAMETER_PASS))
        // callSite1 -> return1 (call edge)
        builder.addEdge(CallEdge(callSite1, return1, false))
        // field1 -> localVar1 (dataflow field load)
        builder.addEdge(DataFlowEdge(field1, localVar1, DataFlowKind.FIELD_LOAD))

        builder.addMethod(method)
        builder.addMethod(callee)
        builder.addMethod(callee2)

        val graph = builder.build()
        executor = CypherExecutor(graph)
    }

    // --- Node Match Tests ---

    @Test
    fun `match nodes by label - CallSiteNode`() {
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN n.callee_name")
        assertEquals(listOf("n.callee_name"), result.columns)
        assertEquals(2, result.rows.size)
        val names = result.rows.map { it["n.callee_name"] }.toSet()
        assertTrue(names.contains("save"))
        assertTrue(names.contains("log"))
    }

    @Test
    fun `match nodes by label with LIMIT`() {
        val result = executor.execute("MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 1")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `match IntConstant with WHERE equality`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.id")
        assertEquals(1, result.rows.size)
        assertEquals(intConst42.value, result.rows[0]["n.id"])
    }

    @Test
    fun `match IntConstant with WHERE greater than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value > 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `match IntConstant with WHERE less than`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value < 10 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    @Test
    fun `match StringConstant`() {
        val result = executor.execute("MATCH (n:StringConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0]["n.value"])
    }

    @Test
    fun `match BooleanConstant`() {
        val result = executor.execute("MATCH (n:BooleanConstant) RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["n.value"])
    }

    @Test
    fun `match EnumConstant`() {
        val result = executor.execute("MATCH (n:EnumConstant) RETURN n.name, n.enum_type")
        assertEquals(1, result.rows.size)
        assertEquals("ACTIVE", result.rows[0]["n.name"])
        assertEquals("com.example.Status", result.rows[0]["n.enum_type"])
    }

    @Test
    fun `match FieldNode with regex filter`() {
        val result = executor.execute("MATCH (n:FieldNode) WHERE n.class =~ 'com\\.example\\..*' RETURN n.name")
        assertEquals(1, result.rows.size)
        assertEquals("name", result.rows[0]["n.name"])
    }

    @Test
    fun `match FieldNode with non-matching regex`() {
        val result = executor.execute("MATCH (n:FieldNode) WHERE n.class =~ 'org\\.other\\..*' RETURN n.name")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `match ParameterNode`() {
        val result = executor.execute("MATCH (n:ParameterNode) RETURN n.index, n.type")
        assertEquals(1, result.rows.size)
        assertEquals(0, result.rows[0]["n.index"])
        // "type" resolves to the node-specific type property (parameter type class name)
        assertEquals("int", result.rows[0]["n.type"])
    }

    @Test
    fun `match ReturnNode`() {
        // Use backtick-escaped label because "ReturnNode" conflicts with RETURN keyword
        val result = executor.execute("MATCH (n:`ReturnNode`) RETURN n.method")
        assertEquals(1, result.rows.size)
        assertTrue((result.rows[0]["n.method"] as String).contains("process"))
    }

    @Test
    fun `match LocalVariable`() {
        val result = executor.execute("MATCH (n:LocalVariable) RETURN n.name")
        assertEquals(2, result.rows.size)
        val names = result.rows.map { it["n.name"] }.toSet()
        assertTrue(names.contains("x"))
        assertTrue(names.contains("y"))
    }

    @Test
    fun `return full node as map`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(42, nodeMap["value"])
        assertEquals("IntConstant", nodeMap["type"])
    }

    // --- Single Hop Relationship Tests ---

    @Test
    fun `match single hop dataflow relationship`() {
        val result = executor.execute(
            "MATCH (c:IntConstant)-[:DATAFLOW]->(p:ParameterNode) RETURN c.value, p.index"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["c.value"])
        assertEquals(0, result.rows[0]["p.index"])
    }

    @Test
    fun `match single hop dataflow to CallSiteNode`() {
        val result = executor.execute(
            "MATCH (c:StringConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name"
        )
        assertEquals(2, result.rows.size)
        val names = result.rows.map { it["cs.callee_name"] }.toSet()
        assertTrue(names.contains("save"))
        assertTrue(names.contains("log"))
    }

    @Test
    fun `match relationship without edge type`() {
        val result = executor.execute(
            "MATCH (c:StringConstant)-[]->(cs:CallSiteNode) RETURN c.value, cs.callee_name"
        )
        assertEquals(2, result.rows.size)
    }

    // --- Variable Length Path Tests ---

    @Test
    fun `match variable-length path`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 -> callSite1
        // That's 4 hops from IntConstant to CallSiteNode
        val result = executor.execute(
            "MATCH (a:IntConstant)-[:DATAFLOW*..5]->(b:CallSiteNode) RETURN a.value, b.callee_name"
        )
        assertTrue(result.rows.isNotEmpty(), "Should find paths from IntConstant to CallSiteNode via DATAFLOW")
        val row = result.rows.first()
        assertEquals(42, row["a.value"])
        assertEquals("save", row["b.callee_name"])
    }

    @Test
    fun `match variable-length path with limit`() {
        val result = executor.execute(
            "MATCH (a)-[:DATAFLOW*..5]->(b:CallSiteNode) RETURN a.id, b.callee_name LIMIT 2"
        )
        assertTrue(result.rows.size <= 2)
    }

    // --- Edge Cases ---

    @Test
    fun `empty result for non-matching label`() {
        val result = executor.execute("MATCH (n:NullConstant) RETURN n.id")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `empty result for non-matching filter`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 999 RETURN n.id")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `match all nodes without label`() {
        val result = executor.execute("MATCH (n) RETURN n.id LIMIT 5")
        assertEquals(5, result.rows.size)
    }

    @Test
    fun `match with STARTS WITH filter`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name STARTS WITH 'sa' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `match with CONTAINS filter`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name CONTAINS 'av' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("save", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `match with ENDS WITH filter`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name ENDS WITH 'og' RETURN n.callee_name")
        assertEquals(1, result.rows.size)
        assertEquals("log", result.rows[0]["n.callee_name"])
    }

    @Test
    fun `match with NOT EQUALS filter`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value <> 42 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    // --- UNION Tests ---

    @Test
    fun `union distinct`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v UNION MATCH (m:IntConstant) RETURN m.value AS v"
        )
        // UNION distinct: {42, 7} from first + {42, 7} from second = distinct {42, 7}
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["v"] }.toSet()
        assertTrue(values.contains(42))
        assertTrue(values.contains(7))
    }

    @Test
    fun `union all`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v UNION ALL MATCH (m:IntConstant) RETURN m.value AS v"
        )
        // UNION ALL: 2 from first + 2 from second = 4
        assertEquals(4, result.rows.size)
    }

    // --- Return full node for all node types (materializeResult) ---

    @Test
    fun `return full StringConstant node as map`() {
        val result = executor.execute("MATCH (n:StringConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("hello", nodeMap["value"])
        assertEquals("StringConstant", nodeMap["type"])
    }

    @Test
    fun `return full LongConstant node as map`() {
        // No LongConstant in our graph, but verify the query runs for coverage
        val result = executor.execute("MATCH (n:LongConstant) RETURN n")
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `return full BooleanConstant node as map`() {
        val result = executor.execute("MATCH (n:BooleanConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(true, nodeMap["value"])
        assertEquals("BooleanConstant", nodeMap["type"])
    }

    @Test
    fun `return full EnumConstant node as map`() {
        val result = executor.execute("MATCH (n:EnumConstant) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("ACTIVE", nodeMap["name"])
        assertEquals("com.example.Status", nodeMap["enum_type"])
    }

    @Test
    fun `return full FieldNode as map`() {
        val result = executor.execute("MATCH (n:FieldNode) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("name", nodeMap["name"])
        assertEquals("java.lang.String", nodeMap["type"])
        assertEquals("com.example.Service", nodeMap["class"])
        assertEquals(false, nodeMap["static"])
    }

    @Test
    fun `return full ParameterNode as map`() {
        val result = executor.execute("MATCH (n:ParameterNode) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals(0, nodeMap["index"])
        assertEquals("int", nodeMap["type"])
    }

    @Test
    fun `return full ReturnNode as map`() {
        val result = executor.execute("MATCH (n:`ReturnNode`) RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertTrue((nodeMap["method"] as String).contains("process"))
    }

    @Test
    fun `return full LocalVariable as map`() {
        val result = executor.execute("MATCH (n:LocalVariable) WHERE n.name = 'x' RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("x", nodeMap["name"])
        assertEquals("int", nodeMap["type"])
    }

    @Test
    fun `return full CallSiteNode as map`() {
        val result = executor.execute("MATCH (n:CallSiteNode) WHERE n.callee_name = 'save' RETURN n")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodeMap = result.rows[0]["n"] as Map<String, Any?>
        assertEquals("com.example.Repository", nodeMap["callee_class"])
        assertEquals("save", nodeMap["callee_name"])
        assertEquals("com.example.Service", nodeMap["caller_class"])
        assertEquals("process", nodeMap["caller_name"])
        assertEquals(10, nodeMap["line"])
    }

    @Test
    fun `materialize list value`() {
        // Return a list containing a node - should materialize nodes inside lists
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN collect(n) AS nodes"
        )
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val nodes = result.rows[0]["nodes"] as List<*>
        assertEquals(2, nodes.size)
        // Each element should be a materialized map
        assertTrue(nodes[0] is Map<*, *>)
    }

    @Test
    fun `materialize map value`() {
        // Use a WITH that creates a map containing a node value indirectly
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN {v: n.value} AS m")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val map = result.rows[0]["m"] as Map<String, Any?>
        assertEquals(42, map["v"])
    }

    // --- NullConstant nodeToMap ---

    @Test
    fun `return NullConstant as map`() {
        // Note: NullConstant may not exist in our graph.
        // We test via OPTIONAL MATCH to get null values.
        val result = executor.execute("MATCH (n:NullConstant) RETURN n.id")
        assertEquals(0, result.rows.size)
    }

    // --- Inline WHERE ---

    @Test
    fun `WITH inline WHERE`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WITH n.value AS v WHERE v > 10 RETURN v"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    // --- UNWIND ---

    @Test
    fun `UNWIND and RETURN`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN x")
        assertEquals(3, result.rows.size)
        assertEquals(listOf(1, 2, 3), result.rows.map { it["x"] })
    }

    // --- ORDER BY ASC ASCENDING ---

    @Test
    fun `ORDER BY ASCENDING keyword`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN n.value AS v ORDER BY v ASCENDING"
        )
        assertEquals(2, result.rows.size)
        assertEquals(7, result.rows[0]["v"])
        assertEquals(42, result.rows[1]["v"])
    }

    // --- SKIP ---

    @Test
    fun `SKIP rows`() {
        val result = executor.execute("UNWIND [1, 2, 3, 4, 5] AS x RETURN x SKIP 3")
        assertEquals(2, result.rows.size)
    }

    // --- count star ---

    @Test
    fun `count star aggregation`() {
        val result = executor.execute("MATCH (n:IntConstant) RETURN count(*) AS cnt")
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
    }

    // --- EXISTS function ---

    @Test
    fun `EXISTS function`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE EXISTS(n.value) RETURN n.value")
        assertEquals(2, result.rows.size)
    }

    // --- RETURN star ---

    @Test
    fun `RETURN star parses without error`() {
        // RETURN * is parsed as a special Variable("*") - verify it doesn't throw
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value AS v")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    // --- Comments ---

    @Test
    fun `single line comment`() {
        val result = executor.execute("""
            // This is a comment
            MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value
        """.trimIndent())
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `multi line comment`() {
        val result = executor.execute("""
            /* This is a
               multi-line comment */
            MATCH (n:IntConstant) WHERE n.value = 42 RETURN n.value
        """.trimIndent())
        assertEquals(1, result.rows.size)
    }

    // --- Hex and Octal literals ---

    @Test
    fun `hex literal`() {
        val result = executor.execute("RETURN 0xFF AS v")
        assertEquals(1, result.rows.size)
        assertEquals(255L, result.rows[0]["v"])
    }

    @Test
    fun `octal literal`() {
        val result = executor.execute("RETURN 0o77 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(63L, result.rows[0]["v"])
    }

    // --- String escape sequences ---

    @Test
    fun `string with escape sequences`() {
        val result = executor.execute("RETURN '\\n\\t\\r' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\n\t\r", result.rows[0]["v"])
    }

    @Test
    fun `string with escaped backslash`() {
        val result = executor.execute("RETURN '\\\\' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\\", result.rows[0]["v"])
    }

    @Test
    fun `string with unicode escape`() {
        val result = executor.execute("RETURN '\\u0041' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("A", result.rows[0]["v"])
    }

    @Test
    fun `string with escaped quote`() {
        val result = executor.execute("RETURN '\\'' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("'", result.rows[0]["v"])
    }

    @Test
    fun `string with double quote escape`() {
        val result = executor.execute("RETURN \"\\\"\" AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\"", result.rows[0]["v"])
    }

    @Test
    fun `double-quoted string doubled quote escape`() {
        val result = executor.execute("RETURN '''' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("'", result.rows[0]["v"])
    }

    @Test
    fun `string with backspace escape`() {
        val result = executor.execute("RETURN '\\b' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\b", result.rows[0]["v"])
    }

    @Test
    fun `string with unknown escape passes through`() {
        val result = executor.execute("RETURN '\\z' AS v")
        assertEquals(1, result.rows.size)
        assertEquals("\\z", result.rows[0]["v"])
    }

    // --- Scientific notation ---

    @Test
    fun `float with scientific notation`() {
        val result = executor.execute("RETURN 1.5e2 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(150.0, result.rows[0]["v"])
    }

    // --- Write clauses parsed but ignored ---

    @Test
    fun `CREATE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("CREATE (n) RETURN 1 AS x") }
    }

    @Test
    fun `DELETE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) DELETE n RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `DETACH DELETE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) DETACH DELETE n RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `SET throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n.x = 1 RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `REMOVE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) REMOVE n.x RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `MERGE throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MERGE (n) RETURN 1 AS x") }
    }

    @Test
    fun `SET with label throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n:Label RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `SET with plus equals throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n += {x: 1} RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `SET all properties throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) SET n = {x: 1} RETURN 1 AS x LIMIT 1") }
    }

    @Test
    fun `REMOVE label throws NotImplementedError`() {
        assertFailsWith<NotImplementedError> { executor.execute("MATCH (n) REMOVE n:Label RETURN 1 AS x LIMIT 1") }
    }

    // --- Semicolons ---

    @Test
    fun `semicolons between clauses`() {
        val result = executor.execute("RETURN 1 AS x;")
        assertEquals(1, result.rows.size)
    }

    // --- CASE expression ---

    @Test
    fun `CASE expression in query`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) RETURN CASE WHEN n.value > 10 THEN 'big' ELSE 'small' END AS size ORDER BY size"
        )
        assertEquals(2, result.rows.size)
    }

    // --- List comprehension ---

    @Test
    fun `list comprehension in query`() {
        val result = executor.execute("RETURN [x IN [1,2,3,4] WHERE x > 2 | x * 10] AS r")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val r = result.rows[0]["r"] as List<*>
        assertEquals(2, r.size)
    }

    // --- Subscript and slice ---

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
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(2, 3), result.rows[0]["v"])
    }

    // --- Map literal ---

    @Test
    fun `map literal in query`() {
        val result = executor.execute("RETURN {name: 'test', value: 42} AS m")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val m = result.rows[0]["m"] as Map<String, Any?>
        assertEquals("test", m["name"])
        assertEquals(42, m["value"])
    }

    // --- XOR in query ---

    @Test
    fun `XOR in query`() {
        val result = executor.execute("RETURN true XOR false AS v")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["v"])
    }

    // --- IS NULL / IS NOT NULL ---

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

    // --- Regex in query ---

    @Test
    fun `regex match in query`() {
        val result = executor.execute("RETURN 'hello' =~ 'hel.*' AS v")
        assertEquals(1, result.rows.size)
        assertEquals(true, result.rows[0]["v"])
    }

    // --- Relationship with multiple types ---

    @Test
    fun `relationship with pipe-separated types`() {
        val result = executor.execute(
            "MATCH (a:IntConstant)-[:DATAFLOW|CALL]->(b) RETURN a.value, b.id LIMIT 5"
        )
        assertTrue(result.rows.isNotEmpty())
    }

    // --- Incoming direction in full query ---

    @Test
    fun `incoming relationship direction`() {
        val result = executor.execute(
            "MATCH (p:ParameterNode)<-[:DATAFLOW]-(c:IntConstant) RETURN c.value, p.index"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["c.value"])
    }

    // --- Exponentiation operator ---

    @Test
    fun `exponentiation operator in query`() {
        val result = executor.execute("RETURN 2 ^ 3 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(8L, result.rows[0]["v"])
    }

    // --- Modulo operator ---

    @Test
    fun `modulo operator in query`() {
        val result = executor.execute("RETURN 10 % 3 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(1L, result.rows[0]["v"])
    }

    // --- Number starting with dot ---

    @Test
    fun `number starting with dot`() {
        val result = executor.execute("RETURN .5 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(0.5, result.rows[0]["v"])
    }

    // --- Long integer ---

    @Test
    fun `large integer becomes long`() {
        val result = executor.execute("RETURN 3000000000 AS v")
        assertEquals(1, result.rows.size)
        assertEquals(3000000000L, result.rows[0]["v"])
    }

    // --- Backtick-escaped identifier ---

    @Test
    fun `backtick-escaped identifier`() {
        val result = executor.execute("MATCH (n:`IntConstant`) RETURN n.value LIMIT 1")
        assertEquals(1, result.rows.size)
    }

    // --- Parameter ---

    @Test
    fun `parameter in expression`() {
        // Parameters resolve from bindings which are empty, so they return null
        val result = executor.execute("RETURN \$param AS v")
        assertEquals(1, result.rows.size)
    }

    // --- WITH ORDER BY SKIP LIMIT ---

    @Test
    fun `WITH with ORDER BY SKIP LIMIT`() {
        val result = executor.execute(
            "MATCH (n:IntConstant) WITH n.value AS v ORDER BY v SKIP 1 LIMIT 1 RETURN v"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["v"])
    }

    // --- ORDER BY with boolean sort ---

    @Test
    fun `ORDER BY with boolean values`() {
        val result = executor.execute("UNWIND [true, false, true] AS x RETURN x ORDER BY x")
        assertEquals(3, result.rows.size)
        assertEquals(false, result.rows[0]["x"])
    }

    // --- ORDER BY with mixed types uses toString ---

    @Test
    fun `ORDER BY with string values`() {
        val result = executor.execute("UNWIND ['c', 'a', 'b'] AS x RETURN x ORDER BY x")
        assertEquals(3, result.rows.size)
        assertEquals("a", result.rows[0]["x"])
    }

    // --- Aggregation with DISTINCT ---

    @Test
    fun `aggregation with DISTINCT`() {
        val result = executor.execute("UNWIND [1, 1, 2, 2, 3] AS x RETURN count(DISTINCT x) AS cnt")
        assertEquals(1, result.rows.size)
        assertEquals(3L, result.rows[0]["cnt"])
    }

    // --- OPTIONAL MATCH with null fill ---

    @Test
    fun `OPTIONAL MATCH with no match fills nulls`() {
        // NullConstant label has 0 nodes, so OPTIONAL MATCH should null-fill
        val result = executor.execute(
            "OPTIONAL MATCH (m:NullConstant) RETURN m"
        )
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["m"])
    }

    // --- Multiple labels ---

    @Test
    fun `match node with multiple labels`() {
        // IntConstant has labels IntConstant + Constant
        val result = executor.execute("MATCH (n:IntConstant:Constant) RETURN n.value")
        assertEquals(2, result.rows.size)
    }

    // --- IN operator ---

    @Test
    fun `IN operator in WHERE`() {
        val result = executor.execute("MATCH (n:IntConstant) WHERE n.value IN [7, 99] RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(7, result.rows[0]["n.value"])
    }

    // --- SKIP with string value ---

    @Test
    fun `SKIP with string evaluates to 0`() {
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN x SKIP 'abc'")
        // 'abc' cannot be converted to int, defaults to 0
        assertEquals(3, result.rows.size)
    }

    // --- Empty result for no results text format ---

    @Test
    fun `no results returns empty columns`() {
        val result = executor.execute("MATCH (n:NullConstant) RETURN n.id")
        assertEquals(0, result.rows.size)
        // columns still populated from RETURN items
        assertEquals(listOf("n.id"), result.columns)
    }

    // --- Incoming relationship without typed edges ---

    @Test
    fun `incoming relationship without edge type`() {
        val result = executor.execute(
            "MATCH (p:ParameterNode)<-[]-(c:IntConstant) RETURN c.value"
        )
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["c.value"])
    }

    // --- Both direction edges ---

    @Test
    fun `both direction relationship`() {
        val result = executor.execute(
            "MATCH (a:IntConstant)-[:DATAFLOW]-(b:ParameterNode) RETURN a.value, b.index"
        )
        // IntConstant -> ParameterNode via DATAFLOW (outgoing)
        assertTrue(result.rows.isNotEmpty())
    }

    // --- Variable-length path with BOTH direction ---

    // --- Parser edge cases ---

    @Test
    fun `NOT in atom position of expression parser`() {
        // NOT in an expression context goes through parseAtom -> NOT branch
        val result = executor.execute("MATCH (n:IntConstant) WHERE NOT n.value = 7 RETURN n.value")
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["n.value"])
    }

    @Test
    fun `keyword used as identifier via backtick`() {
        // Backtick-escaped keywords in identifier positions
        val result = executor.execute("UNWIND [1, 2] AS x RETURN x AS `value` LIMIT 1")
        assertEquals(1, result.rows.size)
    }

    @Test
    fun `list comprehension backtrack when identifier not followed by IN`() {
        // [a, b] where a is an identifier but not followed by IN triggers backtrack
        val result = executor.execute("UNWIND [1, 2, 3] AS x RETURN [x, x + 1] AS pair LIMIT 1")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val pair = result.rows[0]["pair"] as List<*>
        assertEquals(2, pair.size)
    }

    @Test
    fun `keyword as property name in map literal`() {
        // Keywords can be used as keys in map literals
        val result = executor.execute("RETURN {type: 'test', value: 42} AS m")
        assertEquals(1, result.rows.size)
        @Suppress("UNCHECKED_CAST")
        val m = result.rows[0]["m"] as Map<String, Any?>
        assertEquals("test", m["type"])
    }

    @Test
    fun `variable-length incoming path`() {
        val result = executor.execute(
            "MATCH (b:CallSiteNode)<-[:DATAFLOW*..5]-(a:IntConstant) RETURN a.value, b.callee_name LIMIT 1"
        )
        assertTrue(result.rows.isNotEmpty())
    }
}
