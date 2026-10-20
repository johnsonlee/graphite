package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.ReturnNode
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DeclaredType
import io.johnsonlee.graphite.graph.DeclaredTypeBinding
import io.johnsonlee.graphite.graph.DeclaredTypeTable
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MemberTypeKey
import io.johnsonlee.graphite.graph.MethodTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A graph that binds by IDs decides; the member-key lookup answers only what the binding hands back. */
class DeclaredTypeBindingPreferenceTest {
    private val owner = TypeDescriptor("example.Box")
    private val type = TypeDescriptor("java.util.List")
    private val method = MethodDescriptor(owner, "get", listOf(type), type)
    private val field = FieldNode(NodeId(1), FieldDescriptor(owner, "items", type), false)
    private val parameter = ParameterNode(NodeId(2), 0, type, method)
    private val result = ReturnNode(NodeId(3), method)
    private val key = MemberTypeKey(owner.className, method.name, method.descriptor)
    private val byKey = MethodTypes(listOf(1), 1)
    private val byId = MethodTypes(listOf(0), 0)

    private val table = DeclaredTypeTable(
        types = listOf(
            DeclaredType("class", "java.lang.String"), DeclaredType("class", type.className, arguments = listOf(0))
        ),
        fields = mapOf(MemberTypeKey(owner.className, "items", "Ljava/util/List;") to 1),
        methods = mapOf(key to byKey),
        classes = emptyMap()
    )

    private class Bound(private val delegate: Graph, private val binding: DeclaredTypeBinding) : Graph by delegate {
        override fun declaredTypeBinding(): DeclaredTypeBinding = binding
    }

    private fun graph(binding: DeclaredTypeBinding): Graph = Bound(
        DefaultGraph.Builder().apply {
            addNode(field); addNode(parameter); addNode(result); addMethod(method); setDeclaredTypes(table)
        }.build(),
        binding
    )

    private fun binding(fieldId: Int, memberId: Int, descriptorMethod: MethodTypes?) = object : DeclaredTypeBinding {
        override fun fieldTypeId(node: FieldNode): Int = fieldId
        override fun memberTypeId(node: Node): Int = memberId
        override fun methodTypes(method: MethodDescriptor): MethodTypes? = descriptorMethod
        override fun methodShape(method: MethodDescriptor): Int = when {
            descriptorMethod === DeclaredTypeBinding.UNANSWERED_METHOD -> DeclaredTypeBinding.UNANSWERED
            descriptorMethod == null -> DeclaredTypeBinding.UNBOUND
            else -> {
                val bounds = descriptorMethod.typeParameters.any { it.bounds.isNotEmpty() }
                (if (descriptorMethod.parameterTypes.isNotEmpty()) DeclaredTypeBinding.HAS_PARAMETERS else 0) or
                    (if (bounds) DeclaredTypeBinding.HAS_BOUNDS else 0)
            }
        }
    }

    @Test
    fun `a bound answer wins over the key lookup`() {
        val graph = graph(binding(0, 0, byId))
        assertEquals("java.lang.String", DeclaredTypeProperties.nodeProperty(field, "generic_type", graph))
        assertEquals("java.lang.String", DeclaredTypeProperties.nodeProperty(parameter, "generic_type", graph))
        assertEquals("java.lang.String", DeclaredTypeProperties.nodeProperty(result, "generic_type", graph))
        assertEquals("java.lang.String", DeclaredTypeProperties.methodProperty(method, "generic_return_type", graph))
        assertTrue(DeclaredTypeProperties.hasNodeProperties(field, graph))
        assertTrue(DeclaredTypeProperties.hasMethodProperties(method, graph))
        assertEquals(true, DeclaredTypeProperties.nodePropertyPresent(field, "type_info", graph))
        assertEquals(true, DeclaredTypeProperties.methodPropertyPresent(method, "generic_return_type", graph))
        assertEquals(true, DeclaredTypeProperties.methodPropertyPresent(method, "generic_parameter_types", graph))
        assertEquals(true, DeclaredTypeProperties.methodPropertyPresent(method, "type_parameters", graph))
        assertNull(DeclaredTypeProperties.methodPropertyPresent(method, "name", graph))
    }

    @Test
    fun `presence of parameter and bound projections follows the bound row's shape`() {
        val unanswered = DeclaredTypeBinding.UNANSWERED
        val plain = graph(binding(unanswered, unanswered, MethodTypes(emptyList(), 0)))
        assertEquals(true, DeclaredTypeProperties.methodPropertyPresent(method, "generic_return_type", plain))
        assertEquals(true, DeclaredTypeProperties.methodPropertyPresent(method, "generic_parameter_types", plain))
        assertEquals(true, DeclaredTypeProperties.methodPropertyPresent(method, "type_parameters", plain))
        val unbound = graph(binding(unanswered, unanswered, null))
        assertEquals(false, DeclaredTypeProperties.methodPropertyPresent(method, "generic_return_type", unbound))
    }

    @Test
    fun `an unbound answer is final even where the key lookup would bind`() {
        val graph = graph(binding(DeclaredTypeBinding.UNBOUND, DeclaredTypeBinding.UNBOUND, null))
        for (node in listOf(field, parameter, result)) {
            assertNull(DeclaredTypeProperties.nodeProperty(node, "generic_type", graph))
            assertFalse(DeclaredTypeProperties.hasNodeProperties(node, graph))
            assertEquals(false, DeclaredTypeProperties.nodePropertyPresent(node, "generic_type", graph))
            assertTrue(DeclaredTypeProperties.nodeProperties(node, graph).isEmpty())
        }
        assertNull(DeclaredTypeProperties.methodProperty(method, "generic_return_type", graph))
        assertFalse(DeclaredTypeProperties.hasMethodProperties(method, graph))
        assertTrue(DeclaredTypeProperties.methodProperties(method, graph).isEmpty())
    }

    @Test
    fun `an unanswered receiver falls back to the key lookup`() {
        val unanswered = DeclaredTypeBinding.UNANSWERED
        val graph = graph(binding(unanswered, unanswered, DeclaredTypeBinding.UNANSWERED_METHOD))
        val rendered = table.render(1)
        for (node in listOf(field, parameter, result)) {
            assertEquals(rendered, DeclaredTypeProperties.nodeProperty(node, "generic_type", graph))
            assertTrue(DeclaredTypeProperties.hasNodeProperties(node, graph))
        }
        assertEquals(rendered, DeclaredTypeProperties.methodProperty(method, "generic_return_type", graph))
        assertTrue(DeclaredTypeProperties.hasMethodProperties(method, graph))
    }

    @Test
    fun `node and method answers are independent`() {
        val graph = graph(binding(DeclaredTypeBinding.UNANSWERED, DeclaredTypeBinding.UNBOUND, byId))
        assertEquals(table.render(1), DeclaredTypeProperties.nodeProperty(field, "generic_type", graph))
        assertNull(DeclaredTypeProperties.nodeProperty(parameter, "generic_type", graph))
        assertNull(DeclaredTypeProperties.nodeProperty(result, "generic_type", graph))
        assertEquals("java.lang.String", DeclaredTypeProperties.methodProperty(method, "generic_return_type", graph))
    }

    @Test
    fun `a graph without a table never consults its binding`() {
        val graph = Bound(DefaultGraph.Builder().apply { addNode(field) }.build(), binding(0, 0, byId))
        assertNull(DeclaredTypeProperties.nodeProperty(field, "generic_type", graph))
        assertNull(DeclaredTypeProperties.methodProperty(method, "generic_return_type", graph))
    }
}
