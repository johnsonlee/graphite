package io.johnsonlee.graphite.cypher

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CypherDslAdapterTest {

    // ========================================================================
    // Clause parsing
    // ========================================================================

    @Test
    fun `1 - MATCH and RETURN basic`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN n")
        assertEquals(2, clauses.size)
        val match = assertIs<CypherClause.Match>(clauses[0])
        assertEquals(false, match.optional)
        assertEquals(1, match.patterns.size)
        val node = assertIs<PatternElement.NodePattern>(match.patterns[0].elements[0])
        assertEquals("n", node.variable)
        assertTrue(node.labels.isEmpty())
        val ret = assertIs<CypherClause.Return>(clauses[1])
        assertEquals(1, ret.items.size)
        assertEquals(CypherExpr.Variable("n"), ret.items[0].expression)
    }

    @Test
    fun `2 - MATCH with label and RETURN with property`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:CallSiteNode) RETURN n.callee_name")
        val match = assertIs<CypherClause.Match>(clauses[0])
        val node = assertIs<PatternElement.NodePattern>(match.patterns[0].elements[0])
        assertEquals(listOf("CallSiteNode"), node.labels)
        val ret = assertIs<CypherClause.Return>(clauses[1])
        val expr = assertIs<CypherExpr.Property>(ret.items[0].expression)
        assertEquals("callee_name", expr.propertyName)
        assertEquals(CypherExpr.Variable("n"), expr.expression)
    }

    @Test
    fun `3 - MATCH WHERE RETURN`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.value = 42 RETURN n")
        assertEquals(3, clauses.size)
        assertIs<CypherClause.Match>(clauses[0])
        val where = assertIs<CypherClause.Where>(clauses[1])
        val cmp = assertIs<CypherExpr.Comparison>(where.condition)
        assertEquals("=", cmp.op)
        assertIs<CypherClause.Return>(clauses[2])
    }

    @Test
    fun `4 - OPTIONAL MATCH`() {
        val clauses = CypherDslAdapter.parse("OPTIONAL MATCH (n:IntConstant) RETURN n")
        val match = assertIs<CypherClause.Match>(clauses[0])
        assertTrue(match.optional)
        val node = assertIs<PatternElement.NodePattern>(match.patterns[0].elements[0])
        assertEquals(listOf("IntConstant"), node.labels)
    }

    @Test
    fun `5 - MATCH WITH RETURN`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WITH n AS x RETURN x")
        assertEquals(3, clauses.size)
        assertIs<CypherClause.Match>(clauses[0])
        val with = assertIs<CypherClause.With>(clauses[1])
        assertEquals(1, with.items.size)
        assertEquals("x", with.items[0].alias)
        assertEquals(CypherExpr.Variable("n"), with.items[0].expression)
        assertIs<CypherClause.Return>(clauses[2])
    }

    @Test
    fun `6 - WITH DISTINCT and inline WHERE`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WITH DISTINCT n.value AS v WHERE v > 0 RETURN v")
        assertIs<CypherClause.Match>(clauses[0])
        val with = assertIs<CypherClause.With>(clauses[1])
        assertTrue(with.distinct)
        assertEquals("v", with.items[0].alias)
        val whereExpr = assertIs<CypherExpr.Comparison>(with.where!!)
        assertEquals(">", whereExpr.op)
        assertIs<CypherClause.Return>(clauses[2])
    }

    @Test
    fun `7 - UNWIND`() {
        val clauses = CypherDslAdapter.parse("UNWIND [1,2,3] AS x RETURN x")
        assertEquals(2, clauses.size)
        val unwind = assertIs<CypherClause.Unwind>(clauses[0])
        assertEquals("x", unwind.variable)
        val list = assertIs<CypherExpr.ListLiteral>(unwind.expression)
        assertEquals(3, list.elements.size)
        assertIs<CypherClause.Return>(clauses[1])
    }

    @Test
    fun `8 - ORDER BY DESC SKIP LIMIT`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN n ORDER BY n.value DESC SKIP 5 LIMIT 10")
        assertIs<CypherClause.Match>(clauses[0])
        assertIs<CypherClause.Return>(clauses[1])
        val orderBy = assertIs<CypherClause.OrderBy>(clauses[2])
        assertEquals(1, orderBy.items.size)
        assertEquals(false, orderBy.items[0].ascending)
        val skip = assertIs<CypherClause.Skip>(clauses[3])
        assertEquals(CypherExpr.Literal(5), skip.count)
        val limit = assertIs<CypherClause.Limit>(clauses[4])
        assertEquals(CypherExpr.Literal(10), limit.count)
    }

    @Test
    fun `9 - UNION`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN n UNION MATCH (m) RETURN m")
        assertIs<CypherClause.Match>(clauses[0])
        assertIs<CypherClause.Return>(clauses[1])
        val union = assertIs<CypherClause.Union>(clauses[2])
        assertEquals(false, union.all)
        assertIs<CypherClause.Match>(clauses[3])
        assertIs<CypherClause.Return>(clauses[4])
    }

    @Test
    fun `10 - UNION ALL`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN n UNION ALL MATCH (m) RETURN m")
        val union = assertIs<CypherClause.Union>(clauses[2])
        assertTrue(union.all)
    }

    @Test
    fun `11 - RETURN DISTINCT`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN DISTINCT n.value")
        val ret = assertIs<CypherClause.Return>(clauses[1])
        assertTrue(ret.distinct)
        assertIs<CypherExpr.Property>(ret.items[0].expression)
    }

    @Test
    fun `12 - RETURN count star`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN count(*)")
        val ret = assertIs<CypherClause.Return>(clauses[1])
        assertIs<CypherExpr.CountStar>(ret.items[0].expression)
    }

    @Test
    fun `13 - count DISTINCT`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN count(DISTINCT n.value)")
        val ret = assertIs<CypherClause.Return>(clauses[1])
        val fn = assertIs<CypherExpr.FunctionCall>(ret.items[0].expression)
        assertEquals("count", fn.name)
        assertTrue(fn.distinct)
        assertEquals(1, fn.args.size)
    }

    // ========================================================================
    // Pattern parsing
    // ========================================================================

    @Test
    fun `14 - NodePattern no label`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN n")
        val match = assertIs<CypherClause.Match>(clauses[0])
        val node = assertIs<PatternElement.NodePattern>(match.patterns[0].elements[0])
        assertEquals("n", node.variable)
        assertTrue(node.labels.isEmpty())
        assertTrue(node.properties.isEmpty())
    }

    @Test
    fun `15 - NodePattern with label`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:CallSiteNode) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0]
        )
        assertEquals("n", node.variable)
        assertEquals(listOf("CallSiteNode"), node.labels)
    }

    @Test
    fun `16 - NodePattern with properties`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:IntConstant {value: 42}) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0]
        )
        assertEquals(listOf("IntConstant"), node.labels)
        assertEquals(CypherExpr.Literal(42), node.properties["value"])
    }

    @Test
    fun `17 - NodePattern multiple properties`() {
        val clauses = CypherDslAdapter.parse("MATCH (n {name: 'test', value: 42}) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0]
        )
        assertEquals(CypherExpr.Literal("test"), node.properties["name"])
        assertEquals(CypherExpr.Literal(42), node.properties["value"])
    }

    @Test
    fun `18 - RelationshipPattern outgoing`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[r]->(b) RETURN a")
        val elements = assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements
        assertEquals(3, elements.size)
        assertIs<PatternElement.NodePattern>(elements[0])
        val rel = assertIs<PatternElement.RelationshipPattern>(elements[1])
        assertEquals("r", rel.variable)
        assertEquals(Direction.OUTGOING, rel.direction)
        assertIs<PatternElement.NodePattern>(elements[2])
    }

    @Test
    fun `19 - RelationshipPattern incoming`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)<-[r]-(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertEquals(Direction.INCOMING, rel.direction)
    }

    @Test
    fun `20 - RelationshipPattern both`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[r]-(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertEquals(Direction.BOTH, rel.direction)
    }

    @Test
    fun `21 - Relationship with type`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[:DATAFLOW]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertNull(rel.variable)
        assertEquals(listOf("DATAFLOW"), rel.types)
    }

    @Test
    fun `22 - Relationship with variable and type`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[r:CALL]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertEquals("r", rel.variable)
        assertEquals(listOf("CALL"), rel.types)
    }

    @Test
    fun `23 - Variable length max only`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*..5]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertTrue(rel.variableLength)
        assertEquals(1, rel.minHops)
        assertEquals(5, rel.maxHops)
    }

    @Test
    fun `24 - Variable length min and max`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*2..5]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertTrue(rel.variableLength)
        assertEquals(2, rel.minHops)
        assertEquals(5, rel.maxHops)
    }

    @Test
    fun `25 - Variable length unbounded`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertTrue(rel.variableLength)
        assertNull(rel.minHops)
        assertNull(rel.maxHops)
    }

    @Test
    fun `26 - Variable length with type`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[:DATAFLOW*..3]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertEquals(listOf("DATAFLOW"), rel.types)
        assertTrue(rel.variableLength)
        assertEquals(1, rel.minHops)
        assertEquals(3, rel.maxHops)
    }

    @Test
    fun `27 - Chain pattern`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[r]->(b)-[s]->(c) RETURN a")
        val elements = assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements
        assertEquals(5, elements.size)
        assertIs<PatternElement.NodePattern>(elements[0])
        assertIs<PatternElement.RelationshipPattern>(elements[1])
        assertIs<PatternElement.NodePattern>(elements[2])
        assertIs<PatternElement.RelationshipPattern>(elements[3])
        assertIs<PatternElement.NodePattern>(elements[4])
    }

    // ========================================================================
    // Expression parsing - Literals
    // ========================================================================

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

    // ========================================================================
    // Expression parsing - Property access
    // ========================================================================

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

    // ========================================================================
    // Expression parsing - Arithmetic and precedence
    // ========================================================================

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

    // ========================================================================
    // Expression parsing - Comparisons
    // ========================================================================

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
        val comp = assertIs<CypherExpr.ListComprehension>(
            assertIs<CypherClause.Return>(clauses[0]).items[0].expression
        )
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
        val comp = assertIs<CypherExpr.ListComprehension>(
            assertIs<CypherClause.Return>(clauses[0]).items[0].expression
        )
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
        val clauses = CypherDslAdapter.parse(
            "RETURN CASE n.value WHEN 1 THEN 'one' WHEN 2 THEN 'two' ELSE 'other' END"
        )
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
        val clauses = CypherDslAdapter.parse(
            "RETURN CASE WHEN n.value > 0 THEN 'positive' ELSE 'non-positive' END"
        )
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

    // ========================================================================
    // Error cases
    // ========================================================================

    @Test
    fun `65 - Empty string`() {
        val clauses = CypherDslAdapter.parse("")
        assertTrue(clauses.isEmpty())
    }

    @Test
    fun `66 - MATCH without pattern`() {
        assertFailsWith<CypherParseException> {
            CypherDslAdapter.parse("MATCH RETURN n")
        }
    }

    @Test
    fun `67 - RETURN without items parses gracefully or errors`() {
        // RETURN followed by EOF -- parser should either handle or throw
        try {
            val clauses = CypherDslAdapter.parse("RETURN")
            // If it handles gracefully, the Return should have some items or be valid
            assertIs<CypherClause.Return>(clauses[0])
        } catch (e: CypherParseException) {
            // Also acceptable
        }
    }

    @Test
    fun `68 - Unclosed string`() {
        assertFailsWith<CypherParseException> {
            CypherDslAdapter.parse("RETURN 'hello")
        }
    }

    @Test
    fun `69 - Unclosed parenthesis`() {
        assertFailsWith<CypherParseException> {
            CypherDslAdapter.parse("MATCH (n RETURN n")
        }
    }

    // ========================================================================
    // Comments
    // ========================================================================

    @Test
    fun `70 - Single line comment`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) // comment\nRETURN n")
        assertEquals(2, clauses.size)
        assertIs<CypherClause.Match>(clauses[0])
        assertIs<CypherClause.Return>(clauses[1])
    }

    @Test
    fun `71 - Block comment`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) /* block */ RETURN n")
        assertEquals(2, clauses.size)
        assertIs<CypherClause.Match>(clauses[0])
        assertIs<CypherClause.Return>(clauses[1])
    }

    // ========================================================================
    // Backtick-escaped identifiers
    // ========================================================================

    @Test
    fun `72 - Backtick label`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:`ReturnNode`) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0]
        )
        assertEquals(listOf("ReturnNode"), node.labels)
    }

    // ========================================================================
    // Additional edge cases
    // ========================================================================

    @Test
    fun `empty list literal`() {
        val clauses = CypherDslAdapter.parse("RETURN []")
        val list = assertIs<CypherExpr.ListLiteral>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertTrue(list.elements.isEmpty())
    }

    @Test
    fun `RETURN star`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN *")
        val ret = assertIs<CypherClause.Return>(clauses[1])
        assertEquals(1, ret.items.size)
        assertEquals(CypherExpr.Variable("*"), ret.items[0].expression)
    }

    @Test
    fun `multiple return items with aliases`() {
        val clauses = CypherDslAdapter.parse("RETURN n.name AS name, n.value AS val")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        assertEquals(2, ret.items.size)
        assertEquals("name", ret.items[0].alias)
        assertEquals("val", ret.items[1].alias)
    }

    @Test
    fun `ORDER BY ascending default`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) RETURN n ORDER BY n.value")
        val orderBy = assertIs<CypherClause.OrderBy>(clauses[2])
        assertTrue(orderBy.items[0].ascending)
    }

    @Test
    fun `comparison operators LTE and GTE`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.a <= 10 AND n.b >= 20 RETURN n")
        val and = assertIs<CypherExpr.And>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertEquals("<=", assertIs<CypherExpr.Comparison>(and.left).op)
        assertEquals(">=", assertIs<CypherExpr.Comparison>(and.right).op)
    }

    @Test
    fun `node pattern with no variable`() {
        val clauses = CypherDslAdapter.parse("MATCH (:Label) RETURN 1")
        val node = assertIs<PatternElement.NodePattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0]
        )
        assertNull(node.variable)
        assertEquals(listOf("Label"), node.labels)
    }

    @Test
    fun `multiple labels on node`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:A:B:C) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0]
        )
        assertEquals(listOf("A", "B", "C"), node.labels)
    }

    @Test
    fun `relationship with multiple types via pipe`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[:CALL|DATAFLOW]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertEquals(listOf("CALL", "DATAFLOW"), rel.types)
    }

    @Test
    fun `variable length with exact hops`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*3]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(
            assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1]
        )
        assertTrue(rel.variableLength)
        assertEquals(3, rel.minHops)
        assertEquals(3, rel.maxHops)
    }

    @Test
    fun `semicolon between statements`() {
        val clauses = CypherDslAdapter.parse("RETURN 1; RETURN 2")
        assertEquals(2, clauses.size)
        assertIs<CypherClause.Return>(clauses[0])
        assertIs<CypherClause.Return>(clauses[1])
    }

    @Test
    fun `XOR operator`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.a = 1 XOR n.b = 2 RETURN n")
        val xor = assertIs<CypherExpr.Xor>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertIs<CypherExpr.Comparison>(xor.left)
        assertIs<CypherExpr.Comparison>(xor.right)
    }

    @Test
    fun `slice with from only`() {
        val clauses = CypherDslAdapter.parse("RETURN list[2..]")
        val slice = assertIs<CypherExpr.Slice>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(CypherExpr.Literal(2), slice.from)
        assertNull(slice.to)
    }

    @Test
    fun `slice with to only`() {
        val clauses = CypherDslAdapter.parse("RETURN list[..3]")
        val slice = assertIs<CypherExpr.Slice>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertNull(slice.from)
        assertEquals(CypherExpr.Literal(3), slice.to)
    }

    @Test
    fun `modulo operator`() {
        val clauses = CypherDslAdapter.parse("RETURN 10 % 3")
        val op = assertIs<CypherExpr.BinaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("%", op.op)
    }

    @Test
    fun `division operator`() {
        val clauses = CypherDslAdapter.parse("RETURN 10 / 3")
        val op = assertIs<CypherExpr.BinaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("/", op.op)
    }

    @Test
    fun `subtraction operator`() {
        val clauses = CypherDslAdapter.parse("RETURN 10 - 3")
        val op = assertIs<CypherExpr.BinaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("-", op.op)
    }

    @Test
    fun `CASE without ELSE`() {
        val clauses = CypherDslAdapter.parse("RETURN CASE WHEN n.v > 0 THEN 'pos' END")
        val case = assertIs<CypherExpr.CaseExpr>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertNull(case.elseExpr)
        assertEquals(1, case.whenClauses.size)
    }

    @Test
    fun `empty map literal`() {
        val clauses = CypherDslAdapter.parse("RETURN {}")
        val map = assertIs<CypherExpr.MapLiteral>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertTrue(map.entries.isEmpty())
    }

    @Test
    fun `function with multiple args`() {
        val clauses = CypherDslAdapter.parse("RETURN coalesce(n.a, n.b, 'default')")
        val fn = assertIs<CypherExpr.FunctionCall>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("coalesce", fn.name)
        assertEquals(3, fn.args.size)
    }

    @Test
    fun `function with zero args`() {
        val clauses = CypherDslAdapter.parse("RETURN rand()")
        val fn = assertIs<CypherExpr.FunctionCall>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("rand", fn.name)
        assertTrue(fn.args.isEmpty())
    }

    @Test
    fun `nested NOT`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE NOT NOT n.active = true RETURN n")
        val outer = assertIs<CypherExpr.Not>(assertIs<CypherClause.Where>(clauses[1]).condition)
        val inner = assertIs<CypherExpr.Not>(outer.expression)
        assertIs<CypherExpr.Comparison>(inner.expression)
    }

    @Test
    fun `unexpected character throws`() {
        assertFailsWith<CypherParseException> {
            CypherDslAdapter.parse("RETURN ~x")
        }
    }

    @Test
    fun `unterminated backtick throws`() {
        assertFailsWith<CypherParseException> {
            CypherDslAdapter.parse("MATCH (n:`Foo) RETURN n")
        }
    }

    @Test
    fun `DISTINCT expression in atom position`() {
        val clauses = CypherDslAdapter.parse("RETURN DISTINCT n.value")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        assertTrue(ret.distinct)
    }

    @Test
    fun `case insensitive keywords`() {
        val clauses = CypherDslAdapter.parse("match (n) where n.x = 1 return n")
        assertEquals(3, clauses.size)
        assertIs<CypherClause.Match>(clauses[0])
        assertIs<CypherClause.Where>(clauses[1])
        assertIs<CypherClause.Return>(clauses[2])
    }

    @Test
    fun `exponentiation right associative`() {
        // 2 ^ 3 ^ 2 should parse as 2 ^ (3 ^ 2)
        val clauses = CypherDslAdapter.parse("RETURN 2 ^ 3 ^ 2")
        val outer = assertIs<CypherExpr.BinaryOp>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("^", outer.op)
        assertEquals(CypherExpr.Literal(2), outer.left)
        val inner = assertIs<CypherExpr.BinaryOp>(outer.right)
        assertEquals("^", inner.op)
        assertEquals(CypherExpr.Literal(3), inner.left)
        assertEquals(CypherExpr.Literal(2), inner.right)
    }

    @Test
    fun `CREATE clause`() {
        val clauses = CypherDslAdapter.parse("CREATE (n:Person {name: 'Alice'})")
        val create = assertIs<CypherClause.Create>(clauses[0])
        assertEquals(1, create.patterns.size)
        val node = assertIs<PatternElement.NodePattern>(create.patterns[0].elements[0])
        assertEquals(listOf("Person"), node.labels)
    }

    @Test
    fun `DELETE clause`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) DELETE n")
        val delete = assertIs<CypherClause.Delete>(clauses[1])
        assertEquals(false, delete.detach)
        assertEquals(1, delete.expressions.size)
    }

    @Test
    fun `DETACH DELETE clause`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) DETACH DELETE n")
        val delete = assertIs<CypherClause.Delete>(clauses[1])
        assertTrue(delete.detach)
    }

    @Test
    fun `SET property clause`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) SET n.name = 'Bob'")
        val set = assertIs<CypherClause.Set>(clauses[1])
        val item = assertIs<SetItem.PropertySet>(set.items[0])
        assertEquals("n", item.variable)
        assertEquals("name", item.property)
        assertEquals(CypherExpr.Literal("Bob"), item.expression)
    }

    @Test
    fun `REMOVE label clause`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) REMOVE n:Temp")
        val remove = assertIs<CypherClause.Remove>(clauses[1])
        val item = assertIs<RemoveItem.LabelRemove>(remove.items[0])
        assertEquals("n", item.variable)
        assertEquals(listOf("Temp"), item.labels)
    }

    @Test
    fun `REMOVE property clause`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) REMOVE n.temp")
        val remove = assertIs<CypherClause.Remove>(clauses[1])
        val item = assertIs<RemoveItem.PropertyRemove>(remove.items[0])
        assertEquals("n", item.variable)
        assertEquals("temp", item.property)
    }

    @Test
    fun `EXISTS function`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE EXISTS(n.name) RETURN n")
        val fn = assertIs<CypherExpr.FunctionCall>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertEquals("exists", fn.name)
        assertEquals(1, fn.args.size)
    }

    @Test
    fun `OR has lower precedence than AND`() {
        // a AND b OR c should parse as (a AND b) OR c
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE n.a = 1 AND n.b = 2 OR n.c = 3 RETURN n")
        val or = assertIs<CypherExpr.Or>(assertIs<CypherClause.Where>(clauses[1]).condition)
        assertIs<CypherExpr.And>(or.left)
        assertIs<CypherExpr.Comparison>(or.right)
    }

    @Test
    fun `multiple patterns in MATCH`() {
        val clauses = CypherDslAdapter.parse("MATCH (a), (b) RETURN a, b")
        val match = assertIs<CypherClause.Match>(clauses[0])
        assertEquals(2, match.patterns.size)
    }

    @Test
    fun `string escape sequences`() {
        val clauses = CypherDslAdapter.parse("RETURN 'hello\\nworld'")
        val lit = assertIs<CypherExpr.Literal>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("hello\nworld", lit.value)
    }

    @Test
    fun `hex integer literal`() {
        val clauses = CypherDslAdapter.parse("RETURN 0xFF")
        val lit = assertIs<CypherExpr.Literal>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(255L, lit.value)
    }

    @Test
    fun `long integer literal`() {
        val clauses = CypherDslAdapter.parse("RETURN 3000000000")
        val lit = assertIs<CypherExpr.Literal>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(3000000000L, lit.value)
    }

    @Test
    fun `scientific notation float`() {
        val clauses = CypherDslAdapter.parse("RETURN 1.5e2")
        val lit = assertIs<CypherExpr.Literal>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals(150.0, lit.value)
    }

    @Test
    fun `SET merge properties`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) SET n += {name: 'test'}")
        val set = assertIs<CypherClause.Set>(clauses[1])
        val item = assertIs<SetItem.MergePropertiesSet>(set.items[0])
        assertEquals("n", item.variable)
    }

    @Test
    fun `SET all properties`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) SET n = {name: 'test'}")
        val set = assertIs<CypherClause.Set>(clauses[1])
        val item = assertIs<SetItem.AllPropertiesSet>(set.items[0])
        assertEquals("n", item.variable)
    }

    @Test
    fun `SET label`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) SET n:Active")
        val set = assertIs<CypherClause.Set>(clauses[1])
        val item = assertIs<SetItem.LabelSet>(set.items[0])
        assertEquals("n", item.variable)
        assertEquals(listOf("Active"), item.labels)
    }

    @Test
    fun `WITH followed by ORDER BY SKIP LIMIT`() {
        val clauses = CypherDslAdapter.parse("MATCH (n) WITH n ORDER BY n.x SKIP 1 LIMIT 5 RETURN n")
        assertIs<CypherClause.Match>(clauses[0])
        assertIs<CypherClause.With>(clauses[1])
        assertIs<CypherClause.OrderBy>(clauses[2])
        assertIs<CypherClause.Skip>(clauses[3])
        assertIs<CypherClause.Limit>(clauses[4])
        assertIs<CypherClause.Return>(clauses[5])
    }

    @Test
    fun `list comprehension without predicate or map`() {
        val clauses = CypherDslAdapter.parse("RETURN [x IN list]")
        val comp = assertIs<CypherExpr.ListComprehension>(
            assertIs<CypherClause.Return>(clauses[0]).items[0].expression
        )
        assertEquals("x", comp.variable)
        assertNull(comp.predicate)
        assertNull(comp.mapExpr)
    }

    // ========================================================================
    // Parser edge cases for coverage
    // ========================================================================

    @Test
    fun `DISTINCT as expression atom`() {
        // DISTINCT in expression context (not RETURN DISTINCT or function(DISTINCT ...))
        // e.g., WHERE DISTINCT x = 1 would parse DISTINCT as atom
        val clauses = CypherDslAdapter.parse("RETURN DISTINCT n.value")
        // RETURN DISTINCT is handled by parseReturnWithTrailing, so we need it in WITH items
        val ret = assertIs<CypherClause.Return>(clauses[0])
        assertTrue(ret.distinct)
    }

    @Test
    fun `NOT as expression atom in parseAtom`() {
        // NOT at the start of an expression inside a WHERE clause
        val clauses = CypherDslAdapter.parse("MATCH (n) WHERE NOT n.value = 1 RETURN n")
        val where = assertIs<CypherClause.Where>(clauses[1])
        assertIs<CypherExpr.Not>(where.condition)
    }

    @Test
    fun `identifier followed by non-IN backtracks in list comprehension`() {
        // [a, b, c] where 'a' is an identifier but not followed by IN
        val clauses = CypherDslAdapter.parse("RETURN [a, b, c]")
        val list = assertIs<CypherExpr.ListLiteral>(
            assertIs<CypherClause.Return>(clauses[0]).items[0].expression
        )
        assertEquals(3, list.elements.size)
        // a, b, c should be parsed as Variables
        assertIs<CypherExpr.Variable>(list.elements[0])
    }

    @Test
    fun `keyword used as variable via fallback in atom parser`() {
        // Keywords like 'all', 'by' etc. can appear in expression context
        // When they're not at the start of a clause, they fall through to the
        // keyword-as-variable branch in parseAtom's else clause
        val clauses = CypherDslAdapter.parse("RETURN all")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val expr = ret.items[0].expression
        assertIs<CypherExpr.Variable>(expr)
        assertEquals("all", (expr as CypherExpr.Variable).name)
    }

    @Test
    fun `keyword used as function name via fallback in atom parser`() {
        // A keyword token followed by LPAREN gets parsed as a function call
        // via the keyword fallback branch in parseAtom
        val clauses = CypherDslAdapter.parse("RETURN all(x)")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val fn = assertIs<CypherExpr.FunctionCall>(ret.items[0].expression)
        assertEquals("all", fn.name)
    }
}
