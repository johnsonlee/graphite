#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

export const COMMENT_MARKER = "<!-- graphite-benchmark-regression-gate -->";

const MIB = 1024 * 1024;
export const LATENCY_EXPECTED_BENCHMARK_KEYS = [
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.coldWrappedCaseInsensitiveDiscovery[graphCount=1]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.coldWrappedCaseInsensitiveDiscovery[graphCount=17]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery[graphCount=1]",
    "io.johnsonlee.graphite.webgraph.WrappedDiscoveryLatencyBenchmark.wrappedCaseInsensitiveDiscovery[graphCount=17]",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.broadlyDistributedClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.denseDistributedMethodContainsCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.earlyGraphClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.firstLastGraphBimodalClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.lateGraphClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.middleGraphsClassPrefixCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.skewedMixedClassMethodOperatorCaseInsensitiveDiscovery",
    "io.johnsonlee.graphite.webgraph.AllFixtureWrappedDiscoveryLatencyBenchmark.zeroHitBroadContainsCaseInsensitiveDiscovery"
];
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
    if (baseScore <= 0) return Number.POSITIVE_INFINITY;
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

export function compareJmh(baseResults, candidateResults, threshold = 15) {
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
        if (baseScore === null || candidateScore === null || baseScore <= 0 || candidateScore < 0) {
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
            blocked: aboveThreshold && separated
        });
    }

    if (rows.length === 0) errors.push("No comparable JMH benchmarks were found");
    return {
        passed: errors.length === 0 && rows.every((row) => !row.blocked),
        errors,
        rows
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
        rows
    };
}

export function renderJmhReport(comparison) {
    const lines = [
        "### Method-level JMH",
        "",
        "A row blocks only when it exceeds the 15% limit, the 99.9% confidence intervals do not overlap,",
        "and a reverse-order confirmation run fails the same benchmark.",
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
        const speedup = ((fixedScore / candidateScore) - 1) * 100;
        const improvementSeparated = confidenceSeparates(
            confidenceBounds(current.primaryMetric ?? {}),
            confidenceBounds(baseline.primaryMetric ?? {}),
            true
        );
        const improvementBlocked = speedup < minimumSpeedup || !improvementSeparated;
        rows.push({
            ...baseRow,
            fixedScore,
            speedup,
            minimumSpeedup,
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

export function renderLatencyBaselineReport(comparison) {
    const lines = [
        "### Wrapped case-insensitive query latency",
        "",
        "The PR must remain at least 50% faster than the fixed pre-PR-95 baseline and must not regress",
        "more than 15% against the PR base with separated 99.9% confidence intervals.",
        "",
        "| Benchmark | Pre-PR-95 | PR base | PR | Speedup vs fixed | Regression vs base | Gate |",
        "|---|---:|---:|---:|---:|---:|:---:|"
    ];
    for (const row of comparison.rows) {
        lines.push(
            `| \`${shortBenchmarkName(row.key)}\` | ${formatScore(row.fixedScore)} ${row.unit} | ` +
            `${formatScore(row.baseScore)} ${row.unit} | ${formatScore(row.candidateScore)} ${row.unit} | ` +
            `${formatDelta(row.speedup)} | ${formatDelta(row.delta)} | ` +
            `**${row.blocked ? "FAIL" : "PASS"}** |`
        );
    }
    if (comparison.errors.length > 0) {
        lines.push("", "Errors:", ...comparison.errors.map((error) => `- ${error}`));
    }
    return `${lines.join("\n")}\n`;
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
        { name: "large-corpus", report: "large-corpus-report.md", status: "large-corpus-status.json" },
        { name: "wrapped-query-latency", report: "latency-report.md", status: "latency-status.json" }
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

function compareJmhCommand(args) {
    const comparison = compareJmh(
        readJson(requireArg(args, "base")),
        readJson(requireArg(args, "candidate")),
        Number(args.threshold ?? 15)
    );
    writeFile(requireArg(args, "report"), renderJmhReport(comparison));
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
        LATENCY_EXPECTED_BENCHMARK_KEYS
    );
    writeFile(requireArg(args, "report"), renderLatencyBaselineReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function confirmJmhCommand(args) {
    const initial = readJson(requireArg(args, "initial"));
    const confirmation = compareJmh(
        readJson(requireArg(args, "base")),
        readJson(requireArg(args, "candidate")),
        Number(args.threshold ?? 15)
    );
    const comparison = confirmJmh(initial, confirmation);
    writeFile(requireArg(args, "report"), renderJmhReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
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
    else if (command === "confirm-jmh") confirmJmhCommand(args);
    else if (command === "compare-large-corpus") compareLargeCorpusCommand(args);
    else if (command === "confirm-large-corpus") confirmLargeCorpusCommand(args);
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
