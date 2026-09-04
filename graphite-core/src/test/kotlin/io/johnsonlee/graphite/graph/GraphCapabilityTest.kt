package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphCapabilityTest {

    @Test
    fun `v2 4 8 scheduling capability API remains callable without restoring its runtime path`() {
        val availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val defaultPlan = GraphScanParallelismPlan.balanced()
        assertEquals(availableProcessors, defaultPlan.graphWorkerCount + defaultPlan.segmentWorkerCount)
        assertEquals(
            GraphScanParallelismPlan(availableProcessors, 0),
            GraphScanParallelismPlan.withGraphWorkers(graphWorkers = Int.MAX_VALUE)
        )
        assertEquals(GraphScanParallelismPlan(1, 0), GraphScanParallelismPlan.balanced(1))
        assertEquals(GraphScanParallelismPlan(2, 3), GraphScanParallelismPlan.balanced(5))
        assertEquals(GraphScanParallelismPlan(4, 0), GraphScanParallelismPlan.withGraphWorkers(4, 99))
        assertFailsWith<IllegalArgumentException> { GraphScanParallelismPlan(0, 0) }
        assertFailsWith<IllegalArgumentException> { GraphScanParallelismPlan(1, -1) }

        var work = 0L
        val persisted = PreferredPersistedStringIndexGraphWorkBatchConsumer { units -> work += units }
        persisted.consume(2)
        assertEquals(2L, work)
        assertEquals(
            setOf(SerialGraphWorkBatchConsumer::class.java, ParallelGraphWorkBatchConsumer::class.java),
            PreferredPersistedStringIndexGraphWorkBatchConsumer::class.java.interfaces.toSet()
        )

        val mapped = object : PreferredMappedStringIndexViewGraphWorkBatchConsumer {
            override val segmentWorkerCount = 2

            override fun consume(workUnits: Long) {
                work += workUnits
            }
        }
        mapped.consume(4)
        assertEquals(6L, work)
        assertEquals(2, mapped.segmentWorkerCount)

        val prepared = object : PreparedStringPropertyDisjunctionLookup {
            override fun hasPreparedStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>
            ): Boolean = type == CallSiteNode::class.java && predicates.isEmpty()
        }
        assertTrue(prepared.hasPreparedStringPropertyDisjunction(CallSiteNode::class.java, emptyList()))
    }

    @Test
    fun `string disjunction capability defaults and lifecycle are callable`() {
        var work = 0L
        val batch = object : GraphWorkBatchConsumer {
            override fun consume(workUnits: Long) {
                work += workUnits
            }
        }
        batch.consume()
        batch.consume(2)

        val aggregate = object : WorkAwareStringPropertyDisjunctionAggregation {
            override fun aggregateStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                distinctProperty: String?
            ) = StringPropertyDisjunctionAggregate(predicates.size.toLong())

            override fun aggregateStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                distinctProperty: String?,
                workConsumer: GraphWorkConsumer
            ) = StringPropertyDisjunctionAggregate(predicates.size.toLong(), setOfNotNull(distinctProperty))
        }
        val projection = object : StringPropertyDisjunctionDistinctProjection {
            override fun distinctStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                selectedValues: Set<List<String?>>?,
                workConsumer: GraphWorkConsumer?
            ) = listOf(StringPropertyDistinctRow(7, projectedProperties))
        }
        val duplicateProjection = object : StringPropertyDisjunctionProjection {
            override fun projectStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>,
                projectedProperties: List<String>,
                limit: Int,
                workConsumer: GraphWorkConsumer?
            ) = listOf(StringPropertyProjectionRow(projectedProperties))
        }
        var released = false
        val cache = object : ReleasableStringPropertyDisjunctionCache {
            override fun releaseStringPropertyDisjunctionCache() {
                released = true
            }
        }
        val strategy = object : StringPropertyDisjunctionLookupStrategy {
            override fun prefersSerialStringPropertyDisjunction(
                type: Class<out Node>,
                predicates: List<StringPropertyPredicate>
            ) = predicates.isEmpty()
        }

        assertEquals(3, work)
        assertEquals(0L, aggregate.aggregateStringPropertyDisjunction(CallSiteNode::class.java, emptyList())?.count)
        assertEquals(
            emptySet(),
            aggregate.aggregateStringPropertyDisjunction(
                CallSiteNode::class.java,
                emptyList(),
                workConsumer = batch
            )?.distinctValues
        )
        assertEquals(
            setOf("caller_name"),
            aggregate.aggregateStringPropertyDisjunction(
                CallSiteNode::class.java,
                emptyList(),
                "caller_name",
                batch
            )?.distinctValues
        )
        assertEquals(
            listOf("caller_name"),
            projection.distinctStringPropertyDisjunction(
                CallSiteNode::class.java,
                emptyList(),
                listOf("caller_name"),
                1
            )?.single()?.values
        )
        assertEquals(
            listOf("caller_name"),
            duplicateProjection.projectStringPropertyDisjunction(
                CallSiteNode::class.java,
                emptyList(),
                listOf("caller_name"),
                1
            )?.single()?.values
        )
        cache.releaseStringPropertyDisjunctionCache()
        assertEquals(true, released)
        assertEquals(true, strategy.prefersSerialStringPropertyDisjunction(CallSiteNode::class.java, emptyList()))
        assertNull(StringPropertyDisjunctionAggregate(0).distinctValues)

        val graph = DefaultGraph.Builder().build()
        assertNull(
            graph.nodesByStringProperty(
                CallSiteNode::class.java,
                "caller_class",
                StringMatchMode.CONTAINS,
                "Target",
                1,
                batch
            )
        )
        assertNull(
            graph.nodesByTransformedStringProperty(
                CallSiteNode::class.java,
                "caller_class",
                StringValueTransform.LOWERCASE,
                StringMatchMode.CONTAINS,
                "target",
                1,
                batch
            )
        )
    }
}
