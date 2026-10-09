package io.johnsonlee.graphite.webgraph

/** Runs the selected mapped admission invariant without a JMH timer or resource profiler. */
object MappedAdmissionCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        val state = BudgetedTransformedScanBenchmarkState()
        try {
            state.setupGraph()
            state.resetMatchState()
            state.zeroHit() // Existing assertions require zero matches and exact node-work charging.
        } finally {
            state.tearDownGraph()
        }
        println("CORRECTNESS_PASS\tmapped-admission")
    }
}
