#!/usr/bin/env node

import fs from "node:fs";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { compareGlobalWidePressure } from "./benchmark-gate.mjs";

export const GLOBAL_WIDE_GOAL_BASE_SHA = "78ce46b57b2d88ae0f1823432ffefc5c7685bc1b";

const DEFAULT_MAXIMUM_REGRESSION_PERCENT = 15;
const DEFAULT_MINIMUM_REGRESSION_NANOS = 1_000_000;

function finiteNumber(value) {
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
}

function metricRegression(baseValue, candidateValue, maximumPercent, minimumNanos) {
    const base = finiteNumber(baseValue);
    const candidate = finiteNumber(candidateValue);
    if (base === null || candidate === null || base <= 0 || candidate <= 0) {
        return { valid: false, base, candidate, regressionPercent: 0, absoluteIncreaseNanos: 0, material: false };
    }
    const absoluteIncreaseNanos = candidate - base;
    const regressionPercent = absoluteIncreaseNanos / base * 100;
    return {
        valid: true,
        base,
        candidate,
        regressionPercent,
        absoluteIncreaseNanos,
        material: regressionPercent > maximumPercent && absoluteIncreaseNanos > minimumNanos
    };
}

export function applyCurrentMainNonRegressionPolicy(
    structuralComparison,
    maximumRegressionPercent = DEFAULT_MAXIMUM_REGRESSION_PERCENT,
    minimumRegressionNanos = DEFAULT_MINIMUM_REGRESSION_NANOS
) {
    const errors = Array.isArray(structuralComparison?.errors) ? [...structuralComparison.errors] : [];
    const runs = Array.isArray(structuralComparison?.runs) ? structuralComparison.runs : [];
    if (structuralComparison?.passed !== true && errors.length === 0) {
        errors.push("current-main: structural global-wide comparison failed without an error");
    }
    if (!Number.isFinite(maximumRegressionPercent) || maximumRegressionPercent < 0) {
        errors.push("current-main: maximum regression percent must be finite and non-negative");
    }
    if (!Number.isFinite(minimumRegressionNanos) || minimumRegressionNanos < 0) {
        errors.push("current-main: minimum regression latency must be finite and non-negative");
    }
    if (runs.length !== 3) errors.push("current-main: exactly three paired forks are required");

    const measurements = [];
    const materialByMetric = new Map();
    const addMeasurement = (pair, metric, baseValue, candidateValue) => {
        const regression = metricRegression(
            baseValue,
            candidateValue,
            maximumRegressionPercent,
            minimumRegressionNanos
        );
        measurements.push({ pair, metric, ...regression });
        if (!regression.valid) {
            errors.push(`current-main/pair-${pair}/${metric}: latency values must be finite and positive`);
        } else if (regression.material) {
            const samples = materialByMetric.get(metric) ?? [];
            samples.push({ pair, ...regression });
            materialByMetric.set(metric, samples);
        }
    };

    runs.forEach((run, index) => {
        const pair = index + 1;
        addMeasurement(pair, "aggregate-p50", run.baseP50LatencyNanos, run.p50LatencyNanos);
        addMeasurement(pair, "aggregate-p95", run.baseP95LatencyNanos, run.p95LatencyNanos);
        const wrapped = Array.isArray(run.wrappedShapeRuns) ? run.wrappedShapeRuns : [];
        if (wrapped.length !== 2) {
            errors.push(`current-main/pair-${pair}: expected exactly two wrapped-shape measurements`);
        }
        for (const shape of wrapped) {
            addMeasurement(
                pair,
                `wrapped/${shape.shape}`,
                shape.baseLatencyNanos,
                shape.latencyNanos
            );
        }
    });

    for (const [metric, samples] of materialByMetric) {
        if (samples.length < 2) continue;
        const detail = samples.map((sample) =>
            `pair-${sample.pair} ${sample.regressionPercent.toFixed(2)}%/+` +
            `${(sample.absoluteIncreaseNanos / 1e6).toFixed(3)}ms`
        ).join(", ");
        errors.push(
            `current-main/${metric}: material regression repeated in ${samples.length} independent pairs ` +
            `(${detail})`
        );
    }

    const { minimumSpeedup: _ignoredMinimumSpeedup, ...structural } = structuralComparison ?? {};
    return {
        ...structural,
        comparison: "current-main-non-regression",
        passed: structuralComparison?.passed === true && errors.length === 0,
        errors,
        maximumRegressionPercent,
        minimumRegressionNanos,
        measurements
    };
}

function milliseconds(nanos) {
    return `${(nanos / 1e6).toFixed(3)} ms`;
}

function gibibytes(bytes) {
    return `${(bytes / (1024 ** 3)).toFixed(2)} GiB`;
}

export function renderCurrentMainNonRegressionReport(comparison) {
    const latencyMeasurements = comparison.measurements.filter((measurement) => measurement.valid);
    const lines = [
        "### Current-main global wide-query non-regression gate",
        "",
        `A latency regression blocks only when it exceeds ${comparison.maximumRegressionPercent.toFixed(0)}% ` +
            `and ${(comparison.minimumRegressionNanos / 1e6).toFixed(0)} ms in at least two of three forks.`,
        "Correctness, graph access, CPU, heap, and RSS remain hard requirements in every paired fork.",
        "",
        "| Pair | Order | Base P50 | Base P95 | Candidate P50 | Candidate P95 | CPU total | Heap | RSS |",
        "| ---: | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ...comparison.runs.map((run, index) =>
            `| ${index + 1} | ${run.order} | ${milliseconds(run.baseP50LatencyNanos)} | ` +
            `${milliseconds(run.baseP95LatencyNanos)} | ${milliseconds(run.p50LatencyNanos)} | ` +
            `${milliseconds(run.p95LatencyNanos)} | ${milliseconds(run.baseProcessCpuNanos)} → ` +
            `${milliseconds(run.processCpuNanos)} | ${gibibytes(run.basePeakUsedHeapBytes)} → ` +
            `${gibibytes(run.peakUsedHeapBytes)} | ${gibibytes(run.basePeakResidentSetBytes)} → ` +
            `${gibibytes(run.peakResidentSetBytes)} |`
        ),
        "",
        "| Pair | Latency metric | Base | Candidate | Regression | Material sample |",
        "| ---: | :--- | ---: | ---: | ---: | :---: |",
        ...latencyMeasurements.map((measurement) =>
            `| ${measurement.pair} | ${measurement.metric} | ${milliseconds(measurement.base)} | ` +
            `${milliseconds(measurement.candidate)} | ${measurement.regressionPercent.toFixed(2)}% | ` +
            `${measurement.material ? "yes" : "no"} |`
        ),
        "",
        comparison.passed ? "**Result: PASS**" : `**Result: FAIL**\n\n${comparison.errors.join("\n")}`,
        ""
    ];
    return lines.join("\n");
}

function validSha(value) {
    return typeof value === "string" && /^[0-9a-f]{40}$/.test(value);
}

export function combineDualBaselineComparisons({
    goal,
    current,
    goalBaseSha,
    currentBaseSha,
    candidateSha,
    goalExitCode = 0,
    currentExitCode = 0,
    infrastructureErrors = []
}) {
    const errors = [...infrastructureErrors];
    for (const [name, value] of [
        ["goal base", goalBaseSha],
        ["current base", currentBaseSha],
        ["candidate", candidateSha]
    ]) {
        if (!validSha(value)) errors.push(`${name} SHA must be an exact 40-character commit id`);
    }
    if (goalBaseSha !== GLOBAL_WIDE_GOAL_BASE_SHA) {
        errors.push(`goal baseline must remain frozen at v2.4.7 ${GLOBAL_WIDE_GOAL_BASE_SHA}`);
    }
    if (currentBaseSha === candidateSha) errors.push("current base and candidate SHAs must differ");
    for (const [name, exitCode] of [["v2.4.7 goal", goalExitCode], ["current-main", currentExitCode]]) {
        if (!Number.isSafeInteger(exitCode) || exitCode < 0) errors.push(`${name} driver exit status is invalid`);
        else if (exitCode !== 0) errors.push(`${name} driver exited with status ${exitCode}`);
    }

    if (goal === null || typeof goal !== "object") {
        errors.push("v2.4.7 goal status is missing or invalid");
    } else {
        if (goal.minimumSpeedup !== 10) errors.push("v2.4.7 goal must enforce an exact 10x minimum");
        if (goal.passed !== true) {
            const details = Array.isArray(goal.errors) ? goal.errors : ["status did not pass"];
            errors.push(...details.map((error) => `v2.4.7 goal: ${error}`));
        }
    }
    if (current === null || typeof current !== "object") {
        errors.push("current-main status is missing or invalid");
    } else {
        if (current.comparison !== "current-main-non-regression") {
            errors.push("current-main status does not use the non-regression policy");
        }
        if (current.passed !== true) {
            const details = Array.isArray(current.errors) ? current.errors : ["status did not pass"];
            errors.push(...details.map((error) => `current-main: ${error}`));
        }
    }

    return {
        comparison: "global-wide-dual-baseline",
        passed: errors.length === 0,
        errors,
        goalBaseSha,
        currentBaseSha,
        candidateSha,
        goal,
        current
    };
}

function demoteHeading(report) {
    return report.trim().replace(/^### /, "#### ");
}

export function renderDualBaselineReport(combined, goalReport, currentReport) {
    return [
        "### 64 fixture-derived global wide-query dual-baseline gate",
        "",
        `- Frozen 10x goal base (v2.4.7): \`${combined.goalBaseSha}\``,
        `- Current PR base non-regression reference: \`${combined.currentBaseSha}\``,
        `- Candidate: \`${combined.candidateSha}\``,
        "",
        demoteHeading(goalReport || "### Frozen v2.4.7 goal\n\nReport unavailable."),
        "",
        demoteHeading(currentReport || "### Current-main non-regression\n\nReport unavailable."),
        "",
        combined.passed ? "**Dual-baseline result: PASS**" :
            `**Dual-baseline result: FAIL**\n\n${combined.errors.join("\n")}`,
        ""
    ].join("\n");
}

function parseArgs(argv) {
    const parsed = { _: [] };
    for (let index = 0; index < argv.length; index += 1) {
        const value = argv[index];
        if (!value.startsWith("--")) {
            parsed._.push(value);
            continue;
        }
        const name = value.slice(2);
        const next = argv[index + 1];
        if (next === undefined || next.startsWith("--")) parsed[name] = true;
        else {
            parsed[name] = next;
            index += 1;
        }
    }
    return parsed;
}

function requireArg(args, name) {
    const value = args[name];
    if (typeof value !== "string" || value.length === 0) throw new Error(`Missing --${name}`);
    return value;
}

function commaSeparatedFiles(args, name) {
    return requireArg(args, name).split(",").map((value) => value.trim()).filter(Boolean);
}

function readJson(file) {
    return JSON.parse(fs.readFileSync(file, "utf8"));
}

function writeJson(file, value) {
    fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function compareCurrentCommand(args) {
    const baseFiles = commaSeparatedFiles(args, "bases");
    const candidateFiles = commaSeparatedFiles(args, "candidates");
    const baseObservationFiles = commaSeparatedFiles(args, "base-observations");
    const candidateObservationFiles = commaSeparatedFiles(args, "candidate-observations");
    const structural = compareGlobalWidePressure(
        baseFiles.map(readJson),
        candidateFiles.map(readJson),
        baseObservationFiles.map((file) => fs.readFileSync(file, "utf8")),
        candidateObservationFiles.map((file) => fs.readFileSync(file, "utf8")),
        fs.readFileSync(requireArg(args, "correctness-oracle"), "utf8"),
        0,
        requireArg(args, "run-orders").split(",").map((value) => value.trim()).filter(Boolean),
        fs.readFileSync(requireArg(args, "graph-manifest"), "utf8")
    );
    const comparison = applyCurrentMainNonRegressionPolicy(structural);
    fs.writeFileSync(requireArg(args, "report"), renderCurrentMainNonRegressionReport(comparison));
    writeJson(requireArg(args, "status"), comparison);
    if (!comparison.passed) process.exitCode = 1;
}

function readOptionalJson(file, label, errors) {
    try {
        return readJson(file);
    } catch (error) {
        errors.push(`${label}: ${error.message}`);
        return null;
    }
}

function readOptionalText(file, label, errors) {
    try {
        return fs.readFileSync(file, "utf8");
    } catch (error) {
        errors.push(`${label}: ${error.message}`);
        return "";
    }
}

function combineCommand(args) {
    const infrastructureErrors = [];
    const goalStatusFile = requireArg(args, "goal-status");
    const currentStatusFile = requireArg(args, "current-status");
    const goalReport = readOptionalText(requireArg(args, "goal-report"), "v2.4.7 goal report", infrastructureErrors);
    const currentReport = readOptionalText(
        requireArg(args, "current-report"),
        "current-main report",
        infrastructureErrors
    );
    const combined = combineDualBaselineComparisons({
        goal: readOptionalJson(goalStatusFile, "v2.4.7 goal status", infrastructureErrors),
        current: readOptionalJson(currentStatusFile, "current-main status", infrastructureErrors),
        goalBaseSha: requireArg(args, "goal-base-sha"),
        currentBaseSha: requireArg(args, "current-base-sha"),
        candidateSha: requireArg(args, "candidate-sha"),
        goalExitCode: Number(requireArg(args, "goal-exit-code")),
        currentExitCode: Number(requireArg(args, "current-exit-code")),
        infrastructureErrors
    });
    fs.writeFileSync(
        requireArg(args, "report"),
        renderDualBaselineReport(combined, goalReport, currentReport)
    );
    writeJson(requireArg(args, "status"), combined);
    if (!combined.passed) process.exitCode = 1;
}

function main(argv) {
    const args = parseArgs(argv);
    if (args._[0] === "compare-current") compareCurrentCommand(args);
    else if (args._[0] === "combine") combineCommand(args);
    else throw new Error(`Unknown command: ${args._[0] ?? "<missing>"}`);
}

if (process.argv[1] !== undefined &&
    fs.realpathSync(fileURLToPath(import.meta.url)) === fs.realpathSync(process.argv[1])
) {
    try {
        main(process.argv.slice(2));
    } catch (error) {
        console.error(error.stack ?? error.message);
        process.exitCode = 1;
    }
}
