package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.NodePropertyTextCandidates
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("StringLiteralDuplication")
class DeclaredTypeQueryTest {
    private val owner = TypeDescriptor("example.Repository")
    private val list = TypeDescriptor("java.util.List")
    private val method = MethodDescriptor(owner, "load", listOf(list), list)
    private val field = FieldNode(NodeId(1), FieldDescriptor(owner, "items", list), false)

    @Test
    fun `declaration projections preserve erased types and expose structured arguments`() {
        val graph = graph("java.lang.String")
        val executor = CypherExecutor(graph)
        for (label in listOf("Field", "Parameter", "Return")) {
            val row = executor.execute(
                "MATCH (n:$label) WHERE n.generic_type CONTAINS 'String' " +
                    "RETURN n.generic_type AS declaration, n.type_info AS info, properties(n) AS props, keys(n) AS keys, n"
            ).rows.single()
            assertEquals("java.util.List<java.lang.String>", row["declaration"])
            val info = row["info"] as Map<*, *>
            assertEquals("class", info["kind"])
            assertEquals("java.util.List", info["name"])
            assertEquals("java.lang.String", ((info["arguments"] as List<*>).single() as Map<*, *>)["name"])
            assertEquals(row["declaration"], (row["props"] as Map<*, *>)["generic_type"])
            assertEquals(info, (row["n"] as Map<*, *>)["type_info"])
            assertTrue("generic_type" in row["keys"] as List<*>)
            assertTrue("type_info" in row["keys"] as List<*>)
        }
        assertEquals("java.util.List", executor.execute("MATCH (n:Field) RETURN n.type AS t").rows.single()["t"])
    }

    @Test
    fun `Method projections use full descriptor and support residual filtering and dynamic access`() {
        val graph = graph("java.lang.String")
        val row = CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE m.generic_return_type CONTAINS 'String' " +
                "RETURN m.generic_return_type AS result, m.generic_parameter_types AS params, " +
                "m.return_type_info AS info, m.parameter_type_info AS paramInfo, " +
                "m.type_parameters AS variables, properties(m) AS props, keys(m) AS keys, m"
        ).rows.single()
        assertEquals("java.util.List<java.lang.String>", row["result"])
        assertEquals(listOf(row["result"]), row["params"])
        assertEquals(listOf(row["info"]), row["paramInfo"])
        val variable = (row["variables"] as List<*>).single() as Map<*, *>
        assertEquals("T", variable["name"])
        assertEquals("example.Repository#load(Ljava/util/List;)Ljava/util/List;", variable["scope"])
        assertEquals(listOf("java.lang.Object"), variable["bounds"])
        assertEquals("java.lang.Object", ((variable["bound_info"] as List<*>).single() as Map<*, *>)["name"])
        assertEquals(row["result"], (row["props"] as Map<*, *>)["generic_return_type"])
        assertEquals(row["info"], (row["m"] as Map<*, *>)["return_type_info"])
        assertTrue("type_parameters" in row["keys"] as List<*>)
        assertEquals(listOf("java.util.List<java.lang.String>"), CypherExecutor(graph).execute(
            "MATCH (m:Method) WHERE any(k IN keys(m) WHERE toString(m[k]) CONTAINS 'String') " +
                "RETURN m.generic_return_type AS t"
        ).rows.map { it["t"] })
    }

    @Test
    fun `same erased members resolve declarations in their owning graphs`() {
        val executor = CrossGraphCypherExecutor(listOf(
            CypherGraph("strings", graph("java.lang.String")),
            CypherGraph("integers", graph("java.lang.Integer"))
        ))
        for ((label, property) in listOf("Field" to "generic_type", "Method" to "generic_return_type")) {
            val rows = executor.execute(
                "MATCH (n:$label) RETURN n.graphId AS graph, n.$property AS t, properties(n) AS props ORDER BY graph"
            ).rows
            assertEquals(listOf("integers", "strings"), rows.map { it["graph"] })
            assertEquals(listOf("java.util.List<java.lang.Integer>", "java.util.List<java.lang.String>"), rows.map { it["t"] })
            assertEquals(rows.map { it["t"] }, rows.map { (it["props"] as Map<*, *>)[property] })
        }
    }

    @Test
    fun `legacy metadata is null while present non generic declarations remain visible`() {
        val legacy = DefaultGraph.Builder().apply { addNode(field); addMethod(method) }.build()
        val executor = CypherExecutor(legacy)
        assertNull(executor.execute("MATCH (n:Field) RETURN n.generic_type AS t").rows.single()["t"])
        val row = executor.execute("MATCH (m:Method) RETURN properties(m) AS p").rows.single()["p"] as Map<*, *>
        for (property in DeclaredTypeProperties.methodPropertyNames) {
            assertNull(row[property])
            assertFalse(property in row)
        }
        val raw = graph("java.lang.String", raw = true)
        assertEquals("java.util.List", CypherExecutor(raw).execute(
            "MATCH (n:Field) RETURN n.generic_type AS t"
        ).rows.single()["t"])
    }

    @Test
    fun `bridge method with same erased name and parameters does not share declaration`() {
        val bridge = method.copy(returnType = TypeDescriptor("java.lang.Object"))
        val table = graph("java.lang.String").declaredTypes()
        assertEquals(
            "java.util.List<java.lang.String>",
            DeclaredTypeProperties.methodProperty(method, "generic_return_type", graph("java.lang.String"))
        )
        val bridgeGraph = DefaultGraph.Builder().apply { setDeclaredTypes(table); addMethod(bridge) }.build()
        assertNull(CypherExecutor(bridgeGraph).execute("MATCH (m:Method) RETURN m.generic_return_type AS t").rows.single()["t"])
    }

    @Test
    fun `metadata text remains searchable when erased node index has no candidates`() {
        val delegate = graph("java.lang.String")
        val indexed = object : Graph by delegate, NodePropertyTextCandidates {
            override fun <T : Node> propertyTextCandidates(
                type: Class<T>, fragment: String, workConsumer: GraphWorkConsumer?
            ): Sequence<T>? = emptySequence()
        }
        val rows = CypherExecutor(indexed).execute(
            "MATCH (n:Field) WHERE any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'java.lang.String') " +
                "RETURN n.generic_type AS t"
        ).rows
        assertEquals(listOf(mapOf("t" to "java.util.List<java.lang.String>")), rows)
    }

    @Test
    fun `missing declarations preserve negative dynamic search and absent property keys`() {
        val externalMethod = method.copy(name = "external")
        val externalField = field.copy(id = NodeId(value = 91), descriptor = field.descriptor.copy(name = "external"))
        val partial = DefaultGraph.Builder().apply {
            addNode(field)
            addNode(externalField)
            addMethod(method)
            addMethod(externalMethod)
            setDeclaredTypes(graph("java.lang.String").declaredTypes())
        }.build()
        val legacy = DefaultGraph.Builder().apply { addNode(field); addMethod(method) }.build()
        for (source in listOf(legacy, partial, legacy)) {
            val executor = CypherExecutor(source)
            for (label in listOf("Field", "Method")) {
                val rows = executor.execute(
                    "MATCH (n:$label) WHERE NOT any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'zzzzAbsent') " +
                        "RETURN n.name AS name, keys(n) AS keys, properties(n) AS props, n ORDER BY name"
                ).rows
                assertEquals(if (source === legacy) 1 else 2, rows.size)
                val names = if (label == "Field") DeclaredTypeProperties.nodePropertyNames
                    else DeclaredTypeProperties.methodPropertyNames
                for (row in rows) {
                    val hasDeclaration = source !== legacy && row["name"] != "external"
                    assertDeclarationKeys(row, names, hasDeclaration)
                }
            }
            assertNull(executor.execute(
                "MATCH (n:Field) WHERE n.name = '${if (source === legacy) "items" else "external"}' " +
                    "RETURN n.generic_type AS t, n.type_info AS info"
            ).rows.single()["t"])
        }
    }

    @Test
    fun `qualified keys distinguish legacy and declared members without caching the wrong schema`() {
        val legacy = DefaultGraph.Builder().apply { addNode(field); addMethod(method) }.build()
        val executor = CrossGraphCypherExecutor(listOf(
            CypherGraph("legacy", legacy), CypherGraph("declared", graph("java.lang.String"))
        ))
        for ((label, property) in listOf("Field" to "generic_type", "Method" to "generic_return_type")) {
            val rows = executor.execute(
                "MATCH (n:$label) WHERE NOT any(k IN keys(n) WHERE toString(n[k]) CONTAINS 'zzzzAbsent') " +
                    "RETURN n.graphId AS graph, keys(n) AS keys, properties(n) AS props ORDER BY graph"
            ).rows
            assertEquals(listOf("declared", "legacy"), rows.map { it["graph"] })
            assertTrue(property in rows.first()["keys"] as List<*>)
            assertFalse(property in rows.last()["keys"] as List<*>)
            assertFalse(property in rows.last()["props"] as Map<*, *>)
        }
    }

    @Test
    fun `declaration property names do not hide annotation members with the same names`() {
        val annotation = AnnotationNode(NodeId(value = 93), "example.Annotation", owner.className, "load",
            mapOf("generic_type" to "user supplied", "type_info" to "annotation value"))
        val graph = DefaultGraph.Builder().apply {
            addNode(annotation)
            setDeclaredTypes(graph("java.lang.String").declaredTypes())
        }.build()
        val rows = CypherExecutor(graph).execute(
            "MATCH (n:Annotation) RETURN n.generic_type AS declared, n.type_info AS info, properties(n) AS props, n"
        ).rows
        val row = rows.single()
        assertEquals("user supplied", row["declared"])
        assertEquals("annotation value", row["info"])
        assertEquals("user supplied", (row["props"] as Map<*, *>)["generic_type"])
        assertEquals("annotation value", (row["n"] as Map<*, *>)["type_info"])
    }

    @Test
    fun `unbound parameter ordinals do not fabricate declaration metadata`() {
        val parameter = ParameterNode(NodeId(value = 94), 1, list, method)
        val graph = DefaultGraph.Builder().apply {
            addNode(parameter)
            setDeclaredTypes(graph("java.lang.String").declaredTypes())
        }.build()
        val row = CypherExecutor(graph).execute(
            "MATCH (n:Parameter) RETURN n.generic_type AS declared, n.type_info AS info, keys(n) AS keys, properties(n) AS props"
        ).rows.single()
        assertNull(row["declared"])
        assertNull(row["info"])
        assertFalse("generic_type" in row["keys"] as List<*>)
        assertFalse("type_info" in row["props"] as Map<*, *>)
    }

    @Test
    fun `keys exposes sparse metadata maps and retains null valued ordinary map keys`() {
        val executor = CypherExecutor(graph("java.lang.String"))
        val row = executor.execute(
            "MATCH (n:Field) RETURN keys(n.type_info) AS keys, " +
                "keys(n.type_info.arguments[0]) AS argumentKeys, n.type_info.scope IS NULL AS noScope"
        ).rows.single()
        assertEquals(listOf("kind", "name", "arguments"), row["keys"])
        assertEquals(listOf("kind", "name", "arguments"), row["argumentKeys"])
        assertEquals(true, row["noScope"])
        val literal = executor.execute("RETURN keys({name: 'example', absent: null}) AS keys, keys(null) AS missing")
            .rows.single()
        assertEquals(setOf("name", "absent"), (literal["keys"] as List<*>).toSet())
        assertNull(literal["missing"])
    }

    private fun assertDeclarationKeys(row: Map<String, Any?>, names: List<String>, hasDeclaration: Boolean) {
        for (name in names) {
            assertEquals(hasDeclaration, name in row["keys"] as List<*>)
            assertEquals(hasDeclaration, name in row["props"] as Map<*, *>)
            assertEquals(hasDeclaration, name in row["n"] as Map<*, *>)
        }
    }

    private fun graph(argument: String, raw: Boolean = false) = DefaultGraph.Builder().apply {
        addNode(field)
        addNode(ParameterNode(NodeId(2), 0, list, method))
        addNode(ReturnNode(NodeId(value = 3), method))
        addMethod(method)
        setDeclaredTypes(DeclaredTypeTable(
            types = listOf(
                DeclaredType("class", argument),
                DeclaredType("class", "java.util.List", arguments = if (raw) emptyList() else listOf(0)),
                DeclaredType("class", "java.lang.Object")
            ),
            fields = mapOf(MemberTypeKey(owner.className, "items", "Ljava/util/List;") to 1),
            methods = mapOf(MemberTypeKey(owner.className, method.name, method.descriptor) to MethodTypes(
                listOf(1), 1, listOf(TypeParameter("T", "${owner.className}#${method.name}${method.descriptor}", listOf(2)))
            )),
            classes = emptyMap()
        ))
    }.build()
}
