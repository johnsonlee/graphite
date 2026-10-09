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
        val counters = LargeBroadQueryPressureCounters()
        try {
            suite.setupTrial()
            suite.setupInvocation()
            suite.replayBroadQueries(counters)
            writeMetrics(Path.of(args[OUTPUT_INDEX]), suite.indexState, suite.coverageFamily, counters)
        } finally {
            suite.tearDownTrial()
        }
        println("CORRECTNESS_PASS\tgraph-routing\t${args[0]}\t${args[1]}")
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
    private const val OUTPUT_INDEX = 3

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
