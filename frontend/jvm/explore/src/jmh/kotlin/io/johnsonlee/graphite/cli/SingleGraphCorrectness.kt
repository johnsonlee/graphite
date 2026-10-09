package io.johnsonlee.graphite.cli

import kotlin.reflect.KFunction1

/** Calls the existing behavioral assertions without JMH, timing or resource sampling. */
object SingleGraphCorrectness {
    @JvmStatic
    fun main(args: Array<String>) = execute(args)

    fun execute(
        args: Array<String>,
        prepare: (Array<String>) -> CorrectnessRun<*> = ::prepare,
        report: (String) -> Unit = ::println
    ) {
        prepare(args).execute()
        report("CORRECTNESS_PASS\t${args.joinToString("\t")}")
    }

    fun prepare(args: Array<String>): CorrectnessRun<*> {
        require(args.isNotEmpty()) { "Expected explorer, capacity, or methods" }
        return when (args[0]) {
            "explorer" -> explorer()
            "capacity" -> capacity()
            "methods" -> methods(args)
            else -> error("Unknown correctness suite: ${args[0]}")
        }
    }

    private fun explorer(): CorrectnessRun<ExplorerMemoryCounters> {
        val suite = ExplorerMemoryBenchmark().apply {
            correctnessOnly = true
            loadMode = "MAPPED"
            repeats = 3
            sampledNodeCount = 512
            waterlineWarmupCycles = 32
            waterlineMeasuredCycles = 256
        }
        return CorrectnessRun(suite, ExplorerMemoryCounters(), suite::setup, listOf(
            suite::android_initialExplorerSession,
            suite::android_browserForwardExploration,
            suite::android_longRunningExplorerWaterline,
            suite::android_incomingExplorerWaterline
        ), suite::tearDown)
    }

    private fun capacity(): CorrectnessRun<CypherCapacityBenchmarkCounters> {
        val suite = CypherCapacityBenchmark().apply { correctnessOnly = true }
        return CorrectnessRun(suite, CypherCapacityBenchmarkCounters(), suite::setup,
            listOf(suite::fourWorstCaseQueriesRejectCancelAndRecover), suite::tearDown)
    }

    private fun methods(args: Array<String>): CorrectnessRun<MethodCompatibilityCounters> {
        require(args.size == 3) { "Expected methods <4|17|36> <scenario>" }
        val count = args[1].toInt()
        require(count in listOf(4, 17, 36))
        val suite = MethodDiscoveryCompatibilityBenchmark().apply {
            correctnessOnly = true
            graphCount = count
            scenario = args[2]
        }
        return CorrectnessRun(suite, MethodCompatibilityCounters(), suite::setup,
            listOf(suite::methodScenarioGate), suite::tearDown)
    }
}

/** Separates suite selection from its lifecycle so failures cannot bypass cleanup or publish PASS. */
class CorrectnessRun<C>(
    val suite: Any,
    val counters: C,
    val setup: () -> Unit,
    val checks: List<KFunction1<C, Long>>,
    val tearDown: () -> Unit
) {
    fun execute() {
        try {
            setup()
            checks.forEach { it(counters) }
        } finally {
            tearDown()
        }
    }
}
