package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringValueTransform
import io.johnsonlee.graphite.graph.TransformedStringPropertyLookup
import io.johnsonlee.graphite.graph.WorkAwareTransformedStringPropertyLookup
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PipelineLowercaseTest : QueryPipelineTestFixture() {

    @Test
    fun `wrapped lowercase disjunction uses transformed lookup with exact semantics`() {
        val caller = MethodDescriptor(TypeDescriptor("Com.Example.VoucherService"), "Create", emptyList(), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repository"), "load", emptyList(), stringType)
        val backing = DefaultGraph.Builder().apply { addNode(CallSiteNode(NodeId(0), caller, callee, 0, null, emptyList())) }.build()
        val lookups = mutableListOf<Pair<String, String>>()
        val lookupGraph =
            object : Graph by backing, TransformedStringPropertyLookup {
                override fun <T : Node> nodes(type: Class<T>): Sequence<T> =
                    error("wrapped lowercase query unexpectedly used a full node scan")

                override fun <T : Node> nodesByTransformedStringProperty(
                    type: Class<T>,
                    property: String,
                    transform: StringValueTransform,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> {
                    assertEquals(StringValueTransform.LOWERCASE, transform)
                    assertEquals(StringMatchMode.CONTAINS, mode)
                    lookups += property to expected
                    return backing
                        .nodes(type)
                        .filter { node ->
                            val actual = NodePropertyAccessor.getProperty(node, property) as? String
                            actual?.lowercase()?.contains(expected) == true
                        }
                        .take(limit)
                }
            }

        val query =
            "MATCH (n) WHERE " +
                "toLower(coalesce(n.caller_class, '')) CONTAINS 'voucher' OR " +
                "toLowercase(coalesce(n.callee_class, '')) CONTAINS 'voucher' " +
                "RETURN DISTINCT n.caller_class AS caller LIMIT 250"
        val result = CypherExecutor(lookupGraph).execute(query)

        assertEquals(listOf("Com.Example.VoucherService"), result.rows.map { it["caller"] })
        assertTrue(lookups.contains("caller_class" to "voucher"))
        assertTrue(lookups.contains("callee_class" to "voucher"))
    }

    @Test
    fun `budgeted wrapped lowercase disjunction accounts for transformed lookup work`() {
        val caller = MethodDescriptor(TypeDescriptor("Com.Example.VoucherService"), "Create", emptyList(), stringType)
        val callee = MethodDescriptor(TypeDescriptor("com.example.Repository"), "load", emptyList(), stringType)
        val backing = DefaultGraph.Builder().apply { addNode(CallSiteNode(NodeId(0), caller, callee, 0, null, emptyList())) }.build()
        var consumed = 0
        val lookupGraph =
            object : Graph by backing, WorkAwareTransformedStringPropertyLookup {
                override fun <T : Node> nodesByTransformedStringProperty(
                    type: Class<T>,
                    property: String,
                    transform: StringValueTransform,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int
                ): Sequence<T> = error("budgeted query did not use the work-aware transformed lookup")

                override fun <T : Node> nodesByTransformedStringProperty(
                    type: Class<T>,
                    property: String,
                    transform: StringValueTransform,
                    mode: StringMatchMode,
                    expected: String,
                    limit: Int,
                    workConsumer: GraphWorkConsumer
                ): Sequence<T> =
                    backing
                        .nodes(type)
                        .onEach {
                            consumed++
                            workConsumer.consume()
                        }
                        .filter { node ->
                            val actual = NodePropertyAccessor.getProperty(node, property) as? String
                            actual?.lowercase()?.contains(expected) == true
                        }
                        .take(limit)
            }

        val result =
            CypherExecutor(lookupGraph, CypherExecutionBudget(maxWorkUnits = 10))
                .execute(
                    "MATCH (n) WHERE " +
                        "toLower(coalesce(n.caller_class, '')) CONTAINS 'voucher' OR " +
                        "toLower(coalesce(n.callee_class, '')) CONTAINS 'voucher' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertEquals(listOf("Com.Example.VoucherService"), result.rows.map { it["caller"] })
        assertEquals(1, consumed)
    }

    @Test
    fun `wrapped lowercase fast path does not normalize the expected literal`() {
        val result =
            CypherExecutor(graph)
                .execute(
                    "MATCH (n:CallSiteNode) " +
                        "WHERE toLower(coalesce(n.caller_class, '')) CONTAINS 'Service' " +
                        "RETURN DISTINCT n.caller_class AS caller LIMIT 1")

        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `wrapped lowercase empty needle preserves coalesce missing property semantics`() {
        val result =
            CypherExecutor(graph)
                .execute("MATCH (n) WHERE toLower(coalesce(n.caller_class, '')) CONTAINS '' " + "RETURN DISTINCT n.id AS id LIMIT 2")

        assertEquals(listOf(intConst42.value, intConst7.value), result.rows.map { it["id"] })
    }

    @Test
    fun `wrapped lowercase nonempty coalesce fallback stays on generic evaluator`() {
        val result =
            CypherExecutor(graph)
                .execute(
                    "MATCH (n) " +
                        "WHERE toLower(coalesce(n.caller_class, 'FallbackVoucher')) CONTAINS 'voucher' " +
                        "RETURN DISTINCT n.id AS id LIMIT 2")

        assertEquals(listOf(intConst42.value, intConst7.value), result.rows.map { it["id"] })
    }
}
