package io.johnsonlee.graphite.cypher

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.DataFlowEdge
import io.johnsonlee.graphite.core.DataFlowKind
import io.johnsonlee.graphite.core.FieldDescriptor
import io.johnsonlee.graphite.core.FieldNode
import io.johnsonlee.graphite.core.IntConstant
import io.johnsonlee.graphite.core.MethodDescriptor
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.StringConstant
import io.johnsonlee.graphite.core.TypeDescriptor
import io.johnsonlee.graphite.core.TypeRelation
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
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.OptionsBuilder
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
open class CypherBenchmark {

    private lateinit var graph: Graph

    @Setup
    fun setup() {
        NodeId.reset()
        val builder = DefaultGraph.Builder()
        val fooType = TypeDescriptor("com.example.Foo")
        val barType = TypeDescriptor("com.example.Bar")

        // Create 500 call site nodes with corresponding int constants and dataflow edges
        for (i in 1..500) {
            val method = MethodDescriptor(fooType, "method$i", emptyList(), TypeDescriptor("void"))
            val callee = MethodDescriptor(barType, "target${i % 50}", listOf(TypeDescriptor("int")), TypeDescriptor("void"))
            val csId = NodeId.next()
            val cs = CallSiteNode(csId, method, callee, i, null, emptyList())
            builder.addNode(cs)
            builder.addMethod(method)
            builder.addMethod(callee)

            val cId = NodeId.next()
            val c = IntConstant(cId, i)
            builder.addNode(c)

            builder.addEdge(DataFlowEdge(c.id, cs.id, DataFlowKind.PARAMETER_PASS))
        }

        // Add string constants
        for (i in 1..100) {
            val sc = StringConstant(NodeId.next(), "value_$i")
            builder.addNode(sc)
        }

        // Add type hierarchy
        builder.addTypeRelation(barType, fooType, TypeRelation.EXTENDS)

        // Add fields
        for (i in 1..100) {
            val field = FieldNode(
                NodeId.next(),
                FieldDescriptor(fooType, "field$i", TypeDescriptor("java.lang.String")),
                false
            )
            builder.addNode(field)
        }

        graph = builder.build()
    }

    @Benchmark
    fun simpleNodeMatch(): CypherResult {
        return graph.query("MATCH (n:CallSiteNode) RETURN n.callee_name LIMIT 100")
    }

    @Benchmark
    fun nodeMatchWithWhere(): CypherResult {
        return graph.query("MATCH (n:IntConstant) WHERE n.value > 100 AND n.value < 200 RETURN n.value")
    }

    @Benchmark
    fun regexFilter(): CypherResult {
        return graph.query("MATCH (n:CallSiteNode) WHERE n.callee_class =~ 'com\\.example\\..*' RETURN n.callee_name LIMIT 50")
    }

    @Benchmark
    fun singleHopRelationship(): CypherResult {
        return graph.query("MATCH (c:IntConstant)-[:DATAFLOW]->(cs:CallSiteNode) RETURN c.value, cs.callee_name LIMIT 50")
    }

    @Benchmark
    fun aggregationCountGroupBy(): CypherResult {
        return graph.query("MATCH (n:CallSiteNode) RETURN n.callee_class, count(*) AS cnt")
    }

    @Benchmark
    fun variableLengthPath(): CypherResult {
        return graph.query("MATCH (a:IntConstant)-[:DATAFLOW*..2]->(b:CallSiteNode) RETURN a.value, b.callee_name LIMIT 20")
    }

    @Benchmark
    fun returnDistinct(): CypherResult {
        return graph.query("MATCH (n:CallSiteNode) RETURN DISTINCT n.callee_class")
    }

    @Benchmark
    fun withPipeline(): CypherResult {
        return graph.query("MATCH (n:IntConstant) WITH n.value AS v WHERE v > 250 RETURN v ORDER BY v LIMIT 10")
    }

    @Benchmark
    fun functionCalls(): CypherResult {
        return graph.query("MATCH (n:CallSiteNode) RETURN toLower(n.callee_name), size(n.callee_class) LIMIT 50")
    }

    @Benchmark
    fun countStar(): CypherResult {
        return graph.query("MATCH (n) RETURN count(*)")
    }
}

/**
 * Run benchmarks from command line:
 *   java -cp <classpath> io.johnsonlee.graphite.cypher.CypherBenchmarkRunner
 */
class CypherBenchmarkRunner {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val options = OptionsBuilder()
                .include(CypherBenchmark::class.java.simpleName)
                .build()
            Runner(options).run()
        }
    }
}
