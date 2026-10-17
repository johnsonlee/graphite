package io.johnsonlee.graphite.webgraph

import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Fresh production build/save; the benchmark controller then loads these files in the Rust server. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx4g"])
open class Fixture64BuildBenchmark {
    @Benchmark
    fun buildAndSaveDistinctRealGraphs(): Long {
        val output = Path.of(requireNotNull(System.getProperty("graphite.fixture64.output")))
        check(Files.notExists(output)) { "Fresh graph output must not already exist: $output" }
        Fixture64GraphPreparation.main(arrayOf(output.toString()))
        val manifest = output.resolve("graphs.tsv")
        check(Files.isRegularFile(manifest)) { "Fixture build did not complete its manifest" }
        return Files.size(manifest)
    }
}
