package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.Node
import io.johnsonlee.graphite.core.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class GraphWorkDefaultsTest {
    @Test
    fun `work aware extensions refuse graphs without capabilities without charging`() {
        val graph = DefaultGraph.Builder().addNode(IntConstant(NodeId(1), 7)).build()
        var charged = 0
        val work = GraphWorkConsumer { charged++ }
        assertNull(graph.nodesByStringProperty(Node::class.java, "value", StringMatchMode.CONTAINS, "7", 1, work))
        assertNull(graph.nodesByTransformedStringProperty(Node::class.java, "value", StringValueTransform.LOWERCASE,
            StringMatchMode.CONTAINS, "7", 1, work))
        assertEquals(0, charged)
    }

    @Test
    fun `specialized work consumers preserve single unit charging`() {
        var charged = 0L
        val consumers = listOf(
            ParallelGraphWorkBatchConsumer { charged += it },
            SerialGraphWorkBatchConsumer { charged += it },
            PreferredRawGraphWorkBatchConsumer { charged += it },
            PreferredPersistedStringIndexGraphWorkBatchConsumer { charged += it },
            object : SplitGraphWorkBatchConsumer {
                override val segmentWorkerCount = 2
                override fun consume(workUnits: Long) { charged += workUnits }
            },
            object : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
                override val segmentWorkerCount = 3
                override fun consume(workUnits: Long) { charged += workUnits }
            })
        consumers.forEach { it.consume(); it.consume(2) }
        assertEquals(18L, charged)
        consumers.forEach(JavaWorkConsumerCaller::charge)
        assertEquals(36L, charged, "Java interface calls must preserve the same unit budget")
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val balanced = GraphScanParallelismPlan.balanced()
        assertEquals(processors, balanced.graphWorkerCount + balanced.segmentWorkerCount)
        val overridden = GraphScanParallelismPlan.withGraphWorkers(graphWorkers = 1)
        assertEquals(1, overridden.graphWorkerCount)
        assertEquals(processors - 1, overridden.segmentWorkerCount)
    }

    @Test
    fun `legacy property candidates use first fragment and preserve refusal`() {
        val node = IntConstant(NodeId(1), 7)
        var selected: String? = null
        var work = 0
        val consumer = GraphWorkConsumer { work++ }
        val provider = object : NodePropertyTextCandidates {
            override fun <T : Node> propertyTextCandidates(
                type: Class<T>, fragment: String, workConsumer: GraphWorkConsumer?
            ): Sequence<T>? {
                selected = fragment
                workConsumer?.consume()
                return if (fragment == "unsupported") null else sequenceOf(type.cast(node))
            }
        }
        assertEquals(listOf(node), provider.propertyTextCandidates(IntConstant::class.java, listOf("first", "second"), consumer)?.toList())
        assertEquals("first", selected)
        assertNull(provider.propertyTextCandidates(IntConstant::class.java, emptyList(), consumer))
        assertEquals(1, work, "empty fragments must not call the legacy provider")
        assertNull(provider.propertyTextCandidates(IntConstant::class.java, listOf("unsupported"), consumer))
    }

    @Test
    fun `legacy declared binding defaults to unanswered rather than absent`() {
        val binding = object : DeclaredTypeBinding {
            override fun fieldTypeId(node: FieldNode) = DeclaredTypeBinding.UNANSWERED
            override fun memberTypeId(node: Node) = DeclaredTypeBinding.UNANSWERED
            override fun methodTypes(method: MethodDescriptor) = DeclaredTypeBinding.UNANSWERED_METHOD
            override fun methodShape(method: MethodDescriptor) = DeclaredTypeBinding.UNANSWERED
        }
        assertEquals(DeclaredTypeBinding.UNANSWERED, binding.nodePresence(IntConstant(NodeId(1), 7)))
        val marker = DeclaredTypeBinding.UNANSWERED_METHOD
        assertSame(marker, DeclaredTypeBinding.UNANSWERED_METHOD)
        assertEquals(emptyList(), marker.parameterTypes)
        assertEquals(DeclaredTypeBinding.UNANSWERED, marker.returnType)
    }
}
