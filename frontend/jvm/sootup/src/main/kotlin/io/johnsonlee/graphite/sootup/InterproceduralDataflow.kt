package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId

/**
 * The edges that join a call to the body it runs, collected while the methods are processed and
 * linked once every body has its nodes (`LoaderConfig.interproceduralDataflow`). Without them a
 * graph's dataflow ends at the call site: a constant a helper returns reaches the helper's
 * `ReturnNode` and stops, and a caller's argument reaches the `CallSite` but never the callee's
 * `ParameterNode`, so no query can follow a key through a helper into the gate it feeds.
 *
 * Every call links to its declared callee and, when it is virtual, to every override the view
 * knows (the implementations the adapter's dispatch flows use as well): argument `i` flows to
 * `ParameterNode(i)` of each, and each `ReturnNode` flows to the call's result. A callee without a
 * body in the build (a JDK method, an abstract one) has no such nodes and links nothing. The
 * adapter clears its per-method node maps after each method, so the ids are kept here, and only
 * when the flag is on: the index costs a few ints per method and per call.
 */
internal class InterproceduralDataflow {

    private class Call(val callee: MethodDescriptor, val virtual: Boolean, val arguments: List<NodeId>, val result: NodeId?)

    private val returns = HashMap<MethodDescriptor, NodeId>()
    private val parameters = HashMap<MethodDescriptor, List<NodeId>>()
    private val calls = ArrayList<Call>()

    fun method(method: MethodDescriptor, returnNode: NodeId) {
        returns[method] = returnNode
    }

    fun parameters(method: MethodDescriptor, nodes: List<NodeId>) {
        parameters[method] = nodes
    }

    fun call(callee: MethodDescriptor, virtual: Boolean, arguments: List<NodeId>, result: NodeId?) {
        calls += Call(callee, virtual, arguments, result)
    }

    /**
     * Emit the edges of every recorded call through [addEdge], resolving the overrides of a
     * virtual callee with [overrides]; the number of edges emitted.
     */
    fun link(overrides: (MethodDescriptor) -> List<MethodDescriptor>, addEdge: (DataFlowEdge) -> Unit): Int {
        var edges = 0
        for (call in calls) {
            val targets = if (call.virtual) (listOf(call.callee) + overrides(call.callee)).distinct() else listOf(call.callee)
            for (target in targets) {
                parameters[target]?.forEachIndexed { index, parameter ->
                    call.arguments.getOrNull(index)?.let { argument ->
                        addEdge(DataFlowEdge(from = argument, to = parameter, kind = DataFlowKind.PARAMETER_PASS))
                        edges++
                    }
                }
                val returned = returns[target]
                if (returned != null && call.result != null) {
                    addEdge(DataFlowEdge(from = returned, to = call.result, kind = DataFlowKind.RETURN_VALUE))
                    edges++
                }
            }
        }
        calls.clear()
        return edges
    }
}
