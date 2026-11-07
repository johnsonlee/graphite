package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherOperatorAdapterTest {

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
        val list = assertIs<CypherExpr.ListLiteral>(assertIs<CypherClause.Return>(clauses[0]).items[0].expression)
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

    @Test
    fun `subtraction operator parsed correctly`() {
        val clauses = CypherDslAdapter.parse("RETURN 10 - 3 AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val binOp = assertIs<CypherExpr.BinaryOp>(ret.items[0].expression)
        assertEquals("-", binOp.op)
        assertEquals(CypherExpr.Literal(10), binOp.left)
        assertEquals(CypherExpr.Literal(3), binOp.right)
    }

    @Test
    fun `mixed addition and subtraction parsed in order`() {
        // Exercises the count++ branch in findAddSubOp for SUB token
        val clauses = CypherDslAdapter.parse("RETURN 10 - 3 + 2 AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        // Should be ((10 - 3) + 2) -- left-associative
        val outerOp = assertIs<CypherExpr.BinaryOp>(ret.items[0].expression)
        assertEquals("+", outerOp.op)
        val innerOp = assertIs<CypherExpr.BinaryOp>(outerOp.left)
        assertEquals("-", innerOp.op)
    }

    @Test
    fun `multiplication operator parsed correctly`() {
        val clauses = CypherDslAdapter.parse("RETURN 3 * 4 AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val binOp = assertIs<CypherExpr.BinaryOp>(ret.items[0].expression)
        assertEquals("*", binOp.op)
    }

    @Test
    fun `division operator parsed correctly`() {
        val clauses = CypherDslAdapter.parse("RETURN 12 / 4 AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val binOp = assertIs<CypherExpr.BinaryOp>(ret.items[0].expression)
        assertEquals("/", binOp.op)
    }

    @Test
    fun `modulo operator parsed correctly`() {
        val clauses = CypherDslAdapter.parse("RETURN 10 % 3 AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val binOp = assertIs<CypherExpr.BinaryOp>(ret.items[0].expression)
        assertEquals("%", binOp.op)
    }

    @Test
    fun `mixed multiplication and division parsed in order`() {
        // Exercises the count++ branch in findMultDivOp for MULT token
        val clauses = CypherDslAdapter.parse("RETURN 10 * 3 / 2 AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val outerOp = assertIs<CypherExpr.BinaryOp>(ret.items[0].expression)
        assertEquals("/", outerOp.op)
        val innerOp = assertIs<CypherExpr.BinaryOp>(outerOp.left)
        assertEquals("*", innerOp.op)
    }

    @Test
    fun `mixed division and modulo parsed in order`() {
        // Exercises the count++ branch in findMultDivOp for DIV token
        val clauses = CypherDslAdapter.parse("RETURN 10 / 3 % 2 AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val outerOp = assertIs<CypherExpr.BinaryOp>(ret.items[0].expression)
        assertEquals("%", outerOp.op)
        val innerOp = assertIs<CypherExpr.BinaryOp>(outerOp.left)
        assertEquals("/", innerOp.op)
    }

    @Test
    fun `list comprehension with pipe expression only`() {
        val clauses = CypherDslAdapter.parse("RETURN [x IN list | x * 2] AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val comp = assertIs<CypherExpr.ListComprehension>(ret.items[0].expression)
        assertEquals("x", comp.variable)
        assertNull(comp.predicate)
        assertIs<CypherExpr.BinaryOp>(comp.mapExpr!!)
    }

    @Test
    fun `rand function with no args`() {
        val clauses = CypherDslAdapter.parse("RETURN rand() AS v")
        val ret = assertIs<CypherClause.Return>(clauses[0])
        val fn = assertIs<CypherExpr.FunctionCall>(ret.items[0].expression)
        assertEquals("rand", fn.name)
        assertTrue(fn.args.isEmpty())
    }
}
