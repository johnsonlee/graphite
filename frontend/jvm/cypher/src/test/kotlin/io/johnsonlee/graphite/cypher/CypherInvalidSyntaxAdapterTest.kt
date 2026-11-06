package io.johnsonlee.graphite.cypher

import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class CypherInvalidSyntaxAdapterTest {

    @Test
    fun `65 - Empty string`() {
        val clauses = CypherDslAdapter.parse("")
        assertTrue(clauses.isEmpty())
    }

    @Test
    fun `66 - MATCH without pattern`() {
        assertFailsWith<CypherParseException> { CypherDslAdapter.parse("MATCH RETURN n") }
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
        assertFailsWith<CypherParseException> { CypherDslAdapter.parse("RETURN 'hello") }
    }

    @Test
    fun `69 - Unclosed parenthesis`() {
        assertFailsWith<CypherParseException> { CypherDslAdapter.parse("MATCH (n RETURN n") }
    }
}
