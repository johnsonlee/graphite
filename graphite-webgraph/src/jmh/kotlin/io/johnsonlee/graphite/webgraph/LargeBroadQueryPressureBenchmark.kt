@file:Suppress("MagicNumber", "ReturnCount", "StringLiteralDuplication", "TooManyFunctions")

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherQueryTimeoutException
import io.johnsonlee.graphite.cypher.CypherResult
import org.openjdk.jmh.annotations.AuxCounters
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.io.Closeable
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * End-to-end pressure benchmark for the broad, unlabeled queries used by multi-graph discovery.
 *
 * One measured operation replays an operator/boolean/selectivity/projection coverage matrix over
 * up to 64 mapped graph handles. The per-query distribution is calculated inside the benchmark;
 * JMH's primary score is the complete replay wall time and must not be mistaken for query P50/P95.
 * This source is intentionally compatible with the pre-change implementation so the benchmark
 * gate can copy the same harness into both revisions before building their JMH jars.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
@Suppress("TooManyFunctions")
open class LargeBroadQueryPressureBenchmark {

    @Param("cold")
    lateinit var indexState: String

    @Param("60000")
    var timeoutMillis: Long = 0

    @Param("64")
    var graphCount: Int = 0

    @Param("all")
    lateinit var coverageFamily: String

    private lateinit var sources: List<CypherGraph>
    private lateinit var queryExecutor: ExecutorService
    private lateinit var sampler: BroadQueryResourceSampler
    private lateinit var workload: List<BroadQueryCase>
    private lateinit var graphPaths: List<Path>
    private lateinit var sourcesById: Map<String, CypherGraph>
    private lateinit var correctnessMode: BroadQueryCorrectnessMode
    private var correctnessOracle: List<QueryCorrectnessRecord>? = null
    private val graphs = mutableListOf<MappedWebGraphBackedGraph>()

    @Setup(Level.Trial)
    fun setupTrial() {
        check(Runtime.getRuntime().maxMemory() in MIN_EIGHT_GIB_HEAP_BYTES..MAX_EIGHT_GIB_HEAP_BYTES) {
            "Large broad-query pressure benchmark must run with -Xmx8g; " +
                "max heap was ${Runtime.getRuntime().maxMemory()} bytes"
        }
        require(timeoutMillis > 0L)
        require(indexState == COLD_INDEX_STATE || indexState == WARM_INDEX_STATE)
        require(graphCount in 1..MAX_GRAPH_COUNT)
        val graphSources = broadQueryGraphSources(graphCount)
        val fullCoverage = broadQueryCoverageWorkload(graphSources)
        workload = when (coverageFamily) {
            ALL_COVERAGE_FAMILIES -> fullCoverage
            GRAPH_ROUTING_COVERAGE_FAMILY -> fullCoverage.filter { case -> case.family.isGraphRouting() }
            else -> {
                require(coverageFamily in BroadQueryFamily.entries.map(BroadQueryFamily::id)) {
                    "Unknown broad-query coverage family: $coverageFamily"
                }
                fullCoverage.filter { case -> case.family.id == coverageFamily }
            }
        }
        check(workload.isNotEmpty())
        check(workload.map(BroadQueryCase::selectivity).toSet() == BroadQuerySelectivity.entries.toSet())
        configureCorrectnessGate()

        graphPaths = graphSources.map(BroadQueryGraphSource::path)
        sources = graphSources.map { source ->
            val graph = GraphStore.loadMapped(source.path)
                as MappedWebGraphBackedGraph
            graphs += graph
            CypherGraph(source.id, graph)
        }
        sourcesById = sources.associateBy(CypherGraph::id)
        queryExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "broad-query-pressure-worker").apply { isDaemon = true }
        }
        sampler = BroadQueryResourceSampler()
    }

    @Setup(Level.Invocation)
    fun setupInvocation() {
        graphs.forEach(MappedWebGraphBackedGraph::clearStringPropertyIndexes)
        resetCallSiteScanMetrics()
        if (indexState == WARM_INDEX_STATE) {
            enforceCorrectness(replay(validateResults = true))
            resetCallSiteScanMetrics()
        }
        forcePressureGc()
        sampler.start()
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        runCatching { queryExecutor.shutdownNow() }
        runCatching { sampler.close() }
        graphs.asReversed().forEach { graph -> runCatching { graph.close() } }
    }

    @Benchmark
    fun replayBroadQueries(counters: LargeBroadQueryPressureCounters): Long {
        val before = sampler.current()
        val beforeCpu = processCpuTimeNanos()
        val beforeGc = gcSnapshot()
        val started = System.nanoTime()
        val samples = replay(validateResults = true)
        val wallNanos = System.nanoTime() - started
        val afterGc = gcSnapshot()
        val processCpuNanos = (processCpuTimeNanos() - beforeCpu).coerceAtLeast(0L)
        val peak = sampler.stop()
        val after = sampler.current()

        populateCounters(counters, samples, before, peak, after, beforeGc, afterGc, processCpuNanos, wallNanos)
        writeCorrectnessManifest(samples)
        writeObservations(samples)
        enforceCorrectness(samples)
        return samples.sumOf(BroadQuerySample::responseBytes) + samples.sumOf(BroadQuerySample::rowCount)
    }

    private fun configureCorrectnessGate() {
        correctnessMode = BroadQueryCorrectnessMode.parse(
            System.getProperty(CORRECTNESS_MODE_PROPERTY) ?: BroadQueryCorrectnessMode.VERIFY.id
        )
        val workloadIds = workload.mapTo(mutableSetOf(), BroadQueryCase::id)
        correctnessOracle = when (correctnessMode) {
            BroadQueryCorrectnessMode.RECORD -> {
                require(!System.getProperty(OUTPUT_PROPERTY).isNullOrBlank()) {
                    "Correctness record mode requires -D$OUTPUT_PROPERTY=<output-manifest>"
                }
                null
            }
            BroadQueryCorrectnessMode.VERIFY -> {
                val configured = requireNotNull(System.getProperty(CORRECTNESS_ORACLE_PROPERTY)) {
                    "Correctness is a hard gate: provide -D$CORRECTNESS_ORACLE_PROPERTY=<oracle-manifest>; " +
                        "use -D$CORRECTNESS_MODE_PROPERTY=record only to create a complete oracle"
                }
                val path = Path.of(configured).toAbsolutePath().normalize()
                QueryCorrectnessManifest.selectCompleteOracle(
                    QueryCorrectnessManifest.read(path),
                    workloadIds,
                    path.toString()
                )
            }
        }
    }

    private fun enforceCorrectness(samples: List<BroadQuerySample>) {
        val records = samples.map(BroadQuerySample::correctnessRecord)
        when (correctnessMode) {
            BroadQueryCorrectnessMode.RECORD -> QueryCorrectnessManifest.requireRecordable(
                records,
                workload.mapTo(mutableSetOf(), BroadQueryCase::id)
            )
            BroadQueryCorrectnessMode.VERIFY -> QueryCorrectnessManifest.verify(
                checkNotNull(correctnessOracle),
                records
            )
        }
    }

    private fun replay(validateResults: Boolean): List<BroadQuerySample> = workload.map { case ->
        val started = System.nanoTime()
        val cancellation = CypherCancellationSignal()
        val context = CypherExecutionContext(CypherExecutionBudget(Long.MAX_VALUE), cancellation)
        val task = queryExecutor.submit(Callable {
            val executionSources = case.requestGraphId?.let { graphId ->
                listOf(checkNotNull(sourcesById[graphId]) { "Requested graph is not loaded: $graphId" })
            } ?: sources
            CrossGraphCypherExecutor(executionSources, context).execute(case.query, case.parameters)
        })
        try {
            val result = task.get(case.timeoutMillis(timeoutMillis), TimeUnit.MILLISECONDS)
            BroadQuerySample(
                case = case,
                latencyNanos = System.nanoTime() - started,
                outcome = BroadQueryOutcome.SUCCESS,
                rowCount = result.rows.size.toLong(),
                responseBytes = canonicalResult(result).length.toLong(),
                digest = digest(result)
            ).also { sample ->
                if (validateResults && case.expectZeroRows) check(sample.rowCount == 0L) {
                    "${case.id} expected zero rows, got ${sample.rowCount}"
                }
                if (validateResults && case.expectedRowCountRange != null) {
                    check(sample.rowCount in case.expectedRowCountRange) {
                        "${case.id} expected ${case.expectedRowCountRange} rows, got ${sample.rowCount}"
                    }
                }
            }
        } catch (_: TimeoutException) {
            val effectiveTimeoutMillis = case.timeoutMillis(timeoutMillis)
            cancellation.cancel(CypherQueryTimeoutException(effectiveTimeoutMillis))
            task.cancel(true)
            awaitCancellation()
            BroadQuerySample(
                case,
                TimeUnit.MILLISECONDS.toNanos(effectiveTimeoutMillis),
                BroadQueryOutcome.TIMEOUT,
                0L,
                0L,
                TIMEOUT_DIGEST
            )
        } catch (error: ExecutionException) {
            BroadQuerySample(
                case,
                System.nanoTime() - started,
                BroadQueryOutcome.FAILED,
                0L,
                0L,
                error.cause?.javaClass?.name ?: error.javaClass.name
            )
        }
    }

    private fun awaitCancellation() {
        val barrier = queryExecutor.submit(Callable { true })
        check(barrier.get(CANCELLATION_GRACE_SECONDS, TimeUnit.SECONDS)) {
            "Timed-out broad query did not leave the pressure worker"
        }
    }

    @Suppress("LongParameterList")
    private fun populateCounters(
        counters: LargeBroadQueryPressureCounters,
        samples: List<BroadQuerySample>,
        before: BroadQueryResourceSample,
        peak: BroadQueryResourceSample,
        after: BroadQueryResourceSample,
        beforeGc: GcSnapshot,
        afterGc: GcSnapshot,
        processCpuNanos: Long,
        wallNanos: Long
    ) {
        val latencies = samples.map(BroadQuerySample::latencyNanos).sorted()
        counters.graphCount = graphCount.toLong()
        counters.distinctGraphPathCount = graphPaths.map { it.toAbsolutePath().normalize() }.toSet().size.toLong()
        counters.queryCount = samples.size.toLong()
        counters.successCount = samples.count { it.outcome == BroadQueryOutcome.SUCCESS }.toLong()
        counters.timeoutCount = samples.count { it.outcome == BroadQueryOutcome.TIMEOUT }.toLong()
        counters.failureCount = samples.count { it.outcome == BroadQueryOutcome.FAILED }.toLong()
        counters.totalRows = samples.sumOf(BroadQuerySample::rowCount)
        counters.p50LatencyNanos = percentile(latencies, 0.50)
        counters.p95LatencyNanos = percentile(latencies, 0.95)
        counters.maxLatencyNanos = latencies.last()
        counters.zeroP50LatencyNanos = groupedPercentile(samples, 0.50) {
            it.case.selectivity == BroadQuerySelectivity.ZERO
        }
        counters.zeroP95LatencyNanos = groupedPercentile(samples, 0.95) {
            it.case.selectivity == BroadQuerySelectivity.ZERO
        }
        counters.targetedP50LatencyNanos = groupedPercentile(samples, 0.50) {
            it.case.selectivity == BroadQuerySelectivity.TARGETED
        }
        counters.targetedP95LatencyNanos = groupedPercentile(samples, 0.95) {
            it.case.selectivity == BroadQuerySelectivity.TARGETED
        }
        counters.denseP50LatencyNanos = groupedPercentile(samples, 0.50) {
            it.case.selectivity == BroadQuerySelectivity.DENSE
        }
        counters.denseP95LatencyNanos = groupedPercentile(samples, 0.95) {
            it.case.selectivity == BroadQuerySelectivity.DENSE
        }
        counters.containsP95LatencyNanos = familyP95(samples, BroadQueryFamily.CONTAINS)
        counters.booleanP95LatencyNanos = familyP95(samples, BroadQueryFamily.BOOLEAN)
        counters.exactP95LatencyNanos = familyP95(samples, BroadQueryFamily.EXACT)
        counters.wrappedP95LatencyNanos = familyP95(samples, BroadQueryFamily.WRAPPED)
        counters.projectionP95LatencyNanos = familyP95(samples, BroadQueryFamily.PROJECTION)
        counters.aggregationP95LatencyNanos = familyP95(samples, BroadQueryFamily.AGGREGATION)
        counters.globalP95LatencyNanos = familyP95(samples, BroadQueryFamily.GLOBAL)
        counters.regexP95LatencyNanos = familyP95(samples, BroadQueryFamily.REGEX)
        counters.graphIdP95LatencyNanos = familyP95(samples, BroadQueryFamily.GRAPH_ID)
        counters.graphParameterP95LatencyNanos = familyP95(samples, BroadQueryFamily.GRAPH_PARAMETER)
        counters.graphIdTargetCount = samples.asSequence()
            .filter { sample -> sample.case.family == BroadQueryFamily.GRAPH_ID }
            .mapNotNull { sample -> sample.case.targetGraphId }
            .toSet()
            .size
            .toLong()
        counters.graphParameterTargetCount = samples.asSequence()
            .filter { sample -> sample.case.family == BroadQueryFamily.GRAPH_PARAMETER }
            .mapNotNull { sample -> sample.case.targetGraphId }
            .toSet()
            .size
            .toLong()
        counters.coverageShapeCount = workload.map(BroadQueryCase::shape).toSet().size.toLong()
        counters.coverageFamilyCount = workload.map(BroadQueryCase::family).toSet().size.toLong()
        counters.coverageSelectivityCount = workload.map(BroadQueryCase::selectivity).toSet().size.toLong()
        counters.coverageProjectionCount = workload.map(BroadQueryCase::projection).toSet().size.toLong()
        counters.coverageOperatorCount = workload.map(BroadQueryCase::operator).toSet().size.toLong()
        counters.coverageBoundaryCount = workload.map(BroadQueryCase::boundary).toSet().size.toLong()
        counters.wallNanos = wallNanos
        counters.processCpuNanos = processCpuNanos
        counters.cpuCoreUtilizationPermille = if (wallNanos == 0L) 0L else processCpuNanos * 1_000L / wallNanos
        counters.usedHeapBeforeBytes = before.usedHeapBytes
        counters.peakUsedHeapBytes = peak.usedHeapBytes
        counters.usedHeapAfterBytes = after.usedHeapBytes
        counters.peakCommittedHeapBytes = peak.committedHeapBytes
        counters.maxHeapBytes = Runtime.getRuntime().maxMemory()
        counters.residentSetBeforeBytes = before.residentSetBytes
        counters.peakResidentSetBytes = peak.residentSetBytes
        counters.residentSetAfterBytes = after.residentSetBytes
        counters.peakProcessCpuLoadPermille = peak.processCpuLoadPermille
        counters.gcCount = (afterGc.count - beforeGc.count).coerceAtLeast(0L)
        counters.gcMillis = (afterGc.millis - beforeGc.millis).coerceAtLeast(0L)
        counters.rawStringMatchStateBytes = graphs.sumOf(MappedWebGraphBackedGraph::rawStringMatchStateBytes)
        val indexMetrics = callSiteIndexMetrics()
        counters.callSiteIndexAdmittedGraphs = indexMetrics.first
        counters.callSiteIndexRetainedBytes = indexMetrics.second
        counters.callSiteTrigramIndexedGraphs = graphs.count { graph ->
            invokeInternalMetric(graph, "isCallSiteTrigramIndexInitialized") == true
        }.toLong()
        counters.callSiteParallelScanCount = graphs.sumOf { graph ->
            (invokeInternalMetric(graph, "callSiteParallelScanCount") as? Number)?.toLong() ?: 0L
        }
        counters.callSiteParallelScanGraphCount = graphs.count { graph ->
            ((invokeInternalMetric(graph, "callSiteParallelScanCount") as? Number)?.toLong() ?: 0L) > 0L
        }.toLong()
        val indexLookupCounts = graphs.map { graph ->
            (invokeInternalMetric(graph, "callSiteStringIndexLookupCount") as? Number)?.toLong() ?: 0L
        }
        counters.callSiteStringIndexLookupCount = indexLookupCounts.sum()
        counters.callSiteStringIndexLookupGraphCount = indexLookupCounts.count { count -> count > 0L }.toLong()
        counters.callSiteStringIndexLookupMinPerGraph = indexLookupCounts.minOrNull() ?: 0L
        counters.callSiteStringIndexLookupMaxPerGraph = indexLookupCounts.maxOrNull() ?: 0L
        counters.callSiteScanPeakActiveWorkers = graphs.maxOfOrNull { graph ->
            (invokeInternalMetric(graph, "callSiteScanPeakActiveWorkers") as? Number)?.toLong() ?: 0L
        } ?: 0L
    }

    private fun familyP95(samples: List<BroadQuerySample>, family: BroadQueryFamily): Long =
        groupedPercentile(samples, 0.95) { it.case.family == family }

    private fun groupedPercentile(
        samples: List<BroadQuerySample>,
        fraction: Double,
        predicate: (BroadQuerySample) -> Boolean
    ): Long = samples.asSequence()
        .filter(predicate)
        .map(BroadQuerySample::latencyNanos)
        .sorted()
        .toList()
        .let { latencies -> if (latencies.isEmpty()) 0L else percentile(latencies, fraction) }

    private fun callSiteIndexMetrics(): Pair<Long, Long> {
        var admitted = 0L
        var bytes = 0L
        graphs.forEach { graph ->
            val initialized = invokeInternalMetric(graph, "isCallSiteStringIndexInitialized") as? Boolean
            val retained = invokeInternalMetric(graph, "callSiteStringIndexBytes") as? Number
            if (initialized == true) admitted++
            bytes += retained?.toLong() ?: 0L
        }
        return admitted to bytes
    }

    private fun resetCallSiteScanMetrics() {
        graphs.forEach { graph -> invokeInternalMetric(graph, "resetCallSiteScanMetrics") }
    }

    private fun invokeInternalMetric(graph: MappedWebGraphBackedGraph, prefix: String): Any? = runCatching {
        graph.javaClass.declaredMethods.firstOrNull { method ->
            method.parameterCount == 0 && method.name.startsWith(prefix)
        }?.let { method ->
            method.isAccessible = true
            method.invoke(graph)
        }
    }.getOrNull()

    private fun writeCorrectnessManifest(samples: List<BroadQuerySample>) {
        val configured = System.getProperty(OUTPUT_PROPERTY) ?: return
        QueryCorrectnessManifest.write(
            Path.of(configured),
            samples.map(BroadQuerySample::correctnessRecord)
        )
    }

    private fun writeObservations(samples: List<BroadQuerySample>) {
        val configured = System.getProperty(OBSERVATIONS_OUTPUT_PROPERTY) ?: return
        val header = listOf(
            "id",
            "family",
            "shape",
            "selectivity",
            "operator",
            "boundary",
            "projection",
            "limit",
            "targetGraphId",
            "outcome",
            "rowCount",
            "responseBytes",
            "digest",
            "latencyNanos"
        ).joinToString("\t")
        val lines = samples.joinToString("\n", prefix = "$header\n", postfix = "\n") { sample ->
            listOf(
                sample.case.id,
                sample.case.family.id,
                sample.case.shape,
                sample.case.selectivity.id,
                sample.case.operator,
                sample.case.boundary,
                sample.case.projection,
                sample.case.limit,
                sample.case.targetGraphId.orEmpty(),
                sample.outcome.name.lowercase(),
                sample.rowCount,
                sample.responseBytes,
                sample.digest,
                sample.latencyNanos
            ).joinToString("\t")
        }
        Files.writeString(Path.of(configured), lines)
    }

    private fun digest(result: CypherResult): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalResult(result).toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun canonicalResult(result: CypherResult): String = buildString {
        append(result.columns.joinToString(","))
        result.rows.forEach { row ->
            append('\n')
            append(row.entries.sortedBy(Map.Entry<String, Any?>::key).joinToString(",") { (key, value) ->
                "$key=${canonical(value)}"
            })
        }
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
            .joinToString(prefix = "{", postfix = "}") { (key, nested) -> "$key:${canonical(nested)}" }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        else -> value.toString()
    }
}

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
open class LargeBroadQueryPressureCounters {
    @JvmField var graphCount: Long = 0
    @JvmField var distinctGraphPathCount: Long = 0
    @JvmField var queryCount: Long = 0
    @JvmField var successCount: Long = 0
    @JvmField var timeoutCount: Long = 0
    @JvmField var failureCount: Long = 0
    @JvmField var totalRows: Long = 0
    @JvmField var p50LatencyNanos: Long = 0
    @JvmField var p95LatencyNanos: Long = 0
    @JvmField var maxLatencyNanos: Long = 0
    @JvmField var zeroP50LatencyNanos: Long = 0
    @JvmField var zeroP95LatencyNanos: Long = 0
    @JvmField var targetedP50LatencyNanos: Long = 0
    @JvmField var targetedP95LatencyNanos: Long = 0
    @JvmField var denseP50LatencyNanos: Long = 0
    @JvmField var denseP95LatencyNanos: Long = 0
    @JvmField var containsP95LatencyNanos: Long = 0
    @JvmField var booleanP95LatencyNanos: Long = 0
    @JvmField var exactP95LatencyNanos: Long = 0
    @JvmField var wrappedP95LatencyNanos: Long = 0
    @JvmField var projectionP95LatencyNanos: Long = 0
    @JvmField var aggregationP95LatencyNanos: Long = 0
    @JvmField var globalP95LatencyNanos: Long = 0
    @JvmField var regexP95LatencyNanos: Long = 0
    @JvmField var graphIdP95LatencyNanos: Long = 0
    @JvmField var graphParameterP95LatencyNanos: Long = 0
    @JvmField var graphIdTargetCount: Long = 0
    @JvmField var graphParameterTargetCount: Long = 0
    @JvmField var coverageShapeCount: Long = 0
    @JvmField var coverageFamilyCount: Long = 0
    @JvmField var coverageSelectivityCount: Long = 0
    @JvmField var coverageProjectionCount: Long = 0
    @JvmField var coverageOperatorCount: Long = 0
    @JvmField var coverageBoundaryCount: Long = 0
    @JvmField var wallNanos: Long = 0
    @JvmField var processCpuNanos: Long = 0
    @JvmField var cpuCoreUtilizationPermille: Long = 0
    @JvmField var usedHeapBeforeBytes: Long = 0
    @JvmField var peakUsedHeapBytes: Long = 0
    @JvmField var usedHeapAfterBytes: Long = 0
    @JvmField var peakCommittedHeapBytes: Long = 0
    @JvmField var maxHeapBytes: Long = 0
    @JvmField var residentSetBeforeBytes: Long = 0
    @JvmField var peakResidentSetBytes: Long = 0
    @JvmField var residentSetAfterBytes: Long = 0
    @JvmField var peakProcessCpuLoadPermille: Long = 0
    @JvmField var gcCount: Long = 0
    @JvmField var gcMillis: Long = 0
    @JvmField var rawStringMatchStateBytes: Long = 0
    @JvmField var callSiteIndexAdmittedGraphs: Long = 0
    @JvmField var callSiteIndexRetainedBytes: Long = 0
    @JvmField var callSiteTrigramIndexedGraphs: Long = 0
    @JvmField var callSiteParallelScanCount: Long = 0
    @JvmField var callSiteParallelScanGraphCount: Long = 0
    @JvmField var callSiteStringIndexLookupCount: Long = 0
    @JvmField var callSiteStringIndexLookupGraphCount: Long = 0
    @JvmField var callSiteStringIndexLookupMinPerGraph: Long = 0
    @JvmField var callSiteStringIndexLookupMaxPerGraph: Long = 0
    @JvmField var callSiteScanPeakActiveWorkers: Long = 0
}

private data class BroadQueryCase(
    val id: String,
    val family: BroadQueryFamily,
    val shape: String,
    val selectivity: BroadQuerySelectivity,
    val operator: String,
    val boundary: String,
    val projection: String,
    val limit: Int,
    val query: String,
    val parameters: Map<String, Any?>,
    val expectZeroRows: Boolean,
    val targetGraphId: String? = null,
    val requestGraphId: String? = null,
    val expectedRowCountRange: LongRange? = null,
    val configuredTimeoutMillis: Long? = null
) {
    fun timeoutMillis(defaultMillis: Long): Long = configuredTimeoutMillis ?: defaultMillis
}

private data class BroadQuerySample(
    val case: BroadQueryCase,
    val latencyNanos: Long,
    val outcome: BroadQueryOutcome,
    val rowCount: Long,
    val responseBytes: Long,
    val digest: String
) {
    fun correctnessRecord(): QueryCorrectnessRecord = QueryCorrectnessRecord(
        id = case.id,
        family = case.family.id,
        shape = case.shape,
        selectivity = case.selectivity.id,
        operator = case.operator,
        boundary = case.boundary,
        projection = case.projection,
        limit = case.limit.toLong(),
        outcome = outcome.name.lowercase(),
        rowCount = rowCount,
        responseBytes = responseBytes,
        digest = digest
    )
}

private enum class BroadQueryOutcome { SUCCESS, TIMEOUT, FAILED }

private enum class BroadQueryCorrectnessMode(val id: String) {
    RECORD("record"),
    VERIFY("verify");

    companion object {
        fun parse(value: String): BroadQueryCorrectnessMode = entries.singleOrNull { it.id == value }
            ?: throw IllegalArgumentException(
                "Unknown correctness mode '$value'; expected ${entries.joinToString { it.id }}"
            )
    }
}

private enum class BroadQueryFamily(val id: String) {
    CONTAINS("contains"),
    BOOLEAN("boolean"),
    EXACT("exact"),
    WRAPPED("wrapped"),
    PROJECTION("projection"),
    AGGREGATION("aggregation"),
    GLOBAL("global"),
    REGEX("regex"),
    GRAPH_ID("graph-id"),
    GRAPH_PARAMETER("graph-parameter");

    fun isGraphRouting(): Boolean = this == GRAPH_ID || this == GRAPH_PARAMETER
}

private enum class BroadQuerySelectivity(val id: String) {
    ZERO("zero"),
    TARGETED("targeted"),
    DENSE("dense")
}

private data class BroadQueryResourceSample(
    val usedHeapBytes: Long,
    val committedHeapBytes: Long,
    val residentSetBytes: Long,
    val processCpuLoadPermille: Long
) {
    fun maximum(other: BroadQueryResourceSample): BroadQueryResourceSample = BroadQueryResourceSample(
        maxOf(usedHeapBytes, other.usedHeapBytes),
        maxOf(committedHeapBytes, other.committedHeapBytes),
        maxOf(residentSetBytes, other.residentSetBytes),
        maxOf(processCpuLoadPermille, other.processCpuLoadPermille)
    )
}

private class BroadQueryResourceSampler : Closeable {
    private val running = AtomicBoolean(true)
    private val sampling = AtomicBoolean(false)
    private val maximumUsedHeap = AtomicLong()
    private val maximumCommittedHeap = AtomicLong()
    private val maximumResidentSet = AtomicLong()
    private val maximumProcessCpuLoad = AtomicLong()
    private val thread = Thread({ sampleLoop() }, "broad-query-resource-sampler").apply {
        isDaemon = true
        start()
    }

    fun start() {
        val initial = current()
        maximumUsedHeap.set(initial.usedHeapBytes)
        maximumCommittedHeap.set(initial.committedHeapBytes)
        maximumResidentSet.set(initial.residentSetBytes)
        maximumProcessCpuLoad.set(initial.processCpuLoadPermille)
        sampling.set(true)
    }

    fun stop(): BroadQueryResourceSample {
        sampling.set(false)
        record(current())
        return BroadQueryResourceSample(
            maximumUsedHeap.get(),
            maximumCommittedHeap.get(),
            maximumResidentSet.get(),
            maximumProcessCpuLoad.get()
        )
    }

    fun current(): BroadQueryResourceSample {
        val runtime = Runtime.getRuntime()
        return BroadQueryResourceSample(
            runtime.totalMemory() - runtime.freeMemory(),
            runtime.totalMemory(),
            residentSetBytes(),
            processCpuLoadPermille()
        )
    }

    override fun close() {
        sampling.set(false)
        running.set(false)
        thread.join(SAMPLER_JOIN_MILLIS)
    }

    private fun sampleLoop() {
        var samples = 0
        while (running.get()) {
            if (sampling.get()) {
                val runtime = Runtime.getRuntime()
                maximumUsedHeap.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                maximumCommittedHeap.accumulateAndGet(runtime.totalMemory(), ::maxOf)
                maximumProcessCpuLoad.accumulateAndGet(processCpuLoadPermille(), ::maxOf)
                if (samples++ % RSS_SAMPLE_DIVISOR == 0) {
                    maximumResidentSet.accumulateAndGet(residentSetBytes(), ::maxOf)
                }
            }
            LockSupport.parkNanos(SAMPLER_INTERVAL_NANOS)
        }
    }

    private fun record(sample: BroadQueryResourceSample) {
        maximumUsedHeap.accumulateAndGet(sample.usedHeapBytes, ::maxOf)
        maximumCommittedHeap.accumulateAndGet(sample.committedHeapBytes, ::maxOf)
        maximumResidentSet.accumulateAndGet(sample.residentSetBytes, ::maxOf)
        maximumProcessCpuLoad.accumulateAndGet(sample.processCpuLoadPermille, ::maxOf)
    }
}

private data class GcSnapshot(val count: Long, val millis: Long)

private fun gcSnapshot(): GcSnapshot = GcSnapshot(
    ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0L) },
    ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0L) }
)

private fun processCpuTimeNanos(): Long =
    (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.processCpuTime
        ?.coerceAtLeast(0L)
        ?: 0L

private fun processCpuLoadPermille(): Long {
    val load = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.processCpuLoad
        ?: return 0L
    return if (load < 0.0) 0L else (load * 1_000.0).toLong()
}

private fun residentSetBytes(): Long {
    val status = Path.of("/proc/self/status")
    if (Files.isRegularFile(status)) {
        val kibibytes = Files.readAllLines(status)
            .firstOrNull { line -> line.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))
            ?.firstNotNullOfOrNull(String::toLongOrNull)
        if (kibibytes != null) return kibibytes * BYTES_PER_KIBIBYTE
    }
    val process = ProcessBuilder(
        "ps", "-o", "rss=", "-p", ProcessHandle.current().pid().toString()
    ).redirectErrorStream(true).start()
    if (!process.waitFor(PS_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) return 0L
    return process.inputStream.bufferedReader().use { reader ->
        reader.readText().trim().toLongOrNull()?.times(BYTES_PER_KIBIBYTE) ?: 0L
    }
}

private fun percentile(sorted: List<Long>, fraction: Double): Long {
    require(sorted.isNotEmpty() && fraction in 0.0..1.0)
    return sorted[(ceil(fraction * sorted.size).toInt() - 1).coerceAtLeast(0)]
}

private fun forcePressureGc() {
    repeat(GC_ATTEMPTS) {
        System.gc()
        System.runFinalization()
        Thread.sleep(GC_PAUSE_MILLIS)
    }
}

private data class BroadQueryTerms(
    val term: String,
    val exactClass: String,
    val exactName: String,
    val absent: String,
    val graphId: String
)

private data class BroadQueryCoverageSpec(
    val id: String,
    val family: BroadQueryFamily,
    val projection: String,
    val limit: Int,
    val operator: String = "contains",
    val boundary: String = "single-query",
    val zeroReturnsNoRows: Boolean = true,
    val parameters: (BroadQueryTerms) -> Map<String, Any?> = { emptyMap() },
    val build: (BroadQueryTerms) -> String
)

private data class BroadQueryGraphSource(
    val id: String,
    val path: Path,
    val routingTerms: Map<BroadQuerySelectivity, String>
)

private fun broadQueryGraphSources(graphCount: Int): List<BroadQueryGraphSource> {
    val configured = requireNotNull(System.getProperty(GRAPH_MANIFEST_PROPERTY)) {
        "Real persisted graphs are required for performance evidence: " +
            "provide -D$GRAPH_MANIFEST_PROPERTY=<manifest>"
    }
    val manifest = Path.of(configured).toAbsolutePath().normalize()
    require(Files.isRegularFile(manifest)) { "Broad-query graph manifest not found at $manifest" }
    val sources = Files.readAllLines(manifest).mapIndexedNotNull { lineIndex, rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith('#')) return@mapIndexedNotNull null
        val columns = line.split('\t')
        require(columns.size == GRAPH_MANIFEST_COLUMN_COUNT && columns.all(String::isNotBlank)) {
            "$manifest:${lineIndex + 1} must contain <graph-id><TAB><persisted-graph-path>" +
                "<TAB><zero-term><TAB><targeted-term><TAB><dense-term>"
        }
        val path = Path.of(columns[1].trim()).toRealPath()
        require(Files.isDirectory(path)) { "$manifest:${lineIndex + 1} graph path not found: $path" }
        val terms = BroadQuerySelectivity.entries.zip(columns.drop(2).map(String::trim)).toMap()
        require(terms.values.map(String::lowercase).toSet().size == BroadQuerySelectivity.entries.size) {
            "$manifest:${lineIndex + 1} zero, targeted, and dense terms must be distinct"
        }
        BroadQueryGraphSource(columns[0].trim(), path, terms)
    }
    require(sources.size == graphCount) {
        "$manifest contains ${sources.size} graphs; graphCount=$graphCount requires exactly $graphCount"
    }
    require(sources.map(BroadQueryGraphSource::id).toSet().size == sources.size) {
        "$manifest contains duplicate graph ids"
    }
    require(sources.map(BroadQueryGraphSource::path).toSet().size == sources.size) {
        "$manifest must reference $graphCount distinct persisted graph paths; repeated fixtures are not " +
            "valid performance evidence"
    }
    return sources
}

private data class BroadQueryTarget(
    val graphId: String,
    val graphIndex: Int,
    val selectivity: BroadQuerySelectivity,
    val routingTerm: String? = null
)

private fun broadQueryCoverageWorkload(graphSources: List<BroadQueryGraphSource>): List<BroadQueryCase> = buildList {
    require(graphSources.isNotEmpty())
    BROAD_QUERY_COVERAGE.forEachIndexed { shapeIndex, spec ->
        val targets = if (spec.family.isGraphRouting()) {
            graphSources.flatMapIndexed { graphIndex, source ->
                BroadQuerySelectivity.entries.map { selectivity ->
                    BroadQueryTarget(
                        source.id,
                        graphIndex,
                        selectivity,
                        source.routingTerms.getValue(selectivity)
                    )
                }
            }
        } else {
            BroadQuerySelectivity.entries.map { selectivity ->
                BroadQueryTarget(graphSources.last().id, graphSources.lastIndex, selectivity)
            }
        }
        targets.forEach { target ->
            val targetGraphId = target.graphId
            val selectivity = target.selectivity
            val termIndex = if (spec.family.isGraphRouting()) target.graphIndex else shapeIndex
            val absent = if (spec.family.isGraphRouting()) {
                checkNotNull(target.routingTerm)
            } else {
                "GraphitePressureAbsent${shapeIndex.toString().padStart(2, '0')}${selectivity.id}X"
            }
            val term = target.routingTerm ?: when (selectivity) {
                BroadQuerySelectivity.ZERO -> absent
                BroadQuerySelectivity.TARGETED -> TARGETED_TERMS[termIndex % TARGETED_TERMS.size]
                BroadQuerySelectivity.DENSE -> DENSE_TERMS[termIndex % DENSE_TERMS.size]
            }
            val exactClass = when (selectivity) {
                BroadQuerySelectivity.ZERO -> "com.graphite.pressure.Absent${shapeIndex.toString().padStart(2, '0')}"
                BroadQuerySelectivity.TARGETED -> TARGETED_EXACT_CLASSES[shapeIndex % TARGETED_EXACT_CLASSES.size]
                BroadQuerySelectivity.DENSE -> DENSE_EXACT_CLASSES[shapeIndex % DENSE_EXACT_CLASSES.size]
            }
            val exactName = when (selectivity) {
                BroadQuerySelectivity.ZERO -> absent
                BroadQuerySelectivity.TARGETED -> TARGETED_EXACT_NAMES[shapeIndex % TARGETED_EXACT_NAMES.size]
                BroadQuerySelectivity.DENSE -> DENSE_EXACT_NAMES[shapeIndex % DENSE_EXACT_NAMES.size]
            }
            val terms = BroadQueryTerms(term, exactClass, exactName, absent, targetGraphId)
            val targetSuffix = if (spec.family.isGraphRouting()) {
                "-target-${target.graphIndex.toString().padStart(2, '0')}"
            } else {
                ""
            }
            add(
                BroadQueryCase(
                    id = "${spec.id}$targetSuffix-${selectivity.id}",
                    family = spec.family,
                    shape = spec.id,
                    selectivity = selectivity,
                    operator = spec.operator,
                    boundary = spec.boundary,
                    projection = spec.projection,
                    limit = spec.limit,
                    query = spec.build(terms),
                    parameters = spec.parameters(terms),
                    expectZeroRows = spec.zeroReturnsNoRows && selectivity == BroadQuerySelectivity.ZERO,
                    targetGraphId = targetGraphId.takeIf { spec.family.isGraphRouting() },
                    requestGraphId = targetGraphId.takeIf { spec.family == BroadQueryFamily.GRAPH_PARAMETER },
                    expectedRowCountRange = if (spec.family == BroadQueryFamily.GRAPH_PARAMETER) {
                        selectivity.expectedReferenceRows()
                    } else {
                        null
                    }
                )
            )
        }
    }
}

private fun BroadQuerySelectivity.expectedReferenceRows(): LongRange = when (this) {
    BroadQuerySelectivity.ZERO -> 0L..0L
    BroadQuerySelectivity.TARGETED -> 1L until ROUTING_RESULT_LIMIT
    BroadQuerySelectivity.DENSE -> ROUTING_RESULT_LIMIT..ROUTING_RESULT_LIMIT
}

private fun singleContains(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term'
    RETURN n.caller_class AS callerClass
    LIMIT 200
""".trimIndent()

private fun classContainsOr(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.callee_class CONTAINS '$term'
    RETURN DISTINCT n.caller_class AS callerClass, n.caller_name AS callerName,
        n.callee_class AS calleeClass, n.callee_name AS calleeName
    LIMIT 200
""".trimIndent()

private fun nameContainsOr(term: String): String = """
    MATCH (n)
    WHERE n.caller_name CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN n.caller_class AS callerClass, n.caller_name AS callerName,
        n.callee_class AS calleeClass, n.callee_name AS calleeName
    LIMIT 200
""".trimIndent()

private fun fourPropertyOr(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN n.graphId, n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun labeledFourPropertyOr(term: String): String = """
    MATCH (n:CallSiteNode)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun rawStartsOr(term: String): String = """
    MATCH (n)
    WHERE n.caller_class STARTS WITH '$term' OR n.callee_class STARTS WITH '$term'
    RETURN n.caller_class, n.callee_class
    LIMIT 100
""".trimIndent()

private fun rawEndsOr(term: String): String = """
    MATCH (n)
    WHERE n.caller_name ENDS WITH '$term' OR n.callee_name ENDS WITH '$term'
    RETURN n.caller_name, n.callee_name
    LIMIT 100
""".trimIndent()

private fun wrappedStartsOr(term: String): String = """
    MATCH (n)
    WHERE toLower(coalesce(n.caller_class, '')) STARTS WITH '${term.lowercase()}'
       OR toLower(coalesce(n.callee_class, '')) STARTS WITH '${term.lowercase()}'
    RETURN DISTINCT n.caller_class, n.callee_class
    LIMIT 100
""".trimIndent()

private fun wrappedEndsOr(term: String): String = """
    MATCH (n)
    WHERE toLower(coalesce(n.caller_name, '')) ENDS WITH '${term.lowercase()}'
       OR toLower(coalesce(n.callee_name, '')) ENDS WITH '${term.lowercase()}'
    RETURN DISTINCT n.caller_name, n.callee_name
    LIMIT 100
""".trimIndent()

private fun wrappedContainsOr(term: String): String = """
    MATCH (n)
    WHERE toLower(coalesce(n.caller_class, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.caller_name, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.callee_class, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.callee_name, '')) CONTAINS '${term.lowercase()}'
    RETURN DISTINCT n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun graphIdPropertyWrappedContains(graphId: String, term: String): String = """
    MATCH (n)
    WHERE n.graphId = '${cypherString(graphId)}'
      AND (toLower(coalesce(n.caller_class, '')) CONTAINS '${cypherString(term.lowercase())}'
        OR toLower(coalesce(n.caller_name, '')) CONTAINS '${cypherString(term.lowercase())}'
        OR toLower(coalesce(n.callee_class, '')) CONTAINS '${cypherString(term.lowercase())}'
        OR toLower(coalesce(n.callee_name, '')) CONTAINS '${cypherString(term.lowercase())}')
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun graphIdFunctionWrappedContains(graphId: String, term: String): String = """
    MATCH (n)
    WHERE graphId(n) = '${cypherString(graphId)}'
      AND (toLower(coalesce(n.caller_class, '')) CONTAINS '${cypherString(term.lowercase())}'
        OR toLower(coalesce(n.caller_name, '')) CONTAINS '${cypherString(term.lowercase())}'
        OR toLower(coalesce(n.callee_class, '')) CONTAINS '${cypherString(term.lowercase())}'
        OR toLower(coalesce(n.callee_name, '')) CONTAINS '${cypherString(term.lowercase())}')
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun parameterizedGraphIdWrappedContains(): String = """
    MATCH (n)
    WHERE n.graphId = ${'$'}graphId
      AND (toLower(coalesce(n.caller_class, '')) CONTAINS ${'$'}term
        OR toLower(coalesce(n.caller_name, '')) CONTAINS ${'$'}term
        OR toLower(coalesce(n.callee_class, '')) CONTAINS ${'$'}term
        OR toLower(coalesce(n.callee_name, '')) CONTAINS ${'$'}term)
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun requestGraphWrappedContains(term: String): String = """
    MATCH (n)
    WHERE toLower(coalesce(n.caller_class, '')) CONTAINS '${cypherString(term.lowercase())}'
       OR toLower(coalesce(n.caller_name, '')) CONTAINS '${cypherString(term.lowercase())}'
       OR toLower(coalesce(n.callee_class, '')) CONTAINS '${cypherString(term.lowercase())}'
       OR toLower(coalesce(n.callee_name, '')) CONTAINS '${cypherString(term.lowercase())}'
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun cypherString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

private fun containsAnd(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' AND n.callee_class CONTAINS '$term'
    RETURN DISTINCT n.caller_class, n.callee_class
    LIMIT 100
""".trimIndent()

private fun containsAndOr(required: String, optional: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$required'
      AND (n.caller_class CONTAINS '$optional' OR n.caller_class CONTAINS 'Voucher'
        OR n.caller_class CONTAINS 'Loyalty' OR n.caller_class CONTAINS 'Under')
    RETURN DISTINCT n.caller_class AS class
    LIMIT 100
""".trimIndent()

private fun pairAndPair(term: String): String = """
    MATCH (n)
    WHERE (n.caller_class CONTAINS '$term' OR n.callee_class CONTAINS '$term')
      AND (n.caller_name CONTAINS '$term' OR n.callee_name CONTAINS '$term')
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 100
""".trimIndent()

private fun exactOr(exactClass: String, absent: String): String = """
    MATCH (n)
    WHERE n.caller_class = '$exactClass' OR n.callee_class = '$exactClass'
       OR n.caller_class = '$absent' OR n.callee_class = '$absent'
    RETURN DISTINCT n.caller_class AS callerClass, n.caller_name AS callerName,
        n.callee_class AS calleeClass, n.callee_name AS calleeName
    LIMIT 160
""".trimIndent()

private fun exactAndContains(exactClass: String, absent: String): String = """
    MATCH (n)
    WHERE n.caller_class = '$exactClass' AND n.callee_class CONTAINS '$absent'
    RETURN n
    LIMIT 20
""".trimIndent()

private fun exactOrNode(exactClass: String, absent: String): String = """
    MATCH (n)
    WHERE n.caller_class = '$exactClass' OR n.callee_class = '$exactClass'
       OR n.caller_class = '$absent' OR n.callee_class = '$absent'
    RETURN n
    LIMIT 20
""".trimIndent()

private fun fourPropertyKeys(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN keys(n) AS keys
    LIMIT 5
""".trimIndent()

private fun fourPropertyGraphId(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN graphId(n) AS graph, n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun singleDistinct(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term'
    RETURN DISTINCT n.caller_class
    LIMIT 100
""".trimIndent()

private fun orderedProjection(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.callee_class CONTAINS '$term'
    RETURN n.caller_class AS callerClass, n.callee_class AS calleeClass
    ORDER BY callerClass, calleeClass
    LIMIT 100
""".trimIndent()

private fun orderedSkip(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN n.caller_class AS callerClass, n.caller_name AS callerName,
        n.callee_class AS calleeClass, n.callee_name AS calleeName
    ORDER BY callerClass, callerName, calleeClass, calleeName
    SKIP 10000 LIMIT 200
""".trimIndent()

private fun distinctSkip(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.callee_class CONTAINS '$term'
    RETURN DISTINCT n.caller_class AS callerClass, n.callee_class AS calleeClass
    SKIP 10000 LIMIT 200
""".trimIndent()

private fun largeLimit(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 5000
""".trimIndent()

private fun filteredCount(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN count(*) AS matches
""".trimIndent()

private fun filteredDistinctCount(term: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term'
    RETURN count(DISTINCT n.caller_class) AS callerClasses
""".trimIndent()

private fun parameterizedFourPropertyOr(): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS ${'$'}term OR n.caller_name CONTAINS ${'$'}term
       OR n.callee_class CONTAINS ${'$'}term OR n.callee_name CONTAINS ${'$'}term
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun exactNameIn(exactName: String, absent: String): String = """
    MATCH (n)
    WHERE n.caller_name IN ['$exactName', '$absent'] OR n.callee_name IN ['$exactName', '$absent']
    RETURN DISTINCT n.caller_name, n.callee_name
    LIMIT 200
""".trimIndent()

private fun positiveAndNot(term: String, absent: String): String = """
    MATCH (n)
    WHERE (n.caller_class CONTAINS '$term' OR n.caller_name CONTAINS '$term'
       OR n.callee_class CONTAINS '$term' OR n.callee_name CONTAINS '$term')
      AND NOT n.caller_name CONTAINS '$absent'
    RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun globalSixPropertyOr(term: String): String = """
    MATCH (n)
    WHERE (exists(n.class) AND n.class CONTAINS '$term')
       OR (exists(n.name) AND n.name CONTAINS '$term')
       OR (exists(n.caller_class) AND n.caller_class CONTAINS '$term')
       OR (exists(n.caller_name) AND n.caller_name CONTAINS '$term')
       OR (exists(n.callee_class) AND n.callee_class CONTAINS '$term')
       OR (exists(n.callee_name) AND n.callee_name CONTAINS '$term')
    RETURN DISTINCT n.class, n.name, n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun wrappedGlobalSixPropertyOr(term: String): String = """
    MATCH (n)
    WHERE toLower(coalesce(n.class, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.name, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.caller_class, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.caller_name, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.callee_class, '')) CONTAINS '${term.lowercase()}'
       OR toLower(coalesce(n.callee_name, '')) CONTAINS '${term.lowercase()}'
    RETURN DISTINCT n.class, n.name, n.caller_class, n.caller_name, n.callee_class, n.callee_name
    LIMIT 200
""".trimIndent()

private fun guardedContains(term: String): String = """
    MATCH (n)
    WHERE exists(n.caller_class) AND n.caller_class CONTAINS '$term'
    RETURN n.caller_class
    LIMIT 200
""".trimIndent()

private fun regexOr(term: String): String {
    val escaped = Regex.escape(term).replace("'", "\\'")
    return """
        MATCH (n)
        WHERE n.caller_class =~ '.*$escaped.*' OR n.callee_class =~ '.*$escaped.*'
        RETURN n.caller_class, n.callee_class
        LIMIT 100
    """.trimIndent()
}

private val BROAD_QUERY_COVERAGE = listOf(
    BroadQueryCoverageSpec("single-contains-unlabeled", BroadQueryFamily.CONTAINS, "property", 200) {
        singleContains(it.term)
    },
    BroadQueryCoverageSpec("class-contains-or-unlabeled", BroadQueryFamily.CONTAINS, "distinct-properties", 200) {
        classContainsOr(it.term)
    },
    BroadQueryCoverageSpec("name-contains-or-unlabeled", BroadQueryFamily.CONTAINS, "properties", 200) {
        nameContainsOr(it.term)
    },
    BroadQueryCoverageSpec("four-contains-or-unlabeled", BroadQueryFamily.CONTAINS, "properties", 200) {
        fourPropertyOr(it.term)
    },
    BroadQueryCoverageSpec("four-contains-or-labeled", BroadQueryFamily.CONTAINS, "properties", 200) {
        labeledFourPropertyOr(it.term)
    },
    BroadQueryCoverageSpec(
        "starts-or-raw", BroadQueryFamily.CONTAINS, "properties", 100, operator = "starts-with"
    ) {
        rawStartsOr(it.term)
    },
    BroadQueryCoverageSpec(
        "ends-or-raw", BroadQueryFamily.CONTAINS, "properties", 100, operator = "ends-with"
    ) {
        rawEndsOr(it.term)
    },
    BroadQueryCoverageSpec(
        "starts-or-wrapped", BroadQueryFamily.WRAPPED, "distinct-properties", 100,
        operator = "wrapped-starts-with"
    ) {
        wrappedStartsOr(it.term)
    },
    BroadQueryCoverageSpec(
        "ends-or-wrapped", BroadQueryFamily.WRAPPED, "distinct-properties", 100,
        operator = "wrapped-ends-with"
    ) {
        wrappedEndsOr(it.term)
    },
    BroadQueryCoverageSpec(
        "contains-or-wrapped", BroadQueryFamily.WRAPPED, "distinct-properties", 200,
        operator = "wrapped-contains"
    ) {
        wrappedContainsOr(it.term)
    },
    BroadQueryCoverageSpec(
        "graph-id-property-wrapped-contains", BroadQueryFamily.GRAPH_ID, "properties", 200,
        operator = "graph-id-equals-and-wrapped-contains", boundary = "graph-routing"
    ) {
        graphIdPropertyWrappedContains(it.graphId, it.term)
    },
    BroadQueryCoverageSpec(
        "graph-id-function-wrapped-contains", BroadQueryFamily.GRAPH_ID, "properties", 200,
        operator = "graph-id-function-equals-and-wrapped-contains", boundary = "graph-routing"
    ) {
        graphIdFunctionWrappedContains(it.graphId, it.term)
    },
    BroadQueryCoverageSpec(
        "graph-id-parameter-wrapped-contains", BroadQueryFamily.GRAPH_ID, "properties", 200,
        operator = "graph-id-parameter-equals-and-wrapped-contains", boundary = "parameters",
        parameters = { mapOf("graphId" to it.graphId, "term" to it.term.lowercase()) }
    ) {
        parameterizedGraphIdWrappedContains()
    },
    BroadQueryCoverageSpec(
        "api-graph-parameter-wrapped-contains", BroadQueryFamily.GRAPH_PARAMETER, "properties", 200,
        operator = "request-graph-selection-and-wrapped-contains", boundary = "api-graph-parameter"
    ) {
        requestGraphWrappedContains(it.term)
    },
    BroadQueryCoverageSpec(
        "contains-and", BroadQueryFamily.BOOLEAN, "distinct-properties", 100, boundary = "and"
    ) {
        containsAnd(it.term)
    },
    BroadQueryCoverageSpec(
        "contains-and-or", BroadQueryFamily.BOOLEAN, "distinct-property", 100, boundary = "and-or"
    ) {
        containsAndOr(it.term, it.absent)
    },
    BroadQueryCoverageSpec(
        "pair-and-pair", BroadQueryFamily.BOOLEAN, "properties", 100, boundary = "and-of-or"
    ) {
        pairAndPair(it.term)
    },
    BroadQueryCoverageSpec(
        "exact-or", BroadQueryFamily.EXACT, "distinct-properties", 160, operator = "equals"
    ) {
        exactOr(it.exactClass, it.absent)
    },
    BroadQueryCoverageSpec(
        "exact-and-contains", BroadQueryFamily.EXACT, "node", 20,
        operator = "equals-and-contains", boundary = "and"
    ) {
        exactAndContains(it.exactClass, it.absent)
    },
    BroadQueryCoverageSpec(
        "exact-or-node", BroadQueryFamily.EXACT, "node", 20, operator = "equals"
    ) {
        exactOrNode(it.exactClass, it.absent)
    },
    BroadQueryCoverageSpec("four-or-keys", BroadQueryFamily.PROJECTION, "keys", 5) {
        fourPropertyKeys(it.term)
    },
    BroadQueryCoverageSpec("four-or-graph-id", BroadQueryFamily.PROJECTION, "graph-id-properties", 200) {
        fourPropertyGraphId(it.term)
    },
    BroadQueryCoverageSpec("single-distinct", BroadQueryFamily.PROJECTION, "distinct-property", 100) {
        singleDistinct(it.term)
    },
    BroadQueryCoverageSpec("ordered-projection", BroadQueryFamily.PROJECTION, "ordered-properties", 100) {
        orderedProjection(it.term)
    },
    BroadQueryCoverageSpec(
        "ordered-skip", BroadQueryFamily.PROJECTION, "ordered-properties", 200,
        boundary = "order-skip-limit"
    ) {
        orderedSkip(it.term)
    },
    BroadQueryCoverageSpec(
        "distinct-skip", BroadQueryFamily.PROJECTION, "distinct-properties", 200,
        boundary = "distinct-skip-limit"
    ) {
        distinctSkip(it.term)
    },
    BroadQueryCoverageSpec(
        "large-limit", BroadQueryFamily.PROJECTION, "properties", 5000, boundary = "large-limit"
    ) {
        largeLimit(it.term)
    },
    BroadQueryCoverageSpec(
        "filtered-count", BroadQueryFamily.AGGREGATION, "count", 0,
        boundary = "aggregation", zeroReturnsNoRows = false
    ) {
        filteredCount(it.term)
    },
    BroadQueryCoverageSpec(
        "filtered-distinct-count", BroadQueryFamily.AGGREGATION, "distinct-count", 0,
        boundary = "aggregation", zeroReturnsNoRows = false
    ) {
        filteredDistinctCount(it.term)
    },
    BroadQueryCoverageSpec(
        "parameterized-four-or", BroadQueryFamily.CONTAINS, "properties", 200,
        boundary = "parameters", parameters = { mapOf("term" to it.term) }
    ) {
        parameterizedFourPropertyOr()
    },
    BroadQueryCoverageSpec(
        "exact-name-in", BroadQueryFamily.EXACT, "distinct-properties", 200, operator = "in"
    ) {
        exactNameIn(it.exactName, it.absent)
    },
    BroadQueryCoverageSpec(
        "positive-and-not", BroadQueryFamily.BOOLEAN, "properties", 200,
        operator = "contains-and-not", boundary = "and-not"
    ) {
        positiveAndNot(it.term, it.absent)
    },
    BroadQueryCoverageSpec("global-six-or", BroadQueryFamily.GLOBAL, "distinct-six-properties", 200) {
        globalSixPropertyOr(it.term)
    },
    BroadQueryCoverageSpec("global-six-or-wrapped", BroadQueryFamily.GLOBAL, "distinct-six-properties", 200) {
        wrappedGlobalSixPropertyOr(it.term)
    },
    BroadQueryCoverageSpec("exists-guarded-contains", BroadQueryFamily.GLOBAL, "property", 200) {
        guardedContains(it.term)
    },
    BroadQueryCoverageSpec(
        "regex-or", BroadQueryFamily.REGEX, "properties", 100, operator = "regex"
    ) {
        regexOr(it.term)
    }
)

private const val MAX_GRAPH_COUNT = 64
private const val COLD_INDEX_STATE = "cold"
private const val WARM_INDEX_STATE = "warm"
private const val ALL_COVERAGE_FAMILIES = "all"
private const val GRAPH_ROUTING_COVERAGE_FAMILY = "graph-routing"
private const val GRAPH_MANIFEST_COLUMN_COUNT = 5
private const val ROUTING_RESULT_LIMIT = 200L
private const val OUTPUT_PROPERTY = "graphite.broad.pressure.output"
private const val OBSERVATIONS_OUTPUT_PROPERTY = "graphite.broad.pressure.observations.output"
private const val GRAPH_MANIFEST_PROPERTY = "graphite.broad.pressure.graphs"
private const val CORRECTNESS_MODE_PROPERTY = "graphite.broad.pressure.correctness.mode"
private const val CORRECTNESS_ORACLE_PROPERTY = "graphite.broad.pressure.correctness.oracle"
private const val TIMEOUT_DIGEST = "timeout"
private const val CANCELLATION_GRACE_SECONDS = 5L
private const val MIN_EIGHT_GIB_HEAP_BYTES = 7L * 1_024L * 1_024L * 1_024L
private const val MAX_EIGHT_GIB_HEAP_BYTES = 8L * 1_024L * 1_024L * 1_024L
private const val BYTES_PER_KIBIBYTE = 1_024L
private const val PS_TIMEOUT_SECONDS = 2L
private const val SAMPLER_INTERVAL_NANOS = 1_000_000L
private const val RSS_SAMPLE_DIVISOR = 250
private const val SAMPLER_JOIN_MILLIS = 5_000L
private const val GC_ATTEMPTS = 3
private const val GC_PAUSE_MILLIS = 100L

private val TARGETED_TERMS = listOf("android.", "org.apache.tika.", "org.apache.hadoop.hive.", "kotlin")
private val DENSE_TERMS = listOf("java", "org", "get", "set")
private val TARGETED_EXACT_CLASSES = listOf("java.util.List", "java.util.Map", "java.lang.Runnable")
private val DENSE_EXACT_CLASSES = listOf("java.lang.String", "java.lang.Object", "java.lang.Class")
private val TARGETED_EXACT_NAMES = listOf("main", "parse", "onCreate", "invoke")
private val DENSE_EXACT_NAMES = listOf("get", "set", "toString", "equals")
