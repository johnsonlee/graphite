package io.johnsonlee.graphite.graph

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.Node
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GraphCapabilityTest {

    @Test
    fun `balanced graph scan parallelism divides NCPU additively`() {
        assertEquals(GraphScanParallelismPlan(8, 8), GraphScanParallelismPlan.balanced(16))
        assertEquals(GraphScanParallelismPlan(3, 4), GraphScanParallelismPlan.balanced(7))
        assertEquals(GraphScanParallelismPlan(1, 0), GraphScanParallelismPlan.balanced(1))
        assertEquals(GraphScanParallelismPlan(1, 0), GraphScanParallelismPlan.balanced(0))
    }

    @Test
    fun `graph worker override preserves one additive NCPU budget`() {
        assertEquals(GraphScanParallelismPlan(16, 0), GraphScanParallelismPlan.withGraphWorkers(16, 16))
        assertEquals(GraphScanParallelismPlan(2, 14), GraphScanParallelismPlan.withGraphWorkers(16, 2))
        assertEquals(GraphScanParallelismPlan(1, 0), GraphScanParallelismPlan.withGraphWorkers(1, 16))
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
    }
}
