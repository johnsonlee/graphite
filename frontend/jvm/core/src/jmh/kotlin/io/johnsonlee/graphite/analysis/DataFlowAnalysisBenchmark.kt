package io.johnsonlee.graphite.analysis

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ParameterNode
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
import io.johnsonlee.graphite.graph.Graph
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * Guardrail benchmark for backwardSlice().
 *
 * Covers:
 * - direct intra-procedural traversal
 * - inter-procedural parameter tracing
 * - overload filtering on parameter tracing
 *
 * The benchmark intentionally avoids adding extra indexes or caches so it stays
 * aligned with the memory constraints documented in docs/webgraph-storage.md.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1, jvmArgs = ["-Xmx1g"])
open class DataFlowAnalysisBenchmark {

    private lateinit var directGraph: Graph
    private lateinit var interproceduralGraph: Graph
    private lateinit var overloadGraph: Graph

    private var directSinkId: NodeId = NodeId(0)
    private var parameterId: NodeId = NodeId(0)
    private var overloadParameterId: NodeId = NodeId(0)

    @Setup
    fun setup() {
        NodeId.reset()
        buildDirectGraph()
        buildInterproceduralGraph()
        buildOverloadGraph()
    }

    @Benchmark
    fun backwardSliceDirect(): DataFlowResult {
        return DataFlowAnalysis(directGraph).backwardSlice(directSinkId)
    }

    @Benchmark
    fun backwardSliceInterprocedural(): DataFlowResult {
        return DataFlowAnalysis(interproceduralGraph).backwardSlice(parameterId)
    }

    @Benchmark
    fun backwardSliceInterproceduralOverloadFiltering(): DataFlowResult {
        return DataFlowAnalysis(overloadGraph).backwardSlice(overloadParameterId)
    }

    private fun buildDirectGraph() {
        val builder = DefaultGraph.Builder()
        val method = MethodDescriptor(
            TypeDescriptor("com.example.Direct"),
            "run",
            emptyList(),
            TypeDescriptor("void")
        )

        var previousId = NodeId.next()
        builder.addNode(IntConstant(previousId, 1))
        repeat(64) { index ->
            val local = LocalVariable(NodeId.next(), "v$index", TypeDescriptor("int"), method)
            builder.addNode(local)
            builder.addEdge(DataFlowEdge(previousId, local.id, DataFlowKind.ASSIGN))
            previousId = local.id
        }

        directSinkId = previousId
        directGraph = builder.build()
    }

    private fun buildInterproceduralGraph() {
        val builder = DefaultGraph.Builder()
        val callee = MethodDescriptor(
            TypeDescriptor("com.example.Callee"),
            "process",
            listOf(TypeDescriptor("int")),
            TypeDescriptor("void")
        )
        builder.addMethod(callee)

        parameterId = NodeId.next()
        builder.addNode(ParameterNode(parameterId, 0, TypeDescriptor("int"), callee))

        repeat(128) { index ->
            val caller = MethodDescriptor(
                TypeDescriptor("com.example.Caller$index"),
                "call",
                emptyList(),
                TypeDescriptor("void")
            )
            builder.addMethod(caller)

            val constant = IntConstant(NodeId.next(), index)
            builder.addNode(constant)
            builder.addNode(
                CallSiteNode(
                    id = NodeId.next(),
                    caller = caller,
                    callee = callee,
                    lineNumber = index,
                    receiver = null,
                    arguments = listOf(constant.id)
                )
            )
        }

        interproceduralGraph = builder.build()
    }

    private fun buildOverloadGraph() {
        val builder = DefaultGraph.Builder()
        val caller = MethodDescriptor(
            TypeDescriptor("com.example.Caller"),
            "call",
            emptyList(),
            TypeDescriptor("void")
        )
        val intOverload = MethodDescriptor(
            TypeDescriptor("com.example.Callee"),
            "process",
            listOf(TypeDescriptor("int")),
            TypeDescriptor("void")
        )
        val stringOverload = MethodDescriptor(
            TypeDescriptor("com.example.Callee"),
            "process",
            listOf(TypeDescriptor("java.lang.String")),
            TypeDescriptor("void")
        )
        builder.addMethod(caller)
        builder.addMethod(intOverload)
        builder.addMethod(stringOverload)

        overloadParameterId = NodeId.next()
        builder.addNode(ParameterNode(overloadParameterId, 0, TypeDescriptor("int"), intOverload))

        repeat(128) { index ->
            val intConst = IntConstant(NodeId.next(), index)
            val stringConst = StringConstant(NodeId.next(), "noise-$index")
            builder.addNode(intConst)
            builder.addNode(stringConst)
            builder.addNode(
                CallSiteNode(
                    id = NodeId.next(),
                    caller = caller,
                    callee = intOverload,
                    lineNumber = index,
                    receiver = null,
                    arguments = listOf(intConst.id)
                )
            )
            builder.addNode(
                CallSiteNode(
                    id = NodeId.next(),
                    caller = caller,
                    callee = stringOverload,
                    lineNumber = 1000 + index,
                    receiver = null,
                    arguments = listOf(stringConst.id)
                )
            )
        }

        overloadGraph = builder.build()
    }
}
