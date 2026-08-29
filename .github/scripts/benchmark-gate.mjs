#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const COMMENT_MARKER = "<!-- graphite-benchmark-regression-gate -->";

const MIB = 1024 * 1024;
const SYNTHETIC_LATENCY_ABSOLUTE_NOISE_FLOOR_MS = 0.5;
export const LATENCY_EXPECTED_BENCHMARK_KEYS = [
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.coldWrappedCaseInsensitiveDiscovery[graphCount=1]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.coldWrappedCaseInsensitiveDiscovery[graphCount=4]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.coldWrappedCaseInsensitiveDiscovery[graphCount=16]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.coldWrappedCaseInsensitiveDiscovery[graphCount=64]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery[graphCount=1]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery[graphCount=4]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery[graphCount=16]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery[graphCount=64]",
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
    "synthetic-1", "synthetic-4", "synthetic-16", "synthetic-64",
    "real-a", "real-b", "real-c", "real-d", "real-36"
];
export const LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS = [
    "io.johnsonlee.graphite.webgraph.SingleGraphWrappedDiscoveryResourceBenchmark.singleGraphFootprint",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryResourceBenchmark.allFixtureThirtySixGraphFootprint"
];
const GIB = 1024 * MIB;
const LATENCY_RESOURCE_PROFILES = new Map([
    [LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS[0], { maxHeapBytes: 4 * GIB }],
    [LATENCY_RESOURCE_EXPECTED_BENCHMARK_KEYS[1], { maxHeapBytes: 8 * GIB }]
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
    const base = new Map();
    const candidate = new Map();
    for (const result of baseResults) base.set(benchmarkKey(result), result);
    for (const result of candidateResults) candidate.set(benchmarkKey(result), result);

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
    const fixed = new Map(fixedResults.map((result) => [benchmarkKey(result), result]));
    const candidate = new Map(candidateResults.map((result) => [benchmarkKey(result), result]));
    const errors = [...regression.errors];
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
        const syntheticScale = /WrappedDiscoveryLatencyBenchmark.*graphCount=/.test(key);
        const absoluteRegression = candidateScore - baseRow.baseScore;
        const belowAbsoluteNoiseFloor = syntheticScale && baseRow.unit === "ms/op" &&
            absoluteRegression < SYNTHETIC_LATENCY_ABSOLUTE_NOISE_FLOOR_MS;
        const regressionBlocked = baseRow.blocked && !belowAbsoluteNoiseFloor;
        rows.push({
            ...baseRow,
            fixedScore,
            speedup,
            minimumSpeedup: requiredSpeedup,
            improvementSeparated,
            improvementBlocked,
            absoluteRegression,
            absoluteNoiseFloor: syntheticScale ? SYNTHETIC_LATENCY_ABSOLUTE_NOISE_FLOOR_MS : null,
            belowAbsoluteNoiseFloor,
            blocked: regressionBlocked || improvementBlocked
        });
    }

    if (rows.length === 0) errors.push("No comparable latency benchmarks were found");
    return {
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
    for (const shard of LATENCY_EXPECTED_SHARDS) {
        const statusFile = path.join(directory, `latency-status-${shard}.json`);
        if (!fs.existsSync(statusFile)) {
            errors.push(`${shard}: latency shard status is missing`);
            continue;
        }
        const status = readJson(statusFile);
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
    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
    };
}

export function parseLargeCorpusLog(contents) {
    const results = new Map();
    for (const line of contents.split(/\r?\n/)) {
        const marker = line.indexOf("LARGE_CORPUS_BASELINE");
        if (marker < 0) continue;
        const tokens = line.slice(marker).trim().split(/\s+/);
        if (tokens.length < 3) continue;
        const corpus = tokens[1];
        const measurement = { corpus };
        for (const token of tokens.slice(2)) {
            const separator = token.indexOf("=");
            if (separator < 1) continue;
            const name = token.slice(0, separator);
            const value = finiteNumber(token.slice(separator + 1));
            if (value !== null) measurement[name] = value;
        }
        results.set(corpus, measurement);
    }
    return results;
}

export function compareLargeCorpus(baseLog, candidateLog) {
    const base = parseLargeCorpusLog(baseLog);
    const candidate = parseLargeCorpusLog(candidateLog);
    const errors = [];
    const rows = [];
    const corpora = [...new Set([...base.keys(), ...candidate.keys()])].sort();

    for (const corpus of corpora) {
        const baseline = base.get(corpus);
        const current = candidate.get(corpus);
        if (baseline === undefined || current === undefined) {
            errors.push(`${corpus}: missing from ${baseline === undefined ? "base" : "candidate"} results`);
            continue;
        }
        for (const metric of LARGE_CORPUS_METRICS) {
            const baseValue = finiteNumber(baseline[metric.key]);
            const candidateValue = finiteNumber(current[metric.key]);
            if (baseValue === null || candidateValue === null || baseValue <= 0 || candidateValue < 0) {
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

    if (corpora.length === 0) errors.push("No large-corpus measurements were found");
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
            `${confirmation} | ${row.advisory ? "4 GiB cap" : `${row.threshold.toFixed(0)}%`} | ` +
            `**${statusLabel(row)}** |`
        );
    }
    if (comparison.errors.length > 0) {
        lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    }
    return `${lines.join("\n")}\n`;
}

export function aggregateReports(directory, metadata) {
    const components = [
        { name: "method-level", report: "method-report.md", status: "method-status.json" },
        { name: "explorer", report: "explorer-report.md", status: "explorer-status.json" },
        {
            name: "method-compatibility",
            report: "method-compatibility-report.md",
            status: "method-compatibility-status.json"
        },
        {
            name: "cypher-capacity",
            report: "cypher-capacity-report.md",
            status: "cypher-capacity-status.json"
        },
        {
            name: "budgeted-collection",
            report: "budgeted-collection-report.md",
            status: "budgeted-collection-status.json"
        },
        {
            name: "budgeted-mapped-string",
            report: "budgeted-string-report.md",
            status: "budgeted-string-status.json"
        },
        { name: "large-corpus", report: "large-corpus-report.md", status: "large-corpus-status.json" },
        { name: "wrapped-query-latency", report: "latency-report.md", status: "latency-status.json" },
        { name: "wrapped-query-resources", report: "latency-resource-report.md", status: "latency-resource-status.json" }
    ];
    const errors = [];
    const reports = [];
    let passed = true;
    for (const component of components) {
        const reportFile = path.join(directory, component.report);
        const statusFile = path.join(directory, component.status);
        if (!fs.existsSync(reportFile) || !fs.existsSync(statusFile)) {
            errors.push(`${component.name}: result artifact is missing`);
            passed = false;
            continue;
        }
        reports.push(fs.readFileSync(reportFile, "utf8").trim());
        const status = readJson(statusFile);
        if (status.passed !== true) passed = false;
    }

    const result = passed && errors.length === 0 ? "PASS" : "FAIL";
    const body = [
        COMMENT_MARKER,
        "## Benchmark Regression Gate",
        "",
        `**${result}**`,
        "",
        `Base: \`${metadata.baseSha.slice(0, 12)}\`  `,
        `PR: \`${metadata.candidateSha.slice(0, 12)}\`  `,
        `Runner: \`${metadata.runner}\``,
        "",
        ...reports.flatMap((report) => [report, ""]),
        ...(errors.length > 0 ? ["### Infrastructure errors", "", ...errors.map((error) => `- ${error}`), ""] : []),
        `[View benchmark logs and artifacts](${metadata.runUrl})`
    ].join("\n");
    return { passed: result === "PASS", errors, body: `${body}\n` };
}

export function stageLatestArtifacts(directory, output) {
    const artifacts = fs.readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isDirectory())
        .map((entry) => {
            const match = entry.name.match(/-(\d+)$/);
            return match === null ? null : { name: entry.name, attempt: Number(match[1]) };
        })
        .filter((entry) => entry !== null)
        .sort((left, right) => left.attempt - right.attempt || left.name.localeCompare(right.name));
    if (artifacts.length === 0) throw new Error(`No benchmark artifacts found in ${directory}`);
    fs.mkdirSync(output, { recursive: true });
    for (const artifact of artifacts) {
        fs.cpSync(path.join(directory, artifact.name), output, { recursive: true, force: true });
    }
    return artifacts.map((artifact) => artifact.name);
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

function combineLatencyShardsCommand(args) {
    const comparison = combineLatencyShards(requireArg(args, "directory"));
    writeFile(requireArg(args, "report"), renderLatencyBaselineReport(comparison));
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
    else if (command === "confirm-latency-baseline") confirmLatencyBaselineCommand(args);
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
