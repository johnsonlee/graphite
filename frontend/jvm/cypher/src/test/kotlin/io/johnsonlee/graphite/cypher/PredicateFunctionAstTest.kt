package io.johnsonlee.graphite.cypher

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PredicateFunctionAstTest {
    @Test
    fun `none single and empty argument functions retain concrete AST semantics`() {
        val returned = assertIs<CypherClause.Return>(CypherDslAdapter.parse(
            "RETURN none(x IN [1] WHERE x = 2), single(x IN [1] WHERE x = 1), count()").single())
        val predicates = returned.items.take(2).map { assertIs<CypherExpr.PredicateFunction>(it.expression) }
        assertEquals(listOf("none", "single"), predicates.map { it.name })
        predicates.forEach { assertEquals("x", it.variable) }
        assertEquals(CypherExpr.FunctionCall("count", emptyList()), returned.items.last().expression)
        assertEquals(CypherExpr.Comparison("=", CypherExpr.Variable("x"), CypherExpr.Literal(2)), predicates[0].predicate)
        assertEquals(CypherExpr.ListLiteral(listOf(CypherExpr.Literal(1))), predicates[1].listExpr)
        val evaluator = ExpressionEvaluator()
        assertEquals(true, evaluator.evaluate(predicates[0], emptyMap()))
        assertEquals(true, evaluator.evaluate(predicates[1], emptyMap()))
    }
}
