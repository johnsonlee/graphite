package io.johnsonlee.graphite.cli

/** Calls the existing behavioral assertions without JMH, timing or resource sampling. */
object SingleGraphCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Expected explorer, capacity, or methods" }
        when (args[0]) {
            "explorer" -> explorer()
            "capacity" -> capacity()
            "methods" -> methods(args)
            else -> error("Unknown correctness suite: ${args[0]}")
        }
        println("CORRECTNESS_PASS\t${args.joinToString("\t")}")
    }

    private fun explorer() {
        val suite = ExplorerMemoryBenchmark().apply {
            correctnessOnly = true
            loadMode = "MAPPED"
            repeats = 3
            sampledNodeCount = 512
            waterlineWarmupCycles = 32
            waterlineMeasuredCycles = 256
        }
        try {
            suite.setup()
            val counters = ExplorerMemoryCounters()
            suite.android_initialExplorerSession(counters)
            suite.android_browserForwardExploration(counters)
            suite.android_longRunningExplorerWaterline(counters)
            suite.android_incomingExplorerWaterline(counters)
        } finally {
            suite.tearDown()
        }
    }

    private fun capacity() {
        val suite = CypherCapacityBenchmark().apply { correctnessOnly = true }
        try {
            suite.setup()
            suite.fourWorstCaseQueriesRejectCancelAndRecover(CypherCapacityBenchmarkCounters())
        } finally {
            suite.tearDown()
        }
    }

    private fun methods(args: Array<String>) {
        require(args.size == 3) { "Expected methods <4|17|36> <scenario>" }
        val count = args[1].toInt()
        require(count in listOf(4, 17, 36))
        val suite = MethodDiscoveryCompatibilityBenchmark().apply {
            correctnessOnly = true
            graphCount = count
            scenario = args[2]
        }
        try {
            suite.setup()
            suite.methodScenarioGate(MethodCompatibilityCounters())
        } finally {
            suite.tearDown()
        }
    }
}
