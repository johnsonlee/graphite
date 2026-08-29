package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MethodQueryExecutorTest {

    @Test
    fun `Method source returns indexed methods without graph nodes`() {
        val indexedOnly = method("com.example.Child", "qux", listOf("java.lang.String"), "int")
        val graph = DefaultGraph.Builder().apply { addMethod(indexedOnly) }.build()

        val result = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Child' AND m.name STARTS WITH 'qu' " +
                "RETURN m.signature, m.class, m.name, m.parameter_types, m.return_type LIMIT 10"
        )

        assertEquals(1, result.rows.size)
        assertEquals(indexedOnly.signature, result.rows.single()["m.signature"])
        assertEquals("com.example.Child", result.rows.single()["m.class"])
        assertEquals("qux", result.rows.single()["m.name"])
        assertEquals(listOf("java.lang.String"), result.rows.single()["m.parameter_types"])
        assertEquals("int", result.rows.single()["m.return_type"])
        assertTrue(graph.nodes(Node::class.java).none())
    }

    @Test
    fun `Method source uses metadata slice for large zero and late hits under a tiny budget`() {
        val late = method("com.example.Generated", "method300000", emptyList(), "void")
        val graph = SyntheticLargeMethodIndexGraph(late)
        val executor = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1))

        val missing = executor.execute(
            "MATCH (m:Method) WHERE m.name = 'neverPresent' RETURN m.signature LIMIT 1"
        )
        val found = executor.execute(
            "MATCH (m:Method) WHERE m.name = 'method300000' RETURN m.signature LIMIT 1"
        )

        assertTrue(missing.rows.isEmpty())
        assertEquals(late.signature, found.rows.single()["m.signature"])
        assertEquals(2, graph.sliceCalls)
        assertEquals(300_001L, graph.methodCount())
    }

    @Test
    fun `Method source pushes inline exact list suffix contains and regex filters`() {
        val indexedOnly = method("com.example.Child", "qux", listOf("java.lang.String"), "int")
        val graph = DefaultGraph.Builder().apply { addMethod(indexedOnly) }.build()
        val executor = CypherExecutor(graph)

        val exact = executor.execute(
            "MATCH (m:Method {class: 'com.example.Child'}) " +
                "WHERE 'qux' = m.name AND m.parameter_types = ['java.lang.String'] " +
                "AND m.return_type ENDS WITH 'int' RETURN m.signature SKIP 0 LIMIT 10"
        )
        val stringFilters = executor.execute(
            "MATCH (m:Method) WHERE m.name CONTAINS 'u' AND m.class =~ 'com[.]example[.].*' " +
                "RETURN m.signature LIMIT 10"
        )

        assertEquals(indexedOnly.signature, exact.rows.single()["m.signature"])
        assertEquals(indexedOnly.signature, stringFilters.rows.single()["m.signature"])
    }

    @Test
    fun `Method source falls back to methods sequence when slicing is unavailable`() {
        val indexedOnly = method("com.example.Fallback", "load", emptyList(), "void")
        val delegate = DefaultGraph.Builder().build()
        val graph = object : Graph by delegate {
            override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor>? = null

            override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
                sequenceOf(indexedOnly).filter(pattern::matches)
        }

        val result = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.return_type = 'void' RETURN m"
        )

        val value = result.rows.single()["m"] as Map<*, *>
        assertEquals(indexedOnly.signature, value["signature"])
    }

    @Test
    fun `Method source rejects shapes that cannot preserve indexed semantics`() {
        val graph = DefaultGraph.Builder().build()
        val executor = CypherExecutor(graph)
        listOf(
            "MATCH (m:Method) WHERE m.unknown = 'x' RETURN m LIMIT 1",
            "MATCH (m:Method) WHERE 'x' = 'x' RETURN m LIMIT 1",
            "MATCH (m:Method) WHERE m.name = 'a' OR m.name = 'b' RETURN m LIMIT 1",
            "MATCH (m:Method) RETURN size(collect(m.name)) LIMIT 1"
        ).forEach { query ->
            assertFailsWith<CypherException>(query) { executor.execute(query) }
        }

        assertFailsWith<CypherException> {
            MethodQueryExecutor.execute(emptyList(), listOf(CypherGraph("single", graph)), false) {}
        }
        val relationshipOnly = CypherClause.Match(
            listOf(CypherPattern(listOf(PatternElement.RelationshipPattern())))
        )
        assertFailsWith<CypherException> {
            MethodQueryExecutor.execute(listOf(relationshipOnly), listOf(CypherGraph("single", graph)), false) {}
        }
    }

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String) =
        MethodDescriptor(
            TypeDescriptor(owner),
            name,
            parameters.map(::TypeDescriptor),
            TypeDescriptor(returnType)
        )

    private class SyntheticLargeMethodIndexGraph(
        private val lateMethod: MethodDescriptor
    ) : Graph by DefaultGraph.Builder().build() {
        var sliceCalls = 0

        override fun methodCount(): Long = 300_001L

        override fun methodSlice(pattern: MethodPattern, limit: Int): List<MethodDescriptor> {
            sliceCalls++
            return if (limit > 0 && pattern.matches(lateMethod)) listOf(lateMethod) else emptyList()
        }

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
            error("Method metadata queries must not scan graph nodes")
    }
}
