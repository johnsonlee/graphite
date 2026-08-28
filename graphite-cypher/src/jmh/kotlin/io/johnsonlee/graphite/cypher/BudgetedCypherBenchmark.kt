package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.LocalVariable
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.graph.DefaultGraph
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

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class BudgetedCypherBenchmark {
    private lateinit var unbudgeted: CypherExecutor
    private lateinit var budgeted: CypherExecutor

    @Setup
    fun setup() {
        NodeId.reset()
        val owner = TypeDescriptor("com.example.BenchmarkService")
        val valueType = TypeDescriptor("int")
        val returnType = TypeDescriptor("void")
        val caller = MethodDescriptor(owner, "run", emptyList(), returnType)
        val callee = MethodDescriptor(
            TypeDescriptor("com.example.BenchmarkRepository"),
            "save",
            listOf(valueType),
            returnType
        )
        val graph = DefaultGraph.Builder().apply {
            repeat(GRAPH_WIDTH) { index ->
                val constant = IntConstant(NodeId.next(), index)
                val local = LocalVariable(NodeId.next(), "value$index", valueType, caller)
                val call = CallSiteNode(NodeId.next(), caller, callee, index, null, listOf(constant.id))
                addNode(constant)
                addNode(local)
                addNode(call)
                addEdge(DataFlowEdge(constant.id, local.id, DataFlowKind.ASSIGN))
                addEdge(DataFlowEdge(local.id, call.id, DataFlowKind.PARAMETER_PASS))
            }
        }.build()
        unbudgeted = CypherExecutor(graph)
        budgeted = CypherExecutor(graph, CypherExecutionBudget(BENCHMARK_WORK_BUDGET))
    }

    @Benchmark
    fun unbudgetedNodeScan(): CypherResult = unbudgeted.execute(NODE_SCAN_QUERY)

    @Benchmark
    fun budgetedNodeScan(): CypherResult = budgeted.execute(NODE_SCAN_QUERY)

    @Benchmark
    fun unbudgetedRelationship(): CypherResult = unbudgeted.execute(RELATIONSHIP_QUERY)

    @Benchmark
    fun budgetedRelationship(): CypherResult = budgeted.execute(RELATIONSHIP_QUERY)

    @Benchmark
    fun unbudgetedVariableLengthPath(): CypherResult = unbudgeted.execute(VARIABLE_PATH_QUERY)

    @Benchmark
    fun budgetedVariableLengthPath(): CypherResult = budgeted.execute(VARIABLE_PATH_QUERY)

    @Benchmark
    fun budgetedGeneralRegex(): CypherResult = budgeted.execute(GENERAL_REGEX_QUERY)
}

private const val GRAPH_WIDTH = 500
private const val BENCHMARK_WORK_BUDGET = 10_000L
private const val NODE_SCAN_QUERY = "MATCH (n:IntConstant) RETURN n.value LIMIT 500"
private const val RELATIONSHIP_QUERY =
    "MATCH (n:IntConstant)-[:DATAFLOW]->(v:LocalVariable) RETURN n.value, v.name LIMIT 500"
private const val VARIABLE_PATH_QUERY =
    "MATCH (n:IntConstant)-[r:DATAFLOW*2..2]->(c:CallSiteNode) RETURN r LIMIT 500"
private val GENERAL_REGEX_QUERY =
    "UNWIND range(1, 10000) AS x WITH '${"a".repeat(100)}12345678901234' AS s " +
        "WHERE s =~ '[a-z]+[0-9]+' RETURN count(*) AS n"
