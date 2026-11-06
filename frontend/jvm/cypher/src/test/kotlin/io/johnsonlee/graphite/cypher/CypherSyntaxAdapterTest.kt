package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherSyntaxAdapterTest {

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

    @Test
    fun `72 - Backtick label`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:`ReturnNode`) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0])
        assertEquals(listOf("ReturnNode"), node.labels)
    }

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
        val node = assertIs<PatternElement.NodePattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0])
        assertNull(node.variable)
        assertEquals(listOf("Label"), node.labels)
    }

    @Test
    fun `multiple labels on node`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:A:B:C) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0])
        assertEquals(listOf("A", "B", "C"), node.labels)
    }

    @Test
    fun `relationship with multiple types via pipe`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[:CALL|DATAFLOW]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertEquals(listOf("CALL", "DATAFLOW"), rel.types)
    }

    @Test
    fun `variable length with exact hops`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*3]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
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
        assertFailsWith<CypherParseException> { CypherDslAdapter.parse("RETURN ~x") }
    }

    @Test
    fun `unterminated backtick throws`() {
        assertFailsWith<CypherParseException> { CypherDslAdapter.parse("MATCH (n:`Foo) RETURN n") }
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
        val comp = assertIs<CypherExpr.ListComprehension>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
        assertEquals("x", comp.variable)
        assertNull(comp.predicate)
        assertNull(comp.mapExpr)
    }
}
