#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

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
            "graph-routing-pressure",
            "global-wide-pressure"
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
    },
    {
        name: "global-wide-pressure",
        report: "global-wide-report.md",
        status: "global-wide-status.json",
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
        "id", "family", "shape", "selectivity", "targetGraphId", "workloadIdentity", "outcome", "rowCount",
        "responseBytes", "digest", "latencyNanos", "executionPath", "inputSourceCount", "accessedGraphCount",
        "targetGraphIds", "selectedGraphCount", "accessedGraphIds", "targetGraphAccessCount",
        "nonTargetGraphAccessCount", "parallelScanCount",
        "indexLookupCount", "peakActiveWorkers", "graphWorkUnits", "graphIdSourceSelections",
        "graphIdSourcePruningExecutions", "graphIdSourcesPruned", "filteredNodeLimitFastPathExecutions",
        "generalFallbackExecutions"
    ];
    for (const header of required) {
        if (!headers.includes(header)) errors.push(`${revision}: pressure observations missing ${header}`);
    }
    const rows = lines.slice(1).map((line) => {
        const values = line.split("\t");
        return Object.fromEntries(headers.map((header, index) => [header, values[index]]));
    }).filter((row) => ["graph-id", "graph-parameter", "graph-id-set", "graph-set-reference"].includes(row.family));
    const seen = new Set();
    for (const row of rows) {
        if (seen.has(row.id)) errors.push(`${revision}: duplicate graph-routing observation ${row.id}`);
        seen.add(row.id);
        if (row.targetGraphId === "" || !/^[0-9a-f]{64}$/.test(row.workloadIdentity ?? "")) {
            errors.push(`${revision}/${row.id}: target graph and workload identity are required`);
        }
    }
    return rows;
}

const GRAPH_ROUTING_SELECTIVITIES = ["zero", "targeted", "dense"];
const GRAPH_ROUTING_ORACLE_SHAPES = [
    {
        shape: "graph-id-property-wrapped-contains",
        operator: "graph-id-equals-and-wrapped-contains",
        boundary: "graph-routing"
    },
    {
        shape: "graph-id-function-wrapped-contains",
        operator: "graph-id-function-equals-and-wrapped-contains",
        boundary: "graph-routing"
    },
    {
        shape: "graph-id-parameter-wrapped-contains",
        operator: "graph-id-parameter-equals-and-wrapped-contains",
        boundary: "parameters"
    }
];
const GRAPH_PARAMETER_SHAPE = "request-selected-source-wrapped-contains";
const GRAPH_PARAMETER_OPERATOR = "request-graph-selection-and-wrapped-contains";
const GRAPH_PARAMETER_BOUNDARY = "request-selected-source";
// Width one is represented by the existing 64-target graphId equality matrix.
const GRAPH_SET_WIDTHS = new Map([[2, 32], [8, 8], [64, 1]]);
const GRAPH_SET_REFERENCE_SHAPE = "request-selected-set-wrapped-contains";
const GRAPH_SET_ORACLE_SHAPES = [
    {
        shape: "graph-id-in-literal-wrapped-contains",
        operator: "graph-id-in-literal-and-wrapped-contains",
        boundary: "graph-routing-set"
    },
    {
        shape: "graph-id-in-parameter-wrapped-contains",
        operator: "graph-id-in-parameter-and-wrapped-contains",
        boundary: "parameters"
    }
];

function parseCorrectnessRecords(contents, source, errors, requireRoutingIdentity = true) {
    const rows = [];
    const seen = new Set();
    for (const [index, rawLine] of contents.split(/\r?\n/).entries()) {
        const line = rawLine.trim();
        if (line.length === 0 || line.startsWith("#")) continue;
        const fields = line.split("|");
        if (fields.length !== 14) {
            errors.push(`${source}:${index + 1}: expected 14 correctness fields`);
            continue;
        }
        const [id, family, shape, selectivity, operator, boundary, projection,
            targetGraphId, workloadIdentity, limitText, outcome, rowCountText, responseBytesText, digest] = fields;
        const limit = finiteNumber(limitText);
        const rowCount = finiteNumber(rowCountText);
        const responseBytes = finiteNumber(responseBytesText);
        if (seen.has(id)) errors.push(`${source}: duplicate correctness id ${id}`);
        seen.add(id);
        if ([id, family, shape, selectivity, operator, boundary, projection].some(value => value === "")) {
            errors.push(`${source}:${index + 1}: blank correctness identity field`);
        }
        if (limit === null || rowCount === null || responseBytes === null ||
            !Number.isInteger(limit) || !Number.isInteger(rowCount) || !Number.isInteger(responseBytes) ||
            limit < 0 || rowCount < 0 || responseBytes < 0
        ) {
            errors.push(`${source}:${index + 1}: invalid correctness numeric field`);
        }
        if (outcome !== "success") errors.push(`${source}:${id}: outcome=${outcome}; expected success`);
        if (!/^[0-9a-f]{64}$/.test(digest)) errors.push(`${source}:${id}: invalid SHA-256 digest`);
        if (requireRoutingIdentity && (targetGraphId === "" || !/^[0-9a-f]{64}$/.test(workloadIdentity))) {
            errors.push(`${source}:${id}: target graph and workload identity are required`);
        }
        rows.push({
            id, family, shape, selectivity, operator, boundary, projection,
            targetGraphId, workloadIdentity, limit, outcome, rowCount, responseBytes, digest
        });
    }
    if (rows.length === 0) errors.push(`${source}: correctness manifest is empty`);
    return rows;
}

function encodeCorrectnessRecord(record) {
    return [
        record.id, record.family, record.shape, record.selectivity, record.operator,
        record.boundary, record.projection, record.targetGraphId, record.workloadIdentity,
        record.limit, record.outcome, record.rowCount,
        record.responseBytes, record.digest
    ].join("|");
}

export function canonicalCorrectnessManifest(contents, source) {
    const records = contents.replaceAll("\r\n", "\n").split("\n");
    if (records.at(-1) === "") records.pop();
    if (records.length === 0 || records.some((record) => record.length === 0)) {
        throw new Error(`${source} contains empty correctness records`);
    }
    const queryIds = records.map((record) => {
        const separator = record.indexOf("|");
        if (separator <= 0) throw new Error(`${source} contains a malformed correctness record`);
        return record.slice(0, separator);
    });
    if (new Set(queryIds).size !== queryIds.length) {
        throw new Error(`${source} contains duplicate query IDs`);
    }
    return records.toSorted().join("\n");
}

export function deriveGraphRoutingOracle(referenceContents) {
    const errors = [];
    const references = parseCorrectnessRecords(referenceContents, "base-single-source", errors);
    const bySlot = new Map();
    const setBySlot = new Map();
    for (const reference of references) {
        const match = reference.id.match(
            /^request-selected-source-wrapped-contains-target-(\d{2})-(zero|targeted|dense)$/
        );
        const setMatch = reference.id.match(
            /^request-selected-set-wrapped-contains-k(02|08|64)-group-(\d{2})-(zero|targeted|dense)$/
        );
        if (match === null && setMatch === null) {
            errors.push(`base-single-source: unexpected reference id ${reference.id}`);
            continue;
        }
        if (setMatch !== null) {
            const width = Number(setMatch[1]);
            const groupIndex = Number(setMatch[2]);
            const selectivity = setMatch[3];
            const expectedGroupCount = GRAPH_SET_WIDTHS.get(width);
            if (expectedGroupCount === undefined || groupIndex >= expectedGroupCount ||
                reference.family !== "graph-set-reference" || reference.shape !== GRAPH_SET_REFERENCE_SHAPE ||
                reference.selectivity !== selectivity ||
                reference.operator !== "request-graph-set-selection-and-wrapped-contains" ||
                reference.boundary !== GRAPH_PARAMETER_BOUNDARY || reference.projection !== "properties" ||
                reference.limit !== 200
            ) {
                errors.push(`base-single-source: invalid graph-set identity for ${reference.id}`);
            }
            const slot = `${width}\u0000${groupIndex}\u0000${selectivity}`;
            if (setBySlot.has(slot)) errors.push(`base-single-source: duplicate graph-set slot ${slot}`);
            setBySlot.set(slot, reference);
            continue;
        }
        const targetIndex = Number(match[1]);
        const selectivity = match[2];
        if (targetIndex < 0 || targetIndex >= 64 || reference.family !== "graph-parameter" ||
            reference.shape !== GRAPH_PARAMETER_SHAPE || reference.selectivity !== selectivity ||
            reference.operator !== GRAPH_PARAMETER_OPERATOR || reference.boundary !== GRAPH_PARAMETER_BOUNDARY ||
            reference.projection !== "properties" || reference.limit !== 200
        ) {
            errors.push(`base-single-source: invalid identity for ${reference.id}`);
        }
        const validRows = selectivity === "zero" ? reference.rowCount === 0 :
            selectivity === "targeted" ? reference.rowCount >= 1 && reference.rowCount < 200 :
                reference.rowCount === 200;
        if (!validRows) {
            errors.push(`base-single-source: ${reference.id} rowCount=${reference.rowCount} ` +
                "does not satisfy zero=0, targeted=1..199, dense=200");
        }
        const slot = `${targetIndex}\u0000${selectivity}`;
        if (bySlot.has(slot)) errors.push(`base-single-source: duplicate target/selectivity ${slot}`);
        bySlot.set(slot, reference);
    }
    if (references.length !== 315 || bySlot.size !== 192 || setBySlot.size !== 123) {
        errors.push(`base-single-source: expected 192 single-source plus 123 graph-set references, found ` +
            `${references.length} records, ${bySlot.size} single slots, and ${setBySlot.size} set slots`);
    }
    for (let targetIndex = 0; targetIndex < 64; targetIndex++) {
        for (const selectivity of GRAPH_ROUTING_SELECTIVITIES) {
            if (!bySlot.has(`${targetIndex}\u0000${selectivity}`)) {
                errors.push(`base-single-source: missing target-${String(targetIndex).padStart(2, "0")}-${selectivity}`);
            }
        }
    }
    for (const [width, groupCount] of GRAPH_SET_WIDTHS) {
        for (let groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            for (const selectivity of GRAPH_ROUTING_SELECTIVITIES) {
                if (!setBySlot.has(`${width}\u0000${groupIndex}\u0000${selectivity}`)) {
                    errors.push(`base-single-source: missing k${String(width).padStart(2, "0")}-` +
                        `group-${String(groupIndex).padStart(2, "0")}-${selectivity}`);
                }
            }
        }
    }
    if (errors.length > 0) return { passed: false, errors, records: [], oracle: "" };

    const records = [];
    for (const identity of GRAPH_ROUTING_ORACLE_SHAPES) {
        for (let targetIndex = 0; targetIndex < 64; targetIndex++) {
            for (const selectivity of GRAPH_ROUTING_SELECTIVITIES) {
                const reference = bySlot.get(`${targetIndex}\u0000${selectivity}`);
                records.push({
                    ...reference,
                    id: `${identity.shape}-target-${String(targetIndex).padStart(2, "0")}-${selectivity}`,
                    family: "graph-id",
                    shape: identity.shape,
                    operator: identity.operator,
                    boundary: identity.boundary
                });
            }
        }
    }
    for (let targetIndex = 0; targetIndex < 64; targetIndex++) {
        for (const selectivity of GRAPH_ROUTING_SELECTIVITIES) {
            records.push(bySlot.get(`${targetIndex}\u0000${selectivity}`));
        }
    }
    for (const identity of GRAPH_SET_ORACLE_SHAPES) {
        for (const [width, groupCount] of GRAPH_SET_WIDTHS) {
            for (let groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                for (const selectivity of GRAPH_ROUTING_SELECTIVITIES) {
                    const reference = setBySlot.get(`${width}\u0000${groupIndex}\u0000${selectivity}`);
                    records.push({
                        ...reference,
                        id: `${identity.shape}-k${String(width).padStart(2, "0")}-group-` +
                            `${String(groupIndex).padStart(2, "0")}-${selectivity}`,
                        family: "graph-id-set",
                        shape: identity.shape,
                        operator: identity.operator,
                        boundary: identity.boundary
                    });
                }
            }
        }
    }
    for (const [width, groupCount] of GRAPH_SET_WIDTHS) {
        for (let groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            for (const selectivity of GRAPH_ROUTING_SELECTIVITIES) {
                records.push(setBySlot.get(`${width}\u0000${groupIndex}\u0000${selectivity}`));
            }
        }
    }
    return {
        passed: true,
        errors: [],
        records,
        oracle: `${records.map(encodeCorrectnessRecord).join("\n")}\n`
    };
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
    baseCorrectnessContents,
    candidateCorrectnessContents,
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
            ["graphCount", 64], ["distinctGraphPathCount", 64], ["queryCount", 1137],
            ["successCount", 1137], ["timeoutCount", 0], ["failureCount", 0],
            ["graphIdTargetCount", 64], ["graphParameterTargetCount", 64],
            ["coverageShapeCount", 7], ["coverageFamilyCount", 4], ["coverageSelectivityCount", 3]
        ]) {
            const actual = pressureMetric(result, metric);
            if (actual !== expected) errors.push(`${revision}: ${metric}=${actual}; expected ${expected}`);
        }
        return result;
    };
    const baseResult = selectResult(baseResults, "base");
    const candidateResult = selectResult(candidateResults, "candidate");
    const baseIndexState = baseResult?.params?.indexState;
    const candidateIndexState = candidateResult?.params?.indexState;
    if (!new Set(["cold", "warm", "startup-prepared"]).has(baseIndexState)) {
        errors.push(`base: invalid graph-routing indexState=${baseIndexState}`);
    }
    if (candidateIndexState !== baseIndexState) {
        errors.push(`candidate: indexState=${candidateIndexState}; expected ${baseIndexState}`);
    }
    const resourceMetricNames = [
        "cpuCoreUtilizationPermille", "peakUsedHeapBytes", "peakResidentSetBytes",
        "gcCount", "gcMillis", "callSiteIndexAdmittedGraphs", "callSiteIndexRetainedBytes",
        "callSiteTrigramIndexedGraphs", "callSiteParallelScanCount", "callSiteParallelScanGraphCount",
        "callSiteStringIndexLookupCount", "callSiteStringIndexLookupGraphCount",
        "callSiteStringIndexLookupMinPerGraph", "callSiteStringIndexLookupMaxPerGraph",
        "callSiteScanPeakActiveWorkers"
    ];
    const resourceSnapshot = (result, revision) => Object.fromEntries(resourceMetricNames.map((name) => {
        const value = pressureMetric(result, name);
        if (value === null || value < 0) errors.push(`${revision}: ${name} requires a non-negative finite value`);
        return [name, value ?? 0];
    }));
    const baseResources = resourceSnapshot(baseResult, "base");
    const candidateResources = resourceSnapshot(candidateResult, "candidate");
    if (candidateIndexState === "startup-prepared") {
        if (baseResources.callSiteIndexAdmittedGraphs !== 64 ||
            baseResources.callSiteTrigramIndexedGraphs !== 64 ||
            baseResources.callSiteParallelScanCount !== 0 ||
            baseResources.callSiteParallelScanGraphCount !== 0
        ) {
            errors.push("base: startup-prepared reference must use the retained index without raw scans; " +
                `admitted=${baseResources.callSiteIndexAdmittedGraphs}, ` +
                `trigram=${baseResources.callSiteTrigramIndexedGraphs}, ` +
                `scans=${baseResources.callSiteParallelScanCount}, ` +
                `graphs=${baseResources.callSiteParallelScanGraphCount}`);
        }
        if (baseResources.callSiteStringIndexLookupCount !== 2043 ||
            baseResources.callSiteStringIndexLookupGraphCount !== 64 ||
            baseResources.callSiteStringIndexLookupMinPerGraph !== 30 ||
            baseResources.callSiteStringIndexLookupMaxPerGraph !== 39
        ) {
            errors.push("base: startup-prepared reference must preserve the optimized 2,043 retained-index " +
                "lookups distributed 30..39 per graph; " +
                `lookups=${baseResources.callSiteStringIndexLookupCount}, ` +
                `graphs=${baseResources.callSiteStringIndexLookupGraphCount}, ` +
                `perGraph=${baseResources.callSiteStringIndexLookupMinPerGraph}..` +
                `${baseResources.callSiteStringIndexLookupMaxPerGraph}`);
        }
    }
    if (candidateIndexState === "cold") {
        const rawBuildLifecycle = candidateResources.callSiteParallelScanCount === 64 &&
            candidateResources.callSiteParallelScanGraphCount === 64 &&
            candidateResources.callSiteScanPeakActiveWorkers >= 2;
        const persistedLoadLifecycle = candidateResources.callSiteParallelScanCount === 0 &&
            candidateResources.callSiteParallelScanGraphCount === 0 &&
            candidateResources.callSiteScanPeakActiveWorkers === 0 &&
            candidateResources.callSiteIndexAdmittedGraphs === 64 &&
            candidateResources.callSiteTrigramIndexedGraphs === 64;
        if (!rawBuildLifecycle && !persistedLoadLifecycle) {
            errors.push("candidate: cold selected-graph workload must either build one parallel index per graph " +
                "or restore all 64 persisted sidecars; " +
                `scans=${candidateResources.callSiteParallelScanCount}, ` +
                `graphs=${candidateResources.callSiteParallelScanGraphCount}, ` +
                `peak=${candidateResources.callSiteScanPeakActiveWorkers}, ` +
                `admitted=${candidateResources.callSiteIndexAdmittedGraphs}, ` +
                `trigram=${candidateResources.callSiteTrigramIndexedGraphs}`);
        }
        if (candidateResources.callSiteStringIndexLookupCount !== 1979 ||
            candidateResources.callSiteStringIndexLookupGraphCount !== 64 ||
            candidateResources.callSiteStringIndexLookupMinPerGraph !== 29 ||
            candidateResources.callSiteStringIndexLookupMaxPerGraph !== 38
        ) {
            errors.push("candidate: cold selected-graph workload must reuse the retained index for the " +
                "1,979 post-build accesses distributed 29..38 per graph; " +
                `lookups=${candidateResources.callSiteStringIndexLookupCount}, ` +
                `graphs=${candidateResources.callSiteStringIndexLookupGraphCount}, ` +
                `perGraph=${candidateResources.callSiteStringIndexLookupMinPerGraph}..` +
                `${candidateResources.callSiteStringIndexLookupMaxPerGraph}`);
        }
    } else if (candidateIndexState === "warm" || candidateIndexState === "startup-prepared") {
        if (candidateResources.callSiteIndexAdmittedGraphs !== 64 ||
            candidateResources.callSiteTrigramIndexedGraphs !== 64
        ) {
            errors.push("candidate: warm selected-graph workload must execute the retained trigram index path " +
                `for all 64 graphs; admitted=${candidateResources.callSiteIndexAdmittedGraphs}, ` +
                `trigram=${candidateResources.callSiteTrigramIndexedGraphs}`);
        }
        if (candidateResources.callSiteParallelScanCount !== 0 ||
            candidateResources.callSiteParallelScanGraphCount !== 0
        ) {
            errors.push("candidate: warm selected-graph workload must not fall back to raw scans; " +
                `scans=${candidateResources.callSiteParallelScanCount}, ` +
                `graphs=${candidateResources.callSiteParallelScanGraphCount}`);
        }
        if (candidateResources.callSiteStringIndexLookupCount !== 2043 ||
            candidateResources.callSiteStringIndexLookupGraphCount !== 64 ||
            candidateResources.callSiteStringIndexLookupMinPerGraph !== 30 ||
            candidateResources.callSiteStringIndexLookupMaxPerGraph !== 39
        ) {
            errors.push("candidate: warm selected-graph workload must execute exactly 2,043 retained-index " +
                "lookups distributed 30..39 per graph; " +
                `lookups=${candidateResources.callSiteStringIndexLookupCount}, ` +
                `graphs=${candidateResources.callSiteStringIndexLookupGraphCount}, ` +
                `perGraph=${candidateResources.callSiteStringIndexLookupMinPerGraph}..` +
                `${candidateResources.callSiteStringIndexLookupMaxPerGraph}`);
        }
    }

    const baseRows = parsePressureObservations(baseObservations, "base", errors);
    const candidateRows = parsePressureObservations(candidateObservations, "candidate", errors);
    const coldFirstId = "request-selected-set-wrapped-contains-k64-group-00-zero";
    let coldFirst = null;
    if (candidateIndexState === "cold") {
        const baseFirst = baseRows[0];
        const candidateFirst = candidateRows[0];
        if (baseFirst?.id !== coldFirstId || candidateFirst?.id !== coldFirstId) {
            errors.push(`cold: first observation must be ${coldFirstId} in both revisions`);
        } else {
            const baseLatencyNanos = finiteNumber(baseFirst.latencyNanos);
            const candidateLatencyNanos = finiteNumber(candidateFirst.latencyNanos);
            if (baseFirst.outcome !== "success" || candidateFirst.outcome !== "success" ||
                baseLatencyNanos === null || candidateLatencyNanos === null ||
                baseLatencyNanos <= 0 || candidateLatencyNanos <= 0
            ) {
                errors.push("cold: first K64 request requires successful positive latency samples");
            } else {
                const limitNanos = Math.max(baseLatencyNanos * 1.15, baseLatencyNanos + 250_000_000);
                if (candidateLatencyNanos > limitNanos) {
                    errors.push(`cold: first K64 request latency regressed; base/candidate ` +
                        `${baseLatencyNanos}/${candidateLatencyNanos}, limit ${limitNanos}`);
                }
                coldFirst = {
                    id: coldFirstId,
                    baseLatencyNanos,
                    candidateLatencyNanos,
                    speedup: baseLatencyNanos / candidateLatencyNanos,
                    limitNanos
                };
            }
        }
    }
    for (const row of candidateRows) {
        const graphIdPredicate = row.family === "graph-id" || row.family === "graph-id-set";
        const targetGraphIds = (row.targetGraphIds ?? "").split(",").filter(Boolean);
        const selectedGraphCount = finiteNumber(row.selectedGraphCount);
        const expectedWidths = row.family === "graph-id-set" || row.family === "graph-set-reference"
            ? new Set([2, 8, 64]) : new Set([1]);
        if (selectedGraphCount === null || !expectedWidths.has(selectedGraphCount) ||
            targetGraphIds.length !== selectedGraphCount || new Set(targetGraphIds).size !== selectedGraphCount ||
            row.targetGraphId !== targetGraphIds[0]
        ) {
            errors.push(`candidate/${row.id}: invalid selected graph set ` +
                `${row.selectedGraphCount}/${row.targetGraphId}/${row.targetGraphIds}`);
            continue;
        }
        const expectedPath = graphIdPredicate ? "cypher-graph-id-predicate" : "request-selected-source";
        const expectedInputSources = graphIdPredicate ? 64 : selectedGraphCount;
        if (row.executionPath !== expectedPath || finiteNumber(row.inputSourceCount) !== expectedInputSources) {
            errors.push(`candidate/${row.id}: execution path ${row.executionPath}/${row.inputSourceCount} ` +
                `does not prove ${expectedPath}/${expectedInputSources} input sources`);
        }
        const accessedGraphIds = (row.accessedGraphIds ?? "").split(",").filter(Boolean);
        const expectedAccessedGraphCount = row.selectivity === "dense" ? 1 : selectedGraphCount;
        const targetSet = new Set(targetGraphIds);
        const accessedSet = new Set(accessedGraphIds);
        const accessedOnlySelected = accessedSet.size === accessedGraphIds.length &&
            accessedGraphIds.every((graphId) => targetSet.has(graphId));
        const allSelectedAccessed = expectedAccessedGraphCount < selectedGraphCount ||
            targetGraphIds.every((graphId) => accessedSet.has(graphId));
        if (finiteNumber(row.accessedGraphCount) !== expectedAccessedGraphCount ||
            finiteNumber(row.targetGraphAccessCount) !== expectedAccessedGraphCount ||
            finiteNumber(row.nonTargetGraphAccessCount) !== 0 ||
            !accessedOnlySelected || !allSelectedAccessed
        ) {
            errors.push(`candidate/${row.id}: selected-graph isolation failed; accessed=${row.accessedGraphIds}, ` +
                `counts=${row.accessedGraphCount}/${row.targetGraphAccessCount}/${row.nonTargetGraphAccessCount}`);
        }
        for (const field of ["parallelScanCount", "indexLookupCount", "peakActiveWorkers", "graphWorkUnits"]) {
            const value = finiteNumber(row[field]);
            if (value === null || value < 0 || !Number.isInteger(value)) {
                errors.push(`candidate/${row.id}: ${field}=${row[field]} must be a non-negative integer`);
            }
        }
        const expectedSelections = graphIdPredicate ? 1 : 0;
        const expectedSourcesPruned = graphIdPredicate ? 64 - selectedGraphCount : 0;
        const expectedPruningExecutions = expectedSourcesPruned > 0 ? 1 : 0;
        if (finiteNumber(row.graphIdSourceSelections) !== expectedSelections ||
            finiteNumber(row.graphIdSourcePruningExecutions) !== expectedPruningExecutions ||
            finiteNumber(row.graphIdSourcesPruned) !== expectedSourcesPruned
        ) {
            errors.push(`candidate/${row.id}: planner routing diagnostics ` +
                `${row.graphIdSourceSelections}/${row.graphIdSourcePruningExecutions}/${row.graphIdSourcesPruned} ` +
                `do not prove ${expectedSelections}/${expectedPruningExecutions}/${expectedSourcesPruned}`);
        }
        if (finiteNumber(row.filteredNodeLimitFastPathExecutions) !== 1 ||
            finiteNumber(row.generalFallbackExecutions) !== 0
        ) {
            errors.push(`candidate/${row.id}: expected filtered-limit fast path without general fallback; ` +
                `fast=${row.filteredNodeLimitFastPathExecutions}, fallback=${row.generalFallbackExecutions}`);
        }
    }
    const bindObservationsToCorrectness = (observations, contents, revision) => {
        if (typeof contents !== "string") {
            errors.push(`${revision}: independent correctness evidence is required`);
            return;
        }
        const correctness = parseCorrectnessRecords(contents, `${revision}-correctness`, errors);
        const correctnessById = new Map(correctness.map((record) => [record.id, record]));
        if (correctness.length !== 1137 || correctnessById.size !== 1137) {
            errors.push(`${revision}: expected 1137 independent correctness records, found ${correctness.length}`);
        }
        const observationIds = new Set(observations.map((row) => row.id));
        for (const row of observations) {
            const singleMatch = row.id.match(/-target-(\d{2})-(zero|targeted|dense)$/);
            const setMatch = row.id.match(/-k(02|08|64)-group-(\d{2})-(zero|targeted|dense)$/);
            const singleCanonical = singleMatch !== null &&
                row.id === `${row.shape}-target-${singleMatch[1]}-${row.selectivity}` &&
                singleMatch[2] === row.selectivity;
            const setCanonical = setMatch !== null &&
                row.id === `${row.shape}-k${setMatch[1]}-group-${setMatch[2]}-${row.selectivity}` &&
                setMatch[3] === row.selectivity;
            if (!singleCanonical && !setCanonical) {
                errors.push(`${revision}/${row.id}: observation ID is not canonical`);
                continue;
            }
            const expected = correctnessById.get(row.id);
            if (expected === undefined) {
                errors.push(`${revision}/${row.id}: missing independent correctness record`);
                continue;
            }
            for (const field of [
                "family", "shape", "selectivity", "operator", "boundary", "projection",
                "targetGraphId", "workloadIdentity", "limit", "outcome", "rowCount",
                "responseBytes", "digest"
            ]) {
                if (String(row[field]) !== String(expected[field])) {
                    errors.push(`${revision}/${row.id}: ${field} differs from independent correctness record`);
                }
            }
        }
        for (const id of correctnessById.keys()) {
            if (!observationIds.has(id)) {
                errors.push(`${revision}/${id}: correctness record has no latency observation`);
            }
        }
    };
    bindObservationsToCorrectness(baseRows, baseCorrectnessContents, "base");
    bindObservationsToCorrectness(candidateRows, candidateCorrectnessContents, "candidate");
    const baseGraphIdRows = baseRows.filter((row) => row.family === "graph-id");
    const candidateGraphIdRows = candidateRows.filter((row) => row.family === "graph-id");
    const baseGraphParameterRows = baseRows.filter((row) => row.family === "graph-parameter");
    const candidateGraphParameterRows = candidateRows.filter((row) => row.family === "graph-parameter");
    const baseGraphSetRows = baseRows.filter((row) => row.family === "graph-id-set");
    const candidateGraphSetRows = candidateRows.filter((row) => row.family === "graph-id-set");
    const baseGraphSetReferences = baseRows.filter((row) => row.family === "graph-set-reference");
    const candidateGraphSetReferences = candidateRows.filter((row) => row.family === "graph-set-reference");
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
        if (base.workloadIdentity !== candidate.workloadIdentity) {
            errors.push(`${id}: workload identity differs between base and candidate`);
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
            workloadIdentity: candidate.workloadIdentity,
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
        const workloadIdentities = new Set([...baseTargetRows, ...candidateTargetRows]
            .map((row) => row.workloadIdentity));
        if (workloadIdentities.size !== 1) {
            errors.push(`${targetId}: expected one stable workload identity`);
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
    const graphParameterShape = "request-selected-source-wrapped-contains";
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
            const expectedWorkloadIdentity = candidateTargetCounts.get(targetId)?.[0]?.workloadIdentity;
            if (baseReference.workloadIdentity !== candidateReference.workloadIdentity ||
                candidateReference.workloadIdentity !== expectedWorkloadIdentity) {
                errors.push(`${targetId}/${selectivity}: graph-parameter workload identity differs`);
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
    const graphSetKey = (row) => {
        const match = row.id.match(/-k(02|08|64)-group-(\d{2})-(zero|targeted|dense)$/);
        return match === null ? null : `${Number(match[1])}\u0000${Number(match[2])}\u0000${match[3]}`;
    };
    const indexGraphSetRows = (observations, revision, expectedCount) => {
        const indexed = new Map();
        for (const row of observations) {
            const key = graphSetKey(row);
            if (key === null) {
                errors.push(`${revision}/${row.id}: invalid graph-set id`);
                continue;
            }
            const rowsForSlot = indexed.get(key) ?? [];
            rowsForSlot.push(row);
            indexed.set(key, rowsForSlot);
        }
        if (observations.length !== expectedCount) {
            errors.push(`${revision}: expected ${expectedCount} graph-set rows, found ${observations.length}`);
        }
        return indexed;
    };
    const baseGraphSetsBySlot = indexGraphSetRows(baseGraphSetRows, "base", 246);
    const candidateGraphSetsBySlot = indexGraphSetRows(candidateGraphSetRows, "candidate", 246);
    const baseGraphSetReferencesBySlot = indexGraphSetRows(baseGraphSetReferences, "base-reference", 123);
    const candidateGraphSetReferencesBySlot = indexGraphSetRows(
        candidateGraphSetReferences, "candidate-reference", 123
    );
    const graphSetLatencyRows = [];
    for (const [width, groupCount] of GRAPH_SET_WIDTHS) {
        const coveredGraphIds = new Set();
        for (let groupIndex = 0; groupIndex < groupCount; groupIndex++) {
            let stableTargetSet = null;
            for (const selectivity of GRAPH_ROUTING_SELECTIVITIES) {
                const key = `${width}\u0000${groupIndex}\u0000${selectivity}`;
                const baseSet = baseGraphSetsBySlot.get(key) ?? [];
                const candidateSet = candidateGraphSetsBySlot.get(key) ?? [];
                const baseReferences = baseGraphSetReferencesBySlot.get(key) ?? [];
                const candidateReferences = candidateGraphSetReferencesBySlot.get(key) ?? [];
                if (baseSet.length !== 2 || candidateSet.length !== 2 ||
                    baseReferences.length !== 1 || candidateReferences.length !== 1
                ) {
                    errors.push(`k${width}/group-${groupIndex}/${selectivity}: expected two IN forms ` +
                        "and one request-selected reference in both revisions");
                    continue;
                }
                const baseReference = baseReferences[0];
                const candidateReference = candidateReferences[0];
                const targetSet = candidateReference.targetGraphIds;
                if (stableTargetSet !== null && stableTargetSet !== targetSet) {
                    errors.push(`k${width}/group-${groupIndex}: target set changes by selectivity`);
                }
                stableTargetSet = targetSet;
                const targetIdsForGroup = targetSet.split(",").filter(Boolean);
                if (targetIdsForGroup.length !== width || new Set(targetIdsForGroup).size !== width) {
                    errors.push(`k${width}/group-${groupIndex}: expected ${width} distinct real graph ids`);
                }
                targetIdsForGroup.forEach((graphId) => coveredGraphIds.add(graphId));
                for (const row of [...baseSet, ...candidateSet, baseReference, candidateReference]) {
                    if (row.targetGraphIds !== targetSet || finiteNumber(row.selectedGraphCount) !== width) {
                        errors.push(`${row.id}: graph-set identity differs from its request-selected reference`);
                    }
                    for (const field of [
                        "selectivity", "rowCount", "responseBytes", "digest", "workloadIdentity", "targetGraphId"
                    ]) {
                        if (row[field] !== candidateReference[field]) {
                            errors.push(`${row.id}: ${field} differs from request-selected K-source reference`);
                        }
                    }
                }
                const byShape = (rowsForRevision) => new Map(rowsForRevision.map((row) => [row.shape, row]));
                const baseByShape = byShape(baseSet);
                const candidateByShape = byShape(candidateSet);
                for (const identity of GRAPH_SET_ORACLE_SHAPES) {
                    const baseRow = baseByShape.get(identity.shape);
                    const candidateRow = candidateByShape.get(identity.shape);
                    if (baseRow === undefined || candidateRow === undefined) {
                        errors.push(`k${width}/group-${groupIndex}/${selectivity}: missing ${identity.shape}`);
                        continue;
                    }
                    const baseLatencyNanos = finiteNumber(baseRow.latencyNanos);
                    const candidateLatencyNanos = finiteNumber(candidateRow.latencyNanos);
                    if (baseLatencyNanos === null || candidateLatencyNanos === null ||
                        baseLatencyNanos <= 0 || candidateLatencyNanos <= 0
                    ) {
                        errors.push(`${candidateRow.id}: graph-set latency must be positive`);
                        continue;
                    }
                    graphSetLatencyRows.push({
                        width,
                        shape: identity.shape,
                        selectivity,
                        baseLatencyNanos,
                        candidateLatencyNanos
                    });
                }
            }
        }
        if (coveredGraphIds.size !== 64) {
            errors.push(`k${width}: deterministic disjoint groups cover ${coveredGraphIds.size}/64 real graphs`);
        }
    }
    const baseLatencies = rows.map((row) => row.baseLatencyNanos);
    const candidateLatencies = rows.map((row) => row.candidateLatencyNanos);
    const baseGraphIdP50 = pressurePercentile(baseLatencies, 0.50);
    const candidateGraphIdP50 = pressurePercentile(candidateLatencies, 0.50);
    const baseGraphIdP95 = pressurePercentile(baseLatencies, 0.95);
    const candidateGraphIdP95 = pressurePercentile(candidateLatencies, 0.95);
    const p50Speedup = rows.length === 0 ? 0 :
        baseGraphIdP50 / candidateGraphIdP50;
    const p95Speedup = rows.length === 0 ? 0 :
        baseGraphIdP95 / candidateGraphIdP95;
    const baseGraphParameterLatencies = graphParameterLatencyRows.map((row) => row.baseLatencyNanos);
    const candidateGraphParameterLatencies = graphParameterLatencyRows.map((row) => row.candidateLatencyNanos);
    const baseGraphParameterP50 = pressurePercentile(baseGraphParameterLatencies, 0.50);
    const candidateGraphParameterP50 = pressurePercentile(candidateGraphParameterLatencies, 0.50);
    const baseGraphParameterP95 = pressurePercentile(baseGraphParameterLatencies, 0.95);
    const candidateGraphParameterP95 = pressurePercentile(candidateGraphParameterLatencies, 0.95);
    const graphParameterP50Speedup = graphParameterLatencyRows.length === 0 ? 0 :
        baseGraphParameterP50 / candidateGraphParameterP50;
    const graphParameterP95Speedup = graphParameterLatencyRows.length === 0 ? 0 :
        baseGraphParameterP95 / candidateGraphParameterP95;
    const graphParameterP50Regression = graphParameterLatencyRows.length === 0 ? Number.POSITIVE_INFINITY :
        candidateGraphParameterP50 / baseGraphParameterP50 - 1;
    const graphParameterP95Regression = graphParameterLatencyRows.length === 0 ? Number.POSITIVE_INFINITY :
        candidateGraphParameterP95 / baseGraphParameterP95 - 1;
    const maximumGraphParameterRegression = 0.15;
    // A percentage-only guardrail is unstable for the tens-of-microseconds reference path.
    // Preserve the 15% bound for material latency and tolerate at most 0.25ms of absolute jitter.
    const maximumGraphParameterAbsoluteRegressionNanos = 250_000;
    const graphParameterP50Passed = candidateGraphParameterP50 <= Math.max(
        baseGraphParameterP50 * (1 + maximumGraphParameterRegression),
        baseGraphParameterP50 + maximumGraphParameterAbsoluteRegressionNanos
    );
    const graphParameterP95Passed = candidateGraphParameterP95 <= Math.max(
        baseGraphParameterP95 * (1 + maximumGraphParameterRegression),
        baseGraphParameterP95 + maximumGraphParameterAbsoluteRegressionNanos
    );
    const routingOverheadP50 = candidateRoutingOverheads.length === 0 ? Number.POSITIVE_INFINITY :
        pressurePercentile(candidateRoutingOverheads, 0.50);
    const routingOverheadP95 = candidateRoutingOverheads.length === 0 ? Number.POSITIVE_INFINITY :
        pressurePercentile(candidateRoutingOverheads, 0.95);
    const graphSetLatencyByWidth = [...GRAPH_SET_WIDTHS.keys()].map((width) => {
        const widthRows = graphSetLatencyRows.filter((row) => row.width === width);
        const baseWidthLatencies = widthRows.map((row) => row.baseLatencyNanos);
        const candidateWidthLatencies = widthRows.map((row) => row.candidateLatencyNanos);
        const baseP50 = pressurePercentile(baseWidthLatencies, 0.50);
        const baseP95 = pressurePercentile(baseWidthLatencies, 0.95);
        const candidateP50 = pressurePercentile(candidateWidthLatencies, 0.50);
        const candidateP95 = pressurePercentile(candidateWidthLatencies, 0.95);
        const p50Limit = Math.max(
            baseP50 * 1.15,
            baseP50 + maximumGraphParameterAbsoluteRegressionNanos
        );
        // K=64 is already a sub-2ms path and this pressure protocol intentionally uses one
        // full-corpus single shot. Keep the relative guardrail for material latency while
        // treating sub-1ms P95 movement as runner noise.
        const maximumGraphSetP95AbsoluteRegressionNanos = 1_000_000;
        const p95Limit = Math.max(
            baseP95 * 1.15,
            baseP95 + maximumGraphSetP95AbsoluteRegressionNanos
        );
        if (candidateP50 > p50Limit || candidateP95 > p95Limit) {
            errors.push(`k${width}: graph-set latency regressed; base/candidate P50 ` +
                `${baseP50}/${candidateP50}, P95 ${baseP95}/${candidateP95}`);
        }
        return {
            width,
            sampleCount: widthRows.length,
            baseP50,
            baseP95,
            candidateP50,
            candidateP95,
            p50Speedup: baseP50 / candidateP50,
            p95Speedup: baseP95 / candidateP95,
            normalizedCandidateP95: candidateP95 / width
        };
    });
    for (let index = 1; index < graphSetLatencyByWidth.length; index++) {
        const previous = graphSetLatencyByWidth[index - 1];
        const current = graphSetLatencyByWidth[index];
        if (current.normalizedCandidateP95 > previous.normalizedCandidateP95 * 1.5) {
            errors.push(`k${current.width}: candidate P95/source scales worse than 1.5x versus k${previous.width}`);
        }
    }
    const maximumGraphIdP50Regression = 0.15;
    const maximumGraphIdAbsoluteRegressionNanos = 250_000;
    const graphIdP50Regression = p50Speedup > 0 ? (1 / p50Speedup) - 1 : Number.POSITIVE_INFINITY;
    const graphIdP50Limit = Math.max(
        baseGraphIdP50 * (1 + maximumGraphIdP50Regression),
        baseGraphIdP50 + maximumGraphIdAbsoluteRegressionNanos
    );
    const graphIdP95Limit = Math.max(
        baseGraphIdP95 * (1 + maximumGraphIdP50Regression),
        baseGraphIdP95 + maximumGraphIdAbsoluteRegressionNanos
    );
    const p50Passed = candidateGraphIdP50 <= graphIdP50Limit;
    const p95Passed = candidateGraphIdP95 <= graphIdP95Limit;
    const gateP50Speedup = p50Speedup;
    const gateP95Speedup = p95Speedup;
    const passed = errors.length === 0 && p50Passed && p95Passed &&
        graphParameterP50Passed && graphParameterP95Passed;
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
        maximumGraphIdP50Regression,
        maximumGraphIdAbsoluteRegressionNanos,
        graphIdP50Regression,
        maximumGraphParameterRegression,
        maximumGraphParameterAbsoluteRegressionNanos,
        graphParameterP50Regression,
        graphParameterP95Regression,
        routingOverheadP50,
        routingOverheadP95,
        indexState: candidateIndexState,
        resources: {
            base: baseResources,
            candidate: candidateResources
        },
        rows,
        graphParameterLatencyRows,
        graphSetLatencyRows,
        graphSetLatencyByWidth,
        coldFirst
    };
}

function parseGlobalWideObservations(contents, revision, errors) {
    const lines = contents.trim().split(/\r?\n/);
    if (lines.length !== 35) {
        errors.push(`${revision}: expected a header plus 34 pressure observations, found ${lines.length}`);
        return [];
    }
    const headers = lines[0].split("\t");
    const required = [
        "id", "family", "shape", "selectivity", "operator", "boundary", "projection", "limit",
        "targetGraphId", "workloadIdentity", "outcome", "rowCount", "responseBytes", "digest",
        "latencyNanos", "fixtureDistributionId", "hitGraphIds", "executionPath", "inputSourceCount",
        "accessedGraphCount", "targetGraphIds",
        "selectedGraphCount", "accessedGraphIds", "targetGraphAccessCount", "nonTargetGraphAccessCount",
        "parallelScanCount", "indexLookupCount", "peakActiveWorkers", "graphWorkUnits",
        "graphIdSourceSelections", "graphIdSourcePruningExecutions", "graphIdSourcesPruned",
        "filteredNodeLimitFastPathExecutions", "generalFallbackExecutions"
    ];
    for (const header of required) {
        if (!headers.includes(header)) errors.push(`${revision}: pressure observations missing ${header}`);
    }
    const seen = new Set();
    return lines.slice(1).map((line) => {
        const values = line.split("\t");
        const row = Object.fromEntries(headers.map((header, index) => [header, values[index]]));
        if (seen.has(row.id)) errors.push(`${revision}: duplicate pressure observation ${row.id}`);
        seen.add(row.id);
        return row;
    });
}

function bindGlobalWideCorrectness(observations, oracleContents, revision, errors) {
    const oracle = parseCorrectnessRecords(oracleContents, "global-wide-oracle", errors, false);
    const oracleById = new Map(oracle.map((record) => [record.id, record]));
    if (oracle.length !== 34 || oracleById.size !== 34) {
        errors.push(`global-wide-oracle: expected 34 records, found ${oracle.length}`);
    }
    const observationIds = new Set(observations.map((row) => row.id));
    for (const row of observations) {
        const expected = oracleById.get(row.id);
        if (expected === undefined) {
            errors.push(`${revision}/${row.id}: missing from correctness oracle`);
            continue;
        }
        for (const field of [
            "family", "shape", "selectivity", "operator", "boundary", "projection", "targetGraphId",
            "workloadIdentity", "limit", "outcome", "rowCount", "responseBytes", "digest"
        ]) {
            if (String(row[field]) !== String(expected[field])) {
                errors.push(`${revision}/${row.id}: ${field} differs from correctness oracle`);
            }
        }
    }
    for (const id of oracleById.keys()) {
        if (!observationIds.has(id)) errors.push(`${revision}/${id}: oracle record has no latency observation`);
    }
}

function parseGlobalWideGraphManifest(contents, errors) {
    const lines = String(contents ?? "").split(/\r?\n/).map((line) => line.trim());
    const rows = lines
        .map((line) => line.trim())
        .filter((line) => line !== "" && !line.startsWith("#"))
        .map((line) => line.split("\t"));
    if (rows.length !== 64) errors.push(`global-wide-manifest: expected 64 graphs, found ${rows.length}`);
    if (rows.some((columns) => columns.length !== 6 || columns.some((column) => column === ""))) {
        errors.push("global-wide-manifest: every graph requires six non-empty columns");
    }
    const graphIds = rows.map((columns) => columns[0]);
    const workloadIdentities = rows.map((columns) => columns[5]);
    if (new Set(graphIds).size !== graphIds.length) {
        errors.push("global-wide-manifest: graph IDs must be unique");
    }
    if (new Set(workloadIdentities).size !== workloadIdentities.length ||
        workloadIdentities.some((identity) => !/^[0-9a-f]{64}$/.test(identity))
    ) {
        errors.push("global-wide-manifest: workload identities must be unique SHA-256 values");
    }
    const distributionRows = lines.filter((line) => line.startsWith("# global-wide-distribution-v1\t"))
        .map((line) => line.split("\t"));
    const distributions = new Map();
    for (const columns of distributionRows) {
        if (columns.length !== 5 || columns.some((column) => column === "")) {
            errors.push("global-wide-manifest: malformed distribution record");
            continue;
        }
        const [, id, targetGraphId, term, hitGraphIdsValue] = columns;
        const hitGraphIds = hitGraphIdsValue.split(",");
        if (distributions.has(id)) errors.push(`global-wide-manifest: duplicate distribution ${id}`);
        if (!graphIds.includes(targetGraphId) || hitGraphIds.length === 0 ||
            new Set(hitGraphIds).size !== hitGraphIds.length ||
            hitGraphIds.some((graphId) => !graphIds.includes(graphId)) ||
            hitGraphIds.join(",") !== graphIds.filter((graphId) => hitGraphIds.includes(graphId)).join(",")
        ) {
            errors.push(`global-wide-manifest: distribution ${id} is not bound in graph order`);
        }
        distributions.set(id, { id, targetGraphId, term, hitGraphIds });
    }
    const requiredDistributionIds = ["localized-early", "localized-middle", "localized-late", "broad-all-64"];
    if (distributionRows.length !== requiredDistributionIds.length ||
        requiredDistributionIds.some((id) => !distributions.has(id))
    ) {
        errors.push("global-wide-manifest: exact localized early/middle/late and broad-all-64 distributions required");
    }
    for (const id of requiredDistributionIds.filter((candidate) => candidate.startsWith("localized-"))) {
        const distribution = distributions.get(id);
        if (distribution && (distribution.hitGraphIds.length !== 1 ||
            distribution.hitGraphIds[0] !== distribution.targetGraphId)
        ) errors.push(`global-wide-manifest: ${id} must be globally localized to its target graph`);
    }
    const broad = distributions.get("broad-all-64");
    if (broad && broad.hitGraphIds.join(",") !== graphIds.join(",")) {
        errors.push("global-wide-manifest: broad-all-64 must hit all 64 real graphs in manifest order");
    }
    return {
        graphIds,
        graphIdSet: new Set(graphIds),
        workloadIdentities,
        workloadIdentitySet: new Set(workloadIdentities),
        calibrationGraphId: graphIds[0] ?? "",
        calibrationIdentity: rows[0]?.[5] ?? "",
        distributions
    };
}

const GLOBAL_WIDE_SHAPE_CONTRACTS = [
    ["global-wide-four-properties", "raw-contains", "single-query", "properties"],
    ["global-wide-class-pair", "raw-contains", "single-query", "class-properties"],
    ["global-wide-name-pair", "raw-contains", "single-query", "name-properties"],
    ["global-wide-caller-class", "raw-contains", "single-query", "caller-class"],
    ["global-wide-callee-class", "raw-contains", "single-query", "callee-class"],
    ["global-wide-provenance", "raw-contains", "single-query", "graph-id-properties"],
    ["global-wide-aliased", "raw-contains", "single-query", "aliased-properties"],
    ["global-wide-parameterized", "raw-contains", "parameters", "properties"],
    ["global-wide-wrapped-case-insensitive", "wrapped-lowercase-contains", "single-query", "properties"],
    ["global-wide-wrapped-case-insensitive-distinct", "wrapped-lowercase-contains", "single-query",
        "distinct-properties"]
];
const GLOBAL_WIDE_SHAPES = GLOBAL_WIDE_SHAPE_CONTRACTS.map(([shape]) => shape);
const GLOBAL_WIDE_DISTRIBUTION_IDS = [
    "localized-early", "localized-middle", "localized-late", "broad-all-64"
];
const GLOBAL_WIDE_DISTRIBUTION_SHAPES = GLOBAL_WIDE_DISTRIBUTION_IDS.map((id) =>
    `global-wide-distribution-${id}`);
const GLOBAL_WIDE_WRAPPED_SHAPES = [
    "global-wide-wrapped-case-insensitive",
    "global-wide-wrapped-case-insensitive-distinct"
];

export function compareGlobalWidePressure(
    baseResultSets,
    candidateResultSets,
    baseObservationContents,
    candidateObservationContents,
    correctnessOracle,
    minimumSpeedup = 10,
    runOrders = [],
    graphManifestContents = ""
) {
    const errors = [];
    const manifest = parseGlobalWideGraphManifest(graphManifestContents, errors);
    const expectedBenchmark =
        "io.johnsonlee.graphite.webgraph.LargeBroadQueryPressureBenchmark.replayBroadQueries";
    const selectResult = (results, revision, requireNcpuSplit) => {
        const matches = results.filter((result) => result.benchmark === expectedBenchmark &&
            result.params?.graphCount === "64" && result.params?.coverageFamily === "global-wide" &&
            result.params?.indexState === "cold");
        if (matches.length !== 1) {
            errors.push(`${revision}: expected exactly one 64-graph cold global-wide result`);
            return null;
        }
        const result = matches[0];
        for (const [metric, expected] of [
            ["graphCount", 64], ["distinctGraphPathCount", 64], ["queryCount", 34],
            ["successCount", 34], ["timeoutCount", 0], ["failureCount", 0],
            ["coverageShapeCount", 14], ["coverageFamilyCount", 1], ["coverageSelectivityCount", 3],
            ["coverageProjectionCount", 8], ["coverageOperatorCount", 2], ["coverageBoundaryCount", 3],
            ["maxHeapBytes", 8 * GIB]
        ]) {
            const actual = pressureMetric(result, metric);
            if (actual !== expected) errors.push(`${revision}: ${metric}=${actual}; expected ${expected}`);
        }
        const processors = pressureMetric(result, "availableProcessors");
        const graphWorkers = pressureMetric(result, "graphWorkerCount");
        const segmentWorkers = pressureMetric(result, "segmentWorkerCount");
        if (processors === null || processors < 1 || !Number.isInteger(processors)) {
            errors.push(`${revision}: availableProcessors=${processors}; expected a positive integer`);
        } else if (requireNcpuSplit) {
            const expectedGraphWorkers = processors === 1 ? 1 : Math.floor(processors / 2);
            const expectedSegmentWorkers = processors === 1 ? 0 : processors - expectedGraphWorkers;
            if (graphWorkers !== expectedGraphWorkers || segmentWorkers !== expectedSegmentWorkers) {
                errors.push(`${revision}: NCPU split ${processors} -> ${graphWorkers}+${segmentWorkers}; ` +
                    `expected ${expectedGraphWorkers}+${expectedSegmentWorkers}`);
            }
            const graphPeak = pressureMetric(result, "graphScanPeakActiveWorkers");
            const segmentPeak = pressureMetric(result, "segmentScanPeakActiveWorkers");
            const expectedGraphPeak = processors === 1 ? 0 : expectedGraphWorkers;
            if (graphPeak !== expectedGraphPeak || segmentPeak !== expectedSegmentWorkers) {
                errors.push(`${revision}: observed graph/segment worker peaks ${graphPeak}+${segmentPeak}; ` +
                    `expected ${expectedGraphPeak}+${expectedSegmentWorkers}`);
            }
        }
        for (const metric of [
            "p50LatencyNanos", "p95LatencyNanos", "maxLatencyNanos", "processCpuNanos",
            "cpuCoreUtilizationPermille",
            "peakUsedHeapBytes", "peakResidentSetBytes", "graphWorkUnits"
        ]) {
            const value = pressureMetric(result, metric);
            if (value === null || value <= 0) errors.push(`${revision}: ${metric} requires a positive finite value`);
        }
        return result;
    };
    const baseResults = baseResultSets.map((results, index) =>
        selectResult(results, `base-${index + 1}`, false));
    const candidateResults = candidateResultSets.map((results, index) =>
        selectResult(results, `candidate-${index + 1}`, true));
    if (baseResults.length < 3) errors.push("base: at least three independent JVM forks are required");
    if (candidateResults.length < 3) errors.push("candidate: at least three independent JVM forks are required");
    if (baseResults.length !== candidateResults.length) {
        errors.push("base/candidate: paired result run counts differ");
    }

    const validateRows = (contents, revision, requireAccessEvidence) => {
        const rows = parseGlobalWideObservations(contents, revision, errors);
        bindGlobalWideCorrectness(rows, correctnessOracle, revision, errors);
        for (const row of rows) {
            if (row.family !== "global-wide" || row.executionPath !== "cross-graph-query" ||
                finiteNumber(row.inputSourceCount) !== 64 || finiteNumber(row.selectedGraphCount) !== 0 ||
                row.targetGraphId !== "" || row.targetGraphIds !== ""
            ) {
                errors.push(`${revision}/${row.id}: expected an unscoped 64-source cross-graph query`);
            }
            if (row.outcome !== "success" || finiteNumber(row.latencyNanos) === null ||
                finiteNumber(row.latencyNanos) <= 0
            ) {
                errors.push(`${revision}/${row.id}: expected a successful positive latency sample`);
            }
            for (const field of [
                "accessedGraphCount", "targetGraphAccessCount", "nonTargetGraphAccessCount",
                "parallelScanCount", "indexLookupCount", "peakActiveWorkers", "graphWorkUnits"
            ]) {
                const value = finiteNumber(row[field]);
                if (value === null || value < 0 || !Number.isInteger(value)) {
                    errors.push(`${revision}/${row.id}: ${field}=${row[field]} must be a non-negative integer`);
                }
            }
            const accessedGraphIds = row.accessedGraphIds === "" ? [] : row.accessedGraphIds.split(",");
            const accessedGraphCount = finiteNumber(row.accessedGraphCount);
            if (accessedGraphIds.length !== accessedGraphCount ||
                new Set(accessedGraphIds).size !== accessedGraphIds.length
            ) {
                errors.push(`${revision}/${row.id}: accessed graph IDs must be unique and match ` +
                    `accessedGraphCount=${row.accessedGraphCount}`);
            }
            if (accessedGraphIds.some((graphId) => !manifest.graphIdSet.has(graphId))) {
                errors.push(`${revision}/${row.id}: accessed graph IDs are not bound to graphs.tsv`);
            }
            const hitGraphIds = row.hitGraphIds === "" ? [] : row.hitGraphIds.split(",");
            if (new Set(hitGraphIds).size !== hitGraphIds.length ||
                hitGraphIds.some((graphId) => !manifest.graphIdSet.has(graphId))
            ) {
                errors.push(`${revision}/${row.id}: result hit graph IDs must be unique graphs.tsv members`);
            }
            const shapeIndex = GLOBAL_WIDE_SHAPES.indexOf(row.shape);
            const distributionShapeIndex = GLOBAL_WIDE_DISTRIBUTION_SHAPES.indexOf(row.shape);
            const isDistribution = distributionShapeIndex >= 0;
            const distributionId = isDistribution ? GLOBAL_WIDE_DISTRIBUTION_IDS[distributionShapeIndex] : "";
            const distribution = isDistribution ? manifest.distributions.get(distributionId) : null;
            if (shapeIndex < 0 && !isDistribution) {
                errors.push(`${revision}/${row.id}: unknown global-wide shape ${row.shape}`);
            } else if (shapeIndex >= 0) {
                const [, operator, boundary, projection] = GLOBAL_WIDE_SHAPE_CONTRACTS[shapeIndex];
                if (row.operator !== operator || row.boundary !== boundary || row.projection !== projection) {
                    errors.push(`${revision}/${row.id}: shape contract must be ` +
                        `${operator}/${boundary}/${projection}`);
                }
                if (row.fixtureDistributionId !== "") {
                    errors.push(`${revision}/${row.id}: ordinary shape must not claim a fixture distribution`);
                }
            } else if (row.operator !== "wrapped-lowercase-contains" ||
                row.boundary !== "fixture-distribution" || row.projection !== "properties" ||
                row.selectivity !== "dense" || row.fixtureDistributionId !== distributionId
            ) {
                errors.push(`${revision}/${row.id}: malformed fixture distribution shape contract`);
            }
            const placementOrdinal = shapeIndex < 0 ? 0 :
                Math.floor(shapeIndex * (manifest.graphIds.length - 1) / (GLOBAL_WIDE_SHAPES.length - 1));
            const expectedOrdinal = isDistribution ? manifest.graphIds.indexOf(distribution?.targetGraphId) :
                row.selectivity === "dense" ? 0 : placementOrdinal;
            const expectedIdentity = manifest.workloadIdentities[expectedOrdinal] ?? "";
            const expectedGraphId = manifest.graphIds[expectedOrdinal] ?? "";
            if (row.workloadIdentity !== expectedIdentity || !manifest.workloadIdentitySet.has(row.workloadIdentity)) {
                errors.push(`${revision}/${row.id}: workload identity is not bound to its graphs.tsv placement`);
            }
            const rowCount = finiteNumber(row.rowCount);
            const accessedSet = new Set(accessedGraphIds);
            const hitSet = new Set(hitGraphIds);
            if (isDistribution) {
                if (rowCount !== 200) {
                    errors.push(`${revision}/${row.id}: distribution query must fill LIMIT 200`);
                }
                const expectedReturnedHits = distributionId === "broad-all-64" ?
                    [manifest.calibrationGraphId] : [distribution?.targetGraphId];
                if (hitGraphIds.length !== expectedReturnedHits.length ||
                    expectedReturnedHits.some((graphId) => !hitSet.has(graphId))
                ) {
                    errors.push(`${revision}/${row.id}: returned hit graphs do not match ${distributionId}`);
                }
                if (requireAccessEvidence) {
                    const targetReached = accessedSet.has(distribution?.targetGraphId);
                    const accessValid = distributionId === "localized-middle" ?
                        targetReached && accessedGraphCount > 1 && accessedGraphCount < 64 :
                        distributionId === "localized-late" ? targetReached && accessedGraphCount === 64 :
                            accessedGraphCount === 1 && accessedGraphIds[0] === manifest.calibrationGraphId;
                    if (!accessValid) {
                        errors.push(`${revision}/${row.id}: graph access does not exercise ${distributionId}`);
                    }
                }
            } else if (row.selectivity === "zero") {
                if (rowCount !== 0) errors.push(`${revision}/${row.id}: zero-hit query must return zero rows`);
                if (hitGraphIds.length !== 0) {
                    errors.push(`${revision}/${row.id}: zero-hit query reported hit graphs`);
                }
                if (requireAccessEvidence &&
                    (accessedGraphCount !== 64 ||
                        manifest.graphIds.some((graphId) => !accessedSet.has(graphId)) ||
                        finiteNumber(row.targetGraphAccessCount) !== 0 ||
                        finiteNumber(row.nonTargetGraphAccessCount) !== 64)
                ) errors.push(`${revision}/${row.id}: zero-hit query must prove the exact graphs.tsv set was searched`);
            } else if (row.selectivity === "targeted") {
                if (rowCount === null || rowCount < 1 || rowCount >= 200) {
                    errors.push(`${revision}/${row.id}: targeted query must return between 1 and 199 rows`);
                }
                if (!hitSet.has(expectedGraphId)) {
                    errors.push(`${revision}/${row.id}: targeted result must identify its calibrated hit graph`);
                }
                if (requireAccessEvidence && !accessedSet.has(expectedGraphId)) {
                    errors.push(`${revision}/${row.id}: targeted query must reach its real-graph placement`);
                }
            } else if (row.selectivity === "dense") {
                if (rowCount !== 200) {
                    errors.push(`${revision}/${row.id}: dense query must fill LIMIT 200`);
                }
                const requiresCompleteProvenance =
                    row.shape === "global-wide-wrapped-case-insensitive-distinct";
                if (!hitSet.has(manifest.calibrationGraphId) ||
                    !requiresCompleteProvenance && hitGraphIds.length !== 1
                ) {
                    errors.push(`${revision}/${row.id}: dense result must include its calibrated first graph`);
                }
                if (requireAccessEvidence) {
                    const accessValid = requiresCompleteProvenance ?
                        accessedGraphCount === 64 &&
                            manifest.graphIds.every((graphId) => accessedSet.has(graphId)) :
                        accessedGraphCount === 1 && accessedGraphIds[0] === manifest.calibrationGraphId;
                    if (!accessValid) {
                        errors.push(`${revision}/${row.id}: dense access does not match its provenance contract`);
                    }
                }
            }
            if (finiteNumber(row.graphIdSourceSelections) !== 0 ||
                finiteNumber(row.graphIdSourcePruningExecutions) !== 0 ||
                finiteNumber(row.graphIdSourcesPruned) !== 0 ||
                finiteNumber(row.filteredNodeLimitFastPathExecutions) !== 1 ||
                finiteNumber(row.generalFallbackExecutions) !== 0
            ) {
                errors.push(`${revision}/${row.id}: expected the non-routing filtered-limit execution path`);
            }
        }
        for (const shape of GLOBAL_WIDE_SHAPES) {
            for (const selectivity of ["zero", "targeted", "dense"]) {
                const count = rows.filter((row) =>
                    row.shape === shape && row.selectivity === selectivity).length;
                if (count !== 1) {
                    errors.push(`${revision}: expected exactly one ${shape}/${selectivity} observation`);
                }
            }
        }
        for (const shape of GLOBAL_WIDE_DISTRIBUTION_SHAPES) {
            const count = rows.filter((row) => row.shape === shape && row.selectivity === "dense").length;
            if (count !== 1) errors.push(`${revision}: expected exactly one ${shape}/dense observation`);
        }
        return rows;
    };
    // The tagged base predates per-graph access telemetry. Keep its result/correctness checks hard,
    // but require exact real-graph access evidence only from the candidate that introduces it.
    const baseRowsByRun = baseObservationContents.map((contents, index) =>
        validateRows(contents, `base-${index + 1}`, false));
    const candidateRowsByRun = candidateObservationContents.map((contents, index) =>
        validateRows(contents, `candidate-${index + 1}`, true));
    if (baseObservationContents.length !== baseResultSets.length) {
        errors.push("base: result and observation run counts differ");
    }
    if (candidateObservationContents.length !== candidateResultSets.length) {
        errors.push("candidate: result and observation run counts differ");
    }
    const allowedRunOrders = new Set(["candidate-base", "base-candidate"]);
    if (runOrders.length !== candidateResultSets.length ||
        runOrders.some((order) => !allowedRunOrders.has(order))) {
        errors.push("base/candidate: every paired fork requires a valid run order");
    }

    const observationLatencySummary = (rows, result, revision) => {
        const latencies = rows.map((row) => finiteNumber(row.latencyNanos)).filter((value) => value !== null)
            .sort((left, right) => left - right);
        if (latencies.length !== GLOBAL_WIDE_SHAPES.length * 3 + GLOBAL_WIDE_DISTRIBUTION_SHAPES.length) {
            return { p50: 0, p95: 0, max: 0 };
        }
        const percentile = (fraction) => latencies[Math.max(0, Math.ceil(fraction * latencies.length) - 1)];
        const summary = { p50: percentile(0.50), p95: percentile(0.95), max: latencies.at(-1) };
        for (const [metric, value] of [
            ["p50LatencyNanos", summary.p50],
            ["p95LatencyNanos", summary.p95],
            ["maxLatencyNanos", summary.max]
        ]) {
            const recorded = pressureMetric(result, metric);
            if (recorded !== value) {
                errors.push(`${revision}: ${metric}=${recorded}; observation rows recompute to ${value}`);
            }
        }
        return summary;
    };

    const alignedLatencyRegressions = new Map();
    const runs = candidateResults.map((result, index) => {
        const baseResult = baseResults[index];
        if (!baseResult || !result) return null;
        const baseLatency = observationLatencySummary(baseRowsByRun[index] ?? [], baseResult, `base-${index + 1}`);
        const candidateLatency = observationLatencySummary(
            candidateRowsByRun[index] ?? [], result, `candidate-${index + 1}`);
        const baseP50 = baseLatency.p50;
        const baseP95 = baseLatency.p95;
        const p50 = candidateLatency.p50;
        const p95 = candidateLatency.p95;
        const p95Speedup = p95 > 0 ? baseP95 / p95 : 0;
        const wrappedP95 = (rows, shape) => Math.max(0, ...rows
            .filter((row) => row.shape === shape)
            .map((row) => finiteNumber(row.latencyNanos) ?? 0));
        const wrappedShapeRuns = GLOBAL_WIDE_WRAPPED_SHAPES.map((shape) => {
            const baseLatencyNanos = wrappedP95(baseRowsByRun[index] ?? [], shape);
            const latencyNanos = wrappedP95(candidateRowsByRun[index] ?? [], shape);
            return {
                shape,
                baseLatencyNanos,
                latencyNanos,
                speedup: latencyNanos > 0 ? baseLatencyNanos / latencyNanos : 0
            };
        });
        const worstWrapped = wrappedShapeRuns.reduce((left, right) =>
            left.speedup < right.speedup ? left : right);
        const baseWrappedP95LatencyNanos = worstWrapped.baseLatencyNanos;
        const wrappedP95LatencyNanos = worstWrapped.latencyNanos;
        const wrappedP95Speedup = worstWrapped.speedup;
        const baseProcessCpuNanos = pressureMetric(baseResult, "processCpuNanos") ?? 0;
        const processCpuNanos = pressureMetric(result, "processCpuNanos") ?? 0;
        const basePeakUsedHeapBytes = pressureMetric(baseResult, "peakUsedHeapBytes") ?? 0;
        const peakUsedHeapBytes = pressureMetric(result, "peakUsedHeapBytes") ?? 0;
        const basePeakResidentSetBytes = pressureMetric(baseResult, "peakResidentSetBytes") ?? 0;
        const peakResidentSetBytes = pressureMetric(result, "peakResidentSetBytes") ?? 0;
        if (p95Speedup < minimumSpeedup) {
            errors.push(`pair-${index + 1}: P95 speedup ${p95Speedup.toFixed(2)}x; ` +
                `required ${minimumSpeedup.toFixed(2)}x in every independent fork`);
        }
        for (const wrapped of wrappedShapeRuns) {
            if (wrapped.speedup < minimumSpeedup) {
                errors.push(`pair-${index + 1}: ${wrapped.shape} P95 speedup ` +
                    `${wrapped.speedup.toFixed(2)}x; required ${minimumSpeedup.toFixed(2)}x`);
            }
        }
        const baseRows = new Map((baseRowsByRun[index] ?? []).map((row) => [row.id, row]));
        for (const row of candidateRowsByRun[index] ?? []) {
            const baseRow = baseRows.get(row.id);
            const baseHitGraphIds = (baseRow?.hitGraphIds ?? "").split(",").filter(Boolean).sort();
            const candidateHitGraphIds = (row.hitGraphIds ?? "").split(",").filter(Boolean).sort();
            if (baseHitGraphIds.join(",") !== candidateHitGraphIds.join(",")) {
                errors.push(`pair-${index + 1}/${row.shape}/${row.selectivity}: hit graph distribution ` +
                    `differs from the base reference`);
            }
            const baseRowLatency = finiteNumber(baseRow?.latencyNanos);
            const candidateRowLatency = finiteNumber(row.latencyNanos);
            if (baseRowLatency === null || candidateRowLatency === null) continue;
            if (candidateRowLatency > baseRowLatency * 1.15 &&
                candidateRowLatency - baseRowLatency > 1_000_000
            ) {
                const key = `${row.shape}/${row.selectivity}`;
                const samples = alignedLatencyRegressions.get(key) ?? [];
                samples.push(`pair-${index + 1}/${key}: aligned latency ` +
                    `${candidateRowLatency} exceeds base ${baseRowLatency} by >15% and >1 ms`);
                alignedLatencyRegressions.set(key, samples);
            }
        }
        for (const [label, baseValue, candidateValue] of [
            ["process CPU", baseProcessCpuNanos, processCpuNanos],
            ["peak used heap", basePeakUsedHeapBytes, peakUsedHeapBytes],
            ["peak RSS", basePeakResidentSetBytes, peakResidentSetBytes]
        ]) {
            if (baseValue > 0 && candidateValue > baseValue * 1.15) {
                errors.push(`pair-${index + 1}: ${label} ${candidateValue} exceeds paired base ${baseValue} by >15%`);
            }
        }
        return {
            order: runOrders[index] ?? "unknown",
            baseP50LatencyNanos: baseP50,
            baseP95LatencyNanos: baseP95,
            p50LatencyNanos: p50,
            p95LatencyNanos: p95,
            p50Speedup: p50 > 0 ? baseP50 / p50 : 0,
            p95Speedup,
            baseWrappedP95LatencyNanos,
            wrappedP95LatencyNanos,
            wrappedP95Speedup,
            wrappedShapeRuns,
            baseProcessCpuNanos,
            processCpuNanos,
            cpuCoreUtilizationPermille: pressureMetric(result, "cpuCoreUtilizationPermille") ?? 0,
            basePeakUsedHeapBytes,
            peakUsedHeapBytes,
            basePeakResidentSetBytes,
            peakResidentSetBytes
        };
    }).filter(Boolean);
    for (const samples of alignedLatencyRegressions.values()) {
        if (samples.length >= 2) errors.push(`${samples[0]}; repeated in ${samples.length} independent pairs`);
    }
    const median = (values) => {
        const sorted = [...values].sort((left, right) => left - right);
        if (sorted.length === 0) return 0;
        const middle = Math.floor(sorted.length / 2);
        return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    };
    const orderSummaries = [...allowedRunOrders].map((order) => {
        const orderedRuns = runs.filter((run) => run.order === order);
        const medianP50Speedup = median(orderedRuns.map((run) => run.p50Speedup));
        const medianP95Speedup = median(orderedRuns.map((run) => run.p95Speedup));
        if (orderedRuns.length === 0) errors.push(`${order}: no paired fork was recorded`);
        // Order medians remain diagnostic; every independent pair is gated above.
        return { order, runCount: orderedRuns.length, medianP50Speedup, medianP95Speedup };
    });
    return {
        passed: errors.length === 0,
        errors,
        minimumSpeedup,
        runs,
        orderSummaries
    };
}

export function renderGlobalWidePressureReport(comparison) {
    const ms = (nanos) => `${(nanos / 1e6).toFixed(3)} ms`;
    const gib = (bytes) => `${(bytes / GIB).toFixed(2)} GiB`;
    const worst = comparison.runs.length === 0 ? {
        p50LatencyNanos: 0,
        p95LatencyNanos: 0,
        p50Speedup: 0,
        p95Speedup: 0
    } : comparison.runs.reduce((left, right) =>
        left.p95Speedup < right.p95Speedup ? left : right);
    const worstOrder = comparison.orderSummaries.reduce((left, right) =>
        left.medianP95Speedup < right.medianP95Speedup ? left : right);
    const worstWrapped = comparison.runs.length === 0 ? { wrappedP95Speedup: 0 } :
        comparison.runs.reduce((left, right) =>
            left.wrappedP95Speedup < right.wrappedP95Speedup ? left : right);
    return [
        "### 64 fixture-derived global wide-query pressure gate",
        "",
        `Required P95 speedup in every independent paired fork: **${comparison.minimumSpeedup.toFixed(1)}x**`,
        "",
        `- Worst paired base P50 / P95: **${ms(worst.baseP50LatencyNanos)} / ` +
            `${ms(worst.baseP95LatencyNanos)}**`,
        `- Worst paired candidate P50 / P95: **${ms(worst.p50LatencyNanos)} / ` +
            `${ms(worst.p95LatencyNanos)}**`,
        `- Worst individual P95 speedup (retained for audit): **${worst.p95Speedup.toFixed(2)}x**`,
        `- Worst wrapped case-insensitive P95 speedup: **${worstWrapped.wrappedP95Speedup.toFixed(2)}x**`,
        `- Worst order-median P95 speedup: **${worstOrder.medianP95Speedup.toFixed(2)}x**`,
        ...comparison.orderSummaries.map(summary =>
            `- ${summary.order}: **${summary.runCount} pair(s), ` +
            `${summary.medianP50Speedup.toFixed(2)}x P50 / ${summary.medianP95Speedup.toFixed(2)}x P95**`),
        "",
        "| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | P50 speedup | P95 speedup | CPU total | CPU cores | Heap | RSS |",
        "| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ...comparison.runs.map((run, index) =>
            `| ${index + 1} | ${run.order} | ${ms(run.baseP50LatencyNanos)} | ${ms(run.baseP95LatencyNanos)} | ` +
            `${ms(run.p50LatencyNanos)} | ${ms(run.p95LatencyNanos)} | ` +
            `${run.p50Speedup.toFixed(2)}x | ${run.p95Speedup.toFixed(2)}x | ` +
            `${ms(run.baseProcessCpuNanos)} → ${ms(run.processCpuNanos)} | ` +
            `${(run.cpuCoreUtilizationPermille / 1000).toFixed(2)} | ` +
            `${gib(run.basePeakUsedHeapBytes)} → ${gib(run.peakUsedHeapBytes)} | ` +
            `${gib(run.basePeakResidentSetBytes)} → ${gib(run.peakResidentSetBytes)} |`
        ),
        "",
        comparison.passed ? "**Result: PASS**" : `**Result: FAIL**\n\n${comparison.errors.join("\n")}`,
        ""
    ].join("\n");
}

export function renderGraphIdPressureReport(comparison) {
    const baseResources = comparison.resources.base;
    const candidateResources = comparison.resources.candidate;
    const gibibytes = (bytes) => `${(bytes / (1024 ** 3)).toFixed(2)} GiB`;
    const lines = [
        "### 64 fixture-derived graphId pressure gate",
        "",
        `Index state: **${comparison.indexState}**`,
        "",
        `Post-optimization regression gate: query-level graphId and request-selected P50/P95 may regress by at most ` +
            `${(comparison.maximumGraphParameterRegression * 100).toFixed(0)}% or 0.25ms of absolute jitter.`,
        "",
        `- Query-level graphId P50 speedup: **${comparison.p50Speedup.toFixed(2)}x**`,
        `- Query-level graphId P95 speedup: **${comparison.p95Speedup.toFixed(2)}x**`,
        `- Request-selected P50 speedup: **${comparison.graphParameterP50Speedup.toFixed(2)}x**`,
        `- Request-selected P95 speedup: **${comparison.graphParameterP95Speedup.toFixed(2)}x**`,
        ...(comparison.coldFirst === null ? [] : [
            `- First cold K64 request: **${(comparison.coldFirst.baseLatencyNanos / 1e6).toFixed(3)} → ` +
                `${(comparison.coldFirst.candidateLatencyNanos / 1e6).toFixed(3)} ms ` +
                `(${comparison.coldFirst.speedup.toFixed(2)}x)**`
        ]),
        `- Request-selected regression: ` +
            `**${(comparison.graphParameterP50Regression * 100).toFixed(2)}% P50 / ` +
            `${(comparison.graphParameterP95Regression * 100).toFixed(2)}% P95**`,
        `- Candidate graphId/request-selected latency ratio: ` +
            `**${comparison.routingOverheadP50.toFixed(2)}x P50 / ${comparison.routingOverheadP95.toFixed(2)}x P95**`,
        "",
        "| Selected graph width | Samples | Base P50 | Candidate P50 | Base P95 | Candidate P95 | P95 speedup |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ...comparison.graphSetLatencyByWidth.map((summary) =>
            `| ${summary.width} | ${summary.sampleCount} | ${(summary.baseP50 / 1e6).toFixed(3)} ms | ` +
            `${(summary.candidateP50 / 1e6).toFixed(3)} ms | ${(summary.baseP95 / 1e6).toFixed(3)} ms | ` +
            `${(summary.candidateP95 / 1e6).toFixed(3)} ms | ${summary.p95Speedup.toFixed(2)}x |`
        ),
        "",
        `- Candidate intra-graph scans: **${candidateResources.callSiteParallelScanCount.toFixed(0)}**; ` +
            `graphs scanned: **${candidateResources.callSiteParallelScanGraphCount.toFixed(0)}**; ` +
            `peak simultaneously active workers: **${candidateResources.callSiteScanPeakActiveWorkers.toFixed(0)}**`,
        `- Candidate retained-index lookups: **${candidateResources.callSiteStringIndexLookupCount.toFixed(0)}**; ` +
            `graphs covered: **${candidateResources.callSiteStringIndexLookupGraphCount.toFixed(0)}**; ` +
            `per graph: **${candidateResources.callSiteStringIndexLookupMinPerGraph.toFixed(0)}..` +
            `${candidateResources.callSiteStringIndexLookupMaxPerGraph.toFixed(0)}**`,
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
        `- Trigram-indexed graphs: **${baseResources.callSiteTrigramIndexedGraphs.toFixed(0)} → ` +
            `${candidateResources.callSiteTrigramIndexedGraphs.toFixed(0)}**`,
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
    const baseComparison = compareJmh(baseResults, candidateResults, regressionThreshold, true);
    const anchorComparison = compareJmh(anchorResults, candidateResults, anchorThreshold, true);
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
        "against the current PR base. Every point estimate beyond either threshold triggers a",
        "reverse-order confirmation and blocks only when the same benchmark repeats the regression.",
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
            const callSiteIndexBytes = finiteNumber(measurement.callSiteIndexBytes);
            const productionIndexPrepared = finiteNumber(measurement.productionIndexPrepared);
            if (!Number.isSafeInteger(callSiteIndexBytes) || callSiteIndexBytes < 0 ||
                !Number.isSafeInteger(productionIndexPrepared) ||
                (productionIndexPrepared !== 0 && productionIndexPrepared !== 1) ||
                (productionIndexPrepared === 0 && callSiteIndexBytes !== 0) ||
                (productionIndexPrepared === 1 && callSiteIndexBytes <= 0)
            ) {
                errors.push(`${corpus}/${revision}: invalid production CallSite-index lifecycle measurement`);
            }
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

const GRAPH_ROUTING_STATES = ["cold", "warm", "startup-prepared"];

export function aggregateGraphRoutingStates(directory) {
    const errors = [];
    const states = {};
    const sections = [];
    for (const state of GRAPH_ROUTING_STATES) {
        const statusFile = path.join(directory, `graph-routing-${state}-status.json`);
        const reportFile = path.join(directory, `graph-routing-${state}-report.md`);
        if (!fs.existsSync(statusFile) || !fs.existsSync(reportFile)) {
            errors.push(`${state}: state result artifact is missing`);
            states[state] = { passed: false, errors: ["State result artifact is missing"] };
            continue;
        }
        let status;
        try {
            status = readJson(statusFile);
        } catch (error) {
            const message = error instanceof Error ? error.message : String(error);
            errors.push(`${state}: invalid status: ${message}`);
            states[state] = { passed: false, errors: [message] };
            continue;
        }
        states[state] = status;
        sections.push(fs.readFileSync(reportFile, "utf8").trim());
        if (status.passed !== true) {
            const stateErrors = Array.isArray(status.errors) && status.errors.length > 0
                ? status.errors
                : ["State comparator failed"];
            errors.push(...stateErrors.map((error) => `${state}: ${error}`));
        }
    }
    const passed = errors.length === 0 && GRAPH_ROUTING_STATES.every((state) => states[state]?.passed === true);
    const body = [
        "### 64 fixture-derived graph routing pressure",
        "",
        passed ? "**PASS**" : "**FAIL**",
        "",
        ...sections,
        ...(errors.length > 0 ? ["", "Aggregate errors:", ...errors.map((error) => `- ${error}`)] : [])
    ].join("\n") + "\n";
    return { passed, errors, states, body };
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
        fs.readFileSync(requireArg(args, "base-correctness"), "utf8"),
        fs.readFileSync(requireArg(args, "candidate-correctness"), "utf8"),
        Number(args["minimum-speedup"] ?? 10)
    );
    writeFile(requireArg(args, "report"), renderGraphIdPressureReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function commaSeparatedFiles(args, name) {
    return requireArg(args, name).split(",").map((file) => file.trim()).filter(Boolean);
}

function compareGlobalWidePressureCommand(args) {
    const baseFiles = commaSeparatedFiles(args, "bases");
    const candidateFiles = commaSeparatedFiles(args, "candidates");
    const baseObservationFiles = commaSeparatedFiles(args, "base-observations");
    const candidateObservationFiles = commaSeparatedFiles(args, "candidate-observations");
    const comparison = compareGlobalWidePressure(
        baseFiles.map(readJson),
        candidateFiles.map(readJson),
        baseObservationFiles.map((file) => fs.readFileSync(file, "utf8")),
        candidateObservationFiles.map((file) => fs.readFileSync(file, "utf8")),
        fs.readFileSync(requireArg(args, "correctness-oracle"), "utf8"),
        Number(args["minimum-speedup"] ?? 10),
        requireArg(args, "run-orders").split(",").map((order) => order.trim()).filter(Boolean),
        fs.readFileSync(requireArg(args, "graph-manifest"), "utf8")
    );
    writeFile(requireArg(args, "report"), renderGlobalWidePressureReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function deriveGraphRoutingOracleCommand(args) {
    const result = deriveGraphRoutingOracle(
        fs.readFileSync(requireArg(args, "references"), "utf8")
    );
    if (!result.passed) {
        throw new Error(`Unable to derive graph-routing oracle:\n${result.errors.join("\n")}`);
    }
    writeFile(requireArg(args, "oracle"), result.oracle);
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

function aggregateGraphRoutingStatesCommand(args) {
    const aggregated = aggregateGraphRoutingStates(requireArg(args, "directory"));
    writeFile(requireArg(args, "report"), aggregated.body);
    writeJson(requireArg(args, "status"), {
        passed: aggregated.passed,
        errors: aggregated.errors,
        states: aggregated.states
    });
    if (!aggregated.passed) process.exitCode = 1;
}

function main(argv) {
    const args = parseArgs(argv);
    const command = args._[0];
    if (command === "compare-jmh") compareJmhCommand(args);
    else if (command === "compare-latency-baseline") compareLatencyBaselineCommand(args);
    else if (command === "derive-graph-routing-oracle") deriveGraphRoutingOracleCommand(args);
    else if (command === "compare-graph-id-pressure") compareGraphIdPressureCommand(args);
    else if (command === "compare-global-wide-pressure") compareGlobalWidePressureCommand(args);
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
    else if (command === "aggregate-graph-routing-states") aggregateGraphRoutingStatesCommand(args);
    else if (command === "aggregate") aggregateCommand(args);
    else throw new Error(`Unknown command: ${command ?? "<missing>"}`);
}

if (process.argv[1] !== undefined &&
    fs.realpathSync(fileURLToPath(import.meta.url)) === fs.realpathSync(process.argv[1])
) {
    try {
        main(process.argv.slice(2));
    } catch (error) {
        console.error(error instanceof Error ? error.stack : String(error));
        process.exitCode = 1;
    }
}
