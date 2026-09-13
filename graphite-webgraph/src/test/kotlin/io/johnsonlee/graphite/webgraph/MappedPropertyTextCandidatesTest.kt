package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.AnnotationNode
import io.johnsonlee.graphite.core.BooleanConstant
import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DoubleConstant
import io.johnsonlee.graphite.core.EnumConstant
import io.johnsonlee.graphite.core.EnumValueReference
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
import io.johnsonlee.graphite.cypher.NodePropertyAccessor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.GraphWorkConsumer
import io.johnsonlee.graphite.graph.NodePropertyTextCandidates
import io.johnsonlee.graphite.graph.propertyTextFragment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Synthetic persisted fixtures verify correctness and source access only, never performance. */
class MappedPropertyTextCandidatesTest {
    @Test
    fun `candidate superset preserves exact dynamic property matches and encounter order`() = withGraph { graph ->
        val all = graph.nodes(Node::class.java).toList()
        val lookup = graph as NodePropertyTextCandidates
        val needles = listOf(
            "Service.checkVoucher(", "java.lang.Argument", "ResolvedPayload", "productionProfile",
            "primaryField", "localPayload", "parameterPayload", "callerMethod", "calleeMethod",
            "stringPayload", "Service.nonexistentMethod(", "missingUniqueFragment",
            "enumPayload, nested", "resourcePayload, nested", "annotationPayload", "AnnotationNode",
            "enum.package.Priority.HIGH", "annotationKey", "propertiesFormat", "resourcePath"
        )
        for (needle in needles) {
            val fragment = assertNotNull(propertyTextFragment(needle), needle)
            val candidates = assertNotNull(lookup.propertyTextCandidates(Node::class.java, fragment, null), needle).toList()
            val candidateIds = candidates.map { it.id }.toSet()
            assertEquals(candidates.size, candidateIds.size, "duplicate candidates for $needle")
            assertEquals(all.filter { it.id in candidateIds }.map { it.id }, candidates.map { it.id }, needle)
            val expected = all.filter { matches(it, needle) }.map { it.id }
            // Applying the original complete predicate removes all false positives and retains all exact hits.
            assertEquals(expected, candidates.filter { matches(it, needle) }.map { it.id }, "$needle -> $fragment")
        }
    }

    @Test
    fun `fallback node types retain synthesized and nested property values`() = withGraph { graph ->
        val lookup = graph as NodePropertyTextCandidates
        val cases = listOf(
            EnumConstant::class.java to "enumPayload, nested",
            ResourceValueNode::class.java to "resourcePayload, nested",
            AnnotationNode::class.java to "AnnotationNode"
        )
        for ((type, needle) in cases) {
            val expected = graph.nodes(type).filter { matches(it, needle) }.map { it.id }.toList()
            assertTrue(expected.isNotEmpty(), "fixture must exercise a real fallback match: $needle")
            val fragment = assertNotNull(propertyTextFragment(needle))
            val candidates = lookup.propertyTextCandidates(type, fragment, null) ?: graph.nodes(type)
            assertEquals(expected, candidates.filter { matches(it, needle) }.map { it.id }.toList(), needle)
        }
    }

    @Test
    fun `typed candidates exclude unrelated node types while retaining signature match`() = withGraph { graph ->
        val fragment = assertNotNull(propertyTextFragment("Service.checkVoucher("))
        val candidates = assertNotNull((graph as NodePropertyTextCandidates).propertyTextCandidates(
            CallSiteNode::class.java, fragment, null
        )).toList()
        assertTrue(candidates.isNotEmpty())
        assertEquals(graph.nodes(CallSiteNode::class.java).filter { matches(it, "Service.checkVoucher(") }.map { it.id }.toList(),
            candidates.filter { matches(it, "Service.checkVoucher(") }.map { it.id })
    }

    @Test
    fun `necessary fragment candidates still require the complete substring predicate`() = withGraph { graph ->
        val needle = "Service.checkVoucher(wrong)"
        val fragment = assertNotNull(propertyTextFragment(needle))
        assertEquals("checkVoucher", fragment)
        val candidates = assertNotNull((graph as NodePropertyTextCandidates).propertyTextCandidates(
            CallSiteNode::class.java, fragment, null
        )).toList()
        assertTrue(candidates.any { matches(it, fragment) })
        assertTrue(candidates.none { matches(it, needle) })
        assertTrue(graph.nodes(CallSiteNode::class.java).none { matches(it, needle) })
    }

    @Test
    fun `unsupported numeric and generated fragments request ordinary evaluation`() = withGraph { graph ->
        val lookup = graph as NodePropertyTextCandidates
        for (fragment in listOf("E123", "e45", "Infinity", "NaN", "true", "123", "")) {
            assertNull(propertyTextFragment(fragment), fragment)
            assertNull(lookup.propertyTextCandidates(Node::class.java, fragment, null), fragment)
        }
    }

    @Test
    fun `candidate search propagates source work cancellation`() = withGraph { graph ->
        val marker = StopLookup()
        val actual = assertFailsWith<StopLookup> {
            (graph as NodePropertyTextCandidates).propertyTextCandidates(
                Node::class.java, "missingUniqueFragment", GraphWorkConsumer { throw marker }
            )?.toList()
        }
        assertSame(marker, actual)
    }

    // This oracle deliberately uses exposed keys followed by the accessor, not stored columns or
    // properties().values: annotation collisions have different accessor semantics.
    private fun matches(node: Node, needle: String): Boolean = NodePropertyAccessor.getAllProperties(node).keys
        .any { key -> NodePropertyAccessor.getProperty(node, key)?.toString()?.contains(needle) == true }

    private class StopLookup : RuntimeException()

    private fun withGraph(block: (Graph) -> Unit) {
        val directory = Files.createTempDirectory("mapped-property-text-candidates")
        try {
            val builder = DefaultGraph.Builder().apply { fixtureNodes().forEach(::addNode) }
            GraphStore.save(builder.build(), directory)
            val graph = GraphStore.loadMapped(directory)
            try {
                block(graph)
            } finally {
                (graph as? AutoCloseable)?.close()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun fixtureNodes(): List<Node> {
        val argument = TypeDescriptor("java.lang.Argument")
        val method = MethodDescriptor(TypeDescriptor("example.Service"), "checkVoucher", listOf(argument), TypeDescriptor("void"))
        val caller = method.copy(name = "callerMethod")
        val callee = method.copy(name = "calleeMethod")
        return listOf(
            IntConstant(NodeId(1), 105873), LongConstant(NodeId(2), 123456789L),
            FloatConstant(NodeId(3), 1.2E10f), DoubleConstant(NodeId(4), Double.POSITIVE_INFINITY),
            BooleanConstant(NodeId(5), true), NullConstant(NodeId(6)),
            StringConstant(NodeId(7), "stringPayload"),
            CallSiteNode(NodeId(8), method, callee, 123, null, emptyList()),
            CallSiteNode(NodeId(9), caller, callee, null, null, emptyList()),
            LocalVariable(NodeId(10), "localPayload", argument, method),
            FieldNode(NodeId(11), FieldDescriptor(TypeDescriptor("example.Service"), "primaryField", argument), true),
            ParameterNode(NodeId(12), 2, TypeDescriptor("parameterPayload"), method),
            ReturnNode(NodeId(13), method, TypeDescriptor("ResolvedPayload")),
            ResourceFileNode(NodeId(14), "resourcePath", "archiveSource", "propertiesFormat", "productionProfile"),
            EnumConstant(NodeId(15), TypeDescriptor("example.Priority"), "HIGH", listOf(listOf("enumPayload", "nested"))),
            ResourceValueNode(NodeId(16), "values.properties", "config", listOf("resourcePayload", "nested"), "properties"),
            AnnotationNode(NodeId(17), "example.Annotation", "example.Service", "member", mapOf(
                "annotationKey" to "annotationPayload", "type" to null,
                "nested" to listOf(EnumValueReference("enum.package.Priority", "HIGH"))
            ))
        )
    }
}
