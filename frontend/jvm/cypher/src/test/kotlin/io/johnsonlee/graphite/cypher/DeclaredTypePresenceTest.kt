package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.ImmutableDeclaredTypeStorage
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import io.johnsonlee.graphite.graph.TypeParameter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Suppress("StringLiteralDuplication")
class DeclaredTypePresenceTest {
    private val owner = TypeDescriptor("example.Box")
    private val type = TypeDescriptor("java.util.List")
    private val method = MethodDescriptor(owner, "get", listOf(type), type)
    private val field = FieldNode(NodeId(1), FieldDescriptor(owner, "items", type), false)
    private val parameter = ParameterNode(NodeId(2), 0, type, method)
    private val result = ReturnNode(NodeId(3), method)
    private val key = MemberTypeKey(owner.className, method.name, method.descriptor)

    @Test
    fun `bound node and method presence agrees with complete projection`() {
        val graph = graph(table())
        for (node in listOf(field, parameter, result)) {
            for (property in DeclaredTypeProperties.nodePropertyNames) assertParity(node, property, graph, true)
        }
        for (property in DeclaredTypeProperties.methodPropertyNames) {
            assertParity(MethodValue(null, method, graph), property, graph, true)
        }
    }

    @Test
    fun `validated presence does not expand declared type expressions`() {
        val original = table()
        var reads = 0
        val types = object : AbstractList<DeclaredType>(), ImmutableDeclaredTypeStorage {
            override val immutableFields = original.fields
            override val immutableMethods = original.methods
            override val immutableClasses = original.classes
            override val size: Int get() = original.types.size
            override fun get(index: Int): DeclaredType {
                reads++
                return original.types[index]
            }
        }
        val table = original.copy(types = types)
        table.validate()
        reads = 0
        val graph = graph(table)
        val evaluator = ExpressionEvaluator({ null }, null, graph)
        for (receiver in listOf(field, parameter, result, MethodValue(null, method, graph))) {
            val properties = if (receiver is MethodValue) {
                DeclaredTypeProperties.methodPropertyNames
            } else {
                DeclaredTypeProperties.nodePropertyNames
            }
            for (property in properties) {
                val access = CypherExpr.Property(CypherExpr.Literal(receiver), property)
                assertEquals(true, evaluator.evaluate(CypherExpr.IsNotNull(access), emptyMap()))
            }
        }
        assertEquals(0, reads)
        evaluator.evaluate(CypherExpr.Property(CypherExpr.Literal(field), "type_info"), emptyMap())
        assertTrue(reads > 0)
    }

    @Test
    fun `cross graph grouped presence retains every graph and absent group`() {
        val graphs = listOf(CypherGraph("present", graph(table())), CypherGraph("absent", graph(DeclaredTypeTable.EMPTY)))
        for ((label, property) in listOf("Field" to "type_info", "Method" to "return_type_info")) {
            val rows = CrossGraphCypherExecutor(graphs).execute(
                "MATCH (n:$label) RETURN graphId(n) AS graph, n.$property IS NOT NULL AS present, count(*) AS total"
            ).rows
            assertEquals(2, rows.size)
            val byGraph = rows.associateBy { it["graph"] }
            assertEquals(true, byGraph.getValue("present")["present"])
            assertEquals(false, byGraph.getValue("absent")["present"])
            assertEquals(1L, byGraph.getValue("present")["total"])
            assertEquals(1L, byGraph.getValue("absent")["total"])
        }
    }

    @Test
    fun `legacy missing binding and out of range parameter stay absent`() {
        val legacy = graph(DeclaredTypeTable.EMPTY)
        val missing = graph(table().copy(fields = emptyMap(), methods = emptyMap()))
        for (graph in listOf(legacy, missing)) {
            assertParity(field, "generic_type", graph, false)
            assertParity(MethodValue(null, method, graph), "type_parameters", graph, false)
        }
        assertParity(parameter.copy(index = 7), "type_info", graph(table()), false)
        assertParity(parameter.copy(index = -1), "generic_type", graph(table()), false)
    }

    @Test
    fun `orphan declarations do not require materialized nodes or methods`() {
        val graph = DefaultGraph.Builder().apply { setDeclaredTypes(table()) }.build()
        assertParity(field, "type_info", graph, true)
        assertParity(MethodValue(null, method, graph), "return_type_info", graph, true)
    }

    @Test
    fun `qualified receivers always use their own graph`() {
        val present = graph(table())
        val absent = graph(DeclaredTypeTable.EMPTY)
        assertParity(QualifiedNode("present", present, field), "type_info", absent, true)
        assertParity(QualifiedNode("absent", absent, field), "type_info", present, false)
        assertParity(MethodValue("present", method, present), "generic_return_type", absent, true)
        assertParity(MethodValue("absent", method, absent), "generic_return_type", present, false)
    }

    @Test
    fun `ordinary properties maps annotations and null retain normal resolution`() {
        val graph = graph(table())
        val annotation = AnnotationNode(NodeId(4), "example.Annotation", owner.className, "get",
            mapOf("generic_type" to "annotation value"))
        assertParity(annotation, "generic_type", graph, true)
        assertParity(field, "name", graph, true)
        assertParity(field, "unknown", graph, false)
        assertParity(MethodValue(null, method, graph), "name", graph, true)
        assertParity(MethodValue(null, method, graph), "unknown", graph, false)
        assertParity(mapOf("type_info" to emptyList<String>()), "type_info", graph, true)
        assertParity(null, "type_info", graph, false)
    }

    @Test
    fun `direct property receiver is evaluated once including fallback`() {
        val graph = graph(table())
        for (property in listOf("generic_type", "name", "unknown")) {
            for (isNull in listOf(false, true)) {
                var calls = 0
                val evaluator = ExpressionEvaluator({ calls++; field }, null, graph)
                val access = CypherExpr.Property(CypherExpr.Parameter("receiver"), property)
                evaluator.evaluate(if (isNull) CypherExpr.IsNull(access) else CypherExpr.IsNotNull(access), emptyMap())
                assertEquals(1, calls)
            }
        }
    }

    @Test
    fun `empty method projections remain present without validating unrelated invalid types`() {
        val invalid = table().copy(types = listOf(DeclaredType("unknown")), methods = mapOf(
            key to MethodTypes(emptyList(), 0, listOf(TypeParameter("T", "method", emptyList())))
        ))
        val graph = graph(invalid)
        val value = MethodValue(null, method, graph)
        for (property in listOf("generic_parameter_types", "parameter_type_info", "type_parameters")) {
            assertParity(value, property, graph, true)
        }
        val emptyFormals = graph(invalid.copy(methods = mapOf(key to MethodTypes(emptyList(), 0))))
        assertParity(MethodValue(null, method, emptyFormals), "type_parameters", emptyFormals, true)
    }

    @Test
    fun `nonempty projections preserve whole table validation errors`() {
        val invalid = table().copy(types = table().types + DeclaredType("unknown"))
        val graph = graph(invalid)
        for (node in listOf(field, parameter, result)) {
            for (property in DeclaredTypeProperties.nodePropertyNames) assertErrorParity(node, property, graph)
        }
        for (property in DeclaredTypeProperties.methodPropertyNames) {
            assertErrorParity(MethodValue(null, method, graph), property, graph)
        }
    }

    @Test
    fun `invalid references fail but absent bindings do not eagerly validate`() {
        val invalid = table().copy(methods = mapOf(key to MethodTypes(listOf(99), -1)))
        val graph = graph(invalid)
        assertErrorParity(parameter, "type_info", graph)
        assertErrorParity(result, "generic_type", graph)
        assertParity(parameter.copy(index = 9), "type_info", graph, false)
        assertParity(field.copy(descriptor = FieldDescriptor(owner, "missing", type)), "type_info", graph, false)
        val missing = method.copy(name = "missing")
        assertParity(MethodValue(null, missing, graph), "return_type_info", graph, false)
    }

    @Test
    fun `ordinary table mutations after validation preserve projection exceptions`() {
        val fields = table().fields.toMutableMap()
        val direct = table().copy(fields = fields)
        direct.validate()
        fields[fields.keys.single()] = 99
        assertErrorParity(field, "type_info", graph(direct))
        assertErrorParity(field, "generic_type", graph(direct))

        val arguments = mutableListOf(0)
        val nested = table().copy(types = listOf(
            DeclaredType("class", "java.lang.String"), DeclaredType("class", type.className, arguments = arguments)
        ))
        nested.validate()
        arguments[0] = 99
        assertErrorParity(field, "type_info", graph(nested))
        assertErrorParity(field, "generic_type", graph(nested))

        val parameters = mutableListOf(1)
        val methodTable = table().copy(methods = mapOf(key to MethodTypes(parameters, 1)))
        methodTable.validate()
        parameters[0] = 99
        val graph = graph(methodTable)
        assertErrorParity(MethodValue(null, method, graph), "parameter_type_info", graph)
        assertErrorParity(MethodValue(null, method, graph), "generic_parameter_types", graph)
    }

    @Test
    fun `receiver function cancellation checkpoints are unchanged`() {
        var checks = 0
        val evaluator = ExpressionEvaluator { checks++; throw CypherQueryCancelledException("receiver") }
        val receiver = CypherExpr.FunctionCall("head", listOf(CypherExpr.ListLiteral(listOf(CypherExpr.Literal(field)))))
        val access = CypherExpr.Property(receiver, "type_info")
        val failure = assertFailsWith<CypherQueryCancelledException> {
            evaluator.evaluate(CypherExpr.IsNotNull(access), emptyMap())
        }
        assertEquals("receiver", failure.message)
        assertEquals(1, checks)
    }

    @Test
    fun `grouped presence consumes the same scan work as materialized property presence`() {
        val graph = graph(table())
        val expected = CypherResult(listOf("present", "total"), listOf(mapOf("present" to true, "total" to 1L)))
        // Node scans charge each matched node; method metadata scans only poll cancellation.
        val cases = listOf(Triple("Field", "type_info", 1L), Triple("Method", "parameter_type_info", 0L))
        for ((label, property, expectedWork) in cases) {
            val direct = "MATCH (n:$label) RETURN n.$property IS NOT NULL AS present, count(*) AS total"
            val materialized = "MATCH (n:$label) RETURN properties(n).$property IS NOT NULL AS present, count(*) AS total"
            val left = CypherExecutionContext(CypherExecutionBudget(Long.MAX_VALUE))
            val right = CypherExecutionContext(CypherExecutionBudget(Long.MAX_VALUE))
            val projected = CypherExecutor(graph, right).execute(materialized)
            val presence = CypherExecutor(graph, left).execute(direct)
            assertEquals(expected, projected)
            assertEquals(expected, presence)
            assertEquals(projected, presence)
            assertEquals(expectedWork, right.diagnostics.workUnitsConsumed, label)
            assertEquals(right.diagnostics.workUnitsConsumed, left.diagnostics.workUnitsConsumed, label)
        }
    }

    @Test
    fun `presence query preserves cancellation and exhausted scan budget`() {
        val graph = graph(table())
        val query = "MATCH (n) RETURN n.type_info IS NOT NULL AS present, count(*) AS total"
        assertFailsWith<CypherBudgetExceededException> {
            CypherExecutor(graph, CypherExecutionBudget(1)).execute(query)
        }
        val signal = CypherCancellationSignal().apply { cancel() }
        assertFailsWith<CypherQueryCancelledException> {
            CypherExecutor(graph, CypherExecutionContext(CypherExecutionBudget(100), signal)).execute(query)
        }
    }

    private fun assertParity(receiver: Any?, property: String, graph: Graph, present: Boolean) {
        val evaluator = ExpressionEvaluator({ null }, null, graph)
        val access = CypherExpr.Property(CypherExpr.Literal(receiver), property)
        assertEquals(present, evaluator.evaluate(access, emptyMap()) != null, property)
        assertEquals(present, evaluator.evaluate(CypherExpr.IsNotNull(access), emptyMap()), property)
        assertEquals(!present, evaluator.evaluate(CypherExpr.IsNull(access), emptyMap()), property)
    }

    private fun assertErrorParity(receiver: Any?, property: String, graph: Graph) {
        val evaluator = ExpressionEvaluator({ null }, null, graph)
        val access = CypherExpr.Property(CypherExpr.Literal(receiver), property)
        val full = assertFails { evaluator.evaluate(access, emptyMap()) }
        for (expression in listOf(CypherExpr.IsNull(access), CypherExpr.IsNotNull(access))) {
            val presence = assertFails { evaluator.evaluate(expression, emptyMap()) }
            assertEquals(full::class, presence::class)
            assertEquals(full.message, presence.message)
        }
    }

    private fun table() = DeclaredTypeTable(
        types = listOf(DeclaredType("class", "java.lang.String"),
            DeclaredType("class", type.className, arguments = listOf(0))),
        fields = mapOf(MemberTypeKey(owner.className, "items", "Ljava/util/List;") to 1),
        methods = mapOf(key to MethodTypes(listOf(1), 1, listOf(TypeParameter("T", "method", listOf(0))))),
        classes = emptyMap()
    )

    private fun graph(table: DeclaredTypeTable) = DefaultGraph.Builder().apply {
        addNode(field)
        addNode(parameter)
        addNode(result)
        addMethod(method)
        setDeclaredTypes(table)
    }.build()
}
