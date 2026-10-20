package io.johnsonlee.graphite.webgraph

import org.junit.Test

/** Local test entry point; hosted whole-JAR coverage executes the same assertions through JMH. */
abstract class LargeCorpusGate(private val baseline: CorpusBaseline) {
    @Test(timeout = 240_000)
    fun `build save mapped load and query stay within the large corpus gate`() {
        LargeCorpusValidation(baseline).verify()
    }
}

class TikaCorpusPerformanceGateTest : LargeCorpusGate(LargeCorpusBaselines.tika)
class HiveCorpusPerformanceGateTest : LargeCorpusGate(LargeCorpusBaselines.hive)
class KotlinCompilerCorpusPerformanceGateTest : LargeCorpusGate(LargeCorpusBaselines.kotlinCompiler)
