package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.ClassConstant
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import kotlin.test.assertEquals
import org.junit.Test

/** `ClassConstant`: labels, `value` predicates (descriptors hold a `;`), maps and dataflow in the Kotlin engine. */
class ClassConstantCypherTest {
    private val caller = MethodDescriptor(TypeDescriptor("sample.Setup"), "init", emptyList(), TypeDescriptor("void"))
    private val register = MethodDescriptor(
        TypeDescriptor("sample.Registry"), "register", listOf(TypeDescriptor("java.lang.Class")), TypeDescriptor("void"))
    private val payload = ClassConstant(NodeId(1), "Lsample/Payload;")
    private val payloads = ClassConstant(NodeId(2), "[Lsample/Payload;")
    private val ints = ClassConstant(NodeId(3), "[[I")
    private val text = StringConstant(NodeId(4), "Lsample/Payload;")
    private val local = LocalVariable(NodeId(5), "type", TypeDescriptor("java.lang.Class"), caller)
    private val call = CallSiteNode(NodeId(6), caller, register, 7, null, listOf(local.id))

    private val executor = CypherExecutor(DefaultGraph.Builder().apply {
        listOf(payload, payloads, ints, text, local, call).forEach(::addNode)
        addEdge(DataFlowEdge(payload.id, local.id, DataFlowKind.ASSIGN))
        addEdge(DataFlowEdge(local.id, call.id, DataFlowKind.PARAMETER_PASS))
    }.build())

    private fun column(query: String): List<Any?> = executor.execute(query).rows.map { it.values.single() }

    @Test
    fun `label matches only class literals and labels name the constant kind`() {
        assertEquals(
            listOf("Lsample/Payload;", "[Lsample/Payload;", "[[I"),
            column("MATCH (n:ClassConstant) RETURN n.value AS v ORDER BY v"))
        assertEquals(listOf(listOf("ClassConstant", "Constant")), column("MATCH (n:classconstant) WHERE n.id = 3 RETURN labels(n) AS l"))
        assertEquals(listOf("ClassConstant"), column("MATCH (n) WHERE n.id = 1 RETURN n.type AS t"))
        assertEquals(4L, column("MATCH (n:Constant) RETURN count(n) AS c").single(), "three class literals and the string")
    }

    @Test
    fun `value predicates compare the descriptor as text`() {
        assertEquals(listOf(1), column("MATCH (n:ClassConstant) WHERE n.value = 'Lsample/Payload;' RETURN n.id AS id"))
        assertEquals(listOf(4), column("MATCH (n:StringConstant {value: 'Lsample/Payload;'}) RETURN n.id AS id"))
        assertEquals(listOf(2, 3), column("MATCH (n:ClassConstant) WHERE n.value STARTS WITH '[' RETURN n.id AS id ORDER BY id"))
        assertEquals(listOf(3), column("MATCH (n:ClassConstant) WHERE n.value IN ['[[I', 'Lmissing;'] RETURN n.id AS id"))
        assertEquals(listOf(1, 2), column("MATCH (n:ClassConstant) WHERE n.value CONTAINS 'Payload' RETURN n.id AS id ORDER BY id"))
        assertEquals(listOf(1), column("MATCH (n:ClassConstant) WHERE n.value =~ 'L.*;' RETURN n.id AS id"))
        assertEquals(2L, column("MATCH (n:ClassConstant) WHERE n.value ENDS WITH ';' RETURN count(n.value) AS c").single())
    }

    @Test
    fun `node maps and properties carry the descriptor`() {
        assertEquals(
            listOf(mapOf("id" to 3, "type" to "ClassConstant", "value" to "[[I")),
            column("MATCH (n:ClassConstant {value: '[[I'}) RETURN n AS n"))
        assertEquals(listOf(mapOf("id" to 3, "value" to "[[I")), column("MATCH (n:ClassConstant {value: '[[I'}) RETURN properties(n) AS p"))
        assertEquals(listOf(listOf("id", "value")), column("MATCH (n:ClassConstant {value: '[[I'}) RETURN keys(n) AS k"))
        assertEquals(listOf(null), column("MATCH (n:ClassConstant {value: '[[I'}) RETURN n.name AS name"))
    }

    @Test
    fun `a class literal reaches the call argument through the local`() {
        assertEquals(
            listOf("Lsample/Payload;"),
            column(
                "MATCH (c:ClassConstant)-[:DATAFLOW*1..2]->(cs:CallSiteNode {callee_name: 'register'}) RETURN c.value AS v"))
    }

    @Test
    fun `statements split only on semicolons outside literals and escaped names`() {
        assertEquals(listOf("a 'x;y' b", " c"), CypherDslAdapter.splitStatements("a 'x;y' b; c"))
        assertEquals(listOf("\"p;\\\";q\"", ""), CypherDslAdapter.splitStatements("\"p;\\\";q\";"))
        assertEquals(listOf("`a;b` 'it''s;'"), CypherDslAdapter.splitStatements("`a;b` 'it''s;'"))
        assertEquals(listOf("RETURN 1 AS a", " RETURN 2 AS b"), CypherDslAdapter.splitStatements("RETURN 1 AS a; RETURN 2 AS b"))
    }
}
