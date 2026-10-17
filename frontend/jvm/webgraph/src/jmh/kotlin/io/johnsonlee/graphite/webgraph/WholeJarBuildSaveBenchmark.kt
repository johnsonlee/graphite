package io.johnsonlee.graphite.webgraph

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/**
 * Whole inputs exercise heap pressure that partitioned real64 graphs cannot cover.
 * JMH is the isolated execution harness; single-graph durations are diagnostic only.
 * Readback/query assertions validate production save and never represent Rust server latency.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx4g", "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError"])
open class WholeJarBuildSaveBenchmark {
    @Param("tika", "hive", "kotlin-compiler")
    lateinit var corpus: String

    @Benchmark
    fun buildSaveAndValidateWholeJar() {
        check(java.lang.Boolean.getBoolean("graphite.correctness.only")) {
            "Whole-JAR hosted execution must disable performance thresholds"
        }
        val productIdentity = requireNotNull(System.getProperty("graphite.benchmark.productIdentity")) {
            "Whole-JAR hosted execution requires the prepared product identity"
        }
        val baseline = LargeCorpusBaselines.forProduct(corpus, productIdentity)
        LargeCorpusValidation(baseline).verify()
    }
}
