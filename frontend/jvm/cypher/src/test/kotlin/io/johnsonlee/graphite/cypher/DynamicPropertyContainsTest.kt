package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.FloatConstant
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.LongConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.NullConstant
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class DynamicPropertyContainsTest {
    private val method = MethodDescriptor(TypeDescriptor("example.Service"), "process", emptyList(), TypeDescriptor("void"))
    private val graph = DefaultGraph.Builder().build()

    @Test
    fun `compiled predicate matches general three valued evaluation for every node type`() {
        val evaluator = ExpressionEvaluator()
        val targets = nodes().flatMap { listOf(it, QualifiedNode("source", graph, it)) } +
            listOf(MethodValue(null, method), MethodValue("source", method))
        for (target in targets) {
            for (term in listOf("needle", "105873", "source", "", "not-present", null, 42)) {
                val expression = predicate(CypherExpr.Literal(term))
                val bindings = mapOf("n" to target)
                assertNotNull(DynamicPropertyContainsPlan.compile(expression))
                assertEquals(
                    evaluator.evaluate(general(expression), bindings),
                    evaluator.evaluate(expression, bindings),
                    "${target.javaClass.simpleName}: $term"
                )
            }
        }
        assertEquals(true, evaluator.evaluate(predicate(CypherExpr.Literal("needle")), mapOf("n" to StringConstant(NodeId(1), "needle"))))
        assertEquals(false, evaluator.evaluate(predicate(CypherExpr.Literal("absent")), mapOf("n" to StringConstant(NodeId(1), "needle"))))
        assertNull(evaluator.evaluate(predicate(CypherExpr.Literal("absent")), mapOf("n" to NullConstant(NodeId(1)))))
    }

    @Test
    fun `cached keys exactly preserve property maps including dynamic annotation overrides`() {
        val targets = nodes().flatMap { listOf(it, QualifiedNode("source", graph, it)) } +
            listOf(MethodValue(null, method), MethodValue("source", method))
        for (target in targets) {
            val properties = CypherFunctions.call("properties", listOf(target)) as Map<*, *>
            assertEquals(properties.keys.toList(), CypherFunctions.call("keys", listOf(target)), target.toString())
        }
        val first = StringConstant(NodeId(1), "first")
        val second = StringConstant(NodeId(2), "second")
        assertSame(NodePropertyAccessor.getPropertyNames(first), NodePropertyAccessor.getPropertyNames(second))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (CypherFunctions.call("keys", listOf(first)) as MutableList<String>)[0] = "corrupt"
        }
        assertEquals(listOf("id", "value"), CypherFunctions.call("keys", listOf(second)))
        assertNull(CypherFunctions.call("keys", listOf(mapOf("name" to "value"))))
    }

    @Test
    fun `compiled path reads the node binding once and resolves current parameter values`() {
        var nodeReads = 0
        var term: Any? = "needle"
        val bindings = object : Map<String, Any?> by mapOf("n" to StringConstant(NodeId(1), "needle")) {
            override fun get(key: String): Any? {
                if (key == "n") nodeReads++
                return if (key == "n") StringConstant(NodeId(1), "needle") else null
            }
        }
        val evaluator = ExpressionEvaluator(parameterResolver = { term }, checkCancelled = null)
        val expression = predicate(CypherExpr.Parameter("term"))

        assertEquals(true, evaluator.evaluate(expression, bindings))
        assertEquals(1, nodeReads)
        term = "absent"
        assertEquals(false, evaluator.evaluate(expression, bindings))
        assertEquals(2, nodeReads)
        term = null
        assertNull(evaluator.evaluate(expression, bindings))
        assertEquals(3, nodeReads)
    }

    @Test
    fun `unsupported aliases shapes and targets retain the general evaluator`() {
        val expression = predicate(CypherExpr.Literal("needle"))
        val keys = expression.listExpr as CypherExpr.FunctionCall
        val contains = expression.predicate as CypherExpr.StringOp
        val conversion = contains.left as CypherExpr.FunctionCall
        val unsupported = listOf(
            expression.copy(variable = "n"),
            expression.copy(name = "all"),
            expression.copy(listExpr = keys.copy(distinct = true)),
            expression.copy(listExpr = keys.copy(args = emptyList())),
            expression.copy(predicate = contains.copy(right = CypherExpr.Variable("term"))),
            expression.copy(predicate = contains.copy(left = conversion.copy(distinct = true))),
            expression.copy(predicate = contains.copy(left = conversion.copy(args = emptyList()))),
            expression.copy(predicate = contains.copy(left = conversion.copy(args = listOf(
                CypherExpr.Subscript(CypherExpr.Variable("other"), CypherExpr.Variable("k"))
            ))))
        )
        unsupported.forEach { assertNull(DynamicPropertyContainsPlan.compile(it), it.toString()) }

        val evaluator = ExpressionEvaluator()
        for (target in listOf(null, "needle", listOf("needle"), mapOf("value" to "needle"))) {
            assertEquals(
                evaluator.evaluate(general(expression), mapOf("n" to target)),
                evaluator.evaluate(expression, mapOf("n" to target))
            )
        }
        val shadowed = predicate(CypherExpr.Parameter("k"))
        val bindings = mapOf("n" to StringConstant(NodeId(1), "value"), "k" to "absent")
        assertEquals(true, evaluator.evaluate(shadowed, bindings))
        assertEquals(evaluator.evaluate(general(shadowed), bindings), evaluator.evaluate(shadowed, bindings))
    }

    @Test
    fun `annotation values are read through accessors rather than the properties map`() {
        val annotation = AnnotationNode(NodeId(1), "Example", "Owner", "member", mapOf("id" to "fake-id-needle"))
        val evaluator = ExpressionEvaluator()
        assertEquals("fake-id-needle", NodePropertyAccessor.getAllProperties(annotation)["id"])
        assertEquals(false, evaluator.evaluate(predicate(CypherExpr.Literal("fake-id-needle")), mapOf("n" to annotation)))
    }

    @Test
    fun `compiled predicate remains cancellable and does not suppress later property failures`() {
        var polls = 0
        val evaluator = ExpressionEvaluator {
            polls++
            if (polls == 3) error("cancelled")
        }
        assertFailsWith<IllegalStateException> {
            evaluator.evaluate(predicate(CypherExpr.Literal("1")), mapOf("n" to StringConstant(NodeId(1), "needle")))
        }
        val throwingValue = object {
            override fun toString(): String = error("invalid value")
        }
        val annotation = AnnotationNode(NodeId(1), "Example", "Owner", "member", mapOf("later" to throwingValue))
        assertFailsWith<IllegalStateException> {
            ExpressionEvaluator().evaluate(predicate(CypherExpr.Literal("1")), mapOf("n" to annotation))
        }
    }

    @Test
    fun `compiled predicate cache reuses plans and evicts older expressions`() {
        val cache = DynamicPropertyContainsCache()
        val expression = predicate(CypherExpr.Literal("first"))
        val plan = assertNotNull(cache.get(expression))
        assertSame(plan, cache.get(expression))
        repeat(256) { cache.get(predicate(CypherExpr.Literal("other-$it"))) }
        assertNotSame(plan, cache.get(expression))
    }

    private fun predicate(term: CypherExpr) = CypherExpr.PredicateFunction(
        "any", "k", CypherExpr.FunctionCall("keys", listOf(CypherExpr.Variable("n"))),
        CypherExpr.StringOp(
            "CONTAINS",
            CypherExpr.FunctionCall(
                "toString", listOf(CypherExpr.Subscript(CypherExpr.Variable("n"), CypherExpr.Variable("k")))
            ),
            term
        )
    )

    private fun general(expression: CypherExpr.PredicateFunction) = expression.copy(
        predicate = CypherExpr.And(requireNotNull(expression.predicate), CypherExpr.Literal(true))
    )

    private fun nodes(): List<Node> = listOf(
        IntConstant(NodeId(1), 105873), StringConstant(NodeId(2), "needle"), LongConstant(NodeId(3), 105873L),
        FloatConstant(NodeId(4), 1.5f), DoubleConstant(NodeId(5), 1.5), BooleanConstant(NodeId(6), true),
        NullConstant(NodeId(7)), EnumConstant(NodeId(8), TypeDescriptor("Example"), "VALUE", listOf("needle")),
        LocalVariable(NodeId(9), "local", TypeDescriptor("String"), method),
        FieldNode(NodeId(10), FieldDescriptor(TypeDescriptor("Owner"), "field", TypeDescriptor("String")), false),
        ParameterNode(NodeId(11), 0, TypeDescriptor("String"), method), ReturnNode(NodeId(12), method),
        ResourceFileNode(NodeId(13), "file", "source", "text"),
        ResourceValueNode(NodeId(14), "file", "key", "needle", "text"),
        CallSiteNode(NodeId(15), method, method, null, null, emptyList()),
        AnnotationNode(NodeId(16), "Name", "Owner", "member", mapOf("extra" to "needle")),
        AnnotationNode(NodeId(17), "Name", "Owner", "member", linkedMapOf("graphId" to "shadow", "other" to null))
    )
}
