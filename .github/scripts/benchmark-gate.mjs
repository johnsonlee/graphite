#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const COMMENT_MARKER = "<!-- graphite-benchmark-regression-gate -->";

export const BENCHMARK_COVERAGE_DOMAINS = [
    {
        name: "Semantic correctness",
        components: ["method-compatibility"],
        missing: ["universal-correctness-manifest", "isolated-workload-identity"]
    },
    {
        name: "Latency regression",
        components: [
            "method-level",
            "budgeted-collection",
            "budgeted-mapped-string",
            "wrapped-query-latency",
            "graph-routing-pressure"
        ],
        missing: ["cross-graph-dataflow-latency", "storage-load-stage-latency"]
    },
    {
        name: "Throughput and capacity",
        components: ["cypher-capacity"],
        missing: ["sustained-throughput", "saturation-capacity", "multi-client-capacity"]
    },
    {
        name: "Memory and resources",
        components: ["explorer", "wrapped-query-resources"],
        missing: ["allocation-per-operation", "memory-leak", "disk-and-temporary-space-budget"]
    },
    {
        name: "Scalability",
        components: [],
        missing: ["graph-build-scaling", "corpus-load-scaling", "concurrent-user-scaling"]
    },
    {
        name: "Build and persistence lifecycle",
        components: ["large-corpus"],
        missing: ["dedicated-graph-build", "dedicated-save-load", "persisted-mapped-query", "persistence-migration"]
    }
];

export const BENCHMARK_COMPONENTS = [
    {
        name: "method-level",
        report: "method-report.md",
        status: "method-status.json",
        coverage: "partial",
        gap: "Low sample count and hosted-runner variance."
    },
    {
        name: "explorer",
        report: "explorer-report.md",
        status: "explorer-status.json",
        coverage: "partial",
        gap: "Four fixture scenarios; no allocation or leak proof."
    },
    {
        name: "method-compatibility",
        report: "method-compatibility-report.md",
        status: "method-compatibility-status.json",
        coverage: "partial",
        gap: "Fixed discovery matrix only; no universal semantic coverage."
    },
    {
        name: "cypher-capacity",
        report: "cypher-capacity-report.md",
        status: "cypher-capacity-status.json",
        coverage: "partial",
        gap: "One composite point and limited concurrency shapes."
    },
    {
        name: "budgeted-collection",
        report: "budgeted-collection-report.md",
        status: "budgeted-collection-status.json",
        coverage: "partial",
        gap: "Fixed historical baseline; no current-base comparison."
    },
    {
        name: "budgeted-mapped-string",
        report: "budgeted-string-report.md",
        status: "budgeted-string-status.json",
        coverage: "partial",
        gap: "Fixed historical baseline; no current-base comparison."
    },
    {
        name: "large-corpus",
        report: "large-corpus-report.md",
        status: "large-corpus-status.json",
        coverage: "partial",
        gap: "Three fixed corpora; stage rows do not provide universal corpus or lifecycle coverage."
    },
    {
        name: "wrapped-query-latency",
        report: "latency-report.md",
        status: "latency-status.json",
        coverage: "complete",
        gap: "No gate-specific gap identified."
    },
    {
        name: "wrapped-query-resources",
        report: "latency-resource-report.md",
        status: "latency-resource-status.json",
        coverage: "partial",
        gap: "Exact point probes only; no long-duration leak or soak proof."
    },
    {
        name: "graph-routing-pressure",
        report: "graph-routing-report.md",
        status: "graph-routing-status.json",
        coverage: "complete",
        gap: "No gate-specific gap identified."
    }
];

const MIB = 1024 * 1024;
export const LATENCY_EXPECTED_BENCHMARK_KEYS = [
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.broadlyDistributedClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.denseDistributedMethodContainsCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.earlyGraphClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.firstLastGraphBimodalClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.lateGraphClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.middleGraphsClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.skewedMixedClassMethodOperatorCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.zeroHitBroadContainsCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.RealThirtySixGraphWrappedDiscoveryLatencyBenchmark.zeroHitBroadContainsAcrossThirtySixRealGraphs"
];
export const LATENCY_EXPECTED_SHARDS = [
    "real-a", "real-b", "real-c", "real-d", "real-36"
];
export const LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS = [
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryResourceBenchmark.allFixtureThirtySixGraphFootprint"
];
const GIB = 1024 * MIB;
const LATENCY_RESOURCE_PROFILES = new Map([
    [LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS[0], { maxHeapBytes: 8 * GIB }]
]);
const LATENCY_RESOURCE_METRICS = [
    { key: "gc.alloc.rate.norm", label: "allocation", threshold: 15, minimum: 4_096 },
    { key: "queryGcCount", label: "query GC count", threshold: 15, minimum: 1 },
    { key: "queryGcTimeMs", label: "query GC time", threshold: 15, minimum: 10 },
    { key: "retainedHeapDeltaBytes", label: "retained heap delta", threshold: 15, minimum: 16 * MIB },
    { key: "peakUsedHeapBytes", label: "peak used heap", threshold: 15, minimum: 64 * MIB }
];
const LATENCY_RESOURCE_EVENT_METRICS = new Set([
    "maxHeapBytes", "loadedHeapBytes", "peakUsedHeapBytes", "retainedHeapBytes",
    "retainedHeapDeltaBytes", "queryGcCount", "queryGcTimeMs"
]);
const LARGE_CORPUS_METRICS = [
    { key: "buildMs", label: "build", threshold: 20, minimum: 500, unit: "ms" },
    { key: "saveMs", label: "save", threshold: 25, minimum: 250, unit: "ms" },
    { key: "mappedLoadMs", label: "mapped load", threshold: 30, minimum: 50, unit: "ms" },
    { key: "queryMs", label: "query", threshold: 25, minimum: 250, unit: "ms" },
    { key: "pipelineMs", label: "pipeline", threshold: 20, minimum: 1_000, unit: "ms" },
    { key: "peakHeapBytes", label: "peak heap", unit: "bytes", advisory: true }
];
export const LARGE_CORPUS_EXPECTED_CORPORA = ["tika", "hive", "kotlin-compiler"];
const LARGE_CORPUS_SHAPE_FIELDS = ["nodes", "sourceEdges", "persistedEdges", "methods", "callSites"];
const LARGE_CORPUS_PERSISTED_BYTES_TOLERANCE = 4 * 1024;
const LARGE_CORPUS_MAPPED_LOAD_SAMPLES = 5;

function finiteNumber(value) {
    if (value === null || value === undefined || value === "") return null;
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
}

function parseArgs(argv) {
    const args = { _: [] };
    for (let index = 0; index < argv.length; index++) {
        const argument = argv[index];
        if (!argument.startsWith("--")) {
            args._.push(argument);
            continue;
        }
        const name = argument.slice(2);
        const value = argv[index + 1];
        if (value === undefined || value.startsWith("--")) {
            args[name] = true;
        } else {
            args[name] = value;
            index++;
        }
    }
    return args;
}

function requireArg(args, name) {
    const value = args[name];
    if (typeof value !== "string" || value.length === 0) {
        throw new Error(`Missing --${name}`);
    }
    return value;
}

function readJson(file) {
    return JSON.parse(fs.readFileSync(file, "utf8"));
}

function writeFile(file, contents) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, contents);
}

function writeJson(file, value) {
    writeFile(file, `${JSON.stringify(value, null, 2)}\n`);
}

function benchmarkKey(result) {
    const parameters = Object.entries(result.params ?? {})
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([name, value]) => `${name}=${value}`)
        .join(",");
    return parameters.length === 0 ? result.benchmark : `${result.benchmark}[${parameters}]`;
}

function resultMap(results, revision, errors) {
    const mapped = new Map();
    for (const result of results) {
        const key = benchmarkKey(result);
        if (mapped.has(key)) errors.push(`${revision}: duplicate benchmark ${key}`);
        else mapped.set(key, result);
    }
    return mapped;
}

function secondaryMetric(result, name) {
    return result.secondaryMetrics?.[name] ?? null;
}

function rawMetricValues(metric) {
    if (!Array.isArray(metric?.rawData) || metric.rawData.length === 0) return null;
    const values = [];
    for (const fork of metric.rawData) {
        if (!Array.isArray(fork) || fork.length === 0) return null;
        for (const value of fork) {
            const number = finiteNumber(value);
            if (number === null) return null;
            values.push(number);
        }
    }
    return values.length === 0 ? null : values;
}

function resourceMetricValue(result, name) {
    const metric = secondaryMetric(result, name);
    if (!LATENCY_RESOURCE_EVENT_METRICS.has(name)) return finiteNumber(metric?.score);
    const values = rawMetricValues(metric);
    return values === null ? null : values.reduce((sum, value) => sum + value, 0) / values.length;
}

function parseMaximumHeapArguments(result) {
    if (!Array.isArray(result.jvmArgs)) return [];
    return result.jvmArgs.filter((argument) => /^-Xmx/i.test(argument));
}

function shortBenchmarkName(name) {
    return name
        .replace(/^io\.johnsonlee\.graphite\./, "")
        .replace(/\|/g, "\\|");
}

function confidenceBounds(metric) {
    if (!Array.isArray(metric.scoreConfidence) || metric.scoreConfidence.length !== 2) return null;
    const lower = finiteNumber(metric.scoreConfidence[0]);
    const upper = finiteNumber(metric.scoreConfidence[1]);
    return lower !== null && upper !== null && lower <= upper ? [lower, upper] : null;
}

function isLowerBetter(mode) {
    return ["avgt", "sample", "ss"].includes(String(mode).toLowerCase());
}

function regressionPercent(baseScore, candidateScore, lowerIsBetter) {
    if (baseScore === 0) return candidateScore === 0 ? 0 : Number.POSITIVE_INFINITY;
    if (baseScore < 0) return Number.POSITIVE_INFINITY;
    return lowerIsBetter
        ? ((candidateScore / baseScore) - 1) * 100
        : (1 - (candidateScore / baseScore)) * 100;
}

function confidenceSeparates(baseBounds, candidateBounds, lowerIsBetter) {
    if (baseBounds === null || candidateBounds === null) return true;
    return lowerIsBetter
        ? candidateBounds[0] > baseBounds[1]
        : candidateBounds[1] < baseBounds[0];
}

function formatScore(score) {
    if (!Number.isFinite(score)) return "n/a";
    if (Math.abs(score) >= 1_000) return score.toFixed(1);
    if (Math.abs(score) >= 1) return score.toFixed(3);
    return score.toFixed(4);
}

function formatDelta(delta) {
    if (!Number.isFinite(delta)) return "n/a";
    return `${delta >= 0 ? "+" : ""}${delta.toFixed(1)}%`;
}

function statusLabel(row) {
    if (row.advisory) return "INFO";
    if (row.blocked) return "FAIL";
    if (row.aboveThreshold) return "NOISE";
    return "PASS";
}

export function compareJmh(
    baseResults,
    candidateResults,
    threshold = 15,
    thresholdOnly = false,
    allowZeroBase = false
) {
    const errors = [];
    const base = resultMap(baseResults, "base", errors);
    const candidate = resultMap(candidateResults, "candidate", errors);

    const allKeys = [...new Set([...base.keys(), ...candidate.keys()])].sort();
    const rows = [];
    for (const key of allKeys) {
        const baseline = base.get(key);
        const current = candidate.get(key);
        if (baseline === undefined || current === undefined) {
            errors.push(`${key}: missing from ${baseline === undefined ? "base" : "candidate"} results`);
            continue;
        }

        const baseMetric = baseline.primaryMetric ?? {};
        const candidateMetric = current.primaryMetric ?? {};
        const baseScore = finiteNumber(baseMetric.score);
        const candidateScore = finiteNumber(candidateMetric.score);
        if (baseScore === null || candidateScore === null ||
            (allowZeroBase ? baseScore < 0 : baseScore <= 0) || candidateScore < 0
        ) {
            errors.push(`${key}: invalid score`);
            continue;
        }
        if (baseline.mode !== current.mode || baseMetric.scoreUnit !== candidateMetric.scoreUnit) {
            errors.push(`${key}: base and candidate use different mode or unit`);
            continue;
        }

        const lowerBetter = isLowerBetter(baseline.mode);
        if (!lowerBetter && String(baseline.mode).toLowerCase() !== "thrpt") {
            errors.push(`${key}: unsupported JMH mode ${baseline.mode}`);
            continue;
        }
        const delta = regressionPercent(baseScore, candidateScore, lowerBetter);
        const aboveThreshold = delta > threshold;
        const separated = confidenceSeparates(
            confidenceBounds(baseMetric),
            confidenceBounds(candidateMetric),
            lowerBetter
        );
        rows.push({
            key,
            baseScore,
            candidateScore,
            unit: baseMetric.scoreUnit,
            delta,
            threshold,
            aboveThreshold,
            confidenceSeparated: separated,
            blocked: aboveThreshold && (thresholdOnly || separated)
        });
    }

    if (rows.length === 0) errors.push("No comparable JMH benchmarks were found");
    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        thresholdOnly,
        allowZeroBase,
        rows
    };
}

export function selectJmhMetric(results, metricName) {
    if (metricName === undefined || metricName === null) return results;
    return results.map((result) => ({
        ...result,
        primaryMetric: result.secondaryMetrics?.[metricName] ?? {}
    }));
}

export function makeJmhAdvisory(comparison) {
    return {
        ...comparison,
        passed: comparison.errors.length === 0,
        advisory: true,
        rows: comparison.rows.map((row) => ({ ...row, advisory: true, blocked: false }))
    };
}

export function confirmJmh(initial, confirmation) {
    const errors = [
        ...initial.errors,
        ...confirmation.errors.map((error) => `confirmation: ${error}`)
    ];
    const confirmationRows = new Map(confirmation.rows.map((row) => [row.key, row]));
    const rows = initial.rows.map((row) => {
        if (!row.blocked) return row;
        const retry = confirmationRows.get(row.key);
        if (retry === undefined) {
            errors.push(`${row.key}: missing from confirmation results`);
            return row;
        }
        return {
            ...row,
            confirmation: {
                baseScore: retry.baseScore,
                candidateScore: retry.candidateScore,
                delta: retry.delta,
                blocked: retry.blocked
            },
            blocked: retry.blocked
        };
    });
    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        thresholdOnly: initial.thresholdOnly === true,
        rows
    };
}

export function renderJmhReport(comparison, title = "Method-level JMH") {
    const decisionRule = comparison.advisory === true
        ? ["This metric is reported for context and does not block the regression gate."]
        : comparison.thresholdOnly === true
        ? [
            "A row runs reverse-order confirmation whenever it exceeds the 15% limit, regardless of confidence",
            "interval overlap, and blocks only when the confirmation also exceeds 15%."
        ]
        : [
            "A row blocks only when it exceeds the 15% limit, the 99.9% confidence intervals do not overlap,",
            "and a reverse-order confirmation run fails the same benchmark."
        ];
    const lines = [
        `### ${title}`,
        "",
        ...decisionRule,
        "",
        "| Benchmark | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |",
        "|---|---:|---:|---:|---:|---:|:---:|"
    ];
    for (const row of comparison.rows) {
        const confirmation = row.confirmation === undefined
            ? "-"
            : `${formatScore(row.confirmation.baseScore)} -> ${formatScore(row.confirmation.candidateScore)} ` +
                `${row.unit} (${formatDelta(row.confirmation.delta)})`;
        lines.push(
            `| \`${shortBenchmarkName(row.key)}\` | ${formatScore(row.baseScore)} ${row.unit} | ` +
            `${formatScore(row.candidateScore)} ${row.unit} | ${formatDelta(row.delta)} | ` +
            `${confirmation} | ${row.threshold.toFixed(0)}% | **${statusLabel(row)}** |`
        );
    }
    if (comparison.errors.length > 0) {
        lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    }
    return `${lines.join("\n")}\n`;
}

export function compareLatencyBaseline(
    fixedResults,
    baseResults,
    candidateResults,
    regressionThreshold = 15,
    minimumSpeedup = 50,
    expectedKeys = null
) {
    const regression = compareJmh(baseResults, candidateResults, regressionThreshold);
    const errors = [...regression.errors];
    const fixed = resultMap(fixedResults, "fixed baseline", errors);
    const candidate = new Map(candidateResults.map((result) => [benchmarkKey(result), result]));
    const regressionRows = new Map(regression.rows.map((row) => [row.key, row]));
    const rows = [];

    const actualKeys = [...new Set([...fixed.keys(), ...candidate.keys()])].sort();
    const keys = expectedKeys === null ? actualKeys : [...expectedKeys].sort();
    if (expectedKeys !== null) {
        const expected = new Set(expectedKeys);
        for (const [revision, results] of [
            ["fixed baseline", fixedResults],
            ["PR base", baseResults],
            ["candidate", candidateResults]
        ]) {
            const actual = new Set(results.map(benchmarkKey));
            for (const key of expected) {
                if (!actual.has(key)) errors.push(`${revision}: missing expected latency benchmark ${key}`);
            }
            for (const key of actual) {
                if (!expected.has(key)) errors.push(`${revision}: unexpected latency benchmark ${key}`);
            }
        }
    }

    for (const key of keys) {
        const baseline = fixed.get(key);
        const current = candidate.get(key);
        const baseRow = regressionRows.get(key);
        if (baseline === undefined || current === undefined || baseRow === undefined) {
            errors.push(`${key}: missing from fixed baseline, PR base, or candidate results`);
            continue;
        }
        const fixedScore = finiteNumber(baseline.primaryMetric?.score);
        const candidateScore = finiteNumber(current.primaryMetric?.score);
        if (fixedScore === null || candidateScore === null || fixedScore <= 0 || candidateScore <= 0) {
            errors.push(`${key}: invalid fixed-baseline score`);
            continue;
        }
        if (!isLowerBetter(baseline.mode) || baseline.mode !== current.mode ||
            baseline.primaryMetric?.scoreUnit !== current.primaryMetric?.scoreUnit
        ) {
            errors.push(`${key}: fixed baseline and candidate use incompatible latency metrics`);
            continue;
        }
        const fixedBounds = confidenceBounds(baseline.primaryMetric ?? {});
        const candidateBounds = confidenceBounds(current.primaryMetric ?? {});
        if (fixedBounds === null || candidateBounds === null) {
            errors.push(`${key}: fixed-baseline speedup requires finite confidence bounds`);
        }
        const speedup = ((fixedScore / candidateScore) - 1) * 100;
        const multiGraph = /AllFixture|ThirtySix/.test(key);
        const requiredSpeedup = multiGraph ? Math.max(minimumSpeedup, 900) : minimumSpeedup;
        const improvementSeparated = fixedBounds !== null && candidateBounds !== null &&
            confidenceSeparates(candidateBounds, fixedBounds, true);
        const improvementBlocked = speedup < requiredSpeedup || !improvementSeparated;
        rows.push({
            ...baseRow,
            fixedScore,
            speedup,
            minimumSpeedup: requiredSpeedup,
            improvementSeparated,
            improvementBlocked,
            blocked: baseRow.blocked || improvementBlocked
        });
    }

    if (rows.length === 0) errors.push("No comparable latency benchmarks were found");
    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

function parsePressureObservations(contents, revision, errors) {
    const lines = contents.trim().split(/\r?\n/);
    if (lines.length < 2) {
        errors.push(`${revision}: pressure observations are empty`);
        return [];
    }
    const headers = lines[0].split("\t");
    const required = [
        "id", "family", "shape", "selectivity", "targetGraphId", "outcome", "rowCount",
        "responseBytes", "digest", "latencyNanos"
    ];
    for (const header of required) {
        if (!headers.includes(header)) errors.push(`${revision}: pressure observations missing ${header}`);
    }
    const rows = lines.slice(1).map((line) => {
        const values = line.split("\t");
        return Object.fromEntries(headers.map((header, index) => [header, values[index]]));
    }).filter((row) => row.family === "graph-id" || row.family === "graph-parameter");
    const seen = new Set();
    for (const row of rows) {
        if (seen.has(row.id)) errors.push(`${revision}: duplicate graph-routing observation ${row.id}`);
        seen.add(row.id);
    }
    return rows;
}

function pressureMetric(result, name) {
    return finiteNumber(result?.secondaryMetrics?.[name]?.score);
}

function pressurePercentile(values, fraction) {
    const sorted = [...values].sort((left, right) => left - right);
    return sorted[Math.max(0, Math.ceil(sorted.length * fraction) - 1)];
}

export function compareGraphIdPressure(
    baseResults,
    candidateResults,
    baseObservations,
    candidateObservations,
    minimumSpeedup = 10
) {
    const errors = [];
    const expectedBenchmark = "io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries";
    const selectResult = (results, revision) => {
        const matches = results.filter((result) => result.benchmark === expectedBenchmark &&
            result.params?.graphCount === "64" && result.params?.coverageFamily === "graph-routing");
        if (matches.length !== 1) {
            errors.push(`${revision}: expected exactly one 64-graph graph-routing pressure result`);
            return null;
        }
        const result = matches[0];
        for (const [metric, expected] of [
            ["graphCount", 64], ["distinctGraphPathCount", 64], ["queryCount", 768],
            ["successCount", 768], ["timeoutCount", 0], ["failureCount", 0],
            ["graphIdTargetCount", 64], ["graphParameterTargetCount", 64],
            ["coverageShapeCount", 4], ["coverageFamilyCount", 2], ["coverageSelectivityCount", 3]
        ]) {
            const actual = pressureMetric(result, metric);
            if (actual !== expected) errors.push(`${revision}: ${metric}=${actual}; expected ${expected}`);
        }
        return result;
    };
    const baseResult = selectResult(baseResults, "base");
    const candidateResult = selectResult(candidateResults, "candidate");
    const resourceMetricNames = [
        "cpuCoreUtilizationPermille", "peakUsedHeapBytes", "peakResidentSetBytes",
        "gcCount", "gcMillis", "callSiteIndexAdmittedGraphs", "callSiteIndexRetainedBytes",
        "callSiteParallelScanCount", "callSiteScanPeakActiveWorkers"
    ];
    const resourceSnapshot = (result, revision) => Object.fromEntries(resourceMetricNames.map((name) => {
        const value = pressureMetric(result, name);
        if (value === null || value < 0) errors.push(`${revision}: ${name} requires a non-negative finite value`);
        return [name, value ?? 0];
    }));
    const baseResources = resourceSnapshot(baseResult, "base");
    const candidateResources = resourceSnapshot(candidateResult, "candidate");
    if (candidateResources.callSiteParallelScanCount <= 0) {
        errors.push("candidate: selected-graph workload did not execute an intra-graph parallel scan");
    }
    if (candidateResources.callSiteScanPeakActiveWorkers < 2) {
        errors.push("candidate: selected-graph workload did not prove at least two simultaneously active scan workers");
    }

    const baseRows = parsePressureObservations(baseObservations, "base", errors);
    const candidateRows = parsePressureObservations(candidateObservations, "candidate", errors);
    const baseGraphIdRows = baseRows.filter((row) => row.family === "graph-id");
    const candidateGraphIdRows = candidateRows.filter((row) => row.family === "graph-id");
    const baseGraphParameterRows = baseRows.filter((row) => row.family === "graph-parameter");
    const candidateGraphParameterRows = candidateRows.filter((row) => row.family === "graph-parameter");
    const baseById = new Map(baseGraphIdRows.map((row) => [row.id, row]));
    const candidateById = new Map(candidateGraphIdRows.map((row) => [row.id, row]));
    const ids = [...new Set([...baseById.keys(), ...candidateById.keys()])].sort();
    if (ids.length !== 576) errors.push(`expected 576 graph-id observations, found ${ids.length}`);
    const rows = [];
    for (const id of ids) {
        const base = baseById.get(id);
        const candidate = candidateById.get(id);
        if (base === undefined || candidate === undefined) {
            errors.push(`${id}: missing from base or candidate observations`);
            continue;
        }
        if (base.targetGraphId !== candidate.targetGraphId) {
            errors.push(`${id}: target graph id differs between base and candidate`);
            continue;
        }
        const baseLatencyNanos = finiteNumber(base.latencyNanos);
        const candidateLatencyNanos = finiteNumber(candidate.latencyNanos);
        if (base.outcome !== "success" || candidate.outcome !== "success" ||
            baseLatencyNanos === null || candidateLatencyNanos === null ||
            baseLatencyNanos <= 0 || candidateLatencyNanos <= 0
        ) {
            errors.push(`${id}: both revisions require successful positive latency samples`);
            continue;
        }
        rows.push({
            id,
            targetGraphId: candidate.targetGraphId,
            baseLatencyNanos,
            candidateLatencyNanos,
            speedup: baseLatencyNanos / candidateLatencyNanos
        });
    }
    const groupByTarget = (observations) => observations.reduce((grouped, row) => {
        const rowsForTarget = grouped.get(row.targetGraphId) ?? [];
        rowsForTarget.push(row);
        grouped.set(row.targetGraphId, rowsForTarget);
        return grouped;
    }, new Map());
    const baseTargetCounts = groupByTarget(baseGraphIdRows);
    const candidateTargetCounts = groupByTarget(candidateGraphIdRows);
    const expectedShapes = new Set([
        "graph-id-property-wrapped-contains",
        "graph-id-function-wrapped-contains",
        "graph-id-parameter-wrapped-contains"
    ]);
    const targetIds = [...new Set([...baseTargetCounts.keys(), ...candidateTargetCounts.keys()])];
    if (targetIds.length !== 64 || targetIds.includes("")) {
        errors.push(`expected 64 non-empty target graph ids, found ${targetIds.filter(Boolean).length}`);
    }
    for (const targetId of targetIds) {
        const baseTargetRows = baseTargetCounts.get(targetId) ?? [];
        const candidateTargetRows = candidateTargetCounts.get(targetId) ?? [];
        if (baseTargetRows.length !== 9 || candidateTargetRows.length !== 9) {
            errors.push(`${targetId}: expected three selectivities for all three graph-id spellings`);
            continue;
        }
        for (const shape of expectedShapes) {
            const baseSelectivities = new Set(baseTargetRows.filter((row) => row.shape === shape)
                .map((row) => row.selectivity));
            const candidateSelectivities = new Set(candidateTargetRows.filter((row) => row.shape === shape)
                .map((row) => row.selectivity));
            if (baseSelectivities.size !== 3 || candidateSelectivities.size !== 3 ||
                ["zero", "targeted", "dense"].some((selectivity) =>
                    !baseSelectivities.has(selectivity) || !candidateSelectivities.has(selectivity))) {
                errors.push(`${targetId}: ${shape} zero/targeted/dense coverage is incomplete`);
            }
        }
    }
    const graphParameterShape = "api-graph-parameter-wrapped-contains";
    const graphParameterByTarget = (observations, revision) => {
        const result = new Map();
        for (const row of observations) {
            if (row.shape !== graphParameterShape) {
                errors.push(`${revision}/${row.id}: unexpected graph-parameter shape ${row.shape}`);
            }
            const key = `${row.targetGraphId}\u0000${row.selectivity}`;
            if (result.has(key)) {
                errors.push(`${revision}: duplicate graph-parameter target/selectivity ` +
                    `${row.targetGraphId}/${row.selectivity}`);
            }
            result.set(key, row);
        }
        const targets = new Set(observations.map((row) => row.targetGraphId).filter(Boolean));
        if (result.size !== 192 || targets.size !== 64) {
            errors.push(`${revision}: expected 64 graph-parameter targets x 3 selectivities, found ` +
                `${targets.size} targets and ${result.size} rows`);
        }
        return result;
    };
    const baseGraphParameterByTarget = graphParameterByTarget(baseGraphParameterRows, "base");
    const candidateGraphParameterByTarget = graphParameterByTarget(candidateGraphParameterRows, "candidate");
    const graphParameterLatencyRows = [];
    const candidateRoutingOverheads = [];
    for (const targetId of targetIds) {
        for (const selectivity of ["zero", "targeted", "dense"]) {
            const referenceKey = `${targetId}\u0000${selectivity}`;
            const baseReference = baseGraphParameterByTarget.get(referenceKey);
            const candidateReference = candidateGraphParameterByTarget.get(referenceKey);
            if (baseReference === undefined || candidateReference === undefined) {
                errors.push(`${targetId}/${selectivity}: graph-parameter reference is missing from base or candidate`);
                continue;
            }
            const baseReferenceLatency = finiteNumber(baseReference.latencyNanos);
            const candidateReferenceLatency = finiteNumber(candidateReference.latencyNanos);
            const baseReferenceRows = finiteNumber(baseReference.rowCount);
            const candidateReferenceRows = finiteNumber(candidateReference.rowCount);
            if (baseReference.outcome !== "success" || candidateReference.outcome !== "success" ||
                baseReferenceLatency === null || candidateReferenceLatency === null ||
                baseReferenceLatency <= 0 || candidateReferenceLatency <= 0 ||
                baseReferenceRows === null || candidateReferenceRows === null
            ) {
                errors.push(`${targetId}/${selectivity}: graph-parameter references require successful samples`);
                continue;
            }
            const validDistribution = selectivity === "zero" ? candidateReferenceRows === 0 :
                selectivity === "targeted" ? candidateReferenceRows >= 1 && candidateReferenceRows < 200 :
                    candidateReferenceRows === 200;
            if (!validDistribution) {
                errors.push(`${targetId}/${selectivity}: graph-parameter rowCount=${candidateReferenceRows} ` +
                    "does not satisfy zero=0, targeted=1..199, dense=200");
            }
            for (const field of ["selectivity", "rowCount", "responseBytes", "digest"]) {
                if (baseReference[field] !== candidateReference[field]) {
                    errors.push(`${targetId}/${selectivity}: graph-parameter ${field} differs between base and candidate`);
                }
            }
            graphParameterLatencyRows.push({
                targetGraphId: targetId,
                selectivity,
                baseLatencyNanos: baseReferenceLatency,
                candidateLatencyNanos: candidateReferenceLatency
            });
            const candidateRowsForReference = (candidateTargetCounts.get(targetId) ?? [])
                .filter((row) => row.selectivity === selectivity);
            if (candidateRowsForReference.length !== 3) {
                errors.push(`${targetId}/${selectivity}: expected three candidate graphId reference matches`);
            }
            for (const candidateRow of candidateRowsForReference) {
                for (const field of ["selectivity", "rowCount", "responseBytes", "digest"]) {
                    if (candidateRow[field] !== candidateReference[field]) {
                        errors.push(`${candidateRow.id}: candidate result ${field} differs from ` +
                            "the graph-parameter reference");
                    }
                }
                const latencyNanos = finiteNumber(candidateRow.latencyNanos);
                if (latencyNanos !== null && latencyNanos > 0) {
                    candidateRoutingOverheads.push(latencyNanos / candidateReferenceLatency);
                }
            }
        }
    }
    const baseLatencies = rows.map((row) => row.baseLatencyNanos);
    const candidateLatencies = rows.map((row) => row.candidateLatencyNanos);
    const p50Speedup = rows.length === 0 ? 0 :
        pressurePercentile(baseLatencies, 0.50) / pressurePercentile(candidateLatencies, 0.50);
    const p95Speedup = rows.length === 0 ? 0 :
        pressurePercentile(baseLatencies, 0.95) / pressurePercentile(candidateLatencies, 0.95);
    const baseGraphParameterLatencies = graphParameterLatencyRows.map((row) => row.baseLatencyNanos);
    const candidateGraphParameterLatencies = graphParameterLatencyRows.map((row) => row.candidateLatencyNanos);
    const graphParameterP50Speedup = graphParameterLatencyRows.length === 0 ? 0 :
        pressurePercentile(baseGraphParameterLatencies, 0.50) /
            pressurePercentile(candidateGraphParameterLatencies, 0.50);
    const graphParameterP95Speedup = graphParameterLatencyRows.length === 0 ? 0 :
        pressurePercentile(baseGraphParameterLatencies, 0.95) /
            pressurePercentile(candidateGraphParameterLatencies, 0.95);
    const graphParameterP50Regression = graphParameterLatencyRows.length === 0 ? Number.POSITIVE_INFINITY :
        pressurePercentile(candidateGraphParameterLatencies, 0.50) /
            pressurePercentile(baseGraphParameterLatencies, 0.50) - 1;
    const graphParameterP95Regression = graphParameterLatencyRows.length === 0 ? Number.POSITIVE_INFINITY :
        pressurePercentile(candidateGraphParameterLatencies, 0.95) /
            pressurePercentile(baseGraphParameterLatencies, 0.95) - 1;
    const maximumGraphParameterRegression = 0.15;
    const routingOverheadP50 = candidateRoutingOverheads.length === 0 ? Number.POSITIVE_INFINITY :
        pressurePercentile(candidateRoutingOverheads, 0.50);
    const routingOverheadP95 = candidateRoutingOverheads.length === 0 ? Number.POSITIVE_INFINITY :
        pressurePercentile(candidateRoutingOverheads, 0.95);
    const gateP50Speedup = Math.min(p50Speedup, graphParameterP50Speedup);
    const gateP95Speedup = Math.min(p95Speedup, graphParameterP95Speedup);
    const passed = errors.length === 0 && p50Speedup >= minimumSpeedup && p95Speedup >= minimumSpeedup &&
        graphParameterP50Speedup >= minimumSpeedup && graphParameterP95Speedup >= minimumSpeedup;
    return {
        passed,
        errors,
        minimumSpeedup,
        p50Speedup,
        p95Speedup,
        graphParameterP50Speedup,
        graphParameterP95Speedup,
        gateP50Speedup,
        gateP95Speedup,
        maximumGraphParameterRegression,
        graphParameterP50Regression,
        graphParameterP95Regression,
        routingOverheadP50,
        routingOverheadP95,
        resources: {
            base: baseResources,
            candidate: candidateResources
        },
        rows,
        graphParameterLatencyRows
    };
}

export function renderGraphIdPressureReport(comparison) {
    const baseResources = comparison.resources.base;
    const candidateResources = comparison.resources.candidate;
    const gibibytes = (bytes) => `${(bytes / (1024 ** 3)).toFixed(2)} GiB`;
    const lines = [
        "### 64-real-graph graphId pressure gate",
        "",
        `Required speedup: ${comparison.minimumSpeedup.toFixed(1)}x for both P50 and P95 on ` +
            "query-level graphId and API graph-parameter routing.",
        "",
        `- Query-level graphId P50 speedup: **${comparison.p50Speedup.toFixed(2)}x**`,
        `- Query-level graphId P95 speedup: **${comparison.p95Speedup.toFixed(2)}x**`,
        `- API graph-parameter P50 speedup: **${comparison.graphParameterP50Speedup.toFixed(2)}x**`,
        `- API graph-parameter P95 speedup: **${comparison.graphParameterP95Speedup.toFixed(2)}x**`,
        `- Candidate graphId/API-reference latency ratio: ` +
            `**${comparison.routingOverheadP50.toFixed(2)}x P50 / ${comparison.routingOverheadP95.toFixed(2)}x P95**`,
        `- Candidate intra-graph scans: **${candidateResources.callSiteParallelScanCount.toFixed(0)}**; ` +
            `peak simultaneously active workers: **${candidateResources.callSiteScanPeakActiveWorkers.toFixed(0)}**`,
        `- Effective CPU cores: **${(baseResources.cpuCoreUtilizationPermille / 1000).toFixed(2)} → ` +
            `${(candidateResources.cpuCoreUtilizationPermille / 1000).toFixed(2)}**`,
        `- Peak used heap: **${gibibytes(baseResources.peakUsedHeapBytes)} → ` +
            `${gibibytes(candidateResources.peakUsedHeapBytes)}**`,
        `- Peak RSS: **${gibibytes(baseResources.peakResidentSetBytes)} → ` +
            `${gibibytes(candidateResources.peakResidentSetBytes)}**`,
        `- Query GC: **${baseResources.gcCount.toFixed(0)} / ${baseResources.gcMillis.toFixed(0)}ms → ` +
            `${candidateResources.gcCount.toFixed(0)} / ${candidateResources.gcMillis.toFixed(0)}ms**`,
        `- Retained CallSite index: **${baseResources.callSiteIndexAdmittedGraphs.toFixed(0)} graphs / ` +
            `${gibibytes(baseResources.callSiteIndexRetainedBytes)} → ` +
            `${candidateResources.callSiteIndexAdmittedGraphs.toFixed(0)} graphs / ` +
            `${gibibytes(candidateResources.callSiteIndexRetainedBytes)}**`,
        "",
        "| Query | Target graph | Base | PR | Speedup |",
        "|---|---|---:|---:|---:|"
    ];
    for (const row of comparison.rows) {
        lines.push(`| \`${row.id}\` | \`${row.targetGraphId}\` | ${(row.baseLatencyNanos / 1e9).toFixed(3)}s | ` +
            `${(row.candidateLatencyNanos / 1e9).toFixed(3)}s | ${row.speedup.toFixed(2)}x |`);
    }
    if (comparison.errors.length > 0) {
        lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    }
    return `${lines.join("\n")}\n`;
}

export function compareLatencyAnchor(
    anchorResults,
    baseResults,
    candidateResults,
    regressionThreshold = 15,
    anchorThreshold = 50,
    expectedKeys = null
) {
    const baseComparison = compareJmh(baseResults, candidateResults, regressionThreshold);
    const anchorComparison = compareJmh(anchorResults, candidateResults, anchorThreshold);
    const errors = [
        ...baseComparison.errors,
        ...anchorComparison.errors.map((error) => `known-good anchor: ${error}`)
    ];
    for (const [revision, results] of [
        ["known-good anchor", anchorResults],
        ["PR base", baseResults],
        ["candidate", candidateResults]
    ]) {
        for (const result of results) {
            const score = finiteNumber(result.primaryMetric?.score);
            if (score === null || score <= 0) {
                errors.push(`${revision}/${benchmarkKey(result)}: latency score must be finite and positive`);
            }
        }
    }
    const baseRows = new Map(baseComparison.rows.map((row) => [row.key, row]));
    const anchorRows = new Map(anchorComparison.rows.map((row) => [row.key, row]));
    const keys = expectedKeys === null
        ? [...new Set([...baseRows.keys(), ...anchorRows.keys()])].sort()
        : [...expectedKeys].sort();
    if (expectedKeys !== null) {
        const expected = new Set(expectedKeys);
        for (const [revision, results] of [
            ["known-good anchor", anchorResults],
            ["PR base", baseResults],
            ["candidate", candidateResults]
        ]) {
            const actual = new Set(results.map(benchmarkKey));
            for (const key of expected) {
                if (!actual.has(key)) errors.push(`${revision}: missing expected latency benchmark ${key}`);
            }
            for (const key of actual) {
                if (!expected.has(key)) errors.push(`${revision}: unexpected latency benchmark ${key}`);
            }
        }
    }

    const rows = [];
    for (const key of keys) {
        const baseRow = baseRows.get(key);
        const anchorRow = anchorRows.get(key);
        if (baseRow === undefined || anchorRow === undefined) {
            errors.push(`${key}: missing from anchor, PR base, or candidate results`);
            continue;
        }
        rows.push({
            ...baseRow,
            anchorScore: anchorRow.baseScore,
            anchorDelta: anchorRow.delta,
            anchorThreshold: anchorRow.threshold,
            anchorConfidenceSeparated: anchorRow.confidenceSeparated,
            anchorBlocked: anchorRow.blocked,
            blocked: baseRow.blocked || anchorRow.blocked
        });
    }
    if (rows.length === 0) errors.push("No comparable latency benchmarks were found");
    return {
        comparison: "anchor",
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

export function compareLatencyResources(baseResults, candidateResults, threshold = 15) {
    const errors = [];
    const base = resultMap(baseResults, "PR base resources", errors);
    const candidate = resultMap(candidateResults, "candidate resources", errors);
    const rows = [];

    for (const key of LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS) {
        const baseline = base.get(key);
        const current = candidate.get(key);
        if (baseline === undefined || current === undefined) {
            errors.push(`${key}: missing from ${baseline === undefined ? "base" : "candidate"} resource results`);
            continue;
        }
        const profile = LATENCY_RESOURCE_PROFILES.get(key);
        for (const [revision, result] of [["PR base", baseline], ["candidate", current]]) {
            const heapArguments = parseMaximumHeapArguments(result);
            const expectedArgument = `-Xmx${profile.maxHeapBytes / GIB}g`;
            if (heapArguments.length !== 1 || heapArguments[0].toLowerCase() !== expectedArgument.toLowerCase()) {
                errors.push(`${revision}/${key}: expected exactly ${expectedArgument}, found ${heapArguments.join(", ") || "none"}`);
            }
            for (const name of [
                "maxHeapBytes", "loadedHeapBytes", "peakUsedHeapBytes", "retainedHeapBytes",
                "retainedHeapDeltaBytes", "queryGcCount", "queryGcTimeMs",
                "gc.alloc.rate.norm", "gc.count", "gc.time"
            ]) {
                const metric = secondaryMetric(result, name);
                if (metric === null || finiteNumber(metric.score) === null || typeof metric.scoreUnit !== "string" ||
                    metric.scoreUnit.length === 0
                ) errors.push(`${revision}/${key}: missing or invalid secondary metric ${name}`);
                if (LATENCY_RESOURCE_EVENT_METRICS.has(name) && rawMetricValues(metric) === null) {
                    errors.push(`${revision}/${key}: missing or invalid per-invocation raw metric ${name}`);
                }
            }
            const maxHeapValues = rawMetricValues(secondaryMetric(result, "maxHeapBytes"));
            const loadedValues = rawMetricValues(secondaryMetric(result, "loadedHeapBytes"));
            const peakValues = rawMetricValues(secondaryMetric(result, "peakUsedHeapBytes"));
            const retainedValues = rawMetricValues(secondaryMetric(result, "retainedHeapBytes"));
            const tolerance = Math.max(16 * MIB, profile.maxHeapBytes * 0.01);
            if (maxHeapValues !== null && maxHeapValues.some((value) =>
                value > profile.maxHeapBytes || value < profile.maxHeapBytes - tolerance
            )) {
                errors.push(`${revision}/${key}: effective max heap does not match ${profile.maxHeapBytes} in every invocation`);
            }
            if (maxHeapValues !== null && loadedValues !== null && peakValues !== null && retainedValues !== null) {
                const lengths = [maxHeapValues.length, loadedValues.length, peakValues.length, retainedValues.length];
                if (!lengths.every((length) => length === lengths[0])) {
                    errors.push(`${revision}/${key}: resource raw metric invocation counts differ`);
                } else if (loadedValues.some((loaded, index) => {
                    const peak = peakValues[index];
                    const retained = retainedValues[index];
                    return loaded < 0 || retained < 0 || peak < loaded || retained > peak || peak > maxHeapValues[index];
                })) {
                    errors.push(`${revision}/${key}: invalid loaded/retained/peak heap relationship`);
                }
            }
        }

        for (const metric of LATENCY_RESOURCE_METRICS) {
            const baseMetric = secondaryMetric(baseline, metric.key);
            const candidateMetric = secondaryMetric(current, metric.key);
            const baseValue = resourceMetricValue(baseline, metric.key);
            const candidateValue = resourceMetricValue(current, metric.key);
            if (baseValue === null || candidateValue === null || baseValue < 0 || candidateValue < 0) continue;
            if (baseMetric.scoreUnit !== candidateMetric.scoreUnit) {
                errors.push(`${key}/${metric.label}: base and candidate units differ`);
                continue;
            }
            const increase = candidateValue - baseValue;
            const delta = baseValue === 0
                ? (increase > 0 ? Number.POSITIVE_INFINITY : 0)
                : (increase / baseValue) * 100;
            const aboveThreshold = increase > metric.minimum && (baseValue === 0 || delta > threshold);
            rows.push({
                key,
                metric: metric.label,
                baseValue,
                candidateValue,
                unit: baseMetric.scoreUnit,
                delta,
                threshold,
                minimum: metric.minimum,
                aboveThreshold,
                blocked: aboveThreshold
            });
        }
    }
    for (const [revision, mapped] of [["PR base", base], ["candidate", candidate]]) {
        for (const key of mapped.keys()) {
            if (!LATENCY_RESOURCE_PROFILES.has(key)) errors.push(`${revision}: unexpected resource benchmark ${key}`);
        }
    }
    return { passed: errors.length === 0 && rows.every((row) => !row.blocked), errors, rows };
}

export function confirmLatencyResources(initial, confirmation) {
    const errors = [...initial.errors, ...confirmation.errors.map((error) => `confirmation: ${error}`)];
    const retries = new Map(confirmation.rows.map((row) => [`${row.key}/${row.metric}`, row]));
    const rows = initial.rows.map((row) => {
        if (!row.blocked) return row;
        const retry = retries.get(`${row.key}/${row.metric}`);
        if (retry === undefined) {
            errors.push(`${row.key}/${row.metric}: missing from resource confirmation`);
            return row;
        }
        return { ...row, confirmation: retry, blocked: retry.blocked };
    });
    return { passed: errors.length === 0 && rows.every((row) => !row.blocked), errors, rows };
}

export function renderLatencyResourceReport(comparison) {
    const lines = [
        "### Wrapped-query resource guardrails", "",
        "Resource probes run separately from latency timing. JVM caps and metric presence fail closed;",
        "GC, retained-heap, and peak-heap regressions must repeat in reverse order.", "",
        "| Benchmark | Metric | Base | PR | Change | Gate |",
        "|---|---|---:|---:|---:|:---:|"
    ];
    for (const row of comparison.rows) {
        lines.push(`| \`${shortBenchmarkName(row.key)}\` | ${row.metric} | ${formatScore(row.baseValue)} ${row.unit} | ` +
            `${formatScore(row.candidateValue)} ${row.unit} | ${formatDelta(row.delta)} | **${statusLabel(row)}** |`);
    }
    if (comparison.errors.length > 0) lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    return `${lines.join("\n")}\n`;
}

export function confirmLatencyBaseline(initial, confirmation) {
    const errors = [
        ...initial.errors,
        ...confirmation.errors.map((error) => `confirmation: ${error}`)
    ];
    const confirmationRows = new Map(confirmation.rows.map((row) => [row.key, row]));
    const rows = initial.rows.map((row) => {
        if (!row.blocked) return row;
        const retry = confirmationRows.get(row.key);
        if (retry === undefined) {
            errors.push(`${row.key}: missing from confirmation results`);
            return row;
        }
        return {
            ...row,
            confirmation: {
                fixedScore: retry.fixedScore,
                baseScore: retry.baseScore,
                candidateScore: retry.candidateScore,
                speedup: retry.speedup,
                delta: retry.delta,
                blocked: retry.blocked
            },
            blocked: retry.blocked
        };
    });
    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

export function confirmLatencyAnchor(initial, confirmation) {
    const errors = [
        ...initial.errors,
        ...confirmation.errors.map((error) => `confirmation: ${error}`)
    ];
    const confirmationRows = new Map(confirmation.rows.map((row) => [row.key, row]));
    const rows = initial.rows.map((row) => {
        if (!row.blocked) return row;
        const retry = confirmationRows.get(row.key);
        if (retry === undefined) {
            errors.push(`${row.key}: missing from anchor confirmation results`);
            return row;
        }
        return {
            ...row,
            confirmation: {
                anchorScore: retry.anchorScore,
                baseScore: retry.baseScore,
                candidateScore: retry.candidateScore,
                anchorDelta: retry.anchorDelta,
                delta: retry.delta,
                blocked: retry.blocked
            },
            blocked: retry.blocked
        };
    });
    return {
        comparison: "anchor",
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

export function renderLatencyAnchorReport(comparison) {
    const lines = [
        "### Wrapped case-insensitive query latency",
        "",
        "The candidate may not regress more than 50% against the pinned known-good anchor or 15%",
        "against the current PR base. Suspected failures must repeat in reverse execution order.",
        "All measured rows use real persisted graph fixtures; synthetic graphs are excluded.",
        "",
        "| Benchmark | Known-good | PR base | PR | Regression vs anchor | Regression vs base | Confirmation | Gate |",
        "|---|---:|---:|---:|---:|---:|---:|:---:|"
    ];
    for (const row of comparison.rows) {
        const confirmation = row.confirmation === undefined
            ? "-"
            : `${formatScore(row.confirmation.anchorScore)} / ${formatScore(row.confirmation.baseScore)} / ` +
                `${formatScore(row.confirmation.candidateScore)} ${row.unit} ` +
                `(${formatDelta(row.confirmation.anchorDelta)} anchor; ${formatDelta(row.confirmation.delta)} base)`;
        lines.push(
            `| \`${shortBenchmarkName(row.key)}\` | ${formatScore(row.anchorScore)} ${row.unit} | ` +
            `${formatScore(row.baseScore)} ${row.unit} | ${formatScore(row.candidateScore)} ${row.unit} | ` +
            `${formatDelta(row.anchorDelta)} | ${formatDelta(row.delta)} | ${confirmation} | ` +
            `**${row.blocked ? "FAIL" : "PASS"}** |`
        );
    }
    if (comparison.errors.length > 0) {
        lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    }
    return `${lines.join("\n")}\n`;
}

export function renderLatencyBaselineReport(comparison) {
    const lines = [
        "### Wrapped case-insensitive query latency",
        "",
        "Multi-graph rows must remain at least 10x faster than the fixed pre-PR-95 baseline;",
        "the single-graph row must remain at least 50% faster. No row may regress",
        "more than 15% against the PR base with separated 99.9% confidence intervals.",
        "A suspected failure blocks only when the same benchmark fails the reverse-order confirmation run.",
        "",
        "| Benchmark | Pre-PR-95 | PR base | PR | Speedup vs fixed | Regression vs base | Confirmation | Gate |",
        "|---|---:|---:|---:|---:|---:|---:|:---:|"
    ];
    for (const row of comparison.rows) {
        const confirmation = row.confirmation === undefined
            ? "-"
            : `${formatScore(row.confirmation.fixedScore)} / ${formatScore(row.confirmation.baseScore)} / ` +
                `${formatScore(row.confirmation.candidateScore)} ${row.unit} ` +
                `(${formatDelta(row.confirmation.speedup)} fixed; ${formatDelta(row.confirmation.delta)} base)`;
        lines.push(
            `| \`${shortBenchmarkName(row.key)}\` | ${formatScore(row.fixedScore)} ${row.unit} | ` +
            `${formatScore(row.baseScore)} ${row.unit} | ${formatScore(row.candidateScore)} ${row.unit} | ` +
            `${formatDelta(row.speedup)} | ${formatDelta(row.delta)} | ` +
            `${confirmation} | ` +
            `**${row.blocked ? "FAIL" : "PASS"}** |`
        );
    }
    if (comparison.errors.length > 0) {
        lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    }
    return `${lines.join("\n")}\n`;
}

export function combineLatencyShards(directory) {
    const errors = [];
    const rows = [];
    const seenKeys = new Set();
    const comparisons = new Set();
    for (const shard of LATENCY_EXPECTED_SHARDS) {
        const statusFile = path.join(directory, `latency-status-${shard}.json`);
        if (!fs.existsSync(statusFile)) {
            errors.push(`${shard}: latency shard status is missing`);
            continue;
        }
        const status = readJson(statusFile);
        comparisons.add(status.comparison ?? "baseline");
        for (const error of status.errors ?? []) errors.push(`${shard}: ${error}`);
        for (const row of status.rows ?? []) {
            if (seenKeys.has(row.key)) {
                errors.push(`${shard}: duplicate latency benchmark ${row.key}`);
            } else {
                seenKeys.add(row.key);
                rows.push(row);
            }
        }
        if (status.passed !== true) errors.push(`${shard}: latency shard failed`);
    }

    const expected = new Set(LATENCY_EXPECTED_BENCHMARK_KEYS);
    for (const key of expected) {
        if (!seenKeys.has(key)) errors.push(`combined latency results: missing expected benchmark ${key}`);
    }
    for (const key of seenKeys) {
        if (!expected.has(key)) errors.push(`combined latency results: unexpected benchmark ${key}`);
    }
    rows.sort((left, right) => left.key.localeCompare(right.key));
    if (comparisons.size !== 1) errors.push("latency shards use mixed comparison policies");
    return {
        comparison: comparisons.size === 1 ? [...comparisons][0] : "unknown",
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

export function parseLargeCorpusLog(contents) {
    const results = new Map();
    const errors = [];
    for (const line of contents.split(/\r?\n/)) {
        const marker = line.indexOf("LARGE_CORPUS_BASELINE");
        if (marker < 0) continue;
        const tokens = line.slice(marker).trim().split(/\s+/);
        if (tokens.length < 3) {
            errors.push("Malformed large-corpus marker");
            continue;
        }
        const corpus = tokens[1];
        if (results.has(corpus)) {
            errors.push(`duplicate large-corpus marker ${corpus}`);
            continue;
        }
        const measurement = { corpus };
        for (const token of tokens.slice(2)) {
            const separator = token.indexOf("=");
            if (separator < 1) {
                errors.push(`${corpus}: malformed measurement token ${token}`);
                continue;
            }
            const name = token.slice(0, separator);
            if (Object.hasOwn(measurement, name)) {
                errors.push(`${corpus}: duplicate measurement ${name}`);
                continue;
            }
            const value = finiteNumber(token.slice(separator + 1));
            if (value === null) {
                errors.push(`${corpus}/${name}: invalid measurement`);
                continue;
            }
            measurement[name] = value;
        }
        results.set(corpus, measurement);
    }
    return { results, errors };
}

export function compareLargeCorpus(baseLog, candidateLog) {
    const baseParsed = parseLargeCorpusLog(baseLog);
    const candidateParsed = parseLargeCorpusLog(candidateLog);
    const base = baseParsed.results;
    const candidate = candidateParsed.results;
    const errors = [
        ...baseParsed.errors.map((error) => `base: ${error}`),
        ...candidateParsed.errors.map((error) => `candidate: ${error}`)
    ];
    const rows = [];
    const expected = new Set(LARGE_CORPUS_EXPECTED_CORPORA);

    for (const [revision, results] of [["base", base], ["candidate", candidate]]) {
        for (const corpus of expected) {
            if (!results.has(corpus)) errors.push(`${revision}: missing expected large corpus ${corpus}`);
        }
        for (const corpus of results.keys()) {
            if (!expected.has(corpus)) errors.push(`${revision}: unexpected large corpus ${corpus}`);
        }
    }

    for (const corpus of LARGE_CORPUS_EXPECTED_CORPORA) {
        const baseline = base.get(corpus);
        const current = candidate.get(corpus);
        if (baseline === undefined || current === undefined) continue;

        for (const field of LARGE_CORPUS_SHAPE_FIELDS) {
            const baseValue = finiteNumber(baseline[field]);
            const candidateValue = finiteNumber(current[field]);
            if (!Number.isSafeInteger(baseValue) || !Number.isSafeInteger(candidateValue) ||
                baseValue <= 0 || candidateValue <= 0
            ) {
                errors.push(`${corpus}/${field}: invalid graph-shape measurement`);
            } else if (baseValue !== candidateValue) {
                errors.push(`${corpus}/${field}: graph shape changed from ${baseValue} to ${candidateValue}`);
            }
        }

        const basePersistedBytes = finiteNumber(baseline.persistedBytes);
        const candidatePersistedBytes = finiteNumber(current.persistedBytes);
        if (!Number.isSafeInteger(basePersistedBytes) || !Number.isSafeInteger(candidatePersistedBytes) ||
            basePersistedBytes <= 0 || candidatePersistedBytes <= 0
        ) {
            errors.push(`${corpus}/persistedBytes: invalid persisted-size measurement`);
        } else if (Math.abs(candidatePersistedBytes - basePersistedBytes) >
            LARGE_CORPUS_PERSISTED_BYTES_TOLERANCE
        ) {
            errors.push(
                `${corpus}/persistedBytes: persisted size changed from ${basePersistedBytes} to ` +
                `${candidatePersistedBytes}, exceeding the ${LARGE_CORPUS_PERSISTED_BYTES_TOLERANCE}-byte tolerance`
            );
        }

        for (const [revision, measurement] of [["base", baseline], ["candidate", current]]) {
            const sampleCount = finiteNumber(measurement.mappedLoadSamples);
            const minimumLoad = finiteNumber(measurement.mappedLoadMinMs);
            const medianLoad = finiteNumber(measurement.mappedLoadMs);
            const maximumLoad = finiteNumber(measurement.mappedLoadMaxMs);
            if (!Number.isSafeInteger(sampleCount) || sampleCount !== LARGE_CORPUS_MAPPED_LOAD_SAMPLES) {
                errors.push(
                    `${corpus}/${revision}: mappedLoadSamples must equal ${LARGE_CORPUS_MAPPED_LOAD_SAMPLES}`
                );
            }
            if (minimumLoad === null || medianLoad === null || maximumLoad === null ||
                minimumLoad <= 0 || medianLoad <= 0 || maximumLoad <= 0 ||
                minimumLoad > medianLoad || medianLoad > maximumLoad
            ) {
                errors.push(`${corpus}/${revision}: invalid mapped-load sample distribution`);
            }

            const phaseTotal = ["buildMs", "saveMs", "mappedLoadMs", "queryMs"]
                .map((key) => finiteNumber(measurement[key]))
                .reduce((sum, value) => value === null ? null : (sum === null ? null : sum + value), 0);
            const pipeline = finiteNumber(measurement.pipelineMs);
            if (phaseTotal !== null && pipeline !== null && phaseTotal !== pipeline) {
                errors.push(`${corpus}/${revision}: pipelineMs ${pipeline} does not equal phase total ${phaseTotal}`);
            }
        }

        for (const metric of LARGE_CORPUS_METRICS) {
            const baseValue = finiteNumber(baseline[metric.key]);
            const candidateValue = finiteNumber(current[metric.key]);
            if (baseValue === null || candidateValue === null || baseValue <= 0 || candidateValue <= 0) {
                errors.push(`${corpus}/${metric.label}: invalid measurement`);
                continue;
            }
            const absoluteIncrease = candidateValue - baseValue;
            const delta = regressionPercent(baseValue, candidateValue, true);
            const advisory = metric.advisory === true;
            const aboveThreshold = !advisory && delta > metric.threshold;
            const blocked = aboveThreshold && absoluteIncrease > metric.minimum;
            rows.push({
                corpus,
                metric: metric.label,
                baseValue,
                candidateValue,
                unit: metric.unit,
                delta,
                threshold: metric.threshold,
                minimum: metric.minimum,
                advisory,
                aboveThreshold,
                blocked
            });
        }
    }

    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

export function confirmLargeCorpus(initial, confirmation) {
    const errors = [
        ...initial.errors,
        ...confirmation.errors.map((error) => `confirmation: ${error}`)
    ];
    const confirmationRows = new Map(
        confirmation.rows.map((row) => [`${row.corpus}/${row.metric}`, row])
    );
    const rows = initial.rows.map((row) => {
        if (!row.blocked) return row;
        const key = `${row.corpus}/${row.metric}`;
        const retry = confirmationRows.get(key);
        if (retry === undefined) {
            errors.push(`${key}: missing from confirmation results`);
            return row;
        }
        return {
            ...row,
            confirmation: {
                baseValue: retry.baseValue,
                candidateValue: retry.candidateValue,
                delta: retry.delta,
                blocked: retry.blocked
            },
            blocked: retry.blocked
        };
    });
    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

function formatMeasurement(value, unit) {
    if (unit === "bytes") return `${(value / MIB).toFixed(0)} MiB`;
    return `${Math.round(value).toLocaleString("en-US")} ms`;
}

export function renderLargeCorpusReport(comparison) {
    const lines = [
        "### Real-corpus end to end",
        "",
        "Each corpus runs `JAR -> build -> save -> mapped load -> Cypher` in an isolated 4 GiB JVM.",
        "The exact corpus set and graph-shape counts must match; persisted size may vary by at most 4 KiB.",
        "Mapped load is the median of five loads of the same persisted graph; min/max remain in the audit marker.",
        "Small absolute changes below the noise floor do not block.",
        "Suspected timing regressions must repeat in a reverse-order confirmation run.",
        "Sampled peak heap is informational because its single-run value depends on GC timing; OOM still blocks.",
        "",
        "| Corpus | Metric | Base | PR | Regression | Confirmation (base -> PR) | Limit | Gate |",
        "|---|---|---:|---:|---:|---:|---:|:---:|"
    ];
    for (const row of comparison.rows) {
        const confirmation = row.confirmation === undefined
            ? "-"
            : `${formatMeasurement(row.confirmation.baseValue, row.unit)} -> ` +
                `${formatMeasurement(row.confirmation.candidateValue, row.unit)} ` +
                `(${formatDelta(row.confirmation.delta)})`;
        lines.push(
            `| ${row.corpus} | ${row.metric} | ${formatMeasurement(row.baseValue, row.unit)} | ` +
            `${formatMeasurement(row.candidateValue, row.unit)} | ${formatDelta(row.delta)} | ` +
            `${confirmation} | ${row.advisory ? "4 GiB cap" :
                `${row.threshold.toFixed(0)}% + ${formatMeasurement(row.minimum, row.unit)}`} | ` +
            `**${statusLabel(row)}** |`
        );
    }
    if (comparison.errors.length > 0) {
        lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    }
    return `${lines.join("\n")}\n`;
}

export function aggregateReports(directory, metadata) {
    const errors = [];
    const reports = new Map();
    const results = new Map();
    let passed = true;
    for (const component of BENCHMARK_COMPONENTS) {
        const reportFile = path.join(directory, component.report);
        const statusFile = path.join(directory, component.status);
        if (!fs.existsSync(reportFile) || !fs.existsSync(statusFile)) {
            errors.push(`${component.name}: result artifact is missing`);
            passed = false;
            results.set(component.name, "MISSING");
            continue;
        }
        reports.set(component.name, fs.readFileSync(reportFile, "utf8").trim());
        const status = readJson(statusFile);
        const componentPassed = status.passed === true;
        results.set(component.name, componentPassed ? "PASS" : "FAIL");
        if (!componentPassed) passed = false;
    }

    const componentByName = new Map(BENCHMARK_COMPONENTS.map((component) => [component.name, component]));
    const coverageRows = BENCHMARK_COVERAGE_DOMAINS.flatMap((domain) => domain.components.map((name) => {
        const component = componentByName.get(name);
        const coverage = component.coverage === "complete" ? "✅ Complete" : "⚠️ Partial";
        return `| ${domain.name} | \`${name}\` | **${results.get(name) ?? "MISSING"}** | ${coverage} | ${component.gap} |`;
    }));
    const productSections = BENCHMARK_COVERAGE_DOMAINS.flatMap((domain) => {
        const section = [
            `#### ${domain.name}`,
            "",
            domain.components.length === 0
                ? "No implemented benchmark gate currently covers this domain."
                : `Implemented gates: ${domain.components.map((name) => `\`${name}\``).join(", ")}.`,
            "",
            `Not covered by this suite (non-blocking for this run): ${domain.missing.map((name) => `\`${name}\``).join(", ")}.`,
            ""
        ];
        for (const name of domain.components) {
            const report = reports.get(name);
            if (report !== undefined) section.push(report.replace(/^### /, "##### "), "");
        }
        return section;
    });

    const passedComponents = [...results.values()].filter((componentResult) => componentResult === "PASS").length;
    const result = passed && errors.length === 0 ? "PASS" : "FAIL";
    const body = [
        COMMENT_MARKER,
        "## Benchmark Regression Gate",
        "",
        `**${result} — ${passedComponents}/${BENCHMARK_COMPONENTS.length} component reports passed**`,
        "",
        `Base: \`${metadata.baseSha.slice(0, 12)}\`  `,
        `PR: \`${metadata.candidateSha.slice(0, 12)}\`  `,
        `Runner: \`${metadata.runner}\``,
        "",
        "### Coverage summary",
        "",
        "Coverage labels follow the gate model: ✅ has no identified gate-specific gap; ⚠️ is implemented but incomplete.",
        "Run result and coverage are separate: PASS is evidence only for the stated contract, not for a listed gap or an uncovered family.",
        "",
        "| Coverage domain | Gate | Run result | Coverage | Known gap |",
        "|---|---|:---:|:---:|---|",
        ...coverageRows,
        "",
        "### Product performance",
        "",
        ...productSections,
        "### Gate system",
        "",
        "| Area | Coverage | Known gap |",
        "|---|:---:|---|",
        "| Evidence reliability | ⚠️ Partial | Fresh attempt-aware aggregation consumes component verdicts rather than freshly comparing raw measurements; historical variance, cold/warm separation, and required evidence publication remain uncovered. |",
        "| Control-plane integrity | ⚠️ Partial | Base-owned controls and candidate-only tests are implemented, but candidate-process isolation and external required authority remain uncovered. |",
        "| Coverage policy | ⚠️ Partial | The suite is static; changed-path ownership, missing-family policy, and benchmark-budget lifecycle remain uncovered. |",
        "",
        ...(errors.length > 0 ? ["### Infrastructure errors", "", ...errors.map((error) => `- ${error}`), ""] : []),
        `[View benchmark logs and artifacts](${metadata.runUrl})`
    ].join("\n");
    return {
        passed: result === "PASS",
        errors,
        baseSha: metadata.baseSha,
        candidateSha: metadata.candidateSha,
        runner: metadata.runner,
        runUrl: metadata.runUrl,
        body: `${body}\n`
    };
}

export function stageLatestArtifacts(directory, output) {
    const artifacts = fs.readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => {
            const match = entry.name.match(/^(.*)-(\d+)$/);
            return match === null ? null : {
                name: entry.name,
                producer: match[1],
                attempt: Number(match[2])
            };
        })
        .filter((entry) => entry !== null);
    if (artifacts.length === 0) throw new Error(`No benchmark artifacts found in ${directory}`);
    const latestByProducer = new Map();
    for (const artifact of artifacts) {
        const latest = latestByProducer.get(artifact.producer);
        if (latest === undefined || artifact.attempt > latest.attempt) {
            latestByProducer.set(artifact.producer, artifact);
        }
    }
    const latestArtifacts = [...latestByProducer.values()]
        .sort((left, right) => left.name.localeCompare(right.name));
    fs.rmSync(output, { recursive: true, force: true });
    fs.mkdirSync(output, { recursive: true });
    for (const artifact of latestArtifacts) {
        fs.cpSync(path.join(directory, artifact.name), output, { recursive: true, force: true });
    }
    return latestArtifacts.map((artifact) => artifact.name);
}

function compareJmhCommand(args) {
    let comparison = compareJmh(
        selectJmhMetric(readJson(requireArg(args, "base")), args.metric),
        selectJmhMetric(readJson(requireArg(args, "candidate")), args.metric),
        Number(args.threshold ?? 15),
        args["threshold-only"] === true,
        args.metric !== undefined
    );
    if (args.advisory === true) comparison = makeJmhAdvisory(comparison);
    writeFile(requireArg(args, "report"), renderJmhReport(comparison, args.title));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function compareLargeCorpusCommand(args) {
    const comparison = compareLargeCorpus(
        fs.readFileSync(requireArg(args, "base"), "utf8"),
        fs.readFileSync(requireArg(args, "candidate"), "utf8")
    );
    writeFile(requireArg(args, "report"), renderLargeCorpusReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function compareLatencyBaselineCommand(args) {
    const comparison = compareLatencyBaseline(
        readJson(requireArg(args, "fixed")),
        readJson(requireArg(args, "base")),
        readJson(requireArg(args, "candidate")),
        Number(args.threshold ?? 15),
        Number(args["minimum-speedup"] ?? 50),
        args["allow-subset"] === true ? null : LATENCY_EXPECTED_BENCHMARK_KEYS
    );
    writeFile(requireArg(args, "report"), renderLatencyBaselineReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function compareGraphIdPressureCommand(args) {
    const comparison = compareGraphIdPressure(
        readJson(requireArg(args, "base")),
        readJson(requireArg(args, "candidate")),
        fs.readFileSync(requireArg(args, "base-observations"), "utf8"),
        fs.readFileSync(requireArg(args, "candidate-observations"), "utf8"),
        Number(args["minimum-speedup"] ?? 10)
    );
    writeFile(requireArg(args, "report"), renderGraphIdPressureReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function compareLatencyAnchorCommand(args) {
    const comparison = compareLatencyAnchor(
        readJson(requireArg(args, "anchor")),
        readJson(requireArg(args, "base")),
        readJson(requireArg(args, "candidate")),
        Number(args.threshold ?? 15),
        Number(args["anchor-threshold"] ?? 50),
        args["allow-subset"] === true ? null : LATENCY_EXPECTED_BENCHMARK_KEYS
    );
    writeFile(requireArg(args, "report"), renderLatencyAnchorReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function compareLatencyResourcesCommand(args) {
    const comparison = compareLatencyResources(
        readJson(requireArg(args, "base")), readJson(requireArg(args, "candidate")), Number(args.threshold ?? 15)
    );
    writeFile(requireArg(args, "report"), renderLatencyResourceReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function confirmLatencyResourcesCommand(args) {
    const initial = readJson(requireArg(args, "initial"));
    const retry = compareLatencyResources(
        readJson(requireArg(args, "base")), readJson(requireArg(args, "candidate")), Number(args.threshold ?? 15)
    );
    const comparison = confirmLatencyResources(initial, retry);
    writeFile(requireArg(args, "report"), renderLatencyResourceReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function confirmLatencyBaselineCommand(args) {
    const initial = readJson(requireArg(args, "initial"));
    const confirmation = compareLatencyBaseline(
        readJson(requireArg(args, "fixed")),
        readJson(requireArg(args, "base")),
        readJson(requireArg(args, "candidate")),
        Number(args.threshold ?? 15),
        Number(args["minimum-speedup"] ?? 50),
        args["allow-subset"] === true ? null : LATENCY_EXPECTED_BENCHMARK_KEYS
    );
    const comparison = confirmLatencyBaseline(initial, confirmation);
    writeFile(requireArg(args, "report"), renderLatencyBaselineReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function confirmLatencyAnchorCommand(args) {
    const initial = readJson(requireArg(args, "initial"));
    const confirmation = compareLatencyAnchor(
        readJson(requireArg(args, "anchor")),
        readJson(requireArg(args, "base")),
        readJson(requireArg(args, "candidate")),
        Number(args.threshold ?? 15),
        Number(args["anchor-threshold"] ?? 50),
        args["allow-subset"] === true ? null : LATENCY_EXPECTED_BENCHMARK_KEYS
    );
    const comparison = confirmLatencyAnchor(initial, confirmation);
    writeFile(requireArg(args, "report"), renderLatencyAnchorReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function combineLatencyShardsCommand(args) {
    const comparison = combineLatencyShards(requireArg(args, "directory"));
    const report = comparison.comparison === "anchor"
        ? renderLatencyAnchorReport(comparison)
        : renderLatencyBaselineReport(comparison);
    writeFile(requireArg(args, "report"), report);
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function confirmJmhCommand(args) {
    const initial = readJson(requireArg(args, "initial"));
    const confirmation = compareJmh(
        selectJmhMetric(readJson(requireArg(args, "base")), args.metric),
        selectJmhMetric(readJson(requireArg(args, "candidate")), args.metric),
        Number(args.threshold ?? 15),
        args["threshold-only"] === true,
        args.metric !== undefined
    );
    const comparison = confirmJmh(initial, confirmation);
    writeFile(requireArg(args, "report"), renderJmhReport(comparison, args.title));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function stageLatestArtifactsCommand(args) {
    stageLatestArtifacts(requireArg(args, "directory"), requireArg(args, "output"));
}

function confirmLargeCorpusCommand(args) {
    const initial = readJson(requireArg(args, "initial"));
    const confirmation = compareLargeCorpus(
        fs.readFileSync(requireArg(args, "base"), "utf8"),
        fs.readFileSync(requireArg(args, "candidate"), "utf8")
    );
    const comparison = confirmLargeCorpus(initial, confirmation);
    writeFile(requireArg(args, "report"), renderLargeCorpusReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function aggregateCommand(args) {
    const aggregated = aggregateReports(requireArg(args, "directory"), {
        baseSha: requireArg(args, "base-sha"),
        candidateSha: requireArg(args, "candidate-sha"),
        runner: requireArg(args, "runner"),
        runUrl: requireArg(args, "run-url")
    });
    writeFile(requireArg(args, "report"), aggregated.body);
    writeJson(requireArg(args, "status"), aggregated);
}

function main(argv) {
    const args = parseArgs(argv);
    const command = args._[0];
    if (command === "compare-jmh") compareJmhCommand(args);
    else if (command === "compare-latency-baseline") compareLatencyBaselineCommand(args);
    else if (command === "compare-graph-id-pressure") compareGraphIdPressureCommand(args);
    else if (command === "confirm-latency-baseline") confirmLatencyBaselineCommand(args);
    else if (command === "compare-latency-anchor") compareLatencyAnchorCommand(args);
    else if (command === "confirm-latency-anchor") confirmLatencyAnchorCommand(args);
    else if (command === "combine-latency-shards") combineLatencyShardsCommand(args);
    else if (command === "compare-latency-resources") compareLatencyResourcesCommand(args);
    else if (command === "confirm-latency-resources") confirmLatencyResourcesCommand(args);
    else if (command === "confirm-jmh") confirmJmhCommand(args);
    else if (command === "compare-large-corpus") compareLargeCorpusCommand(args);
    else if (command === "confirm-large-corpus") confirmLargeCorpusCommand(args);
    else if (command === "stage-artifacts") stageLatestArtifactsCommand(args);
    else if (command === "aggregate") aggregateCommand(args);
    else throw new Error(`Unknown command: ${command ?? "<missing>"}`);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        main(process.argv.slice(2));
    } catch (error) {
        console.error(error instanceof Error ? error.stack : String(error));
        process.exitCode = 1;
    }
}
