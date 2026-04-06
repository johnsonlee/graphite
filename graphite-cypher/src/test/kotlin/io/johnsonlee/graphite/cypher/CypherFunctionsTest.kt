package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.*
import org.junit.Before
import org.junit.Test
import kotlin.math.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CypherFunctionsTest {

    private val type = TypeDescriptor("com.example.Service")
    private val intType = TypeDescriptor("int")
    private val stringType = TypeDescriptor("java.lang.String")
    private val method = MethodDescriptor(type, "process", listOf(intType), stringType)

    @Before
    fun setup() {
        NodeId.reset()
    }

    // ========================================================================
    // String functions
    // ========================================================================

    @Test
    fun `toLower converts string to lowercase`() {
        assertEquals("hello", CypherFunctions.call("toLower", listOf("HELLO")))
        assertEquals("hello", CypherFunctions.call("toLowercase", listOf("HELLO")))
    }

    @Test
    fun `toLower returns null for null input`() {
        assertNull(CypherFunctions.call("toLower", listOf(null)))
    }

    @Test
    fun `toUpper converts string to uppercase`() {
        assertEquals("HELLO", CypherFunctions.call("toUpper", listOf("hello")))
        assertEquals("HELLO", CypherFunctions.call("toUppercase", listOf("hello")))
    }

    @Test
    fun `toUpper returns null for null input`() {
        assertNull(CypherFunctions.call("toUpper", listOf(null)))
    }

    @Test
    fun `trim removes whitespace`() {
        assertEquals("hello", CypherFunctions.call("trim", listOf("  hello  ")))
    }

    @Test
    fun `ltrim removes leading whitespace`() {
        assertEquals("hello  ", CypherFunctions.call("ltrim", listOf("  hello  ")))
    }

    @Test
    fun `rtrim removes trailing whitespace`() {
        assertEquals("  hello", CypherFunctions.call("rtrim", listOf("  hello  ")))
    }

    @Test
    fun `substring with start only`() {
        assertEquals("llo", CypherFunctions.call("substring", listOf("hello", 2)))
    }

    @Test
    fun `substring with start and length`() {
        assertEquals("ll", CypherFunctions.call("substring", listOf("hello", 2, 2)))
    }

    @Test
    fun `substring returns null for null input`() {
        assertNull(CypherFunctions.call("substring", listOf(null, 0)))
    }

    @Test
    fun `substring length exceeding string length is clamped`() {
        assertEquals("lo", CypherFunctions.call("substring", listOf("hello", 3, 100)))
    }

    @Test
    fun `split splits string by delimiter`() {
        assertEquals(listOf("a", "b", "c"), CypherFunctions.call("split", listOf("a,b,c", ",")))
    }

    @Test
    fun `replace replaces substring`() {
        assertEquals("hXllo", CypherFunctions.call("replace", listOf("hello", "e", "X")))
    }

    @Test
    fun `reverse reverses a string`() {
        assertEquals("olleh", CypherFunctions.call("reverse", listOf("hello")))
    }

    @Test
    fun `reverse reverses a list`() {
        assertEquals(listOf(3, 2, 1), CypherFunctions.call("reverse", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `reverse returns null for non-string non-list`() {
        assertNull(CypherFunctions.call("reverse", listOf(42)))
    }

    @Test
    fun `size of string returns length`() {
        assertEquals(5, CypherFunctions.call("size", listOf("hello")))
    }

    @Test
    fun `size of list returns count`() {
        assertEquals(3, CypherFunctions.call("size", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `length is alias for size`() {
        assertEquals(5, CypherFunctions.call("length", listOf("hello")))
    }

    @Test
    fun `size returns null for non-string non-list`() {
        assertNull(CypherFunctions.call("size", listOf(42)))
    }

    @Test
    fun `left returns first n characters`() {
        assertEquals("hel", CypherFunctions.call("left", listOf("hello", 3)))
    }

    @Test
    fun `right returns last n characters`() {
        assertEquals("llo", CypherFunctions.call("right", listOf("hello", 3)))
    }

    // ========================================================================
    // Math functions - basic
    // ========================================================================

    @Test
    fun `abs of positive int`() {
        assertEquals(5, CypherFunctions.call("abs", listOf(5)))
    }

    @Test
    fun `abs of negative int`() {
        assertEquals(5, CypherFunctions.call("abs", listOf(-5)))
    }

    @Test
    fun `abs of negative long`() {
        assertEquals(5L, CypherFunctions.call("abs", listOf(-5L)))
    }

    @Test
    fun `abs of negative float`() {
        assertEquals(3.14f, CypherFunctions.call("abs", listOf(-3.14f)))
    }

    @Test
    fun `abs of negative double`() {
        assertEquals(2.718, CypherFunctions.call("abs", listOf(-2.718)))
    }

    @Test
    fun `abs of null returns null`() {
        assertNull(CypherFunctions.call("abs", listOf(null)))
    }

    @Test
    fun `ceil rounds up`() {
        assertEquals(3.0, CypherFunctions.call("ceil", listOf(2.3)))
    }

    @Test
    fun `floor rounds down`() {
        assertEquals(2.0, CypherFunctions.call("floor", listOf(2.7)))
    }

    @Test
    fun `round rounds to nearest`() {
        assertEquals(3.0, CypherFunctions.call("round", listOf(2.5)))
        assertEquals(2.0, CypherFunctions.call("round", listOf(2.4)))
    }

    @Test
    fun `sign returns 1 for positive`() {
        assertEquals(1, CypherFunctions.call("sign", listOf(42)))
    }

    @Test
    fun `sign returns -1 for negative`() {
        assertEquals(-1, CypherFunctions.call("sign", listOf(-42)))
    }

    @Test
    fun `sign returns 0 for zero`() {
        assertEquals(0, CypherFunctions.call("sign", listOf(0)))
    }

    @Test
    fun `rand returns a value between 0 and 1`() {
        val result = CypherFunctions.call("rand", emptyList()) as Double
        assertTrue(result >= 0.0 && result < 1.0)
    }

    // ========================================================================
    // Math functions - logarithmic
    // ========================================================================

    @Test
    fun `sqrt computes square root`() {
        assertEquals(3.0, CypherFunctions.call("sqrt", listOf(9.0)))
    }

    @Test
    fun `exp computes e to the power`() {
        assertEquals(exp(1.0), CypherFunctions.call("exp", listOf(1.0)))
    }

    @Test
    fun `log computes natural logarithm`() {
        assertEquals(ln(Math.E), CypherFunctions.call("log", listOf(Math.E)))
    }

    @Test
    fun `log10 computes base-10 logarithm`() {
        assertEquals(2.0, CypherFunctions.call("log10", listOf(100.0)))
    }

    @Test
    fun `e returns Euler number`() {
        assertEquals(Math.E, CypherFunctions.call("e", emptyList()))
    }

    // ========================================================================
    // Math functions - trigonometric
    // ========================================================================

    @Test
    fun `sin computes sine`() {
        assertEquals(sin(Math.PI / 2), CypherFunctions.call("sin", listOf(Math.PI / 2)))
    }

    @Test
    fun `cos computes cosine`() {
        assertEquals(cos(0.0), CypherFunctions.call("cos", listOf(0.0)))
    }

    @Test
    fun `tan computes tangent`() {
        assertEquals(tan(Math.PI / 4), CypherFunctions.call("tan", listOf(Math.PI / 4)))
    }

    @Test
    fun `asin computes arc sine`() {
        assertEquals(asin(1.0), CypherFunctions.call("asin", listOf(1.0)))
    }

    @Test
    fun `acos computes arc cosine`() {
        assertEquals(acos(1.0), CypherFunctions.call("acos", listOf(1.0)))
    }

    @Test
    fun `atan computes arc tangent`() {
        assertEquals(atan(1.0), CypherFunctions.call("atan", listOf(1.0)))
    }

    @Test
    fun `atan2 computes two-argument arc tangent`() {
        assertEquals(atan2(1.0, 1.0), CypherFunctions.call("atan2", listOf(1.0, 1.0)))
    }

    @Test
    fun `cot computes cotangent`() {
        val angle = Math.PI / 4
        assertEquals(1.0 / tan(angle), CypherFunctions.call("cot", listOf(angle)))
    }

    @Test
    fun `pi returns PI`() {
        assertEquals(Math.PI, CypherFunctions.call("pi", emptyList()))
    }

    @Test
    fun `degrees converts radians to degrees`() {
        assertEquals(180.0, CypherFunctions.call("degrees", listOf(Math.PI)))
    }

    @Test
    fun `radians converts degrees to radians`() {
        assertEquals(Math.PI, CypherFunctions.call("radians", listOf(180.0)))
    }

    // ========================================================================
    // List functions
    // ========================================================================

    @Test
    fun `head returns first element`() {
        assertEquals(1, CypherFunctions.call("head", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `head returns null for empty list`() {
        assertNull(CypherFunctions.call("head", listOf(emptyList<Int>())))
    }

    @Test
    fun `tail returns all but first`() {
        assertEquals(listOf(2, 3), CypherFunctions.call("tail", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `last returns last element`() {
        assertEquals(3, CypherFunctions.call("last", listOf(listOf(1, 2, 3))))
    }

    @Test
    fun `last returns null for empty list`() {
        assertNull(CypherFunctions.call("last", listOf(emptyList<Int>())))
    }

    @Test
    fun `range with default step`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), CypherFunctions.call("range", listOf(1, 5)))
    }

    @Test
    fun `range with explicit step`() {
        assertEquals(listOf(0L, 2L, 4L), CypherFunctions.call("range", listOf(0, 4, 2)))
    }

    @Test
    fun `range with negative step`() {
        assertEquals(listOf(5L, 3L, 1L), CypherFunctions.call("range", listOf(5, 1, -2)))
    }

    @Test
    fun `range with zero step throws`() {
        assertFailsWith<CypherException> {
            CypherFunctions.call("range", listOf(1, 5, 0))
        }
    }

    @Test
    fun `size of list via size function`() {
        assertEquals(3, CypherFunctions.call("size", listOf(listOf("a", "b", "c"))))
    }

    // ========================================================================
    // Type conversion functions
    // ========================================================================

    @Test
    fun `toInteger from number`() {
        assertEquals(42L, CypherFunctions.call("toInteger", listOf(42)))
        assertEquals(42L, CypherFunctions.call("toInteger", listOf(42.9)))
    }

    @Test
    fun `toInteger from string`() {
        assertEquals(42L, CypherFunctions.call("toInteger", listOf("42")))
    }

    @Test
    fun `toInteger from boolean`() {
        assertEquals(1L, CypherFunctions.call("toInteger", listOf(true)))
        assertEquals(0L, CypherFunctions.call("toInteger", listOf(false)))
    }

    @Test
    fun `toInteger from invalid string returns null`() {
        assertNull(CypherFunctions.call("toInteger", listOf("abc")))
    }

    @Test
    fun `toInteger from null returns null`() {
        assertNull(CypherFunctions.call("toInteger", listOf(null)))
    }

    @Test
    fun `toInt is alias for toInteger`() {
        assertEquals(42L, CypherFunctions.call("toInt", listOf(42)))
    }

    @Test
    fun `toFloat from number`() {
        assertEquals(42.0, CypherFunctions.call("toFloat", listOf(42)))
    }

    @Test
    fun `toFloat from string`() {
        assertEquals(3.14, CypherFunctions.call("toFloat", listOf("3.14")))
    }

    @Test
    fun `toFloat from invalid string returns null`() {
        assertNull(CypherFunctions.call("toFloat", listOf("abc")))
    }

    @Test
    fun `toFloat from null returns null`() {
        assertNull(CypherFunctions.call("toFloat", listOf(null)))
    }

    @Test
    fun `toBoolean from boolean`() {
        assertEquals(true, CypherFunctions.call("toBoolean", listOf(true)))
        assertEquals(false, CypherFunctions.call("toBoolean", listOf(false)))
    }

    @Test
    fun `toBoolean from string`() {
        assertEquals(true, CypherFunctions.call("toBoolean", listOf("true")))
        assertEquals(true, CypherFunctions.call("toBoolean", listOf("TRUE")))
        assertEquals(false, CypherFunctions.call("toBoolean", listOf("false")))
    }

    @Test
    fun `toBoolean from invalid string returns null`() {
        assertNull(CypherFunctions.call("toBoolean", listOf("maybe")))
    }

    @Test
    fun `toBoolean from null returns null`() {
        assertNull(CypherFunctions.call("toBoolean", listOf(null)))
    }

    @Test
    fun `toString converts value`() {
        assertEquals("42", CypherFunctions.call("toString", listOf(42)))
        assertEquals("true", CypherFunctions.call("toString", listOf(true)))
        assertEquals("hello", CypherFunctions.call("toString", listOf("hello")))
    }

    @Test
    fun `toString returns null for null input`() {
        assertNull(CypherFunctions.call("toString", listOf(null)))
    }

    // ========================================================================
    // Scalar functions
    // ========================================================================

    @Test
    fun `id returns node id value`() {
        val node = IntConstant(NodeId.next(), 42)
        assertEquals(node.id.value, CypherFunctions.call("id", listOf(node)))
    }

    @Test
    fun `id returns null for non-node`() {
        assertNull(CypherFunctions.call("id", listOf("not a node")))
    }

    @Test
    fun `coalesce returns first non-null`() {
        assertEquals("a", CypherFunctions.call("coalesce", listOf(null, "a", "b")))
    }

    @Test
    fun `coalesce returns null when all null`() {
        assertNull(CypherFunctions.call("coalesce", listOf(null, null)))
    }

    @Test
    fun `timestamp returns current time`() {
        val before = System.currentTimeMillis()
        val ts = CypherFunctions.call("timestamp", emptyList()) as Long
        val after = System.currentTimeMillis()
        assertTrue(ts in before..after)
    }

    @Test
    fun `labels returns correct labels for IntConstant`() {
        val node = IntConstant(NodeId.next(), 42)
        assertEquals(listOf("IntConstant", "Constant"), CypherFunctions.call("labels", listOf(node)))
    }

    @Test
    fun `labels returns correct labels for CallSiteNode`() {
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(NodeId.next(), method, callee, 10, null, emptyList())
        assertEquals(listOf("CallSiteNode"), CypherFunctions.call("labels", listOf(node)))
    }

    @Test
    fun `labels returns correct labels for all constant types`() {
        assertEquals(listOf("StringConstant", "Constant"), CypherFunctions.call("labels", listOf(StringConstant(NodeId.next(), "x"))))
        assertEquals(listOf("LongConstant", "Constant"), CypherFunctions.call("labels", listOf(LongConstant(NodeId.next(), 1L))))
        assertEquals(listOf("FloatConstant", "Constant"), CypherFunctions.call("labels", listOf(FloatConstant(NodeId.next(), 1.0f))))
        assertEquals(listOf("DoubleConstant", "Constant"), CypherFunctions.call("labels", listOf(DoubleConstant(NodeId.next(), 1.0))))
        assertEquals(listOf("BooleanConstant", "Constant"), CypherFunctions.call("labels", listOf(BooleanConstant(NodeId.next(), true))))
        assertEquals(listOf("NullConstant", "Constant"), CypherFunctions.call("labels", listOf(NullConstant(NodeId.next()))))
        assertEquals(listOf("EnumConstant", "Constant"), CypherFunctions.call("labels", listOf(EnumConstant(NodeId.next(), type, "A"))))
    }

    @Test
    fun `labels returns correct labels for non-constant node types`() {
        assertEquals(listOf("LocalVariable"), CypherFunctions.call("labels", listOf(LocalVariable(NodeId.next(), "x", intType, method))))
        assertEquals(listOf("FieldNode"), CypherFunctions.call("labels", listOf(FieldNode(NodeId.next(), FieldDescriptor(type, "f", intType), false))))
        assertEquals(listOf("ParameterNode"), CypherFunctions.call("labels", listOf(ParameterNode(NodeId.next(), 0, intType, method))))
        assertEquals(listOf("ReturnNode"), CypherFunctions.call("labels", listOf(ReturnNode(NodeId.next(), method))))
    }

    @Test
    fun `labels returns empty for non-node`() {
        assertEquals(emptyList<String>(), CypherFunctions.call("labels", listOf("not a node")))
    }

    @Test
    fun `type returns edge type name`() {
        val from = NodeId.next()
        val to = NodeId.next()
        assertEquals("DATAFLOW", CypherFunctions.call("type", listOf(DataFlowEdge(from, to, DataFlowKind.ASSIGN))))
        assertEquals("CALL", CypherFunctions.call("type", listOf(CallEdge(from, to, false))))
        assertEquals("TYPE", CypherFunctions.call("type", listOf(TypeEdge(from, to, TypeRelation.EXTENDS))))
        assertEquals("CONTROL_FLOW", CypherFunctions.call("type", listOf(ControlFlowEdge(from, to, ControlFlowKind.SEQUENTIAL))))
    }

    @Test
    fun `type returns null for non-edge`() {
        assertNull(CypherFunctions.call("type", listOf("not an edge")))
    }

    @Test
    fun `properties returns all properties of a node`() {
        val node = IntConstant(NodeId.next(), 42)
        val props = CypherFunctions.call("properties", listOf(node)) as Map<*, *>
        assertEquals(node.id.value, props["id"])
        assertEquals(42, props["value"])
    }

    @Test
    fun `properties returns null for non-node`() {
        assertNull(CypherFunctions.call("properties", listOf("not a node")))
    }

    @Test
    fun `keys returns property names of a node`() {
        val node = IntConstant(NodeId.next(), 42)
        val keyList = CypherFunctions.call("keys", listOf(node)) as List<*>
        assertTrue(keyList.contains("id"))
        assertTrue(keyList.contains("value"))
    }

    @Test
    fun `keys returns null for non-node`() {
        assertNull(CypherFunctions.call("keys", listOf("not a node")))
    }

    @Test
    fun `exists returns true for non-null`() {
        assertEquals(true, CypherFunctions.call("exists", listOf("something")))
    }

    @Test
    fun `exists returns false for null`() {
        assertEquals(false, CypherFunctions.call("exists", listOf(null)))
    }

    // ========================================================================
    // Aggregation functions
    // ========================================================================

    @Test
    fun `count returns number of values`() {
        assertEquals(3L, CypherFunctions.aggregate("count", listOf(1, 2, 3)))
    }

    @Test
    fun `count includes nulls`() {
        assertEquals(3L, CypherFunctions.aggregate("count", listOf(1, null, 3)))
    }

    @Test
    fun `sum of numbers`() {
        assertEquals(6.0, CypherFunctions.aggregate("sum", listOf(1, 2, 3)))
    }

    @Test
    fun `sum ignores nulls`() {
        assertEquals(4.0, CypherFunctions.aggregate("sum", listOf(1, null, 3)))
    }

    @Test
    fun `avg of numbers`() {
        assertEquals(2.0, CypherFunctions.aggregate("avg", listOf(1, 2, 3)))
    }

    @Test
    fun `avg ignores nulls`() {
        assertEquals(2.0, CypherFunctions.aggregate("avg", listOf(1, null, 3)))
    }

    @Test
    fun `avg of empty filtered list returns null`() {
        assertNull(CypherFunctions.aggregate("avg", listOf(null, null)))
    }

    @Test
    fun `min returns smallest value`() {
        assertEquals(1, CypherFunctions.aggregate("min", listOf(3, 1, 2)))
    }

    @Test
    fun `min ignores nulls`() {
        assertEquals(1, CypherFunctions.aggregate("min", listOf(null, 3, 1, null, 2)))
    }

    @Test
    fun `min of empty non-null list returns null`() {
        assertNull(CypherFunctions.aggregate("min", listOf(null)))
    }

    @Test
    fun `max returns largest value`() {
        assertEquals(3, CypherFunctions.aggregate("max", listOf(1, 3, 2)))
    }

    @Test
    fun `collect returns all values as list`() {
        assertEquals(listOf(1, 2, 3), CypherFunctions.aggregate("collect", listOf(1, 2, 3)))
    }

    @Test
    fun `collect preserves nulls`() {
        assertEquals(listOf(1, null, 3), CypherFunctions.aggregate("collect", listOf(1, null, 3)))
    }

    @Test
    fun `stdev computes sample standard deviation`() {
        val values = listOf(2, 4, 4, 4, 5, 5, 7, 9)
        val result = CypherFunctions.aggregate("stdev", values) as Double
        // Sample stdev of [2,4,4,4,5,5,7,9]: mean=5, variance=32/7, stdev=sqrt(32/7) ~ 2.138
        val expected = 2.138089935299395
        assertTrue(kotlin.math.abs(result - expected) < 0.001, "Expected ~$expected but got $result")
    }

    @Test
    fun `stdev returns null for fewer than 2 values`() {
        assertNull(CypherFunctions.aggregate("stdev", listOf(1)))
    }

    @Test
    fun `stdevp computes population standard deviation`() {
        val values = listOf(2, 4, 4, 4, 5, 5, 7, 9)
        val result = CypherFunctions.aggregate("stdevp", values) as Double
        // Population stdev of [2,4,4,4,5,5,7,9]
        val mean = 5.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        assertEquals(sqrt(variance), result, 0.001)
    }

    @Test
    fun `stdevp returns null for fewer than 2 values`() {
        assertNull(CypherFunctions.aggregate("stdevp", listOf(5)))
    }

    @Test
    fun `percentileCont returns median by default`() {
        val result = CypherFunctions.aggregate("percentileCont", listOf(1, 2, 3, 4, 5)) as Double
        assertEquals(3.0, result, 0.001)
    }

    @Test
    fun `percentileCont returns null for empty filtered list`() {
        assertNull(CypherFunctions.aggregate("percentileCont", listOf(null)))
    }

    @Test
    fun `percentileDisc returns median by default`() {
        val result = CypherFunctions.aggregate("percentileDisc", listOf(1, 2, 3, 4, 5)) as Double
        assertEquals(3.0, result, 0.001)
    }

    @Test
    fun `percentileDisc returns null for empty filtered list`() {
        assertNull(CypherFunctions.aggregate("percentileDisc", listOf(null)))
    }

    @Test
    fun `aggregate with explicit percentile`() {
        val result = CypherFunctions.aggregate("percentileCont", listOf(10, 20, 30, 40, 50), 0.9) as Double
        // 0.9 * 4 = 3.6 -> interpolate between index 3 (40) and index 4 (50)
        // 40 + 0.6 * (50 - 40) = 46.0
        assertEquals(46.0, result, 0.001)
    }

    // ========================================================================
    // isAggregation
    // ========================================================================

    @Test
    fun `isAggregation returns true for aggregation functions`() {
        assertTrue(CypherFunctions.isAggregation("count"))
        assertTrue(CypherFunctions.isAggregation("COUNT"))
        assertTrue(CypherFunctions.isAggregation("sum"))
        assertTrue(CypherFunctions.isAggregation("avg"))
        assertTrue(CypherFunctions.isAggregation("min"))
        assertTrue(CypherFunctions.isAggregation("max"))
        assertTrue(CypherFunctions.isAggregation("collect"))
        assertTrue(CypherFunctions.isAggregation("percentileCont"))
        assertTrue(CypherFunctions.isAggregation("percentileDisc"))
        assertTrue(CypherFunctions.isAggregation("stdev"))
        assertTrue(CypherFunctions.isAggregation("stdevp"))
    }

    @Test
    fun `isAggregation returns false for scalar functions`() {
        assertTrue(!CypherFunctions.isAggregation("toLower"))
        assertTrue(!CypherFunctions.isAggregation("abs"))
        assertTrue(!CypherFunctions.isAggregation("id"))
    }

    // ========================================================================
    // Error cases
    // ========================================================================

    @Test
    fun `unknown function throws CypherException`() {
        val ex = assertFailsWith<CypherException> {
            CypherFunctions.call("nonexistent", emptyList())
        }
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun `aggregation function in call context throws CypherAggregationException`() {
        for (name in listOf("count", "sum", "avg", "min", "max", "collect", "percentileCont", "percentileDisc", "stdev", "stdevp")) {
            val ex = assertFailsWith<CypherAggregationException> {
                CypherFunctions.call(name, listOf(1))
            }
            assertEquals(name.lowercase(), ex.functionName.lowercase())
        }
    }

    @Test
    fun `unknown aggregation throws CypherException`() {
        assertFailsWith<CypherException> {
            CypherFunctions.aggregate("nonexistent", listOf(1))
        }
    }

    // ========================================================================
    // Node/Edge list functions (nodes, relationships)
    // ========================================================================

    @Test
    fun `nodes extracts nodes from a list`() {
        val node = IntConstant(NodeId.next(), 42)
        val mixed = listOf(node, "not a node", 99)
        val result = CypherFunctions.call("nodes", listOf(mixed)) as List<*>
        assertEquals(1, result.size)
        assertEquals(node, result[0])
    }

    @Test
    fun `nodes returns null for non-list non-path`() {
        assertNull(CypherFunctions.call("nodes", listOf("not a list")))
    }

    @Test
    fun `relationships extracts edges from a list`() {
        val edge = DataFlowEdge(NodeId.next(), NodeId.next(), DataFlowKind.ASSIGN)
        val mixed = listOf(edge, "not an edge", 99)
        val result = CypherFunctions.call("relationships", listOf(mixed)) as List<*>
        assertEquals(1, result.size)
        assertEquals(edge, result[0])
    }

    @Test
    fun `relationships returns null for non-list non-path`() {
        assertNull(CypherFunctions.call("relationships", listOf("not a list")))
    }

    // ========================================================================
    // nodes and relationships from Path
    // ========================================================================

    @Test
    fun `nodes extracts from Path`() {
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val edge = DataFlowEdge(n1.id, n2.id, DataFlowKind.ASSIGN)
        val path = PathFinder.Path(listOf(n1, n2), listOf(edge))
        val result = CypherFunctions.call("nodes", listOf(path)) as List<*>
        assertEquals(2, result.size)
    }

    @Test
    fun `relationships extracts from Path`() {
        val n1 = IntConstant(NodeId.next(), 1)
        val n2 = IntConstant(NodeId.next(), 2)
        val edge = DataFlowEdge(n1.id, n2.id, DataFlowKind.ASSIGN)
        val path = PathFinder.Path(listOf(n1, n2), listOf(edge))
        val result = CypherFunctions.call("relationships", listOf(path)) as List<*>
        assertEquals(1, result.size)
    }

    // ========================================================================
    // getAllProperties on NodePropertyAccessor
    // ========================================================================

    @Test
    fun `getAllProperties returns complete map for CallSiteNode`() {
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repo"), "save", listOf(stringType), TypeDescriptor("void"))
        val node = CallSiteNode(NodeId.next(), method, callee, 42, null, emptyList())
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("com.example.Repo", props["callee_class"])
        assertEquals("save", props["callee_name"])
        assertEquals("com.example.Service", props["caller_class"])
        assertEquals("process", props["caller_name"])
        assertEquals(42, props["line"])
        assertNotNull(props["callee_signature"])
        assertNotNull(props["caller_signature"])
    }

    @Test
    fun `getAllProperties returns complete map for FieldNode`() {
        val node = FieldNode(NodeId.next(), FieldDescriptor(type, "name", stringType), true)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("name", props["name"])
        assertEquals("java.lang.String", props["type"])
        assertEquals("com.example.Service", props["class"])
        assertEquals(true, props["static"])
    }

    @Test
    fun `getAllProperties returns complete map for ParameterNode`() {
        val node = ParameterNode(NodeId.next(), 0, intType, method)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals(0, props["index"])
        assertEquals("int", props["type"])
        assertNotNull(props["method"])
    }

    @Test
    fun `getAllProperties returns complete map for ReturnNode`() {
        val node = ReturnNode(NodeId.next(), method, stringType)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertNotNull(props["method"])
        assertEquals("java.lang.String", props["actual_type"])
    }

    @Test
    fun `getAllProperties returns complete map for ReturnNode without actual type`() {
        val node = ReturnNode(NodeId.next(), method, null)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertNull(props["actual_type"])
    }

    @Test
    fun `getAllProperties returns complete map for LocalVariable`() {
        val node = LocalVariable(NodeId.next(), "x", intType, method)
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("x", props["name"])
        assertEquals("int", props["type"])
        assertNotNull(props["method"])
    }

    @Test
    fun `getAllProperties returns complete map for NullConstant`() {
        val node = NullConstant(NodeId.next())
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertTrue(props.containsKey("value"))
        assertNull(props["value"])
    }

    @Test
    fun `getAllProperties returns complete map for EnumConstant`() {
        val enumType = TypeDescriptor("com.example.Status")
        val node = EnumConstant(NodeId.next(), enumType, "ACTIVE", listOf("active"))
        val props = NodePropertyAccessor.getAllProperties(node)
        assertEquals(node.id.value, props["id"])
        assertEquals("active", props["value"])
        assertEquals("ACTIVE", props["name"])
        assertEquals("com.example.Status", props["enum_type"])
    }

    @Test
    fun `getAllProperties returns complete map for all constant types`() {
        val intNode = IntConstant(NodeId.next(), 42)
        assertEquals(42, NodePropertyAccessor.getAllProperties(intNode)["value"])

        val strNode = StringConstant(NodeId.next(), "hello")
        assertEquals("hello", NodePropertyAccessor.getAllProperties(strNode)["value"])

        val longNode = LongConstant(NodeId.next(), 100L)
        assertEquals(100L, NodePropertyAccessor.getAllProperties(longNode)["value"])

        val floatNode = FloatConstant(NodeId.next(), 3.14f)
        assertEquals(3.14f, NodePropertyAccessor.getAllProperties(floatNode)["value"])

        val doubleNode = DoubleConstant(NodeId.next(), 2.718)
        assertEquals(2.718, NodePropertyAccessor.getAllProperties(doubleNode)["value"])

        val boolNode = BooleanConstant(NodeId.next(), true)
        assertEquals(true, NodePropertyAccessor.getAllProperties(boolNode)["value"])
    }
}
