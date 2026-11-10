package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatcherDefaultsTest {
    private val first = StringConstant(NodeId(1), "first")
    private val second = StringConstant(NodeId(2), "second")
    private val edge = DataFlowEdge(first.id, second.id, DataFlowKind.ASSIGN)
    private val graph = DefaultGraph.Builder().addNode(first).addNode(second).addEdge(edge).build()
    private val source = CypherGraph("scope", graph)
    private val context = PipelineExecutionContext(graph, false)
    private val rows = CypherRowOperations(context, false)
    private val access = PipelineGraphAccess(listOf(source), false, context)
    private val paths = PatternPathState(listOf(source), false)
    private val nodes = NodePatternMatcher(listOf(source), false, context, access, rows)
    private val relationships = RelationshipPatternMatcher(context, access, rows, paths, nodes)

    @Test
    fun `unbounded default node matching agrees with lazy matching and preserves input bindings`() {
        val pattern = PatternElement.NodePattern("n", listOf("StringConstant"))
        val input = mapOf("parameter" to 7)
        val expected = listOf(input + ("n" to first), input + ("n" to second))
        assertEquals(expected, nodes.matchNodeElement(pattern, input, navigate = false, trackSegments = false))
        assertEquals(expected, nodes.matchNodeElementLazily(pattern, input, navigate = false, trackSegments = false).toList())
        val matcher = PatternMatcher(listOf(source), context, rows, paths, nodes, relationships)
        assertEquals(expected, matcher.executeMatch(listOf(CypherPattern(listOf(pattern))), listOf(input)))
        assertTrue(nodes.nodeElementCandidates(pattern, mapOf("n" to 7)).none())
    }

    @Test
    fun `unknown seed label yields an empty candidate sequence`() {
        val scanner = DirectStringScanner(listOf(source), context, access)
        val seeds = FilteredMatchSeeds(context, access, rows, nodes, scanner)
        val condition = CypherExpr.StringOp("CONTAINS", CypherExpr.Property(CypherExpr.Variable("n"), "value"), CypherExpr.Literal("first"))
        assertEquals(emptyList(), seeds.directStringBindings(PatternElement.NodePattern("n", listOf("AbsentLabel")),
            condition, listOf(source))?.toList())
    }

    @Test
    fun `relationship traversal with no bound start yields no rows`() {
        val pattern = (CypherDslAdapter.parse("MATCH (a)-->(b) RETURN b").first() as CypherClause.Match).patterns.single()
        assertTrue(relationships.matchRelationshipLazily(pattern.elements[0] as PatternElement.NodePattern,
            pattern.elements[1] as PatternElement.RelationshipPattern, pattern.elements[2] as PatternElement.NodePattern,
            emptyMap()).none())
    }

    @Test
    fun `qualified paths sort by their ordered nodes and edges`() {
        val a = QualifiedPath("scope", listOf(QualifiedNode("scope", graph, first)), emptyList())
        val b = QualifiedPath("scope", listOf(QualifiedNode("scope", graph, second)), emptyList())
        assertTrue(compareOrderValues(a, b, true) < 0)
        assertTrue(compareOrderValues(a, b, false) > 0)
        assertEquals(0, compareOrderValues(a, a.copy(), true))
    }

    @Test
    fun `bound unqualified path reserves its relationships against other pattern elements`() {
        val clauses = CypherDslAdapter.parse("MATCH (a)-[r]->(b)-[s]->(c) RETURN a")
        val pattern = (clauses.first() as CypherClause.Match).patterns.single()
        val bound = paths.prepareRelationshipMatchState(pattern, mapOf("r" to PathFinder.Path(listOf(first, second), listOf(edge))))
        val rels = pattern.elements.filterIsInstance<PatternElement.RelationshipPattern>()
        assertEquals(emptySet(), paths.unavailableRelationships(rels[0], bound, source))
        assertEquals(setOf(edge), paths.unavailableRelationships(rels[1], bound, source))
    }

    @Test
    fun `pipeline executor applies union cap after preserving segment values`() {
        val executor = CypherExecutor(QueryPipeline(graph))
        assertEquals(listOf(mapOf("value" to 1), mapOf("value" to 2)),
            executor.execute("RETURN 1 AS value UNION ALL RETURN 2 AS value UNION ALL RETURN 3 AS value", 2).rows)
        val projection = CypherProjection(context, rows)
        assertEquals(listOf(mapOf("value" to "first")) to listOf("value"),
            projection.projectAndAggregate(listOf(ReturnItem(CypherExpr.Property(CypherExpr.Variable("n"), "value"), "value")),
                listOf(mapOf("n" to first)), false))
    }

    @Test
    fun `dynamic property plan without graph distinguishes missing values and matches`() {
        val clause = CypherDslAdapter.parse("RETURN any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'needle')").single()
            as CypherClause.Return
        val plan = requireNotNull(DynamicPropertyContainsPlan.compile(clause.items.single().expression as CypherExpr.PredicateFunction))
        val resolve: (Any?, String) -> Any? = { target, key -> (target as Map<*, *>)[key] }
        assertEquals(true, plan.evaluate(mapOf("one" to null, "two" to "needle"), "needle", resolve, null))
        assertEquals(null, plan.evaluate(mapOf("one" to null), "needle", resolve, null))
        assertEquals(false, plan.evaluate(mapOf("one" to "other"), "needle", resolve, null))
    }
}
