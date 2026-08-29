package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodMetadataScanConsumer
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.graph.StreamingMethodLookup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `Method metadata scans do not consume graph work budget`() {
        val methods = (0..2).map { method("com.example.Generated", "method$it", emptyList(), "void") }
        val graph = SyntheticMethodIndexGraph(methods)
        val executor = CypherExecutor(graph, CypherExecutionBudget(maxWorkUnits = 1))

        val result = executor.execute(
            "MATCH (m:Method) WHERE m.name = 'neverPresent' RETURN m.signature LIMIT 1"
        )

        assertTrue(result.rows.isEmpty())
        assertEquals(3L, graph.methodCount())
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
    fun `Method virtual nodes expose a stable Cypher schema`() {
        val indexedOnly = method("com.example.Schema", "load", listOf("java.lang.String"), "int")
        val graph = DefaultGraph.Builder().apply { addMethod(indexedOnly) }.build()
        val executor = CypherExecutor(graph)

        val result = executor.execute(
            "MATCH (m:Method {signature: '${indexedOnly.signature}'}) " +
                "RETURN id(m) AS id, elementId(m) AS elementId, labels(m) AS labels, " +
                "keys(m) AS keys, properties(m) AS properties LIMIT 1"
        )
        val row = result.rows.single()

        assertNull(row["id"])
        assertEquals("Method:${indexedOnly.signature}", row["elementId"])
        assertEquals(listOf("Method"), row["labels"])
        assertEquals(
            listOf("signature", "class", "name", "parameter_types", "return_type"),
            row["keys"]
        )
        assertEquals(
            linkedMapOf(
                "signature" to indexedOnly.signature,
                "class" to "com.example.Schema",
                "name" to "load",
                "parameter_types" to listOf("java.lang.String"),
                "return_type" to "int"
            ),
            row["properties"]
        )
        assertTrue(executor.execute("MATCH (m:Method) RETURN m LIMIT 0").rows.isEmpty())
    }

    @Test
    fun `Method index path handles residual predicates and declines unsupported shapes`() {
        val graph = DefaultGraph.Builder().build()
        val sources = listOf(CypherGraph("default", graph))
        fun tryFastPath(cypher: String): CypherResult? = MethodQueryExecutor.tryExecute(
            CypherDslAdapter.parse(cypher),
            sources,
            qualified = false,
            checkCancelled = {},
            workTracker = null
        )

        assertNull(tryFastPath("RETURN 1"))
        assertNotNull(tryFastPath("MATCH (m:Method) WHERE true RETURN m"))
        assertNotNull(tryFastPath("MATCH (m:Method) WHERE 1 = 1 RETURN m"))
        assertNull(tryFastPath("MATCH (m:Method {name: other}) RETURN m"))
        assertNull(tryFastPath("MATCH (m:Method) RETURN count(m) AS total ORDER BY total"))
        assertNull(tryFastPath("MATCH (m:Method) RETURN m.name AS name ORDER BY m.name LIMIT 1"))
        assertNull(tryFastPath("MATCH (m:Method) RETURN m.name AS name ORDER BY name LIMIT 10001"))
        assertNull(
            MethodQueryExecutor.tryExecute(
                listOf(
                    CypherClause.Match(
                        listOf(CypherPattern(listOf(PatternElement.RelationshipPattern())))
                    ),
                    CypherClause.Return(listOf(ReturnItem(CypherExpr.Literal(1))))
                ),
                sources,
                qualified = false,
                checkCancelled = {},
                workTracker = null
            )
        )
    }

    @Test
    fun `Method filtered count returns zero without materializing matches`() {
        val graph = DefaultGraph.Builder().apply {
            addMethod(method("com.example.Alpha", "alpha", emptyList(), "void"))
        }.build()

        val result = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Missing' RETURN count(m) AS total"
        )

        assertEquals(0L, result.rows.single()["total"])
    }

    @Test
    fun `Method bounded order handles equal null and boolean keys`() {
        val graph = DefaultGraph.Builder().apply {
            addMethod(method("com.example.Alpha", "alpha", emptyList(), "void"))
            addMethod(method("com.example.Beta", "beta", emptyList(), "void"))
        }.build()
        val executor = CypherExecutor(graph)

        val equalNulls = executor.execute(
            "MATCH (m:Method) RETURN null AS rank, m.name AS name ORDER BY rank LIMIT 2"
        )
        val booleans = executor.execute(
            "MATCH (m:Method) RETURN m.name = 'alpha' AS selected, m.name AS name " +
                "ORDER BY selected DESC LIMIT 2"
        )

        assertEquals(setOf("alpha", "beta"), equalNulls.rows.map { it["name"] }.toSet())
        assertEquals(listOf(true, false), booleans.rows.map { it["selected"] })
        assertEquals(listOf("alpha", "beta"), booleans.rows.map { it["name"] })
    }

    @Test
    fun `Method OR count and order stay streaming across 36 graphs`() {
        val graphCount = 36
        val methodsPerGraph = 1_000
        val sources = (0 until graphCount).map { graphIndex ->
            val methods = (0 until methodsPerGraph).map { methodIndex ->
                method("com.example.Generated", "method$methodIndex", emptyList(), "void")
            }
            CypherGraph("graph-$graphIndex", SyntheticMethodIndexGraph(methods))
        }
        val executor = CrossGraphCypherExecutor(sources, CypherExecutionBudget(maxWorkUnits = 1))

        val alternatives = executor.execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Generated' AND " +
                "(m.name = 'method0' OR m.name = 'method999') " +
                "RETURN graphId(m) AS graph, m.name AS name LIMIT 5000"
        )
        val counts = executor.execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Generated' " +
                "RETURN graphId(m) AS graph, count(m) AS total LIMIT 5000"
        )
        val ordered = executor.execute(
            "MATCH (m:Method) WHERE m.class = 'com.example.Generated' " +
                "RETURN graphId(m) AS graph, m.signature AS signature " +
                "ORDER BY graph, signature DESC LIMIT 36"
        )

        assertEquals(graphCount * 2, alternatives.rows.size)
        assertEquals(setOf("method0", "method999"), alternatives.rows.map { it["name"] }.toSet())
        assertEquals(graphCount, counts.rows.size)
        assertTrue(counts.rows.all { it["total"] == methodsPerGraph.toLong() })
        assertEquals((0 until graphCount).map { "graph-$it" }, counts.rows.map { it["graph"] })
        assertEquals(36, ordered.rows.size)
        assertTrue(ordered.rows.all { it["graph"] == "graph-0" })
        assertEquals(
            ordered.rows.map { it["signature"] as String }.sortedDescending(),
            ordered.rows.map { it["signature"] }
        )
    }

    @Test
    fun `Method source preserves general Cypher semantics outside the indexed fast path`() {
        val alpha = method("com.example.Alpha", "alpha", listOf("java.lang.String"), "void")
        val beta = method("com.example.Beta", "beta", listOf("int", "long"), "int")
        val graph = DefaultGraph.Builder().apply {
            addMethod(alpha)
            addMethod(beta)
            addNode(io.johnsonlee.graphite.core.StringConstant(io.johnsonlee.graphite.core.NodeId(1), "beta"))
        }.build()
        val executor = CypherExecutor(graph)

        val alternatives = executor.execute(
            "MATCH (m:Method) WHERE m.name = 'alpha' OR m.name = 'beta' " +
                "RETURN DISTINCT m.name AS name ORDER BY name DESC SKIP 0 LIMIT 2"
        )
        val aggregate = executor.execute("MATCH (m:Method) RETURN count(m) AS total")
        val unwind = executor.execute(
            "MATCH (m:Method) WITH m WHERE m.name = 'beta' " +
                "UNWIND m.parameter_types AS parameter RETURN parameter ORDER BY parameter"
        )
        val joined = executor.execute(
            "MATCH (m:Method), (n:StringConstant) WHERE m.name = n.value RETURN m.signature AS signature"
        )

        assertEquals(listOf("beta", "alpha"), alternatives.rows.map { it["name"] })
        assertEquals(2L, aggregate.rows.single()["total"])
        assertEquals(listOf("int", "long"), unwind.rows.map { it["parameter"] })
        assertEquals(listOf(beta.signature), joined.rows.map { it["signature"] })
        assertTrue(executor.execute("MATCH (m:Method) WHERE m.unknown = 'x' RETURN m").rows.isEmpty())
    }

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String) =
        MethodDescriptor(
            TypeDescriptor(owner),
            name,
            parameters.map(::TypeDescriptor),
            TypeDescriptor(returnType)
        )

    private class SyntheticMethodIndexGraph(
        private val indexedMethods: List<MethodDescriptor>
    ) : Graph by DefaultGraph.Builder().build(), StreamingMethodLookup {
        override fun methodCount(): Long = indexedMethods.size.toLong()

        override fun methods(pattern: MethodPattern): Sequence<MethodDescriptor> =
            indexedMethods.asSequence().filter(pattern::matches)

        override fun methods(
            pattern: MethodPattern,
            scanConsumer: MethodMetadataScanConsumer
        ): Sequence<MethodDescriptor> = indexedMethods.asSequence()
            .onEach { scanConsumer.inspect() }
            .filter(pattern::matches)

        override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
            error("Method metadata queries must not scan graph nodes")
    }
}
