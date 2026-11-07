package io.johnsonlee.graphite.input

import io.johnsonlee.graphite.graph.AnalysisCompleteness
import io.johnsonlee.graphite.graph.Graph

/**
 * Final evidence of the same build that streamed the observations. The graph (including its
 * final callSiteGaps) remains authoritative. A successful body capture does not erase gaps or
 * promote a library method to an application root. ANALYZED_WITHOUT_CONTROL_FLOW retains its
 * normal meaning: body/dataflow read, graph branch scopes omitted.
 *
 * Methods excluded by scope or budget are not resolved for callbacks: consult completeness's
 * class statuses. A class whose methods could not be enumerated has class RESOLUTION_FAILED;
 * its missing method callbacks never establish successful capture. EXTERNAL/UNKNOWN_MEMBER
 * retain their existing meaning. This result adds no persistent graph format or IR storage.
 */
interface ObservedJavaLoad {
    val graph: Graph
    val foldReport: FoldReport?
    val completeness: AnalysisCompleteness

    companion object {
        operator fun invoke(graph: Graph, foldReport: FoldReport?): ObservedJavaLoad =
            DefaultObservedJavaLoad(graph, foldReport, requireNotNull(graph.analysisCompleteness()))
    }
}

private class DefaultObservedJavaLoad(
    override val graph: Graph,
    override val foldReport: FoldReport?,
    override val completeness: AnalysisCompleteness
) : ObservedJavaLoad
