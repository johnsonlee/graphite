package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class CypherDslAdapterTest {

    @Test
    fun `parsed query cache reuses immutable AST and excludes parse failures`() {
        CypherDslAdapter.clearParsedQueryCache()
        try {
            val query = "MATCH (n:CallSiteNode) WHERE n.caller_name = \$name RETURN n"
            val first = CypherDslAdapter.parse(query)

            assertEquals(1, CypherDslAdapter.parsedQueryCacheSize())
            assertSame(first, CypherDslAdapter.parse(query))
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN") (first as java.util.List<CypherClause>).clear()
            }
            val match = assertIs<CypherClause.Match>(first.first())
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN") (match.patterns as java.util.List<CypherPattern>).clear()
            }
            assertEquals(first, CypherDslAdapter.parse(query))
            assertFailsWith<CypherParseException> { CypherDslAdapter.parse("MATCH RETURN n") }
            assertEquals(1, CypherDslAdapter.parsedQueryCacheSize())
        } finally {
            CypherDslAdapter.clearParsedQueryCache()
        }
    }

    @Test
    fun `immutable AST freezes nested collection literals`() {
        val mutableValues = mutableListOf<Any?>("value")
        val mutableLiteral = linkedMapOf<Any?, Any?>("key" to mutableValues)
        val immutable =
            listOf(CypherClause.Return(listOf(ReturnItem(CypherExpr.Literal(mutableLiteral), "literal")))).toImmutableCypherAst()
        val literal = assertIs<CypherExpr.Literal>(assertIs<CypherClause.Return>(immutable.single()).items.single().expression).value
        @Suppress("UNCHECKED_CAST") val frozenMap = literal as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST") val frozenValues = frozenMap.getValue("key") as List<Any?>

        mutableValues += "later"
        mutableLiteral["later"] = true

        assertEquals(mapOf<Any?, Any?>("key" to listOf("value")), frozenMap)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN") (frozenMap as java.util.Map<Any?, Any?>).put("poisoned", true)
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN") (frozenValues as java.util.List<Any?>).add("poisoned")
        }
    }

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
        assertNull(match.where)
        val node = assertIs<PatternElement.NodePattern>(match.patterns[0].elements[0])
        assertEquals(listOf("IntConstant"), node.labels)
    }

    @Test
    fun `OPTIONAL MATCH WHERE remains attached to the optional operation`() {
        val clauses = CypherDslAdapter.parse("OPTIONAL MATCH (n:IntConstant) WHERE n.value = 42 RETURN n")

        assertEquals(2, clauses.size)
        val match = assertIs<CypherClause.Match>(clauses[0])
        assertTrue(match.optional)
        val where = assertIs<CypherExpr.Comparison>(match.where)
        assertEquals("=", where.op)
        assertEquals(CypherExpr.Property(CypherExpr.Variable("n"), "value"), where.left)
        assertEquals(CypherExpr.Literal(42), where.right)
        assertIs<CypherClause.Return>(clauses[1])
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
}
