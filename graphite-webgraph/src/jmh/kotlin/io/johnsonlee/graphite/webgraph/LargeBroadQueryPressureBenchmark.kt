@file:Suppress("MagicNumber", "ReturnCount", "StringLiteralDuplication", "TooManyFunctions")

package io.johnsonlee.graphite.webgraph

import io.johnsonlee.graphite.cypher.CrossGraphCypherExecutor
import io.johnsonlee.graphite.cypher.CypherCancellationSignal
import io.johnsonlee.graphite.cypher.CypherDslAdapter
import io.johnsonlee.graphite.cypher.CypherExecutionBudget
import io.johnsonlee.graphite.cypher.CypherExecutionContext
import io.johnsonlee.graphite.cypher.CypherGraph
import io.johnsonlee.graphite.cypher.CypherQueryTimeoutException
import io.johnsonlee.graphite.cypher.CypherResult
import io.johnsonlee.graphite.cypher.RESULT_GRAPH_IDS_KEY
import io.johnsonlee.graphite.cypher.RESULT_METADATA_KEY
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
 * gate can copy the same harness into both revisions before building their JMH jars. It does not
 * exercise HTTP or [io.johnsonlee.graphite.cli.ExploreRoutes]: the request-selection reference
 * path means that a parsed request has already selected one source, while the graphId-predicate
 * path passes every source to Cypher and relies on query planning to route it. Per-query access
 * observations make that distinction explicit and prove whether non-target graphs were touched.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.SECONDS)
@Measurement(iterations = 1)
@Fork(1, jvmArgs = ["-Xmx8g"])
@Suppress("TooManyFunctions")
open class LargeBroadQueryPressureBenchmark {

    @Param("cold", "warm", "startup-prepared")
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
    private var originalPrepareIndexOnLoad: String? = null
    private var prepareIndexOnLoadConfigured = false
    private val graphs = mutableListOf<MappedWebGraphBackedGraph>()

    @Setup(Level.Trial)
    fun setupTrial() {
        check(Runtime.getRuntime().maxMemory() in MIN_EIGHT_GIB_HEAP_BYTES..MAX_EIGHT_GIB_HEAP_BYTES) {
            "Large broad-query pressure benchmark must run with -Xmx8g; " +
                "max heap was ${Runtime.getRuntime().maxMemory()} bytes"
        }
        require(timeoutMillis > 0L)
        require(indexState in BROAD_QUERY_INDEX_STATES) {
            "Unknown indexState '$indexState'; expected ${BROAD_QUERY_INDEX_STATES.joinToString()}"
        }
        originalPrepareIndexOnLoad = System.getProperty(PREPARE_INDEX_ON_LOAD_PROPERTY)
        System.setProperty(
            PREPARE_INDEX_ON_LOAD_PROPERTY,
            if (indexState == STARTUP_PREPARED_INDEX_STATE) "true" else LAZY_INDEX_PREPARATION_MODE
        )
        prepareIndexOnLoadConfigured = true
        require(graphCount in 1..MAX_GRAPH_COUNT)
        val graphSources = broadQueryGraphSources(graphCount)
        val fullCoverage = broadQueryCoverageWorkload(
            graphSources,
            if (graphCount == MAX_GRAPH_COUNT) broadQueryFixtureDistributions(graphSources) else emptyMap()
        )
        workload = when (coverageFamily) {
            ALL_COVERAGE_FAMILIES -> fullCoverage
            GRAPH_ROUTING_COVERAGE_FAMILY -> fullCoverage.filter { case -> case.family.isGraphRouting() }
            GRAPH_ROUTING_REFERENCE_COVERAGE_FAMILY -> fullCoverage.filter { case ->
                case.family.isRequestSelectedReference()
            }
            else -> {
                require(coverageFamily in BroadQueryFamily.entries.map(BroadQueryFamily::id)) {
                    "Unknown broad-query coverage family: $coverageFamily"
                }
                fullCoverage.filter { case -> case.family.id == coverageFamily }
            }
        }
        check(workload.isNotEmpty())
        check(workload.map(BroadQueryCase::selectivity).toSet() == BroadQuerySelectivity.entries.toSet())
        workload.forEach { case -> CypherDslAdapter.parse(case.query) }
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
        when (indexState) {
            COLD_INDEX_STATE -> graphs.forEach(MappedWebGraphBackedGraph::clearStringPropertyIndexes)
            WARM_INDEX_STATE -> {
                graphs.forEach(MappedWebGraphBackedGraph::clearStringPropertyIndexes)
                resetCallSiteScanMetrics()
                enforceCorrectness(replay(validateResults = true))
            }
            STARTUP_PREPARED_INDEX_STATE -> Unit
        }
        resetCallSiteScanMetrics()
        forcePressureGc()
        sampler.start()
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        runCatching { queryExecutor.shutdownNow() }
        runCatching { sampler.close() }
        graphs.asReversed().forEach { graph -> runCatching { graph.close() } }
        if (prepareIndexOnLoadConfigured) {
            originalPrepareIndexOnLoad?.let { value ->
                System.setProperty(PREPARE_INDEX_ON_LOAD_PROPERTY, value)
            } ?: System.clearProperty(PREPARE_INDEX_ON_LOAD_PROPERTY)
            prepareIndexOnLoadConfigured = false
        }
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
        resetCallSiteScanMetrics()
        resetGraphWorkerMetrics()
        val started = System.nanoTime()
        val cancellation = CypherCancellationSignal()
        val context = CypherExecutionContext(CypherExecutionBudget(Long.MAX_VALUE), cancellation)
        val executionPath = case.executionPath()
        val executionSources = case.requestGraphIds?.map { graphId ->
            checkNotNull(sourcesById[graphId]) { "Requested graph is not loaded: $graphId" }
        } ?: sources
        val task = queryExecutor.submit(Callable {
            pressureQueryExecutor(
                executionSources,
                context,
                case.requestGraphIds != null
            ).execute(case.query, case.parameters)
        })
        try {
            val result = task.get(case.timeoutMillis(timeoutMillis), TimeUnit.MILLISECONDS)
            val latencyNanos = System.nanoTime() - started
            val canonicalResult = canonicalResult(result)
            BroadQuerySample(
                case = case,
                latencyNanos = latencyNanos,
                outcome = BroadQueryOutcome.SUCCESS,
                rowCount = result.rows.size.toLong(),
                responseBytes = canonicalResult.size.toLong(),
                digest = digest(canonicalResult),
                hitGraphIds = resultHitGraphIds(result),
                execution = queryExecutionMetrics(case, executionPath, executionSources.size, context)
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
                TIMEOUT_DIGEST,
                emptySet(),
                queryExecutionMetrics(case, executionPath, executionSources.size, context)
            )
        } catch (error: ExecutionException) {
            BroadQuerySample(
                case,
                System.nanoTime() - started,
                BroadQueryOutcome.FAILED,
                0L,
                0L,
                error.cause?.javaClass?.name ?: error.javaClass.name,
                emptySet(),
                queryExecutionMetrics(case, executionPath, executionSources.size, context)
            )
        }
    }

    private fun resultHitGraphIds(result: CypherResult): Set<String> = result.rows.flatMapTo(linkedSetOf()) { row ->
        @Suppress("UNCHECKED_CAST")
        val metadata = row[RESULT_METADATA_KEY] as? Map<String, Any?>
        (metadata?.get(RESULT_GRAPH_IDS_KEY) as? Iterable<*>)
            ?.mapNotNull { graphId -> graphId as? String }
            .orEmpty()
    }

    private fun BroadQueryCase.executionPath(): BroadQueryExecutionPath = when {
        requestGraphIds != null -> BroadQueryExecutionPath.REQUEST_SELECTED_SOURCE
        family.isGraphIdPredicate() -> BroadQueryExecutionPath.CYPHER_GRAPH_ID_PREDICATE
        else -> BroadQueryExecutionPath.CROSS_GRAPH_QUERY
    }

    private fun queryExecutionMetrics(
        case: BroadQueryCase,
        executionPath: BroadQueryExecutionPath,
        inputSourceCount: Int,
        context: CypherExecutionContext
    ): BroadQueryExecutionMetrics {
        val perGraph = sources.indices.map { index ->
            val graph = graphs[index]
            BroadQueryGraphAccess(
                graphId = sources[index].id,
                stringLookupEntries = optionalInternalLong(graph, "callSiteStringLookupEntryCount"),
                parallelScans = requiredInternalLong(graph, "callSiteParallelScanCount"),
                serialScans = optionalInternalLong(graph, "callSiteSerialScanCount"),
                indexLookups = requiredInternalLong(graph, "callSiteStringIndexLookupCount"),
                preflightChecks = optionalInternalLong(graph, "callSiteStringPreflightCount"),
                projectionLookups = optionalInternalLong(graph, "callSiteStringProjectionLookupCount"),
                peakActiveWorkers = requiredInternalLong(graph, "callSiteScanPeakActiveWorkers")
            )
        }
        val accessed = perGraph.filter(BroadQueryGraphAccess::wasAccessed)
        val targets = case.targetGraphIds.toSet()
        val planner = plannerDiagnostics(context)
        return BroadQueryExecutionMetrics(
            path = executionPath,
            inputSourceCount = inputSourceCount.toLong(),
            accessedGraphIds = accessed.mapTo(linkedSetOf(), BroadQueryGraphAccess::graphId),
            parallelScanGraphIds = perGraph.asSequence()
                .filter { access -> access.parallelScans > 0L }
                .mapTo(linkedSetOf(), BroadQueryGraphAccess::graphId),
            indexLookupsByGraph = perGraph.associate { access -> access.graphId to access.indexLookups },
            targetGraphAccessCount = accessed.count { access -> access.graphId in targets }.toLong(),
            nonTargetGraphAccessCount = accessed.count { access -> access.graphId !in targets }.toLong(),
            parallelScanCount = perGraph.sumOf(BroadQueryGraphAccess::parallelScans),
            indexLookupCount = perGraph.sumOf(BroadQueryGraphAccess::indexLookups),
            peakActiveWorkers = perGraph.maxOfOrNull(BroadQueryGraphAccess::peakActiveWorkers) ?: 0L,
            graphWorkerPeakActiveWorkers = graphWorkerMetric("directStringGraphPeakActiveWorkers"),
            segmentWorkerPeakActiveWorkers = optionalInternalLong(
                graphs.first(),
                "callSiteSegmentPeakActiveWorkers"
            ),
            graphWorkUnits = consumedGraphWorkUnits(context),
            graphIdSourceSelections = planner.graphIdSourceSelections,
            graphIdSourcePruningExecutions = planner.graphIdSourcePruningExecutions,
            graphIdSourcesPruned = planner.graphIdSourcesPruned,
            filteredNodeLimitFastPathExecutions = planner.filteredNodeLimitFastPathExecutions,
            generalFallbackExecutions = planner.generalFallbackExecutions
        )
    }

    /** Candidate-only planner counters are read reflectively so the same harness still compiles on base. */
    private fun plannerDiagnostics(context: CypherExecutionContext): BroadQueryPlannerDiagnostics {
        val diagnostics = context.javaClass.methods.singleOrNull { method ->
            method.name == "getDiagnostics" && method.parameterCount == 0
        }?.invoke(context) ?: return BroadQueryPlannerDiagnostics.EMPTY
        fun metric(getter: String): Long = checkNotNull(diagnostics.javaClass.methods.singleOrNull { method ->
            method.name == getter && method.parameterCount == 0
        }?.invoke(diagnostics) as? Number) {
            "Cypher execution diagnostic $getter is unavailable"
        }.toLong()
        return BroadQueryPlannerDiagnostics(
            graphIdSourceSelections = metric("getGraphIdSourceSelections"),
            graphIdSourcePruningExecutions = metric("getGraphIdSourcePruningExecutions"),
            graphIdSourcesPruned = metric("getGraphIdSourcesPruned"),
            filteredNodeLimitFastPathExecutions = metric("getFilteredNodeLimitFastPathExecutions"),
            generalFallbackExecutions = metric("getGeneralFallbackExecutions")
        )
    }

    private fun requiredInternalLong(graph: MappedWebGraphBackedGraph, prefix: String): Long =
        checkNotNull(invokeInternalMetric(graph, prefix) as? Number) {
            "Required benchmark metric $prefix is unavailable on ${graph.javaClass.name}"
        }.toLong()

    private fun optionalInternalLong(graph: MappedWebGraphBackedGraph, prefix: String): Long =
        (invokeInternalMetric(graph, prefix) as? Number)?.toLong() ?: 0L

    private fun consumedGraphWorkUnits(context: CypherExecutionContext): Long {
        val tracker = context.javaClass.declaredFields.singleOrNull { field ->
            field.type.simpleName == "CypherWorkTracker"
        }?.let { field ->
            field.isAccessible = true
            field.get(context)
        } ?: error("CypherExecutionContext work tracker is unavailable to the benchmark harness")
        val remaining = tracker.javaClass.declaredFields.singleOrNull { field ->
            field.name == "remaining" && field.type == AtomicLong::class.java
        }?.let { field ->
            field.isAccessible = true
            (field.get(tracker) as AtomicLong).get()
        } ?: error("Cypher work-unit counter is unavailable to the benchmark harness")
        return Long.MAX_VALUE - remaining
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
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val split = reflectedGraphScanParallelism(processors)
        counters.availableProcessors = processors.toLong()
        counters.graphWorkerCount = split?.first?.toLong() ?: 0L
        counters.segmentWorkerCount = split?.second?.toLong() ?: 0L
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
        counters.graphIdSetP95LatencyNanos = familyP95(samples, BroadQueryFamily.GRAPH_ID_SET)
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
        counters.requestSelectedSourceQueryCount = samples.count {
            it.execution.path == BroadQueryExecutionPath.REQUEST_SELECTED_SOURCE
        }.toLong()
        counters.cypherGraphIdPredicateQueryCount = samples.count {
            it.execution.path == BroadQueryExecutionPath.CYPHER_GRAPH_ID_PREDICATE
        }.toLong()
        counters.inputSourceCount = samples.sumOf { sample -> sample.execution.inputSourceCount }
        counters.accessedGraphCount = samples.sumOf { sample -> sample.execution.accessedGraphIds.size.toLong() }
        counters.targetGraphAccessCount = samples.sumOf { sample -> sample.execution.targetGraphAccessCount }
        counters.nonTargetGraphAccessCount = samples.sumOf { sample -> sample.execution.nonTargetGraphAccessCount }
        counters.graphWorkUnits = samples.sumOf { sample -> sample.execution.graphWorkUnits }
        counters.graphIdSourceSelections = samples.sumOf { sample -> sample.execution.graphIdSourceSelections }
        counters.graphIdSourcePruningExecutions = samples.sumOf {
            sample -> sample.execution.graphIdSourcePruningExecutions
        }
        counters.graphIdSourcesPruned = samples.sumOf { sample -> sample.execution.graphIdSourcesPruned }
        counters.filteredNodeLimitFastPathExecutions = samples.sumOf {
            sample -> sample.execution.filteredNodeLimitFastPathExecutions
        }
        counters.generalFallbackExecutions = samples.sumOf { sample -> sample.execution.generalFallbackExecutions }
        counters.callSiteParallelScanCount = samples.sumOf { sample -> sample.execution.parallelScanCount }
        counters.callSiteParallelScanGraphCount = samples.asSequence()
            .flatMap { sample -> sample.execution.parallelScanGraphIds.asSequence() }
            .toSet()
            .size
            .toLong()
        val indexLookupCounts = sources.map { source ->
            samples.sumOf { sample -> sample.execution.indexLookupsByGraph.getValue(source.id) }
        }
        counters.callSiteStringIndexLookupCount = indexLookupCounts.sum()
        counters.callSiteStringIndexLookupGraphCount = indexLookupCounts.count { count -> count > 0L }.toLong()
        counters.callSiteStringIndexLookupMinPerGraph = indexLookupCounts.minOrNull() ?: 0L
        counters.callSiteStringIndexLookupMaxPerGraph = indexLookupCounts.maxOrNull() ?: 0L
        counters.callSiteScanPeakActiveWorkers = samples.maxOfOrNull {
            sample -> sample.execution.peakActiveWorkers
        } ?: 0L
        counters.graphScanPeakActiveWorkers = samples.maxOfOrNull {
            sample -> sample.execution.graphWorkerPeakActiveWorkers
        } ?: 0L
        counters.segmentScanPeakActiveWorkers = samples.maxOfOrNull {
            sample -> sample.execution.segmentWorkerPeakActiveWorkers
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

    private fun resetGraphWorkerMetrics() {
        graphWorkerMetric("resetDirectStringGraphWorkerMetrics")
    }

    private fun graphWorkerMetric(prefix: String): Long = runCatching {
        val owner = Class.forName("io.johnsonlee.graphite.cypher.QueryPipelineKt")
        val method = owner.declaredMethods.firstOrNull { candidate ->
            candidate.parameterCount == 0 && candidate.name.startsWith(prefix)
        } ?: return@runCatching 0L
        method.isAccessible = true
        (method.invoke(null) as? Number)?.toLong() ?: 0L
    }.getOrDefault(0L)

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
            "targetGraphIds",
            "selectedGraphCount",
            "workloadIdentity",
            "outcome",
            "rowCount",
            "responseBytes",
            "digest",
            "latencyNanos",
            "fixtureDistributionId",
            "hitGraphIds",
            "executionPath",
            "inputSourceCount",
            "accessedGraphCount",
            "accessedGraphIds",
            "targetGraphAccessCount",
            "nonTargetGraphAccessCount",
            "parallelScanCount",
            "indexLookupCount",
            "peakActiveWorkers",
            "graphWorkUnits",
            "graphIdSourceSelections",
            "graphIdSourcePruningExecutions",
            "graphIdSourcesPruned",
            "filteredNodeLimitFastPathExecutions",
            "generalFallbackExecutions"
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
                sample.case.targetGraphIds.joinToString(","),
                sample.case.targetGraphIds.size,
                sample.case.workloadIdentity.orEmpty(),
                sample.outcome.name.lowercase(),
                sample.rowCount,
                sample.responseBytes,
                sample.digest,
                sample.latencyNanos,
                sample.case.fixtureDistributionId.orEmpty(),
                sample.hitGraphIds.joinToString(","),
                sample.execution.path.id,
                sample.execution.inputSourceCount,
                sample.execution.accessedGraphIds.size,
                sample.execution.accessedGraphIds.sorted().joinToString(","),
                sample.execution.targetGraphAccessCount,
                sample.execution.nonTargetGraphAccessCount,
                sample.execution.parallelScanCount,
                sample.execution.indexLookupCount,
                sample.execution.peakActiveWorkers,
                sample.execution.graphWorkUnits,
                sample.execution.graphIdSourceSelections,
                sample.execution.graphIdSourcePruningExecutions,
                sample.execution.graphIdSourcesPruned,
                sample.execution.filteredNodeLimitFastPathExecutions,
                sample.execution.generalFallbackExecutions
            ).joinToString("\t")
        }
        Files.writeString(Path.of(configured), lines)
    }

    private fun digest(canonicalResult: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalResult)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun canonicalResult(result: CypherResult): ByteArray {
        val columns = framedSequence("columns", result.columns.map(::canonical))
        val rows = framedSequence("rows", result.rows.map { row ->
            framedSequence("row", row.entries.sortedBy(Map.Entry<String, Any?>::key).flatMap { (key, value) ->
                listOf(canonical(key), canonical(value))
            })
        })
        return "$columns$rows".toByteArray(Charsets.UTF_8)
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is String -> frame("string", value)
        is Number -> framedSequence("number", listOf(frame("type", value::class.java.name), frame("value", value.toString())))
        is Boolean -> frame("boolean", value.toString())
        is Map<*, *> -> framedSequence("map", value.entries
            .map { entry -> canonical(entry.key) to canonical(entry.value) }
            .sortedBy(Pair<String, String>::first)
            .flatMap { (key, nested) -> listOf(key, nested) })
        is Set<*> -> framedSequence("set", value.map(::canonical).sorted())
        is Iterable<*> -> framedSequence("iterable", value.map(::canonical))
        is Array<*> -> framedSequence("array", value.map(::canonical))
        else -> framedSequence(
            "object",
            listOf(frame("type", value::class.java.name), frame("value", value.toString()))
        )
    }

    private fun framedSequence(tag: String, values: List<String>): String =
        frame(tag, values.joinToString(separator = "", prefix = values.size.toString()) { frame("item", it) })

    private fun frame(tag: String, value: String): String =
        "$tag:${value.toByteArray(Charsets.UTF_8).size}:$value"
}

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.EVENTS)
open class LargeBroadQueryPressureCounters {
    @JvmField var availableProcessors: Long = 0
    @JvmField var graphWorkerCount: Long = 0
    @JvmField var segmentWorkerCount: Long = 0
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
    @JvmField var graphIdSetP95LatencyNanos: Long = 0
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
    @JvmField var graphScanPeakActiveWorkers: Long = 0
    @JvmField var segmentScanPeakActiveWorkers: Long = 0
    @JvmField var requestSelectedSourceQueryCount: Long = 0
    @JvmField var cypherGraphIdPredicateQueryCount: Long = 0
    @JvmField var inputSourceCount: Long = 0
    @JvmField var accessedGraphCount: Long = 0
    @JvmField var targetGraphAccessCount: Long = 0
    @JvmField var nonTargetGraphAccessCount: Long = 0
    @JvmField var graphWorkUnits: Long = 0
    @JvmField var graphIdSourceSelections: Long = 0
    @JvmField var graphIdSourcePruningExecutions: Long = 0
    @JvmField var graphIdSourcesPruned: Long = 0
    @JvmField var filteredNodeLimitFastPathExecutions: Long = 0
    @JvmField var generalFallbackExecutions: Long = 0
}

private fun reflectedGraphScanParallelism(processors: Int): Pair<Int, Int>? = runCatching {
    val planClass = Class.forName("io.johnsonlee.graphite.graph.GraphScanParallelismPlan")
    val companion = planClass.getField("Companion").get(null)
    val companionClass = companion.javaClass
    val plan = companionClass.getMethod("balanced", Int::class.javaPrimitiveType).invoke(companion, processors)
    val graphWorkers = plan.javaClass.getMethod("getGraphWorkerCount").invoke(plan) as Int
    val segmentWorkers = plan.javaClass.getMethod("getSegmentWorkerCount").invoke(plan) as Int
    graphWorkers to segmentWorkers
}.getOrNull()

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
    val targetGraphIds: List<String> = listOfNotNull(targetGraphId),
    val workloadIdentity: String? = null,
    val requestGraphIds: List<String>? = null,
    val expectedRowCountRange: LongRange? = null,
    val configuredTimeoutMillis: Long? = null,
    val fixtureDistributionId: String? = null
) {
    fun timeoutMillis(defaultMillis: Long): Long = configuredTimeoutMillis ?: defaultMillis
}

private data class BroadQuerySample(
    val case: BroadQueryCase,
    val latencyNanos: Long,
    val outcome: BroadQueryOutcome,
    val rowCount: Long,
    val responseBytes: Long,
    val digest: String,
    val hitGraphIds: Set<String>,
    val execution: BroadQueryExecutionMetrics
) {
    fun correctnessRecord(): QueryCorrectnessRecord = QueryCorrectnessRecord(
        id = case.id,
        family = case.family.id,
        shape = case.shape,
        selectivity = case.selectivity.id,
        operator = case.operator,
        boundary = case.boundary,
        projection = case.projection,
        targetGraphId = case.targetGraphId.orEmpty(),
        workloadIdentity = case.workloadIdentity.orEmpty(),
        limit = case.limit.toLong(),
        outcome = outcome.name.lowercase(),
        rowCount = rowCount,
        responseBytes = responseBytes,
        digest = digest
    )
}

private data class BroadQueryExecutionMetrics(
    val path: BroadQueryExecutionPath,
    val inputSourceCount: Long,
    val accessedGraphIds: Set<String>,
    val parallelScanGraphIds: Set<String>,
    val indexLookupsByGraph: Map<String, Long>,
    val targetGraphAccessCount: Long,
    val nonTargetGraphAccessCount: Long,
    val parallelScanCount: Long,
    val indexLookupCount: Long,
    val peakActiveWorkers: Long,
    val graphWorkerPeakActiveWorkers: Long,
    val segmentWorkerPeakActiveWorkers: Long,
    val graphWorkUnits: Long,
    val graphIdSourceSelections: Long,
    val graphIdSourcePruningExecutions: Long,
    val graphIdSourcesPruned: Long,
    val filteredNodeLimitFastPathExecutions: Long,
    val generalFallbackExecutions: Long
)

private data class BroadQueryPlannerDiagnostics(
    val graphIdSourceSelections: Long,
    val graphIdSourcePruningExecutions: Long,
    val graphIdSourcesPruned: Long,
    val filteredNodeLimitFastPathExecutions: Long,
    val generalFallbackExecutions: Long
) {
    companion object {
        val EMPTY = BroadQueryPlannerDiagnostics(0L, 0L, 0L, 0L, 0L)
    }
}

private data class BroadQueryGraphAccess(
    val graphId: String,
    val stringLookupEntries: Long,
    val parallelScans: Long,
    val serialScans: Long,
    val indexLookups: Long,
    val preflightChecks: Long,
    val projectionLookups: Long,
    val peakActiveWorkers: Long
) {
    fun wasAccessed(): Boolean = stringLookupEntries > 0L || parallelScans > 0L || serialScans > 0L ||
        indexLookups > 0L ||
        preflightChecks > 0L ||
        projectionLookups > 0L
}

private enum class BroadQueryExecutionPath(val id: String) {
    /** A caller-selected source list, equivalent to post-request-selection execution; no HTTP is measured. */
    REQUEST_SELECTED_SOURCE("request-selected-source"),

    /** All loaded sources enter Cypher; the graphId predicate must route to the target source. */
    CYPHER_GRAPH_ID_PREDICATE("cypher-graph-id-predicate"),

    CROSS_GRAPH_QUERY("cross-graph-query")
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
    GLOBAL_WIDE("global-wide"),
    REGEX("regex"),
    GRAPH_ID("graph-id"),
    GRAPH_PARAMETER("graph-parameter"),
    GRAPH_ID_SET("graph-id-set"),
    GRAPH_SET_REFERENCE("graph-set-reference");

    fun isGraphRouting(): Boolean = isGraphIdPredicate() || isRequestSelectedReference()

    fun isGraphIdPredicate(): Boolean = this == GRAPH_ID || this == GRAPH_ID_SET

    fun isRequestSelectedReference(): Boolean = this == GRAPH_PARAMETER || this == GRAPH_SET_REFERENCE

    fun isGlobalWidePressure(): Boolean = this == GLOBAL_WIDE
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
    val routingTerms: Map<BroadQuerySelectivity, String>,
    val workloadIdentity: String
)

private data class BroadQueryFixtureDistribution(
    val id: String,
    val targetGraphId: String,
    val term: String,
    val hitGraphIds: List<String>
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
                "<TAB><zero-term><TAB><targeted-term><TAB><dense-term><TAB><workload-identity>"
        }
        val path = Path.of(columns[1].trim()).toRealPath()
        require(Files.isDirectory(path)) { "$manifest:${lineIndex + 1} graph path not found: $path" }
        val terms = BroadQuerySelectivity.entries.zip(columns.subList(2, 5).map(String::trim)).toMap()
        require(terms.values.map(String::lowercase).toSet().size == BroadQuerySelectivity.entries.size) {
            "$manifest:${lineIndex + 1} zero, targeted, and dense terms must be distinct"
        }
        val workloadIdentity = columns[5].trim()
        require(SHA_256_IDENTITY.matches(workloadIdentity)) {
            "$manifest:${lineIndex + 1} workload identity must be a lowercase SHA-256 value"
        }
        BroadQueryGraphSource(columns[0].trim(), path, terms, workloadIdentity)
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

private fun broadQueryFixtureDistributions(
    graphSources: List<BroadQueryGraphSource>
): Map<String, BroadQueryFixtureDistribution> {
    val manifest = Path.of(checkNotNull(System.getProperty(GRAPH_MANIFEST_PROPERTY)))
        .toAbsolutePath().normalize()
    val graphIds = graphSources.map(BroadQueryGraphSource::id)
    val graphIdSet = graphIds.toSet()
    val records = Files.readAllLines(manifest)
        .filter { line -> line.startsWith(WIDE_DISTRIBUTION_PREFIX) }
        .map { line ->
            val fields = line.split('\t')
            require(fields.size == WIDE_DISTRIBUTION_FIELD_COUNT) {
                "$manifest has a malformed fixture64 global-wide distribution record"
            }
            BroadQueryFixtureDistribution(fields[1], fields[2], fields[3], fields[4].split(','))
        }
    require(records.map(BroadQueryFixtureDistribution::id).toSet() == REQUIRED_WIDE_DISTRIBUTIONS) {
        "$manifest must contain exactly ${REQUIRED_WIDE_DISTRIBUTIONS.sorted()} distribution records"
    }
    records.forEach { record ->
        require(record.targetGraphId in graphIdSet && record.hitGraphIds.isNotEmpty() &&
            record.hitGraphIds.toSet().size == record.hitGraphIds.size &&
            record.hitGraphIds.all { graphId -> graphId in graphIdSet }
        ) {
            "$manifest distribution ${record.id} is not bound to the 64 graph rows"
        }
        require(record.hitGraphIds == graphIds.filter(record.hitGraphIds.toSet()::contains)) {
            "$manifest distribution ${record.id} hit graphs must retain manifest order"
        }
    }
    return records.associateBy(BroadQueryFixtureDistribution::id)
}

private data class BroadQueryTarget(
    val graphId: String,
    val graphIndex: Int,
    val selectivity: BroadQuerySelectivity,
    val routingTerm: String? = null,
    val workloadIdentity: String? = null
)

private fun broadQueryCoverageWorkload(
    graphSources: List<BroadQueryGraphSource>,
    fixtureDistributions: Map<String, BroadQueryFixtureDistribution>
): List<BroadQueryCase> = buildList {
    require(graphSources.isNotEmpty())
    var globalWideShapeIndex = 0
    BROAD_QUERY_COVERAGE.forEachIndexed { shapeIndex, spec ->
        val placementShapeIndex = if (spec.family.isGlobalWidePressure()) globalWideShapeIndex++ else 0
        val targets = if (spec.family.isGraphRouting()) {
            graphSources.flatMapIndexed { graphIndex, source ->
                BroadQuerySelectivity.entries.map { selectivity ->
                    BroadQueryTarget(
                        source.id,
                        graphIndex,
                        selectivity,
                        source.routingTerms.getValue(selectivity),
                        source.workloadIdentity
                    )
                }
            }
        } else if (spec.family.isGlobalWidePressure()) {
            BroadQuerySelectivity.entries.map { selectivity ->
                val placementIndex = when (selectivity) {
                    BroadQuerySelectivity.DENSE -> 0
                    else -> if (graphSources.size == 1) {
                        0
                    } else {
                        placementShapeIndex * graphSources.lastIndex / (GLOBAL_WIDE_SHAPE_COUNT - 1)
                    }
                }
                val source = graphSources[placementIndex]
                BroadQueryTarget(
                    source.id,
                    placementIndex,
                    selectivity,
                    source.routingTerms.getValue(selectivity),
                    source.workloadIdentity
                )
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
            val absent = if (spec.family.isGraphRouting() || spec.family.isGlobalWidePressure()) {
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
                    workloadIdentity = target.workloadIdentity.takeIf {
                        spec.family.isGraphRouting() || spec.family.isGlobalWidePressure()
                    },
                    requestGraphIds = listOf(targetGraphId).takeIf {
                        spec.family == BroadQueryFamily.GRAPH_PARAMETER
                    },
                    expectedRowCountRange = if (spec.family == BroadQueryFamily.GRAPH_PARAMETER) {
                        selectivity.expectedReferenceRows()
                    } else if (spec.family.isGlobalWidePressure()) {
                        when (selectivity) {
                            BroadQuerySelectivity.ZERO -> 0L..0L
                            BroadQuerySelectivity.TARGETED -> 1L until ROUTING_RESULT_LIMIT
                            BroadQuerySelectivity.DENSE -> ROUTING_RESULT_LIMIT..ROUTING_RESULT_LIMIT
                        }
                    } else {
                        null
                    }
                )
            )
        }
    }
    check(globalWideShapeIndex == GLOBAL_WIDE_SHAPE_COUNT)
    if (graphSources.size == MAX_GRAPH_COUNT) {
        val coldRawControl = indexOfFirst { case ->
            case.shape == "global-wide-four-properties" &&
                case.selectivity == BroadQuerySelectivity.DENSE
        }
        check(coldRawControl >= 0) { "Missing cold raw global-wide control" }
        add(0, removeAt(coldRawControl))
        addFixture64GlobalWideDistributionCases(graphSources, fixtureDistributions)
        addFixture64GraphSetCases(graphSources)
        val coldFirst = indexOfFirst { case ->
            case.shape == GRAPH_SET_REFERENCE_SHAPE &&
                case.selectivity == BroadQuerySelectivity.ZERO &&
                case.requestGraphIds?.size == MAX_GRAPH_COUNT
        }
        check(coldFirst >= 0) { "Missing cold-first K64 request-selected graph-set case" }
        add(0, removeAt(coldFirst))
    }
}

private fun MutableList<BroadQueryCase>.addFixture64GlobalWideDistributionCases(
    graphSources: List<BroadQueryGraphSource>,
    distributions: Map<String, BroadQueryFixtureDistribution>
) {
    REQUIRED_WIDE_DISTRIBUTIONS.sorted().forEach { distributionId ->
        val distribution = checkNotNull(distributions[distributionId])
        val source = graphSources.single { candidate -> candidate.id == distribution.targetGraphId }
        val localized = distributionId != BROAD_WIDE_DISTRIBUTION
        require(if (localized) {
            distribution.hitGraphIds == listOf(distribution.targetGraphId)
        } else {
            distribution.hitGraphIds == graphSources.map(BroadQueryGraphSource::id)
        })
        add(
            BroadQueryCase(
                id = "global-wide-distribution-$distributionId",
                family = BroadQueryFamily.GLOBAL_WIDE,
                shape = "global-wide-distribution-$distributionId",
                selectivity = BroadQuerySelectivity.DENSE,
                operator = "wrapped-lowercase-contains",
                boundary = "fixture-distribution",
                projection = "properties",
                limit = ROUTING_RESULT_LIMIT.toInt(),
                query = requestGraphWrappedContains(distribution.term),
                parameters = emptyMap(),
                expectZeroRows = false,
                workloadIdentity = source.workloadIdentity,
                expectedRowCountRange = ROUTING_RESULT_LIMIT.toLong()..ROUTING_RESULT_LIMIT.toLong(),
                fixtureDistributionId = distributionId
            )
        )
    }
}

private fun MutableList<BroadQueryCase>.addFixture64GraphSetCases(
    graphSources: List<BroadQueryGraphSource>
) {
    GRAPH_SET_WIDTHS.forEach { width ->
        graphSources.chunked(width).forEachIndexed { groupIndex, selectedSources ->
            check(selectedSources.size == width)
            val selectedGraphIds = selectedSources.map(BroadQueryGraphSource::id)
            val targetGraphId = selectedGraphIds.first()
            val workloadIdentity = sha256Identity(
                selectedSources.joinToString("\u0000") { source ->
                    "${source.id}\u0000${source.workloadIdentity}"
                }
            )
            val groupSuffix = "k${width.toString().padStart(2, '0')}-group-" +
                groupIndex.toString().padStart(2, '0')
            BroadQuerySelectivity.entries.forEach { selectivity ->
                val term = selectedSources.first().routingTerms.getValue(selectivity)
                val lowerTerm = term.lowercase()
                val shared = BroadQuerySetCaseFields(
                    selectivity = selectivity,
                    targetGraphId = targetGraphId,
                    targetGraphIds = selectedGraphIds,
                    workloadIdentity = workloadIdentity,
                    suffix = "$groupSuffix-${selectivity.id}"
                )
                add(
                    shared.case(
                        shape = GRAPH_ID_IN_LITERAL_SHAPE,
                        family = BroadQueryFamily.GRAPH_ID_SET,
                        operator = "graph-id-in-literal-and-wrapped-contains",
                        boundary = "graph-routing-set",
                        query = graphIdInLiteralWrappedContains(selectedGraphIds, lowerTerm)
                    )
                )
                add(
                    shared.case(
                        shape = GRAPH_ID_IN_PARAMETER_SHAPE,
                        family = BroadQueryFamily.GRAPH_ID_SET,
                        operator = "graph-id-in-parameter-and-wrapped-contains",
                        boundary = "parameters",
                        query = parameterizedGraphIdInWrappedContains(),
                        parameters = mapOf("graphIds" to selectedGraphIds, "term" to lowerTerm)
                    )
                )
                add(
                    shared.case(
                        shape = GRAPH_SET_REFERENCE_SHAPE,
                        family = BroadQueryFamily.GRAPH_SET_REFERENCE,
                        operator = "request-graph-set-selection-and-wrapped-contains",
                        boundary = "request-selected-source",
                        query = requestGraphWrappedContains(term),
                        requestGraphIds = selectedGraphIds
                    )
                )
            }
        }
    }
}

private data class BroadQuerySetCaseFields(
    val selectivity: BroadQuerySelectivity,
    val targetGraphId: String,
    val targetGraphIds: List<String>,
    val workloadIdentity: String,
    val suffix: String
) {
    @Suppress("LongParameterList")
    fun case(
        shape: String,
        family: BroadQueryFamily,
        operator: String,
        boundary: String,
        query: String,
        parameters: Map<String, Any?> = emptyMap(),
        requestGraphIds: List<String>? = null
    ): BroadQueryCase = BroadQueryCase(
        id = "$shape-$suffix",
        family = family,
        shape = shape,
        selectivity = selectivity,
        operator = operator,
        boundary = boundary,
        projection = "properties",
        limit = ROUTING_RESULT_LIMIT.toInt(),
        query = query,
        parameters = parameters,
        expectZeroRows = false,
        targetGraphId = targetGraphId,
        targetGraphIds = targetGraphIds,
        workloadIdentity = workloadIdentity,
        requestGraphIds = requestGraphIds
    )
}

private fun sha256Identity(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

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

private fun globalWidePredicate(term: String): String =
    "n.caller_class CONTAINS '${cypherString(term)}' OR " +
        "n.caller_name CONTAINS '${cypherString(term)}' OR " +
        "n.callee_class CONTAINS '${cypherString(term)}' OR " +
        "n.callee_name CONTAINS '${cypherString(term)}'"

private fun globalWideProjection(term: String, projection: String): String = """
    MATCH (n)
    WHERE ${globalWidePredicate(term)}
    RETURN $projection
    LIMIT 200
""".trimIndent()

private fun parameterizedGlobalWideProjection(projection: String): String = """
    MATCH (n)
    WHERE n.caller_class CONTAINS ${'$'}term
       OR n.caller_name CONTAINS ${'$'}term
       OR n.callee_class CONTAINS ${'$'}term
       OR n.callee_name CONTAINS ${'$'}term
    RETURN $projection
    LIMIT 200
""".trimIndent()

private const val GLOBAL_WIDE_SHAPE_COUNT = 10
private const val WIDE_DISTRIBUTION_PREFIX = "# global-wide-distribution-v1"
private const val WIDE_DISTRIBUTION_FIELD_COUNT = 5
private const val BROAD_WIDE_DISTRIBUTION = "broad-all-64"
private val REQUIRED_WIDE_DISTRIBUTIONS = setOf(
    "localized-early",
    "localized-middle",
    "localized-late",
    BROAD_WIDE_DISTRIBUTION
)

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

private fun graphIdInLiteralWrappedContains(graphIds: List<String>, term: String): String {
    val literals = graphIds.joinToString(", ") { graphId -> "'${cypherString(graphId)}'" }
    return """
        MATCH (n)
        WHERE n.graphId IN [$literals]
          AND (toLower(coalesce(n.caller_class, '')) CONTAINS '${cypherString(term)}'
            OR toLower(coalesce(n.caller_name, '')) CONTAINS '${cypherString(term)}'
            OR toLower(coalesce(n.callee_class, '')) CONTAINS '${cypherString(term)}'
            OR toLower(coalesce(n.callee_name, '')) CONTAINS '${cypherString(term)}')
        RETURN n.caller_class, n.caller_name, n.callee_class, n.callee_name
        LIMIT 200
    """.trimIndent()
}

private fun parameterizedGraphIdInWrappedContains(): String = """
    MATCH (n)
    WHERE n.graphId IN ${'$'}graphIds
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
        operator = "raw-contains"
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
        "request-selected-source-wrapped-contains", BroadQueryFamily.GRAPH_PARAMETER, "properties", 200,
        operator = "request-graph-selection-and-wrapped-contains", boundary = "request-selected-source"
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
        "global-wide-four-properties", BroadQueryFamily.GLOBAL_WIDE, "properties", 200,
        operator = "raw-contains"
    ) {
        globalWideProjection(it.term, "n.caller_class, n.caller_name, n.callee_class, n.callee_name")
    },
    BroadQueryCoverageSpec(
        "global-wide-class-pair", BroadQueryFamily.GLOBAL_WIDE, "class-properties", 200,
        operator = "raw-contains"
    ) {
        globalWideProjection(it.term, "n.caller_class, n.callee_class")
    },
    BroadQueryCoverageSpec(
        "global-wide-name-pair", BroadQueryFamily.GLOBAL_WIDE, "name-properties", 200,
        operator = "raw-contains"
    ) {
        globalWideProjection(it.term, "n.caller_name, n.callee_name")
    },
    BroadQueryCoverageSpec(
        "global-wide-caller-class", BroadQueryFamily.GLOBAL_WIDE, "caller-class", 200,
        operator = "raw-contains"
    ) {
        globalWideProjection(it.term, "n.caller_class")
    },
    BroadQueryCoverageSpec(
        "global-wide-callee-class", BroadQueryFamily.GLOBAL_WIDE, "callee-class", 200,
        operator = "raw-contains"
    ) {
        globalWideProjection(it.term, "n.callee_class")
    },
    BroadQueryCoverageSpec(
        "global-wide-provenance", BroadQueryFamily.GLOBAL_WIDE, "graph-id-properties", 200,
        operator = "raw-contains"
    ) {
        globalWideProjection(
            it.term,
            "n.graphId, n.caller_class, n.caller_name, n.callee_class, n.callee_name"
        )
    },
    BroadQueryCoverageSpec(
        "global-wide-aliased", BroadQueryFamily.GLOBAL_WIDE, "aliased-properties", 200,
        operator = "raw-contains"
    ) {
        globalWideProjection(
            it.term,
            "n.caller_class AS caller, n.caller_name AS callerMethod, " +
                "n.callee_class AS callee, n.callee_name AS calleeMethod"
        )
    },
    BroadQueryCoverageSpec(
        "global-wide-parameterized", BroadQueryFamily.GLOBAL_WIDE, "properties", 200,
        operator = "raw-contains", boundary = "parameters",
        parameters = { mapOf("term" to it.term) }
    ) {
        parameterizedGlobalWideProjection("n.caller_class, n.caller_name, n.callee_class, n.callee_name")
    },
    BroadQueryCoverageSpec(
        "global-wide-wrapped-case-insensitive",
        BroadQueryFamily.GLOBAL_WIDE,
        "properties",
        200,
        operator = "wrapped-lowercase-contains"
    ) {
        requestGraphWrappedContains(it.term)
    },
    BroadQueryCoverageSpec(
        "global-wide-wrapped-case-insensitive-distinct",
        BroadQueryFamily.GLOBAL_WIDE,
        "distinct-properties",
        200,
        operator = "wrapped-lowercase-contains"
    ) {
        wrappedContainsOr(it.term)
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

private val pressureQueryExecutor:
    (List<CypherGraph>, CypherExecutionContext, Boolean) -> CrossGraphCypherExecutor = run {
    val scopedConstructor = runCatching {
        CrossGraphCypherExecutor::class.java.getConstructor(
            List::class.java,
            CypherExecutionContext::class.java,
            Boolean::class.javaPrimitiveType
        )
    }.getOrNull()
    val factory: (List<CypherGraph>, CypherExecutionContext, Boolean) -> CrossGraphCypherExecutor =
        { graphs, context, graphSourceScopeApplied ->
        if (graphSourceScopeApplied && scopedConstructor != null) {
            scopedConstructor.newInstance(graphs, context, true)
        } else {
            CrossGraphCypherExecutor(graphs, context)
        }
    }
    factory
}

private const val COLD_INDEX_STATE = "cold"
private const val WARM_INDEX_STATE = "warm"
private const val STARTUP_PREPARED_INDEX_STATE = "startup-prepared"
private const val LAZY_INDEX_PREPARATION_MODE = "lazy"
private val BROAD_QUERY_INDEX_STATES = setOf(
    COLD_INDEX_STATE,
    WARM_INDEX_STATE,
    STARTUP_PREPARED_INDEX_STATE
)
private const val ALL_COVERAGE_FAMILIES = "all"
private const val GRAPH_ROUTING_COVERAGE_FAMILY = "graph-routing"
private const val GRAPH_ROUTING_REFERENCE_COVERAGE_FAMILY = "graph-routing-reference"
// Width one is the existing 64-target graphId equality matrix and remains the 10x latency gate.
private val GRAPH_SET_WIDTHS = listOf(2, 8, 64)
private const val GRAPH_ID_IN_LITERAL_SHAPE = "graph-id-in-literal-wrapped-contains"
private const val GRAPH_ID_IN_PARAMETER_SHAPE = "graph-id-in-parameter-wrapped-contains"
private const val GRAPH_SET_REFERENCE_SHAPE = "request-selected-set-wrapped-contains"
private const val GRAPH_MANIFEST_COLUMN_COUNT = 6
private const val ROUTING_RESULT_LIMIT = 200L
private const val OUTPUT_PROPERTY = "graphite.broad.pressure.output"
private const val OBSERVATIONS_OUTPUT_PROPERTY = "graphite.broad.pressure.observations.output"
private const val GRAPH_MANIFEST_PROPERTY = "graphite.broad.pressure.graphs"
private const val PREPARE_INDEX_ON_LOAD_PROPERTY = "graphite.webgraph.prepareCallSiteStringIndexOnLoad"
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
private val SHA_256_IDENTITY = Regex("[0-9a-f]{64}")
private const val GC_ATTEMPTS = 3
private const val GC_PAUSE_MILLIS = 100L

private val TARGETED_TERMS = listOf("android.", "org.apache.tika.", "org.apache.hadoop.hive.", "kotlin")
private val DENSE_TERMS = listOf("java", "org", "get", "set")
private val TARGETED_EXACT_CLASSES = listOf("java.util.List", "java.util.Map", "java.lang.Runnable")
private val DENSE_EXACT_CLASSES = listOf("java.lang.String", "java.lang.Object", "java.lang.Class")
private val TARGETED_EXACT_NAMES = listOf("main", "parse", "onCreate", "invoke")
private val DENSE_EXACT_NAMES = listOf("get", "set", "toString", "equals")
