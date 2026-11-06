package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherExpressionAdapterTest {

    @Test
    fun `29 - Literal int`() {
        val clauses = CypherDslAdapter.parse("RETURN 42")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        assertEquals(CypherExpr.Literal(42), ret.items[0].expression)
    }

    @Test
    fun `30 - Literal float`() {
        val clauses = CypherDslAdapter.parse("RETURN 3.14")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        assertEquals(CypherExpr.Literal(3.14), ret.items[0].expression)
    }

    @Test
    fun `31 - Literal string single quote`() {
        val clauses = CypherDslAdapter.parse("RETURN 'hello'")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        assertEquals(CypherExpr.Literal("hello"), ret.items[0].expression)
    }

    @Test
    fun `32 - Literal string double quote`() {
        val clauses = CypherDslAdapter.parse("RETURN \"hello\"")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        assertEquals(CypherExpr.Literal("hello"), ret.items[0].expression)
    }

    @Test
    fun `33 - Literal boolean true and false`() {
        val trueResult = CypherDslAdapter.parse("RETURN true")
        assertEquals(CypherExpr.Literal(true), assertIs<CypherClause.Return>(trueResult[0]).items[0].expression)
        val falseResult = CypherDslAdapter.parse("RETURN false")
        assertEquals(CypherExpr.Literal(false), assertIs<CypherClause.Return>(falseResult[0]).items[0].expression)
    }

    @Test
    fun `34 - Literal null`() {
        val clauses = CypherDslAdapter.parse("RETURN null")
        assertEquals(CypherExpr.Literal(null), assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
    }

    @Test
    fun `35 - Property access`() {
        val clauses = CypherDslAdapter.parse("RETURN n.value")
        val prop = assertIs<CypherExpr.Property>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("value", prop.propertyName)
        assertEquals(CypherExpr.Variable("n"), prop.expression)
    }

    @Test
    fun `36 - Chained property access`() {
        val clauses = CypherDslAdapter.parse("RETURN n.callee.name")
        val outer = assertIs<CypherExpr.Property>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("name", outer.propertyName)
        val inner = assertIs<CypherExpr.Property>(outer.expression)
        assertEquals("callee", inner.propertyName)
        assertEquals(CypherExpr.Variable("n"), inner.expression)
    }

    @Test
    fun `37 - Operator precedence mul before add`() {
        val clauses = CypherDslAdapter.parse("RETURN 1 + 2 * 3")
        val expr = assertIs<CypherExpr.BinaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("+", expr.op)
        assertEquals(CypherExpr.Literal(1), expr.left)
        val mul = assertIs<CypherExpr.BinaryOp>(expr.right)
        assertEquals("*", mul.op)
        assertEquals(CypherExpr.Literal(2), mul.left)
        assertEquals(CypherExpr.Literal(3), mul.right)
    }

    @Test
    fun `38 - Parenthesized expression`() {
        val clauses = CypherDslAdapter.parse("RETURN (1 + 2) * 3")
        val expr = assertIs<CypherExpr.BinaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("*", expr.op)
        val add = assertIs<CypherExpr.BinaryOp>(expr.left)
        assertEquals("+", add.op)
        assertEquals(CypherExpr.Literal(3), expr.right)
    }

    @Test
    fun `39 - Comparison equals`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value = 42 RETURN n")
        val where = assertIs<CypherClause.Where>(clauses[1])
        val cmp = assertIs<CypherExpr.Comparison>(where.condition)
        assertEquals("=", cmp.op)
        assertIs<CypherExpr.Property>(cmp.left)
        assertEquals(CypherExpr.Literal(42), cmp.right)
    }

    @Test
    fun `40 - Not equal`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value <> 42 RETURN n")
        val cmp = assertIs<CypherExpr.Comparison>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertEquals("<>", cmp.op)
    }

    @Test
    fun `41 - AND`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value > 10 AND n.value < 100 RETURN n")
        val and = assertIs<CypherExpr.And>(assertIs<CypherClause.Where>(clauses[1]).condition)
        val left = assertIs<CypherExpr.Comparison>(and.left)
        assertEquals(">", left.op)
        val right = assertIs<CypherExpr.Comparison>(and.right)
        assertEquals("<", right.op)
    }

    @Test
    fun `42 - OR`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.a = 1 OR n.b = 2 RETURN n")
        val or = assertIs<CypherExpr.Or>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertIs<CypherExpr.Comparison>(or.left)
        assertIs<CypherExpr.Comparison>(or.right)
    }

    @Test
    fun `43 - NOT`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE NOT n.active = true RETURN n")
        val not = assertIs<CypherExpr.Not>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertIs<CypherExpr.Comparison>(not.expression)
    }

    @Test
    fun `44 - Regex match`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.name =~ 'com\\.example\\..*' RETURN n")
        val regex = assertIs<CypherExpr.RegexMatch>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertIs<CypherExpr.Property>(regex.left)
        assertEquals(CypherExpr.Literal("com\\.example\\..*"), regex.right)
    }

    @Test
    fun `45 - STARTS WITH`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.name STARTS WITH 'com' RETURN n")
        val op = assertIs<CypherExpr.StringOp>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertEquals("STARTS WITH", op.op)
        assertEquals(CypherExpr.Literal("com"), op.right)
    }

    @Test
    fun `46 - ENDS WITH`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.name ENDS WITH '.kt' RETURN n")
        val op = assertIs<CypherExpr.StringOp>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertEquals("ENDS WITH", op.op)
    }

    @Test
    fun `47 - CONTAINS`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.name CONTAINS 'example' RETURN n")
        val op = assertIs<CypherExpr.StringOp>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertEquals("CONTAINS", op.op)
    }

    @Test
    fun `48 - IS NULL`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value IS NULL RETURN n")
        val isNull = assertIs<CypherExpr.IsNull>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertIs<CypherExpr.Property>(isNull.expression)
    }

    @Test
    fun `49 - IS NOT NULL`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value IS NOT NULL RETURN n")
        val isNotNull = assertIs<CypherExpr.IsNotNull>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertIs<CypherExpr.Property>(isNotNull.expression)
    }

    @Test
    fun `50 - IN list`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.x IN [1, 2, 3] RETURN n")
        val inOp = assertIs<CypherExpr.ListOp>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertEquals("IN", inOp.op)
        assertIs<CypherExpr.Property>(inOp.left)
        val list = assertIs<CypherExpr.ListLiteral>(inOp.right)
        assertEquals(3, list.elements.size)
    }

    @Test
    fun `51 - List literal`() {
        val clauses = CypherDslAdapter.parse("RETURN [1, 2, 3]")
        val list = assertIs<CypherExpr.ListLiteral>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(3, list.elements.size)
        assertEquals(CypherExpr.Literal(1), list.elements[0])
        assertEquals(CypherExpr.Literal(2), list.elements[1])
        assertEquals(CypherExpr.Literal(3), list.elements[2])
    }

    @Test
    fun `52 - Map literal`() {
        val clauses = CypherDslAdapter.parse("RETURN {name: 'test', value: 42}")
        val map = assertIs<CypherExpr.MapLiteral>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(CypherExpr.Literal("test"), map.entries["name"])
        assertEquals(CypherExpr.Literal(42), map.entries["value"])
    }

    @Test
    fun `53 - List comprehension with map`() {
        val clauses = CypherDslAdapter.parse("RETURN [x IN list WHERE x > 0 | x * 2]")
        val comp = assertIs<CypherExpr.ListComprehension>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("x", comp.variable)
        assertEquals(CypherExpr.Variable("list"), comp.listExpr)
        val pred = assertIs<CypherExpr.Comparison>(comp.predicate!!)
        assertEquals(">", pred.op)
        val mapExpr = assertIs<CypherExpr.BinaryOp>(comp.mapExpr!!)
        assertEquals("*", mapExpr.op)
    }

    @Test
    fun `54 - List comprehension without map`() {
        val clauses = CypherDslAdapter.parse("RETURN [x IN list WHERE x > 0]")
        val comp = assertIs<CypherExpr.ListComprehension>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("x", comp.variable)
        assertIs<CypherExpr.Comparison>(comp.predicate!!)
        assertNull(comp.mapExpr)
    }

    @Test
    fun `55 - Subscript`() {
        val clauses = CypherDslAdapter.parse("RETURN list[0]")
        val sub = assertIs<CypherExpr.Subscript>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(CypherExpr.Variable("list"), sub.expression)
        assertEquals(CypherExpr.Literal(0), sub.index)
    }

    @Test
    fun `56 - Slice`() {
        val clauses = CypherDslAdapter.parse("RETURN list[1..3]")
        val slice = assertIs<CypherExpr.Slice>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(CypherExpr.Variable("list"), slice.expression)
        assertEquals(CypherExpr.Literal(1), slice.from)
        assertEquals(CypherExpr.Literal(3), slice.to)
    }

    @Test
    fun `57 - Simple CASE`() {
        val clauses = CypherDslAdapter.parse("RETURN CASE n.value WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'other' END")
        val case = assertIs<CypherExpr.CaseExpr>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertIs<CypherExpr.Property>(case.test!!)
        assertEquals(2, case.whenClauses.size)
        assertEquals(CypherExpr.Literal(1), case.whenClauses[0].first)
        assertEquals(CypherExpr.Literal("one"), case.whenClauses[0].second)
        assertEquals(CypherExpr.Literal(2), case.whenClauses[1].first)
        assertEquals(CypherExpr.Literal("two"), case.whenClauses[1].second)
        assertEquals(CypherExpr.Literal("other"), case.elseExpr)
    }

    @Test
    fun `58 - Generic CASE`() {
        val clauses = CypherDslAdapter.parse("RETURN CASE WHEN n.value > 0 THEN 'positive' ELSE 'non-positive' END")
        val case = assertIs<CypherExpr.CaseExpr>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertNull(case.test)
        assertEquals(1, case.whenClauses.size)
        assertIs<CypherExpr.Comparison>(case.whenClauses[0].first)
        assertEquals(CypherExpr.Literal("positive"), case.whenClauses[0].second)
        assertEquals(CypherExpr.Literal("non-positive"), case.elseExpr)
    }

    @Test
    fun `59 - Function call`() {
        val clauses = CypherDslAdapter.parse("RETURN toLower(n.name)")
        val fn = assertIs<CypherExpr.FunctionCall>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("toLower", fn.name)
        assertEquals(1, fn.args.size)
        assertIs<CypherExpr.Property>(fn.args[0])
    }

    @Test
    fun `predicate function ANY`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE ANY(label IN labels(n) WHERE label = 'CallSiteNode') RETURN n")
        val predicate = assertIs<CypherExpr.PredicateFunction>(assertIs<CypherClause.Where>(clauses[1]).condition)

        assertEquals("any", predicate.name)
        assertEquals("label", predicate.variable)
        val labelsCall = assertIs<CypherExpr.FunctionCall>(predicate.listExpr)
        assertEquals("labels", labelsCall.name)
        assertEquals(listOf(CypherExpr.Variable("n")), labelsCall.args)

        val condition = assertIs<CypherExpr.Comparison>(predicate.predicate)
        assertEquals("=", condition.op)
        assertEquals(CypherExpr.Variable("label"), condition.left)
        assertEquals(CypherExpr.Literal("CallSiteNode"), condition.right)
    }

    @Test
    fun `predicate function ALL`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE ALL(label IN labels(n) WHERE label CONTAINS 'Constant') RETURN n")
        val predicate = assertIs<CypherExpr.PredicateFunction>(assertIs<CypherClause.Where>(clauses[1]).condition)

        assertEquals("all", predicate.name)
        assertEquals("label", predicate.variable)
        val labelsCall = assertIs<CypherExpr.FunctionCall>(predicate.listExpr)
        assertEquals("labels", labelsCall.name)
        assertEquals(listOf(CypherExpr.Variable("n")), labelsCall.args)

        val condition = assertIs<CypherExpr.StringOp>(predicate.predicate)
        assertEquals("CONTAINS", condition.op)
        assertEquals(CypherExpr.Variable("label"), condition.left)
        assertEquals(CypherExpr.Literal("Constant"), condition.right)
    }

    @Test
    fun `60 - CountStar`() {
        val clauses = CypherDslAdapter.parse("RETURN count(*)")
        assertIs<CypherExpr.CountStar>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
    }

    @Test
    fun `61 - Function with DISTINCT`() {
        val clauses = CypherDslAdapter.parse("RETURN count(DISTINCT n.value)")
        val fn = assertIs<CypherExpr.FunctionCall>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("count", fn.name)
        assertTrue(fn.distinct)
    }

    @Test
    fun `62 - Parameter`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value = \$param RETURN n")
        val cmp = assertIs<CypherExpr.Comparison>(assertIs<CypherClause.Where>(clauses[1]).condition)
        val param = assertIs<CypherExpr.Parameter>(cmp.right)
        assertEquals("param", param.name)
    }

    @Test
    fun `63 - Unary minus`() {
        val clauses = CypherDslAdapter.parse("RETURN -n.value")
        val unary = assertIs<CypherExpr.UnaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("-", unary.op)
        assertIs<CypherExpr.Property>(unary.expression)
    }

    @Test
    fun `64 - Power operator`() {
        val clauses = CypherDslAdapter.parse("RETURN n.value ^ 2")
        val pow = assertIs<CypherExpr.BinaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("^", pow.op)
        assertIs<CypherExpr.Property>(pow.left)
        assertEquals(CypherExpr.Literal(2), pow.right)
    }
}
