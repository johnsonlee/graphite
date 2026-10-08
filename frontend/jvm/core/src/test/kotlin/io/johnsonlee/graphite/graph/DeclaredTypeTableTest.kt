package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.NodeId
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeclaredTypeTableTest {
    private val field = MemberTypeKey("example.Owner", "items", "Ljava/util/List;")
    private val method = MemberTypeKey("example.Owner", "copy", "(Ljava/util/List;)Ljava/util/List;")
    private val table = DeclaredTypeTable(
        types = listOf(
            DeclaredType("class", "java.lang.String"),
            DeclaredType("class", "java.util.List", arguments = listOf(0))
        ),
        fields = mapOf(field to 1),
        methods = mapOf(method to MethodTypes(listOf(1), 1)),
        classes = emptyMap()
    )

    @Test
    fun `default and mapped builders retain shared field parameter and return references`() {
        for (mapped in listOf(false, true)) {
            val directory = Files.createTempDirectory("declared-type-builder")
            try {
                val builder: FullGraphBuilder = if (mapped) MmapGraphBuilder(directory) else DefaultGraph.Builder()
                builder.addNode(IntConstant(NodeId(0), 42))
                builder.setDeclaredTypes(table)
                val graph = builder.build()
                try {
                    val actual = graph.declaredTypes()
                    assertEquals(table, actual)
                    assertEquals(actual.fields.getValue(field), actual.methods.getValue(method).returnType)
                    assertEquals(listOf(actual.fields.getValue(field)), actual.methods.getValue(method).parameterTypes)
                    assertEquals("java.util.List<java.lang.String>", actual.render(actual.fields.getValue(field)))
                } finally {
                    (graph as? Closeable)?.close()
                }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `owner rendering preserves dollar signs in the inner simple name`() {
        val nested = table.copy(types = table.types + listOf(
            DeclaredType("class", "example.Outer", arguments = listOf(0)),
            DeclaredType("class", "example.Outer\$Inner\$Name", owner = 2, arguments = listOf(1))
        ))
        assertEquals("example.Outer<java.lang.String>.Inner\$Name<java.util.List<java.lang.String>>", nested.render(3))
        assertEquals("example.Outer\$Inner\$Name", nested.info(3)["name"])
        assertEquals(nested.info(2), nested.info(3)["owner"])
        assertEquals(listOf(nested.info(1)), nested.info(3)["arguments"])
    }

    @Test
    fun `shared expression DAG expansion is rejected before rendering or info projection`() {
        val expressions = mutableListOf(DeclaredType("class", "example.Leaf"))
        repeat(17) {
            val child = expressions.lastIndex
            expressions.add(DeclaredType("class", "example.Pair", arguments = listOf(child, child)))
        }
        val oversized = DeclaredTypeTable(expressions, emptyMap(), emptyMap(), emptyMap())
        assertFailsWith<IllegalArgumentException> { oversized.validate() }
        assertFailsWith<IllegalArgumentException> { oversized.render(expressions.lastIndex) }
        assertFailsWith<IllegalArgumentException> { oversized.info(expressions.lastIndex) }
    }

    @Test
    fun `expanded byte budget counts UTF8 bytes and repeated references`() {
        val oversized = DeclaredTypeTable(
            listOf(
                DeclaredType("class", "界".repeat(200_000)),
                DeclaredType("class", "Pair", arguments = listOf(0, 0))
            ), emptyMap(), emptyMap(), emptyMap()
        )
        assertFailsWith<IllegalArgumentException> { oversized.info(1) }
    }

    @Test
    fun `cycles bad references and excess depth are rejected while supported depth renders`() {
        val cycle = DeclaredTypeTable(listOf(DeclaredType("array", component = 0)), emptyMap(), emptyMap(), emptyMap())
        assertFailsWith<IllegalArgumentException> { cycle.render(0) }
        assertFailsWith<IllegalArgumentException> { table.copy(fields = mapOf(field to 99)).validate() }
        val expressions = mutableListOf(DeclaredType("primitive", "int"))
        repeat(DeclaredTypeTable.MAX_DEPTH - 1) { expressions.add(DeclaredType("array", component = expressions.lastIndex)) }
        val valid = DeclaredTypeTable(expressions.toList(), emptyMap(), emptyMap(), emptyMap())
        assertEquals("int" + "[]".repeat(DeclaredTypeTable.MAX_DEPTH - 1), valid.render(expressions.lastIndex))
        expressions.add(DeclaredType("array", component = expressions.lastIndex))
        assertFailsWith<IllegalArgumentException> {
            DeclaredTypeTable(expressions, emptyMap(), emptyMap(), emptyMap()).validate()
        }
    }


    @Test
    fun `recursive parameter bounds and all wildcard projections retain their exact structure`() {
        val classScope = "class:example.Box"
        val methodScope = "method:example.Box#copy(Ljava/lang/Comparable;)Ljava/lang/Comparable;"
        val genericMethod = MemberTypeKey("example.Box", "copy", "(Ljava/lang/Comparable;)Ljava/lang/Comparable;")
        val generic = DeclaredTypeTable(
            types = listOf(
                DeclaredType("class", "java.lang.Object"),
                DeclaredType("variable", "T", scope = classScope),
                DeclaredType("class", "java.lang.Comparable", arguments = listOf(1)),
                DeclaredType("wildcard", component = 1, variance = "extends"),
                DeclaredType("wildcard", component = 1, variance = "super"),
                DeclaredType("wildcard", variance = "unbounded"),
                DeclaredType("class", "java.util.Map", arguments = listOf(3, 4)),
                DeclaredType("array", component = 6),
                DeclaredType("class", "java.util.List", arguments = listOf(5)),
                DeclaredType("variable", "U", scope = methodScope)
            ),
            fields = mapOf(MemberTypeKey("example.Box", "values", "[Ljava/util/Map;") to 7),
            methods = mapOf(genericMethod to MethodTypes(listOf(9), 9, listOf(TypeParameter("U", methodScope, listOf(1))))),
            classes = mapOf("example.Box" to ClassTypes(listOf(TypeParameter("T", classScope, listOf(2))), 0, listOf(2)))
        )
        assertEquals("java.util.Map<? extends T, ? super T>[]", generic.render(7))
        assertEquals("java.util.List<?>", generic.render(8))
        assertEquals("U", generic.render(generic.methods.getValue(genericMethod).returnType))
        assertEquals(listOf("java.lang.Comparable<T>"),
            generic.classes.getValue("example.Box").typeParameters.single().bounds.map(generic::render))
        val variableInfo = mapOf<String, Any?>(
            "kind" to "variable", "name" to "T", "scope" to classScope,
            "arguments" to emptyList<Any?>()
        )
        assertEquals(variableInfo, generic.info(1))
        assertEquals(variableInfo, generic.info(3)["component"])
        assertEquals("extends", generic.info(3)["variance"])
        assertEquals("super", generic.info(4)["variance"])
        assertEquals("unbounded", generic.info(5)["variance"])
        assertEquals(null, generic.info(5)["component"])
        assertFalse("component" in generic.info(5))
        assertEquals(setOf("kind", "variance", "arguments"), generic.info(5).keys)
        assertEquals(setOf("kind", "component", "arguments"), generic.info(7).keys)
        assertEquals(generic.info(6), generic.info(7)["component"])
        assertEquals(listOf(generic.info(3), generic.info(4)), generic.info(6)["arguments"])
        assertEquals(methodScope, generic.info(9)["scope"])
    }

    @Test
    fun `invalid variant shapes and bounds are rejected before projection`() {
        val invalid = listOf(
            DeclaredType("unsupported", "Thing"),
            DeclaredType("class"),
            DeclaredType("primitive", "java.lang.String"),
            DeclaredType("array"),
            DeclaredType("variable", "T"),
            DeclaredType("wildcard", variance = "covariant"),
            DeclaredType("wildcard", variance = "extends"),
            DeclaredType("wildcard", variance = "unbounded", component = 0)
        )
        invalid.forEach { expression ->
            assertFailsWith<IllegalArgumentException>(expression.toString()) {
                DeclaredTypeTable(listOf(expression), emptyMap(), emptyMap(), emptyMap()).info(0)
            }
        }
        val badBounds = listOf(TypeParameter("T", "class:example.Box", listOf(99)))
        assertFailsWith<IllegalArgumentException> {
            table.copy(classes = mapOf("example.Box" to ClassTypes(badBounds, null, emptyList()))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            table.copy(methods = mapOf(method to MethodTypes(listOf(0), 0, badBounds))).validate()
        }
    }

}
