package io.johnsonlee.graphite.webgraph

import java.nio.file.Files
import java.nio.file.Path

/** Complete routing assertions, including singleton references, without performance samples. */
object GraphRoutingCorrectness {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == ARGUMENT_COUNT)
        val suite = LargeBroadQueryPressureBenchmark().apply {
            correctnessOnly = true
            graphCount = GRAPH_COUNT
            indexState = args[0]
            coverageFamily = args[1]
            timeoutMillis = args[2].toLong()
        }
        runGraphRoutingCorrectness(args, suite::setupTrial, suite::setupInvocation, suite::replayBroadQueries, suite::tearDownTrial)
    }

    fun writeMetrics(
        output: Path,
        indexState: String,
        coverageFamily: String,
        counters: LargeBroadQueryPressureCounters
    ) {
        val metrics = STRUCTURAL_FIELDS.joinToString(",") { name ->
            "\"$name\":" + counters.javaClass.getField(name).getLong(counters)
        }
        Files.writeString(
            output,
            "{\"scope\":\"correctness-only\",\"indexState\":\"$indexState\"," +
                "\"coverageFamily\":\"$coverageFamily\",\"metrics\":{$metrics}}\n"
        )
    }

    private const val ARGUMENT_COUNT = 4
    private const val GRAPH_COUNT = 64

    private val STRUCTURAL_FIELDS = listOf(
        "graphCount",
        "distinctGraphPathCount",
        "queryCount",
        "successCount",
        "timeoutCount",
        "failureCount",
        "totalRows",
        "graphIdTargetCount",
        "graphParameterTargetCount",
        "coverageShapeCount",
        "coverageFamilyCount",
        "coverageSelectivityCount",
        "coverageProjectionCount",
        "coverageOperatorCount",
        "coverageBoundaryCount",
        "rawStringMatchStateBytes",
        "callSiteIndexAdmittedGraphs",
        "callSiteIndexRetainedBytes",
        "callSiteTrigramIndexedGraphs",
        "requestSelectedSourceQueryCount",
        "cypherGraphIdPredicateQueryCount",
        "inputSourceCount",
        "accessedGraphCount",
        "targetGraphAccessCount",
        "nonTargetGraphAccessCount",
        "graphWorkUnits",
        "graphIdSourceSelections",
        "graphIdSourcePruningExecutions",
        "graphIdSourcesPruned",
        "filteredNodeLimitFastPathExecutions",
        "generalFallbackExecutions",
        "callSiteParallelScanCount",
        "callSiteParallelScanGraphCount",
        "callSiteStringIndexLookupCount",
        "callSiteStringIndexLookupGraphCount",
        "callSiteStringIndexLookupMinPerGraph",
        "callSiteStringIndexLookupMaxPerGraph",
        "callSiteScanPeakActiveWorkers",
        "graphScanPeakActiveWorkers",
        "segmentScanPeakActiveWorkers"
    )
}

/**
 * Publish a correctness artifact only after replay, and release the suite on every exit.
 * Like writeMetrics, this is shared with the separate test compilation, never the application JAR.
 */
fun runGraphRoutingCorrectness(
    args: Array<String>,
    setup: () -> Unit,
    prepare: () -> Unit,
    replay: (LargeBroadQueryPressureCounters) -> Long,
    cleanup: () -> Unit
) {
    val counters = LargeBroadQueryPressureCounters()
    try {
        setup()
        prepare()
        replay(counters)
        GraphRoutingCorrectness.writeMetrics(Path.of(args[ROUTING_OUTPUT_INDEX]), args[0], args[1], counters)
    } finally {
        cleanup()
    }
    println("CORRECTNESS_PASS\tgraph-routing\t${args[0]}\t${args[1]}")
}

private const val ROUTING_OUTPUT_INDEX = 3
