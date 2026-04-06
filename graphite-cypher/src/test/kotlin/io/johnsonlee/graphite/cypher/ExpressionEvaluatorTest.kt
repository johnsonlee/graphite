package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpressionEvaluatorTest {

    private lateinit var evaluator: ExpressionEvaluator

    @Before
    fun setup() {
        evaluator = ExpressionEvaluator()
    }

    private fun eval(expr: CypherExpr, bindings: Map<String, Any?> = emptyMap()): Any? =
        evaluator.evaluate(expr, bindings)

    private fun lit(value: Any?) = CypherExpr.Literal(value)
    private fun variable(name: String) = CypherExpr.Variable(name)

    // ========================================================================
    // 1. Literals
    // ========================================================================

    @Test
    fun `literal int`() {
        assertEquals(42, eval(lit(42)))
    }

    @Test
    fun `literal float`() {
        assertEquals(3.14, eval(lit(3.14)))
    }

    @Test
    fun `literal string`() {
        assertEquals("hello", eval(lit("hello")))
    }

    @Test
    fun `literal boolean`() {
        assertEquals(true, eval(lit(true)))
        assertEquals(false, eval(lit(false)))
    }

    @Test
    fun `literal null`() {
        assertNull(eval(lit(null)))
    }

    // ========================================================================
    // 2. Variables
    // ========================================================================

    @Test
    fun `bound variable`() {
        assertEquals(99, eval(variable("x"), mapOf("x" to 99)))
    }

    @Test
    fun `unbound variable returns null`() {
        assertNull(eval(variable("missing"), emptyMap()))
    }

    // ========================================================================
    // 3. Property access on Node objects
    // ========================================================================

    @Test
    fun `property access on IntConstant`() {
        NodeId.reset()
        val nodeId = NodeId.next()
        val node = IntConstant(nodeId, 42)
        val expr = CypherExpr.Property(variable("n"), "value")
        assertEquals(42, eval(expr, mapOf("n" to node)))
    }

    @Test
    fun `property access on CallSiteNode`() {
        NodeId.reset()
        val nodeId = NodeId.next()
        val type = TypeDescriptor("com.example.Service")
        val intType = TypeDescriptor("int")
        val stringType = TypeDescriptor("java.lang.String")
        val caller = MethodDescriptor(type, "process", listOf(intType), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(nodeId, caller, callee, 10, null, emptyList())
        val expr = CypherExpr.Property(variable("n"), "callee_name")
        assertEquals("save", eval(expr, mapOf("n" to node)))
    }

    @Test
    fun `property access on map`() {
        val expr = CypherExpr.Property(variable("m"), "key")
        assertEquals("val", eval(expr, mapOf("m" to mapOf("key" to "val"))))
    }

    @Test
    fun `property access on null returns null`() {
        val expr = CypherExpr.Property(variable("m"), "key")
        assertNull(eval(expr, mapOf("m" to null)))
    }

    // ========================================================================
    // 4. Arithmetic
    // ========================================================================

    @Test
    fun `addition of integers`() {
        val expr = CypherExpr.BinaryOp("+", lit(3), lit(4))
        assertEquals(7L, eval(expr))
    }

    @Test
    fun `subtraction of integers`() {
        val expr = CypherExpr.BinaryOp("-", lit(10), lit(3))
        assertEquals(7L, eval(expr))
    }

    @Test
    fun `multiplication of integers`() {
        val expr = CypherExpr.BinaryOp("*", lit(6), lit(7))
        assertEquals(42L, eval(expr))
    }

    @Test
    fun `division of integers`() {
        val expr = CypherExpr.BinaryOp("/", lit(10), lit(2))
        assertEquals(5L, eval(expr))
    }

    @Test
    fun `modulo of integers`() {
        val expr = CypherExpr.BinaryOp("%", lit(10), lit(3))
        assertEquals(1L, eval(expr))
    }

    @Test
    fun `power operator`() {
        val expr = CypherExpr.BinaryOp("^", lit(2), lit(3))
        assertEquals(8L, eval(expr))
    }

    @Test
    fun `addition with doubles`() {
        val expr = CypherExpr.BinaryOp("+", lit(1.5), lit(2.5))
        assertEquals(4.0, eval(expr))
    }

    @Test
    fun `arithmetic with null operand returns null`() {
        val expr = CypherExpr.BinaryOp("+", lit(1), lit(null))
        assertNull(eval(expr))
    }

    // ========================================================================
    // 5. String concatenation
    // ========================================================================

    @Test
    fun `string concatenation`() {
        val expr = CypherExpr.BinaryOp("+",
            CypherExpr.BinaryOp("+", lit("hello"), lit(" ")),
            lit("world")
        )
        assertEquals("hello world", eval(expr))
    }

    @Test
    fun `string plus number`() {
        val expr = CypherExpr.BinaryOp("+", lit("value="), lit(42))
        assertEquals("value=42", eval(expr))
    }

    // ========================================================================
    // 6. List concatenation
    // ========================================================================

    @Test
    fun `list concatenation`() {
        val left = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        val right = CypherExpr.ListLiteral(listOf(lit(3), lit(4)))
        val expr = CypherExpr.BinaryOp("+", left, right)
        assertEquals(listOf(1, 2, 3, 4), eval(expr))
    }

    // ========================================================================
    // 7. Comparisons
    // ========================================================================

    @Test
    fun `equality comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("=", lit(42), lit(42))))
        assertEquals(false, eval(CypherExpr.Comparison("=", lit(42), lit(7))))
    }

    @Test
    fun `not-equal comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<>", lit(1), lit(2))))
        assertEquals(false, eval(CypherExpr.Comparison("<>", lit(1), lit(1))))
    }

    @Test
    fun `less than comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<", lit(1), lit(2))))
        assertEquals(false, eval(CypherExpr.Comparison("<", lit(2), lit(1))))
    }

    @Test
    fun `greater than comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison(">", lit(2), lit(1))))
    }

    @Test
    fun `less than or equal comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<=", lit(1), lit(1))))
        assertEquals(true, eval(CypherExpr.Comparison("<=", lit(1), lit(2))))
    }

    @Test
    fun `greater than or equal comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison(">=", lit(2), lit(2))))
        assertEquals(true, eval(CypherExpr.Comparison(">=", lit(3), lit(2))))
    }

    @Test
    fun `comparison with null - equality`() {
        // null = null => true in Cypher comparison semantics
        assertEquals(true, eval(CypherExpr.Comparison("=", lit(null), lit(null))))
        // null = 1 => null
        assertNull(eval(CypherExpr.Comparison("=", lit(null), lit(1))))
    }

    @Test
    fun `comparison with null - not-equal`() {
        // null <> null => false
        assertEquals(false, eval(CypherExpr.Comparison("<>", lit(null), lit(null))))
        // null <> 1 => null
        assertNull(eval(CypherExpr.Comparison("<>", lit(null), lit(1))))
    }

    @Test
    fun `comparison with null - less than`() {
        assertNull(eval(CypherExpr.Comparison("<", lit(null), lit(1))))
    }

    // ========================================================================
    // 8. Three-valued logic
    // ========================================================================

    @Test
    fun `AND - true AND true`() {
        assertEquals(true, eval(CypherExpr.And(lit(true), lit(true))))
    }

    @Test
    fun `AND - true AND false`() {
        assertEquals(false, eval(CypherExpr.And(lit(true), lit(false))))
    }

    @Test
    fun `AND - true AND null`() {
        assertNull(eval(CypherExpr.And(lit(true), lit(null))))
    }

    @Test
    fun `AND - false AND null`() {
        assertEquals(false, eval(CypherExpr.And(lit(false), lit(null))))
    }

    @Test
    fun `AND - null AND null`() {
        assertNull(eval(CypherExpr.And(lit(null), lit(null))))
    }

    @Test
    fun `OR - true OR null`() {
        assertEquals(true, eval(CypherExpr.Or(lit(true), lit(null))))
    }

    @Test
    fun `OR - false OR null`() {
        assertNull(eval(CypherExpr.Or(lit(false), lit(null))))
    }

    @Test
    fun `OR - false OR false`() {
        assertEquals(false, eval(CypherExpr.Or(lit(false), lit(false))))
    }

    @Test
    fun `OR - null OR null`() {
        assertNull(eval(CypherExpr.Or(lit(null), lit(null))))
    }

    @Test
    fun `NOT - true`() {
        assertEquals(false, eval(CypherExpr.Not(lit(true))))
    }

    @Test
    fun `NOT - false`() {
        assertEquals(true, eval(CypherExpr.Not(lit(false))))
    }

    @Test
    fun `NOT - null`() {
        assertNull(eval(CypherExpr.Not(lit(null))))
    }

    @Test
    fun `XOR - true XOR false`() {
        assertEquals(true, eval(CypherExpr.Xor(lit(true), lit(false))))
    }

    @Test
    fun `XOR - true XOR true`() {
        assertEquals(false, eval(CypherExpr.Xor(lit(true), lit(true))))
    }

    @Test
    fun `XOR - null XOR true`() {
        assertNull(eval(CypherExpr.Xor(lit(null), lit(true))))
    }

    // ========================================================================
    // 9. String operators
    // ========================================================================

    @Test
    fun `STARTS WITH`() {
        assertEquals(true, eval(CypherExpr.StringOp("STARTS WITH", lit("hello world"), lit("hello"))))
        assertEquals(false, eval(CypherExpr.StringOp("STARTS WITH", lit("hello world"), lit("world"))))
    }

    @Test
    fun `ENDS WITH`() {
        assertEquals(true, eval(CypherExpr.StringOp("ENDS WITH", lit("hello world"), lit("world"))))
        assertEquals(false, eval(CypherExpr.StringOp("ENDS WITH", lit("hello world"), lit("hello"))))
    }

    @Test
    fun `CONTAINS`() {
        assertEquals(true, eval(CypherExpr.StringOp("CONTAINS", lit("hello world"), lit("lo wo"))))
        assertEquals(false, eval(CypherExpr.StringOp("CONTAINS", lit("hello world"), lit("xyz"))))
    }

    @Test
    fun `string operator with null returns null`() {
        assertNull(eval(CypherExpr.StringOp("STARTS WITH", lit(null), lit("hello"))))
        assertNull(eval(CypherExpr.StringOp("STARTS WITH", lit("hello"), lit(null))))
    }

    // ========================================================================
    // 10. IN operator
    // ========================================================================

    @Test
    fun `IN - element present`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(true, eval(CypherExpr.ListOp("IN", lit(2), list)))
    }

    @Test
    fun `IN - element absent`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(false, eval(CypherExpr.ListOp("IN", lit(5), list)))
    }

    @Test
    fun `IN - null list returns null`() {
        assertNull(eval(CypherExpr.ListOp("IN", lit(1), lit(null))))
    }

    // ========================================================================
    // 11. Regex
    // ========================================================================

    @Test
    fun `regex match`() {
        assertEquals(true, eval(CypherExpr.RegexMatch(lit("hello"), lit("hel.*"))))
        assertEquals(false, eval(CypherExpr.RegexMatch(lit("hello"), lit("xyz.*"))))
    }

    @Test
    fun `regex with null returns null`() {
        assertNull(eval(CypherExpr.RegexMatch(lit(null), lit("hel.*"))))
        assertNull(eval(CypherExpr.RegexMatch(lit("hello"), lit(null))))
    }

    // ========================================================================
    // 12. IS NULL / IS NOT NULL
    // ========================================================================

    @Test
    fun `IS NULL`() {
        assertEquals(true, eval(CypherExpr.IsNull(lit(null))))
        assertEquals(false, eval(CypherExpr.IsNull(lit(42))))
    }

    @Test
    fun `IS NOT NULL`() {
        assertEquals(true, eval(CypherExpr.IsNotNull(lit(42))))
        assertEquals(false, eval(CypherExpr.IsNotNull(lit(null))))
    }

    // ========================================================================
    // 13. CASE simple
    // ========================================================================

    @Test
    fun `simple CASE expression`() {
        val expr = CypherExpr.CaseExpr(
            test = variable("x"),
            whenClauses = listOf(
                lit(1) to lit("one"),
                lit(2) to lit("two")
            ),
            elseExpr = lit("other")
        )
        assertEquals("one", eval(expr, mapOf("x" to 1)))
        assertEquals("two", eval(expr, mapOf("x" to 2)))
        assertEquals("other", eval(expr, mapOf("x" to 99)))
    }

    // ========================================================================
    // 14. CASE generic
    // ========================================================================

    @Test
    fun `generic CASE expression`() {
        val expr = CypherExpr.CaseExpr(
            test = null,
            whenClauses = listOf(
                CypherExpr.Comparison(">", variable("x"), lit(0)) to lit("positive")
            ),
            elseExpr = lit("non-positive")
        )
        assertEquals("positive", eval(expr, mapOf("x" to 5)))
        assertEquals("non-positive", eval(expr, mapOf("x" to -1)))
    }

    @Test
    fun `CASE with no matching when and no else returns null`() {
        val expr = CypherExpr.CaseExpr(
            test = variable("x"),
            whenClauses = listOf(lit(1) to lit("one")),
            elseExpr = null
        )
        assertNull(eval(expr, mapOf("x" to 99)))
    }

    // ========================================================================
    // 15. List literal
    // ========================================================================

    @Test
    fun `list literal`() {
        val expr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(listOf(1, 2, 3), eval(expr))
    }

    @Test
    fun `empty list literal`() {
        val expr = CypherExpr.ListLiteral(emptyList())
        assertEquals(emptyList<Any?>(), eval(expr))
    }

    // ========================================================================
    // 16. Map literal
    // ========================================================================

    @Test
    fun `map literal`() {
        val expr = CypherExpr.MapLiteral(mapOf("name" to lit("test"), "value" to lit(42)))
        assertEquals(mapOf("name" to "test", "value" to 42), eval(expr))
    }

    // ========================================================================
    // 17. List comprehension
    // ========================================================================

    @Test
    fun `list comprehension with filter and map`() {
        // [x IN [1,2,3,4] WHERE x > 2 | x * 2]
        val listExpr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4)))
        val predicate = CypherExpr.Comparison(">", variable("x"), lit(2))
        val mapExpr = CypherExpr.BinaryOp("*", variable("x"), lit(2))
        val expr = CypherExpr.ListComprehension("x", listExpr, predicate, mapExpr)
        assertEquals(listOf(6L, 8L), eval(expr))
    }

    @Test
    fun `list comprehension without filter`() {
        // [x IN [1,2,3] | x * 10]
        val listExpr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        val mapExpr = CypherExpr.BinaryOp("*", variable("x"), lit(10))
        val expr = CypherExpr.ListComprehension("x", listExpr, null, mapExpr)
        assertEquals(listOf(10L, 20L, 30L), eval(expr))
    }

    @Test
    fun `list comprehension without map expression`() {
        // [x IN [1,2,3,4] WHERE x > 2]
        val listExpr = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4)))
        val predicate = CypherExpr.Comparison(">", variable("x"), lit(2))
        val expr = CypherExpr.ListComprehension("x", listExpr, predicate, null)
        assertEquals(listOf(3, 4), eval(expr))
    }

    @Test
    fun `list comprehension on null list returns null`() {
        val expr = CypherExpr.ListComprehension("x", lit(null), null, null)
        assertNull(eval(expr))
    }

    // ========================================================================
    // 18. Subscript
    // ========================================================================

    @Test
    fun `subscript positive index`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(1, eval(CypherExpr.Subscript(list, lit(0))))
        assertEquals(3, eval(CypherExpr.Subscript(list, lit(2))))
    }

    @Test
    fun `subscript negative index`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(3, eval(CypherExpr.Subscript(list, lit(-1))))
        assertEquals(1, eval(CypherExpr.Subscript(list, lit(-3))))
    }

    @Test
    fun `subscript out of bounds returns null`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        assertNull(eval(CypherExpr.Subscript(list, lit(5))))
    }

    @Test
    fun `subscript on string`() {
        assertEquals("h", eval(CypherExpr.Subscript(lit("hello"), lit(0))))
        assertEquals("o", eval(CypherExpr.Subscript(lit("hello"), lit(-1))))
    }

    // ========================================================================
    // 19. Slice
    // ========================================================================

    @Test
    fun `slice list`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3), lit(4)))
        assertEquals(listOf(2, 3), eval(CypherExpr.Slice(list, lit(1), lit(3))))
    }

    @Test
    fun `slice list with no from`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(listOf(1, 2), eval(CypherExpr.Slice(list, null, lit(2))))
    }

    @Test
    fun `slice list with no to`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2), lit(3)))
        assertEquals(listOf(2, 3), eval(CypherExpr.Slice(list, lit(1), null)))
    }

    @Test
    fun `slice string`() {
        assertEquals("ell", eval(CypherExpr.Slice(lit("hello"), lit(1), lit(4))))
    }

    // ========================================================================
    // 20. Function call
    // ========================================================================

    @Test
    fun `function call - toLower`() {
        val expr = CypherExpr.FunctionCall("toLower", listOf(lit("HELLO")))
        assertEquals("hello", eval(expr))
    }

    @Test
    fun `function call - toUpper`() {
        val expr = CypherExpr.FunctionCall("toUpper", listOf(lit("hello")))
        assertEquals("HELLO", eval(expr))
    }

    @Test
    fun `function call - size`() {
        val expr = CypherExpr.FunctionCall("size", listOf(lit("hello")))
        assertEquals(5, eval(expr))
    }

    // ========================================================================
    // 21. CountStar throws CypherAggregationException
    // ========================================================================

    @Test
    fun `CountStar throws CypherAggregationException`() {
        assertFailsWith<CypherAggregationException> {
            eval(CypherExpr.CountStar)
        }
    }

    // ========================================================================
    // 22. Nested expression
    // ========================================================================

    @Test
    fun `nested expression - (1 + 2) x 3 = 9`() {
        val inner = CypherExpr.BinaryOp("+", lit(1), lit(2))
        val expr = CypherExpr.BinaryOp("*", inner, lit(3))
        val result = eval(expr)
        assertEquals(9, (result as Number).toInt())
    }

    // ========================================================================
    // 23. Division by zero
    // ========================================================================

    @Test
    fun `division by zero throws`() {
        val expr = CypherExpr.BinaryOp("/", lit(10), lit(0))
        assertFailsWith<CypherException> {
            eval(expr)
        }
    }

    // ========================================================================
    // 24. Unary minus
    // ========================================================================

    @Test
    fun `unary minus on int`() {
        val expr = CypherExpr.UnaryOp("-", lit(42))
        assertEquals(-42, eval(expr))
    }

    @Test
    fun `unary minus on double`() {
        val expr = CypherExpr.UnaryOp("-", lit(3.14))
        assertEquals(-3.14, eval(expr))
    }

    @Test
    fun `unary minus on null returns null`() {
        val expr = CypherExpr.UnaryOp("-", lit(null))
        assertNull(eval(expr))
    }

    @Test
    fun `unary plus passes through`() {
        val expr = CypherExpr.UnaryOp("+", lit(42))
        assertEquals(42, eval(expr))
    }

    // ========================================================================
    // Additional coverage: Parameter, Distinct
    // ========================================================================

    @Test
    fun `parameter resolves from bindings`() {
        assertEquals("val", eval(CypherExpr.Parameter("p"), mapOf("p" to "val")))
    }

    @Test
    fun `distinct expression passes through`() {
        assertEquals(42, eval(CypherExpr.Distinct(lit(42))))
    }

    @Test
    fun `string comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<", lit("abc"), lit("def"))))
        assertEquals(true, eval(CypherExpr.Comparison("=", lit("abc"), lit("abc"))))
    }

    @Test
    fun `boolean comparison`() {
        assertEquals(true, eval(CypherExpr.Comparison("<", lit(false), lit(true))))
    }

    @Test
    fun `unknown binary operator throws`() {
        assertFailsWith<CypherException> {
            eval(CypherExpr.BinaryOp("??", lit(1), lit(2)))
        }
    }

    @Test
    fun `unknown unary operator throws`() {
        assertFailsWith<CypherException> {
            eval(CypherExpr.UnaryOp("~", lit(1)))
        }
    }

    @Test
    fun `unknown comparison operator throws`() {
        assertFailsWith<CypherException> {
            eval(CypherExpr.Comparison("!!", lit(1), lit(2)))
        }
    }

    @Test
    fun `unknown string operator throws`() {
        assertFailsWith<CypherException> {
            eval(CypherExpr.StringOp("LIKE", lit("a"), lit("b")))
        }
    }

    @Test
    fun `unknown list operator throws`() {
        assertFailsWith<CypherException> {
            eval(CypherExpr.ListOp("ALL", lit(1), lit(listOf(1))))
        }
    }

    @Test
    fun `unary minus on long`() {
        val expr = CypherExpr.UnaryOp("-", lit(100L))
        assertEquals(-100L, eval(expr))
    }

    @Test
    fun `unary minus on float`() {
        val expr = CypherExpr.UnaryOp("-", lit(2.5f))
        assertEquals(-2.5f, eval(expr))
    }

    @Test
    fun `edge property access - DataFlowEdge kind`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = DataFlowEdge(fromId, toId, DataFlowKind.ASSIGN)
        val expr = CypherExpr.Property(variable("e"), "kind")
        assertEquals("ASSIGN", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - CallEdge virtual`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = CallEdge(fromId, toId, isVirtual = true, isDynamic = false)
        val expr = CypherExpr.Property(variable("e"), "virtual")
        assertEquals(true, eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - type`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = DataFlowEdge(fromId, toId, DataFlowKind.ASSIGN)
        val expr = CypherExpr.Property(variable("e"), "type")
        assertEquals("DATAFLOW", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `list add element to list`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        val expr = CypherExpr.BinaryOp("+", list, lit(3))
        assertEquals(listOf(1, 2, 3), eval(expr))
    }

    @Test
    fun `element add to list`() {
        val list = CypherExpr.ListLiteral(listOf(lit(2), lit(3)))
        val expr = CypherExpr.BinaryOp("+", lit(1), list)
        assertEquals(listOf(1, 2, 3), eval(expr))
    }

    @Test
    fun `unary minus on string falls back to toDouble`() {
        val expr = CypherExpr.UnaryOp("-", lit("5"))
        assertEquals(-5.0, eval(expr))
    }

    @Test
    fun `subscript with non-number index returns null`() {
        val list = CypherExpr.ListLiteral(listOf(lit(1), lit(2)))
        assertNull(eval(CypherExpr.Subscript(list, lit("abc"))))
    }

    @Test
    fun `subscript on non-collection returns null`() {
        assertNull(eval(CypherExpr.Subscript(lit(42), lit(0))))
    }

    @Test
    fun `slice on non-collection returns null`() {
        assertNull(eval(CypherExpr.Slice(lit(42), lit(0), lit(1))))
    }

    @Test
    fun `slice string with no from`() {
        assertEquals("he", eval(CypherExpr.Slice(lit("hello"), null, lit(2))))
    }

    @Test
    fun `slice string with no to`() {
        assertEquals("llo", eval(CypherExpr.Slice(lit("hello"), lit(2), null)))
    }

    @Test
    fun `property access on Path returns length`() {
        NodeId.reset()
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val edge = DataFlowEdge(n1.id, n2.id, DataFlowKind.ASSIGN)
        val path = PathFinder.Path(listOf(n1, n2), listOf(edge))
        val expr = CypherExpr.Property(variable("p"), "length")
        assertEquals(1, eval(expr, mapOf("p" to path)))
    }

    @Test
    fun `property access on Path unknown property returns null`() {
        NodeId.reset()
        val n1 = IntConstant(NodeId.next(), 1)
        val path = PathFinder.Path(listOf(n1), emptyList())
        val expr = CypherExpr.Property(variable("p"), "unknown")
        assertNull(eval(expr, mapOf("p" to path)))
    }

    @Test
    fun `property access on non-node non-map non-edge returns null`() {
        val expr = CypherExpr.Property(variable("x"), "prop")
        assertNull(eval(expr, mapOf("x" to 42)))
    }

    @Test
    fun `edge property access - CallEdge dynamic`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = CallEdge(fromId, toId, isVirtual = false, isDynamic = true)
        val expr = CypherExpr.Property(variable("e"), "dynamic")
        assertEquals(true, eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - CallEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = CallEdge(fromId, toId, isVirtual = false, isDynamic = false)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - TypeEdge kind`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = TypeEdge(fromId, toId, TypeRelation.EXTENDS)
        val expr = CypherExpr.Property(variable("e"), "kind")
        assertEquals("EXTENDS", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - TypeEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = TypeEdge(fromId, toId, TypeRelation.EXTENDS)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - ControlFlowEdge kind`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = ControlFlowEdge(fromId, toId, ControlFlowKind.SEQUENTIAL)
        val expr = CypherExpr.Property(variable("e"), "kind")
        assertEquals("SEQUENTIAL", eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - ControlFlowEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = ControlFlowEdge(fromId, toId, ControlFlowKind.SEQUENTIAL)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `edge property access - DataFlowEdge unknown prop returns null`() {
        NodeId.reset()
        val fromId = NodeId.next()
        val toId = NodeId.next()
        val edge = DataFlowEdge(fromId, toId, DataFlowKind.ASSIGN)
        val expr = CypherExpr.Property(variable("e"), "unknown")
        assertNull(eval(expr, mapOf("e" to edge)))
    }

    @Test
    fun `comparison fallback to toString`() {
        // Two non-numeric non-string non-boolean values compared via toString
        val list1 = CypherExpr.ListLiteral(listOf(lit(1)))
        val list2 = CypherExpr.ListLiteral(listOf(lit(2)))
        // Just ensure it doesn't throw - result is toString comparison
        eval(CypherExpr.Comparison("=", list1, list2))
    }

    // ========================================================================
    // toCypherString coverage
    // ========================================================================

    @Test
    fun `toCypherString - Literal null`() {
        assertEquals("null", CypherExpr.Literal(null).toCypherString())
    }

    @Test
    fun `toCypherString - Literal string`() {
        assertEquals("'hello'", CypherExpr.Literal("hello").toCypherString())
    }

    @Test
    fun `toCypherString - Literal string with quote`() {
        assertEquals("'it\\'s'", CypherExpr.Literal("it's").toCypherString())
    }

    @Test
    fun `toCypherString - Literal boolean`() {
        assertEquals("true", CypherExpr.Literal(true).toCypherString())
        assertEquals("false", CypherExpr.Literal(false).toCypherString())
    }

    @Test
    fun `toCypherString - Literal number`() {
        assertEquals("42", CypherExpr.Literal(42).toCypherString())
        assertEquals("3.14", CypherExpr.Literal(3.14).toCypherString())
    }

    @Test
    fun `toCypherString - Variable`() {
        assertEquals("n", CypherExpr.Variable("n").toCypherString())
    }

    @Test
    fun `toCypherString - Property`() {
        assertEquals("n.value", CypherExpr.Property(CypherExpr.Variable("n"), "value").toCypherString())
    }

    @Test
    fun `toCypherString - Parameter`() {
        assertEquals("\$p", CypherExpr.Parameter("p").toCypherString())
    }

    @Test
    fun `toCypherString - FunctionCall`() {
        assertEquals("toLower('A')", CypherExpr.FunctionCall("toLower", listOf(CypherExpr.Literal("A"))).toCypherString())
    }

    @Test
    fun `toCypherString - FunctionCall with DISTINCT`() {
        assertEquals("count(DISTINCT n)", CypherExpr.FunctionCall("count", listOf(CypherExpr.Variable("n")), distinct = true).toCypherString())
    }

    @Test
    fun `toCypherString - BinaryOp`() {
        assertEquals("1 + 2", CypherExpr.BinaryOp("+", CypherExpr.Literal(1), CypherExpr.Literal(2)).toCypherString())
    }

    @Test
    fun `toCypherString - UnaryOp`() {
        assertEquals("-x", CypherExpr.UnaryOp("-", CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - Comparison`() {
        assertEquals("x = 1", CypherExpr.Comparison("=", CypherExpr.Variable("x"), CypherExpr.Literal(1)).toCypherString())
    }

    @Test
    fun `toCypherString - StringOp`() {
        assertEquals("x STARTS WITH 'a'", CypherExpr.StringOp("STARTS WITH", CypherExpr.Variable("x"), CypherExpr.Literal("a")).toCypherString())
    }

    @Test
    fun `toCypherString - ListOp`() {
        assertEquals("x IN [1, 2]", CypherExpr.ListOp("IN", CypherExpr.Variable("x"), CypherExpr.ListLiteral(listOf(CypherExpr.Literal(1), CypherExpr.Literal(2)))).toCypherString())
    }

    @Test
    fun `toCypherString - RegexMatch`() {
        assertEquals("x =~ 'abc'", CypherExpr.RegexMatch(CypherExpr.Variable("x"), CypherExpr.Literal("abc")).toCypherString())
    }

    @Test
    fun `toCypherString - IsNull`() {
        assertEquals("x IS NULL", CypherExpr.IsNull(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - IsNotNull`() {
        assertEquals("x IS NOT NULL", CypherExpr.IsNotNull(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - Not`() {
        assertEquals("NOT x", CypherExpr.Not(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - And`() {
        assertEquals("a AND b", CypherExpr.And(CypherExpr.Variable("a"), CypherExpr.Variable("b")).toCypherString())
    }

    @Test
    fun `toCypherString - Or`() {
        assertEquals("a OR b", CypherExpr.Or(CypherExpr.Variable("a"), CypherExpr.Variable("b")).toCypherString())
    }

    @Test
    fun `toCypherString - Xor`() {
        assertEquals("a XOR b", CypherExpr.Xor(CypherExpr.Variable("a"), CypherExpr.Variable("b")).toCypherString())
    }

    @Test
    fun `toCypherString - CaseExpr simple`() {
        val expr = CypherExpr.CaseExpr(
            test = CypherExpr.Variable("x"),
            whenClauses = listOf(CypherExpr.Literal(1) to CypherExpr.Literal("one")),
            elseExpr = CypherExpr.Literal("other")
        )
        assertEquals("CASE x WHEN 1 THEN 'one' ELSE 'other' END", expr.toCypherString())
    }

    @Test
    fun `toCypherString - CaseExpr generic`() {
        val expr = CypherExpr.CaseExpr(
            test = null,
            whenClauses = listOf(CypherExpr.Comparison(">", CypherExpr.Variable("x"), CypherExpr.Literal(0)) to CypherExpr.Literal("positive")),
            elseExpr = null
        )
        assertEquals("CASE WHEN x > 0 THEN 'positive' END", expr.toCypherString())
    }

    @Test
    fun `toCypherString - ListLiteral`() {
        assertEquals("[1, 2, 3]", CypherExpr.ListLiteral(listOf(CypherExpr.Literal(1), CypherExpr.Literal(2), CypherExpr.Literal(3))).toCypherString())
    }

    @Test
    fun `toCypherString - MapLiteral`() {
        val expr = CypherExpr.MapLiteral(mapOf("name" to CypherExpr.Literal("test")))
        assertEquals("{name: 'test'}", expr.toCypherString())
    }

    @Test
    fun `toCypherString - ListComprehension`() {
        val expr = CypherExpr.ListComprehension("x", CypherExpr.Variable("list"),
            CypherExpr.Comparison(">", CypherExpr.Variable("x"), CypherExpr.Literal(0)),
            CypherExpr.BinaryOp("*", CypherExpr.Variable("x"), CypherExpr.Literal(2))
        )
        assertEquals("[x IN list WHERE x > 0 | x * 2]", expr.toCypherString())
    }

    @Test
    fun `toCypherString - ListComprehension without filter or map`() {
        val expr = CypherExpr.ListComprehension("x", CypherExpr.Variable("list"), null, null)
        assertEquals("[x IN list]", expr.toCypherString())
    }

    @Test
    fun `toCypherString - Subscript`() {
        assertEquals("list[0]", CypherExpr.Subscript(CypherExpr.Variable("list"), CypherExpr.Literal(0)).toCypherString())
    }

    @Test
    fun `toCypherString - Slice`() {
        assertEquals("list[1..3]", CypherExpr.Slice(CypherExpr.Variable("list"), CypherExpr.Literal(1), CypherExpr.Literal(3)).toCypherString())
    }

    @Test
    fun `toCypherString - Slice no from`() {
        assertEquals("list[..3]", CypherExpr.Slice(CypherExpr.Variable("list"), null, CypherExpr.Literal(3)).toCypherString())
    }

    @Test
    fun `toCypherString - Slice no to`() {
        assertEquals("list[1..]", CypherExpr.Slice(CypherExpr.Variable("list"), CypherExpr.Literal(1), null).toCypherString())
    }

    @Test
    fun `toCypherString - Distinct`() {
        assertEquals("DISTINCT x", CypherExpr.Distinct(CypherExpr.Variable("x")).toCypherString())
    }

    @Test
    fun `toCypherString - CountStar`() {
        assertEquals("count(*)", CypherExpr.CountStar.toCypherString())
    }
}
