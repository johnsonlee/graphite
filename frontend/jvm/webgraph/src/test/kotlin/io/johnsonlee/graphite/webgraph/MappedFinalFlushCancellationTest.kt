package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.SplitGraphWorkBatchConsumer
import io.johnsonlee.graphite.graph.StringMatchMode
import io.johnsonlee.graphite.graph.StringPropertyPredicate
import io.johnsonlee.graphite.graph.nodesByStringPropertyDisjunction
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MappedFinalFlushCancellationTest {
    private val predicates = listOf(StringPropertyPredicate("caller_class", null, StringMatchMode.CONTAINS, "Target"))
    private val uninterruptedConsumer = object : SplitGraphWorkBatchConsumer {
        override val segmentWorkerCount: Int = 0
        override fun consume(workUnits: Long) = Unit
    }

    @Test
    fun `inline distinct projection observes interruption from its final accounting flush`() = withMapped { mapped ->
        assertEquals(
            listOf<List<String?>>(listOf("example.TargetCaller0")),
            mapped.distinctStringPropertyDisjunction(
                CallSiteNode::class.java, predicates, listOf("caller_class"), 1, workConsumer = uninterruptedConsumer
            )?.map { it.values }
        )
        assertFinalFlushCancellation(mapped) { consumer ->
            mapped.distinctStringPropertyDisjunction(
                CallSiteNode::class.java, predicates, listOf("caller_class"), 1, workConsumer = consumer
            )
        }
    }

    @Test
    fun `inline candidate scan observes interruption from its final accounting flush`() = withMapped { mapped ->
        assertEquals(
            listOf(NodeId(0)),
            mapped.nodesByStringPropertyDisjunction(
                CallSiteNode::class.java, predicates, limit = 1, workConsumer = uninterruptedConsumer
            ).orEmpty().map { it.id }.toList()
        )
        assertFinalFlushCancellation(mapped) { consumer ->
            mapped.nodesByStringPropertyDisjunction(
                CallSiteNode::class.java, predicates, limit = 1, workConsumer = consumer
            ).orEmpty().toList()
        }
    }

    private fun assertFinalFlushCancellation(
        mapped: MappedWebGraphBackedGraph,
        request: (SplitGraphWorkBatchConsumer) -> Unit
    ) {
        assertFalse(Thread.currentThread().isInterrupted)
        mapped.resetCallSiteScanMetrics()
        var callbacks = 0
        var work = 0L
        var callbackThread: Thread? = null
        val consumer = object : SplitGraphWorkBatchConsumer {
            override val segmentWorkerCount: Int = 0

            override fun consume(workUnits: Long) {
                callbacks++
                work += workUnits
                callbackThread = Thread.currentThread()
                Thread.currentThread().interrupt()
            }
        }
        try {
            val failure = runCatching { request(consumer) }.exceptionOrNull()
            // One matched node is below the batch size: this callback can only be the final flush.
            assertEquals(1, callbacks)
            assertEquals(1L, work)
            assertSame(Thread.currentThread(), callbackThread)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1L, mapped.callSiteParallelScanCount())
            assertEquals(1, mapped.callSiteScanPeakActiveWorkers())
            assertEquals(0, mapped.callSiteScanActiveWorkers())
            assertFalse(mapped.isCallSiteStringIndexInitialized())
            assertFalse(mapped.isMappedCallSiteStringIndexViewInitialized())
            assertTrue(failure is CancellationException, failure?.stackTraceToString() ?: "Interrupted request returned normally")
            assertEquals("Mapped string-property scan interrupted", failure?.message)
        } finally {
            Thread.interrupted()
        }
    }

    private fun withMapped(block: (MappedWebGraphBackedGraph) -> Unit) {
        val graph = DefaultGraph.Builder().apply {
            // Reach raw parallel-scan admission; the request still consumes only the first node.
            repeat(4_096) { index ->
                addNode(CallSiteNode(
                    NodeId(index),
                    MethodDescriptor(TypeDescriptor("example.TargetCaller$index"), "call", emptyList(), TypeDescriptor("void")),
                    MethodDescriptor(TypeDescriptor("example.Dependency"), "invoke", emptyList(), TypeDescriptor("void")),
                    index, null, emptyList()
                ))
            }
        }.build()
        val dir = Files.createTempDirectory("mapped-final-flush-cancellation")
        try {
            GraphStore.save(graph, dir, prepareCallSiteStringIndex = false)
            (GraphStore.loadMapped(dir) as MappedWebGraphBackedGraph).use(block)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
