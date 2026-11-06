package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PipelinePathMatchTest : QueryPipelineTestFixture() {

    @Test
    fun `match relationship with edge variable`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("c", "IntConstant"),
                            relPattern(variable = "r", type = "DATAFLOW"),
                            nodePattern("p", "ParameterNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("c"), "value"), "cval"), returnItem(variable("r"), "edge"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertTrue(result.rows[0]["edge"] is DataFlowEdge)
    }

    @Test
    fun `variable-length relationship variable is a relationship list`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("a", "IntConstant"),
                            relPattern(variable = "p", type = "DATAFLOW", variableLength = true, maxHops = 5),
                            nodePattern("b", "CallSiteNode")))),
                CypherClause.Return(listOf(returnItem(variable("p"), "path"))))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
        val relationships = result.rows[0]["path"] as List<*>
        assertEquals(4, relationships.size)
        assertTrue(relationships.all { it is DataFlowEdge })
    }

    @Test
    fun `variable-length relationship properties constrain every hop`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("a", "ParameterNode"),
                            relPattern(
                                variable = "r",
                                type = "DATAFLOW",
                                variableLength = true,
                                minHops = 2,
                                properties = mapOf("kind" to lit("ASSIGN"))),
                            nodePattern("b", "LocalVariable")))),
                CypherClause.Return(listOf(returnItem(variable("r"), "relationships"), returnItem(prop(variable("b"), "name"), "name"))))

        val result = pipeline.execute(clauses)

        assertEquals(1, result.rows.size)
        assertEquals("y", result.rows.single()["name"])
        val relationships = result.rows.single()["relationships"] as List<*>
        assertEquals(2, relationships.size)
        assertTrue(relationships.all { it is DataFlowEdge && it.kind == DataFlowKind.ASSIGN })
    }

    @Test
    fun `relationships cannot repeat across segments of one pattern`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("a", "IntConstant", mapOf("value" to lit(42))),
                            relPattern(type = "DATAFLOW", variableLength = true, minHops = 2, maxHops = 2),
                            nodePattern("b", "LocalVariable"),
                            relPattern(type = "DATAFLOW", direction = Direction.INCOMING),
                            nodePattern("c", "ParameterNode")))),
                CypherClause.Return(listOf(returnItem(variable("c"), "parameter"))))

        val result = pipeline.execute(clauses)

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `optional match with rel variable fills nulls for all pattern variables`() {
        // Tests that CypherPattern.variables() extracts relationship variables too
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("n", "NullConstant"), relPattern(variable = "r", type = "DATAFLOW"), nodePattern("m"))),
                    optional = true),
                CypherClause.Return(listOf(returnItem(variable("n"), "n"), returnItem(variable("r"), "r"), returnItem(variable("m"), "m"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["n"])
        assertNull(result.rows[0]["r"])
        assertNull(result.rows[0]["m"])
    }

    @Test
    fun `optional match where filters candidates before null fill`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant", mapOf("value" to lit(42)))))),
                CypherClause.Match(
                    patterns = listOf(pattern(nodePattern("n"), relPattern(type = "DATAFLOW"), nodePattern("p", "ParameterNode"))),
                    optional = true,
                    where = CypherExpr.Comparison("=", prop(variable("p"), "index"), lit(999))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "nval"), returnItem(variable("p"), "p"))))

        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows.single()["nval"])
        assertNull(result.rows.single()["p"])
    }

    @Test
    fun `match with relationship property constraint`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("c", "IntConstant"),
                            relPattern(type = "DATAFLOW", properties = mapOf("kind" to lit("PARAMETER_PASS"))),
                            nodePattern("p", "ParameterNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("c"), "value"), "cval"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
    }

    @Test
    fun `match with pre-bound variable returns only matching rows`() {
        // Match pattern where a variable is already bound from previous patterns
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(nodePattern("a", "IntConstant")),
                        pattern(nodePattern("a", "IntConstant"), relPattern(type = "DATAFLOW"), nodePattern("b", "ParameterNode")))),
                CypherClause.Return(
                    listOf(returnItem(prop(variable("a"), "value"), "aval"), returnItem(prop(variable("b"), "index"), "bidx"))))
        val result = pipeline.execute(clauses)
        // Only the IntConstant with value 42 has a DATAFLOW to ParameterNode
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["aval"])
    }

    @Test
    fun `match with multiple relationship types`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("a", "IntConstant"), relPattern(type = null, properties = emptyMap()), nodePattern("b")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "value"), "aval"))))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `match with both direction`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("a", "IntConstant"),
                            relPattern(type = "DATAFLOW", direction = Direction.BOTH),
                            nodePattern("b", "ParameterNode")))),
                CypherClause.Return(
                    listOf(returnItem(prop(variable("a"), "value"), "aval"), returnItem(prop(variable("b"), "index"), "bidx"))))
        val result = pipeline.execute(clauses)
        // Should find: intConst42 -> param1 via DATAFLOW
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `match incoming without edge type`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("p", "ParameterNode"),
                            relPattern(direction = Direction.INCOMING),
                            nodePattern("c", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("c"), "value"), "cval"))))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
        assertEquals(42, result.rows[0]["cval"])
    }

    @Test
    fun `match incoming with edge type`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("p", "ParameterNode"),
                            relPattern(type = "DATAFLOW", direction = Direction.INCOMING),
                            nodePattern("c", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("c"), "value"), "cval"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
    }

    @Test
    fun `both direction with edge type filter`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("a"), relPattern(type = "DATAFLOW", direction = Direction.BOTH), nodePattern("b")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "id"), "aid"))),
                CypherClause.Limit(lit(3)))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `variable-length path incoming`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("b", "CallSiteNode"),
                            relPattern(type = "DATAFLOW", direction = Direction.INCOMING, variableLength = true, maxHops = 5),
                            nodePattern("a", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "value"), "aval"))))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `variable-length path both direction`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("a", "IntConstant"),
                            relPattern(type = "DATAFLOW", direction = Direction.BOTH, variableLength = true, maxHops = 3),
                            nodePattern("b")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "value"), "aval"))),
                CypherClause.Limit(lit(5)))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `match with multiple rel types as OR`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("a", "IntConstant"),
                            relPattern(variable = null, type = null, properties = emptyMap()),
                            nodePattern("b")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "value"), "aval"))),
                CypherClause.Limit(lit(3)))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun `target variable bound to different node filters out`() {
        // MATCH (a:IntConstant), (a)-[:DATAFLOW]->(b:ParameterNode)
        // When 'a' is bound from first pattern to intConst7, the DATAFLOW pattern
        // checks if the target node matches the already-bound 'a'. Since intConst7
        // doesn't have DATAFLOW to param, it gets filtered.
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(nodePattern("a", "IntConstant")),
                        pattern(nodePattern("a"), relPattern(type = "DATAFLOW"), nodePattern("b", "ParameterNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "value"), "aval"))))
        val result = pipeline.execute(clauses)
        // Only intConst42 -> param1 should match
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["aval"])
    }

    @Test
    fun `node variable bound to non-matching class returns empty`() {
        // First match binds 'n' to a StringConstant, then try to match IntConstant with same var
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "StringConstant")))),
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "id"), "nid"))))
        val result = pipeline.execute(clauses)
        // StringConstant is not an IntConstant, so second match should produce empty
        assertEquals(0, result.rows.size)
    }

    @Test
    fun `aggregation with non-aggregation expression`() {
        // Aggregation context with a literal expression
        val clauses =
            listOf(
                CypherClause.Unwind(CypherExpr.ListLiteral(listOf(lit(1), lit(2))), "x"),
                CypherClause.Return(listOf(returnItem(CypherExpr.CountStar, "cnt"), returnItem(lit(99), "fixed"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(2L, result.rows[0]["cnt"])
        assertEquals(99, result.rows[0]["fixed"])
    }

    @Test
    fun `match resource relationship by generic RESOURCE type and kind property`() {
        val builder = DefaultGraph.Builder()
        val resourceFile = ResourceFileNode(NodeId.next(), "messages.properties", "classpath", "properties")
        val lookupMethod = MethodDescriptor(type, "lookup", emptyList(), stringType)
        val bundleMethod = MethodDescriptor(TypeDescriptor("java.util.ResourceBundle"), "getString", listOf(stringType), stringType)
        val callSite = CallSiteNode(NodeId.next(), lookupMethod, bundleMethod, 12, null, emptyList())
        builder.addNode(resourceFile)
        builder.addNode(callSite)
        builder.addEdge(ResourceEdge(resourceFile.id, callSite.id, ResourceRelation.LOOKUP))
        val localPipeline = QueryPipeline(builder.build())

        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("r", "ResourceFile", mapOf("path" to lit("messages.properties"))),
                            relPattern(type = "RESOURCE", properties = mapOf("kind" to lit("LOOKUP"))),
                            nodePattern("c", "CallSiteNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("c"), "callee_name"), "name"))))

        val result = localPipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals("getString", result.rows[0]["name"])
    }

    @Test
    fun `path variable without explicit edge variable retains path type`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        CypherPattern(
                            listOf(nodePattern("a", "IntConstant"), relPattern(type = "DATAFLOW"), nodePattern("b", "ParameterNode")),
                            pathVariable = "p"))),
                CypherClause.Return(listOf(returnItem(variable("p"), "path"))))

        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        val path = result.rows[0]["path"] as PathFinder.Path
        assertEquals(2, path.nodes.size)
        assertTrue(path.nodes[0] is IntConstant)
        assertTrue(path.nodes[1] is ParameterNode)
        assertEquals(1, path.edges.size)
        assertTrue(path.edges.single() is DataFlowEdge)
    }

    @Test
    fun `single node named path retains its node`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        CypherPattern(elements = listOf(nodePattern("n", "IntConstant", mapOf("value" to lit(42)))), pathVariable = "p"))),
                CypherClause.Return(listOf(returnItem(variable("p"), "path"))))

        val path = pipeline.execute(clauses).rows.single()["path"] as PathFinder.Path
        assertEquals(listOf(intConst42), path.nodes.map(Node::id))
        assertTrue(path.edges.isEmpty())
    }
}
