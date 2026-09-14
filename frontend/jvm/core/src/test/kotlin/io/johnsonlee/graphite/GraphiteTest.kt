package io.johnsonlee.graphite

import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.analysis.TypeHierarchyAnalysis
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.query.GraphiteQuery
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphiteTest {

    @BeforeTest
    fun resetNodeId() {
        NodeId.reset()
    }

    @Test
    fun `from creates Graphite instance`() {
        val graph = DefaultGraph.Builder().build()
        val graphite = Graphite.from(graph)
        assertNotNull(graphite)
        assertNotNull(graphite.graph)
    }

    @Test
    fun `query returns GraphiteQuery`() {
        val graph = DefaultGraph.Builder().build()
        val graphite = Graphite.from(graph)
        val query = graphite.query()
        assertNotNull(query)
    }

    @Test
    fun `query block returns results`() {
        val graph = DefaultGraph.Builder().build()
        val graphite = Graphite.from(graph)
        val results = graphite.query {
            findArgumentConstants {
                method {
                    declaringClass = "com.example.Missing"
                    name = "missing"
                }
            }
        }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `dataflow returns DataFlowAnalysis`() {
        val graph = DefaultGraph.Builder().build()
        val graphite = Graphite.from(graph)
        val analysis = graphite.dataflow()
        assertNotNull(analysis)
    }

    @Test
    fun `typeHierarchy returns TypeHierarchyAnalysis`() {
        val graph = DefaultGraph.Builder().build()
        val graphite = Graphite.from(graph)
        val analysis = graphite.typeHierarchy()
        assertNotNull(analysis)
    }
}
