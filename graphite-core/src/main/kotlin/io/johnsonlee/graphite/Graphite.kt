package io.johnsonlee.graphite

import io.johnsonlee.graphite.analysis.DataFlowAnalysis
import io.johnsonlee.graphite.analysis.TypeHierarchyAnalysis
import io.johnsonlee.graphite.core.TypeHierarchyConfig
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.query.GraphiteQuery

/**
 * Main entry point for Graphite static analysis framework.
 *
 * Graphite provides a graph-based abstraction for static program analysis.
 * Use this class to query and analyze a loaded program graph.
 *
 * Example usage:
 * ```kotlin
 * // Create Graphite from a loaded graph
 * val graphite = Graphite.from(graph)
 *
 * // Query the graph
 * val results = graphite.query {
 *     findArgumentConstants {
 *         method {
 *             name = "getOption"
 *             parameterTypes = listOf("java.lang.Integer")
 *         }
 *         argumentIndex = 0
 *     }
 * }
 * ```
 *
 * To load a graph, use a backend like graphite-sootup:
 * ```kotlin
 * val graph = JavaProjectLoader(config).load(path)
 * val graphite = Graphite.from(graph)
 * ```
 */
class Graphite private constructor(
    val graph: Graph
) {
    companion object {
        /**
         * Create Graphite from an existing graph
         */
        fun from(graph: Graph): Graphite {
            return Graphite(graph)
        }
    }

    /**
     * Get the query interface for custom queries
     */
    fun query(): GraphiteQuery = GraphiteQuery(graph)

    /**
     * Execute a query block
     */
    fun <T> query(block: GraphiteQuery.() -> T): T = query().block()

    /**
     * Get the dataflow analysis engine
     */
    fun dataflow(): DataFlowAnalysis = DataFlowAnalysis(graph)

    /**
     * Get the type hierarchy analysis engine
     */
    fun typeHierarchy(config: TypeHierarchyConfig = TypeHierarchyConfig()): TypeHierarchyAnalysis =
        TypeHierarchyAnalysis(graph, config)
}
