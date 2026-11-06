package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class QueryPipelineTest : QueryPipelineTestFixture() {

    @Test
    fun `match all nodes`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", null)))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "id"), "id"))))
        val result = pipeline.execute(clauses)
        // 11 nodes total
        assertEquals(11, result.rows.size)
    }

    @Test
    fun `qualified pipeline forwards an explicit work tracker`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", null)))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "id"), "id"))))
        val tracked = QueryPipeline(listOf(CypherGraph("first", graph), CypherGraph("second", graph)), workTrackingEnabled = true)
        val result = tracked.execute(clauses, CypherWorkTracker(CypherExecutionBudget(maxWorkUnits = 100_000)))

        assertEquals(22, result.rows.size)
    }

    @Test
    fun `match nodes by label`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant")))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value"))))
        val result = pipeline.execute(clauses)
        assertEquals(2, result.rows.size)
        val values = result.rows.map { it["value"] }.toSet()
        assertTrue(values.contains(42))
        assertTrue(values.contains(7))
    }

    @Test
    fun `match with inline property constraint`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "IntConstant", mapOf("value" to lit(42)))))),
                CypherClause.Return(listOf(returnItem(prop(variable("n"), "value"), "value"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["value"])
    }

    @Test
    fun `match single hop dataflow relationship`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("c", "IntConstant"), relPattern(type = "DATAFLOW"), nodePattern("p", "ParameterNode")))),
                CypherClause.Return(
                    listOf(returnItem(prop(variable("c"), "value"), "cval"), returnItem(prop(variable("p"), "index"), "pidx"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
        assertEquals(0, result.rows[0]["pidx"])
    }

    @Test
    fun `single hop relationship limit counts complete matches`() {
        NodeId.reset()
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(type, "limited", emptyList(), TypeDescriptor("void"))

        val first = NodeId.next()
        builder.addNode(IntConstant(first, 7))

        val second = NodeId.next()
        builder.addNode(IntConstant(second, 42))

        val param = NodeId.next()
        builder.addNode(ParameterNode(param, 0, intType, method))
        builder.addEdge(DataFlowEdge(second, param, DataFlowKind.PARAMETER_PASS))

        val localPipeline = QueryPipeline(builder.build())
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("c", "IntConstant"), relPattern(type = "DATAFLOW"), nodePattern("p", "ParameterNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("c"), "value"), "value"))),
                CypherClause.Limit(lit(1)))
        val result = localPipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["value"])
    }

    @Test
    fun `single hop relationship limit supports unlabeled source scans`() {
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("a"), relPattern(type = "DATAFLOW"), nodePattern("b")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "id"), "id"))),
                CypherClause.Limit(lit(1)))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(intConst42.value, result.rows[0]["id"])
    }

    @Test
    fun `single hop relationship zero limit returns no rows`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("a", "IntConstant"), relPattern(type = "DATAFLOW"), nodePattern("b", "ParameterNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "value"), "value"))),
                CypherClause.Limit(lit(0)))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `single hop relationship distinct return falls back to generic pipeline`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("a", "StringConstant"), relPattern(type = "DATAFLOW"), nodePattern("b", "CallSiteNode")))),
                CypherClause.Return(listOf(returnItem(prop(variable("a"), "value"), "value")), distinct = true),
                CypherClause.Limit(lit(1)))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals("hello", result.rows[0]["value"])
    }

    @Test
    fun `match variable-length path`() {
        // intConst42 -> param1 -> localVar1 -> localVar2 -> callSite1 (4 hops)
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(
                        pattern(
                            nodePattern("a", "IntConstant"),
                            relPattern(type = "DATAFLOW", variableLength = true, maxHops = 5),
                            nodePattern("b", "CallSiteNode")))),
                CypherClause.Return(
                    listOf(returnItem(prop(variable("a"), "value"), "aval"), returnItem(prop(variable("b"), "callee_name"), "bname"))))
        val result = pipeline.execute(clauses)
        assertTrue(result.rows.isNotEmpty())
        val row = result.rows.first()
        assertEquals(42, row["aval"])
        assertEquals("save", row["bname"])
    }

    @Test
    fun `match chain pattern - multiple patterns`() {
        // Match IntConstant and StringConstant in the same query
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("i", "IntConstant")), pattern(nodePattern("s", "StringConstant")))),
                CypherClause.Return(
                    listOf(returnItem(prop(variable("i"), "value"), "ival"), returnItem(prop(variable("s"), "value"), "sval"))))
        val result = pipeline.execute(clauses)
        // 2 IntConstants x 1 StringConstant = 2 rows
        assertEquals(2, result.rows.size)
        assertTrue(result.rows.all { it["sval"] == "hello" })
    }

    @Test
    fun `optional match with results`() {
        val clauses =
            listOf(
                CypherClause.Match(
                    listOf(pattern(nodePattern("c", "IntConstant"), relPattern(type = "DATAFLOW"), nodePattern("p", "ParameterNode"))),
                    optional = true),
                CypherClause.Return(
                    listOf(returnItem(prop(variable("c"), "value"), "cval"), returnItem(prop(variable("p"), "index"), "pidx"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertEquals(42, result.rows[0]["cval"])
        assertEquals(0, result.rows[0]["pidx"])
    }

    @Test
    fun `optional match without results produces nulls`() {
        // Match a label that doesn't exist in graph
        val clauses =
            listOf(
                CypherClause.Match(listOf(pattern(nodePattern("n", "NullConstant"))), optional = true),
                CypherClause.Return(listOf(returnItem(variable("n"), "n"))))
        val result = pipeline.execute(clauses)
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0]["n"])
    }
}
