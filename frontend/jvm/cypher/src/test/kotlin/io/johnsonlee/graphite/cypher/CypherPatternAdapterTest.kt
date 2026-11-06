package io.johnsonlee.graphite.cypher

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class CypherPatternAdapterTest {

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
        val node = assertIs<PatternElement.NodePattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0])
        assertEquals("n", node.variable)
        assertEquals(listOf("CallSiteNode"), node.labels)
    }

    @Test
    fun `16 - NodePattern with properties`() {
        val clauses = CypherDslAdapter.parse("MATCH (n:IntConstant {value: 42}) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0])
        assertEquals(listOf("IntConstant"), node.labels)
        assertEquals(CypherExpr.Literal(42), node.properties["value"])
    }

    @Test
    fun `17 - NodePattern multiple properties`() {
        val clauses = CypherDslAdapter.parse("MATCH (n {name: 'test', value: 42}) RETURN n")
        val node = assertIs<PatternElement.NodePattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[0])
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
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertEquals(Direction.INCOMING, rel.direction)
    }

    @Test
    fun `20 - RelationshipPattern both`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[r]-(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertEquals(Direction.BOTH, rel.direction)
    }

    @Test
    fun `21 - Relationship with type`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[:DATAFLOW]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertNull(rel.variable)
        assertEquals(listOf("DATAFLOW"), rel.types)
    }

    @Test
    fun `22 - Relationship with variable and type`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[r:CALL]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertEquals("r", rel.variable)
        assertEquals(listOf("CALL"), rel.types)
    }

    @Test
    fun `23 - Variable length max only`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*..5]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertTrue(rel.variableLength)
        assertEquals(1, rel.minHops)
        assertEquals(5, rel.maxHops)
    }

    @Test
    fun `24 - Variable length min and max`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*2..5]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertTrue(rel.variableLength)
        assertEquals(2, rel.minHops)
        assertEquals(5, rel.maxHops)
    }

    @Test
    fun `25 - Variable length unbounded`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[*]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
        assertTrue(rel.variableLength)
        assertNull(rel.minHops)
        assertNull(rel.maxHops)
    }

    @Test
    fun `26 - Variable length with type`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[:DATAFLOW*..3]->(b) RETURN a")
        val rel = assertIs<PatternElement.RelationshipPattern>(assertIs<CypherClause.Match>(clauses[0]).patterns[0].elements[1])
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
}
